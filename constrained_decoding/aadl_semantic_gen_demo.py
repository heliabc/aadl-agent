#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
上下文敏感约束 + 大模型生成 集成示例
=====================================
演示如何把 AadlContextSensitiveEngine 接入到
Outlines CFG 约束解码的 AADL 生成流程中。

两种集成模式：
  模式 A：两阶段生成（推荐）
    - 阶段1：JSON 架构生成（phased_generator.py）
    - 阶段2：AADL 代码生成（从 JSON → AADL）
    - 优势：阶段2开始时符号表已完整，约束最精确

  模式 B：端到端直接生成
    - 直接从需求生成 AADL 代码
    - 优势：流程简单
    - 劣势：生成前期符号表不完整，约束较弱

用法：
  # 模式 A：从 JSON 架构生成 AADL（带语义约束）
  python aadl_semantic_gen_demo.py --mode from_json \
      --model ~/models/qwen3-14b \
      --arch-json output/arch.json \
      --grammar grammars/aadl_core.lark

  # 模式 B：端到端直接生成（带增量约束）
  python aadl_semantic_gen_demo.py --mode end_to_end \
      --model ~/models/qwen3-14b \
      --grammar grammars/aadl_core.lark \
      --requirement "设计一个飞行控制系统"

  # 仅验证约束逻辑（不需要模型）
  python aadl_semantic_gen_demo.py --verify-only
