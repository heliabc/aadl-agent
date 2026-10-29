from aadl_semantic_full import FullPositionDetector, FullAadlSymbolTable
from aadl_context_sensitive import AadlSymbolTable
from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\speed-regulation\model\software.aadl"
points = find_semantic_points(fpath)

# 第一个 flow 端点
p = None
for pt in points:
    if pt["type"] == "flow_endpoint":
        p = pt
        break

text = p["prefix_text"]
lines = text.split("\n")

sym = FullAadlSymbolTable(AadlSymbolTable())
det = FullPositionDetector(sym)

print("最后 5 行:")
for i, l in enumerate(lines[-5:], 1):
    print(f"  [{len(lines)-5+i}] {repr(l)}")

print()
print("in_flows:", det._is_in_section(lines, "flows"))
print("in_features:", det._is_in_section(lines, "features"))
print("current_type_name:", det._get_current_type_name(lines))
print("current_impl_name:", det._get_current_impl_name(lines))

print()
print("=== 反向扫描（找 type）===")
for i, line in enumerate(reversed(lines[-20:]), 1):
    stripped = line.strip()
    if not stripped:
        continue
    print(f"  {i}: {repr(stripped[:60])}")
