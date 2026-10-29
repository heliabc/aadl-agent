"""分析失败案例的原因分类"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from validate_semantic_rules import validate_file, find_aadl_files

root = r"c:\Users\heli0\Desktop\osate-examples\examples"
files = find_aadl_files(root)

all_failed = []
subcomp_failed_reasons = {}

for f in files[:20]:  # 先看前20个
    results = validate_file(f)
    for r in results:
        if not r.passed and r.valid_count != -1:  # 排除未识别的
            all_failed.append(r)
            if r.constraint_name == "子组件分类器":
                # 分析原因
                actual = r.actual_value
                if "::" in actual:
                    reason = "跨包引用（带::）"
                elif actual.endswith(".impl"):
                    reason = "引用的是实现（不是类型）"
                elif "." in actual:
                    reason = "带点号（可能是嵌套）"
                else:
                    reason = f"其他（合法值0个）" if r.valid_count == 0 else f"其他（有{r.valid_count}个合法值）"
                subcomp_failed_reasons[reason] = subcomp_failed_reasons.get(reason, 0) + 1

print(f"失败案例总数（排除未识别）: {len(all_failed)}")
print()
print("子组件分类器失败原因分布:")
for reason, cnt in sorted(subcomp_failed_reasons.items(), key=lambda x: -x[1]):
    print(f"  {reason}: {cnt}")

print()
print("前10个失败案例:")
for r in all_failed[:10]:
    print(f"  [{r.constraint_name}] {os.path.basename(r.file)} @ {r.location}")
    print(f"    实际: {r.actual_value}, 合法值数: {r.valid_count}")