"""

import os
import sys
import json
import time
import argparse
from typing import Optional, List

from aadl_context_sensitive import (
    AadlContextSensitiveEngine,
    check_aadl_semantics,
)


# ============================================================
# 核心：带语义约束的 AADL 生成函数
# ============================================================
def generate_aadl_with_semantic_constraints(
    model,
    tokenizer,
    grammar_text: str,
    prompt: str,
    arch_json: dict = None,
    max_tokens: int = 4096,
    temperature: float = 0.7,
    stop_text: Optional[str] = None,
    backend: str = "llguidance",
    show_thinking: bool = False,
) -> str:
    """
    带上下文敏感语义约束的 AADL 生成。

    在 CFG 语法约束的基础上，叠加语义约束：
    - CFG 保证语法正确
    - 上下文敏感约束保证语义正确（引用完整性、包含规则、方向匹配、类型匹配等）

    Args:
        model, tokenizer: HF 模型和 tokenizer
        grammar_text: Lark EBNF 语法
        prompt: 生成提示词
        arch_json: 可选，预填充的架构 JSON（两阶段模式下使用）
        max_tokens: 最大生成 token 数
        temperature: 采样温度
        stop_text: 停止词
        backend: CFG 约束后端
        show_thinking: 是否保留 thinking 块

    Returns:
        生成的 AADL 代码字符串
    """
    import torch
    import outlines
    from outlines.generator import get_cfg_logits_processor
    from transformers import (
        TextIteratorStreamer,
        StoppingCriteria,
        StoppingCriteriaList,
    )
    import threading

    # ---- 步骤1：初始化语义约束引擎 ----
    print("[Semantic] 初始化上下文敏感约束引擎...")
    semantic_engine = AadlContextSensitiveEngine()

    if arch_json:
        # 两阶段模式：预填充符号表
        semantic_engine.prefill_from_arch_json(arch_json)
        type_count = len(semantic_engine.symbols.component_types)
        impl_count = len(semantic_engine.symbols.component_impls)
        print(f"[Semantic] 已预填充 {type_count} 个类型 + {impl_count} 个实现")
    else:
        # 端到端模式：从零开始增量积累
        print("[Semantic] 端到端模式，符号表将随生成增量积累")

    # ---- 步骤2：编译 CFG 约束 ----
    print("[CFG] 编译语法到 FSM...")
    t0 = time.time()

    outlines_model = outlines.from_transformers(model, tokenizer)

    cfg_lp = get_cfg_logits_processor(
        backend_name=backend,
        model=outlines_model,
        grammar=grammar_text,
    )

    cfg_time = time.time() - t0
    print(f"[CFG] FSM 编译完成，耗时 {cfg_time:.1f}s")

    # ---- 步骤3：创建语义约束 logits processor ----
    semantic_lp = semantic_engine.get_logits_processor(tokenizer)

    # ---- 步骤4：组合 logits processor ----
    # 顺序很重要：先过 CFG（语法过滤），再过语义约束（语义收窄）
    if isinstance(cfg_lp, (list, tuple)):
        lp_list = list(cfg_lp)
    else:
        lp_list = [cfg_lp]
    lp_list.append(semantic_lp)

    print(f"[Pipeline] CFG 约束 + 语义约束（共 {len(lp_list)} 个 logits processor）")

    # ---- 步骤5：生成 ----
    print()
    print("=" * 60)
    print("开始生成 AADL（带语义约束）")
    print("=" * 60)
    print()

    t1 = time.time()
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)

    # 停止词
    class StopTextCriteria(StoppingCriteria):
        def __init__(self, stop_str, tok):
            self.stop_str = stop_str
            self.tokenizer = tok

        def __call__(self, input_ids, scores, **kwargs):
            text = self.tokenizer.decode(
                input_ids[0][-30:], skip_special_tokens=True
            )
            return self.stop_str in text

    stopping_criteria = StoppingCriteriaList([
        StopTextCriteria(stop_text, tokenizer)
    ]) if stop_text else None

    streamer = TextIteratorStreamer(
        tokenizer, skip_prompt=True, skip_special_tokens=True,
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

    if stopping_criteria is not None:
        gen_kwargs["stopping_criteria"] = stopping_criteria

    generated_parts = []
    constraint_activations = 0  # 统计语义约束触发次数

    def generate_thread():
        with torch.no_grad():
            model.generate(**inputs, **gen_kwargs)

    thread = threading.Thread(target=generate_thread)
    thread.start()

    for new_text in streamer:
        print(new_text, end="", flush=True)
        generated_parts.append(new_text)

    thread.join(timeout=1.0)
    result_text = "".join(generated_parts)

    print()
    print()
    gen_time = time.time() - t1
    num_tokens = len(tokenizer.encode(result_text))
    speed = num_tokens / gen_time if gen_time > 0 else 0
    print("-" * 60)
    print(f"生成完成：约 {num_tokens} tokens，耗时 {gen_time:.1f}s ({speed:.1f} t/s)")

    # ---- 步骤6：生成后语义校验 ----
    print()
    print("=" * 60)
    print("生成后语义校验")
    print("=" * 60)

    errors = check_aadl_semantics(result_text)
    if errors:
        print(f"发现 {len(errors)} 个语义问题：")
        for e in errors:
            print(f"  ⚠️  {e}")
        print()
        print("说明：部分约束（如绑定约束、access feature 约束）可能未完全覆盖，")
        print("     端到端模式下前期符号表不完整也可能导致少量漏检。")
    else:
        print("✅ 5 大核心语义约束全部通过！")

    return result_text


# ============================================================
# 模式 A：从 JSON 架构生成 AADL
# ============================================================
def mode_from_json(args):
    """
    两阶段模式：从已有的 JSON 架构生成 AADL 代码。
    语义约束引擎预填充所有符号，约束最精确。
    """
    from aadl_generator import load_model, load_grammar
    from json_to_prompt import build_prompt as build_aadl_prompt

    # 1. 加载模型
    model, tokenizer = load_model(
        args.model,
        load_in_4bit=args.load_in_4bit,
        load_in_8bit=args.load_in_8bit,
    )

    # 2. 加载语法
    grammar_text = load_grammar(args.grammar)

    # 3. 加载架构 JSON
    with open(args.arch_json, "r", encoding="utf-8") as f:
        arch = json.load(f)
    print(f"[Input] 架构 JSON 已加载: {args.arch_json}")

    # 4. 构建 prompt（以 "package " 开头引导模型）
    prompt = build_aadl_prompt(arch, fmt=args.prompt_format)

    # 5. 停止词
    package_name = arch.get("package_name", "")
    stop_text = f"end {package_name};"

    # 6. 带语义约束生成
    aadl_code = generate_aadl_with_semantic_constraints(
        model=model,
        tokenizer=tokenizer,
        grammar_text=grammar_text,
        prompt=prompt,
        arch_json=arch,           # ← 关键：预填充符号表
        max_tokens=args.max_tokens,
        temperature=args.temperature,
        stop_text=stop_text,
        backend=args.backend,
        show_thinking=args.show_thinking,
    )

    # 7. 保存
    if args.output:
        os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(aadl_code)
        print(f"\n[Output] AADL 代码已保存: {args.output}")


# ============================================================
# 模式 B：端到端直接生成（从需求到 AADL）
# ============================================================
def mode_end_to_end(args):
    """
    端到端模式：直接从自然语言需求生成 AADL 代码。
    语义约束引擎从零开始增量积累符号表。
    """
    from aadl_generator import load_model, load_grammar

    # 1. 加载模型
    model, tokenizer = load_model(
        args.model,
        load_in_4bit=args.load_in_4bit,
        load_in_8bit=args.load_in_8bit,
    )

    # 2. 加载语法
    grammar_text = load_grammar(args.grammar)

    # 3. 构建 prompt
    requirement = args.requirement
    prompt = f"""你是一名资深 AADL 架构设计师。

