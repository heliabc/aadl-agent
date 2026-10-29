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
            if p["type"] != "property_name":
                continue
            lines = p["prefix_text"].split("\n")
            sym = FullAadlSymbolTable(AadlSymbolTable())
            det = FullPositionDetector(sym)
            ct = det._get_current_type_name(lines)
            ci = det._get_current_impl_name(lines)
            if not ct and not ci and len(samples) < 5:
                samples.append({
                    "file": fn,
                    "prop": p["expected"],
                    "last_lines": lines[-8:],
                })

for s in samples:
    print(f"=== {s['file']} - {s['prop']} ===")
    for i, l in enumerate(s['last_lines'], 1):
        print(f"  {i}: {l.rstrip()}")
    print()
