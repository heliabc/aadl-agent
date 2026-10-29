import os
from validate_semantic_full import find_semantic_points
from aadl_semantic_full import FullAadlSemanticEngine

root = r"C:\Users\heli0\Desktop\osate-examples"

count = 0
for dirpath, dirnames, filenames in os.walk(root):
    for fn in sorted(filenames):
        if not fn.endswith(".aadl"):
            continue
        fpath = os.path.join(dirpath, fn)
        points = find_semantic_points(fpath)
        for p in points:
            if p["type"] != "flow_endpoint":
                continue
            engine = FullAadlSemanticEngine()
            valid = engine.get_valid_values(p["prefix_text"])
            if valid is None:
                count += 1
                if count <= 3:
                    print(f"文件: {fpath}")
                    print(f"  {p['location']} (kind={p.get('flow_kind','?')})")
                    print(f"  期望: {p['expected']}")
                    lines = p["prefix_text"].split("\n")
                    print(f"  最后一行: {repr(lines[-1][:80])}")
                    print(f"  倒数第3行: {repr(lines[-3][:80]) if len(lines)>=3 else 'N/A'}")
                    # 看看组件类型名
                    from aadl_semantic_full import FullPositionDetector, FullAadlSymbolTable
                    from aadl_context_sensitive import AadlSymbolTable
                    sym = FullAadlSymbolTable(AadlSymbolTable())
                    det = FullPositionDetector(sym)
                    ct = det._get_current_type_name(lines)
                    in_flows = det._is_in_section(lines, "flows")
                    print(f"  current_type_name: {ct}")
                    print(f"  in_flows: {in_flows}")
                    print()
        if count >= 3:
            break
    if count >= 3:
        break
