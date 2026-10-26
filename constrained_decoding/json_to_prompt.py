#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
JSON 架构 → AADL 生成提示词 解析器
====================================
将结构化的架构 JSON 转换为大模型生成 AADL 时使用的提示词。

支持两种组织方式：
1. 分类式（by_category）：按 features、subcomponents、connections 分类说明
   （类似用户给出的格式，优点是同类信息集中）
2. 组件式（by_component）：按组件依次说明，每个组件包含其所有信息
   （优点是信息完整、上下文集中）

用法:
  python json_to_prompt.py --arch flight_control.json --format by_component
  python json_to_prompt.py --arch flight_control.json --format by_category
"""

import json
import argparse
from typing import List, Dict


# ============================================================
# 分类式提示词（by_category）
# ============================================================

def build_prompt_by_category(arch: dict) -> str:
    """
    分类式：按 features、subcomponents、connections 分类说明。

    结构：
    一、组件列表
    二、每个组件的 features 明细
    三、每个组件实现的 subcomponents
    四、每个组件实现的 connections
    五、生成规则
    六、语法参考
    """
    lines = []
    lines.append("【AADL 架构生成任务】")
    lines.append("=" * 70)
    lines.append("")
    lines.append(f"包名：{arch['package_name']}")

    if arch.get("with_packages"):
        lines.append(f"with 包：{', '.join(arch['with_packages'])}")

    lines.append("")

    # ---- 一、组件列表 ----
    type_comps = [c for c in arch["components"]
                  if "implementation" not in c.get("category", "")]
    impl_comps = [c for c in arch["components"]
                  if "implementation" in c.get("category", "")]

    type_has_impl = {}
    for ic in impl_comps:
        base = ic["name"].rsplit(".", 1)[0] if "." in ic["name"] else ic["name"]
        type_has_impl[base] = True

    lines.append("-" * 70)
    lines.append("一、组件列表（按生成顺序排列）")
    lines.append("-" * 70)
    lines.append("")

    for i, comp in enumerate(type_comps, 1):
        name = comp["name"]
        cat = comp["category"]
        has_impl = type_has_impl.get(name, comp.get("has_impl", True))
        has_ports = bool(comp.get("ports"))

        lines.append(
            f"{i}. {name}，类型 {cat}，"
            f"{'需要实现' if has_impl else '不需要实现'}，"
            f"{'有 features' if has_ports else '无 features（不要生成 features 节）'}"
        )

    lines.append("")

    # 特殊提示：哪些组件不需要 features
    no_port_cats = ["bus", "processor", "memory", "virtual bus", "virtual processor"]
    no_port_comps = [c["name"] for c in type_comps
                     if c["category"] in no_port_cats]
    if no_port_comps:
        lines.append(
            f"注意：{'、'.join(no_port_cats)} 类型的组件通常没有 features，"
            f"不要生成 features 节，直接 end 结束。"
        )
        lines.append("")

    # ---- 二、features 明细 ----
    lines.append("-" * 70)
    lines.append("二、每个组件的 features 明细")
    lines.append("-" * 70)
    lines.append("")

    has_any_port = False
    for comp in type_comps:
        ports = comp.get("ports", [])
        if not ports:
            continue
        has_any_port = True
        lines.append(f"{comp['name']} 的 features：")
        for port in ports:
            type_str = f" {port['data_type']}" if port.get("data_type") else ""
            lines.append(
                f"  - {port['name']}: {port['direction']} "
                f"{port['kind']}{type_str}"
            )
        lines.append("")

    if not has_any_port:
        lines.append("（所有组件均无 features）")
        lines.append("")

    # ---- 三、subcomponents 明细 ----
    lines.append("-" * 70)
    lines.append("三、每个组件实现的 subcomponents")
    lines.append("-" * 70)
    lines.append("")

    for impl in impl_comps:
        subcomps = impl.get("subcomponents", [])
        lines.append(f"{impl['name']} 的 subcomponents：")
        if not subcomps:
            lines.append("  （无子组件）")
        else:
            for sub in subcomps:
                lines.append(
                    f"  - {sub['name']}: {sub['category']} {sub['classifier']}"
                )
        lines.append("")

    # ---- 四、connections 明细 ----
    lines.append("-" * 70)
    lines.append("四、每个组件实现的 connections")
    lines.append("-" * 70)
    lines.append("")

    for impl in impl_comps:
        conns = impl.get("connections", [])
        lines.append(f"{impl['name']} 的 connections：")
        if not conns:
            lines.append("  （无连接，不要生成 connections 节）")
        else:
            for i, conn in enumerate(conns, 1):
                lines.append(
                    f"  {i}. {conn['name']}: "
                    f"{conn['source']} -> {conn['target']}"
                )
        lines.append("")

    # ---- 五、生成规则 ----
    lines.append("-" * 70)
    lines.append("五、生成规则")
    lines.append("-" * 70)
    lines.append("")
    lines.append("1. 按上面列出的顺序生成所有组件类型声明")
    lines.append("2. 需要实现的组件，在组件类型后面生成对应的 .impl 实现")
    lines.append("3. 无 features 的组件，不要生成 features 节，直接 end 结束")
    lines.append("4. 无 connections 的实现，不要生成 connections 节")
    lines.append("5. 子组件的类别关键字必须和类型一致")
    lines.append(f"6. 生成到 end {arch['package_name']}; 就停止")
    lines.append("")

    # ---- 六、语法参考 ----
    lines.append("-" * 70)
    lines.append("六、AADL 语法参考")
    lines.append("-" * 70)
    lines.append("")
    lines.append("有 features 的组件类型：")
    lines.append("  <类别> <组件名>")
    lines.append("    features")
    lines.append("      <端口名>: <方向> data port <数据类型>;")
    lines.append("    end <组件名>;")
    lines.append("")
    lines.append("无 features 的组件类型：")
    lines.append("  <类别> <组件名>")
    lines.append("    end <组件名>;")
    lines.append("")
    lines.append("组件实现：")
    lines.append("  <类别> <组件名>.impl")
    lines.append("    subcomponents")
    lines.append("      <子组件名>: <子类别> <子类型名>;")
    lines.append("    connections")
    lines.append("      <连接名>: <源组件>.<源端口> -> <目标组件>.<目标端口>;")
    lines.append("    end <组件名>.impl;")
    lines.append("")

    lines.append("=" * 70)
    lines.append("")
    lines.append("请根据以上架构描述，生成完整的 AADL 代码。")
    lines.append("")
    lines.append("AADL 代码：")
    lines.append("package ")

    return "\n".join(lines)


# ============================================================
# 组件式提示词（by_component）
# ============================================================

def build_prompt_by_component(arch: dict) -> str:
    """
    组件式：按组件依次说明，每个组件包含其所有相关信息。

    结构：
    组件 1：类型 + features
    组件 2：类型 + features
    ...
    组件 1.impl：subcomponents + connections
    ...

    优点：信息集中，模型生成某个组件时上下文完整
    """
    lines = []
    lines.append("【AADL 架构生成任务】")
    lines.append("=" * 70)
    lines.append("")
    lines.append(f"包名：{arch['package_name']}")

    if arch.get("with_packages"):
        lines.append(f"with 包：{', '.join(arch['with_packages'])}")

    lines.append("")
    lines.append("请按顺序生成以下组件。每个组件的详细信息如下：")
    lines.append("")

    type_comps = [c for c in arch["components"]
                  if "implementation" not in c.get("category", "")]
    impl_comps = [c for c in arch["components"]
                  if "implementation" in c.get("category", "")]

    # 构建 impl 索引
    impl_map = {}
    for ic in impl_comps:
        base = ic["name"].rsplit(".", 1)[0] if "." in ic["name"] else ic["name"]
        impl_map[base] = ic

    for i, comp in enumerate(type_comps, 1):
        name = comp["name"]
        cat = comp["category"]
        impl = impl_map.get(name)

        lines.append("-" * 50)
        lines.append(f"组件 {i}：{name}（{cat}）")
        lines.append("-" * 50)

        # 是否有实现
        if impl:
            lines.append(f"是否需要实现：是（{impl['name']}）")
        else:
            lines.append("是否需要实现：否")

        # features
        ports = comp.get("ports", [])
        if ports:
            lines.append(f"features（{len(ports)} 个端口）：")
            for port in ports:
                type_str = f" {port['data_type']}" if port.get("data_type") else ""
                lines.append(
                    f"  • {port['name']}: {port['direction']} "
                    f"{port['kind']}{type_str}"
                )
        else:
            lines.append("features：无（不要生成 features 节，直接 end 结束）")

        # 实现详情
        if impl:
            lines.append("")
            lines.append(f"实现 {impl['name']}：")

            # subcomponents
            subcomps = impl.get("subcomponents", [])
            if subcomps:
                lines.append(f"  subcomponents（{len(subcomps)} 个）：")
                for sub in subcomps:
                    lines.append(
                        f"    • {sub['name']}: {sub['category']} {sub['classifier']}"
                    )
            else:
                lines.append("  subcomponents：无")

            # connections
            conns = impl.get("connections", [])
            if conns:
                lines.append(f"  connections（{len(conns)} 个）：")
                for j, conn in enumerate(conns, 1):
                    lines.append(
                        f"    {j}. {conn['name']}: "
                        f"{conn['source']} -> {conn['target']}"
                    )
            else:
                lines.append("  connections：无（不要生成 connections 节）")

        lines.append("")

    # 生成规则
    lines.append("=" * 70)
    lines.append("生成规则：")
    lines.append("1. 按上面列出的顺序生成组件类型和实现")
    lines.append("2. 无 features 的组件不要生成 features 节")
    lines.append("3. 无 connections 的实现不要生成 connections 节")
    lines.append(f"4. 生成到 end {arch['package_name']}; 就停止")
    lines.append("")
    lines.append("-" * 70)
    lines.append("AADL 语法参考：")
    lines.append("-" * 70)
    lines.append("")
    lines.append("有 features 的组件类型：")
    lines.append("  <类别> <组件名>")
    lines.append("    features")
    lines.append("      <端口名>: <方向> data port <数据类型>;")
    lines.append("    end <组件名>;")
    lines.append("")
    lines.append("无 features 的组件类型：")
    lines.append("  <类别> <组件名>")
    lines.append("    end <组件名>;")
    lines.append("")
    lines.append("组件实现：")
    lines.append("  <类别> <组件名>.impl")
    lines.append("    subcomponents")
    lines.append("      <子组件名>: <子类别> <子类型名>;")
    lines.append("    connections")
    lines.append("      <连接名>: <源组件>.<源端口> -> <目标组件>.<目标端口>;")
    lines.append("    end <组件名>.impl;")
    lines.append("")

    lines.append("=" * 70)
    lines.append("")
    lines.append("请根据以上架构描述，生成完整的 AADL 代码。")
    lines.append("")
    lines.append("AADL 代码：")
    lines.append("package ")

    return "\n".join(lines)


# ============================================================
# 简洁式提示词（极简，用于小模型）
# ============================================================

def build_prompt_compact(arch: dict) -> str:
    """
    简洁式：最小化提示词长度，将架构信息压缩到最紧凑的形式。
    适合 context 紧张或小模型的场景。
    """
    type_comps = [c for c in arch["components"]
                  if "implementation" not in c.get("category", "")]
    impl_comps = [c for c in arch["components"]
                  if "implementation" in c.get("category", "")]

    lines = [
        f"package {arch['package_name']}",
        f"with: {', '.join(arch.get('with_packages', []))}" if arch.get("with_packages") else "",
        "",
        "// Component Types:",
    ]

    for comp in type_comps:
        ports_str = ""
        if comp.get("ports"):
            port_descs = []
            for p in comp["ports"]:
                t = f" {p['data_type']}" if p.get("data_type") else ""
                port_descs.append(f"{p['name']}:{p['direction']} {p['kind']}{t}")
            ports_str = " [" + ", ".join(port_descs) + "]"
        impl_marker = " [has impl]" if any(
            ic["name"].startswith(comp["name"] + ".") for ic in impl_comps
        ) else ""
        lines.append(f"  {comp['category']} {comp['name']}{impl_marker}{ports_str}")

    lines.append("")
    lines.append("// Implementations:")

    for impl in impl_comps:
        lines.append(f"  {impl['name']}:")
        if impl.get("subcomponents"):
            subs = [f"{s['name']}:{s['category']} {s['classifier']}"
                    for s in impl["subcomponents"]]
            lines.append(f"    subcomponents: {', '.join(subs)}")
        if impl.get("connections"):
            conns = [f"{c['name']}:{c['source']}->{c['target']}"
                     for c in impl["connections"]]
            lines.append(f"    connections: {', '.join(conns)}")

    lines.append("")
    lines.append("Generate complete AADL code:")
    lines.append("package ")

    return "\n".join(lines)


# ============================================================
# 便捷函数
# ============================================================

def build_prompt(arch: dict, fmt: str = "by_category") -> str:
    """
    根据指定格式生成提示词。

    Args:
        arch: 架构 JSON dict
        fmt: 格式 - "by_category" / "by_component" / "compact"
    """
    if fmt == "by_category":
        return build_prompt_by_category(arch)
    elif fmt == "by_component":
        return build_prompt_by_component(arch)
    elif fmt == "compact":
        return build_prompt_compact(arch)
    else:
        raise ValueError(f"Unknown format: {fmt}")


# ============================================================
# 主函数
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description="JSON 架构 → AADL 生成提示词 解析器"
    )
    parser.add_argument("--arch", required=True, help="架构 JSON 文件")
    parser.add_argument("--format", default="by_category",
                        choices=["by_category", "by_component", "compact"],
                        help="提示词组织方式（默认 by_category）")
    parser.add_argument("--output", default=None, help="保存提示词到文件")
    parser.add_argument("--compare", action="store_true",
                        help="对比三种格式的长度")
    args = parser.parse_args()

    # 读取 JSON
    with open(args.arch, "r", encoding="utf-8") as f:
        arch = json.load(f)

    if args.compare:
        # 对比三种格式
        fmts = ["by_category", "by_component", "compact"]
        print(f"{'格式':<15} {'字符数':>8} {'行数':>6}")
        print("-" * 35)
        for fmt in fmts:
            prompt = build_prompt(arch, fmt)
            lines = prompt.count("\n") + 1
            print(f"{fmt:<15} {len(prompt):>8d} {lines:>6d}")
        return

    # 生成提示词
    prompt = build_prompt(arch, args.format)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(prompt)
        print(f"提示词已保存到: {args.output}")
        print(f"长度: {len(prompt)} 字符, {prompt.count(chr(10)) + 1} 行")
    else:
        print(prompt)


if __name__ == "__main__":
    main()
