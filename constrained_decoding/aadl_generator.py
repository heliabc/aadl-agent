#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段二：AADL 代码生成器
========================
使用 Outlines + llguidance 的 CFG 约束，
从结构化 JSON 架构生成语法正确的 AADL 代码。

核心特性：
- Outlines CFG 约束（底层 llguidance）保证 AADL 语法 100% 正确
- 从 JSON 架构预构建符号表，为后续动态约束打基础
- 结构化 prompt 引导生成顺序（组件列表 → 端口明细 → 子组件 → 连接）
- 支持流式输出

用法:
  # 从 JSON 架构生成 AADL
  python aadl_generator.py --model ~/models/qwen3-14b \
      --arch-json flight_control_arch.json \
      --grammar grammars/aadl_core.lark \
      --output flight_control.aadl

  # 仅验证语法
  python aadl_generator.py --grammar grammars/aadl_core.lark --verify-only
"""

import os
import sys
import json
import time
import argparse
from typing import Optional, List

from symbol_table import SymbolTable, build_symbol_table


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
# 语法加载与验证
# ============================================================

def load_grammar(grammar_path: str) -> str:
    """加载 Lark EBNF 语法文件"""
    with open(grammar_path, "r", encoding="utf-8") as f:
        grammar = f.read()
    print(f"[Grammar] 已加载: {grammar_path} ({len(grammar)} 字符)")
    return grammar


def verify_grammar(grammar_text: str) -> bool:
    """用 Lark 验证语法是否正确（不依赖模型）"""
    try:
        from lark import Lark
        parser = Lark(grammar_text, start="start")
        print("[Grammar] Lark 解析验证通过 ✓")
        return True
    except Exception as e:
        print(f"[Grammar] 语法错误: {e}")
        return False


# ============================================================
# Prompt 构建（从 JSON 架构生成结构化提示词）
# ============================================================

def build_generation_prompt(arch: dict) -> str:
    """
    从架构 JSON 构建结构化的 AADL 生成提示词。

    关键：用清晰的结构化信息引导模型按正确顺序生成，
    降低 14B 小模型的推理难度。
    """
    lines = []
    lines.append("【AADL 架构生成任务】")
    lines.append("=" * 70)
    lines.append("")
    lines.append(f"包名：{arch['package_name']}")

    if arch.get("with_packages"):
        lines.append(f"with 包：{', '.join(arch['with_packages'])}")

    lines.append("")

    # ---- 组件列表 ----
    lines.append("-" * 70)
    lines.append("一、组件列表（按生成顺序排列）")
    lines.append("-" * 70)
    lines.append("")

    type_comps = [c for c in arch["components"]
                  if "implementation" not in c.get("category", "")]
    impl_comps = [c for c in arch["components"]
                  if "implementation" in c.get("category", "")]

    # 计算每个类型是否需要实现
    type_has_impl = {}
    for ic in impl_comps:
        base = ic["name"].rsplit(".", 1)[0] if "." in ic["name"] else ic["name"]
        type_has_impl[base] = True

    for i, comp in enumerate(type_comps, 1):
        name = comp["name"]
        cat = comp["category"]
        has_impl = type_has_impl.get(name, comp.get("has_impl", True))
        has_ports = bool(comp.get("ports"))

        lines.append(
            f"{i}. {name}，类型 {cat}，"
            f"{'需要实现' if has_impl else '不需要实现'}，"
            f"{'有 features' if has_ports else '无 features（不要生成 features 节）'}"
        )

    lines.append("")

    # ---- 端口明细 ----
    lines.append("-" * 70)
    lines.append("二、每个组件的 features 明细")
    lines.append("-" * 70)
    lines.append("")

    for comp in type_comps:
        ports = comp.get("ports", [])
        if not ports:
            continue
        lines.append(f"{comp['name']} 的 features：")
        for port in ports:
            type_str = f" {port['data_type']}" if port.get("data_type") else ""
            lines.append(
                f"  - {port['name']}: {port['direction']} "
                f"{port['kind']}{type_str}"
            )
        lines.append("")

    # ---- 子组件明细 ----
    lines.append("-" * 70)
    lines.append("三、每个组件实现的 subcomponents")
    lines.append("-" * 70)
    lines.append("")

    for impl in impl_comps:
        subcomps = impl.get("subcomponents", [])
        lines.append(f"{impl['name']} 的 subcomponents：")
        if not subcomps:
            lines.append("  （无子组件）")
        else:
            for sub in subcomps:
                lines.append(
                    f"  - {sub['name']}: {sub['category']} {sub['classifier']}"
                )
        lines.append("")

    # ---- 连接明细 ----
    lines.append("-" * 70)
    lines.append("四、每个组件实现的 connections")
    lines.append("-" * 70)
    lines.append("")

    for impl in impl_comps:
        conns = impl.get("connections", [])
        lines.append(f"{impl['name']} 的 connections：")
        if not conns:
            lines.append("  （无连接，不要生成 connections 节）")
        else:
            for i, conn in enumerate(conns, 1):
                lines.append(
                    f"  {i}. {conn['name']}: "
                    f"{conn['source']} -> {conn['target']}"
                )
        lines.append("")

    # ---- 生成规则 ----
    lines.append("-" * 70)
    lines.append("五、生成规则")
    lines.append("-" * 70)
    lines.append("")
    lines.append("1. 按上面列出的顺序生成所有组件类型声明")
    lines.append("2. 需要实现的组件，在组件类型后面生成对应的 .impl 实现")
    lines.append("3. 无 features 的组件，不要生成 features 节，直接 end 结束")
    lines.append("4. 无 connections 的实现，不要生成 connections 节")
    lines.append("5. 子组件的类别关键字必须和类型一致")
    lines.append(f"6. 生成到 end {arch['package_name']}; 就停止")
    lines.append("")

    # ---- 语法参考 ----
    lines.append("-" * 70)
    lines.append("六、AADL 语法参考")
    lines.append("-" * 70)
    lines.append("")
    lines.append("有 features 的组件类型：")
    lines.append("  <类别> <组件名>")
    lines.append("    features")
    lines.append("      <端口名>: <方向> data port <数据类型>;")
    lines.append("    end <组件名>;")
    lines.append("")
    lines.append("无 features 的组件类型：")
    lines.append("  <类别> <组件名>")
    lines.append("    end <组件名>;")
    lines.append("")
    lines.append("组件实现：")
    lines.append("  <类别> <组件名>.impl")
    lines.append("    subcomponents")
    lines.append("      <子组件名>: <子类别> <子类型名>;")
    lines.append("    connections")
    lines.append("      <连接名>: <源组件>.<源端口> -> <目标组件>.<目标端口>;")
    lines.append("    end <组件名>.impl;")
    lines.append("")

    lines.append("=" * 70)
    lines.append("")
    lines.append("请根据以上架构描述，生成完整的 AADL 代码。")
    lines.append("")
    lines.append("AADL 代码：")
    lines.append("package ")
    # 注意：prompt 以 "package " 开头，引导模型直接开始生成

    return "\n".join(lines)


# ============================================================
# AADL 生成（Outlines CFG 约束）
# ============================================================

def generate_aadl(model, tokenizer, grammar_text: str, prompt: str,
                  max_tokens: int = 4096,
                  temperature: float = 0.7,
                  stop_text: Optional[str] = None,
                  backend: str = "llguidance",
                  show_thinking: bool = False) -> str:
    """
    使用 Outlines CFG 约束生成 AADL 代码。

    Args:
        model: HF 模型
        tokenizer: HF tokenizer
        grammar_text: Lark EBNF 语法
        prompt: 输入提示词
        max_tokens: 最大生成 token 数
        temperature: 采样温度
        stop_text: 停止词（如 "end PackageName;"）
        backend: 约束后端（llguidance / xgrammar / outlines_core）
        show_thinking: 是否保留 thinking 块

    Returns:
        生成的 AADL 代码字符串
    """
    import torch
    import outlines
    from outlines.generator import get_cfg_logits_processor

    print("[AADL Gen] 编译语法到 FSM...")
    t0 = time.time()

    # 包装模型为 outlines 的 Transformers 对象
    outlines_model = outlines.from_transformers(model, tokenizer)

    # 获取 CFG logits processor
    logits_processor = get_cfg_logits_processor(
        backend_name=backend,
        model=outlines_model,
        grammar=grammar_text,
    )

    compile_time = time.time() - t0
    print(f"[AADL Gen] FSM 编译完成（后端: {backend}），耗时 {compile_time:.1f}s")

    # 流式生成
    print(f"[AADL Gen] 开始生成 (max_tokens={max_tokens}, temp={temperature})...")
    if stop_text:
        print(f"[AADL Gen] 停止词: {repr(stop_text)}")
    print()
    print("─" * 60)

    t1 = time.time()

    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)

    # logits_processor 包装为列表
    if isinstance(logits_processor, (list, tuple)):
        lp_list = list(logits_processor)
    else:
        lp_list = [logits_processor]

    from transformers import (
        TextIteratorStreamer,
        StoppingCriteria,
        StoppingCriteriaList,
    )

    # 停止词检测
    class StopTextCriteria(StoppingCriteria):
        def __init__(self, stop_str, tok):
            self.stop_str = stop_str
            self.tokenizer = tok
            self.generated_text = ""

        def __call__(self, input_ids, scores, **kwargs):
            text = self.tokenizer.decode(
                input_ids[0][-30:], skip_special_tokens=True
            )
            if self.stop_str in text:
                return True
            return False

    if stop_text:
        stopping_criteria = StoppingCriteriaList([
            StopTextCriteria(stop_text, tokenizer)
        ])
    else:
        stopping_criteria = None

    streamer = TextIteratorStreamer(
        tokenizer,
        skip_prompt=True,
        skip_special_tokens=True,
    )

    gen_kwargs = {
        "max_new_tokens": max_tokens,
        "logits_processor": lp_list,
        "pad_token_id": tokenizer.eos_token_id,
        "streamer": streamer,
    }
    if stopping_criteria is not None:
        gen_kwargs["stopping_criteria"] = stopping_criteria
    if temperature > 0:
        gen_kwargs["do_sample"] = True
        gen_kwargs["temperature"] = temperature
        gen_kwargs["top_p"] = 0.9
    else:
        gen_kwargs["do_sample"] = False

    import threading

    generated_text_parts = []
    stopped_early = False

    def generate_thread():
        with torch.no_grad():
            model.generate(**inputs, **gen_kwargs)

    thread = threading.Thread(target=generate_thread)
    thread.start()

    for new_text in streamer:
        print(new_text, end="", flush=True)
        generated_text_parts.append(new_text)
        # 检查停止词
        if stop_text and stop_text in "".join(generated_text_parts):
            stopped_early = True
            break

    thread.join(timeout=2.0)
    result = "".join(generated_text_parts)

    # 命中停止词时截断
    if stop_text and stop_text in result:
        idx = result.index(stop_text) + len(stop_text)
        result = result[:idx]

    print()
    print("─" * 60)
    gen_time = time.time() - t1

    # 统计 token 数
    try:
        num_tokens = len(tokenizer.encode(result))
    except Exception:
        num_tokens = 0

    speed = num_tokens / gen_time if gen_time > 0 else 0
    print(f"\n[AADL Gen] 生成完成，约 {num_tokens} tokens，"
          f"耗时 {gen_time:.1f}s ({speed:.1f} tokens/s)")

    if stopped_early:
        print("[AADL Gen] 命中停止词，提前结束")

    # 去掉 thinking 块
    if not show_thinking:
        import re
        result = re.sub(r'<think>.*?</think>', '', result,
                        flags=re.DOTALL).strip()

    return result


# ============================================================
# 主函数
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description="阶段二：AADL 代码生成（Outlines + llguidance CFG 约束）"
    )
    parser.add_argument("--model", default="~/autodl-tmp/qwen3-14b",
                        help="HuggingFace 模型名或本地路径")
    parser.add_argument("--arch-json", default=None,
                        help="架构 JSON 文件路径（阶段一的输出）")
    parser.add_argument("--grammar", default="grammars/aadl_core.lark",
                        help="Lark EBNF 语法文件路径")
    parser.add_argument("--output", default=None,
                        help="保存 AADL 代码到文件")
    parser.add_argument("--max-tokens", type=int, default=4096)
    parser.add_argument("--temperature", type=float, default=0.7)
    parser.add_argument("--load-in-4bit", action="store_true")
    parser.add_argument("--load-in-8bit", action="store_true")
    parser.add_argument("--backend", default="llguidance",
                        choices=["llguidance", "xgrammar", "outlines_core"],
                        help="约束解码后端（默认 llguidance）")
    parser.add_argument("--verify-only", action="store_true",
                        help="仅验证语法，不加载模型")
    parser.add_argument("--no-constraint", action="store_true",
                        help="不使用 CFG 约束（用于对比）")
    parser.add_argument("--show-thinking", action="store_true",
                        help="保留模型的 thinking 输出")
    parser.add_argument("--print-prompt", action="store_true",
                        help="只打印生成的 prompt，不生成")
    args = parser.parse_args()

    # --- 仅验证语法 ---
    if args.verify_only:
        grammar_text = load_grammar(args.grammar)
        verify_grammar(grammar_text)
        return

    # --- 读取架构 JSON ---
    if not args.arch_json:
        print("错误：请指定 --arch-json（架构 JSON 文件路径）")
        sys.exit(1)

    with open(args.arch_json, "r", encoding="utf-8") as f:
        arch = json.load(f)
    print(f"[Input] 已加载架构: {arch['package_name']} "
          f"({len(arch['components'])} 个组件)")

    # 预构建符号表
    st = build_symbol_table(arch)
    print(f"[Symbol Table] {len(st.component_types)} 个类型, "
          f"{len(st.component_impls)} 个实现")

    # 加载语法
    grammar_text = load_grammar(args.grammar)

    # 构建 prompt
    prompt = build_generation_prompt(arch)
    print(f"[Prompt] 提示词长度: {len(prompt)} 字符")

    if args.print_prompt:
        print("\n" + "=" * 60)
        print(prompt)
        print("=" * 60)
        return

    # --- 加载模型 ---
    model, tokenizer = load_model(
        args.model,
        load_in_4bit=args.load_in_4bit,
        load_in_8bit=args.load_in_8bit,
    )

    # 停止词：end <package_name>;
    stop_text = f"end {arch['package_name']};"

    # --- 生成 AADL ---
    print()
    print("=" * 60)
    print("阶段二：AADL 代码生成")
    print("=" * 60)

    try:
        if args.no_constraint:
            # 无约束模式（简单的原生生成，用于对比）
            print("[AADL Gen] 无约束模式（原生生成）...")
            import torch

            inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
            generated_ids = inputs["input_ids"].clone()
            generated_text = ""

            for i in range(args.max_tokens):
                with torch.no_grad():
                    outputs = model(generated_ids)
                    logits = outputs.logits[:, -1, :]

                if args.temperature > 0:
                    logits = logits / args.temperature
                    probs = torch.softmax(logits, dim=-1)
                    next_token = torch.multinomial(probs, num_samples=1)
                else:
                    next_token = torch.argmax(logits, dim=-1, keepdim=True)

                generated_ids = torch.cat([generated_ids, next_token], dim=-1)
                new_text = tokenizer.decode(
                    generated_ids[0][inputs["input_ids"].shape[1]:],
                    skip_special_tokens=True,
                )
                delta = new_text[len(generated_text):]
                print(delta, end="", flush=True)
                generated_text = new_text

                if stop_text and stop_text in generated_text:
                    idx = generated_text.index(stop_text) + len(stop_text)
                    generated_text = generated_text[:idx]
                    break

                if next_token.item() == tokenizer.eos_token_id:
                    break

            result = generated_text
            print()
        else:
            # CFG 约束模式
            result = generate_aadl(
                model=model,
                tokenizer=tokenizer,
                grammar_text=grammar_text,
                prompt=prompt,
                max_tokens=args.max_tokens,
                temperature=args.temperature,
                stop_text=stop_text,
                backend=args.backend,
                show_thinking=args.show_thinking,
            )

    except Exception as e:
        print(f"\n[Error] 生成失败: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

    # --- 输出结果 ---
    print()
    print("=" * 60)
    print("生成结果:")
    print("=" * 60)
    print(result)
    print("=" * 60)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(result)
        print(f"\n[Output] 已保存到: {args.output}")


if __name__ == "__main__":
    main()
