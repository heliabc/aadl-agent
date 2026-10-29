from aadl_semantic_full import FullAadlSemanticEngine
from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\speed-regulation\model\software.aadl"
points = find_semantic_points(fpath)

p = points[0]
text = p["prefix_text"]

engine = FullAadlSemanticEngine()
engine.update(text)

print("base_engine 符号表中的组件类型:")
print("  ", list(engine.symbols._sym.component_types.keys()))

print()
print("image_acquisition 在符号表里吗?", "image_acquisition" in engine.symbols._sym.component_types)

# 看看 base engine 是怎么解析的
# 直接调 get_valid_flow_endpoints
result = engine.symbols.get_valid_flow_endpoints("image_acquisition", "flow path", True)
print("get_valid_flow_endpoints 返回:", result)
print("数量:", len(result))
