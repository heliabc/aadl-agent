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

def build_chat_prompt(user_content: str, tokenizer,
                      system_content: str = "你是 AADL 代码生成专家，严格按语法输出完整的 AADL 代码。") -> str:
    """用模型的 chat template 包装 user message（参考 aadl_outlines_demo.py）

    Qwen3 等 Instruct 模型必选。chat template 默认开启 thinking 模式，
    模型会先输出 <think>...</think> 再输出正文。
    返回的 prompt 以 assistant 开头结束，约束从下一个 token 开始生效。
    """
    messages = [
        {"role": "system", "content": system_content},
        {"role": "user", "content": user_content},
    ]
    prompt = tokenizer.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=True,
    )
    return prompt


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
# AADL 生成（CFG + 语义约束，可配置）
# ============================================================

def build_logits_processors(
    model,
    tokenizer,
    grammar_text: str,
    constraint_config=None,
    arch: Optional[dict] = None,
    backend: str = "llguidance",
) -> list:
    """
    根据约束配置组装 logits processor 链。

    顺序：CFG 约束 → 语义约束（先语法过滤，再语义收窄）

    Args:
        model: HF 模型
        tokenizer: HF tokenizer
        grammar_text: Lark EBNF 语法文本
        constraint_config: ConstraintConfig 实例，None 表示默认（CFG+全语义）
        arch: 架构 JSON，用于预填充语义引擎符号表
        backend: CFG 约束后端

    Returns:
        logits_processor 列表
    """
    from constraint_config import ConstraintConfig

    if constraint_config is None:
        constraint_config = ConstraintConfig()  # 默认配置

    processors = []

    # ---- 1. CFG 约束 ----
    if constraint_config.cfg_enabled:
        import outlines
        from outlines.generator import get_cfg_logits_processor

        print("[Constraints] 编译 CFG 文法到 FSM...")
        t0 = time.time()
        outlines_model = outlines.from_transformers(model, tokenizer)
        cfg_lp = get_cfg_logits_processor(
            backend_name=backend,
            model=outlines_model,
            grammar=grammar_text,
        )
        if isinstance(cfg_lp, (list, tuple)):
            processors.extend(cfg_lp)
        else:
            processors.append(cfg_lp)
        print(f"[Constraints] CFG 约束已启用（{time.time() - t0:.1f}s 编译）")
    else:
        print("[Constraints] CFG 约束已关闭")

    # ---- 2. 语义约束 ----
    semantic_count = constraint_config.enabled_semantic_count()
    if semantic_count > 0:
        from aadl_semantic_full import FullAadlSemanticEngine

        print(f"[Constraints] 初始化语义约束引擎（{semantic_count}/11 条启用）...")
        t0 = time.time()
        semantic_engine = FullAadlSemanticEngine(config=constraint_config)
        if arch:
            semantic_engine.prefill_from_arch_json(arch)
            print(f"[Constraints] 符号表预填充完成")

        semantic_lp = semantic_engine.get_logits_processor(tokenizer)
        processors.append(semantic_lp)
        print(f"[Constraints] 语义约束已启用（{time.time() - t0:.2f}s 初始化）")
    else:
        print("[Constraints] 语义约束已全部关闭")

    return processors


