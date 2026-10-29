from aadl_semantic_full import FullAadlSemanticEngine
from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\safety-tutorial\packages\GPSSystem.aadl"
points = find_semantic_points(fpath)

for p in points:
    if p["type"] == "property_name" and p["expected"] == "Actual_Processor_Binding":
        engine = FullAadlSemanticEngine()
        engine.update(p["prefix_text"])

        print("期望:", p["expected"])
        print("前缀最后 5 行:")
        lines = p["prefix_text"].split("\n")
        for l in lines[-5:]:
            print(f"  {l.rstrip()}")
        print()

        valid = engine.get_valid_values(p["prefix_text"])
        print("get_valid_values 返回:", valid)
        print("数量:", len(valid) if valid else 0)
        print()

        # 看看位置检测
        det = engine.extra_detector
        field, ctx = det.detect_extra(p["prefix_text"])
        print("detect_extra:", field, ctx)

        # 看看符号表
        print("component_types:", list(engine.symbols._sym.component_types.keys()))
        print("component_impls:", list(engine.symbols._sym.component_impls.keys()))
        break
