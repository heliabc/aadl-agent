#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
语义规则验证脚本（基于 Lark Parser 标准答案）
=============================================
用 aadl_lark_parser 从真实 AADL 文件构建"标准答案"符号表，
然后测试语义引擎在各个语义点返回的合法值是否包含正确答案。

只统计单包内引用（不包含跨包 :: 的情况）。

用法:
  python validate_semantic_lark.py path/to/osate-examples
  python validate_semantic_lark.py path/to/file.aadl
  python validate_semantic_lark.py path/to/dir --max-files 20
"""

import os
import sys
import argparse
from typing import List, Tuple, Dict

from aadl_lark_parser import parse_aadl
from symbol_table import SymbolTable


# ============================================================
# 从标准答案符号表提取验证点
# ============================================================

def extract_test_cases(st: SymbolTable) -> List[dict]:
    """
    从符号表中提取所有可验证的语义点。
    返回测试用例列表，每个用例包含：
      - type: 测试类型（subcomp_classifier / conn_source / conn_target）
      - impl_name: 所在实现名
      - context: 构造的上下文文本（用于喂给语义引擎）
      - expected: 期望值（正确答案）
      - location: 描述位置
    """
    cases = []

    for impl_name, impl in st.component_impls.items():
        # --- 子组件分类器 ---
        for sub_name, sub_info in impl.subcomponents.items():
            classifier = sub_info.classifier
            # 只测单包内引用（不含 ::）
            if "::" in classifier:
                continue
            cases.append({
                "type": "subcomp_classifier",
                "impl_name": impl_name,
                "sub_name": sub_name,
                "sub_category": sub_info.category,
                "expected": classifier,
                "location": f"{impl_name} / subcomponents / {sub_name}",
            })

        # --- 连接源端 ---
        for conn in impl.connections:
            source = conn.get("source", "")
            if not source or "::" in source:
                continue
            cases.append({
                "type": "conn_source",
                "impl_name": impl_name,
                "conn_name": conn.get("name", ""),
                "expected": source,
                "location": f"{impl_name} / connections / {conn.get('name','?')} source",
            })

        # --- 连接目标端 ---
        for conn in impl.connections:
            target = conn.get("target", "")
            if not target or "::" in target:
                continue
            cases.append({
                "type": "conn_target",
                "impl_name": impl_name,
                "conn_name": conn.get("name", ""),
                "expected": target,
                "location": f"{impl_name} / connections / {conn.get('name','?')} target",
            })

    return cases


# ============================================================
# 构造上下文文本并测试语义引擎
# ============================================================

def build_context_for_subcomp(st: SymbolTable, impl_name: str,
                               sub_name: str, sub_category: str) -> str:
    """
    构造子组件分类器位置的上下文文本。
    生成到 "  subname: category " 的位置，光标在分类器前面。
    """
    impl = st.component_impls[impl_name]
    base_type = st.component_types.get(impl.base_name)

    lines = [f"package {st.package_name}"]
    lines.append("public")

    # 先输出所有组件类型
    for tname, tinfo in st.component_types.items():
        lines.append(f"  {tinfo.category} {tname}")
        if tinfo.ports:
            lines.append("    features")
            for pname, pinfo in tinfo.ports.items():
                dtype = f" {pinfo.data_type}" if pinfo.data_type else ""
                lines.append(f"      {pname}: {pinfo.direction} {pinfo.kind}{dtype};")
            lines.append(f"    end {tname};")
        else:
            lines.append(f"    end {tname};")

    # 输出当前实现，到目标子组件之前
    lines.append(f"  {impl.category.replace(' implementation', '')} implementation {impl.name}")
    lines.append("    subcomponents")

    for sname, sinfo in impl.subcomponents.items():
        if sname == sub_name:
            # 到这个子组件的分类器位置就停
            lines.append(f"      {sname}: {sub_category} ")
            # 注意：结尾是空格，光标在分类器前面
            break
        else:
            lines.append(f"      {sname}: {sinfo.category} {sinfo.classifier};")

    # 不输出后面的（包括 connections 和 end）
    # 因为光标在 subcomponents 里的分类器位置

    return "\n".join(lines)


def build_context_for_conn_source(st: SymbolTable, impl_name: str,
                                   conn_name: str) -> str:
    """构造连接源端位置的上下文文本"""
    impl = st.component_impls[impl_name]

    lines = [f"package {st.package_name}"]
    lines.append("public")

    # 所有组件类型
    for tname, tinfo in st.component_types.items():
        lines.append(f"  {tinfo.category} {tname}")
        if tinfo.ports:
            lines.append("    features")
            for pname, pinfo in tinfo.ports.items():
                dtype = f" {pinfo.data_type}" if pinfo.data_type else ""
                lines.append(f"      {pname}: {pinfo.direction} {pinfo.kind}{dtype};")
            lines.append(f"    end {tname};")
        else:
            lines.append(f"    end {tname};")

    # 当前实现：完整的 subcomponents + connections 到目标连接前
    lines.append(f"  {impl.category.replace(' implementation', '')} implementation {impl.name}")
    lines.append("    subcomponents")
    for sname, sinfo in impl.subcomponents.items():
        lines.append(f"      {sname}: {sinfo.category} {sinfo.classifier};")

    lines.append("    connections")

    found = False
    for conn in impl.connections:
        if conn.get("name") == conn_name:
            # 到这个连接的源端位置就停
            cname = f"{conn['name']}: " if conn.get("name") else ""
            lines.append(f"      {cname}")
            found = True
            break
        else:
            cname = f"{conn['name']}: " if conn.get("name") else ""
            lines.append(f"      {cname}{conn['source']} -> {conn['target']};")

    if not found:
        # 没有名字的连接，用索引找
        for i, conn in enumerate(impl.connections):
            if conn.get("name") == conn_name or (not conn.get("name") and conn_name == ""):
                cname = f"{conn['name']}: " if conn.get("name") else ""
                lines.append(f"      {cname}")
                break

    return "\n".join(lines)


def build_context_for_conn_target(st: SymbolTable, impl_name: str,
                                   conn_name: str) -> str:
    """构造连接目标端位置的上下文文本"""
    impl = st.component_impls[impl_name]

    lines = [f"package {st.package_name}"]
    lines.append("public")

    for tname, tinfo in st.component_types.items():
        lines.append(f"  {tinfo.category} {tname}")
        if tinfo.ports:
            lines.append("    features")
            for pname, pinfo in tinfo.ports.items():
                dtype = f" {pinfo.data_type}" if pinfo.data_type else ""
                lines.append(f"      {pname}: {pinfo.direction} {pinfo.kind}{dtype};")
            lines.append(f"    end {tname};")
        else:
            lines.append(f"    end {tname};")

    lines.append(f"  {impl.category.replace(' implementation', '')} implementation {impl.name}")
    lines.append("    subcomponents")
    for sname, sinfo in impl.subcomponents.items():
        lines.append(f"      {sname}: {sinfo.category} {sinfo.classifier};")

    lines.append("    connections")

    for conn in impl.connections:
        if conn.get("name") == conn_name:
            cname = f"{conn['name']}: " if conn.get("name") else ""
            lines.append(f"      {cname}{conn['source']} -> ")
            break
        else:
            cname = f"{conn['name']}: " if conn.get("name") else ""
            lines.append(f"      {cname}{conn['source']} -> {conn['target']};")

    return "\n".join(lines)


# ============================================================
# 调用语义引擎验证
# ============================================================

def test_semantic_engine(context_text: str, test_type: str,
                          expected: str, st: SymbolTable) -> Tuple[bool, List[str], str]:
    """
    调用语义引擎，测试在给定上下文下，合法值列表是否包含期望值。

    Returns:
        (passed, valid_values, error_msg)
    """
    try:
        from aadl_context_sensitive import AadlContextSensitiveEngine

        engine = AadlContextSensitiveEngine()
        engine.update(context_text)
        valid_values = engine.get_valid_values(context_text)

        if valid_values is None:
            return False, [], "未识别到语义位置"

        # 大小写不敏感比较（AADL 大小写不敏感）
        expected_lower = expected.lower()
        valid_lower = [v.lower() for v in valid_values]

        passed = expected_lower in valid_lower
        return passed, valid_values, ""

    except Exception as e:
        return False, [], f"引擎异常: {e}"


# ============================================================
# 主验证流程
# ============================================================

def validate_file(filepath: str) -> dict:
    """验证单个 AADL 文件"""
    with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()

    # 1. Lark parser 构建标准答案
    st = parse_aadl(content)
    if st is None:
        return {"parse_failed": True, "filepath": filepath}

    # 2. 提取测试用例
    cases = extract_test_cases(st)
    if not cases:
        return {"no_cases": True, "filepath": filepath, "symbol_table": st}

    # 3. 逐个测试
    results = []
    for case in cases:
        # 构造上下文
        if case["type"] == "subcomp_classifier":
            context = build_context_for_subcomp(
                st, case["impl_name"], case["sub_name"], case["sub_category"])
        elif case["type"] == "conn_source":
            context = build_context_for_conn_source(
                st, case["impl_name"], case["conn_name"])
        elif case["type"] == "conn_target":
            context = build_context_for_conn_target(
                st, case["impl_name"], case["conn_name"])
        else:
            continue

        passed, valid_values, error = test_semantic_engine(
            context, case["type"], case["expected"], st)

        results.append({
            **case,
            "passed": passed,
            "valid_count": len(valid_values),
            "error": error,
        })

    return {
        "filepath": filepath,
        "symbol_table": st,
        "cases": results,
    }


def print_summary(all_results: List[dict]):
    """打印汇总结果"""
    total_cases = 0
    passed_cases = 0
    type_stats = {}  # 按测试类型统计

    parse_failed = 0
    no_cases = 0

    for r in all_results:
        if r.get("parse_failed"):
            parse_failed += 1
            continue
        if r.get("no_cases"):
            no_cases += 1
            continue

        for case in r["cases"]:
            total_cases += 1
            if case["passed"]:
                passed_cases += 1

            t = case["type"]
            if t not in type_stats:
                type_stats[t] = {"total": 0, "passed": 0, "failed_details": []}
            type_stats[t]["total"] += 1
            if case["passed"]:
                type_stats[t]["passed"] += 1
            else:
                type_stats[t]["failed_details"].append({
                    "file": r["filepath"],
                    "location": case["location"],
                    "expected": case["expected"],
                    "valid_count": case["valid_count"],
                    "error": case["error"],
                })

    print()
    print("=" * 70)
    print("验证结果汇总")
    print("=" * 70)
    print(f"解析失败的文件:       {parse_failed}")
    print(f"无可验证用例的文件:   {no_cases}")
    print(f"成功验证的文件:       {len(all_results) - parse_failed - no_cases}")
    print()

    if total_cases == 0:
        print("（没有找到可验证的单包引用测试用例）")
        return

    print(f"总测试点数:           {total_cases}")
    print(f"通过:                 {passed_cases}")
    print(f"失败:                 {total_cases - passed_cases}")
    print(f"通过率:               {passed_cases/total_cases*100:.1f}%")
    print()

    # 按类型细分
    print("-" * 70)
    print(f"{'约束类型':<22} {'总数':>6} {'通过':>6} {'失败':>6} {'通过率':>8}")
    print("-" * 70)
    type_names = {
        "subcomp_classifier": "子组件分类器",
        "conn_source": "连接源端",
        "conn_target": "连接目标端",
    }
    for t, stats in type_stats.items():
        name = type_names.get(t, t)
        rate = stats["passed"] / stats["total"] * 100 if stats["total"] > 0 else 0
        print(f"{name:<22} {stats['total']:>6} {stats['passed']:>6} "
              f"{stats['total']-stats['passed']:>6} {rate:>7.1f}%")
    print("-" * 70)

    # 失败案例详情（前 20 个）
    failed_all = []
    for stats in type_stats.values():
        failed_all.extend(stats["failed_details"])

    if failed_all:
        print()
        print(f"失败案例（共 {len(failed_all)} 个，显示前 20 个）：")
        print("-" * 70)
        for f in failed_all[:20]:
            fname = os.path.basename(f["file"])
            err = f["error"] or f"合法值 {f['valid_count']} 个"
            print(f"  [{fname}] {f['location']}")
            print(f"    期望: {f['expected']}")
            print(f"    原因: {err}")
        print("-" * 70)


def collect_aadl_files(path: str) -> List[str]:
    """收集所有 .aadl 文件"""
    if os.path.isfile(path):
        return [path]

    files = []
    for root, dirs, fnames in os.walk(path):
        for fn in fnames:
            if fn.endswith(".aadl"):
                files.append(os.path.join(root, fn))
    return sorted(files)


def main():
    parser = argparse.ArgumentParser(description="语义规则验证（Lark Parser 标准答案版）")
    parser.add_argument("path", help="AADL 文件或目录路径")
    parser.add_argument("--max-files", type=int, default=0,
                        help="最多验证多少个文件（0=不限制）")
    args = parser.parse_args()

    files = collect_aadl_files(args.path)
    if args.max_files > 0:
        files = files[:args.max_files]

    print(f"找到 {len(files)} 个 .aadl 文件")
    print()

    all_results = []
    for i, fpath in enumerate(files, 1):
        print(f"[{i}/{len(files)}] {fpath} ... ", end="", flush=True)
        try:
            result = validate_file(fpath)
            all_results.append(result)

            if result.get("parse_failed"):
                print("解析失败")
            elif result.get("no_cases"):
                st = result["symbol_table"]
                print(f"跳过（{len(st.component_types)} 类型, {len(st.component_impls)} 实现, 无可测引用）")
            else:
                total = len(result["cases"])
                passed = sum(1 for c in result["cases"] if c["passed"])
                rate = passed / total * 100 if total > 0 else 0
                print(f"{passed}/{total} 通过 ({rate:.0f}%)")
        except Exception as e:
            print(f"异常: {e}")
            all_results.append({"parse_failed": True, "filepath": fpath})

    print_summary(all_results)


if __name__ == "__main__":
    main()
