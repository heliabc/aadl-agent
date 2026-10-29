import os
from aadl_semantic_full import FullPositionDetector, FullAadlSymbolTable
from aadl_context_sensitive import AadlSymbolTable
from validate_semantic_full import find_semantic_points

root = r"C:\Users\heli0\Desktop\osate-examples"

in_type = 0
in_impl = 0
none_both = 0

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
            if ci:
                in_impl += 1
            elif ct:
                in_type += 1
            else:
                none_both += 1

print(f"属性位置分布:")
print(f"  在实现里: {in_impl}")
print(f"  在类型里: {in_type}")
print(f"  都找不到: {none_both}")
