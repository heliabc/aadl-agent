from validate_semantic_engine import find_semantic_points
from aadl_context_sensitive import AadlContextSensitiveEngine

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\ACVIP\InitialSCS.aadl"
points = find_semantic_points(fpath)

# 找第一个子组件分类器点
p = points[0]
print("=== 第一个验证点 ===")
print("类型:", p["type"])
print("期望:", p["expected"])
print()

prefix = p["prefix_text"]
print("前缀最后 500 字符:")
print(prefix[-500:])
print()

# 调用引擎
engine = AadlContextSensitiveEngine()
print("引擎 update 后状态:")
engine.update(prefix)
print("  组件类型:", list(engine.symbols.component_types.keys()))
print("  组件实现:", list(engine.symbols.component_impls.keys()))
print()

valid = engine.get_valid_values(prefix)
print("get_valid_values 返回:", valid)
if valid is not None:
    print(f"  共 {len(valid)} 个合法值")
    print("  前 10 个:", valid[:10])
else:
    print("  None (未识别到语义位置)")

# 看看位置检测器识别到了什么
detector = engine.position_detector
pos = detector.detect(prefix)
print()
print("位置检测器结果:", pos)
