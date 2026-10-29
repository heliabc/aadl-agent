import os
from validate_semantic_full import find_semantic_points
from aadl_semantic_full import FullAadlSemanticEngine

root = r"C:\Users\heli0\Desktop\osate-examples"

conn_failures = {"empty": 0, "unrecognized": 0, "not_in_list": 0, "total": 0}

for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] not in ("conn_source", "conn_target"):
                continue
            conn_failures["total"] += 1
            engine = FullAadlSemanticEngine()
            valid = engine.get_valid_values(p["prefix_text"])
            if valid is None:
                conn_failures["unrecognized"] += 1
            elif len(valid) == 0:
                conn_failures["empty"] += 1
            else:
                exp_lower = p["expected"].lower()
                if exp_lower not in [v.lower() for v in valid]:
                    conn_failures["not_in_list"] += 1

print("连接失败原因分布:")
print("  总数:", conn_failures["total"])
print("  未识别到语义位置:", conn_failures["unrecognized"])
print("  合法值列表为空:", conn_failures["empty"])
print("  不在合法值列表中:", conn_failures["not_in_list"])
