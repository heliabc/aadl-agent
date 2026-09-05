"""
Cross-Encoder 重排序服务（纯 transformers 实现，轻量版）
基于 bge-reranker-v2-m3 模型，提供 HTTP 接口
"""

import os
import sys
import time
import logging
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn
import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("reranker")

app = FastAPI(title="BGE Reranker Service", version="1.0.0")

# 全局模型对象，启动时加载
_model = None
_tokenizer = None
_device = None


# ========== 请求/响应模型 ==========

class RerankRequest(BaseModel):
    query: str
    documents: List[str]
    top_k: Optional[int] = None
    return_documents: Optional[bool] = True


class RerankItem(BaseModel):
    index: int
    relevance_score: float
    document: Optional[str] = None


class RerankResponse(BaseModel):
    results: List[RerankItem]
    total: int
    took_ms: float


# ========== 模型加载 ==========

def load_model(model_name: str = "BAAI/bge-reranker-v2-m3", device: str = None):
    """加载重排序模型"""
    global _model, _tokenizer, _device

    if _model is not None:
        return

    logger.info("正在加载重排序模型: %s", model_name)
    start_time = time.time()

    try:
        # 自动判断设备
        if device is None:
            device = "cuda" if torch.cuda.is_available() else "cpu"
        _device = device
        logger.info("使用设备: %s", device)

        _tokenizer = AutoTokenizer.from_pretrained(model_name)
        _model = AutoModelForSequenceClassification.from_pretrained(model_name)
        _model.to(device)
        _model.eval()

        elapsed = time.time() - start_time
        logger.info("模型加载完成，耗时 %.2f 秒", elapsed)

    except Exception as e:
        logger.error("模型加载失败: %s", str(e))
        raise


def get_model():
    if _model is None:
        raise RuntimeError("模型尚未加载")
    return _model, _tokenizer, _device


# ========== API 接口 ==========

@app.get("/health")
def health_check():
    return {
        "status": "ok" if _model is not None else "loading",
        "model_loaded": _model is not None,
    }


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest):
    start_time = time.time()

    if not request.query:
        raise HTTPException(status_code=400, detail="query 不能为空")
    if not request.documents:
        return RerankResponse(results=[], total=0, took_ms=0.0)

    try:
        model, tokenizer, device = get_model()

        # 构建 (query, doc) 对
        pairs = [[request.query, doc] for doc in request.documents]

        # tokenize
        inputs = tokenizer(
            pairs,
            padding=True,
            truncation=True,
            max_length=512,
            return_tensors="pt",
        ).to(device)

        # 推理
        with torch.no_grad():
            logits = model(**inputs).logits.squeeze(-1)
            # sigmoid 归一化到 0-1
            scores = torch.sigmoid(logits).cpu().numpy().tolist()

        # 按分数降序排序，记录原始索引
        indexed_scores = [
            {"index": i, "score": float(score)}
            for i, score in enumerate(scores)
        ]
        indexed_scores.sort(key=lambda x: x["score"], reverse=True)

        # top_k 截断
        top_k = request.top_k if request.top_k and request.top_k > 0 else len(indexed_scores)
        top_k = min(top_k, len(indexed_scores))
        top_results = indexed_scores[:top_k]

        # 组装响应
        results = []
        for item in top_results:
            result = RerankItem(
                index=item["index"],
                relevance_score=round(item["score"], 6),
            )
            if request.return_documents:
                result.document = request.documents[item["index"]]
            results.append(result)

        elapsed_ms = (time.time() - start_time) * 1000

        logger.info(
            "重排序完成: %d 条 → top %d, 耗时 %.1f ms",
            len(request.documents), top_k, elapsed_ms
        )

        return RerankResponse(
            results=results,
            total=len(results),
            took_ms=round(elapsed_ms, 1),
        )

    except Exception as e:
        logger.error("重排序失败: %s", str(e), exc_info=True)
        raise HTTPException(status_code=500, detail=f"重排序失败: {str(e)}")


# ========== 启动入口 ==========

if __name__ == "__main__":
    model_name = os.environ.get("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3")
    host = os.environ.get("RERANKER_HOST", "0.0.0.0")
    port = int(os.environ.get("RERANKER_PORT", "8081"))
    device = os.environ.get("RERANKER_DEVICE", None)

    try:
        load_model(model_name, device)
    except Exception as e:
        logger.error("启动失败，模型加载异常: %s", str(e))
        sys.exit(1)

    logger.info("启动重排序服务: http://%s:%d", host, port)
    uvicorn.run(app, host=host, port=port, log_level="info")
