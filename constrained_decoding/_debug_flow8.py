import os
from validate_semantic_full import find_semantic_points
from aadl_semantic_full import FullAadlSemanticEngine

root = r"C:\Users\heli0\Desktop\osate-examples"

unrecognized = []
total = 0
passed = 0

for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] != "flow_endpoint":
                continue
            total += 1
            engine = FullAadlSemanticEngine()
            valid = engine.get_valid_values(p["prefix_text"])
            if valid is None:
                if len(unrecognized) < 10:
                    lines = p["prefix_text"].split("\n")
                    unrecognized.append({
                        "file": fn,
                        "loc": p["location"],
                        "kind": p.get("flow_kind", "?"),
                        "last_line": repr(lines[-1][:80]),
                        "second_last": repr(lines[-2][:80]) if len(lines) >= 2 else "N/A",
                    })
            elif valid:
                exp_lower = p["expected"].lower()
                if exp_lower in [v.lower() for v in valid]:
                    passed += 1

print(f"总数: {total}, 通过: {passed}")
print(f"未识别: {total - passed}")
print()
print("前 10 个未识别案例:")
for u in unrecognized:
    print(f"  [{u['file']}] {u['loc']} (kind={u['kind']})")
    print(f"    最后一行: {u['last_line']}")
    print(f"    倒数第二: {u['second_last']}")
    print()
