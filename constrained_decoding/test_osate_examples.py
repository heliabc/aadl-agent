#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
osate-examples 真实 AADL 文件解析测试
======================================
把 osate-examples 里的真实 AADL 文件做轻量解析，提取架构信息，
用来测试/验证我们的 JSON schema 和语义校验器。

测试维度：
1. 能否正确识别包名、组件类型/实现、端口、子组件、连接
2. 提取出的架构 JSON 能否通过语义校验器
3. 包含规则、引用完整性等核心规则是否与真实 AADL 一致

注意：这是"轻量解析"，只提取架构结构信息，不解析 Annex、属性值细节等。
"""

import re
import json
import os
import sys
from pathlib import Path
from typing import List, Dict, Optional, Tuple

# 把 constrained_decoding 加入路径
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from json_validator import quick_validate, ValidationResult


# ============================================================
# 轻量 AADL 解析器
# ============================================================

# 组件类别关键字（按优先级，长的在前避免前缀匹配问题）
COMPONENT_KEYWORDS = [
    "abstract",
    "bus",
    "data",
    "device",
    "memory",
    "processor",
    "process",
    "subprogram group",
    "subprogram",
    "system",
    "thread group",
    "thread",
    "virtual bus",
    "virtual processor",
]

# 端口方向
PORT_DIRECTIONS = ["in out", "in", "out"]

# 端口种类
PORT_KINDS = ["event data port", "data port", "event port"]

# 连接关键字
CONNECTION_KINDS = ["port connection", "feature group connection", "access connection"]


class AADLLightParser:
    """
    轻量 AADL 解析器。
    只提取架构结构（组件、端口、子组件、连接），不做完整语法解析。
    基于正则和关键词匹配，速度快，适合批量测试。
    """

    def parse_file(self, filepath: str) -> Optional[dict]:
        """解析单个 AADL 文件，返回架构 JSON 字典（如果能解析的话）"""
        try:
            with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()
        except Exception as e:
            return None

        # 去掉注释（-- 开头的行内注释）
        content = re.sub(r'--.*$', '', content, flags=re.MULTILINE)
        # 去掉 /* */ 块注释
        content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)

        # 提取包名
        pkg_match = re.search(r'package\s+([\w:]+)', content)
        if not pkg_match:
            return None
        package_name = pkg_match.group(1)

        # 提取 with 列表
        with_packages = re.findall(r'with\s+([\w:]+)\s*;', content)

        # 找所有组件声明（类型 + 实现）
        components = self._find_components(content)

        if not components:
            return None

        return {
            "package_name": package_name,
            "with_packages": with_packages,
            "components": components,
        }

    def _find_components(self, content: str) -> List[dict]:
        """查找所有组件类型和实现声明"""
        components = []

        # 找所有 "类别 组件名" 的起始位置
        # 模式：<keyword> <name> [extends <parent>] [is]
        for kw in COMPONENT_KEYWORDS:
            # 类型声明: keyword name [extends ...] [is]
            pattern = rf'^\s*{kw}\s+([\w]+)(?:\s+extends\s+([\w:]+))?\s*(?:is)?\s*$'
            for match in re.finditer(pattern, content, re.MULTILINE):
                name = match.group(1)
                extends = match.group(2)

                # 跳过 annex、property set 等非组件声明
                # 通过检查后面是否有 "end name;" 来判断是不是完整组件
                start = match.end()
                end_pattern = rf'end\s+{re.escape(name)}\s*;'
                end_match = re.search(end_pattern, content[start:])
                if not end_match:
                    continue

                body_end = start + end_match.start()
                body = content[start:body_end]

                # 判断是不是实现（有 implementation 关键字）
                # 类型声明的 body 里不应该有 "implementation"
                if re.search(r'\bimplementation\b', body[:50]):
                    continue  # 这是实现，后面单独处理

                # 解析 features
                ports, accesses = self._parse_features(body)

                # 解析 flows
                flows = self._parse_flows(body)

                # 检查是否有实现
                has_impl = self._has_implementation(content, name, kw)

                comp = {
                    "name": name,
                    "category": kw,
                    "has_impl": has_impl,
                    "ports": ports,
                    "accesses": accesses,
                    "flows": flows,
                    "properties": [],
                }
                if extends:
                    comp["extends"] = extends
                components.append(comp)

        # 找实现声明
        impl_pattern = (
            r'^\s*([\w]+)\s*\.\s*([\w]+)\s*:\s*([\w\s]+?)\s*implementation'
            r'(?:\s+extends\s+([\w:.]+))?\s*(?:is)?\s*$'
        )
        for match in re.finditer(impl_pattern, content, re.MULTILINE):
            base_name = match.group(1)
            impl_id = match.group(2)
            cat_raw = match.group(3).strip()
            extends = match.group(4)
            impl_name = f"{base_name}.{impl_id}"

            # 规范化类别名
            category = f"{cat_raw} implementation"

            start = match.end()
            end_pattern = rf'end\s+{re.escape(base_name)}\.{re.escape(impl_id)}\s*;'
            end_match = re.search(end_pattern, content[start:])
            if not end_match:
                # 也可能是 end base_name.impl;
                alt_end = rf'end\s+{re.escape(impl_name)}\s*;'
                alt_match = re.search(alt_end, content[start:])
                if not alt_match:
                    continue
                body_end = start + alt_match.start()
            else:
                body_end = start + end_match.start()

            body = content[start:body_end]

            # 解析子组件
            subcomponents = self._parse_subcomponents(body)

            # 解析连接
            connections = self._parse_connections(body)

            impl = {
                "name": impl_name,
                "category": category,
                "base_type": base_name,
                "subcomponents": subcomponents,
                "connections": connections,
                "bindings": [],
                "flows": [],
                "properties": [],
            }
            if extends:
                impl["extends"] = extends
            components.append(impl)

        return components

    def _parse_features(self, body: str) -> Tuple[List[dict], List[dict]]:
        """解析 features 节，返回 (ports, accesses)"""
        ports = []
        accesses = []

        # 找 features 块
        feat_match = re.search(r'features\s*(?::|is)?\s*\n(.*?)(?=\n\s*(end\s|subcomponents|connections|flows|properties|calls))',
                               body, re.DOTALL | re.IGNORECASE)
        if not feat_match:
            return ports, accesses

        feat_body = feat_match.group(1)

        # 解析端口
        # 模式: name: direction kind [type];
        port_pattern = (
            r'^\s*([\w]+)\s*:\s*'
            r'(in out|in|out)\s+'
            r'(event data port|data port|event port)'
            r'(?:\s+([\w:]+))?'
            r'\s*;'
        )
        for m in re.finditer(port_pattern, feat_body, re.MULTILINE | re.IGNORECASE):
            port = {
                "name": m.group(1),
                "direction": m.group(2),
                "kind": m.group(3),
                "data_type": m.group(4),
                "properties": [],
            }
            if m.group(4):
                # 简单判断类型来源
                dt = m.group(4)
                if "::" in dt and "Base_Types" in dt:
                    port["data_type_source"] = "predefined"
                elif "::" in dt:
                    port["data_type_source"] = "external"
                else:
                    port["data_type_source"] = "custom"
            ports.append(port)

        # 解析 access
        access_pattern = (
            r'^\s*([\w]+)\s*:\s*'
            r'(provides|requires)\s+'
            r'(bus access|data access|subprogram access)'
            r'(?:\s+([\w:]+))?'
            r'\s*;'
        )
        for m in re.finditer(access_pattern, feat_body, re.MULTILINE | re.IGNORECASE):
            access = {
                "name": m.group(1),
                "direction": m.group(2),
                "kind": m.group(3),
                "classifier": m.group(4) or "",
                "properties": [],
            }
            accesses.append(access)

        return ports, accesses

    def _parse_subcomponents(self, body: str) -> List[dict]:
        """解析 subcomponents 节"""
        subs = []

        sub_match = re.search(
            r'subcomponents\s*(?::|is)?\s*\n(.*?)(?=\n\s*(end\s|connections|flows|properties|calls))',
            body, re.DOTALL | re.IGNORECASE
        )
        if not sub_match:
            return subs

        sub_body = sub_match.group(1)

        # 模式: name: category classifier;
        for kw in COMPONENT_KEYWORDS:
            pattern = rf'^\s*([\w]+)\s*:\s*{re.escape(kw)}\s+([\w:.]+)\s*;'
            for m in re.finditer(pattern, sub_body, re.MULTILINE | re.IGNORECASE):
                sub = {
                    "name": m.group(1),
                    "category": kw,
                    "classifier": m.group(2),
                    "properties": [],
                }
                subs.append(sub)

        # 去重（按 name）
        seen = set()
        unique = []
        for s in subs:
            if s["name"] not in seen:
                seen.add(s["name"])
                unique.append(s)
        return unique

    def _parse_connections(self, body: str) -> List[dict]:
        """解析 connections 节"""
        conns = []

        conn_match = re.search(
            r'connections\s*(?::|is)?\s*\n(.*?)(?=\n\s*(end\s|subcomponents|flows|properties|calls))',
            body, re.DOTALL | re.IGNORECASE
        )
        if not conn_match:
            return conns

        conn_body = conn_match.group(1)

        # 端口连接: name: source -> target;
        port_conn_pattern = (
            r'^\s*([\w]+)\s*:\s*'
            r'(?:port\s+connection\s+)?'
            r'([\w.]+)\s*->\s*([\w.]+)\s*;'
        )
        for m in re.finditer(port_conn_pattern, conn_body, re.MULTILINE | re.IGNORECASE):
            conn = {
                "name": m.group(1),
                "kind": "port connection",
                "source": m.group(2),
                "target": m.group(3),
                "bidirectional": False,
                "bound_to_bus": None,
                "properties": [],
            }
            conns.append(conn)

        # 双向连接: name: source <-> target; (bus access 等)
        bi_conn_pattern = (
            r'^\s*([\w]+)\s*:\s*'
            r'(?:access\s+connection\s+)?'
            r'([\w.]+)\s*<->\s*([\w.]+)\s*;'
        )
        for m in re.finditer(bi_conn_pattern, conn_body, re.MULTILINE | re.IGNORECASE):
            conn = {
                "name": m.group(1),
                "kind": "access connection",
                "source": m.group(2),
                "target": m.group(3),
                "bidirectional": True,
                "bound_to_bus": None,
                "properties": [],
            }
            conns.append(conn)

        return conns

    def _parse_flows(self, body: str) -> List[dict]:
        """解析 flows 节"""
        flows = []

        flow_match = re.search(
            r'flows\s*(?::|is)?\s*\n(.*?)(?=\n\s*(end\s|subcomponents|connections|properties|calls))',
            body, re.DOTALL | re.IGNORECASE
        )
        if not flow_match:
            return flows

        flow_body = flow_match.group(1)

        # flow path: name: flow path source -> target;
        path_pattern = (
            r'^\s*([\w]+)\s*:\s*flow\s+path\s+([\w.]+)\s*->\s*([\w.]+)\s*;'
        )
        for m in re.finditer(path_pattern, flow_body, re.MULTILINE | re.IGNORECASE):
            flows.append({
                "name": m.group(1),
                "kind": "flow path",
                "source": m.group(2),
                "target": m.group(3),
                "path": None,
                "properties": [],
            })

        # flow source: name: flow source port;
        src_pattern = r'^\s*([\w]+)\s*:\s*flow\s+source\s+([\w.]+)\s*;'
        for m in re.finditer(src_pattern, flow_body, re.MULTILINE | re.IGNORECASE):
            flows.append({
                "name": m.group(1),
                "kind": "flow source",
                "source": m.group(2),
                "target": None,
                "path": None,
                "properties": [],
            })

        # flow sink
        sink_pattern = r'^\s*([\w]+)\s*:\s*flow\s+sink\s+([\w.]+)\s*;'
        for m in re.finditer(sink_pattern, flow_body, re.MULTILINE | re.IGNORECASE):
            flows.append({
                "name": m.group(1),
                "kind": "flow sink",
                "source": None,
                "target": m.group(2),
                "path": None,
                "properties": [],
            })

        return flows

    def _has_implementation(self, content: str, type_name: str, category: str) -> bool:
        """检查某个类型是否有对应的实现"""
        pattern = rf'{re.escape(type_name)}\s*\.\s*[\w]+\s*:\s*{re.escape(category)}\s+implementation'
        return bool(re.search(pattern, content, re.IGNORECASE))


# ============================================================
# 批量测试
# ============================================================

def scan_aadl_files(root_dir: str) -> List[str]:
    """扫描目录下所有 .aadl 文件"""
    aadl_files = []
    for dirpath, _, filenames in os.walk(root_dir):
        for f in filenames:
            if f.endswith('.aadl'):
                aadl_files.append(os.path.join(dirpath, f))
    return sorted(aadl_files)


def run_tests(examples_dir: str, output_dir: Optional[str] = None) -> dict:
    """
    运行批量测试。

    Returns:
        统计结果字典
    """
    parser = AADLLightParser()
    files = scan_aadl_files(examples_dir)

    stats = {
        "total_files": len(files),
        "parsed_files": 0,
        "files_with_components": 0,
        "validation_passed": 0,
        "validation_failed": 0,
        "total_components": 0,
        "total_ports": 0,
        "total_subcomponents": 0,
        "total_connections": 0,
        "details": [],
    }

    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    for filepath in files:
        rel_path = os.path.relpath(filepath, examples_dir)

        try:
            arch = parser.parse_file(filepath)
        except Exception as e:
            stats["details"].append({
                "file": rel_path,
                "status": "parse_error",
                "error": str(e),
            })
            continue

        if arch is None:
            stats["details"].append({
                "file": rel_path,
                "status": "no_package",
            })
            continue

        stats["parsed_files"] += 1

        comp_count = len(arch["components"])
        if comp_count == 0:
            stats["details"].append({
                "file": rel_path,
                "status": "no_components",
            })
            continue

        stats["files_with_components"] += 1
        stats["total_components"] += comp_count

        # 统计端口/子组件/连接数
        for comp in arch["components"]:
            stats["total_ports"] += len(comp.get("ports", []))
            stats["total_subcomponents"] += len(comp.get("subcomponents", []))
            stats["total_connections"] += len(comp.get("connections", []))

        # 语义校验
        result = quick_validate(arch)
        if result.valid:
            stats["validation_passed"] += 1
            status = "passed"
        else:
            stats["validation_failed"] += 1
            status = "failed"

        detail = {
            "file": rel_path,
            "status": status,
            "components": comp_count,
            "ports": sum(len(c.get("ports", [])) for c in arch["components"]),
            "subcomponents": sum(len(c.get("subcomponents", [])) for c in arch["components"]),
            "connections": sum(len(c.get("connections", [])) for c in arch["components"]),
        }
        if not result.valid:
            detail["errors"] = result.errors[:5]  # 最多显示 5 个
        if result.warnings:
            detail["warnings"] = result.warnings[:5]

        stats["details"].append(detail)

        # 保存解析出的 JSON（如果指定了输出目录）
        if output_dir:
            safe_name = rel_path.replace(os.sep, "__").replace(".aadl", ".json")
            out_path = os.path.join(output_dir, safe_name)
            with open(out_path, "w", encoding="utf-8") as f:
                json.dump(arch, f, indent=2, ensure_ascii=False)

    return stats


def print_report(stats: dict):
    """打印测试报告"""
    print("=" * 70)
    print("  osate-examples AADL 解析测试报告")
    print("=" * 70)
    print()
    print(f"  总文件数:           {stats['total_files']}")
    print(f"  成功解析:           {stats['parsed_files']}")
    print(f"  含组件的文件:       {stats['files_with_components']}")
    print(f"  语义校验通过:       {stats['validation_passed']}")
    print(f"  语义校验失败:       {stats['validation_failed']}")
    print()
    print(f"  总组件数:           {stats['total_components']}")
    print(f"  总端口数:           {stats['total_ports']}")
    print(f"  总子组件数:         {stats['total_subcomponents']}")
    print(f"  总连接数:           {stats['total_connections']}")
    print()

    if stats['validation_failed'] > 0:
        print("-" * 70)
        print("  校验失败的文件：")
        print("-" * 70)
        for d in stats["details"]:
            if d.get("status") == "failed":
                print(f"\n  ✗ {d['file']}")
                print(f"    组件: {d['components']}, 端口: {d['ports']}, "
                      f"子组件: {d['subcomponents']}, 连接: {d['connections']}")
                for err in d.get("errors", [])[:3]:
                    print(f"      · {err}")
        print()

    # 显示警告
    warned = [d for d in stats["details"] if d.get("warnings")]
    if warned:
        print("-" * 70)
        print(f"  有警告的文件（{len(warned)} 个）：")
        print("-" * 70)
        for d in warned[:10]:
            print(f"\n  ⚠ {d['file']}")
            for w in d.get("warnings", [])[:2]:
                print(f"      · {w}")
        if len(warned) > 10:
            print(f"\n  ... 还有 {len(warned) - 10} 个文件有警告")
        print()

    print("=" * 70)
    pass_rate = (
        stats['validation_passed'] / stats['files_with_components'] * 100
        if stats['files_with_components'] > 0 else 0
    )
    print(f"  校验通过率: {pass_rate:.1f}% "
          f"({stats['validation_passed']}/{stats['files_with_components']})")
    print("=" * 70)


# ============================================================
# 主入口
# ============================================================

if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="osate-examples AADL 解析测试")
    parser.add_argument(
        "--examples-dir",
        default=r"C:\Users\heli0\Desktop\osate-examples\examples",
        help="osate-examples 的 examples 目录路径",
    )
    parser.add_argument(
        "--output-dir",
        default=None,
        help="保存解析出的 JSON 文件的目录（可选）",
    )
    parser.add_argument(
        "--file", "-f",
        default=None,
        help="只测试单个 AADL 文件",
    )
    args = parser.parse_args()

    if args.file:
        # 单个文件测试
        parser = AADLLightParser()
        arch = parser.parse_file(args.file)
        if arch:
            print(json.dumps(arch, indent=2, ensure_ascii=False))
            print("\n" + "=" * 50)
            result = quick_validate(arch)
            print(result.summary())
        else:
            print(f"无法解析文件: {args.file}")
    else:
        # 批量测试
        stats = run_tests(args.examples_dir, args.output_dir)
        print_report(stats)

        if args.output_dir:
            stats_path = os.path.join(args.output_dir, "_stats.json")
            with open(stats_path, "w", encoding="utf-8") as f:
                json.dump(stats, f, indent=2, ensure_ascii=False, default=str)
            print(f"\n统计结果已保存到: {stats_path}")
