import os
from collections import defaultdict
from validate_semantic_full import find_semantic_points
from aadl_semantic_full import FullAadlSemanticEngine

root = r"C:\Users\heli0\Desktop\osate-examples"

# 按类别统计失败的属性
failed_by_cat = defaultdict(lambda: defaultdict(int))
total_by_cat = defaultdict(int)
passed_by_cat = defaultdict(int)

for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] != "property_name":
                continue
            # 判断类别
            cat = p.get("category", "unknown")
            total_by_cat[cat] += 1
            engine = FullAadlSemanticEngine()
            valid = engine.get_valid_values(p["prefix_text"])
            if valid is None or len(valid) == 0:
                failed_by_cat[cat][p["expected"]] += 1
            else:
                exp_lower = p["expected"].lower()
                if exp_lower in [v.lower() for v in valid]:
                    passed_by_cat[cat] += 1
                else:
                    failed_by_cat[cat][p["expected"]] += 1

print("按类别统计:")
for cat in sorted(total_by_cat.keys()):
    total = total_by_cat[cat]
    passed = passed_by_cat[cat]
    rate = passed/total*100 if total > 0 else 0
    failed_count = total - passed
    top_failed = sorted(failed_by_cat[cat].items(), key=lambda x: -x[1])[:5]
    print(f"\n  {cat}: {passed}/{total} ({rate:.0f}%), 失败{failed_count}个")
    print(f"    失败TOP5: {top_failed}")
