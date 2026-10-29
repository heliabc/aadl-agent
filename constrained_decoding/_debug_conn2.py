import os
from validate_semantic_full import find_semantic_points
from aadl_semantic_full import FullAadlSemanticEngine

root = r"C:\Users\heli0\Desktop\osate-examples"

unrecognized_samples = []
not_in_list_samples = []

for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] not in ("conn_source", "conn_target"):
                continue
            engine = FullAadlSemanticEngine()
            valid = engine.get_valid_values(p["prefix_text"])

            if valid is None and len(unrecognized_samples) < 5:
                unrecognized_samples.append({
                    "file": fn,
                    "location": p["location"],
                    "last_line": p["prefix_text"].split("\n")[-2] if "\n" in p["prefix_text"] else p["prefix_text"],
                })
            elif valid is not None and len(valid) > 0:
                exp_lower = p["expected"].lower()
                if exp_lower not in [v.lower() for v in valid] and len(not_in_list_samples) < 5:
                    not_in_list_samples.append({
                        "file": fn,
                        "location": p["location"],
                        "expected": p["expected"],
                        "valid_sample": valid[:5],
                        "valid_count": len(valid),
                    })

print("=== 未识别到语义位置（前 5 个）===")
for s in unrecognized_samples:
    print(f"  [{s['file']}] {s['location']}")
    print(f"    最后一行: {repr(s['last_line'][:80])}")
    print()

print("=== 不在合法值列表中（前 5 个）===")
for s in not_in_list_samples:
    print(f"  [{s['file']}] {s['location']}")
    print(f"    期望: {s['expected']}")
    print(f"    引擎返回 {s['valid_count']} 个，前 5 个: {s['valid_sample']}")
    print()
