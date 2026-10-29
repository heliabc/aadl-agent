from aadl_semantic_full import FullAadlSemanticEngine
from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\speed-regulation\model\software.aadl"
points = find_semantic_points(fpath)

# 拿第一个 flow 端点
p = None
for pt in points:
    if pt["type"] == "flow_endpoint":
        p = pt
        break

if p:
    print("验证点:", p["location"])
    print("期望:", p["expected"])
    print()
    print("前缀最后 1000 字符:")
    print(p["prefix_text"][-1000:])
    print()

    engine = FullAadlSemanticEngine()
    engine.update(p["prefix_text"])

    print("引擎状态:")
    print("  in_flows:", engine.symbols._in_flows)
    print("  current_type_name:", engine.base_engine.position_detector.current_type_name
          if hasattr(engine.base_engine.position_detector, 'current_type_name') else "N/A")
    print()

    # 手动调位置检测
    det = engine.base_engine.position_detector
    from aadl_context_sensitive import ConstraintField
    field, ctx = det.detect(p["prefix_text"])
    print("基础引擎位置检测:", field, ctx)

    # 也看看扩展检测
    from aadl_semantic_full import FullPositionDetector, ExtraConstraintField
    full_det = FullPositionDetector(engine.symbols)
    field2, ctx2 = full_det.detect(p["prefix_text"])
    print("扩展位置检测:", field2, ctx2)
