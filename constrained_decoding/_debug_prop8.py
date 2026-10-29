from aadl_semantic_full import FullPositionDetector, FullAadlSymbolTable
from aadl_context_sensitive import AadlSymbolTable
from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\safety-tutorial\packages\GPSSystem.aadl"
points = find_semantic_points(fpath)

# 找第一个属性名点
for p in points:
    if p["type"] == "property_name":
        text = p["prefix_text"]
        lines = text.split("\n")
        sym = FullAadlSymbolTable(AadlSymbolTable())
        det = FullPositionDetector(sym)

        print("验证点:", p["location"], "期望:", p["expected"])
        print(f"共 {len(lines)} 行")
        print("最后 5 行:")
        for l in lines[-5:]:
            print(f"  {l.rstrip()}")
        print()
        print("current_type_name:", det._get_current_type_name(lines))
        print("current_impl_name:", det._get_current_impl_name(lines))
        print("in_properties:", det._is_in_section(lines, "properties"))
        break
