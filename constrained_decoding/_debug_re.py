import re

last_line = "\t\t\tf0: flow path "
print("last_line:", repr(last_line))

# flow path source 正则
flow_path_src_match = re.match(
    r'^\s*(\w+)\s*:\s*flow\s+path\s+(\S*)$',
    last_line
)
print("flow_path_src_match:", flow_path_src_match)
if flow_path_src_match:
    print("  group(1):", repr(flow_path_src_match.group(1)))
    print("  group(2):", repr(flow_path_src_match.group(2)))

# flow source 正则
flow_src_match = re.match(
    r'^\s*(\w+)\s*:\s*flow\s+source\s+(\S*)$',
    last_line
)
print("flow_src_match:", flow_src_match)

# 检查 ->
print("'->' in last_line:", '->' in last_line)
