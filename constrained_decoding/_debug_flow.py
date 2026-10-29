import os
from validate_semantic_full import find_semantic_points
from aadl_semantic_full import FullAadlSemanticEngine

root = r"C:\Users\heli0\Desktop\osate-examples"

flow_failures = {"empty": 0, "unrecognized": 0, "not_in_list": 0, "total": 0}
flow_samples = []

for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] != "flow_endpoint":
                continue
            flow_failures["total"] += 1
            engine = FullAadlSemanticEngine()
            valid = engine.get_valid_values(p["prefix_text"])
            if valid is None:
                flow_failures["unrecognized"] += 1
                if len(flow_samples) < 5:
                    flow_samples.append({
                        "type": "unrecognized",
                        "file": fn,
                        "loc": p["location"],
                        "flow_kind": p.get("flow_kind", "?"),
                        "last_line": p["prefix_text"].split("\n")[-2] if "\n" in p["prefix_text"] else p["prefix_text"],
                    })
            elif len(valid) == 0:
                flow_failures["empty"] += 1
                if len(flow_samples) < 5:
                    flow_samples.append({
                        "type": "empty",
                        "file": fn,
                        "loc": p["location"],
                        "flow_kind": p.get("flow_kind", "?"),
                        "expected": p["expected"],
                    })
            else:
                exp_lower = p["expected"].lower()
                if exp_lower not in [v.lower() for v in valid]:
                    flow_failures["not_in_list"] += 1
                    if len(flow_samples) < 5:
                        flow_samples.append({
                            "type": "not_in_list",
                            "file": fn,
                            "loc": p["location"],
                            "flow_kind": p.get("flow_kind", "?"),
                            "expected": p["expected"],
                            "valid_sample": valid[:5],
                            "valid_count": len(valid),
                        })

print("Flow 端点失败原因分布:")
print("  总数:", flow_failures["total"])
print("  未识别到语义位置:", flow_failures["unrecognized"])
print("  合法值列表为空:", flow_failures["empty"])
print("  不在合法值列表中:", flow_failures["not_in_list"])
print()
print("失败案例样例:")
for s in flow_samples:
    print(f"  [{s['file']}] {s['loc']} (kind={s['flow_kind']}, type={s['type']})")
    if "expected" in s:
        print(f"    期望: {s['expected']}")
    if "valid_sample" in s:
        print(f"    引擎返回 {s['valid_count']} 个: {s['valid_sample']}")
    if "last_line" in s:
        print(f"    最后一行: {repr(s['last_line'][:100])}")
    print()
