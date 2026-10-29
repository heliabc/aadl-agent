#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
基于 Lark 的 AADL 解析器
=========================
用 aadl_core.lark 文法解析 AADL 文本，从 AST 构建符号表。
比正则提取更准确，和 CFG 约束用同一套文法，保证一致性。

用法:
  from aadl_lark_parser import parse_aadl
  st = parse_aadl(aadl_text)
  print(st.summary())
"""

import os
import re
from typing import List, Dict, Optional
from lark import Lark, Tree, Token

from symbol_table import (
    SymbolTable, ComponentTypeInfo, ComponentImplInfo,
    PortInfo, SubcomponentInfo,
)


DEFAULT_GRAMMAR_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "grammars", "aadl_core.lark"
)

# 组件类别列表（和文法里的 comp_category 对应）
COMP_CATEGORIES = {
    "system", "process", "thread", "subprogram", "data",
    "device", "processor", "memory", "bus", "abstract",
}


def _load_grammar(grammar_path: str = None) -> str:
    path = grammar_path or DEFAULT_GRAMMAR_PATH
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def _get_parser(grammar_path: str = None) -> Lark:
    grammar_text = _load_grammar(grammar_path)
    return Lark(grammar_text, start="package_spec", parser="earley",
                propagate_positions=True)


def _child(tree: Tree, name: str) -> Optional[Tree]:
    """获取第一个指定名字的子 Tree 节点"""
    for child in tree.children:
        if isinstance(child, Tree) and child.data == name:
            return child
    return None


def _children(tree: Tree, name: str) -> List[Tree]:
    """获取所有指定名字的子 Tree 节点"""
    return [c for c in tree.children if isinstance(c, Tree) and c.data == name]


def _first_ident(tree: Tree) -> Optional[str]:
    """从树中找到第一个 IDENT token"""
    if tree is None:
        return None
    for child in tree.children:
        if isinstance(child, Token) and child.type == "IDENT":
            return child.value
        if isinstance(child, Tree):
            val = _first_ident(child)
            if val:
                return val
    return None


def _node_text(node, full_text: str) -> str:
    """从原文本中截取节点对应的文本"""
    if node is None:
        return ""
    if hasattr(node, 'meta') and node.meta is not None and hasattr(node.meta, 'start_pos'):
        start = node.meta.start_pos
        end = node.meta.end_pos
        return full_text[start:end]
    return ""


def _first_token_value(tree: Tree, token_type: str) -> Optional[str]:
    """找第一个指定类型的 token"""
    if tree is None:
        return None
    for child in tree.children:
        if isinstance(child, Token) and child.type == token_type:
            return child.value
        if isinstance(child, Tree):
            val = _first_token_value(child, token_type)
            if val:
                return val
    return None


def _get_comp_category(ctype_node: Tree, full_text: str) -> str:
    """获取 component_type 或 component_impl 的类别关键字。
    comp_category 节点在 AST 中没有子 token，所以从原文本截取。"""
    cat_node = _child(ctype_node, "comp_category")
    if cat_node is None:
        return ""
    cat_text = _node_text(cat_node, full_text).strip()
    if cat_text in COMP_CATEGORIES:
        return cat_text
    return ""


def _parse_classifier_ref(node: Tree, full_text: str) -> str:
    """解析 classifier_ref 节点"""
    if node is None:
        return ""
    return _node_text(node, full_text).strip()


def _parse_port(feature: Tree, full_text: str) -> Optional[PortInfo]:
    """解析一个 feature_decl，如果是端口就返回 PortInfo"""
    name = _first_ident(_child(feature, "feature_name"))
    if not name:
        return None

    kind_node = _child(feature, "feature_kind")
    if not kind_node:
        return None

    port_spec = _child(kind_node, "port_spec")
    if not port_spec:
        return None  # 不是端口

    port_text = _node_text(port_spec, full_text).strip()

    # 方向
    direction = ""
    port_dir = _child(port_spec, "port_dir")
    if port_dir:
        direction = _node_text(port_dir, full_text).strip()

    # 端口类型
    kind = ""
    if "event data port" in port_text:
        kind = "event data port"
    elif "data port" in port_text:
        kind = "data port"
    elif "event port" in port_text:
        kind = "event port"

    # 数据类型
    data_type = None
    classifier_ref = _child(port_spec, "classifier_ref")
    if classifier_ref:
        data_type = _parse_classifier_ref(classifier_ref, full_text)

    return PortInfo(
        name=name,
        direction=direction,
        kind=kind,
        data_type=data_type,
    )


def _parse_component_type(decl: Tree, full_text: str) -> Optional[ComponentTypeInfo]:
    """解析 component_type 节点"""
    category = _get_comp_category(decl, full_text)
    name = _first_ident(_child(decl, "comp_name"))
    if not name or not category:
        return None

    ctype = ComponentTypeInfo(name=name, category=category, has_impl=True)

    # 解析 features
    type_sections = _child(decl, "type_sections")
    if type_sections:
        features_section = _child(type_sections, "features_section")
        if features_section:
            feature_list = _child(features_section, "feature_list")
            if feature_list:
                for feature in _children(feature_list, "feature_decl"):
                    port = _parse_port(feature, full_text)
                    if port:
                        ctype.ports[port.name] = port

    return ctype


def _parse_conn_end(conn_end: Tree, full_text: str) -> str:
    """解析 conn_end 节点，返回完整路径"""
    segments = _children(conn_end, "conn_segment")
    names = []
    for seg in segments:
        ident = _first_ident(seg)
        if ident:
            names.append(ident)
    return ".".join(names)


def _parse_component_impl(decl: Tree, full_text: str) -> Optional[ComponentImplInfo]:
    """解析 component_impl 节点"""
    category = _get_comp_category(decl, full_text)
    comp_name = _first_ident(_child(decl, "comp_name"))
    impl_name_id = _first_ident(_child(decl, "impl_name"))
    if not comp_name or not impl_name_id or not category:
        return None

    full_name = f"{comp_name}.{impl_name_id}"
    cat_full = f"{category} implementation"

    impl = ComponentImplInfo(
        name=full_name,
        base_name=comp_name,
        category=cat_full,
    )

    impl_sections = _child(decl, "impl_sections")
    if impl_sections:
        # subcomponents
        sub_section = _child(impl_sections, "subcomponents_section")
        if sub_section:
            sub_list = _child(sub_section, "subcomp_list")
            if sub_list:
                for sub_decl in _children(sub_list, "subcomp_decl"):
                    sub_name = _first_ident(_child(sub_decl, "subcomp_name"))
                    sub_cat = _get_comp_category(sub_decl, full_text)
                    classifier_ref = _child(sub_decl, "classifier_ref")
                    classifier = _parse_classifier_ref(classifier_ref, full_text) if classifier_ref else ""

                    if sub_name and sub_cat and classifier:
                        impl.subcomponents[sub_name] = SubcomponentInfo(
                            name=sub_name,
                            category=sub_cat,
                            classifier=classifier,
                        )

        # connections
        conn_section = _child(impl_sections, "connections_section")
        if conn_section:
            conn_list = _child(conn_section, "conn_list")
            if conn_list:
                for conn_decl in _children(conn_list, "conn_decl"):
                    conn_name_node = _child(conn_decl, "conn_name")
                    conn_name = _first_ident(conn_name_node) if conn_name_node else ""

                    conn_ends = _children(conn_decl, "conn_end")
                    source = ""
                    target = ""
                    if len(conn_ends) >= 1:
                        source = _parse_conn_end(conn_ends[0], full_text)
                    if len(conn_ends) >= 2:
                        target = _parse_conn_end(conn_ends[1], full_text)

                    if source and target:
                        impl.connections.append({
                            "name": conn_name,
                            "source": source,
                            "target": target,
                        })

    return impl


def _preprocess_aadl(text: str) -> str:
    """
    AADL 文本预处理，把核心文法不支持的语法清理掉，
    让 Lark parser 能解析尽可能多的真实文件。

    清理项：
    1. -- 行注释
    2. annex {** ... **}; 整块
    3. package Foo::Bar -> package Bar（只取最后一段包名）
    4. component extends ... -> 去掉 extends 子句
    5. in modes (...) 子句（子组件里的）
    """
    # 1. 去掉 -- 行注释
    lines = []
    for line in text.split("\n"):
        # 找到不在引号里的 --
        idx = line.find("--")
        if idx >= 0:
            before = line[:idx]
            quote_count = before.count('"')
            if quote_count % 2 == 0:
                line = line[:idx]
        # 去掉行尾空白（tab / 空格）
        line = line.rstrip()
        lines.append(line)
    text = "\n".join(lines)

    # 2. 去掉 annex 块：从 annex 开始到 }; 结束（包含嵌套的 {** **}）
    # 简化处理：匹配 annex ... {** ... **};
    # 用非贪婪 + 允许嵌套层级（最多 5 层）
    pattern = r'annex\s+\w+[\s\S]*?\{\*\*[\s\S]*?\*\*\}\s*;'
    text = re.sub(pattern, '', text)

    # 再清一遍可能残留的 annex 单行
    text = re.sub(r'^\s*annex\s+.*$', '', text, flags=re.MULTILINE)

    # 3. package Foo::Bar -> package Bar
    def _fix_package_name(m):
        full = m.group(1)
        # 取最后一段
        short = full.split("::")[-1]
        return f"package {short}"

    text = re.sub(r'package\s+([\w:]+)', _fix_package_name, text)

    # 4. 去掉 extends 子句（在组件类型/实现声明行上）
    # 形式：system Foo extends Base ...
    # 匹配 "extends" 到行尾或到换行前的内容
    text = re.sub(r'\bextends\s+[^\n;]+', '', text)

    # 5. 去掉子组件里的 in modes (...) 子句
    text = re.sub(r'\bin\s+modes\s*\([^)]*\)', '', text)

    # 6. 连接类型关键字（port / feature group access / parameter 等）
    # 形式：name: port src -> dst;
    # 简化处理：把 " port "、" feature group " 等连接类型关键字去掉
    # 只在连接行里处理（包含 -> 的行）
    lines = []
    for line in text.split("\n"):
        if "->" in line and ":" in line:
            # 去掉连接类型关键字
            line = re.sub(r':\s+(port|feature group|access|parameter|port access|event data port|event port|data port|bus access|data access|subprogram access)\s+', ': ', line)
        lines.append(line)
    text = "\n".join(lines)

    # 7. 给 -> 前后补空格（有些文件里写的是 "->c1" 这种）
    text = re.sub(r'(\S)->', r'\1 ->', text)
    text = re.sub(r'->(\S)', r'-> \1', text)

    # 8. 去掉多余空行
    # 开头的空行全部去掉（parser 要求第一个 token 就是 package）
    text = text.lstrip("\n")
    # 中间连续多个空行压缩成一个
    text = re.sub(r'\n\s*\n\s*\n+', '\n\n', text)

    return text


def parse_aadl(text: str, grammar_path: str = None) -> Optional[SymbolTable]:
    """
    用 Lark 解析 AADL 文本，构建符号表。

    Args:
        text: AADL 代码文本
        grammar_path: 文法文件路径，None 用默认

    Returns:
        SymbolTable 实例，解析失败返回 None
    """
    # 预处理：清理核心文法不支持的语法
    text = _preprocess_aadl(text)

    try:
        parser = _get_parser(grammar_path)
        # 补末尾换行（真实 AADL 文件不一定有末尾换行，但文法要求）
        if not text.endswith("\n"):
            text = text + "\n"
        tree = parser.parse(text)
    except Exception:
        return None

    st = SymbolTable()

    # 包名
    pkg_name_node = _child(tree, "package_name")
    if pkg_name_node:
        st.package_name = _first_ident(pkg_name_node) or ""

    # with 子句
    with_list = _child(tree, "with_list")
    if with_list:
        for with_clause in _children(with_list, "with_clause"):
            pkg = _first_ident(_child(with_clause, "package_name"))
            if pkg:
                st.with_packages.append(pkg)
            for rest in _children(with_clause, "with_rest"):
                rest_pkg = _first_ident(_child(rest, "package_name"))
                if rest_pkg:
                    st.with_packages.append(rest_pkg)

    # public 声明
    public_decls = _child(tree, "public_decls")
    if public_decls:
        for decl in _children(public_decls, "public_decl"):
            ctype = _child(decl, "component_type")
            if ctype:
                info = _parse_component_type(ctype, text)
                if info:
                    st.component_types[info.name] = info
                continue
            cimpl = _child(decl, "component_impl")
            if cimpl:
                info = _parse_component_impl(cimpl, text)
                if info:
                    st.component_impls[info.name] = info
                continue

    # private 声明
    private_section = _child(tree, "private_section")
    if private_section:
        private_decls = _child(private_section, "private_decls")
        if private_decls:
            for decl in _children(private_decls, "private_decl"):
                ctype = _child(decl, "component_type")
                if ctype:
                    info = _parse_component_type(ctype, text)
                    if info:
                        st.component_types[info.name] = info
                    continue
                cimpl = _child(decl, "component_impl")
                if cimpl:
                    info = _parse_component_impl(cimpl, text)
                    if info:
                        st.component_impls[info.name] = info
                    continue

    # 更新 has_impl
    for type_name in list(st.component_types.keys()):
        impl_name = f"{type_name}.impl"
        if impl_name not in st.component_impls:
            st.component_types[type_name].has_impl = False

    return st


if __name__ == "__main__":
    test_code = """package Test
public
  device Sensor
    features
      data_out: out data port Base_Types::Float;
    end Sensor;

  thread Controller
    features
      sensor_in: in data port Base_Types::Float;
      control_out: out data port Base_Types::Float;
    end Controller;

  system FlightControl
    features
      input: in data port Base_Types::Float;
      output: out data port Base_Types::Float;
    end FlightControl;

  system implementation FlightControl.impl
    subcomponents
      sensor: device Sensor;
      controller: thread Controller.impl;
    connections
      c1: sensor.data_out -> controller.sensor_in;
      c2: controller.control_out -> output;
    end FlightControl.impl;
end Test;
"""
    st = parse_aadl(test_code)
    if st:
        print(st.summary())
        print("\n--- 连接源端候选 (FlightControl.impl) ---")
        for s in st.get_connection_sources("FlightControl.impl"):
            print(f"  <- {s}")
        print("\n--- 连接目标端候选 (FlightControl.impl) ---")
        for t in st.get_connection_targets("FlightControl.impl"):
            print(f"  -> {t}")
    else:
        print("解析失败")
