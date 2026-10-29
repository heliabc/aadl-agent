#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
语义引擎验证脚本（完整版）
============================
直接用生成时的语义引擎来验证。
拿真实 AADL 文件，在每个语义关键点截取前缀文本，
调用 get_valid_values() 看返回的合法值是否包含真实值。

覆盖的语义规则：
  1. 子组件分类器 (subcomp_classifier)
  2. 端口数据类型 (port_data_type)
  3. 连接源端 (conn_source)
  4. 连接目标端 (conn_target)
  5. 实现 base_type (impl_base_type)
  6. Flow 端点 (flow_endpoint)
  7. 属性名 (property_name)

只统计单包内引用，且定义出现在引用之前（和生成时的顺序一致）。

用法:
  python validate_semantic_full.py path/to/osate-examples
  python validate_semantic_full.py path/to/file.aadl
  python validate_semantic_full.py path/to/dir --max-files 20
"""

import os
import re
import sys
import argparse
from typing import List, Tuple, Dict, Optional

from aadl_semantic_full import FullAadlSemanticEngine


COMP_CATEGORIES = [
    "system", "process", "thread", "subprogram", "data",
    "device", "processor", "memory", "bus", "abstract",
]


def _strip_comments(content: str) -> str:
    """去掉 -- 行注释，返回干净的文本"""
    clean_lines = []
    for line in content.split("\n"):
        idx = line.find("--")
        if idx >= 0:
            before = line[:idx]
            if before.count('"') % 2 == 0:
                line = before
        clean_lines.append(line.rstrip())
    return "\n".join(clean_lines)


def _line_char_to_pos(lines: List[str], line_idx: int, char_idx: int) -> int:
    """行号+列号 → 绝对字符位置"""
    pos = 0
    for i in range(line_idx):
        pos += len(lines[i]) + 1
    pos += char_idx
    return pos


def _trim_trailing_newlines(text: str) -> str:
    """去掉前缀末尾的空行（生成时不会输出空行后等待输入）"""
    while text.endswith("\n"):
        text = text[:-1]
    # 再加一个换行，因为生成时最后一行也是完整的
    return text + "\n"


def _is_subcomp_local(source_path: str, subcomponents: dict,
                       local_components: dict, local_impls: dict) -> bool:
    """
    判断连接端点引用的子组件是否是本地定义的。
    source_path 格式: "sub.port" 或 "port"
    """
    if "." not in source_path:
        return True  # 自身端口，肯定本地
    sub_name = source_path.split(".")[0].lower()
    sub = subcomponents.get(sub_name)
    if not sub:
        return False
    classifier = sub.classifier.lower()
    if "::" in classifier:
        return False
    if classifier in local_components or classifier in local_impls:
        return True
    if "." in classifier:
        base = classifier.rsplit(".", 1)[0]
        if base in local_components:
            return True
    return False


def find_semantic_points(filepath: str) -> List[dict]:
    """
    从 AADL 文件中找出所有语义关键点。
    返回每个点的类型、真实值、前缀文本。
    """
    with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()

    clean_content = _strip_comments(content)
    lines = clean_content.split("\n")
    points = []

    # 收集组件类型和实现的位置，用于判断是否先定义后引用
    local_components = {}  # name_lower -> line_idx
    local_impls = {}       # full_name_lower -> line_idx

    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped:
            continue

        m = re.match(
            r"^(" + "|".join(COMP_CATEGORIES) + r")\s+(\w+)\s*$",
            stripped, re.IGNORECASE)
        if m:
            name = m.group(2).lower()
            if name not in local_components:
                local_components[name] = i
            continue

        m = re.match(
            r"^(" + "|".join(COMP_CATEGORIES) + r")\s+implementation\s+(\w+)\.(\w+)",
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
        if "::" in classifier:
            return False
        cls_lower = classifier.lower()
        if cls_lower in local_components:
            if ref_line_idx is not None and local_components[cls_lower] >= ref_line_idx:
                return False
            return True
        if cls_lower in local_impls:
            if ref_line_idx is not None and local_impls[cls_lower] >= ref_line_idx:
                return False
            return True
        if "." in cls_lower:
            base = cls_lower.rsplit(".", 1)[0]
            if base in local_components:
                if ref_line_idx is not None and local_components[base] >= ref_line_idx:
                    return False
                return True
        return False

    # 各种正则
    subcomp_pat = re.compile(
        r"^\s*(\w+)\s*:\s*(" + "|".join(COMP_CATEGORIES) + r")\s+([\w:.]+)\s*;",
        re.IGNORECASE)

    conn_pat = re.compile(
        r"^\s*(\w*)\s*:\s*(?:port|feature\s+group|access|parameter|port\s+access|"
        r"event\s+data\s+port|event\s+port|data\s+port|bus\s+access|"
        r"data\s+access|subprogram\s+access)\s+(\S+)\s*->\s*(\S+)\s*;",
        re.IGNORECASE)

    port_pat = re.compile(
        r"^\s*(\w+)\s*:\s*(in\s+out|in|out)\s+(data\s+port|event\s+port|event\s+data\s+port)\s+([\w:.]+)\s*;",
        re.IGNORECASE)

    impl_pat = re.compile(
        r"^\s*(" + "|".join(COMP_CATEGORIES) + r")\s+implementation\s+(\w+)\.(\w+)",
        re.IGNORECASE)

    flow_pat = re.compile(
        r"^\s*(\w+)\s*:\s*flow\s+(source|sink|path)\s+(.+?)\s*;",
        re.IGNORECASE)

    property_pat = re.compile(
        r"^\s*(\w+(?:::\w+)*)\s*=>\s*.+;",
        re.IGNORECASE)

    # 逐行扫描
    in_features = False
    in_properties = False
    in_flows = False
    in_impl = False  # 当前是否在组件实现里（flow 只在类型里）
    prop_bracket_depth = 0  # properties 节内的括号深度（跟踪嵌套属性值）

    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped:
            continue

        # 进入组件实现
        if re.match(
            r"^(" + "|".join(COMP_CATEGORIES) + r")\s+implementation\s+\w+\.\w+",
            stripped, re.IGNORECASE):
            in_impl = True
            in_features = False
            in_properties = False
            in_flows = False
            continue

        # section 切换
        if re.match(r"^features\s*$", stripped, re.IGNORECASE):
            in_features = True
            in_properties = False
            in_flows = False
            continue
        if re.match(r"^properties\s*$", stripped, re.IGNORECASE):
            in_properties = True
            in_features = False
            in_flows = False
            prop_bracket_depth = 0
            continue
        if re.match(r"^flows\s*$", stripped, re.IGNORECASE):
            in_flows = True
            in_features = False
            in_properties = False
            continue
        if re.match(r"^end\s+\w+(\.\w+)?\s*;?$", stripped, re.IGNORECASE):
            in_features = False
            in_properties = False
            in_flows = False
            in_impl = False
            prop_bracket_depth = 0
            continue
        if re.match(r"^(subconnections|subcomponents|connections|modes|calls|flows|features)\s*$", stripped, re.IGNORECASE):
            in_features = False
            in_properties = False
            in_flows = False
            prop_bracket_depth = 0
            continue

        # === 1. 子组件分类器 ===
        m = subcomp_pat.match(line)
        if m:
            sub_name = m.group(1)
            category = m.group(2).lower()
            classifier = m.group(3)

            if is_local_classifier(classifier, ref_line_idx=i):
                prefix_end = line.find(classifier, line.find(":"))
                if prefix_end >= 0:
                    prefix_text = clean_content[:_line_char_to_pos(lines, i, prefix_end)]
                    points.append({
                        "type": "subcomp_classifier",
                        "line": i + 1,
                        "expected": classifier,
                        "prefix_text": prefix_text,
                        "location": f"子组件 {sub_name} 分类器",
                    })
            continue

        # === 2. 连接源端 ===
        m = conn_pat.match(line)
        if m:
            conn_name = m.group(1) or "(anonymous)"
            source = m.group(2).rstrip(";")
            target = m.group(3).rstrip(";")

            # 源端
            if "::" not in source:
                type_match = re.search(
                    r":\s*(?:port|feature\s+group|access|parameter)\s+",
                    line, re.IGNORECASE)
                if type_match:
                    src_start = type_match.end()
                    prefix_text = clean_content[:_line_char_to_pos(lines, i, src_start)]
                    points.append({
                        "type": "conn_source",
                        "line": i + 1,
                        "expected": source,
                        "prefix_text": prefix_text,
                        "location": f"连接 {conn_name} 源端",
                    })

            # 目标端
            if "::" not in target:
                arrow_pos = line.find("->")
                if arrow_pos >= 0:
                    tgt_start = arrow_pos + 2
                    while tgt_start < len(line) and line[tgt_start] == " ":
                        tgt_start += 1
                    prefix_text = clean_content[:_line_char_to_pos(lines, i, tgt_start)]
                    points.append({
                        "type": "conn_target",
                        "line": i + 1,
                        "expected": target,
                        "prefix_text": prefix_text,
                        "location": f"连接 {conn_name} 目标端",
                    })
            continue

        # === 3. 端口数据类型 ===
        if in_features:
            m = port_pat.match(line)
            if m:
                port_name = m.group(1)
                data_type = m.group(4)

                if is_local_classifier(data_type, ref_line_idx=i):
                    # 前缀到端口类型关键字后面
                    # "port_name: direction data port "
                    # 找到 data_type 在 line 中的位置
                    dt_start = line.find(data_type)
                    if dt_start >= 0:
                        prefix_text = clean_content[:_line_char_to_pos(lines, i, dt_start)]
                        points.append({
                            "type": "port_data_type",
                            "line": i + 1,
                            "expected": data_type,
                            "prefix_text": prefix_text,
                            "location": f"端口 {port_name} 数据类型",
                        })
                continue

        # === 4. 实现 base_type ===
        m = impl_pat.match(line)
        if m:
            category = m.group(1).lower()
            base_name = m.group(2)
            impl_name = m.group(3)

            if is_local_classifier(base_name, ref_line_idx=i):
                # 前缀到 "implementation " 后面
                impl_kw = "implementation"
                impl_pos = line.lower().find(impl_kw.lower())
                if impl_pos >= 0:
                    base_start = impl_pos + len(impl_kw) + 1  # +1 for space
                    # 跳过空格
                    while base_start < len(line) and line[base_start] == " ":
                        base_start += 1
                    prefix_text = clean_content[:_line_char_to_pos(lines, i, base_start)]
                    points.append({
                        "type": "impl_base_type",
                        "line": i + 1,
                        "expected": base_name,
                        "category": category,
                        "prefix_text": prefix_text,
                        "location": f"实现 {base_name}.{impl_name} base_type",
                    })
            continue

        # === 5. Flow 端点 ===
        # flow 只在组件类型里定义，不在实现里
        if in_flows and not in_impl:
            m = flow_pat.match(line)
            if m:
                flow_name = m.group(1)
                flow_kind = m.group(2).lower()  # source / sink / path
                flow_body = m.group(3).strip()

                # flow source: 只有一个端点
                if flow_kind == "source":
                    endpoint = flow_body.strip()
                    if "::" not in endpoint and "." not in endpoint:
                        # 前缀到 "source " 后面
                        src_kw = "flow source "
                        src_pos = line.lower().find(src_kw.lower())
                        if src_pos >= 0:
                            ep_start = src_pos + len(src_kw)
                            while ep_start < len(line) and line[ep_start] == " ":
                                ep_start += 1
                            prefix_text = clean_content[:_line_char_to_pos(lines, i, ep_start)]
                            points.append({
                                "type": "flow_endpoint",
                                "line": i + 1,
                                "expected": endpoint,
                                "flow_kind": "source",
                                "prefix_text": prefix_text,
                                "location": f"Flow {flow_name} 端点",
                            })

                # flow sink: 只有一个端点
                elif flow_kind == "sink":
                    endpoint = flow_body.strip()
                    if "::" not in endpoint and "." not in endpoint:
                        sink_kw = "flow sink "
                        sink_pos = line.lower().find(sink_kw.lower())
                        if sink_pos >= 0:
                            ep_start = sink_pos + len(sink_kw)
                            while ep_start < len(line) and line[ep_start] == " ":
                                ep_start += 1
                            prefix_text = clean_content[:_line_char_to_pos(lines, i, ep_start)]
                            points.append({
                                "type": "flow_endpoint",
                                "line": i + 1,
                                "expected": endpoint,
                                "flow_kind": "sink",
                                "prefix_text": prefix_text,
                                "location": f"Flow {flow_name} 端点",
                            })

                # flow path: 两个端点 src -> dst
                elif flow_kind == "path" and "->" in flow_body:
                    parts = flow_body.split("->")
                    if len(parts) >= 2:
                        src = parts[0].strip()
                        dst = parts[1].strip()

                        # 源端
                        if "::" not in src and "." not in src:
                            path_kw = "flow path "
                            path_pos = line.lower().find(path_kw.lower())
                            if path_pos >= 0:
                                ep_start = path_pos + len(path_kw)
                                while ep_start < len(line) and line[ep_start] == " ":
                                    ep_start += 1
                                prefix_text = clean_content[:_line_char_to_pos(lines, i, ep_start)]
                                points.append({
                                    "type": "flow_endpoint",
                                    "line": i + 1,
                                    "expected": src,
                                    "flow_kind": "path_source",
                                    "prefix_text": prefix_text,
                                    "location": f"Flow {flow_name} 源端",
                                })

                        # 目标端
                        if "::" not in dst and "." not in dst:
                            arrow_pos = line.find("->")
                            if arrow_pos >= 0:
                                dst_start = arrow_pos + 2
                                while dst_start < len(line) and line[dst_start] == " ":
                                    dst_start += 1
                                prefix_text = clean_content[:_line_char_to_pos(lines, i, dst_start)]
                                points.append({
                                    "type": "flow_endpoint",
                                    "line": i + 1,
                                    "expected": dst,
                                    "flow_kind": "path_target",
                                    "prefix_text": prefix_text,
                                    "location": f"Flow {flow_name} 目标端",
                                })
            continue

        # === 6. 属性名 ===
        # 只验证顶层属性（不在属性值的嵌套记录/数组里）
        if in_properties:
            # 先更新括号深度（看这一行 => 之后的内容）
            line_depth_change = 0
            if "=>" in stripped:
                value_part = stripped.split("=>", 1)[1]
                for ch in value_part:
                    if ch in "([":
                        line_depth_change += 1
                    elif ch in ")]":
                        line_depth_change -= 1

            # 属性名在当前深度的"之前"
            # 如果进入这行之前深度是 0，那这行的属性名就是顶层的
            is_top_level = (prop_bracket_depth == 0)

            # 处理完这行后更新深度
            prop_bracket_depth += line_depth_change
            if prop_bracket_depth < 0:
                prop_bracket_depth = 0

            m = property_pat.match(line)
            if m and is_top_level:
                prop_name = m.group(1)
                # 只测不带 :: 的属性名（当前包内的）
                if "::" not in prop_name:
                    # 前缀到行首（属性名位置）
                    prefix_text = clean_content[:_line_char_to_pos(lines, i, len(line) - len(line.lstrip()))]
                    points.append({
                        "type": "property_name",
                        "line": i + 1,
                        "expected": prop_name,
                        "prefix_text": prefix_text,
                        "location": f"属性 {prop_name}",
                    })
            continue

    return points


# ============================================================
# 验证
# ============================================================

def validate_point(point: dict) -> Tuple[bool, int, str]:
    """验证单个语义点"""
    try:
        engine = FullAadlSemanticEngine()
        valid_values = engine.get_valid_values(point["prefix_text"])

        if valid_values is None:
            return False, 0, "引擎未识别到语义位置"
        if not valid_values:
            return False, 0, "合法值列表为空"

        expected_lower = point["expected"].lower()
        valid_lower = [v.lower() for v in valid_values]

        passed = expected_lower in valid_lower
        return passed, len(valid_values), ""

    except Exception as e:
        return False, 0, f"引擎异常: {e}"


def validate_file(filepath: str) -> dict:
    try:
        points = find_semantic_points(filepath)
    except Exception as e:
        return {"filepath": filepath, "extract_error": str(e), "points": []}

    if not points:
        return {"filepath": filepath, "points": [], "no_points": True}

    results = []
    for p in points:
        passed, count, error = validate_point(p)
        results.append({**p, "passed": passed, "valid_count": count, "error": error})

    return {"filepath": filepath, "points": results}


# ============================================================
# 输出
# ============================================================

TYPE_NAMES = {
    "subcomp_classifier": "子组件分类器",
    "port_data_type": "端口数据类型",
    "conn_source": "连接源端",
    "conn_target": "连接目标端",
    "impl_base_type": "实现 base_type",
    "flow_endpoint": "Flow 端点",
    "property_name": "属性名",
}


def print_summary(all_results: List[dict]):
    total = 0
    passed = 0
    type_stats = {}
    no_points = 0
    extract_error = 0

    for r in all_results:
        if r.get("extract_error"):
            extract_error += 1
            continue
        if r.get("no_points"):
            no_points += 1
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
    print("语义引擎验证结果（完整版）")
    print("=" * 70)
    print(f"文件总数:             {len(all_results)}")
    print(f"提取失败:             {extract_error}")
    print(f"无可验证点:           {no_points}")
    print(f"成功验证的文件:       {len(all_results) - extract_error - no_points}")
    print()

    if total == 0:
        print("（没有找到可验证的语义点）")
        return

    print(f"总验证点数:           {total}")
    print(f"通过:                 {passed}")
    print(f"失败:                 {total - passed}")
    print(f"通过率:               {passed/total*100:.1f}%")
    print()

    print("-" * 70)
    print(f"{'约束类型':<22} {'总数':>6} {'通过':>6} {'失败':>6} {'通过率':>8}")
    print("-" * 70)
    for t in TYPE_NAMES:
        if t not in type_stats:
            continue
        stats = type_stats[t]
        name = TYPE_NAMES[t]
        rate = stats["passed"] / stats["total"] * 100 if stats["total"] > 0 else 0
        print(f"{name:<22} {stats['total']:>6} {stats['passed']:>6} "
              f"{stats['total']-stats['passed']:>6} {rate:>7.1f}%")
    print("-" * 70)

    all_failed = []
    for t in TYPE_NAMES:
        if t in type_stats:
            all_failed.extend(type_stats[t]["failed"])

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
    ap = argparse.ArgumentParser(description="语义引擎完整验证")
    ap.add_argument("path", help="AADL 文件或目录")
    ap.add_argument("--max-files", type=int, default=0)
    args = ap.parse_args()

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
