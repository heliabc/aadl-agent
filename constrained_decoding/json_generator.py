#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段一：JSON 架构生成器
=======================
使用 Outlines + llguidance 的 JSON Schema 约束，
从自然语言需求生成结构化的 AADL 架构描述 JSON。

核心特性：
- JSON Schema 约束保证输出格式 100% 正确
- 生成后自动进行语义校验（包含规则、引用完整性等）
- 支持多轮修复：语义校验不通过时自动重试

用法:
  python json_generator.py --model ~/models/qwen3-14b \
      --requirement "设计一个飞行控制系统" \
      --output flight_control_arch.json

  # 仅验证 schema，不加载模型
  python json_generator.py --verify-only
"""

import os
import sys
import json
import time
import argparse
from typing import Optional

# 本地模块
from architecture_schema import (
    ArchitectureModel,
    get_json_schema_dict,
    validate_json as validate_json_structure,
)
from json_validator import validate_architecture, ValidationResult


# ============================================================
# 模型加载
# ============================================================

def load_model(model_name: str, load_in_4bit: bool = False,
               load_in_8bit: bool = False):
    """加载 HuggingFace 模型和 tokenizer"""
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    print(f"[Model] 正在加载: {model_name}")
    t0 = time.time()

    tokenizer = AutoTokenizer.from_pretrained(model_name)

    model_kwargs = {
        "device_map": "auto",
        "torch_dtype": torch.float16,
    }

    if load_in_4bit:
        try:
            from transformers import BitsAndBytesConfig
            model_kwargs["quantization_config"] = BitsAndBytesConfig(
                load_in_4bit=True,
                bnb_4bit_compute_dtype=torch.float16,
            )
            print("[Model] 使用 4bit 量化（BitsAndBytesConfig）")
        except ImportError:
            print("[Model] 警告: bitsandbytes 未安装，使用 fp16")

    if load_in_8bit:
        try:
            from transformers import BitsAndBytesConfig
            model_kwargs["quantization_config"] = BitsAndBytesConfig(
                load_in_8bit=True,
            )
            print("[Model] 使用 8bit 量化（BitsAndBytesConfig）")
        except ImportError:
            pass

    model = AutoModelForCausalLM.from_pretrained(model_name, **model_kwargs)

    elapsed = time.time() - t0
    try:
        import torch
        vram = torch.cuda.memory_allocated() / 1024**3
        print(f"[Model] 加载完成，耗时 {elapsed:.1f}s，显存 {vram:.1f} GB")
    except Exception:
        print(f"[Model] 加载完成，耗时 {elapsed:.1f}s")

    return model, tokenizer


# ============================================================
# Prompt 构建
# ============================================================

def build_prompt(requirement: str) -> str:
    """构建阶段一的 prompt：需求 → JSON 架构"""
    return f"""你是一个 AADL 架构设计专家。请根据以下需求，设计一个完整的 AADL 架构，并以 JSON 格式输出。

需求描述：
{requirement}

设计要求：
1. 架构应包含完整的组件类型（component type）和组件实现（component implementation）
2. 每个组件需要定义端口（features），包括方向、种类和数据类型
3. 子组件的包含关系必须符合 AADL 标准（如 system 包含 process，process 包含 thread）
4. 连接定义中源端必须是 out/in out 端口，目标端必须是 in/in out 端口
5. 所有分类器引用必须指向已定义的组件
6. 常用数据类型使用 Base_Types 包（如 Base_Types::Float）

