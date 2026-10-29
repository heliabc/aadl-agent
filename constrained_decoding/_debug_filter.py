import re
from validate_semantic_engine import find_semantic_points

COMP_CATEGORIES = [
    "system", "process", "thread", "subprogram", "data",
    "device", "processor", "memory", "bus", "abstract",
]

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\ACVIP\InitialSCS.aadl"
with open(fpath, "r", encoding="utf-8", errors="ignore") as f:
    content = f.read()

# 去注释
clean_lines = []
for line in content.split("\n"):
    idx = line.find("--")
    if idx >= 0:
        before = line[:idx]
        if before.count('"') % 2 == 0:
            line = before
    clean_lines.append(line.rstrip())
lines = clean_lines

local_components = set()
for line in lines:
    stripped = line.strip()
    if not stripped:
        continue
    pattern = r"^(" + "|".join(COMP_CATEGORIES) + r")\s+(\w+)\s*$"
    m = re.match(pattern, stripped, re.IGNORECASE)
    if m:
        local_components.add(m.group(2).lower())
        print("组件类型:", m.group(2))

print()
print("local_components:", local_components)
print()

# 测试分类器判断
classifiers = ["SimpleSensor.i", "SimpleController.i", "HighLevelSCS"]
for cls in classifiers:
    cls_lower = cls.lower()
    in_comps = cls_lower in local_components
    has_dot = "." in cls_lower
    base = cls_lower.rsplit(".", 1)[0] if has_dot else ""
    base_in_comps = base in local_components if has_dot else False
    print(f"{cls}: in_comps={in_comps}, base={base}, base_in_comps={base_in_comps}")
