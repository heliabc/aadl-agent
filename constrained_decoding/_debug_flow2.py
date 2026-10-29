from validate_semantic_full import find_semantic_points

fpath = r"C:\Users\heli0\Desktop\osate-examples\examples\speed-regulation\model\software.aadl"
points = find_semantic_points(fpath)

print("所有 flow 端点验证点:")
for p in points:
    if p["type"] == "flow_endpoint":
        print(f"  {p['location']} (kind={p.get('flow_kind', '?')})")
        print(f"    期望: {p['expected']}")
        lines = p["prefix_text"].split("\n")
        print(f"    最后一行: {repr(lines[-1])}")
        print(f"    倒数第二行: {repr(lines[-2]) if len(lines) >= 2 else 'N/A'}")
        print()
