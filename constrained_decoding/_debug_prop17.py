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
                lines = p["prefix_text"].split("\n")
                # 找类别
                from aadl_semantic_full import FullPositionDetector, FullAadlSymbolTable
                from aadl_context_sensitive import AadlSymbolTable
                sym = FullAadlSymbolTable(AadlSymbolTable())
                det = FullPositionDetector(sym)
                ct = det._get_current_type_name(lines)
                ci = det._get_current_impl_name(lines)
                print(f"文件: {fn}")
                print(f"  type: {ct}, impl: {ci}")
                print(f"  引擎返回 valid 数量: {len(valid) if valid else 0}")
                if valid:
                    has_latency = 'Latency' in [v for v in valid]
                    print(f"  有 Latency 吗: {has_latency}")
                    if not has_latency:
                        print(f"  前 10 个: {valid[:10]}")
                # 看看符号表里的类别
                if ci:
                    impl = engine.symbols._sym.component_impls.get(ci.lower())
                    if impl:
                        base = engine.symbols._sym.component_types.get(impl.base_name.lower())
                        if base:
                            print(f"  类别: {base.category}")
                print()