请严格按照 JSON Schema 输出架构描述，不要输出任何额外的解释或 markdown 标记。
直接输出 JSON 对象。"""


# ============================================================
# JSON 生成（Outlines + llguidance）
# ============================================================

def generate_json(model, tokenizer, prompt: str,
                  max_tokens: int = 2048,
                  temperature: float = 0.0,
                  backend: str = "llguidance",
                  show_thinking: bool = False) -> dict:
    """
    使用 Outlines 的 JSON Schema 约束生成架构 JSON。

    Args:
        model: HF 模型
        tokenizer: HF tokenizer
        prompt: 输入提示词
        max_tokens: 最大生成 token 数
        temperature: 采样温度（0 = 贪心）
        backend: 约束后端（llguidance / xgrammar / outlines_core）
        show_thinking: 是否保留 thinking 块（Qwen3 等模型）

    Returns:
        生成的 JSON dict
    """
    import outlines

    print("[JSON Gen] 初始化 Outlines JSON 生成器...")
    t0 = time.time()

    # 包装模型为 outlines 的 Transformers 模型
    outlines_model = outlines.from_transformers(model, tokenizer)

    # 获取 JSON Schema
    schema = get_json_schema_dict()

    # 创建 JSON 生成器（底层自动用 llguidance 做约束）
    generator = outlines.generate.json(
        outlines_model,
        ArchitectureModel,  # Pydantic model → 自动转 JSON Schema
        max_tokens=max_tokens,
        temperature=temperature if temperature > 0 else None,
    )

    init_time = time.time() - t0
    print(f"[JSON Gen] 生成器初始化完成，耗时 {init_time:.1f}s（后端: {backend}）")
    print(f"[JSON Gen] 开始生成 (max_tokens={max_tokens}, temp={temperature})...")
    print()
    print("─" * 60)

    t1 = time.time()

    # 生成 JSON（Outlines 内部处理约束解码）
    result = generator(prompt)

    gen_time = time.time() - t1

    print("─" * 60)
    print(f"\n[JSON Gen] 生成完成，耗时 {gen_time:.1f}s")

    # Pydantic model → dict
    if hasattr(result, 'model_dump'):
        result_dict = result.model_dump()
    elif isinstance(result, dict):
        result_dict = result
    else:
        result_dict = json.loads(str(result))

    return result_dict


# ============================================================
# 语义校验 + 自动修复
# ============================================================

def validate_and_fix(model, tokenizer, arch: dict, requirement: str,
                     max_retries: int = 2) -> tuple[dict, ValidationResult]:
    """
    验证架构 JSON 的语义正确性，不通过时尝试修复。

    Args:
        model, tokenizer: 模型和 tokenizer
        arch: 初始生成的架构
        requirement: 原始需求
        max_retries: 最大重试次数

    Returns:
        (最终架构, 校验结果)
    """
    result = validate_architecture(arch)

    if result.valid:
        print("[Validate] ✅ 语义校验一次通过")
        return arch, result

    print(f"[Validate] ⚠️  语义校验发现 {len(result.errors)} 个错误")
    print(result.summary())

    current_arch = arch

    for attempt in range(max_retries):
        print(f"\n[Fix] 第 {attempt + 1}/{max_retries} 轮修复...")

        # 构建修复 prompt
        fix_prompt = _build_fix_prompt(requirement, current_arch, result.errors)

        # 重新生成
        try:
            fixed_arch = generate_json(
                model, tokenizer, fix_prompt,
                max_tokens=2048, temperature=0.3,
            )
        except Exception as e:
            print(f"[Fix] 修复失败: {e}")
            break

        # 重新校验
        result = validate_architecture(fixed_arch)
        current_arch = fixed_arch

        if result.valid:
            print(f"[Fix] ✅ 第 {attempt + 1} 轮修复成功")
            break
        else:
            print(f"[Fix] 仍有 {len(result.errors)} 个错误")
            print(result.summary())

    return current_arch, result


def _build_fix_prompt(requirement: str, arch: dict, errors: list) -> str:
    """构建修复提示词"""
    error_text = "\n".join(f"  - {e}" for e in errors)
    arch_json = json.dumps(arch, indent=2, ensure_ascii=False)

    return f"""你是一个 AADL 架构设计专家。之前的架构设计存在语义错误，请根据错误列表修复。

原始需求：
{requirement}

当前架构（有错误）：
{arch_json}

错误列表：
{error_text}

请修复上述错误，输出完整的修正后架构 JSON。
要求：
1. 修复所有列出的错误
2. 保持架构的整体结构和设计意图不变
3. 严格按照 JSON Schema 输出

