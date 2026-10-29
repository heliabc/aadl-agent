"""
离线分析：用精确上下文检测器扫描 LLM 生成的 AADL 代码
验证每个约束位置是否都能被正确检测到
"""
import sys
sys.path.insert(0, '/root/sj-tmp/attribute_grammar')
import re
from core.context_detector import ContextDetector

def extract_code_from_log(log_path: str) -> str:
    """从 demo 输出日志中提取生成的 AADL 代码"""
    with open(log_path, 'r') as f:
        content = f.read()
    
    # 代码在两条 ── 线之间
    lines = content.split('\n')
    in_code = False
    code_lines = []
    sep_count = 0
    
    for line in lines:
        # 检测分隔线（──────────）
        if '────────' in line:
            sep_count += 1
            if sep_count == 1:
                in_code = True
                continue
            elif sep_count == 2:
                break
        
        if in_code and line.strip():
            # 去掉 thinking 块的内容可能在前面被去掉了
            code_lines.append(line)
    
    return '\n'.join(code_lines)


def analyze_code(code: str, grammar_path: str):
    """逐位置分析，记录所有约束上下文变化"""
    detector = ContextDetector(grammar_path)
    
    print(f"代码总长度: {len(code)} 字符, {len(code.splitlines())} 行")
    print()
    
    # 逐字符扫描，记录上下文变化
    last_ctx = "none"
    transitions = []
    
    for i in range(len(code)):
        prefix = code[:i+1]
        ctx, env = detector.detect(prefix)
        
        if ctx.context_type != last_ctx:
            transitions.append((i, last_ctx, ctx.context_type, dict(ctx.info), prefix[-50:]))
            last_ctx = ctx.context_type
    
    # 打印结果
    print("=" * 70)
    print("  上下文变化点（逐字符扫描）")
    print("=" * 70)
    
    for pos, from_ctx, to_ctx, info, tail in transitions:
        info_str = ", ".join(f"{k}={v}" for k, v in info.items()) if info else "{}"
        print(f"\n  位置 {pos:4d}: {from_ctx} → {to_ctx}")
        print(f"    info: {info_str}")
        print(f"    末尾: {repr(tail)}")
    
    print()
    print("=" * 70)
    print(f"  共 {len(transitions)} 次上下文变化")
    print("=" * 70)
    
    # 统计各类约束的命中次数
    constraint_hits = {}
    for _, from_ctx, to_ctx, _, _ in transitions:
        if from_ctx == "none" and to_ctx != "none":
            constraint_hits[to_ctx] = constraint_hits.get(to_ctx, 0) + 1
    
    print("\n各类约束进入次数:")
    for ctx_type, count in sorted(constraint_hits.items()):
        print(f"  {ctx_type}: {count} 次")
    
    return transitions


if __name__ == "__main__":
    log_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/aadl_demo.log"
    grammar_path = sys.argv[2] if len(sys.argv) > 2 else "/root/sj-tmp/attribute_grammar/grammars/aadl_mvp.lark"
    
    print(f"日志文件: {log_path}")
    print(f"文法文件: {grammar_path}")
    print()
    
    code = extract_code_from_log(log_path)
    
    if not code.strip():
        print("❌ 未能从日志中提取代码")
        sys.exit(1)
    
    print("-" * 50)
    print("生成的代码（前 600 字符）:")
    print("-" * 50)
    print(code[:600])
    if len(code) > 600:
        print(f"... (共 {len(code)} 字符)")
    print("-" * 50)
    print()
    
    analyze_code(code, grammar_path)
