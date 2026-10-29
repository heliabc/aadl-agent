#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用语义引擎验证真实 AADL 样本中的语义约束。

验证思路：
  从 AADL 文件中提取组件/端口/子组件/连接等结构，
  然后逐个检查关键约束点：
    - 子组件 classifier 是否在合法值列表中
    - 连接源端是否在合法值列表中
    - 连接目标端是否在合法值列表中
    - 端口数据类型是否在合法值列表中

  如果真实样本中的正确值都在引擎返回的合法值列表中 → ✅
  如果不在 → ❌ 说明引擎规则有漏检或错误
"""

import re
import os
import sys
from dataclasses import dataclass, field
from typing import List, Dict, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from aadl_semantic_full import FullAadlSemanticEngine


# ============================================================
# 简单 AADL 结构提取器（基于正则，够用就行）
# ============================================================

@dataclass
class PortInfo:
    name: str
    direction: str  # in / out / in out
    kind: str       # data port / event data port / ...
    data_type: str = ""  # 可能没有


@dataclass
class SubcompInfo:
    name: str
    category: str
    classifier: str


@dataclass
class ConnectionInfo:
    name: str
    conn_type: str   # port / ...
    source: str      # 可能是 "comp.port" 或 "port"
    target: str


@dataclass
class ComponentType:
    name: str
    category: str
    ports: List[PortInfo] = field(default_factory=list)
    has_features: bool = False


@dataclass
class ComponentImpl:
    name: str
    category: str       # e.g. "process implementation"
    base_type: str      # e.g. "SMS_App_Process_SMPC"
    subcomponents: List[SubcompInfo] = field(default_factory=list)
    connections: List[ConnectionInfo] = field(default_factory=list)


@dataclass
class AadlPackage:
    name: str
    with_packages: List[str] = field(default_factory=list)
    component_types: List[ComponentType] = field(default_factory=list)
    component_impls: List[ComponentImpl] = field(default_factory=list)


def extract_aadl_structure(filepath: str) -> AadlPackage:
    """从 AADL 文件中提取核心结构（忽略 annex、properties 等）"""
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    # 去掉注释
    content = re.sub(r'--.*', '', content)
    # 去掉 annex 块（{** ... **}）
    content = re.sub(r'\{\*\*.*?\*\*\}', '', content, flags=re.DOTALL)

    lines = content.split('\n')
    pkg = AadlPackage(name="unknown")

    # 包名
    pkg_match = re.search(r'package\s+(\w+)', content)
    if pkg_match:
        pkg.name = pkg_match.group(1)

    # with 包
    for m in re.finditer(r'with\s+([\w:]+)\s*;', content):
        pkg.with_packages.append(m.group(1))

    # 逐段解析组件
    # 策略：找 "end X;" 作为组件结束标记，反向收集内容

    # 组件类型正则
    # <category> <name>
    #   features...
    # end <name>;
    type_pattern = re.compile(
        r'^\s*(system|process|thread|thread\s+group|subprogram|'
        r'subprogram\s+group|data|device|processor|'
        r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)\s+'
        r'(\w+)\s*$',
        re.MULTILINE
    )

    # 实现正则：<category> implementation <name>.<base>
    impl_pattern = re.compile(
        r'^\s*(system|process|thread|thread\s+group|subprogram|'
        r'subprogram\s+group|data|device|processor|'
        r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)\s+'
        r'implementation\s+(\w+\.\w+)\s*$',
        re.MULTILINE
    )

    # 提取每个组件的内容块
    # 简单方法：按行扫描，跟踪当前组件
    current_type = None
    current_impl = None
    in_features = False
    in_subcomponents = False
    in_connections = False

    for line in lines:
        stripped = line.strip()
        if not stripped:
            continue

        # 跳过 properties 节（太复杂，先不处理）
        if stripped == "properties":
            in_features = False
            in_subcomponents = False
            in_connections = False
            continue

        # 组件类型开始
        m_type = re.match(
            r'^(system|process|thread|thread\s+group|subprogram|'
            r'subprogram\s+group|data|device|processor|'
            r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)\s+'
            r'(\w+)\s*$',
            stripped
        )
        if m_type and 'implementation' not in stripped:
            cat = m_type.group(1).replace("  ", " ").strip()
            name = m_type.group(2)
            current_type = ComponentType(name=name, category=cat)
            current_impl = None
            in_features = False
            in_subcomponents = False
            in_connections = False
            continue

        # 实现开始
        m_impl = re.match(
            r'^(system|process|thread|thread\s+group|subprogram|'
            r'subprogram\s+group|data|device|processor|'
            r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)\s+'
            r'implementation\s+(\w+\.\w+)\s*$',
            stripped
        )
        if m_impl:
            cat = m_impl.group(1).replace("  ", " ").strip()
            full_name = m_impl.group(2)
            base_type = full_name.rsplit(".", 1)[0]
            current_impl = ComponentImpl(
                name=full_name,
                category=f"{cat} implementation",
                base_type=base_type,
            )
            current_type = None
            in_features = False
            in_subcomponents = False
            in_connections = False
            continue

        # 节开始
        if stripped == "features":
            in_features = True
            if current_type:
                current_type.has_features = True
            continue
        if stripped == "subcomponents":
            in_subcomponents = True
            continue
        if stripped == "connections":
            in_connections = True
            continue

        # 组件结束
        if re.match(r'^end\s+(\w+(?:\.\w+)?)\s*;?\s*$', stripped):
            if current_type:
                pkg.component_types.append(current_type)
                current_type = None
            elif current_impl:
                pkg.component_impls.append(current_impl)
                current_impl = None
            in_features = False
            in_subcomponents = False
            in_connections = False
            continue

        # 端口定义
        if in_features and current_type:
            #  <name>: <direction> <kind> <data_type>;
            port_match = re.match(
                r'^(\w+)\s*:\s*(in\s+out|in|out)\s+'
                r'(data\s+port|event\s+data\s+port|event\s+port)\s+'
                r'([\w:]+(?:\.\w+)?)\s*;?\s*$',
                stripped
            )
            if port_match:
                direction = port_match.group(2).replace("  ", " ").strip()
                kind = port_match.group(3).replace("  ", " ").strip()
                dtype = port_match.group(4)
                current_type.ports.append(PortInfo(
                    name=port_match.group(1),
                    direction=direction,
                    kind=kind,
                    data_type=dtype,
                ))
                continue

            # 没有数据类型的端口（data port 后直接分号）
            port_match2 = re.match(
                r'^(\w+)\s*:\s*(in\s+out|in|out)\s+'
                r'(data\s+port|event\s+data\s+port|event\s+port)\s*;?\s*$',
                stripped
            )
            if port_match2:
                direction = port_match2.group(2).replace("  ", " ").strip()
                kind = port_match2.group(3).replace("  ", " ").strip()
                current_type.ports.append(PortInfo(
                    name=port_match2.group(1),
                    direction=direction,
                    kind=kind,
                ))
                continue

            # feature / bus access 等非端口 feature，忽略
            continue

        # 子组件定义
        if in_subcomponents and current_impl:
            sub_match = re.match(
                r'^(\w+)\s*:\s*(system|process|thread|thread\s+group|'
                r'subprogram|subprogram\s+group|data|device|processor|'
                r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)\s+'
                r'([\w:]+(?:\.\w+)?)\s*;?\s*$',
                stripped
            )
            if sub_match:
                cat = sub_match.group(2).replace("  ", " ").strip()
                current_impl.subcomponents.append(SubcompInfo(
                    name=sub_match.group(1),
                    category=cat,
                    classifier=sub_match.group(3),
                ))
                continue

            # data 子组件带内联属性的（忽略属性部分）
            sub_data_match = re.match(
                r'^(\w+)\s*:\s*data\s+(\w+)\s*\{',
                stripped
            )
            if sub_data_match:
                current_impl.subcomponents.append(SubcompInfo(
                    name=sub_data_match.group(1),
                    category="data",
                    classifier=sub_data_match.group(2),
                ))
                continue

        # 连接定义
        if in_connections and current_impl:
            # <name>: port <src> -> <dst>;
            conn_match = re.match(
                r'^(\w+)\s*:\s*(port)\s+([\w.]+)\s*->\s*([\w.]+)\s*;?\s*$',
                stripped
            )
            if conn_match:
                current_impl.connections.append(ConnectionInfo(
                    name=conn_match.group(1),
                    conn_type=conn_match.group(2),
                    source=conn_match.group(3),
                    target=conn_match.group(4),
                ))
                continue

    return pkg


# ============================================================
# 构建语义引擎的 arch JSON
# ============================================================

def pkg_to_arch_json(pkg: AadlPackage) -> dict:
    """把解析出来的包结构转为语义引擎需要的 arch JSON 格式"""
    type_components = []
    for ct in pkg.component_types:
        type_components.append({
            "name": ct.name,
            "category": ct.category,
            "ports": [
                {
                    "name": p.name,
                    "direction": p.direction,
                    "kind": p.kind,
                    "data_type": p.data_type,
                }
                for p in ct.ports
            ],
        })

    impl_components = []
    for ci in pkg.component_impls:
        impl_components.append({
            "name": ci.name,
            "category": ci.category,
            "base_type": ci.base_type,
            "subcomponents": [
                {
                    "name": s.name,
                    "category": s.category,
                    "classifier": s.classifier,
                }
                for s in ci.subcomponents
            ],
        })

    return {
        "package_name": pkg.name,
        "type_components": type_components,
        "impl_components": impl_components,
    }


# ============================================================
# 验证约束
# ============================================================

@dataclass
class ValidationResult:
    constraint_name: str
    file: str
    location: str
    actual_value: str
    valid_count: int
    passed: bool
    message: str = ""


def validate_file(filepath: str) -> List[ValidationResult]:
    """验证单个 AADL 文件的所有约束点"""
    results = []

    try:
        pkg = extract_aadl_structure(filepath)
    except Exception as e:
        return [ValidationResult(
            constraint_name="parse",
            file=filepath,
            location="file",
            actual_value="",
            valid_count=0,
            passed=False,
            message=f"解析失败: {e}",
        )]

    if not pkg.component_types and not pkg.component_impls:
        return [ValidationResult(
            constraint_name="parse",
            file=filepath,
            location="file",
            actual_value="",
            valid_count=0,
            passed=False,
            message="未提取到任何组件",
        )]

    arch = pkg_to_arch_json(pkg)
    engine = FullAadlSemanticEngine()
    engine.prefill_from_arch_json(arch)

    filename = os.path.basename(filepath)

    # 构造前缀文本（package + 所有组件类型 + 目标实现前的内容）
    # 为了验证每个约束点，我们需要构造"截止到该位置之前"的文本

    # ---- 验证1：子组件 classifier ----
    for impl in pkg.component_impls:
        for sub in impl.subcomponents:
            # 构造文本：包开头 + 所有类型 + 该实现的 subcomponents 节，到该子组件的类别后面
            text_lines = [f"package {pkg.name}", "public"]
            for t in pkg.component_types:
                text_lines.append(f"{t.category} {t.name}")
                if t.ports:
                    text_lines.append("  features")
                    for p in t.ports:
                        dtype = p.data_type if p.data_type else ""
                        text_lines.append(
                            f"    {p.name}: {p.direction} {p.kind} {dtype}"
                        )
                text_lines.append(f"end {t.name};")
                text_lines.append("")

            # 实现开头到这个子组件
            base_cat = impl.category.replace(" implementation", "")
            text_lines.append(f"{base_cat} implementation {impl.name}")
            text_lines.append("  subcomponents")
            # 加上前面的子组件（完整的）
            for prev_sub in impl.subcomponents:
                if prev_sub.name == sub.name:
                    break
                text_lines.append(
                    f"    {prev_sub.name}: {prev_sub.category} {prev_sub.classifier};"
                )
            # 当前子组件（刚输入完类别，准备输 classifier）
            text_lines.append(f"    {sub.name}: {sub.category} ")

            text = "\n".join(text_lines)
            valid_values = engine.get_valid_values(text)

            if valid_values is None:
                results.append(ValidationResult(
                    constraint_name="子组件分类器",
                    file=filename,
                    location=f"{impl.name}.{sub.name}",
                    actual_value=sub.classifier,
                    valid_count=-1,
                    passed=False,
                    message="引擎返回 None（未识别该位置）",
                ))
            else:
                # classifier 可能带包前缀（如 Base_Types::Float），简单比对名称
                passed = _classifier_matches(sub.classifier, valid_values)
                results.append(ValidationResult(
                    constraint_name="子组件分类器",
                    file=filename,
                    location=f"{impl.name}.{sub.name}",
                    actual_value=sub.classifier,
                    valid_count=len(valid_values),
                    passed=passed,
                    message="" if passed else f"不在合法值列表中: {valid_values[:5]}...",
                ))

    # ---- 验证2：连接源端 ----
    for impl in pkg.component_impls:
        for conn in impl.connections:
            if not impl.subcomponents:
                continue  # 没有子组件的话连接源端列表可能不完整

            text_lines = [f"package {pkg.name}", "public"]
            for t in pkg.component_types:
                text_lines.append(f"{t.category} {t.name}")
                if t.ports:
                    text_lines.append("  features")
                    for p in t.ports:
                        dtype = p.data_type if p.data_type else ""
                        text_lines.append(
                            f"    {p.name}: {p.direction} {p.kind} {dtype}"
                        )
                text_lines.append(f"end {t.name};")
                text_lines.append("")

            base_cat = impl.category.replace(" implementation", "")
            text_lines.append(f"{base_cat} implementation {impl.name}")
            text_lines.append("  subcomponents")
            for s in impl.subcomponents:
                text_lines.append(f"    {s.name}: {s.category} {s.classifier};")
            text_lines.append("  connections")
            # 前面的连接
            for prev_conn in impl.connections:
                if prev_conn.name == conn.name:
                    break
                text_lines.append(
                    f"    {prev_conn.name}: {prev_conn.conn_type} {prev_conn.source} -> {prev_conn.target};"
                )
            # 当前连接（刚输入完名字和类型，准备输源端）
            text_lines.append(f"    {conn.name}: {conn.conn_type} ")

            text = "\n".join(text_lines)
            valid_values = engine.get_valid_values(text)

            if valid_values is None:
                results.append(ValidationResult(
                    constraint_name="连接源端",
                    file=filename,
                    location=f"{impl.name}.{conn.name}",
                    actual_value=conn.source,
                    valid_count=-1,
                    passed=False,
                    message="引擎返回 None（未识别该位置）",
                ))
            else:
                passed = conn.source in valid_values
                results.append(ValidationResult(
                    constraint_name="连接源端",
                    file=filename,
                    location=f"{impl.name}.{conn.name}",
                    actual_value=conn.source,
                    valid_count=len(valid_values),
                    passed=passed,
                    message="" if passed else f"不在合法值列表中: {valid_values[:5]}...",
                ))

    # ---- 验证3：连接目标端 ----
    for impl in pkg.component_impls:
        for conn in impl.connections:
            if not impl.subcomponents:
                continue

            text_lines = [f"package {pkg.name}", "public"]
            for t in pkg.component_types:
                text_lines.append(f"{t.category} {t.name}")
                if t.ports:
                    text_lines.append("  features")
                    for p in t.ports:
                        dtype = p.data_type if p.data_type else ""
                        text_lines.append(
                            f"    {p.name}: {p.direction} {p.kind} {dtype}"
                        )
                text_lines.append(f"end {t.name};")
                text_lines.append("")

            base_cat = impl.category.replace(" implementation", "")
            text_lines.append(f"{base_cat} implementation {impl.name}")
            text_lines.append("  subcomponents")
            for s in impl.subcomponents:
                text_lines.append(f"    {s.name}: {s.category} {s.classifier};")
            text_lines.append("  connections")
            for prev_conn in impl.connections:
                if prev_conn.name == conn.name:
                    break
                text_lines.append(
                    f"    {prev_conn.name}: {prev_conn.conn_type} {prev_conn.source} -> {prev_conn.target};"
                )
            # 当前连接（源端已输入，准备输目标端）
            text_lines.append(f"    {conn.name}: {conn.conn_type} {conn.source} -> ")

            text = "\n".join(text_lines)
            valid_values = engine.get_valid_values(text)

            if valid_values is None:
                results.append(ValidationResult(
                    constraint_name="连接目标端",
                    file=filename,
                    location=f"{impl.name}.{conn.name}",
                    actual_value=conn.target,
                    valid_count=-1,
                    passed=False,
                    message="引擎返回 None（未识别该位置）",
                ))
            else:
                passed = conn.target in valid_values
                results.append(ValidationResult(
                    constraint_name="连接目标端",
                    file=filename,
                    location=f"{impl.name}.{conn.name}",
                    actual_value=conn.target,
                    valid_count=len(valid_values),
                    passed=passed,
                    message="" if passed else f"不在合法值列表中: {valid_values[:5]}...",
                ))

    return results


def _classifier_matches(actual: str, valid_list: List[str]) -> bool:
    """检查实际 classifier 是否匹配合法值列表中的某一项

    处理带包前缀的情况，如 Base_Types::Float vs Float
    """
    if actual in valid_list:
        return True
    # 去掉包前缀再比
    short = actual.split("::")[-1]
    if short in valid_list:
        return True
    # 合法值加包前缀
    for v in valid_list:
        if v.endswith("::" + short):
            return True
        if actual.endswith("::" + v):
            return True
    return False


# ============================================================
# 批量验证
# ============================================================

def find_aadl_files(root_dir: str) -> List[str]:
    """递归查找所有 .aadl 文件"""
    result = []
    for dirpath, _, filenames in os.walk(root_dir):
        for f in filenames:
            if f.endswith(".aadl"):
                result.append(os.path.join(dirpath, f))
    return sorted(result)


def run_validation(root_dir: str, max_files: int = 0):
    """运行验证，打印结果汇总"""
    files = find_aadl_files(root_dir)
    if max_files > 0:
        files = files[:max_files]

    print(f"找到 {len(files)} 个 AADL 文件")
    print()

    all_results = []
    parse_failed = []

    for f in files:
        results = validate_file(f)
        if not results:
            continue
        if results[0].constraint_name == "parse" and not results[0].passed:
            parse_failed.append((f, results[0].message))
            continue
        all_results.extend(results)

    # ---- 汇总 ----
    print("=" * 70)
    print("验证结果汇总")
    print("=" * 70)

    # 区分单包内引用 vs 跨包引用
    local_results = [r for r in all_results if "::" not in r.actual_value]
    cross_results = [r for r in all_results if "::" in r.actual_value]

    def _print_summary(results, title):
        from collections import defaultdict
        by_constraint = defaultdict(list)
        for r in results:
            by_constraint[r.constraint_name].append(r)

        print(f"\n【{title}】共 {len(results)} 个验证点")
        print(f"{'约束类型':<15} {'总数':>5} {'通过':>5} {'失败':>5} {'通过率':>8}")
        print("-" * 50)
        for cname in sorted(by_constraint.keys()):
            items = by_constraint[cname]
            passed = sum(1 for r in items if r.passed)
            total = len(items)
            rate = passed / total * 100 if total > 0 else 0
            print(f"{cname:<15} {total:>5} {passed:>5} {total - passed:>5} {rate:>7.1f}%")

        total_all = len(results)
        passed_all = sum(1 for r in results if r.passed)
        rate_all = passed_all / total_all * 100 if total_all > 0 else 0
        print("-" * 50)
        print(f"{'合计':<15} {total_all:>5} {passed_all:>5} {total_all - passed_all:>5} {rate_all:>7.1f}%")

    _print_summary(local_results, "单包内引用（当前包定义的组件）")
    _print_summary(cross_results, "跨包引用（带 ::，符号表中无定义，跳过）")
    print()

    # 解析失败的文件
    if parse_failed:
        print(f"解析失败/无组件的文件: {len(parse_failed)} 个")
        for f, msg in parse_failed[:10]:
            print(f"  - {os.path.basename(f)}: {msg}")
        if len(parse_failed) > 10:
            print(f"  ... 还有 {len(parse_failed) - 10} 个")
        print()

    # 打印失败案例（前10个）
    failed = [r for r in all_results if not r.passed]
    if failed:
        print("失败案例（前10个）:")
        print("-" * 70)
        for r in failed[:10]:
            print(f"  [{r.constraint_name}] {r.file} @ {r.location}")
            print(f"    实际值: {r.actual_value}")
            print(f"    合法值数量: {r.valid_count}")
            if r.message:
                print(f"    说明: {r.message}")
        if len(failed) > 10:
            print(f"  ... 还有 {len(failed) - 10} 个失败案例")

    print()
    print("=" * 70)

    return all_results


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="验证 AADL 语义约束规则")
    parser.add_argument("path", help="AADL 文件或目录路径")
    parser.add_argument("--max-files", type=int, default=0,
                        help="最多验证多少个文件（0=全部）")
    args = parser.parse_args()

    if os.path.isfile(args.path):
        # 单文件
        results = validate_file(args.path)
        filename = os.path.basename(args.path)
        print(f"文件: {filename}")
        print(f"验证点: {len(results)}")
        print()
        for r in results:
            status = "✅" if r.passed else "❌"
            print(f"  {status} [{r.constraint_name}] {r.location}")
            print(f"     实际值: {r.actual_value}, 合法值数: {r.valid_count}")
            if r.message:
                print(f"     {r.message}")
    else:
        # 目录
        run_validation(args.path, args.max_files)
