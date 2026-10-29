"""分析 outlines + llguidance 的 API 和接入可能性"""
import sys
sys.path.insert(0, '/root/sj-tmp/attribute_grammar')
import inspect
import outlines
import outlines.backends.llguidance as llg_backend

print("=" * 60)
print("1. outlines 版本和模块结构")
print("=" * 60)
print(f"outlines 路径: {outlines.__file__}")
print(f"llguidance 后端路径: {llg_backend.__file__}")

print()
print("=" * 60)
print("2. LLGuidanceLogitsProcessor 完整 API")
print("=" * 60)
lp_cls = llg_backend.LLGuidanceLogitsProcessor
print(f"类名: {lp_cls.__name__}")
print(f"基类: {[c.__name__ for c in lp_cls.__bases__]}")
print()

# 所有方法（包括私有）
print("所有方法:")
for name in dir(lp_cls):
    if name.startswith('_') and not name.startswith('__'):
        obj = getattr(lp_cls, name)
        if callable(obj):
            try:
                sig = inspect.signature(obj)
                print(f"  {name}{sig}")
            except:
                print(f"  {name} (callable)")

print()
print("公开属性/方法:")
for name in dir(lp_cls):
    if not name.startswith('_'):
        obj = getattr(lp_cls, name)
        if callable(obj):
            try:
                sig = inspect.signature(obj)
                print(f"  {name}{sig}")
            except:
                print(f"  {name} (callable)")
        else:
            print(f"  {name} = {repr(obj)[:80]}")

print()
print("=" * 60)
print("3. __init__ 参数详情")
print("=" * 60)
try:
    sig = inspect.signature(lp_cls.__init__)
    print(f"__init__{sig}")
except:
    print("无法获取签名")

print()
print("=" * 60)
print("4. __call__ 方法源码（前50行）")
print("=" * 60)
try:
    src = inspect.getsource(lp_cls.__call__)
    lines = src.split('\n')[:50]
    for i, line in enumerate(lines):
        print(f"  {i+1:3d}: {line}")
except Exception as e:
    print(f"无法获取源码: {e}")

print()
print("=" * 60)
print("5. get_cfg_logits_processor 源码")
print("=" * 60)
try:
    from outlines.generator import get_cfg_logits_processor
    src = inspect.getsource(get_cfg_logits_processor)
    lines = src.split('\n')
    for i, line in enumerate(lines):
        print(f"  {i+1:3d}: {line}")
except Exception as e:
    print(f"无法获取: {e}")

print()
print("=" * 60)
print("6. generate.cfg 流程")
print("=" * 60)
try:
    from outlines.generate import cfg as cfg_gen
    src = inspect.getsource(cfg_gen)
    lines = src.split('\n')[:60]
    for i, line in enumerate(lines):
        print(f"  {i+1:3d}: {line}")
except Exception as e:
    print(f"无法获取: {e}")

print()
print("=" * 60)
print("7. llguidance 底层库 API")
print("=" * 60)
try:
    import llguidance
    print(f"llguidance 版本: {getattr(llguidance, '__version__', 'unknown')}")
    print(f"llguidance 路径: {llguidance.__file__}")
    print()
    
    # 看看有哪些类
    for name in dir(llguidance):
        if not name.startswith('_'):
            obj = getattr(llguidance, name)
            if isinstance(obj, type):
                print(f"  类: {name}")
            elif callable(obj):
                print(f"  函数: {name}")
except ImportError as e:
    print(f"导入失败: {e}")
