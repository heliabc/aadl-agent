import os
from aadl_semantic_full import FullAadlSemanticEngine
from validate_semantic_full import find_semantic_points

root = r"C:\Users\heli0\Desktop\osate-examples"

# 找 Actual_Processor_Binding 失败的案例
for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] == "property_name" and p["expected"] == "Actual_Processor_Binding":
                engine = FullAadlSemanticEngine()
                valid = engine.get_valid_values(p["prefix_text"])
                if valid is None or len(valid) == 0:
                    print(f"文件: {fpath}")
                    print(f"  返回: {valid}")
                    lines = p["prefix_text"].split("\n")
                    print(f"  最后3行:")
                    for l in lines[-3:]:
                        print(f"    {l.rstrip()}")
                    print()
                    break
    else:
        continue
    break
