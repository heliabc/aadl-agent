#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AADL GBNF 受限解码实验脚本
============================

基于 llama-cpp-python 的真正 token 级 GBNF 语法约束。

功能:
  1. 无约束生成 (baseline)
  2. GBNF 语法约束生成 (constrained)
  3. 对比实验：语法正确率、生成速度、token 数

用法:
  # 基础用法
  python aadl_gbnf_demo.py --model /path/to/model.gguf --prompt "生成一个系统"

  # 完整对比实验
  python aadl_gbnf_demo.py --model /path/to/model.gguf --experiment

  # 仅验证语法 (不生成)
  python aadl_gbnf_demo.py --validate sample.aadl

环境安装:
  pip install llama-cpp-python
  # GPU 版本 (CUDA 12.1):
  pip install llama-cpp-python --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu121
"""

import os
import sys
import time
import json
import argparse
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional, List, Dict


# ============================================================
# 语法验证器（用于评估生成质量）
# ============================================================

class AadlGrammarChecker:
    """轻量级 AADL 语法检查器，用于评估生成结果的质量。
    
    注意: 这不是完整的解析器，只是检查常见的结构性语法错误。
    用于对比实验中的质量评估指标。
    """
    
    def __init__(self):
        import re
        self.re = re
        self.patterns = {
            'pkg_start': re.compile(r'^\s*package\s+([A-Za-z_]\w*(?:::[A-Za-z_]\w*)*)\s*$', re.M),
            'pkg_end': re.compile(r'^\s*end\s+([A-Za-z_]\w*(?:::[A-Za-z_]\w*)*)\s*;\s*$', re.M),
            'comp_type': re.compile(
                r'^\s*(abstract|bus|data|device|memory|process|processor|'
                r'subprogram\s+group|subprogram|system|thread\s+group|thread|'
                r'virtual\s+bus|virtual\s+processor)\s+([A-Za-z_]\w*)\s*$', re.M
            ),
            'comp_impl': re.compile(
                r'^\s*(abstract|bus|data|device|memory|process|processor|'
                r'subprogram\s+group|subprogram|system|thread\s+group|thread|'
                r'virtual\s+bus|virtual\s+processor)\s+implementation\s+'
                r'([A-Za-z_]\w*)\s*\.\s*([A-Za-z_]\w*)\s*$', re.M
            ),
            'comp_end': re.compile(
                r'^\s*end\s+([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)?)\s*;\s*$', re.M
            ),
            'section': re.compile(
                r'^\s*(prototypes|features|subcomponents|internal features|'
                r'calls|connections|flows|modes|properties|annex)\s*$', re.M
            ),
        }
        self.type_sections = ['prototypes', 'features', 'flows', 'modes', 'properties', 'annex']
        self.impl_sections = ['prototypes', 'subcomponents', 'internal features',
                              'calls', 'connections', 'flows', 'modes', 'properties', 'annex']
    
    def check(self, code: str) -> Dict:
        """检查 AADL 代码语法，返回错误列表和分数。"""
        errors = []
        lines = code.strip().split('\n')
        
        # 1. 检查 package 结构
        pkg_name = None
        pkg_started = False
        pkg_ended = False
        comp_depth = 0
        
        for i, line in enumerate(lines):
            s = line.strip()
            
            # 跟踪组件深度
            if self.patterns['comp_type'].match(s) and not s.startswith('end '):
                comp_depth += 1
            if self.patterns['comp_impl'].match(s):
                comp_depth += 1
            if self.patterns['comp_end'].match(s) and comp_depth > 0:
                comp_depth -= 1
            
            m = self.patterns['pkg_start'].match(s)
            if m:
                pkg_started = True
                pkg_name = m.group(1)
            
            if self.patterns['pkg_end'].match(s) and comp_depth == 0:
                if not pkg_started:
                    errors.append(f"L{i+1}: end without package")
                elif m := self.patterns['pkg_end'].match(s):
                    if m.group(1) != pkg_name:
                        errors.append(f"L{i+1}: package end name mismatch: '{m.group(1)}' != '{pkg_name}'")
                pkg_ended = True
        
        if pkg_started and not pkg_ended:
            errors.append(f"package '{pkg_name}' missing end")
        
        # 2. 检查组件 start/end 匹配
        stack = []  # (name, is_impl, line)
        for i, line in enumerate(lines):
            s = line.strip()
            
            m = self.patterns['comp_type'].match(s)
            if m and not s.startswith('end '):
                stack.append((m.group(2), False, i + 1))
                continue
            
            m = self.patterns['comp_impl'].match(s)
            if m:
                full = f"{m.group(2)}.{m.group(3)}"
                stack.append((full, True, i + 1))
                continue
            
            m = self.patterns['comp_end'].match(s)
            if m and stack:
                end_name = m.group(1).replace(' ', '')
                expected, is_impl, start_line = stack.pop()
                expected_clean = expected.replace(' ', '')
                if end_name.lower() != expected_clean.lower():
                    errors.append(
                        f"L{i+1}: component end name mismatch: "
                        f"'{end_name}' != '{expected}' (declared at L{start_line})"
                    )
        
        for name, is_impl, start_line in stack:
            errors.append(f"L{start_line}: component '{name}' missing end")
        
        # 3. 检查块顺序
        in_comp = False
        is_impl = False
        sections_seen = []
        
        for i, line in enumerate(lines):
            s = line.strip()
            
            if self.patterns['comp_type'].match(s) and not s.startswith('end '):
                in_comp, is_impl, sections_seen = True, False, []
                continue
            if self.patterns['comp_impl'].match(s):
                in_comp, is_impl, sections_seen = True, True, []
                continue
            if self.patterns['comp_end'].match(s) and in_comp:
                in_comp = False
                continue
            
            if not in_comp:
                continue
            
            m = self.patterns['section'].match(s)
            if m:
                sec = m.group(1)
                valid = self.impl_sections if is_impl else self.type_sections
                
                if sec not in valid:
                    errors.append(f"L{i+1}: section '{sec}' not allowed in {'impl' if is_impl else 'type'}")
                    continue
                
                sections_seen.append(sec)
                
                # 检查顺序
                for j in range(len(sections_seen) - 1):
                    try:
                        ci = valid.index(sections_seen[j])
                        ni = valid.index(sections_seen[j + 1])
                        if ci > ni:
                            errors.append(
                                f"L{i+1}: wrong order: '{sections_seen[j+1]}' "
                                f"should come before '{sections_seen[j]}'"
                            )
                            break
                    except ValueError:
                        pass
        
        # 4. 计算分数 (0-100)
        base_score = 100
        # 每种错误扣分
        pkg_errors = sum(1 for e in errors if 'package' in e.lower())
        comp_errors = sum(1 for e in errors if 'component' in e.lower() or 'missing end' in e.lower())
        order_errors = sum(1 for e in errors if 'order' in e.lower() or 'not allowed' in e.lower())
        
        score = base_score - (pkg_errors * 15) - (comp_errors * 10) - (order_errors * 5)
        score = max(0, min(100, score))
        
        return {
            'score': score,
            'error_count': len(errors),
            'errors': errors,
            'has_package': pkg_started,
            'package_closed': pkg_ended,
            'components_count': len(stack) + comp_depth if not stack else len(stack),  # 粗略估计
        }


# ============================================================
# GBNF 受限解码器
# ============================================================

@dataclass
class GenerationResult:
    text: str
    tokens: int
    elapsed_ms: int
    tokens_per_sec: float
    finish_reason: str
    grammar_constrained: bool
    grammar_check: Dict = field(default_factory=dict)


class AadlGbnfDecoder:
    """AADL GBNF 受限解码器。
    
    核心原理:
      llama.cpp 在每个 token 生成步骤，根据 GBNF 语法计算合法 token 集合，
      将非法 token 的 logit 设为 -inf，模型只能从合法 token 中采样。
      输出保证 100% 符合语法定义。
    """
    
    def __init__(self, model_path: str, grammar_path: str, n_ctx: int = 4096, 
                 n_gpu_layers: int = -1, verbose: bool = False):
        try:
            from llama_cpp import Llama
        except ImportError:
            print("[ERROR] llama-cpp-python not installed.")
            print("  Install: pip install llama-cpp-python")
            print("  GPU version: pip install llama-cpp-python --extra-index-url "
                  "https://abetlen.github.io/llama-cpp-python/whl/cu121")
            sys.exit(1)
        
        self.grammar_path = grammar_path
        self.grammar_text = None
        self.checker = AadlGrammarChecker()
        
        # 加载语法
        if grammar_path and os.path.exists(grammar_path):
            with open(grammar_path, 'r') as f:
                self.grammar_text = f.read()
            print(f"[INFO] Loaded GBNF grammar: {grammar_path} ({len(self.grammar_text)} chars)")
        
        # 加载模型
        print(f"[INFO] Loading model: {model_path}")
        t0 = time.time()
        self.llm = Llama(
            model_path=model_path,
            n_ctx=n_ctx,
            n_gpu_layers=n_gpu_layers,
            verbose=verbose,
        )
        print(f"[INFO] Model loaded in {time.time()-t0:.1f}s (ctx={n_ctx})")
    
    def generate(self, prompt: str, max_tokens: int = 2048, 
                 temperature: float = 0.1, top_p: float = 0.95,
                 use_grammar: bool = True, grammar_root: str = "root") -> GenerationResult:
        """生成 AADL 代码。
        
        Args:
            prompt: 提示词
            max_tokens: 最大生成 token 数
            temperature: 温度
            top_p: 核采样参数
            use_grammar: 是否启用 GBNF 语法约束
            grammar_root: GBNF 根规则名
        
        Returns:
            GenerationResult
        """
        t0 = time.time()
        
        kwargs = dict(
            prompt=prompt,
            max_tokens=max_tokens,
            temperature=temperature,
            top_p=top_p,
            echo=False,
            stop=["</s>", "<|endoftext|>", "```"],
        )
        
        if use_grammar and self.grammar_text:
            kwargs['grammar'] = self.grammar_text
            kwargs['grammar_root'] = grammar_root
        
        output = self.llm(**kwargs)
        
        elapsed = time.time() - t0
        text = output['choices'][0]['text']
        
        # 统计 token 数
        try:
            tokens = len(output['choices'][0]['logprobs']['tokens'])
        except (KeyError, TypeError):
            # 用粗略估计
            tokens = len(text) // 3
        
        finish_reason = output['choices'][0].get('finish_reason', 'unknown')
        
        # 语法检查
        grammar_check = self.checker.check(text)
        
        result = GenerationResult(
            text=text,
            tokens=tokens,
            elapsed_ms=int(elapsed * 1000),
            tokens_per_sec=tokens / elapsed if elapsed > 0 else 0,
            finish_reason=finish_reason,
            grammar_constrained=use_grammar,
            grammar_check=grammar_check,
        )
        
        return result


# ============================================================
# 对比实验
# ============================================================

def run_comparison_experiment(decoder: AadlGbnfDecoder, prompts: List[Dict], 
                              max_tokens: int = 2048, temperature: float = 0.1):
    """运行有约束 vs 无约束的对比实验。"""
    
    print("\n" + "=" * 70)
    print("  AADL GBNF 受限解码对比实验")
    print("=" * 70)
    
    results = []
    
    for i, prompt_info in enumerate(prompts):
        name = prompt_info['name']
        prompt = prompt_info['prompt']
        
        print(f"\n{'─' * 70}")
        print(f"  实验 {i+1}/{len(prompts)}: {name}")
        print(f"  Prompt 长度: {len(prompt)} chars")
        print(f"{'─' * 70}")
        
        # 无约束 (baseline)
        print("\n  [无约束 Baseline] 生成中...", end=" ", flush=True)
        baseline = decoder.generate(
            prompt, max_tokens=max_tokens, temperature=temperature,
            use_grammar=False
        )
        print(f"完成 ({baseline.tokens} tok, {baseline.tokens_per_sec:.1f} tok/s)")
        print(f"    语法分数: {baseline.grammar_check['score']}/100  "
              f"错误数: {baseline.grammar_check['error_count']}")
        
        # 有约束 (GBNF)
        print(f"  [GBNF 约束] 生成中...", end=" ", flush=True)
        constrained = decoder.generate(
            prompt, max_tokens=max_tokens, temperature=temperature,
            use_grammar=True
        )
        print(f"完成 ({constrained.tokens} tok, {constrained.tokens_per_sec:.1f} tok/s)")
        print(f"    语法分数: {constrained.grammar_check['score']}/100  "
              f"错误数: {constrained.grammar_check['error_count']}")
        
        results.append({
            'name': name,
            'baseline': baseline,
            'constrained': constrained,
        })
    
    # 汇总
    print("\n" + "=" * 70)
    print("  实验结果汇总")
    print("=" * 70)
    
    print(f"\n  {'实验名称':<20} {'模式':<12} {'语法分':>8} {'错误数':>8} {'速度(tok/s)':>12} {'Token数':>10}")
    print(f"  {'-'*20} {'-'*12} {'-'*8} {'-'*8} {'-'*12} {'-'*10}")
    
    for r in results:
        b = r['baseline']
        c = r['constrained']
        print(f"  {r['name']:<20} {'无约束':<12} {b.grammar_check['score']:>7.0f} "
              f"{b.grammar_check['error_count']:>8} {b.tokens_per_sec:>12.1f} {b.tokens:>10}")
        print(f"  {'':<20} {'GBNF约束':<12} {c.grammar_check['score']:>7.0f} "
              f"{c.grammar_check['error_count']:>8} {c.tokens_per_sec:>12.1f} {c.tokens:>10}")
        
        # 改进百分比
        if b.grammar_check['error_count'] > 0:
            improvement = (b.grammar_check['error_count'] - c.grammar_check['error_count']) / \
                         b.grammar_check['error_count'] * 100
            print(f"  {'':<20} {'改进':<12} {'':>8} {improvement:>+7.1f}% {'':>12} {'':>10}")
    
    # 保存详细结果
    output_data = []
    for r in results:
        output_data.append({
            'name': r['name'],
            'baseline': {
                'text': r['baseline'].text,
                'tokens': r['baseline'].tokens,
                'tokens_per_sec': r['baseline'].tokens_per_sec,
                'grammar_check': r['baseline'].grammar_check,
            },
            'constrained': {
                'text': r['constrained'].text,
                'tokens': r['constrained'].tokens,
                'tokens_per_sec': r['constrained'].tokens_per_sec,
                'grammar_check': r['constrained'].grammar_check,
            }
        })
    
    out_file = 'gbnf_experiment_results.json'
    with open(out_file, 'w', encoding='utf-8') as f:
        json.dump(output_data, f, ensure_ascii=False, indent=2)
    print(f"\n  详细结果已保存到: {out_file}")
    
    return results


# ============================================================
# 默认测试用例
# ============================================================

DEFAULT_PROMPTS = [
    {
        'name': '简单系统',
        'prompt': """请生成一个简单的 AADL 包。