请根据以下需求，设计并生成完整的 AADL 架构代码。

需求描述：
{requirement}

生成要求：
1. 先定义包，然后依次定义所有组件类型和实现
2. 每个组件需要定义 features、subcomponents、connections 等
3. 注意包含规则：system 包含 process/device/bus 等，process 包含 thread 等
4. 注意端口方向：连接的源端必须是 out/in out，目标端必须是 in/in out
5. 注意数据类型匹配：连接两端端口的数据类型必须一致

请直接输出 AADL 代码。
AADL 代码：
package """

    # 4. 端到端生成（不预填充，增量积累）
    aadl_code = generate_aadl_with_semantic_constraints(
        model=model,
        tokenizer=tokenizer,
        grammar_text=grammar_text,
        prompt=prompt,
        arch_json=None,          # ← 关键：不预填充，从零开始
        max_tokens=args.max_tokens,
        temperature=args.temperature,
        stop_text=None,          # 端到端模式下不确定包名，不用停止词
        backend=args.backend,
        show_thinking=args.show_thinking,
    )

    # 5. 保存
    if args.output:
        os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(aadl_code)
        print(f"\n[Output] AADL 代码已保存: {args.output}")


# ============================================================
# 仅验证模式（不需要模型，验证约束逻辑）
# ============================================================
def mode_verify_only(args):
    """验证约束引擎的逻辑正确性（不需要模型）"""
    print("=" * 60)
    print("约束引擎逻辑验证")
    print("=" * 60)
    print()

    # 测试用例1：合法 AADL
    print("【测试1】合法的 AADL 架构（system → process → thread）")
    test1 = """package Demo
with Base_Types;
public
  data SensorData
    end SensorData;

  thread WorkerThread
    features
      input: in data port SensorData;
      output: out data port Base_Types::Float;
    end WorkerThread;

  process MainProcess
    features
      data_in: in data port SensorData;
      result_out: out data port Base_Types::Float;
    end MainProcess;

  process implementation MainProcess.impl
    subcomponents
      worker: thread WorkerThread;
    connections
      c1: data_in -> worker.input;
      c2: worker.output -> result_out;
    end MainProcess.impl;

  system TopSystem
    features
      sensor_in: in data port SensorData;
      result_out: out data port Base_Types::Float;
    end TopSystem;

  system implementation TopSystem.impl
    subcomponents
      main_proc: process MainProcess.impl;
    connections
      c1: sensor_in -> main_proc.data_in;
      c2: main_proc.result_out -> result_out;
    end TopSystem.impl;

end Demo;
"""
    errors = check_aadl_semantics(test1)
    if not errors:
        print("  ✅ 通过：0 个错误")
    else:
        print(f"  ❌ 失败：{len(errors)} 个错误")
        for e in errors:
            print(f"      {e}")

    # 测试用例2：违反包含规则
    print("\n【测试2】违反包含规则（system 直接包含 thread）")
    test2 = """package Bad1
public
  thread BadThread
    end BadThread;

  system BadSys
    end BadSys;

  system implementation BadSys.impl
    subcomponents
      bt: thread BadThread;
    end BadSys.impl;
end Bad1;
"""
    errors2 = check_aadl_semantics(test2)
    containment_errs = [e for e in errors2 if "包含规则" in e]
    if containment_errs:
        print(f"  ✅ 正确捕获到 {len(containment_errs)} 个包含规则错误")
        for e in containment_errs:
            print(f"      {e}")
    else:
        print("  ❌ 失败：未检测到包含规则错误")

    # 测试用例3：端口方向错误
    print("\n【测试3】端口方向错误（子组件 in 端口做 source）")
    test3 = """package Bad2
with Base_Types;
public
  process ProcA
    features
      in_port: in data port Base_Types::Float;
    end ProcA;

  process ProcB
    features
      in_port: in data port Base_Types::Float;
    end ProcB;

  system Top
    end Top;

  system implementation Top.impl
    subcomponents
      pa: process ProcA;
      pb: process ProcB;
    connections
      bad: pa.in_port -> pb.in_port;
    end Top.impl;