def generate_aadl(model, tokenizer, grammar_text: str, prompt: str,
                  max_tokens: int = 4096,
                  temperature: float = 0.7,
                  backend: str = "llguidance",
                  show_thinking: bool = False,
                  constraint_config=None,
                  arch: Optional[dict] = None) -> str:
    """
    使用可配置约束生成 AADL 代码。

    Args:
        model: HF 模型
        tokenizer: HF tokenizer
        grammar_text: Lark EBNF 语法
        prompt: 输入提示词
        max_tokens: 最大生成 token 数
        temperature: 采样温度
        backend: 约束后端（llguidance / xgrammar / outlines_core）
        show_thinking: 是否保留 thinking 块
        constraint_config: ConstraintConfig 约束配置，None 用默认
        arch: 架构 JSON（用于预填充语义引擎符号表）

    Returns:
        生成的 AADL 代码字符串
    """
    import torch
    from transformers import TextIteratorStreamer

    # 组装 logits processor 链
    lp_list = build_logits_processors(
        model=model,
        tokenizer=tokenizer,
        grammar_text=grammar_text,
        constraint_config=constraint_config,
        arch=arch,
        backend=backend,
    )

    # 流式生成
    print(f"[AADL Gen] 开始生成 (max_tokens={max_tokens}, temp={temperature})...")
    print()
    print("─" * 60)

    t1 = time.time()

    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)

    streamer = TextIteratorStreamer(
        tokenizer,
        skip_prompt=True,
        skip_special_tokens=True,
    )

    gen_kwargs = {
        "max_new_tokens": max_tokens,
        "logits_processor": lp_list if lp_list else None,
        "pad_token_id": tokenizer.eos_token_id,
        "streamer": streamer,
    }
    if temperature > 0:
        gen_kwargs["do_sample"] = True
        gen_kwargs["temperature"] = temperature
        gen_kwargs["top_p"] = 0.9
    else:
        gen_kwargs["do_sample"] = False

    import threading

    generated_text_parts = []

    def generate_thread():
        with torch.no_grad():
            model.generate(**inputs, **gen_kwargs)

    thread = threading.Thread(target=generate_thread)
    thread.start()

    for new_text in streamer:
        print(new_text, end="", flush=True)
        generated_text_parts.append(new_text)

    thread.join(timeout=2.0)
    result = "".join(generated_text_parts)

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

    # 去掉 thinking 块
    if not show_thinking:
        import re
        result = re.sub(r'<think>.*?</think>', '', result,
                        flags=re.DOTALL).strip()

    return result


# ============================================================
# 主函数
# ============================================================

def _arch_to_semantic_format(arch: dict) -> dict:
    """把通用 arch JSON 转为语义引擎需要的格式（type_components + impl_components）"""
    components = arch.get("components", [])
    type_components = []
    impl_components = []

    for comp in components:
        cat = comp.get("category", "")
        if "implementation" in cat:
            impl_components.append({
                "name": comp.get("name", ""),
                "category": cat,
                "base_type": comp.get("base_type", comp.get("name", "").rsplit(".", 1)[0]),
                "subcomponents": comp.get("subcomponents", []),
            })
        else:
            type_components.append({
                "name": comp.get("name", ""),
                "category": cat,
                "ports": comp.get("ports", []),
            })

    return {
        "package_name": arch.get("package_name", ""),
        "type_components": type_components,
        "impl_components": impl_components,
    }


