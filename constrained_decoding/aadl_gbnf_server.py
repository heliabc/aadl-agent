#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AADL GBNF 受限解码 HTTP 服务
===============================
基于 llama-cpp-python + GBNF 语法的真正 token 级约束解码服务。

提供 REST API，供 Java/Spring Boot 端调用。

启动:
  python aadl_gbnf_server.py --model /path/to/model.gguf [--grammar grammars/aadl_core.gbnf]

API:
  GET  /health               健康检查
  POST /api/generate         生成 AADL 代码（GBNF 约束）
  POST /api/generate/baseline  无约束生成（用于对比）
  POST /api/validate         验证 AADL 代码语法
  GET  /api/grammar          获取当前语法信息
"""

import os
import sys
import time
import json
import logging
from pathlib import Path
from typing import Optional

from flask import Flask, request, jsonify

logging.basicConfig(level=logging.INFO, 
                    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger('aadl-gbnf-server')

app = Flask(__name__)

# 全局实例
_decoder = None
_checker = None


def init_decoder(model_path: str, grammar_path: str, n_ctx: int = 4096, 
                 n_gpu_layers: int = -1):
    """初始化解码器。"""
    global _decoder, _checker
    
    try:
        from llama_cpp import Llama
    except ImportError:
        logger.error("llama-cpp-python is not installed!")
        logger.error("Install: pip install llama-cpp-python")
        sys.exit(1)
    
    # 加载语法
    grammar_text = None
    if grammar_path and os.path.exists(grammar_path):
        with open(grammar_path, 'r', encoding='utf-8') as f:
            grammar_text = f.read()
        logger.info(f"GBNF grammar loaded: {grammar_path} ({len(grammar_text)} chars)")
    else:
        logger.warning(f"Grammar file not found: {grammar_path}")
    
    # 加载模型
    logger.info(f"Loading model: {model_path}")
    t0 = time.time()
    llm = Llama(
        model_path=model_path,
        n_ctx=n_ctx,
        n_gpu_layers=n_gpu_layers,
        verbose=False,
    )
    logger.info(f"Model loaded in {time.time()-t0:.1f}s")
    
    _decoder = llm
    _decoder.grammar_text = grammar_text
    
    # 初始化检查器
    from aadl_gbnf_demo import AadlGrammarChecker
    _checker = AadlGrammarChecker()
    
    return llm, grammar_text


# ============================================================
# API Routes
# ============================================================

@app.route('/health', methods=['GET'])
def health():
    return jsonify({
        'status': 'ok',
        'service': 'aadl-gbnf-constrained-decoder',
        'model_loaded': _decoder is not None,
        'grammar_loaded': _decoder.grammar_text is not None if _decoder else False,
    })


@app.route('/api/generate', methods=['POST'])
def generate_constrained():
    """GBNF 约束生成。"""
    if _decoder is None:
        return jsonify({'error': 'Model not loaded'}), 503
    
    data = request.json or {}
    prompt = data.get('prompt', '')
    max_tokens = data.get('max_tokens', 2048)
    temperature = data.get('temperature', 0.1)
    top_p = data.get('top_p', 0.95)
    grammar_root = data.get('grammar_root', 'root')
    custom_grammar = data.get('grammar')
    
    if not prompt:
        return jsonify({'error': 'prompt is required'}), 400
    
    grammar_to_use = custom_grammar or (_decoder.grammar_text if hasattr(_decoder, 'grammar_text') else None)
    if not grammar_to_use:
        return jsonify({'error': 'No grammar available for constrained generation'}), 500
    
    t0 = time.time()
    
    try:
        output = _decoder(
            prompt=prompt,
            max_tokens=max_tokens,
            temperature=temperature,
            top_p=top_p,
            grammar=grammar_to_use,
            grammar_root=grammar_root,
            echo=False,
            stop=["</s>", "<|endoftext|>"],
        )
    except Exception as e:
        logger.error(f"Generation failed: {e}")
        return jsonify({'error': str(e)}), 500
    
    elapsed = time.time() - t0
    text = output['choices'][0]['text']
    finish_reason = output['choices'][0].get('finish_reason', 'unknown')
    
    # 统计 token 数
    try:
        tokens = len(output['choices'][0]['logprobs']['tokens'])
    except (KeyError, TypeError):
        tokens = len(text) // 3  # 粗略估计
    
    # 语法检查
    grammar_check = _checker.check(text) if _checker else {}
    
    result = {
        'success': True,
        'text': text,
        'tokens_generated': tokens,
        'elapsed_ms': int(elapsed * 1000),
        'tokens_per_sec': round(tokens / elapsed, 2) if elapsed > 0 else 0,
        'finish_reason': finish_reason,
        'grammar_constrained': True,
        'grammar_check': grammar_check,
    }
    
    logger.info(f"Constrained generation: {tokens} tok, {tokens/elapsed:.1f} tok/s, "
                f"score={grammar_check.get('score', '?')}/100, "
                f"errors={grammar_check.get('error_count', '?')}")
    
    return jsonify(result)


@app.route('/api/generate/baseline', methods=['POST'])
def generate_baseline():
    """无约束生成（用于对比实验）。"""
    if _decoder is None:
        return jsonify({'error': 'Model not loaded'}), 503
    
    data = request.json or {}
    prompt = data.get('prompt', '')
    max_tokens = data.get('max_tokens', 2048)
    temperature = data.get('temperature', 0.1)
    top_p = data.get('top_p', 0.95)
    
    if not prompt:
        return jsonify({'error': 'prompt is required'}), 400
    
    t0 = time.time()
    
    try:
        output = _decoder(
            prompt=prompt,
            max_tokens=max_tokens,
            temperature=temperature,
            top_p=top_p,
            echo=False,
            stop=["</s>", "<|endoftext|>"],
        )
    except Exception as e:
        logger.error(f"Generation failed: {e}")
        return jsonify({'error': str(e)}), 500
    
    elapsed = time.time() - t0
    text = output['choices'][0]['text']
    finish_reason = output['choices'][0].get('finish_reason', 'unknown')
    
    try:
        tokens = len(output['choices'][0]['logprobs']['tokens'])
    except (KeyError, TypeError):
        tokens = len(text) // 3
    
    grammar_check = _checker.check(text) if _checker else {}
    
    result = {
        'success': True,
        'text': text,
        'tokens_generated': tokens,
        'elapsed_ms': int(elapsed * 1000),
        'tokens_per_sec': round(tokens / elapsed, 2) if elapsed > 0 else 0,
        'finish_reason': finish_reason,
        'grammar_constrained': False,
        'grammar_check': grammar_check,
    }
    
    logger.info(f"Baseline generation: {tokens} tok, {tokens/elapsed:.1f} tok/s, "
                f"score={grammar_check.get('score', '?')}/100, "
                f"errors={grammar_check.get('error_count', '?')}")
    
    return jsonify(result)


@app.route('/api/validate', methods=['POST'])
def validate():
    """验证 AADL 代码语法。"""
    if _checker is None:
        return jsonify({'error': 'Checker not available'}), 503
    
    data = request.json or {}
    code = data.get('code', '')
    
    if not code:
        return jsonify({'error': 'code is required'}), 400
    
    result = _checker.check(code)
    return jsonify(result)


@app.route('/api/grammar', methods=['GET'])
def grammar_info():
    """获取语法信息。"""
    grammar_text = _decoder.grammar_text if _decoder and hasattr(_decoder, 'grammar_text') else None
    return jsonify({
        'grammar_loaded': grammar_text is not None,
        'grammar_size': len(grammar_text) if grammar_text else 0,
        'grammar_preview': grammar_text[:300] + '...' if grammar_text else None,
    })


# ============================================================
# 启动
# ============================================================

def main():
    import argparse
    
    parser = argparse.ArgumentParser(description='AADL GBNF Constrained Decoding Server')
    parser.add_argument('--host', default='0.0.0.0', help='监听地址')
    parser.add_argument('--port', type=int, default=8082, help='端口')
    parser.add_argument('--model', required=True, help='GGUF 模型文件路径')
    parser.add_argument('--grammar', default=None, help='GBNF 语法文件路径')
    parser.add_argument('--n-ctx', type=int, default=4096, help='上下文窗口')
    parser.add_argument('--n-gpu-layers', type=int, default=-1, help='GPU 层数 (-1=全部)')
    
    args = parser.parse_args()
    
    # 找语法文件
    grammar_path = args.grammar
    if not grammar_path:
        script_dir = Path(__file__).parent
        default = script_dir / 'grammars' / 'aadl_core.gbnf'
        if default.exists():
            grammar_path = str(default)
    
    # 初始化解码器
    init_decoder(args.model, grammar_path, args.n_ctx, args.n_gpu_layers)
    
    # 启动服务
    logger.info(f"Starting AADL GBNF server on {args.host}:{args.port}")
    app.run(host=args.host, port=args.port, debug=False, threaded=False)


if __name__ == '__main__':
    main()