要求：
- 包名: SimpleDemo
- 包含一个系统组件类型 MySystem
- MySystem 有一个输入数据端口 dataIn 和一个输出数据端口 dataOut
- 包含一个系统实现 MySystem.impl

请直接输出 AADL 代码。
""",
    },
    {
        'name': '带连接的系统',
        'prompt': """请生成一个 AADL 包，描述一个传感器-处理器-执行器系统。

要求：
- 包名: SensorSystem
- 数据组件: SensorData (data), ActuatorData (data)
- 系统类型: ControlSystem，有 in 端口 sensor_input (data port SensorData), 
  out 端口 actuator_output (data port ActuatorData)
- 系统实现: ControlSystem.impl，包含子组件:
  - sensor: device SensorData
  - processor: process Processor
  - actuator: device ActuatorData
- 在 connections 块中建立端口连接

请直接输出完整的 AADL 代码。
""",
    },
    {
        'name': '多组件包',
        'prompt': """请生成一个包含多个组件的 AADL 包。

要求：
- 包名: FlightControl
- 包含以下组件类型:
  - 数据组件: SensorValue, ControlCommand
  - 系统类型: FlightController (in 端口 sensor_in: data port SensorValue, 
    out 端口 cmd_out: data port ControlCommand)
  - 线程类型: ControlThread
