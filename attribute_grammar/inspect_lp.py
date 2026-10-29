"""查看 LLGuidanceLogitsProcessor 的 API"""
import outlines
from outlines.backends.llguidance import LLGuidanceLogitsProcessor
import inspect

print("=== 方法 ===")
for name, method in inspect.getmembers(LLGuidanceLogitsProcessor, predicate=inspect.isfunction):
    if not name.startswith("_"):
        sig = inspect.signature(method)
        print(f"  {name}{sig}")

print()
print("=== 所有公开成员 ===")
for name in dir(LLGuidanceLogitsProcessor):
    if not name.startswith("_"):
        obj = getattr(LLGuidanceLogitsProcessor, name)
        if callable(obj):
            try:
                sig = inspect.signature(obj)
                print(f"  {name}{sig}")
            except:
                print(f"  {name} (callable)")
        else:
            print(f"  {name} = {repr(obj)[:60]}")
