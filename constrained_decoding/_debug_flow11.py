import os
from aadl_semantic_full import FullPositionDetector, FullAadlSymbolTable
from aadl_context_sensitive import AadlSymbolTable
from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\speed-regulation\model\software.aadl"
points = find_semantic_points(fpath)

# 第一个未识别的 flow 点
p = points[0]
text = p["prefix_text"]
lines = text.split("\n")

print("最后 15 行:")
for i, l in enumerate(lines[-15:], 1):
    idx = len(lines) - 15 + i
    print(f"  [{idx}] {l.rstrip()}")

print()
print("反向扫描（找 type 和 end）:")
for i, line in enumerate(reversed(lines), 1):
    stripped = line.strip()
    if not stripped:
        continue
    print(f"  往上第{i}行: {stripped[:70]}")
    if stripped.lower().startswith('end '):
        print(f"    → 遇到 end，停止!")
        break
    if i > 30:
        print("    → 超过30行，停止")
        break
