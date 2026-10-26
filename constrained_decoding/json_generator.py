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
from json_validator import quick_validate, ValidationResult


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
        "dtype": torch.float16,
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
    """构建阶段一的 prompt：需求 → JSON 架构（v2 完整版）"""
    return f"""你是一名资深 AADL（Architecture Analysis & Design Language）架构设计师。
请根据以下需求，设计一个完整、语义正确的 AADL 架构，并以严格的 JSON 格式输出。

═══════════════════════════════════════════════════════════
需求描述
═══════════════════════════════════════════════════════════
{requirement}

═══════════════════════════════════════════════════════════
输出结构总览
═══════════════════════════════════════════════════════════
你输出的 JSON 必须包含以下顶层字段：
  - package_name     : 包名（可嵌套，如 FlightControl::Software）
  - with_packages    : 依赖包列表（如 ["Base_Types"]）
  - components       : 组件列表，按「类型在前、对应实现在后」的顺序排列

每个 component 是统一的组件声明，用 category 字段区分类型/实现：
  - 类型：category 不带 "implementation"（如 "system", "process"）
  - 实现：category 带 "implementation" 后缀（如 "system implementation"）

【重要约定】除简单 data 类型外，每个组件类型都必须有对应的实现，即使实现是空的。
components 列表按「类型在前、对应实现在后」的顺序排列。

═══════════════════════════════════════════════════════════
一、包与依赖（package_name / with_packages）
═══════════════════════════════════════════════════════════
1. package_name 使用有意义的英文名称，支持 :: 嵌套（如 Avionics::FC）。
2. with_packages 列出所有需要 with 的包：
   - 使用预定义数据类型时，必须包含 "Base_Types"
   - 引用其他包的组件时，必须包含对应的包名
3. 本包内定义的组件，引用时直接写名称，不需要加包前缀。

═══════════════════════════════════════════════════════════
二、组件类型（ComponentType）
═══════════════════════════════════════════════════════════
每个组件类型必须指定：
  - name     : 组件类型名（大驼峰，如 NavigationThread）
  - category : 组件类别，可选值：
      system, process, thread, thread group,
      subprogram, subprogram group,
      data, device, processor, virtual processor,
      memory, bus, virtual bus, abstract
  - has_impl : 是否有对应的实现（除简单 data 类型外均为 true）
  - extends  : 可选，继承的父类型名

【重要】不是所有组件都有端口！根据类别按需填写：
  ports          : 端口列表（data / event / event data port）
  accesses       : access 特征列表（bus access / data access 等）
  feature_groups : feature group 列表
  flows          : 流定义（flow path / flow source / flow sink）
  properties     : 属性列表

───────────────────────────────────────────────────────────
2.1 端口（ports）
───────────────────────────────────────────────────────────
每个端口包含：
  - name      : 端口名（小写+下划线，如 sensor_data）
  - direction : 方向：in / out / in out
  - kind      : 种类：
      data port       — 纯数据端口（需要 data_type）
      event port      — 纯事件端口（不需要 data_type）
      event data port — 带数据的事件端口（需要 data_type）
  - data_type       : 数据类型（data port / event data port 需要）
      * 预定义类型：Base_Types::Float / Base_Types::Integer / Base_Types::Boolean
                    Base_Types::Int32 / Base_Types::Float64 等
      * 自定义类型：直接写本包内 data 组件的名字（如 SensorData）
      * 跨包类型：包名::类型名（如 ICD::SpeedData）
  - data_type_source: predefined / custom / external
  - properties      : 端口属性（可选）

───────────────────────────────────────────────────────────
2.2 Access 特征（accesses）
───────────────────────────────────────────────────────────
用于 bus access、data access、subprogram access 等。
每个 access 包含：
  - name       : 特征名
  - direction  : provides / requires
  - kind       : bus access / data access / subprogram access / subprogram group access
  - classifier : 分类器引用（bus 组件名或 data 组件名）

───────────────────────────────────────────────────────────
2.3 流（flows — 类型内）
───────────────────────────────────────────────────────────
类型内的流描述数据流经过哪些端口：
  - kind   : flow path / flow source / flow sink
  - source : 源端口名（flow path / flow source 需要）
  - target : 目标端口名（flow path / flow sink 需要）

═══════════════════════════════════════════════════════════
三、组件实现（ComponentImpl）
═══════════════════════════════════════════════════════════
每个组件实现必须指定：
  - name      : 实现名，格式为「类型名.实现标识」（如 NavThread.i / Sys.impl）
  - category  : 实现类别（如 "process implementation"）
  - base_type : 对应的组件类型名
  - extends   : 可选，继承的父实现名

【注意】实现可以是空的（没有子组件、没有连接），这在 AADL 中完全合法。
即使是空实现，base_type 也必须填写。

实现内部结构：
  subcomponents : 子组件列表
  connections   : 连接列表
  bindings      : 绑定列表
  flows         : 端到端流列表
  properties    : 实现属性
  calls         : 子程序调用序列（thread implementation 用）

───────────────────────────────────────────────────────────
3.1 子组件（subcomponents）与包含规则【静态语义】
───────────────────────────────────────────────────────────
每个子组件：
  - name       : 实例名（小写+下划线，如 nav_thread）
  - category   : 子组件类别
  - classifier : 分类器（类型名或实现名，同包直接写名字）

【包含规则 · 必须严格遵守】
父组件类别 → 允许包含的子组件类别：
  system         → system, process, processor, memory, bus, device,
                    thread group, data, subprogram, abstract
  process        → thread, thread group, subprogram, data, abstract
  thread         → subprogram, data
  thread group   → thread, thread group, data, subprogram, abstract
  data           → data, subprogram
  processor      → memory, bus, virtual bus, virtual processor, abstract
  memory         → memory
  bus            → virtual bus
  virtual bus    → virtual bus
  device         → data
  abstract       → 几乎所有类别（最宽松）
  subprogram     → data
  subprogram group → subprogram, subprogram group, data

───────────────────────────────────────────────────────────
3.2 连接（connections）与方向规则【静态语义】
───────────────────────────────────────────────────────────
每个连接：
  - name       : 连接名（如 c1 / data_conn）
  - kind       : port connection / feature group connection / access connection
  - source     : 源端引用，格式：
                   自身端口直接写名字："output_port"
                   子组件端口："子组件名.端口名"，如 "nav_thread.out_data"
  - target     : 目标端引用，格式同上
  - bidirectional : 是否双向（bus access 连接常用）
  - bound_to_bus  : 绑定的总线子组件实例名（Actual_Connection_Binding）
  - properties : 连接属性

【端口方向规则 · 必须严格遵守】
  对于 port connection：
    - 源端（source）端口方向必须是 out 或 in out
    - 目标端（target）端口方向必须是 in 或 in out
  对于 access connection：
    - 源端 direction 为 requires 的 access → 目标端 direction 为 provides 的 access

───────────────────────────────────────────────────────────
3.3 绑定（bindings）【静态语义】
───────────────────────────────────────────────────────────
绑定描述软件组件如何映射到硬件：
  - kind   : 绑定类型
      actual_processor_binding   — 线程/进程绑定到处理器
      actual_memory_binding      — 数据/代码绑定到内存
      actual_connection_binding  — 连接绑定到总线
      actual_subprogram_binding  — 子程序绑定
  - source : 被绑定的元素（子组件实例名或连接名）
  - target : 绑定目标（处理器/内存/总线 子组件实例名）

【绑定规则 · 必须严格遵守】
  - actual_processor_binding：源是 thread / process，目标是 processor / virtual processor
  - actual_memory_binding：源是 data / 代码，目标是 memory
  - actual_connection_binding：源是连接名，目标是 bus / virtual bus 子组件

───────────────────────────────────────────────────────────
3.4 端到端流（flows — 实现内）
───────────────────────────────────────────────────────────
end to end flow 描述跨子组件的完整数据流路径：
  - kind   : "end to end flow"
  - path   : 路径序列，交替为「子组件流名」和「连接名」
             如 ["sensor.f_out", "c1", "processor.f_in"]

═══════════════════════════════════════════════════════════
四、自定义数据类型（data 组件）
═══════════════════════════════════════════════════════════
当需要结构化或枚举数据类型时，定义 data 组件：
  - category : "data"
  - ports    : data 组件通常没有端口
  - has_impl : 简单 data 类型（直接用 Base_Types 别名）可设为 false，不需要实现
               结构化/复杂 data 类型仍需要实现
  - properties : 用属性描述数据字段（可选）

═══════════════════════════════════════════════════════════
五、Bus 与通信
═══════════════════════════════════════════════════════════
定义总线组件时：
  - category   : "bus" 或 "virtual bus"
  - accesses   : 通常不需要（bus 是被访问的对象）
  - 其他组件通过 bus access 特征来引用总线：
      * 需要 bus access 的组件：在 accesses 中定义
        direction = "requires", kind = "bus access", classifier = 总线类型名

═══════════════════════════════════════════════════════════
六、引用完整性规则【静态语义】
═══════════════════════════════════════════════════════════
1. 所有 classifier 引用（子组件、access、feature group 等）
   必须指向本包中已定义的组件，或 with_packages 中的外部包。
2. 连接的 source / target 引用的端口必须真实存在于对应组件。
3. 绑定的 source / target 必须是实现中真实存在的子组件或连接。
4. end to end flow 的 path 中的元素必须真实存在。
5. extends 引用的父类型/父实现必须存在。

═══════════════════════════════════════════════════════════
七、命名规范
═══════════════════════════════════════════════════════════
- 包名、类型名：大驼峰（PascalCase），如 FlightControl
- 实现名：类型名.标识，如 FlightSystem.i
- 子组件实例名、端口名：小写下划线（snake_case），如 nav_thread
- 属性名：大驼峰或全大写，如 Period / DEADLINE

═══════════════════════════════════════════════════════════
输出要求
═══════════════════════════════════════════════════════════
请严格按照 JSON Schema 输出完整的架构描述 JSON 对象。
不要输出任何额外的解释、markdown 标记或前后缀文本。
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

    采用 logits_processor + HF model.generate() 的方式，
    与 aadl_outlines_demo.py 保持一致。

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
    import torch
    import outlines
    from outlines.generator import get_json_schema_logits_processor

    print("[JSON Gen] 初始化 Outlines JSON 生成器...")
    t0 = time.time()

    # 1. 包装模型为 outlines 的 Transformers 对象
    outlines_model = outlines.from_transformers(model, tokenizer)

    # 2. 获取 JSON Schema
    schema = get_json_schema_dict()

    # 3. 获取 JSON Schema logits processor
    #    注意：参数名是 json_schema，需要传字符串形式的 schema
    import json as _json
    logits_processor = get_json_schema_logits_processor(
        backend_name=backend,
        model=outlines_model,
        json_schema=_json.dumps(schema),
    )

    init_time = time.time() - t0
    print(f"[JSON Gen] FSM 编译完成（后端: {backend}），耗时 {init_time:.1f}s")
    print(f"[JSON Gen] 开始生成 (max_tokens={max_tokens}, temp={temperature})...")
    print()
    print("─" * 60)

    # 4. 流式生成
    t1 = time.time()

    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)

    # logits_processor 包装为列表
    if isinstance(logits_processor, (list, tuple)):
        lp_list = list(logits_processor)
    else:
        lp_list = [logits_processor]

    from transformers import TextIteratorStreamer

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
    if temperature > 0:
        gen_kwargs["do_sample"] = True
        gen_kwargs["temperature"] = temperature
        gen_kwargs["top_p"] = 0.9
    else:
        gen_kwargs["do_sample"] = False

    import threading
    generated_text = []

    def generate_thread():
        with torch.no_grad():
            model.generate(**inputs, **gen_kwargs)

    thread = threading.Thread(target=generate_thread)
    thread.start()

    for new_text in streamer:
        print(new_text, end="", flush=True)
        generated_text.append(new_text)

    thread.join(timeout=1.0)
    result_text = "".join(generated_text)

    print()
    print("─" * 60)
    gen_time = time.time() - t1
    num_tokens = len(tokenizer.encode(result_text))
    speed = num_tokens / gen_time if gen_time > 0 else 0
    print(f"\n[JSON Gen] 生成完成，约 {num_tokens} tokens，耗时 {gen_time:.1f}s ({speed:.1f} tokens/s)")

    # 去掉 Qwen3 的 thinking 块
    if not show_thinking:
        import re
        result_text = re.sub(r'<think>.*?</think>', '', result_text,
                             flags=re.DOTALL).strip()

    # 5. 解析为 dict
    try:
        result_dict = json.loads(result_text)
    except json.JSONDecodeError as e:
        print(f"[JSON Gen] 警告: JSON 解析失败，尝试提取... ({e})")
        # 尝试从文本中提取 JSON 对象
        import re
        match = re.search(r'\{.*\}', result_text, re.DOTALL)
        if match:
            result_dict = json.loads(match.group(0))
        else:
            raise

    # 6. 后处理：递归 trim 所有 dict key 的首尾空格
    #    （llguidance 约束下字段名可能带空格，这里兜底修复）
    result_dict = _trim_dict_keys(result_dict)

    return result_dict


def _trim_dict_keys(obj):
    """递归地去掉所有 dict key 的首尾空格"""
    if isinstance(obj, dict):
        return {k.strip(): _trim_dict_keys(v) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [_trim_dict_keys(item) for item in obj]
    else:
        return obj


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
    result = quick_validate(arch)

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
        result = quick_validate(fixed_arch)
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
                        help="不进行自动修复（只生成一次，但仍做语义校验）")
    parser.add_argument("--no-validate", action="store_true",
                        help="跳过后处理语义校验和修复（仅 JSON Schema 格式约束，用于对比实验）")
    parser.add_argument("--max-retries", type=int, default=2,
                        help="最大修复重试次数")
    parser.add_argument("--show-thinking", action="store_true",
                        help="保留模型的 thinking 输出")
    parser.add_argument("--gen-prompt", action="store_true",
                        help="生成 JSON 后自动生成 AADL 提示词")
    parser.add_argument("--prompt-format", default="by_component",
                        choices=["by_component", "by_category", "compact"],
                        help="AADL 提示词组织格式（默认 by_component）")
    parser.add_argument("--phased", action="store_true",
                        help="使用三阶段渐进式生成（语义约束在生成时生效）")
    parser.add_argument("--validate-file", default=None,
                        help="仅校验已有 JSON 文件，不生成（快速校验模式）")
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

        result = quick_validate(test_arch)
        print(f"语义校验: {'通过 ✓' if result.valid else '有问题'}")
        print(result.summary())
        return

    # --- 仅校验已有 JSON 文件 ---
    if args.validate_file:
        print("=" * 60)
        print(f"校验 JSON 文件: {args.validate_file}")
        print("=" * 60)

        with open(args.validate_file, "r", encoding="utf-8") as f:
            arch = json.load(f)

        # 先 trim key（llguidance 生成的 JSON 可能字段名带空格）
        arch = _trim_dict_keys(arch)

        # 结构校验
        ok, struct_errors = validate_json_structure(arch)
        print(f"\n结构校验: {'✅ 通过' if ok else '❌ 失败'}")
        if struct_errors:
            for e in struct_errors:
                print(f"  ✗ {e}")

        # 语义校验
        result = quick_validate(arch)
        print(f"\n语义校验: {'✅ 通过' if result.valid else '❌ 失败'}")
        print(result.summary())

        # 统计信息
        types = [c for c in arch["components"]
                 if "implementation" not in c.get("category", "")]
        impls = [c for c in arch["components"]
                 if "implementation" in c.get("category", "")]
        total_ports = sum(len(c.get("ports", [])) for c in arch["components"])
        total_subs = sum(len(c.get("subcomponents", [])) for c in arch["components"])
        total_conns = sum(len(c.get("connections", [])) for c in arch["components"])

        print(f"\n架构概览:")
        print(f"  包名: {arch.get('package_name', '?')}")
        print(f"  组件类型: {len(types)} 个")
        print(f"  组件实现: {len(impls)} 个")
        print(f"  端口数:   {total_ports}")
        print(f"  子组件数: {total_subs}")
        print(f"  连接数:   {total_conns}")

        # 可选：生成 AADL 提示词（语义校验通过才生成）
        if args.gen_prompt:
            if not result.valid:
                print("\n[Skip] 语义校验未通过，跳过生成 AADL 提示词")
            else:
                from json_to_prompt import build_prompt as build_aadl_prompt
                prompt = build_aadl_prompt(arch, fmt=args.prompt_format)

                base = args.validate_file.replace(".json", "")
                prompt_path = base + f".{args.prompt_format}.txt"
                with open(prompt_path, "w", encoding="utf-8") as f:
                    f.write(prompt)
                print(f"\n[Output] AADL 提示词已保存: {prompt_path}")
                print(f"         长度: {len(prompt)} 字符")

        sys.exit(0 if result.valid else 1)

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
        if args.phased:
            # 三阶段渐进式生成（语义约束在生成时生效）
            from phased_generator import generate_phased
            arch = generate_phased(
                model, tokenizer, requirement,
                max_tokens=args.max_tokens,
                temperature=args.temperature,
                backend=args.backend,
                show_thinking=args.show_thinking,
            )
        else:
            # 单阶段生成（格式约束 + 后处理语义校验）
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
        result = quick_validate(arch)
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

    # --- 保存 JSON（无论校验是否通过都保存，方便调试） ---
    if args.output:
        os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(arch, f, indent=2, ensure_ascii=False)
        print(f"\n[Output] 架构 JSON 已保存: {args.output}")

    # --- 自动生成 AADL 提示词 ---
    if args.gen_prompt:
        # 语义校验通过才生成提示词，避免用有问题的 JSON
        if not result.valid:
            print("\n[Skip] 语义校验未通过，跳过生成 AADL 提示词")
            print("       请先修复 JSON 中的语义错误")
        else:
            from json_to_prompt import build_prompt as build_aadl_prompt

            prompt_format = args.prompt_format
            aadl_prompt = build_aadl_prompt(arch, fmt=prompt_format)

            # 输出到文件或控制台
            if args.output:
                prompt_path = args.output.replace(".json", "") + f".{prompt_format}.txt"
            else:
                prompt_path = f"aadl_prompt.{prompt_format}.txt"

            os.makedirs(os.path.dirname(prompt_path) or ".", exist_ok=True)
            with open(prompt_path, "w", encoding="utf-8") as f:
                f.write(aadl_prompt)
            print(f"[Output] AADL 生成提示词已保存: {prompt_path}")
            print(f"         格式: {prompt_format}, 长度: {len(aadl_prompt)} 字符")

    # 退出码
    sys.exit(0 if result.valid else 1)


if __name__ == "__main__":
    main()
