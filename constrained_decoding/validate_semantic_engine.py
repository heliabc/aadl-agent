#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
语义引擎验证脚本
================
直接用生成时的语义引擎（AadlContextSensitiveEngine）来验证。
拿真实 AADL 文件，在每个语义关键点截取前缀文本，
调用 get_valid_values() 看返回的合法值是否包含真实值。

只统计单包内引用（不含 :: 的跨包引用）。

用法:
  python validate_semantic_engine.py path/to/osate-examples
  python validate_semantic_engine.py path/to/file.aadl
  python validate_semantic_engine.py path/to/dir --max-files 20
"""

import os
import re
import sys
import argparse
from typing import List, Tuple, Dict, Optional

from aadl_context_sensitive import AadlContextSensitiveEngine


# ============================================================
# 从 AADL 文件中提取语义关键点（只找位置，不做完整解析）
# ============================================================

COMP_CATEGORIES = [
    "system", "process", "thread", "subprogram", "data",
    "device", "processor", "memory", "bus", "abstract",
]


def find_semantic_points(filepath: str) -> List[dict]:
    """
    从 AADL 文件中找出所有语义关键点。
    返回：每个点的位置、类型、真实值、前缀文本。
    
    注意：构造前缀文本时会去掉注释行，
    因为生成时模型不会输出注释，语义引擎的输入本来就是干净的 AADL 代码。
    """
    with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()

    # 去掉 -- 行注释（生成时不会有注释，验证要和生成时输入一致）
    clean_lines = []
    for line in content.split("\n"):
        idx = line.find("--")
        if idx >= 0:
            before = line[:idx]
            # 简单判断引号是否闭合
            if before.count('"') % 2 == 0:
                line = before
        clean_lines.append(line.rstrip())
    clean_content = "\n".join(clean_lines)

    points = []
    lines = clean_content.split("\n")

    # 先收集当前包内定义的组件类型和实现，以及它们出现的行号
    # 用于判断：
    # 1. 分类器是不是跨包引用
    # 2. 类型定义是否出现在引用位置之前（生成时一定是先定义后引用）
    local_components = {}  # name_lower -> line_idx (0-based)
    local_impls = {}       # full_name_lower -> line_idx

    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped:
            continue
        # 组件类型：<category> <name>
        m = re.match(r'^(' + '|'.join(COMP_CATEGORIES) + r')\s+(\w+)\s*$',
                     stripped, re.IGNORECASE)
        if m:
            name = m.group(2).lower()
            if name not in local_components:
                local_components[name] = i
            continue
        # 组件实现：<category> implementation <base>.<impl>
        m = re.match(r'^(' + '|'.join(COMP_CATEGORIES) + r')\s+implementation\s+(\w+)\.(\w+)',
                     stripped, re.IGNORECASE)
        if m:
            base = m.group(2).lower()
            impl = m.group(3).lower()
            full = f"{base}.{impl}"
            if base not in local_components:
                local_components[base] = i
            if full not in local_impls:
                local_impls[full] = i
            continue

    def is_local_classifier(classifier: str, ref_line_idx: int = None) -> bool:
        """
        判断分类器是否在当前包内定义，且定义出现在引用位置之前。
        ref_line_idx 是引用所在的行号（0-based），如果为 None 只判断是否是本地的。
        """
        if "::" in classifier:
            return False
        cls_lower = classifier.lower()

        # 类型名
        if cls_lower in local_components:
            if ref_line_idx is not None and local_components[cls_lower] >= ref_line_idx:
                return False  # 定义在引用之后，生成时不会出现这种情况
            return True

        # 类型.实现 形式
        if cls_lower in local_impls:
            if ref_line_idx is not None and local_impls[cls_lower] >= ref_line_idx:
                return False
            return True

        # 去掉后缀看类型名
        if "." in cls_lower:
            base = cls_lower.rsplit(".", 1)[0]
            if base in local_components:
                if ref_line_idx is not None and local_components[base] >= ref_line_idx:
                    return False
                return True

        return False

    # 先找出所有组件实现块（subcomponents / connections 在里面）
    # 简单做法：逐行扫描，跟踪当前在哪个实现里

    current_impl = None  # (name, category)
    in_subcomponents = False
    in_connections = False
    brace_depth = 0  # 不跟踪大括号，用 end 来判断

    # 用更简单的方式：正则匹配子组件行和连接行，记录它们在文件中的位置

    # 1. 找所有子组件行
    # 模式：<name> : <category> <classifier> ;
    # 只在 subcomponents section 里匹配
    subcomp_pattern = re.compile(
        r'^\s*(\w+)\s*:\s*(' + '|'.join(COMP_CATEGORIES) + r')\s+([\w:.]+)\s*;',
        re.IGNORECASE
    )

    # 2. 找所有连接行
    # 模式：<name> : <type> <src> -> <dst> ;
    conn_pattern = re.compile(
        r'^\s*(\w*)\s*:\s*(?:port|feature\s+group|access|parameter|port\s+access|'
        r'event\s+data\s+port|event\s+port|data\s+port|bus\s+access|'
        r'data\s+access|subprogram\s+access)\s+(\S+)\s*->\s*(\S+)\s*;',
        re.IGNORECASE
    )

    # 先找出所有 section 的位置，确定哪些行在 subcomponents / connections 里
    impl_starts = []  # (line_idx, impl_name)
    impl_ends = []
    subcomp_section_starts = []
    subcomp_section_ends = []
    conn_section_starts = []
    conn_section_ends = []

    # 逐行扫描
    for i, line in enumerate(lines):
        stripped = line.strip()

        # 跳过注释行
        if stripped.startswith('--'):
            continue

        # 组件实现开始
        m = re.match(r'^\s*(' + '|'.join(COMP_CATEGORIES) + r')\s+implementation\s+(\w+)\.(\w+)',
                     line, re.IGNORECASE)
        if m:
            cat = m.group(1).lower()
            base = m.group(2)
            impl = m.group(3)
            full_name = f"{base}.{impl}"
            impl_starts.append((i, full_name, cat))
            continue

        # 组件实现结束：end <base>.<impl>;
        m = re.match(r'^\s*end\s+(\w+)\.(\w+)\s*;', line, re.IGNORECASE)
        if m and impl_starts:
            impl_ends.append(i)
            continue

        # subcomponents section
        if re.match(r'^\s*subcomponents\s*$', line, re.IGNORECASE):
            subcomp_section_starts.append(i)
            continue

        # connections section
        if re.match(r'^\s*connections\s*$', line, re.IGNORECASE):
            conn_section_starts.append(i)
            continue

    # 确定 section 范围（下一个 section 或 end 之前）
    # 简化：每个实现有自己的 section，用 impl 范围来划分
    # 这里先不做复杂嵌套，直接全局找——反正测试点只要前缀文本对就行

    # 直接找出所有子组件和连接
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith('--') or not stripped:
            continue

        # 子组件
        m = subcomp_pattern.match(line)
        if m:
            sub_name = m.group(1)
            category = m.group(2).lower()
            classifier = m.group(3)

            # 跳过跨包引用 和 定义在引用之后的（生成时一定先定义后引用）
            if not is_local_classifier(classifier, ref_line_idx=i):
                continue

            # 前缀文本：到这个子组件的 category 后面
            # 即 "  subname: category "
            prefix_end = line.find(classifier, line.find(':'))
            if prefix_end < 0:
                continue
            prefix_text = clean_content[:_line_char_to_pos(lines, i, prefix_end)]

            points.append({
                "type": "subcomp_classifier",
                "line": i + 1,
                "sub_name": sub_name,
                "category": category,
                "expected": classifier,
                "prefix_text": prefix_text,
                "location": f"第{i+1}行 {sub_name}",
            })
            continue

        # 连接
        m = conn_pattern.match(line)
        if m:
            conn_name = m.group(1) or "(anonymous)"
            source = m.group(2).rstrip(';')
            target = m.group(3).rstrip(';')

            # 连接源端
            if "::" not in source:
                # 前缀到连接类型关键字后面，源端前面
                # 找 "port " 或其他类型关键字后面的位置
                type_match = re.search(r':\s*(?:port|feature\s+group|access|parameter)\s+',
                                       line, re.IGNORECASE)
                if type_match:
                    src_start = type_match.end()
                    prefix_text = clean_content[:_line_char_to_pos(lines, i, src_start)]
                    points.append({
                        "type": "conn_source",
                        "line": i + 1,
                        "conn_name": conn_name,
                        "expected": source,
                        "prefix_text": prefix_text,
                        "location": f"第{i+1}行 {conn_name} 源端",
                    })

            # 连接目标端
            if "::" not in target:
                # 前缀到 -> 后面，目标端前面
                arrow_pos = line.find('->')
                if arrow_pos >= 0:
                    tgt_start = arrow_pos + 2
                    # 跳过 -> 后面的空格
                    while tgt_start < len(line) and line[tgt_start] == ' ':
                        tgt_start += 1
                    prefix_text = clean_content[:_line_char_to_pos(lines, i, tgt_start)]
                    points.append({
                        "type": "conn_target",
                        "line": i + 1,
                        "conn_name": conn_name,
                        "expected": target,
                        "prefix_text": prefix_text,
                        "location": f"第{i+1}行 {conn_name} 目标端",
                    })

    return points


def _line_char_to_pos(lines: List[str], line_idx: int, char_idx: int) -> int:
    """把行号+列号转换成文件中的绝对字符位置"""
    pos = 0
    for i in range(line_idx):
        pos += len(lines[i]) + 1  # +1 是换行符
    pos += char_idx
    return pos


# ============================================================
# 验证每个语义点
# ============================================================

def validate_point(point: dict) -> Tuple[bool, int, str]:
    """
    验证单个语义点。
    返回 (passed, valid_count, error_msg)
    """
    try:
        engine = AadlContextSensitiveEngine()
        valid_values = engine.get_valid_values(point["prefix_text"])

        if valid_values is None:
            return False, 0, "引擎未识别到语义位置"

        if not valid_values:
            return False, 0, "合法值列表为空"

        # AADL 大小写不敏感
        expected_lower = point["expected"].lower()
        valid_lower = [v.lower() for v in valid_values]

        passed = expected_lower in valid_lower
        return passed, len(valid_values), ""

    except Exception as e:
        return False, 0, f"引擎异常: {e}"


# ============================================================
# 主流程
# ============================================================

def validate_file(filepath: str) -> dict:
    """验证单个文件"""
    try:
        points = find_semantic_points(filepath)
    except Exception as e:
        return {"filepath": filepath, "extract_error": str(e), "points": []}

    if not points:
        return {"filepath": filepath, "points": [], "no_points": True}

    results = []
    for p in points:
        passed, count, error = validate_point(p)
        results.append({
            **p,
            "passed": passed,
            "valid_count": count,
            "error": error,
        })

    return {"filepath": filepath, "points": results}


def print_summary(all_results: List[dict]):
    """打印汇总"""
    total = 0
    passed = 0
    type_stats = {}
    no_points_files = 0
    extract_error_files = 0

    for r in all_results:
        if r.get("extract_error"):
            extract_error_files += 1
            continue
        if r.get("no_points"):
            no_points_files += 1
            continue

        for p in r["points"]:
            total += 1
            if p["passed"]:
                passed += 1

            t = p["type"]
            if t not in type_stats:
                type_stats[t] = {"total": 0, "passed": 0, "failed": []}
            type_stats[t]["total"] += 1
            if p["passed"]:
                type_stats[t]["passed"] += 1
            else:
                type_stats[t]["failed"].append({
                    "file": os.path.basename(r["filepath"]),
                    "location": p["location"],
                    "expected": p["expected"],
                    "valid_count": p["valid_count"],
                    "error": p["error"],
                })

    print()
    print("=" * 70)
    print("语义引擎验证结果")
    print("=" * 70)
    print(f"文件总数:             {len(all_results)}")
    print(f"提取失败的文件:       {extract_error_files}")
    print(f"无可验证点的文件:     {no_points_files}")
    print(f"成功验证的文件:       {len(all_results) - extract_error_files - no_points_files}")
    print()

    if total == 0:
        print("（没有找到可验证的语义点）")
        return

    print(f"总验证点数:           {total}")
    print(f"通过:                 {passed}")
    print(f"失败:                 {total - passed}")
    print(f"通过率:               {passed/total*100:.1f}%")
    print()

    type_names = {
        "subcomp_classifier": "子组件分类器",
        "conn_source": "连接源端",
        "conn_target": "连接目标端",
    }

    print("-" * 70)
    print(f"{'约束类型':<22} {'总数':>6} {'通过':>6} {'失败':>6} {'通过率':>8}")
    print("-" * 70)
    for t, stats in type_stats.items():
        name = type_names.get(t, t)
        rate = stats["passed"] / stats["total"] * 100 if stats["total"] > 0 else 0
        print(f"{name:<22} {stats['total']:>6} {stats['passed']:>6} "
              f"{stats['total']-stats['passed']:>6} {rate:>7.1f}%")
    print("-" * 70)

    # 失败详情（前 30 个）
    all_failed = []
    for stats in type_stats.values():
        all_failed.extend(stats["failed"])

    if all_failed:
        print()
        print(f"失败案例（共 {len(all_failed)} 个，显示前 30 个）：")
        print("-" * 70)
        for f in all_failed[:30]:
            err = f["error"] or f"合法值 {f['valid_count']} 个"
            print(f"  [{f['file']}] {f['location']}")
            print(f"    期望: {f['expected']}")
            print(f"    原因: {err}")
        print("-" * 70)


def collect_aadl_files(path: str) -> List[str]:
    if os.path.isfile(path):
        return [path]
    files = []
    for root, dirs, fnames in os.walk(path):
        for fn in fnames:
            if fn.endswith(".aadl"):
                files.append(os.path.join(root, fn))
    return sorted(files)


def main():
    parser = argparse.ArgumentParser(description="语义引擎验证（直接用生成时的引擎）")
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
        result = validate_file(fpath)
        all_results.append(result)

        if result.get("extract_error"):
            print(f"提取失败: {result['extract_error'][:50]}")
        elif result.get("no_points"):
            print("无可验证点")
        else:
            total = len(result["points"])
            ok = sum(1 for p in result["points"] if p["passed"])
            rate = ok / total * 100 if total > 0 else 0
            print(f"{ok}/{total} 通过 ({rate:.0f}%)")

    print_summary(all_results)


if __name__ == "__main__":
    main()