end Bad2;
"""
    errors3 = check_aadl_semantics(test3)
    dir_errs = [e for e in errors3 if "端口方向" in e]
    if dir_errs:
        print(f"  ✅ 正确捕获到 {len(dir_errs)} 个方向错误")
        for e in dir_errs:
            print(f"      {e}")
    else:
        print("  ❌ 失败：未检测到方向错误")

    # 测试用例4：数据类型不匹配
    print("\n【测试4】数据类型不匹配（Float → Integer）")
    test4 = """package Bad3
with Base_Types;
public
  thread ThreadA
    features
      out_port: out data port Base_Types::Float;
    end ThreadA;

  thread ThreadB
    features
      in_port: in data port Base_Types::Integer;
    end ThreadB;

  process Proc
    end Proc;

  process implementation Proc.impl
    subcomponents
      ta: thread ThreadA;
      tb: thread ThreadB;
    connections
      bad: ta.out_port -> tb.in_port;
    end Proc.impl;
end Bad3;
"""
    errors4 = check_aadl_semantics(test4)
    type_errs = [e for e in errors4 if "类型匹配" in e]
    if type_errs:
        print(f"  ✅ 正确捕获到 {len(type_errs)} 个类型匹配错误")
        for e in type_errs:
            print(f"      {e}")
    else:
        print("  ❌ 失败：未检测到类型匹配错误")

    # 测试用例5：增量约束演示
    print("\n【测试5】增量约束——模拟生成 connection target")
    partial = """package Demo
with Base_Types;
public
  data SensorData
    end SensorData;

  thread Worker
    features
      sensor_in: in data port SensorData;
      result: out data port Base_Types::Float;
    end Worker;

  process MainProc
    features
      input: in data port SensorData;
      output: out data port Base_Types::Float;
    end MainProc;

  process implementation MainProc.impl
    subcomponents
      worker: thread Worker;
    connections
      c1: input -> """

    engine = AadlContextSensitiveEngine()
    engine.update(partial)
    valid_targets = engine.get_valid_values(partial)
    src_type = engine.symbols.get_port_data_type(
        "MainProc.impl", "input"
    )
    print(f"  源端 input 的数据类型：{src_type}")
    print(f"  合法 target（同类型的 in/in out 子组件端口）：")
    for v in valid_targets or []:
        print(f"    ✅ {v}")
    print(f"  说明：Worker 有两个 in 方向端口（sensor_in, result），")
    print(f"       但只有 sensor_in 是 SensorData 类型，所以被选中。")
    print(f"       result 是 Float 类型，被数据类型匹配约束过滤掉了。")

    print()
    print("=" * 60)
    print("验证完成 ✅")
    print("=" * 60)


# ============================================================
# 命令行入口
# ============================================================
def main():
    parser = argparse.ArgumentParser(
        description="AADL 生成 + 上下文敏感语义约束 集成示例"
    )
    parser.add_argument(
        "--mode", default="verify_only",
        choices=["from_json", "end_to_end", "verify_only"],
        help="运行模式：from_json（两阶段）/ end_to_end（端到端）/ verify_only（仅验证）",
    )
    parser.add_argument("--model", default=None, help="模型路径")
    parser.add_argument("--grammar", default="grammars/aadl_core.lark",
                        help="AADL EBNF 语法文件路径")
    parser.add_argument("--arch-json", default=None,
                        help="架构 JSON 文件路径（from_json 模式用）")
    parser.add_argument("--requirement", default=None,
                        help="需求描述（end_to_end 模式用）")
    parser.add_argument("--output", default=None, help="输出 AADL 文件路径")
    parser.add_argument("--max-tokens", type=int, default=4096)
    parser.add_argument("--temperature", type=float, default=0.7)
    parser.add_argument("--load-in-8bit", action="store_true")
    parser.add_argument("--load-in-4bit", action="store_true")
    parser.add_argument("--backend", default="llguidance",
                        choices=["llguidance", "xgrammar", "outlines_core"])
    parser.add_argument("--show-thinking", action="store_true")
    parser.add_argument("--prompt-format", default="by_component",
                        choices=["by_component", "by_category", "compact"])
    args = parser.parse_args()

    if args.mode == "verify_only":
        mode_verify_only(args)
    elif args.mode == "from_json":
        if not args.model or not args.arch_json:
            print("错误：from_json 模式需要 --model 和 --arch-json")
            sys.exit(1)
        mode_from_json(args)
    elif args.mode == "end_to_end":
        if not args.model or not args.requirement:
            print("错误：end_to_end 模式需要 --model 和 --requirement")
            sys.exit(1)
        mode_end_to_end(args)


if __name__ == "__main__":
    main()
