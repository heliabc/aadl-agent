from aadl_semantic_full import FullPositionDetector, FullAadlSymbolTable
from aadl_context_sensitive import AadlSymbolTable
from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\speed-regulation\model\software.aadl"
points = find_semantic_points(fpath)

p = points[0]  # 第一个 flow 端点

text = p["prefix_text"]
sym = FullAadlSymbolTable(AadlSymbolTable())
det = FullPositionDetector(sym)

result = det.detect_extra(text)
print("detect_extra 返回:", result)

# 也看看原始引擎的 detect
from aadl_context_sensitive import AadlContextSensitiveEngine
engine = AadlContextSensitiveEngine()
base_result = engine.position_detector.detect(text)
print("base detect 返回:", base_result)
