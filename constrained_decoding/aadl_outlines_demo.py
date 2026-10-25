#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AADL 约束生成 - Outlines + Transformers 版本
===============================================
使用 Outlines 库的 Lark EBNF 语法做约束解码，
避免 llama.cpp GBNF 的段错误问题。

用法:
  # 模式1：自然语言需求 -> AADL
  python aadl_outlines_demo.py --model ~/autodl-tmp/qwen3-14b \
      --grammar grammars/aadl_core.lark \
      --prompt "生成一个飞行控制系统" --chat

  # 模式2：直接传 prompt 原文（raw-prompt）
  python aadl_outlines_demo.py --model ~/autodl-tmp/qwen3-14b \
      --grammar grammars/aadl_core.lark \
      --prompt "完整的prompt内容..." --raw-prompt --chat

依赖:
  pip install outlines transformers accelerate torch bitsandbytes llguidance
"""

import os
import sys
import time
import argparse


def load_grammar(grammar_path: str) -> str:
    """加载 Lark EBNF 语法文件"""
    with open(grammar_path, "r", encoding="utf-8") as f:
        grammar = f.read()
    print(f"[Grammar] 已加载: {grammar_path} ({len(grammar)} 字符)")
    return grammar


def verify_grammar(grammar_text: str) -> bool:
    """用 lark 验证语法是否正确（不依赖模型）"""
    try:
        from lark import Lark
        parser = Lark(grammar_text, start="start")
        print("[Grammar] Lark 解析验证通过 ✓")
        return True
    except Exception as e:
        print(f"[Grammar] 语法错误: {e}")
        return False


def load_model(model_name: str, load_in_8bit: bool = False, load_in_4bit: bool = False):
    """加载 HuggingFace 模型"""
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    print(f"[Model] 正在加载: {model_name}")
    t0 = time.time()

    tokenizer = AutoTokenizer.from_pretrained(model_name)

    model_kwargs = {
        "device_map": "auto",
        "dtype": torch.float16,
    }

    if load_in_8bit:
        try:
            from transformers import BitsAndBytesConfig
            model_kwargs["quantization_config"] = BitsAndBytesConfig(load_in_8bit=True)
            print("[Model] 使用 8bit 量化（BitsAndBytesConfig）")
        except ImportError:
            model_kwargs["load_in_8bit"] = True
            print("[Model] 使用 8bit 量化（旧版 API）")

    if load_in_4bit:
        try:
            from transformers import BitsAndBytesConfig
            model_kwargs["quantization_config"] = BitsAndBytesConfig(load_in_4bit=True)
            print("[Model] 使用 4bit 量化（BitsAndBytesConfig）")
        except ImportError:
            model_kwargs["load_in_4bit"] = True
            print("[Model] 使用 4bit 量化（旧版 API）")

    model = AutoModelForCausalLM.from_pretrained(model_name, **model_kwargs)

    elapsed = time.time() - t0
    try:
        vram = torch.cuda.memory_allocated() / 1024**3
        print(f"[Model] 加载完成，耗时 {elapsed:.1f}s，显存 {vram:.1f} GB")
    except Exception:
        print(f"[Model] 加载完成，耗时 {elapsed:.1f}s")
    return model, tokenizer


def build_chat_prompt(user_content: str, tokenizer,
                      system_content: str = "你是 AADL 代码生成专家，严格按语法输出完整的 AADL 代码。") -> str:
    """用模型的 chat template 包装 user message

    适用于 Instruct/Chat 模型。
    返回的 prompt 以 assistant 的开头结束，CFG 从下一个 token 开始约束。
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


