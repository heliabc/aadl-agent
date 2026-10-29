import os
from collections import Counter
from validate_semantic_full import find_semantic_points
from aadl_semantic_full import FullAadlSemanticEngine

root = r"C:\Users\heli0\Desktop\osate-examples"

failed_props = Counter()
total = 0
passed = 0

for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] != "property_name":
                continue
            total += 1
            engine = FullAadlSemanticEngine()
            valid = engine.get_valid_values(p["prefix_text"])
            if valid is None or len(valid) == 0:
                failed_props[p["expected"]] += 1
            else:
                exp_lower = p["expected"].lower()
                if exp_lower in [v.lower() for v in valid]:
                    passed += 1
                else:
                    failed_props[p["expected"]] += 1

print(f"属性名验证: {passed}/{total} 通过 ({passed/total*100:.1f}%)")
print()
print("失败最多的 30 个属性:")
for name, count in failed_props.most_common(30):
    print(f"  {name}: {count}次")