def main():
    parser = argparse.ArgumentParser(
        description="阶段二：AADL 代码生成（可配置约束：CFG + 语义）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
约束预设示例：
  --constraint-preset baseline        无约束（原生生成）
  --constraint-preset cfg_only        仅 CFG 文法约束
  --constraint-preset cfg_semantic    CFG + 全部语义约束（推荐）
  --constraint-preset cfg_semantic_core  CFG + 核心5条语义约束
  --constraint-preset ablation_no_classifier  消融：关分类器引用

单条约束控制（在预设基础上覆盖）：
  --disable-classifier-ref    关闭子组件分类器引用约束
  --disable-port-direction    关闭端口方向匹配约束
  --disable-port-type-match   关闭端口类型匹配约束
  --disable-property-name     关闭属性名合法性约束
  --enable-naming-unique      启用命名唯一性约束（默认关闭）
        """,
    )
    parser.add_argument("-m", "--model", default=None,
                        help="模型路径（HF 模型目录或模型名），也可通过环境变量 AADL_MODEL 设置")
    parser.add_argument("--arch-json", default=None,
                        help="架构 JSON 文件路径（阶段一的输出，自动构建 prompt）")
    parser.add_argument("--prompt-file", default=None,
                        help="直接指定 prompt txt 文件（优先级高于 --arch-json）")
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
    parser.add_argument("--chat", action="store_true",
                        help="使用 Chat Template 格式（Qwen3 Instruct 模型必选，默认开启 thinking 模式）")
    parser.add_argument("--show-thinking", action="store_true",
                        help="保留模型的 thinking 输出")
    parser.add_argument("--print-prompt", action="store_true",
                        help="只打印生成的 prompt，不生成")

    # ---- 约束配置参数 ----
    parser.add_argument("--constraint-preset", default="cfg_semantic",
                        help="约束预设（默认 cfg_semantic）")
    parser.add_argument("--list-presets", action="store_true",
                        help="列出所有可用预设并退出")

    # CFG 开关
    parser.add_argument("--no-cfg", action="store_true",
                        help="关闭 CFG 文法约束（覆盖预设）")

    # 语义约束总开关
    parser.add_argument("--no-semantic", action="store_true",
                        help="关闭所有语义约束（覆盖预设）")
    parser.add_argument("--all-semantic", action="store_true",
                        help="启用所有语义约束（覆盖预设）")

    # 单条约束开关（在预设基础上覆盖）
    parser.add_argument("--disable-classifier-ref", action="store_true",
                        help="关闭子组件分类器引用约束")
    parser.add_argument("--disable-containment", action="store_true",
                        help="关闭包含规则约束")
    parser.add_argument("--disable-port-direction", action="store_true",
                        help="关闭端口方向匹配约束")
    parser.add_argument("--disable-port-type-match", action="store_true",
                        help="关闭端口类型匹配约束")
    parser.add_argument("--disable-binding", action="store_true",
                        help="关闭绑定约束")
    parser.add_argument("--disable-port-data-type", action="store_true",
                        help="关闭端口数据类型约束")
    parser.add_argument("--disable-impl-base-type", action="store_true",
                        help="关闭实现 base_type 约束")
    parser.add_argument("--disable-conn-bound-bus", action="store_true",
                        help="关闭连接 bound_to_bus 约束")
    parser.add_argument("--disable-flow-endpoint", action="store_true",
                        help="关闭 Flow 端点约束")
    parser.add_argument("--disable-property-name", action="store_true",
                        help="关闭属性名合法性约束")
    parser.add_argument("--enable-naming-unique", action="store_true",
                        help="启用命名唯一性约束（默认关闭）")

    # 兼容旧参数
    parser.add_argument("--no-constraint", action="store_true",
                        help="（兼容旧版）等价于 --constraint-preset baseline")

    args = parser.parse_args()

    # --- 列出预设 ---
    if args.list_presets:
        from constraint_config import print_all_presets
        print_all_presets()
        return

    # --- 仅验证语法 ---
    if args.verify_only:
        grammar_text = load_grammar(args.grammar)
        verify_grammar(grammar_text)
        return

    # --- 解析模型路径（命令行 > 环境变量 > 报错） ---
    model_path = args.model
    if not model_path:
        model_path = os.environ.get("AADL_MODEL", "")
    if not model_path:
        print("错误：未指定模型路径。请使用：")
        print("  1. -m / --model 参数指定，如：-m D:\\models\\qwen3-14b")
        print("  2. 设置环境变量 AADL_MODEL")
        sys.exit(1)
    model_path = os.path.expanduser(model_path)
    if not os.path.isdir(model_path) and not model_path.startswith(
        ("http", "hf://", "/")
    ):
        # 可能是 HF 模型名，不检查路径
        print(f"[Model] 使用 HuggingFace 模型: {model_path}")
    else:
        print(f"[Model] 本地模型路径: {model_path}")

    # --- 读取输入（两种模式：prompt txt 或 arch json） ---
    prompt = None
    arch = None

    if args.prompt_file:
        # 模式1：直接用 txt 文件作为 prompt
        with open(args.prompt_file, "r", encoding="utf-8") as f:
            prompt = f.read()
        print(f"[Input] 已加载 prompt 文件: {args.prompt_file} ({len(prompt)} 字符)")

    elif args.arch_json:
        # 模式2：从架构 JSON 构建 prompt
        with open(args.arch_json, "r", encoding="utf-8") as f:
            arch = json.load(f)
        print(f"[Input] 已加载架构: {arch['package_name']} "
              f"({len(arch['components'])} 个组件)")

        # 预构建符号表
        st = build_symbol_table(arch)
        print(f"[Symbol Table] {len(st.component_types)} 个类型, "
              f"{len(st.component_impls)} 个实现")

        # 构建 prompt
        prompt = build_generation_prompt(arch)
    else:
        print("错误：请指定 --prompt-file 或 --arch-json 作为输入")
        sys.exit(1)

    # 加载语法
    grammar_text = load_grammar(args.grammar)

    print(f"[Prompt] 提示词长度: {len(prompt)} 字符")

    if args.print_prompt:
        print("\n" + "=" * 60)
        print(prompt)
        print("=" * 60)
        return

    # ---- 构建约束配置 ----
    from constraint_config import get_preset

    # 兼容旧参数
    preset_name = "baseline" if args.no_constraint else args.constraint_preset

    try:
        config = get_preset(preset_name)
    except ValueError as e:
        print(f"错误：{e}")
        sys.exit(1)

    # CFG 覆盖
    if args.no_cfg:
        config.cfg_enabled = False

    # 语义总开关覆盖
    if args.no_semantic:
        config.disable_all_semantic()
    if args.all_semantic:
        config.enable_all_semantic()

    # 单条约束覆盖（关闭）
    if args.disable_classifier_ref:
        config.semantic_classifier_ref = False
    if args.disable_containment:
        config.semantic_containment = False
    if args.disable_port_direction:
        config.semantic_port_direction = False
    if args.disable_port_type_match:
        config.semantic_port_type_match = False
    if args.disable_binding:
        config.semantic_binding = False
    if args.disable_port_data_type:
        config.semantic_port_data_type = False
    if args.disable_impl_base_type:
        config.semantic_impl_base_type = False
    if args.disable_conn_bound_bus:
        config.semantic_conn_bound_bus = False
    if args.disable_flow_endpoint:
        config.semantic_flow_endpoint = False
    if args.disable_property_name:
        config.semantic_property_name = False

    # 单条约束覆盖（启用）
    if args.enable_naming_unique:
        config.semantic_naming_unique = True

    print()
    print("=" * 60)
    print("约束配置")
    print("=" * 60)
    print(config.summary())
    print("=" * 60)

    # --- 加载模型 ---
    model, tokenizer = load_model(
        model_path,
        load_in_4bit=args.load_in_4bit,
        load_in_8bit=args.load_in_8bit,
    )

    # 转为语义引擎需要的格式（仅 arch 模式有）
    semantic_arch = None
    if arch:
        semantic_arch = _arch_to_semantic_format(arch)

    # --- Chat 模式包装（Qwen3 thinking 支持，参考 aadl_outlines_demo.py） ---
    if args.chat:
        prompt = build_chat_prompt(prompt, tokenizer)
        prompt_tokens = len(tokenizer.encode(prompt))
        print(f"[Prompt] Chat 模式（thinking 默认开启），prompt 长度: {prompt_tokens} tokens")

    # --- 生成 AADL ---
    print()
    print("=" * 60)
    print("阶段二：AADL 代码生成")
    print("=" * 60)

    try:
        result = generate_aadl(
            model=model,
            tokenizer=tokenizer,
            grammar_text=grammar_text,
            prompt=prompt,
            max_tokens=args.max_tokens,
            temperature=args.temperature,
            backend=args.backend,
            show_thinking=args.show_thinking,
            constraint_config=config,
            arch=semantic_arch,
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