def generate_native(model, tokenizer, prompt: str,
                    max_tokens: int = 2048,
                    temperature: float = 0.7,
                    show_thinking: bool = False) -> str:
    """无约束原生生成（用于对比）"""
    import torch

    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)

    gen_kwargs = {
        "max_new_tokens": max_tokens,
        "temperature": temperature,
        "do_sample": temperature > 0,
        "top_p": 0.9,
    }

    # 流式生成
    gen_kwargs["pad_token_id"] = tokenizer.eos_token_id

    generated_ids = inputs["input_ids"].clone()
    generated_text = ""

    from transformers import set_seed
    if temperature == 0:
        set_seed(42)

    # 逐 token 生成
    for i in range(max_tokens):
        with torch.no_grad():
            outputs = model(generated_ids)
            logits = outputs.logits[:, -1, :]

        if temperature > 0:
            logits = logits / temperature
            probs = torch.softmax(logits, dim=-1)
            next_token = torch.multinomial(probs, num_samples=1)
        else:
            next_token = torch.argmax(logits, dim=-1, keepdim=True)

        generated_ids = torch.cat([generated_ids, next_token], dim=-1)

        # 解码新 token
        new_text = tokenizer.decode(
            generated_ids[0][inputs["input_ids"].shape[1]:],
            skip_special_tokens=True,
        )

        # 打印增量
        delta = new_text[len(generated_text):]
        print(delta, end="", flush=True)
        generated_text = new_text

        # eos 检测
        if next_token.item() == tokenizer.eos_token_id:
            break

    print()

    # 去掉 Qwen3 的 thinking 块（默认）
    if not show_thinking:
        import re
        generated_text = re.sub(r'<think>.*?</think>', '', generated_text,
                                flags=re.DOTALL).strip()

    return generated_text


def generate_with_outlines(model, tokenizer, grammar_text: str,
                           prompt: str, max_tokens: int = 2048,
                           temperature: float = 0.7,
                           backend_name: str = None,
                           show_thinking: bool = False) -> str:
    """使用 Outlines CFG 约束生成

    用 transformers 的 generate + outlines 的 logits_processor。
    """
    import torch
    import outlines
    from outlines.generator import get_cfg_logits_processor

    print("[Generate] 编译语法到 FSM...")
    t0 = time.time()

    # 1. 包装模型为 outlines 的 Transformers 对象
    outlines_model = outlines.from_transformers(model, tokenizer)

    # 2. 获取 CFG logits processor
    logits_processor = get_cfg_logits_processor(
        backend_name=backend_name,
        model=outlines_model,
        grammar=grammar_text,
    )

    compile_time = time.time() - t0
    backend_display = backend_name if backend_name else "auto"
    print(f"[Generate] FSM 编译完成（后端: {backend_display}），耗时 {compile_time:.1f}s")

    # 3. 流式生成（逐 token 打印）
    print(f"[Generate] 开始生成 (max_tokens={max_tokens}, temp={temperature})...")
    print()
    print("─" * 60)
    t1 = time.time()

    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)

    # logits_processor 需要是列表
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
    result = "".join(generated_text)

    print()
    print("─" * 60)
    gen_time = time.time() - t1
    num_tokens = len(tokenizer.encode(result))
    speed = num_tokens / gen_time if gen_time > 0 else 0
    print(f"\n[Generate] 生成完成，约 {num_tokens} tokens，耗时 {gen_time:.1f}s ({speed:.1f} tokens/s)")

    # 去掉 Qwen3 的 thinking 块（默认）
    if not show_thinking:
        import re
        result = re.sub(r'<think>.*?</think>', '', result, flags=re.DOTALL).strip()

    return result


