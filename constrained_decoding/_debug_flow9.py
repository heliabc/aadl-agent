import os
from validate_semantic_full import find_semantic_points
from aadl_semantic_full import FullAadlSemanticEngine

root = r"C:\Users\heli0\Desktop\osate-examples"

stats = {"total": 0, "passed": 0, "unrecognized": 0, "empty": 0, "not_in_list": 0}
samples = []

for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] != "flow_endpoint":
                continue
            stats["total"] += 1
            engine = FullAadlSemanticEngine()
            valid = engine.get_valid_values(p["prefix_text"])

            if valid is None:
                stats["unrecognized"] += 1
                if len(samples) < 5:
                    lines = p["prefix_text"].split("\n")
                    samples.append(("unrecognized", fn, p["location"],
                                    p.get("flow_kind","?"), lines[-1],
                                    lines[-2] if len(lines)>=2 else ""))
            elif len(valid) == 0:
                stats["empty"] += 1
            else:
                exp_lower = p["expected"].lower()
                if exp_lower in [v.lower() for v in valid]:
                    stats["passed"] += 1
                else:
                    stats["not_in_list"] += 1
                    if len(samples) < 5:
                        samples.append(("not_in_list", fn, p["location"],
                                        p.get("flow_kind","?"), p["expected"],
                                        str(valid[:5])))

print("Flow 端点统计:")
for k, v in stats.items():
    print(f"  {k}: {v}")

print()
print("案例样例:")
for s in samples:
    print(f"  [{s[1]}] {s[2]} (kind={s[3]}, type={s[0]})")
    print(f"    {s[4][:80]}")
    print(f"    {s[5][:80]}")
    print()
