from validate_semantic_full import find_semantic_points
from aadl_semantic_full import FullAadlSemanticEngine

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\safety-tutorial\packages\FlightSystem.aadl"
points = find_semantic_points(fpath)

# 找第一个连接源端
for p in points:
    if p["type"] == "conn_source":
        print("=== 连接源端验证点 ===")
        print("期望:", p["expected"])
        print()
        print("前缀最后 800 字符:")
        print(p["prefix_text"][-800:])
        print()

        engine = FullAadlSemanticEngine()
        valid = engine.get_valid_values(p["prefix_text"])
        print("引擎返回:", valid)
        print()

        # 看看符号表
        print("组件类型:", list(engine.symbols._sym.component_types.keys()))
        print("组件实现:", list(engine.symbols._sym.component_impls.keys()))
        print()

        # 看看当前实现
        # 先找到当前 impl name
        from aadl_context_sensitive import PositionDetector, ConstraintField
        det = engine.base_engine.position_detector
        field, ctx = det.detect(p["prefix_text"])
        print("位置检测:", field, ctx)

        if "impl_name" in ctx:
            impl_name = ctx["impl_name"]
            impl = engine.symbols._sym.component_impls.get(impl_name)
            if impl:
                print(f"实现 {impl_name} 的子组件:")
                for sname, sub in impl.subcomponents.items():
                    print(f"  {sname}: {sub.category} {sub.classifier}")
                    # 查类型
                    t = engine.symbols._sym.component_types.get(sub.classifier)
                    print(f"    直接查类型: {t is not None}")
                    base = sub.classifier.split(".")[0] if "." in sub.classifier else sub.classifier
                    t2 = engine.symbols._sym.component_types.get(base)
                    print(f"    查 base 类型: {t2 is not None}")
                    if t2:
                        print(f"    端口: {list(t2.ports.keys())}")
        break
