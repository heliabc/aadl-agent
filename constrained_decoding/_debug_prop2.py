from json_validator import get_valid_properties

cats = ["system", "process", "thread", "data", "device", "processor", "memory", "bus", "abstract"]

all_props = set()
for cat in cats:
    props = get_valid_properties(cat)
    all_props.update(props)
    print(f"\n{cat} ({len(props)} 个):")
    for p in sorted(props)[:20]:
        print(f"  {p}")
    if len(props) > 20:
        print(f"  ... 还有 {len(props)-20} 个")

print(f"\n所有类别合计不重复: {len(all_props)} 个")
