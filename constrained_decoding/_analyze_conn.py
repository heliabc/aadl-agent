"""分析连接失败的原因"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from validate_semantic_rules import validate_file, find_aadl_files

root = r"c:\Users\heli0\Desktop\osate-examples\examples"
files = find_aadl_files(root)

conn_src_fail = []
conn_tgt_fail = []

for f in files:
    results = validate_file(f)
    for r in results:
        if "::" in r.actual_value:
            continue  # 跳过跨包
        if r.constraint_name == "连接源端" and not r.passed:
            conn_src_fail.append(r)
        if r.constraint_name == "连接目标端" and not r.passed:
            conn_tgt_fail.append(r)

print(f"连接源端失败: {len(conn_src_fail)}")
print(f"连接目标端失败: {len(conn_tgt_fail)}")
print()

# 连接失败的主要原因：合法值数为 0（子组件端口没提取到）
zero_count_src = sum(1 for r in conn_src_fail if r.valid_count == 0)
zero_count_tgt = sum(1 for r in conn_tgt_fail if r.valid_count == 0)
print(f"源端失败中合法值数=0: {zero_count_src}/{len(conn_src_fail)}")
print(f"目标端失败中合法值数=0: {zero_count_tgt}/{len(conn_tgt_fail)}")
print()

# 打印前 5 个合法值>0 的失败（说明引擎有值但没包含正确值）
print("源端失败（合法值>0，引擎计算了但答案不对）:")
cnt = 0
for r in conn_src_fail:
    if r.valid_count > 0:
        print(f"  {os.path.basename(r.file)} @ {r.location}")
        print(f"    实际: {r.actual_value}")
        print(f"    合法值: {r.message}")
        cnt += 1
        if cnt >= 5:
            break

print()
print("目标端失败（合法值>0，引擎计算了但答案不对）:")
cnt = 0
for r in conn_tgt_fail:
    if r.valid_count > 0:
        print(f"  {os.path.basename(r.file)} @ {r.location}")
        print(f"    实际: {r.actual_value}")
        print(f"    合法值: {r.message}")
        cnt += 1
        if cnt >= 5:
            break