直接输出 JSON 对象，不要任何解释。"""


# ============================================================
# 主函数
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description="阶段一：JSON 架构生成（Outlines + llguidance）"
    )
    parser.add_argument("--model", default="~/autodl-tmp/qwen3-14b",
                        help="HuggingFace 模型名或本地路径")
    parser.add_argument("--requirement", default=None,
                        help="需求描述文本")
    parser.add_argument("--requirement-file", default=None,
                        help="从文件读取需求")
    parser.add_argument("--output", default=None,
                        help="保存 JSON 到文件")
    parser.add_argument("--max-tokens", type=int, default=2048)
    parser.add_argument("--temperature", type=float, default=0.0)
    parser.add_argument("--load-in-4bit", action="store_true")
    parser.add_argument("--load-in-8bit", action="store_true")
    parser.add_argument("--backend", default="llguidance",
                        choices=["llguidance", "xgrammar", "outlines_core"],
                        help="约束解码后端（默认 llguidance）")
    parser.add_argument("--verify-only", action="store_true",
                        help="仅验证 Schema，不加载模型")
    parser.add_argument("--no-fix", action="store_true",
                        help="不进行自动修复（只生成一次）")
    parser.add_argument("--max-retries", type=int, default=2,
                        help="最大修复重试次数")
    parser.add_argument("--show-thinking", action="store_true",
                        help="保留模型的 thinking 输出")
    args = parser.parse_args()

    # --- 仅验证模式 ---
    if args.verify_only:
        print("=" * 60)
        print("Schema 验证模式")
        print("=" * 60)
        schema = get_json_schema_dict()
        print(f"JSON Schema 大小: {len(json.dumps(schema))} 字符")
        print(f"顶层字段: {list(schema.get('properties', {}).keys())}")
        print()

        # 用测试数据验证
        test_arch = {
            "package_name": "TestPkg",
            "with_packages": ["Base_Types"],
            "components": [
                {
                    "name": "TestSys",
                    "category": "system",
                    "has_impl": True,
                    "ports": [
                        {"name": "in1", "direction": "in",
                         "kind": "data port", "data_type": "Base_Types::Float"},
                    ],
                },
                {
                    "name": "TestSys.impl",
                    "category": "system implementation",
                    "subcomponents": [],
                    "connections": [],
                },
            ],
        }
        ok, errs = validate_json_structure(test_arch)
        print(f"结构校验: {'通过 ✓' if ok else '失败 ✗'}")
        if errs:
            for e in errs:
                print(f"  ✗ {e}")

        result = validate_architecture(test_arch)
        print(f"语义校验: {'通过 ✓' if result.valid else '有问题'}")
        print(result.summary())
        return

    # --- 读取需求 ---
    if args.requirement_file:
        with open(args.requirement_file, "r", encoding="utf-8") as f:
            requirement = f.read().strip()
        print(f"[Input] 从文件读取需求: {args.requirement_file}")
    elif args.requirement:
        requirement = args.requirement
    else:
        print("错误：请指定 --requirement 或 --requirement-file")
        sys.exit(1)

    print(f"[Input] 需求长度: {len(requirement)} 字符")

    # --- 加载模型 ---
    model, tokenizer = load_model(
        args.model,
        load_in_4bit=args.load_in_4bit,
        load_in_8bit=args.load_in_8bit,
    )

    # --- 构建 prompt ---
    prompt = build_prompt(requirement)

    # --- 生成 JSON ---
    print()
    print("=" * 60)
    print("阶段一：JSON 架构生成")
    print("=" * 60)

    try:
        arch = generate_json(
            model, tokenizer, prompt,
            max_tokens=args.max_tokens,
            temperature=args.temperature,
            backend=args.backend,
            show_thinking=args.show_thinking,
        )
    except Exception as e:
        print(f"\n[Error] JSON 生成失败: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

    # --- 结构校验 ---
    ok, struct_errors = validate_json_structure(arch)
    if not ok:
        print("\n[Error] JSON 结构校验失败（理论上不应发生）:")
        for e in struct_errors:
            print(f"  ✗ {e}")
    else:
        print("\n[Validate] JSON 结构校验通过 ✓")

    # --- 语义校验 + 修复 ---
    if args.no_fix:
        result = validate_architecture(arch)
    else:
        arch, result = validate_and_fix(
            model, tokenizer, arch, requirement,
            max_retries=args.max_retries,
        )

    # --- 输出结果 ---
    print()
    print("=" * 60)
    print("最终架构 JSON:")
    print("=" * 60)
    output_json = json.dumps(arch, indent=2, ensure_ascii=False)
    print(output_json)
    print("=" * 60)

    print()
    print(result.summary())

    # --- 保存 ---
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(arch, f, indent=2, ensure_ascii=False)
        print(f"\n[Output] 已保存到: {args.output}")

    # 退出码
    sys.exit(0 if result.valid else 1)


if __name__ == "__main__":
    main()
