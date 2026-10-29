#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
属性文法约束 LLM 生成测试
=========================
Outlines CFG 约束 + 属性文法精确上下文检测

验证目标：
  1. 精确上下文检测器在真实 LLM 逐 token 生成过程中能否正确检测约束位置
  2. 检测到的约束位置是否和实际语义对应
  3. 为后续真正的语义约束过滤打基础

用法:
  python test_llm_generation.py --model /root/sj-fs/qwen3-14b \
      --grammar grammars/aadl_mvp.lark \
      --max-tokens 512
"""

import os
import sys
import time
import argparse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))


def load_model(model_name: str):
    """加载模型（和 aadl_outlines_demo.py 一致）"""
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    print(f"[Model] 正在加载: {model_name}")
    t0 = time.time()

    tokenizer = AutoTokenizer.from_pretrained(model_name)

    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        device_map="auto",
        dtype=torch.float16,
    )

    try:
        vram = torch.cuda.memory_allocated() / 1024**3
        print(f"[Model] 加载完成，耗时 {time.time()-t0:.1f}s，显存 {vram:.1f} GB")
    except Exception:
        print(f"[Model] 加载完成，耗时 {time.time()-t0:.1f}s")

    return model, tokenizer


def build_chat_prompt(user_content: str, tokenizer,
                      system_content: str = "你是 AADL 代码生成专家，严格按语法输出完整的 AADL 代码。") -> str:
    """用模型的 chat template 包装"""
    messages = [
        {"role": "system", "content": system_content},
        {"role": "user", "content": user_content},
    ]
    text = tokenizer.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=True
    )
    return text


class ContextDetectionLogitsProcessor:
    """
    上下文检测 LogitsProcessor：
    不实际过滤 token，只在每一步检测当前语义上下文并记录，
    用于验证精确上下文检测器的正确性。
    """

    def __init__(self, detector, tokenizer, input_len: int):
        self.detector = detector
        self.tokenizer = tokenizer
        self.input_len = input_len
        self.detection_log = []  # (step, ctx_type, info, new_token_text)
        self._last_ctx = "none"

    def __call__(self, input_ids, scores):
        step = input_ids.shape[1] - self.input_len

        # 只解码生成部分（跳过 prompt）
        generated_ids = input_ids[0][self.input_len:]
        text = self.tokenizer.decode(generated_ids, skip_special_tokens=True)

        # 检测当前上下文
        ctx, env = self.detector.detect(text)

        # 获取当前 token 的文本（最后一个 token）
        if step > 0:
            last_token_id = generated_ids[-1]
            last_token_text = self.tokenizer.decode([last_token_id], skip_special_tokens=True)
        else:
            last_token_text = ""

        # 记录上下文变化（进入新约束位置时记录）
        if ctx.context_type != self._last_ctx:
            self.detection_log.append((
                step, ctx.context_type, dict(ctx.info), last_token_text, text[:80]
            ))
            self._last_ctx = ctx.context_type

        # 不过滤，原样返回 scores
        return scores


def run_test(model, tokenizer, grammar_path: str, prompt: str,
             max_tokens: int = 512, temperature: float = 0.0,
             backend_name: str = None):
    """
    运行生成测试，同时记录上下文检测结果
    """
    import torch
    import outlines
    from outlines.generator import get_cfg_logits_processor
    from core.context_detector import ContextDetector

    # 加载文法
    with open(grammar_path, "r", encoding="utf-8") as f:
        grammar_text = f.read()
    print(f"[Grammar] 已加载: {grammar_path} ({len(grammar_text)} 字符)")

    # 初始化精确上下文检测器
    detector = ContextDetector(grammar_path)
    print("[Detector] 精确上下文检测器已初始化")

    # 获取 CFG logits processor
    print("[Outlines] 编译 FSM...")
    t0 = time.time()
    outlines_model = outlines.from_transformers(model, tokenizer)
    cfg_lp = get_cfg_logits_processor(
        backend_name=backend_name,
        model=outlines_model,
        grammar=grammar_text,
    )
    print(f"[Outlines] FSM 编译完成，耗时 {time.time()-t0:.1f}s")

    # 编码 prompt
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
    input_len = inputs.input_ids.shape[1]

    # 创建上下文检测 processor
    ctx_lp = ContextDetectionLogitsProcessor(detector, tokenizer, input_len)

    # 组合 logits processors（CFG 先，检测后）
    if isinstance(cfg_lp, (list, tuple)):
        lp_list = list(cfg_lp) + [ctx_lp]
    else:
        lp_list = [cfg_lp, ctx_lp]

    # 生成参数
    gen_kwargs = {
        "max_new_tokens": max_tokens,
        "logits_processor": lp_list,
        "pad_token_id": tokenizer.eos_token_id,
    }
    if temperature > 0:
        gen_kwargs["do_sample"] = True
        gen_kwargs["temperature"] = temperature
        gen_kwargs["top_p"] = 0.9
    else:
        gen_kwargs["do_sample"] = False

    # 生成
    print(f"\n[Generate] 开始生成 (max_tokens={max_tokens}, temp={temperature})...")
    print("─" * 60)
    t1 = time.time()

    with torch.no_grad():
        outputs = model.generate(**inputs, **gen_kwargs)

    gen_time = time.time() - t1
    generated_ids = outputs[0][input_len:]
    result = tokenizer.decode(generated_ids, skip_special_tokens=True)

    # 去掉 thinking 块
    import re
    result_clean = re.sub(r'<think>.*?</think>', '', result, flags=re.DOTALL).strip()

    print("─" * 60)
    num_tokens = len(generated_ids)
    speed = num_tokens / gen_time if gen_time > 0 else 0
    print(f"\n[Generate] 生成完成，{num_tokens} tokens，耗时 {gen_time:.1f}s ({speed:.1f} tokens/s)")

    return result_clean, ctx_lp.detection_log


def print_detection_log(detection_log):
    """打印上下文检测日志"""
    print("\n" + "=" * 60)
    print("  精确上下文检测日志")
    print("=" * 60)

    if not detection_log:
        print("  （未检测到任何约束上下文）")
        return

    print(f"  共检测到 {len(detection_log)} 次上下文变化：")
    print()

    for i, (step, ctx_type, info, token, text_preview) in enumerate(detection_log):
        info_str = ", ".join(f"{k}={v}" for k, v in info.items()) if info else "无"
        token_repr = repr(token) if token else "(起始)"
        print(f"  [{i+1}] step {step:4d} | {ctx_type:30s} | token={token_repr}")
        print(f"       info: {info_str}")
        print(f"       生成文本预览: {repr(text_preview)}")


def verify_semantic(code: str, grammar_path: str) -> dict:
    """验证生成代码的语义"""
    from core.attribute_parser import IncrementalAttributeParser

    result = {
        "parse_ok": False,
        "errors": [],
        "num_data_types": 0,
        "num_systems": 0,
        "num_subcomponents": 0,
        "num_connections": 0,
    }

    try:
        parser = IncrementalAttributeParser(grammar_path)
        env = parser.parse_full_text(code)
        result["parse_ok"] = True
        result["num_data_types"] = len(env.get_available_types_by_category("data"))
        result["num_systems"] = len(env.get_available_types_by_category("system"))
        result["num_subcomponents"] = len(env.get_subcomponent_names())
        result["num_connections"] = len(env.connections) if hasattr(env, 'connections') else 0
    except Exception as e:
        result["errors"].append(str(e))

    return result


def main():
    parser = argparse.ArgumentParser(description="属性文法约束 LLM 生成测试")
    parser.add_argument("--model", default="/root/sj-fs/qwen3-14b", help="模型路径")
    parser.add_argument("--grammar", default="grammars/aadl_mvp.lark", help="文法文件路径")
    parser.add_argument("--requirement", default="设计一个简单的飞行控制系统，包含传感器、控制器和它们之间的连接",
                        help="需求描述")
    parser.add_argument("--max-tokens", type=int, default=512, help="最大生成 token 数")
    parser.add_argument("--temperature", type=float, default=0.0, help="温度")
    parser.add_argument("--backend", default=None, help="CFG 后端 (llguidance/xgrammar/outlines_core)")
    parser.add_argument("--output-dir", default="output/llm_test", help="输出目录")
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # 加载模型
    model, tokenizer = load_model(args.model)

    # 构建 prompt
    prompt = build_chat_prompt(args.requirement, tokenizer)
    print(f"\n[Prompt] 长度: {len(prompt)} 字符")
    print(f"         {prompt[-100:]}")

    # 运行测试
    result, detection_log = run_test(
        model, tokenizer, args.grammar, prompt,
        max_tokens=args.max_tokens,
        temperature=args.temperature,
        backend_name=args.backend,
    )

    # 保存生成结果
    out_path = output_dir / "generated.aadl"
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(result)
    print(f"\n[输出] 已保存到 {out_path}")

    # 打印检测日志
    print_detection_log(detection_log)

    # 保存检测日志
    log_path = output_dir / "detection_log.txt"
    with open(log_path, "w", encoding="utf-8") as f:
        for step, ctx_type, info, token, text_preview in detection_log:
            info_str = ", ".join(f"{k}={v}" for k, v in info.items()) if info else "{}"
            f.write(f"step {step}: {ctx_type} | info={info_str} | token={repr(token)} | text={repr(text_preview)}\n")
    print(f"\n[输出] 检测日志已保存到 {log_path}")

    # 语义验证
    print("\n" + "=" * 60)
    print("  语义验证")
    print("=" * 60)
    verification = verify_semantic(result, args.grammar)
    print(f"  解析成功: {verification['parse_ok']}")
    if verification["errors"]:
        for err in verification["errors"]:
            print(f"  错误: {err}")
    print(f"  data 类型数: {verification['num_data_types']}")
    print(f"  system 类型数: {verification['num_systems']}")
    print(f"  子组件数: {verification['num_subcomponents']}")
    print(f"  连接数: {verification['num_connections']}")

    print("\n✅ 测试完成！")


if __name__ == "__main__":
    main()
