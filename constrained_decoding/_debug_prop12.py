import os
from aadl_semantic_full import FullAadlSemanticEngine
from validate_semantic_full import find_semantic_points

root = r"C:\Users\heli0\Desktop\osate-examples"

for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] == "property_name" and p["expected"] == "latency":
                engine = FullAadlSemanticEngine()
                valid = engine.get_valid_values(p["prefix_text"])
                print(f"文件: {fn}")
                print(f"  返回: {valid}")
                if valid:
                    print(f"  有 Latency 吗? {'Latency' in [v for v in valid]}")
                lines = p["prefix_text"].split("\n")
                print(f"  最后2行:")
                for l in lines[-2:]:
                    print(f"    {l.rstrip()}")
                print()
                break
    else:
        continue
    break
