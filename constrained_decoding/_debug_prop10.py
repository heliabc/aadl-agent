from aadl_semantic_full import FullAadlSemanticEngine
from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\safety-tutorial\packages\GPSSystem.aadl"
points = find_semantic_points(fpath)

for p in points:
    if p["type"] == "property_name" and p["expected"] == "Actual_Processor_Binding":
        engine = FullAadlSemanticEngine()
        engine.update(p["prefix_text"])

        print("component_types:", list(engine.symbols._sym.component_types.keys()))
        print("component_impls:", list(engine.symbols._sym.component_impls.keys()))
        print()

        # 手动查
        impl_name = "GPS.BasicBound"
        impl = engine.symbols._sym.component_impls.get(impl_name)
        print(f"impl {impl_name}: {impl}")
        if impl:
            print(f"  base_name: {impl.base_name}")
            base_type = engine.symbols._sym.component_types.get(impl.base_name)
            print(f"  base_type: {base_type}")
            if base_type:
                print(f"  category: {base_type.category}")
        break
