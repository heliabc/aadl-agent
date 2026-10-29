from aadl_semantic_full import FullPositionDetector, FullAadlSymbolTable
from aadl_context_sensitive import AadlSymbolTable
from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\safety-tutorial\packages\GPSSystem.aadl"
points = find_semantic_points(fpath)

for p in points:
    if p["type"] == "property_name" and p["expected"] == "Actual_Processor_Binding":
        text = p["prefix_text"]
        lines = text.split("\n")
        sym = FullAadlSymbolTable(AadlSymbolTable())
        det = FullPositionDetector(sym)

        print("最后 3 行:")
        for l in lines[-3:]:
            print(f"  {repr(l.rstrip())}")
        print()
        print("current_type_name:", det._get_current_type_name(lines))
        print("current_impl_name:", det._get_current_impl_name(lines))

        # 手动测正则
        import re
        CAT_RE = (
            r'(?:system|process|thread|thread\s+group|subprogram|'
            r'subprogram\s+group|data|device|processor|'
            r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
        )
        impl_line = lines[-3].strip() if len(lines) >= 3 else ""
        print(f"\n实现行: {repr(impl_line)}")
        m = re.match(
            rf'^{CAT_RE}\s+implementation\s+(\w+\.\w+)'
            r'(?:\s+extends\s+[\w.:]+)?\s*;?\s*$',
            impl_line
        )
        print("正则匹配:", m)
        if m:
            print("  group(1):", m.group(1))

        # 也试试 detect_extra 直接返回啥
        field, ctx = det.detect_extra(text)
        print("\ndetect_extra 返回:", field, ctx)
        break
