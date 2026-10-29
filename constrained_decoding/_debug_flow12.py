from aadl_semantic_full import FullPositionDetector, FullAadlSymbolTable
from aadl_context_sensitive import AadlSymbolTable
from validate_semantic_full import find_semantic_points
import re

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\speed-regulation\model\software.aadl"
points = find_semantic_points(fpath)

# 找到第一个未识别的 flow 点
sym = FullAadlSymbolTable(AadlSymbolTable())
det = FullPositionDetector(sym)

first_unrec = None
for p in points:
    if p["type"] != "flow_endpoint":
        continue
    lines = p["prefix_text"].split("\n")
    ct = det._get_current_type_name(lines)
    if ct is None:
        first_unrec = p
        break

if first_unrec:
    print("第一个未识别的 flow 点:")
    print("  位置:", first_unrec["location"])
    print("  期望:", first_unrec["expected"])
    lines = first_unrec["prefix_text"].split("\n")
    print(f"  共 {len(lines)} 行")
    print()

    # 逐行反向扫描，看看卡在哪
    print("逐行反向扫描:")
    for i, line in enumerate(reversed(lines), 1):
        stripped = line.strip()
        if not stripped:
            continue
        print(f"  往上{i}: {stripped[:70]}")

        # 看是不是 end
        end_m = re.match(r'^end\s+\w+(?:\.\w+)?\s*;?\s*$', stripped)
        if end_m:
            print(f"    → 匹配 end，停止!")
            break

        # 看是不是类型声明
        cat_re = r'(?:system|process|thread|thread\s+group|subprogram|subprogram\s+group|data|device|processor|virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
        type_m = re.match(rf'^{cat_re}\s+(\w+)\s*$', stripped)
        if type_m and 'implementation' not in stripped:
            print(f"    → 匹配类型: {type_m.group(1)}")
            break

        if i > 30:
            print("    → 超过30行")
            break
else:
    print("所有 flow 点都能识别到！")
