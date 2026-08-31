"""
Cross-Encoder 重排序服务
基于 bge-reranker-v2-m3 模型，提供 HTTP 接口
兼容 Python 3.8+
"""

import os
import sys
import time
import logging
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("reranker")

app = FastAPI(title="BGE Reranker Service", version="1.0.0")

# 全局模型对象，启动时加载
_reranker_model = None


# ========== 请求/响应模型 ==========

class RerankRequest(BaseModel):
    query: str
    documents: List[str]
    top_k: Optional[int] = None  # 不传则返回全部排序结果
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
    """加载重排序模型，支持从本地或HuggingFace下载"""
    global _reranker_model

    if _reranker_model is not None:
        return _reranker_model

    logger.info("正在加载重排序模型: %s", model_name)
    start_time = time.time()

    try:
        from FlagEmbedding import FlagReranker

        # 自动判断设备
        if device is None:
            try:
                import torch
                device = "cuda" if torch.cuda.is_available() else "cpu"
            except ImportError:
                device = "cpu"

        logger.info("使用设备: %s", device)

        # use_fp16 在 CPU 下不适用，仅 CUDA 开启
        use_fp16 = (device == "cuda")

        _reranker_model = FlagReranker(
            model_name,
            use_fp16=use_fp16,
            device=device,
        )

        elapsed = time.time() - start_time
        logger.info("模型加载完成，耗时 %.2f 秒", elapsed)
        return _reranker_model

    except Exception as e:
        logger.error("模型加载失败: %s", str(e))
        raise


def get_reranker():
    """获取已加载的模型实例"""
    if _reranker_model is None:
        raise RuntimeError("模型尚未加载，请先调用 load_model()")
    return _reranker_model


# ========== API 接口 ==========

@app.get("/health")
def health_check():
    """健康检查接口"""
    return {
        "status": "ok" if _reranker_model is not None else "loading",
        "model_loaded": _reranker_model is not None,
    }


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest):
    """
    重排序接口
    输入查询和文档列表，返回按相关性从高到低排序的结果
    """
    start_time = time.time()

    if not request.query:
        raise HTTPException(status_code=400, detail="query 不能为空")
    if not request.documents:
        return RerankResponse(results=[], total=0, took_ms=0.0)

    try:
        reranker = get_reranker()

        # 构建 (query, doc) 对
        pairs = [[request.query, doc] for doc in request.documents]

        # 调用模型计算分数（返回 list of float）
        scores = reranker.compute_score(pairs, normalize=True)

        # 确保 scores 是列表（单条时可能返回标量）
        if not isinstance(scores, list):
            scores = [float(scores)]

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
                relevance_score=item["score"],
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
    # 从环境变量读取配置
    model_name = os.environ.get("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3")
    host = os.environ.get("RERANKER_HOST", "0.0.0.0")
    port = int(os.environ.get("RERANKER_PORT", "8081"))
    device = os.environ.get("RERANKER_DEVICE", None)  # auto

    # 启动前预加载模型
    try:
        load_model(model_name, device)
    except Exception as e:
        logger.error("启动失败，模型加载异常: %s", str(e))
        sys.exit(1)

    logger.info("启动重排序服务: http://%s:%d", host, port)
    uvicorn.run(app, host=host, port=port, log_level="info")
