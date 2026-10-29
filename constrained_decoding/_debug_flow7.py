from aadl_semantic_full import FullAadlSemanticEngine
from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\speed-regulation\model\software.aadl"
points = find_semantic_points(fpath)

p = points[0]
print("验证点:", p["location"], "期望:", p["expected"])

engine = FullAadlSemanticEngine()
valid = engine.get_valid_values(p["prefix_text"])
print("get_valid_values 返回:", valid)
if valid:
    print("期望在列表中吗?", p["expected"].lower() in [v.lower() for v in valid])