def main():
    parser = argparse.ArgumentParser(description="AADL Outlines 约束生成")
    parser.add_argument("--model", default="~/autodl-tmp/qwen3-14b",
                        help="HuggingFace 模型名或本地路径")
    parser.add_argument("--grammar", default="grammars/aadl_core.lark",
                        help="Lark EBNF 语法文件路径")
    parser.add_argument("--prompt", default=None, help="输入文本（需求或完整 prompt）")
    parser.add_argument("--prompt-file", default=None,
                        help="从文件读取 prompt（与 --prompt 二选一）")
    parser.add_argument("--package-name", default="GeneratedSystem",
                        help="生成的包名（用于 prompt 模板）")
    parser.add_argument("--output", default=None, help="保存输出到文件")
    parser.add_argument("--max-tokens", type=int, default=2048)
    parser.add_argument("--temperature", type=float, default=0.7)
    parser.add_argument("--load-in-8bit", action="store_true",
                        help="使用 8bit 量化")
    parser.add_argument("--load-in-4bit", action="store_true",
                        help="使用 4bit 量化")
    parser.add_argument("--verify-only", action="store_true",
                        help="只验证语法，不加载模型")
    parser.add_argument("--raw-prompt", action="store_true",
                        help="直接使用 prompt 原文当 user 消息内容，不套模板")
    parser.add_argument("--chat", action="store_true",
                        help="使用 Chat Template 格式（Instruct 模型必选）")
    parser.add_argument("--backend", default=None,
                        choices=["llguidance", "xgrammar", "outlines_core", None],
                        help="CFG 约束解码后端（默认自动选择）")
    parser.add_argument("--no-constraint", action="store_true",
                        help="不使用 CFG 约束，原生生成（用于对比）")
    parser.add_argument("--show-thinking", action="store_true",
                        help="保留 Qwen3 的 thinking 输出（默认去掉）")
    args = parser.parse_args()

    # 1. 加载并验证语法
    grammar_text = load_grammar(args.grammar)

    if not verify_grammar(grammar_text):
        print("语法验证失败，退出。")
        sys.exit(1)

    if args.verify_only:
        print("语法验证通过，退出（--verify-only）。")
        return

    # 2. 读取 prompt
    if args.prompt_file:
        with open(args.prompt_file, "r", encoding="utf-8") as f:
            prompt_text = f.read()
        print(f"[Prompt] 从文件加载: {args.prompt_file} ({len(prompt_text)} 字符)")
    elif args.prompt:
        prompt_text = args.prompt
    else:
        print("错误：必须指定 --prompt 或 --prompt-file")
        sys.exit(1)

    # 3. 加载模型
    model, tokenizer = load_model(args.model, load_in_8bit=args.load_in_8bit,
                                   load_in_4bit=args.load_in_4bit)

    # 4. 构建 prompt
    if args.chat:
        # Chat 模式：用 chat template 包装
        final_prompt = build_chat_prompt(prompt_text, tokenizer)
        prompt_tokens = len(tokenizer.encode(final_prompt))
        print(f"[Prompt] Chat 模式，prompt 长度: {prompt_tokens} tokens")
    else:
        # Base 模式：直接用 prompt
        final_prompt = prompt_text

    print()
    print("=" * 60)
    prompt_preview = prompt_text[:120].replace("\n", " ")
    if len(prompt_text) > 120:
        prompt_preview += "..."
    print(f"输入: {prompt_preview}")
    print("=" * 60)
    print()

    try:
        if args.no_constraint:
            # 无约束模式：原生生成
            print("[Generate] 无约束模式（原生生成）...")
            print(f"[Generate] max_tokens={args.max_tokens}, temp={args.temperature}")
            print()
            print("─" * 60)
            result = generate_native(
                model=model,
                tokenizer=tokenizer,
                prompt=final_prompt,
                max_tokens=args.max_tokens,
                temperature=args.temperature,
                show_thinking=args.show_thinking,
            )
        else:
            # CFG 约束模式
            result = generate_with_outlines(
                model=model,
                tokenizer=tokenizer,
                grammar_text=grammar_text,
                prompt=final_prompt,
                max_tokens=args.max_tokens,
                temperature=args.temperature,
                backend_name=args.backend,
                show_thinking=args.show_thinking,
            )

        print()
        print("=" * 60)
        print("生成结果:")
        print("=" * 60)
        print(result)
        print("=" * 60)

        if args.output:
            with open(args.output, "w", encoding="utf-8") as f:
                f.write(result)
            print(f"[Output] 已保存到: {args.output}")

    except Exception as e:
        print(f"[Error] 生成失败: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
