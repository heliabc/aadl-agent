#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
两阶段端到端 Demo
==================
完整演示：自然语言需求 → JSON 架构 → AADL 代码

阶段一：Outlines JSON Schema 约束（llguidance 后端）生成架构 JSON
阶段二：Outlines CFG 约束（llguidance 后端）生成 AADL 代码

用法:
  python two_stage_demo.py --model ~/models/qwen3-14b \
      --requirement "设计一个飞行控制系统" \
      --grammar grammars/aadl_core.lark \
      --output-dir output/two_stage

  # 使用已有架构 JSON，跳过阶段一
  python two_stage_demo.py --model ~/models/qwen3-14b \
      --arch-json existing_arch.json \
      --grammar grammars/aadl_core.lark \
      --skip-stage1

  # 仅运行阶段一
  python two_stage_demo.py --model ~/models/qwen3-14b \
      --requirement "xxx" --stage1-only
"""

import os
import sys
import json
import time
import argparse
from pathlib import Path


# ============================================================
# 阶段一：JSON 架构生成
# ============================================================

def run_stage1(model, tokenizer, requirement: str,
               max_tokens: int = 2048,
               temperature: float = 0.0,
               backend: str = "llguidance",
               max_retries: int = 2) -> dict:
    """运行阶段一：需求 → JSON 架构"""
    from json_generator import build_prompt, generate_json, validate_and_fix

    print()
    print("╔" + "═" * 58 + "╗")
    print("║  阶段一：JSON 架构生成" + " " * 36 + "║")
    print("╚" + "═" * 58 + "╝")
    print()

    prompt = build_prompt(requirement)

    # 生成
    arch = generate_json(
        model, tokenizer, prompt,
        max_tokens=max_tokens,
        temperature=temperature,
        backend=backend,
    )

    # 语义校验 + 自动修复
    arch, result = validate_and_fix(
        model, tokenizer, arch, requirement,
        max_retries=max_retries,
    )

    print()
    print("阶段一结果:")
    print(f"  组件数: {len(arch.get('components', []))}")
    print(f"  语义校验: {'✅ 通过' if result.valid else '❌ 未通过'}")
    if result.errors:
        print(f"  错误数: {len(result.errors)}")
    if result.warnings:
        print(f"  警告数: {len(result.warnings)}")

    return arch


# ============================================================
# 阶段二：AADL 代码生成
# ============================================================

def run_stage2(model, tokenizer, arch: dict, grammar_text: str,
               max_tokens: int = 4096,
               temperature: float = 0.7,
               backend: str = "llguidance") -> str:
    """运行阶段二：JSON 架构 → AADL 代码"""
    from aadl_generator import (
        build_generation_prompt, generate_aadl,
    )
    from symbol_table import build_symbol_table

    print()
    print("╔" + "═" * 58 + "╗")
    print("║  阶段二：AADL 代码生成" + " " * 34 + "║")
    print("╚" + "═" * 58 + "╝")
    print()

    # 预构建符号表
    st = build_symbol_table(arch)
    print(f"[Symbol Table] {len(st.component_types)} 个类型, "
          f"{len(st.component_impls)} 个实现")

    # 构建 prompt
    prompt = build_generation_prompt(arch)
    print(f"[Prompt] {len(prompt)} 字符")

    # 停止词
    stop_text = f"end {arch['package_name']};"

    # 生成 AADL
    result = generate_aadl(
        model=model,
        tokenizer=tokenizer,
        grammar_text=grammar_text,
        prompt=prompt,
        max_tokens=max_tokens,
        temperature=temperature,
        stop_text=stop_text,
        backend=backend,
    )

    return result


# ============================================================
# 结果验证
# ============================================================

def validate_aadl_output(aadl_code: str, grammar_text: str) -> dict:
    """验证生成的 AADL 代码"""
    result = {
        "grammar_valid": False,
        "validator_passed": False,
        "errors": [],
    }

    # 1. Lark 语法解析验证
    try:
        from lark import Lark
        parser = Lark(grammar_text, start="start")
        parser.parse(aadl_code)
        result["grammar_valid"] = True
    except Exception as e:
        result["errors"].append(f"语法解析错误: {e}")

    # 2. 本地验证器（如果可用）
    try:
        sys.path.insert(0, os.path.dirname(__file__))
        from aadl_v8_validator import validate_aadl
        val_result = validate_aadl(aadl_code)
        if val_result.get("valid"):
            result["validator_passed"] = True
        else:
            for err in val_result.get("errors", []):
                result["errors"].append(f"验证器: {err}")
    except ImportError:
        pass  # 验证器不可用时跳过
    except Exception as e:
        result["errors"].append(f"验证器异常: {e}")

    return result


# ============================================================
# 主函数
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description="两阶段端到端 Demo：需求 → JSON 架构 → AADL 代码"
    )
    parser.add_argument("--model", default="~/autodl-tmp/qwen3-14b",
                        help="HuggingFace 模型名或本地路径")
    parser.add_argument("--requirement", default=None,
                        help="需求描述文本")
    parser.add_argument("--requirement-file", default=None,
                        help="从文件读取需求")
    parser.add_argument("--arch-json", default=None,
                        help="已有架构 JSON（跳过阶段一或用于阶段二）")
    parser.add_argument("--grammar", default="grammars/aadl_core.lark",
                        help="Lark EBNF 语法文件")
    parser.add_argument("--output-dir", default="output/two_stage",
                        help="输出目录")
    parser.add_argument("--max-tokens-stage1", type=int, default=2048)
    parser.add_argument("--max-tokens-stage2", type=int, default=4096)
    parser.add_argument("--temperature-stage1", type=float, default=0.0)
    parser.add_argument("--temperature-stage2", type=float, default=0.7)
    parser.add_argument("--load-in-4bit", action="store_true")
    parser.add_argument("--load-in-8bit", action="store_true")
    parser.add_argument("--backend", default="llguidance",
                        choices=["llguidance", "xgrammar", "outlines_core"])
    parser.add_argument("--skip-stage1", action="store_true",
                        help="跳过阶段一（直接用 --arch-json 跑阶段二）")
    parser.add_argument("--stage1-only", action="store_true",
                        help="只跑阶段一")
    parser.add_argument("--max-retries", type=int, default=2,
                        help="阶段一最大修复重试次数")
    args = parser.parse_args()

    # --- 参数检查 ---
    if args.skip_stage1 and not args.arch_json:
        print("错误：--skip-stage1 需要同时指定 --arch-json")
        sys.exit(1)

    if not args.skip_stage1 and not args.stage1_only \
            and not args.requirement and not args.requirement_file:
        print("错误：请指定 --requirement 或 --requirement-file")
        sys.exit(1)

    # --- 读取需求 ---
    requirement = None
    if args.requirement_file:
        with open(args.requirement_file, "r", encoding="utf-8") as f:
            requirement = f.read().strip()
    elif args.requirement:
        requirement = args.requirement

    # --- 创建输出目录 ---
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # --- 加载语法 ---
    from aadl_generator import load_grammar, verify_grammar
    grammar_text = load_grammar(args.grammar)
    verify_grammar(grammar_text)

    # --- 加载模型（两阶段共用） ---
    if not args.stage1_only or not args.skip_stage1:
        from aadl_generator import load_model
        model, tokenizer = load_model(
            args.model,
            load_in_4bit=args.load_in_4bit,
            load_in_8bit=args.load_in_8bit,
        )

    total_start = time.time()

    # --- 阶段一 ---
    arch = None
    if not args.skip_stage1:
        arch = run_stage1(
            model, tokenizer, requirement,
            max_tokens=args.max_tokens_stage1,
            temperature=args.temperature_stage1,
            backend=args.backend,
            max_retries=args.max_retries,
        )

        # 保存架构 JSON
        arch_path = output_dir / "architecture.json"
        with open(arch_path, "w", encoding="utf-8") as f:
            json.dump(arch, f, indent=2, ensure_ascii=False)
        print(f"\n[Output] 架构 JSON 已保存: {arch_path}")

    elif args.arch_json:
        with open(args.arch_json, "r", encoding="utf-8") as f:
            arch = json.load(f)
        print(f"[Input] 加载已有架构: {arch['package_name']}")

    # --- 阶段二 ---
    if not args.stage1_only and arch is not None:
        aadl_code = run_stage2(
            model, tokenizer, arch, grammar_text,
            max_tokens=args.max_tokens_stage2,
            temperature=args.temperature_stage2,
            backend=args.backend,
        )

        # 保存 AADL 代码
        aadl_path = output_dir / f"{arch['package_name']}.aadl"
        with open(aadl_path, "w", encoding="utf-8") as f:
            f.write(aadl_code)
        print(f"\n[Output] AADL 代码已保存: {aadl_path}")

        # 验证结果
        print()
        print("=" * 60)
        print("结果验证")
        print("=" * 60)
        val_result = validate_aadl_output(aadl_code, grammar_text)
        print(f"语法解析: {'✅ 通过' if val_result['grammar_valid'] else '❌ 失败'}")
        print(f"验证器检测: {'✅ 通过' if val_result['validator_passed'] else '❌ 有错误'}")
        if val_result["errors"]:
            print(f"错误详情 ({len(val_result['errors'])} 个):")
            for e in val_result["errors"]:
                print(f"  ✗ {e}")

    # --- 总耗时 ---
    total_time = time.time() - total_start
    print()
    print("=" * 60)
    print(f"总耗时: {total_time:.1f}s")
    print(f"输出目录: {output_dir.resolve()}")
    print("=" * 60)


if __name__ == "__main__":
    main()