- 包含 FlightController 的实现，有子组件和连接

请直接输出 AADL 代码。
""",
    },
]


# ============================================================
# 主函数
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description='AADL GBNF Constrained Decoding Demo',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 单次生成 (有约束)
  python aadl_gbnf_demo.py --model model.gguf --prompt "生成一个系统"

  # 对比实验
  python aadl_gbnf_demo.py --model model.gguf --experiment

  # 自定义 prompt 文件
  python aadl_gbnf_demo.py --model model.gguf --prompt-file prompt.txt

  # 指定语法文件
  python aadl_gbnf_demo.py --model model.gguf --grammar aadl_core.gbnf --experiment
        """
    )
    
    parser.add_argument('--model', required=True, help='GGUF 模型文件路径')
    parser.add_argument('--grammar', default=None, help='GBNF 语法文件路径 (默认: grammars/aadl_core.gbnf)')
    parser.add_argument('--prompt', default=None, help='单次生成的提示词')
    parser.add_argument('--prompt-file', default=None, help='从文件读取提示词')
    parser.add_argument('--experiment', action='store_true', help='运行对比实验')
    parser.add_argument('--max-tokens', type=int, default=2048, help='最大生成 token 数')
    parser.add_argument('--temperature', type=float, default=0.1, help='温度')
    parser.add_argument('--n-ctx', type=int, default=4096, help='上下文窗口')
    parser.add_argument('--n-gpu-layers', type=int, default=-1, help='GPU 层数 (-1=全部)')
    parser.add_argument('--validate', default=None, help='验证指定 AADL 文件的语法')
    parser.add_argument('--output', default=None, help='输出结果保存路径')
    
    args = parser.parse_args()
    
    # 仅验证模式
    if args.validate:
        checker = AadlGrammarChecker()
        with open(args.validate, 'r') as f:
            code = f.read()
        result = checker.check(code)
        print(f"语法分数: {result['score']}/100")
        print(f"错误数: {result['error_count']}")
        for e in result['errors']:
            print(f"  - {e}")
        return
    
    # 确定语法文件路径
    grammar_path = args.grammar
    if not grammar_path:
        # 尝试默认路径
        script_dir = Path(__file__).parent
        default_grammar = script_dir / 'grammars' / 'aadl_core.gbnf'
        if default_grammar.exists():
            grammar_path = str(default_grammar)
        else:
            print("[WARN] No grammar file found. GBNF constraint will not be available.")
    
    # 初始化解码器
    decoder = AadlGbnfDecoder(
        model_path=args.model,
        grammar_path=grammar_path,
        n_ctx=args.n_ctx,
        n_gpu_layers=args.n_gpu_layers,
    )
    
    # 单次生成模式
    if args.prompt or args.prompt_file:
        prompt = args.prompt
        if args.prompt_file:
            with open(args.prompt_file, 'r') as f:
                prompt = f.read()
        
        print(f"\nPrompt: {prompt[:100]}...\n")
        
        print("[GBNF Constrained] Generating...")
        result = decoder.generate(
            prompt, 
            max_tokens=args.max_tokens,
            temperature=args.temperature,
            use_grammar=True
        )
        
        print(f"\n{'='*60}")
        print(f"  生成结果 (GBNF 约束)")
        print(f"{'='*60}")
        print(f"  Tokens: {result.tokens}")
        print(f"  速度: {result.tokens_per_sec:.1f} tok/s")
        print(f"  语法分: {result.grammar_check['score']}/100")
        print(f"  错误数: {result.grammar_check['error_count']}")
        if result.grammar_check['errors']:
            print(f"  错误列表:")
            for e in result.grammar_check['errors'][:5]:
                print(f"    - {e}")
        print(f"\n  --- 生成代码 ---")
        print(result.text)
        print(f"  --- 结束 ---")
        
        if args.output:
            with open(args.output, 'w', encoding='utf-8') as f:
                f.write(result.text)
            print(f"\n结果已保存到: {args.output}")
        
        return
    
    # 对比实验模式
    if args.experiment:
        run_comparison_experiment(
            decoder, DEFAULT_PROMPTS,
            max_tokens=args.max_tokens,
            temperature=args.temperature
        )
        return
    
    # 什么都没指定
    print("[INFO] No action specified. Use --prompt, --prompt-file, --experiment, or --validate.")
    print("       Run with --help for details.")


if __name__ == '__main__':
    main()
