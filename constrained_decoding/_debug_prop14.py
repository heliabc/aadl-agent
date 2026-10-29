import os
from aadl_semantic_full import FullPositionDetector, FullAadlSymbolTable
from aadl_context_sensitive import AadlSymbolTable
from validate_semantic_full import find_semantic_points

root = r"C:\Users\heli0\Desktop\osate-examples"

samples = []
for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] == "property_name" and p["expected"] == "Actual_Connection_Binding":
                lines = p["prefix_text"].split("\n")
                if len(samples) < 5:
                    samples.append({
                        "file": fn,
                        "last_10_lines": lines[-10:],
                    })
                break
    if len(samples) >= 5:
        break

for i, s in enumerate(samples, 1):
    print(f"=== 样例 {i}: {s['file']} ===")
    for l in s['last_10_lines']:
        print(f"  {l.rstrip()}")
    print()
