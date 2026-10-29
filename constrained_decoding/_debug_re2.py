import re

CAT_RE = (
    r'(?:system|process|thread|thread\s+group|subprogram|'
    r'subprogram\s+group|data|device|processor|'
    r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
)

stripped = "process image_acquisition"
print("stripped:", repr(stripped))

pattern = rf'^{CAT_RE}\s+(\w+)\s*$'
print("pattern:", pattern)

m = re.match(pattern, stripped)
print("match:", m)
if m:
    print("group(1):", m.group(1))

# 排除 implementation 检查
print("'implementation' in stripped:", 'implementation' in stripped)
