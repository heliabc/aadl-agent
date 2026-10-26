#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
三阶段渐进式 JSON 架构生成器
=============================
分阶段生成 AADL 架构，每一步的 Schema 都基于前一步的结果动态构建，
把引用完整性、包含规则等语义约束编码进 JSON Schema 的枚举里，
实现"生成时即语义正确"。

阶段划分：
  阶段1：包 + 组件类型 + 实现骨架（类型层定义）
  阶段2：所有实现的 subcomponents（分类器引用约束）
  阶段3：所有实现的 connections + bindings + flows（端口引用、绑定约束）

用法:
  python phased_generator.py --model ~/models/qwen3-14b \
      --requirement "设计一个飞行控制系统" \
      --output output/arch.json
"""

import os
import sys
import json
import time
import copy
from typing import List, Dict, Optional

from architecture_schema import (
    PREDEFINED_DATA_TYPES,
    is_predefined_type,
)

# 包含规则从 validator 导入
from json_validator import CONTAINMENT_RULES


# ============================================================
# 动态 Schema 构建工具
# ============================================================

def make_string_enum(values: List[str], description: str = "") -> dict:
    """构建一个 string enum 的 JSON Schema"""
    schema = {"type": "string", "enum": sorted(values)}
    if description:
        schema["description"] = description
    return schema


def make_array_of(item_schema: dict, description: str = "") -> dict:
    """构建一个数组 schema"""
    schema = {"type": "array", "items": item_schema}
    if description:
        schema["description"] = description
    return schema


# ============================================================
# 阶段1：包 + 组件类型 + 实现骨架
# ============================================================

def _component_type_props_skeleton() -> dict:
    """组件的通用属性（type/impl 共用的基础字段）"""
    return {
        "name": {
            "type": "string",
            "description": "组件名。类型用大驼峰（如 NavigationThread），实现用'类型名.i'",
            "pattern": r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)?$",
        },
        "category": {
            "type": "string",
            "description": "组件类别（system/process/thread/device/bus/data 等）",
            "enum": [
                "system", "process", "thread", "thread group",
                "subprogram", "subprogram group",
                "data", "device", "processor", "virtual processor",
                "memory", "bus", "virtual bus", "abstract",
            ],
        },
        "extends": {
            "type": ["string", "null"],
            "description": "继承的父类型/父实现名",
            "default": None,
        },
        "properties": {
            "type": "array",
            "description": "属性列表",
            "default": [],
            "items": {
                "type": "object",
                "required": ["name", "value"],
                "additionalProperties": False,
                "properties": {
                    "name": {"type": "string"},
                    "value": {"type": "string"},
                    "unit": {"type": ["string", "null"], "default": None},
                },
            },
        },
    }


def _component_features_schema() -> dict:
    """类型组件的特征（ports/accesses/flows 等）"""
    return {
        "ports": {
            "type": "array",
            "description": "端口列表（组件的对外接口）",
            "default": [],
            "items": {
                "type": "object",
                "required": ["name", "direction", "kind"],
                "additionalProperties": False,
                "properties": {
                    "name": {"type": "string", "pattern": r"^[A-Za-z_][A-Za-z0-9_]*$"},
                    "direction": {"type": "string", "enum": ["in", "out", "in out"]},
                    "kind": {"type": "string", "enum": ["data port", "event port", "event data port"]},
                    "data_type": {"type": ["string", "null"], "default": None},
                    "data_type_source": {
                        "type": ["string", "null"],
                        "enum": ["predefined", "custom", "external", None],
                        "default": None,
                    },
                    "properties": {
                        "type": "array",
                        "default": [],
                        "items": {
                            "type": "object",
                            "required": ["name", "value"],
                            "additionalProperties": False,
                            "properties": {
                                "name": {"type": "string"},
                                "value": {"type": "string"},
                                "unit": {"type": ["string", "null"], "default": None},
                            },
                        },
                    },
                },
            },
        },
        "accesses": {
            "type": "array",
            "description": "Access 特征列表（bus access 等）",
            "default": [],
            "items": {
                "type": "object",
                "required": ["name", "direction", "kind", "classifier"],
                "additionalProperties": False,
                "properties": {
                    "name": {"type": "string", "pattern": r"^[A-Za-z_][A-Za-z0-9_]*$"},
                    "direction": {"type": "string", "enum": ["provides", "requires"]},
                    "kind": {
                        "type": "string",
                        "enum": ["bus access", "data access", "subprogram access", "subprogram group access"],
                    },
                    "classifier": {"type": "string"},
                    "properties": {
                        "type": "array",
                        "default": [],
                        "items": {
                            "type": "object",
                            "required": ["name", "value"],
                            "additionalProperties": False,
                            "properties": {
                                "name": {"type": "string"},
                                "value": {"type": "string"},
                                "unit": {"type": ["string", "null"], "default": None},
                            },
                        },
                    },
                },
            },
        },
        "flows": {
            "type": "array",
            "description": "类型上的流定义（flow path/source/sink）",
            "default": [],
            "items": {
                "type": "object",
                "required": ["name", "kind"],
                "additionalProperties": False,
                "properties": {
                    "name": {"type": "string", "pattern": r"^[A-Za-z_][A-Za-z0-9_]*$"},
                    "kind": {"type": "string", "enum": ["flow path", "flow source", "flow sink"]},
                    "source": {"type": ["string", "null"], "default": None},
                    "target": {"type": ["string", "null"], "default": None},
                    "properties": {
                        "type": "array",
                        "default": [],
                        "items": {
                            "type": "object",
                            "required": ["name", "value"],
                            "additionalProperties": False,
                            "properties": {
                                "name": {"type": "string"},
                                "value": {"type": "string"},
                                "unit": {"type": ["string", "null"], "default": None},
                            },
                        },
                    },
                },
            },
        },
    }


def build_phase1_schema() -> dict:
    """
    阶段1 Schema：生成包定义 + 所有组件类型 + 实现骨架。

    type_components 和 impl_components 是两个独立数组，
    从结构上区分类型和实现，避免模型混淆。

    实现骨架只包含 name/category/base_type/extends，
    不包含 subcomponents/connections/bindings/flows（留到后面阶段）。
    """
    # 类型组件 schema：完整特征（ports/accesses/flows + 基础字段）
    type_props = _component_type_props_skeleton()
    type_props.update(_component_features_schema())

    type_item_schema = {
        "type": "object",
        "required": ["name", "category"],
        "additionalProperties": False,
        "properties": type_props,
    }

    # 实现组件 schema：基础字段 + base_type + 空内部结构
    impl_props = _component_type_props_skeleton()
    impl_props["base_type"] = {
        "type": "string",
        "description": "对应的组件类型名（必须在 type_components 中定义）",
    }
    impl_props["subcomponents"] = {
        "type": "array",
        "description": "【阶段2填充】子组件列表（阶段1留空）",
        "default": [],
        "items": {"type": "object"},
    }
    impl_props["connections"] = {
        "type": "array",
        "description": "【阶段3填充】连接列表（阶段1留空）",
        "default": [],
        "items": {"type": "object"},
    }
    impl_props["bindings"] = {
        "type": "array",
        "description": "【阶段3填充】绑定列表（阶段1留空）",
        "default": [],
        "items": {"type": "object"},
    }
    impl_props["calls"] = {
        "type": "array",
        "description": "子程序调用序列（阶段1留空）",
        "default": [],
        "items": {"type": "object"},
    }

    impl_item_schema = {
        "type": "object",
        "required": ["name", "category", "base_type"],
        "additionalProperties": False,
        "properties": impl_props,
    }

    return {
        "type": "object",
        "required": ["package_name", "with_packages", "type_components", "impl_components"],
        "additionalProperties": False,
        "properties": {
            "package_name": {
                "type": "string",
                "description": "AADL 包名（支持嵌套，如 FlightControl::Software）",
                "pattern": r"^[A-Za-z_][A-Za-z0-9_]*(::[A-Za-z_][A-Za-z0-9_]*)*$",
            },
            "with_packages": {
                "type": "array",
                "description": "依赖的包列表（with 子句）",
                "items": {"type": "string"},
                "default": [],
            },
            "type_components": {
                "type": "array",
                "description": (
                    "所有组件类型（system/process/thread/device/bus/data 等）。"
                    "每个类型定义完整的端口、access、flow 等特征。"
                    "除简单 data 类型外，每个类型都要有对应的实现（在 impl_components 中）。"
                ),
                "minItems": 1,
                "items": type_item_schema,
            },
            "impl_components": {
                "type": "array",
                "description": (
                    "所有组件实现（骨架）。"
                    "每个实现对应一个类型（base_type 指向 type_components 中的类型名）。"
                    "subcomponents/connections/bindings 留空，后续阶段再填充。"
                    "简单 data 类型可以没有实现。"
                ),
                "items": impl_item_schema,
            },
        },
    }


# ============================================================
# 阶段2：subcomponents（分类器引用约束）
# ============================================================

def _normalize_phase1(phase1_result: dict) -> dict:
    """
    将阶段1的 type_components / impl_components 两数组格式
    标准化为统一的 components 数组格式（下游代码兼容）。

    实现组件的 category 会加上 " implementation" 后缀，
    保持和 architecture_schema / json_validator 的约定一致。
    """
    components = []

    # 先加所有类型
    for comp in phase1_result.get("type_components", []):
        components.append(comp)

    # 再加所有实现（category 加后缀）
    for comp in phase1_result.get("impl_components", []):
        norm_comp = dict(comp)
        cat = comp.get("category", "")
        if "implementation" not in cat:
            norm_comp["category"] = f"{cat} implementation"
        components.append(norm_comp)

    result = {
        "package_name": phase1_result.get("package_name", ""),
        "with_packages": phase1_result.get("with_packages", []),
        "components": components,
    }
    return result


def _get_type_names(phase1_result: dict) -> List[str]:
    """从阶段1结果中提取所有组件类型名"""
    names = []
    for comp in phase1_result.get("components", []):
        cat = comp.get("category", "")
        if "implementation" not in cat:
            names.append(comp["name"])
    return names


def _get_impl_names(phase1_result: dict) -> List[str]:
    """从阶段1结果中提取所有实现名"""
    names = []
    for comp in phase1_result.get("components", []):
        cat = comp.get("category", "")
        if "implementation" in cat:
            names.append(comp["name"])
    return names


def _get_impl_parent_category(impl_name: str, phase1_result: dict) -> Optional[str]:
    """获取实现对应的类型类别（用于包含规则约束）"""
    impl_comp = None
    for comp in phase1_result.get("components", []):
        if comp.get("name") == impl_name:
            impl_comp = comp
            break
    if not impl_comp:
        return None
    base_type = impl_comp.get("base_type")
    if not base_type:
        return None
    for comp in phase1_result.get("components", []):
        if comp.get("name") == base_type:
            return comp.get("category")
    return None


# 类别 → 分组字段名映射（用合法的 JSON key）
CAT_TO_GROUP = {
    "system": "systems",
    "process": "processes",
    "thread": "threads",
    "thread group": "thread_groups",
    "subprogram": "subprograms",
    "subprogram group": "subprogram_groups",
    "data": "data_components",
    "device": "devices",
    "processor": "processors",
    "virtual processor": "virtual_processors",
    "memory": "memories",
    "bus": "buses",
    "virtual bus": "virtual_buses",
    "abstract": "abstracts",
}


def _get_types_by_category(phase1_result: dict) -> Dict[str, List[str]]:
    """从阶段1结果中按类别分组的类型名"""
    by_cat = {}
    for comp in phase1_result.get("components", []):
        cat = comp.get("category", "")
        if "implementation" not in cat:
            by_cat.setdefault(cat, []).append(comp["name"])
    return by_cat


def build_phase2_schema(phase1_result: dict) -> dict:
    """
    阶段2 Schema：填充所有实现的 subcomponents（按类别分组）。

    动态约束：
    1. 按类别分组，每个分组的 classifier 枚举只包含对应类别的类型名
       （天然保证 category 和 classifier 一致，不会出现"类别 data + 分类器 system"的错误）
    2. 只保留父组件允许包含的类别分组（包含规则）
    """
    types_by_cat = _get_types_by_category(phase1_result)
    impl_names = _get_impl_names(phase1_result)

    # 子组件对象模板（不含 category 字段，因为隐含在分组中）
    def _subcomp_item_schema(cat: str) -> dict:
        return {
            "type": "object",
            "required": ["name", "classifier"],
            "additionalProperties": False,
            "properties": {
                "name": {
                    "type": "string",
                    "description": "子组件实例名",
                    "pattern": r"^[A-Za-z_][A-Za-z0-9_]*$",
                },
                "classifier": {
                    "type": "string",
                    "description": f"分类器（{cat} 类别）",
                    "enum": sorted(types_by_cat.get(cat, [])),
                },
                "properties": {
                    "type": "array",
                    "default": [],
                    "items": {
                        "type": "object",
                        "required": ["name", "value"],
                        "additionalProperties": False,
                        "properties": {
                            "name": {"type": "string"},
                            "value": {"type": "string"},
                            "unit": {"type": ["string", "null"], "default": None},
                        },
                    },
                },
            },
        }

    # 为每个实现构建独立的分组 subcomponents schema
    impl_props = {}
    for impl_name in impl_names:
        parent_cat = _get_impl_parent_category(impl_name, phase1_result)

        # 根据包含规则确定允许的子组件类别
        if parent_cat and parent_cat in CONTAINMENT_RULES:
            allowed_cats = sorted(CONTAINMENT_RULES[parent_cat])
        else:
            allowed_cats = sorted(CAT_TO_GROUP.keys())

        # 只保留有对应类型的类别分组（没有对应类型的类别就不显示该分组）
        group_props = {}
        for cat in allowed_cats:
            group_key = CAT_TO_GROUP[cat]
            if cat in types_by_cat and types_by_cat[cat]:
                group_props[group_key] = {
                    "type": "array",
                    "description": f"{cat} 类别的子组件",
                    "items": _subcomp_item_schema(cat),
                    "default": [],
                }

        impl_props[impl_name] = {
            "type": "object",
            "description": (
                f"{impl_name} 的子组件（按类别分组，父类别: {parent_cat or 'unknown'}）。"
                f" 每个分组的 classifier 只能选对应类别的类型。"
            ),
            "additionalProperties": False,
            "properties": group_props,
        }

    return {
        "type": "object",
        "required": ["impl_subcomponents"],
        "additionalProperties": False,
        "properties": {
            "impl_subcomponents": {
                "type": "object",
                "description": "每个实现的子组件（按类别分组，按实现名索引）",
                "additionalProperties": False,
                "properties": impl_props,
            },
        },
    }


# ============================================================
# 阶段3：connections + bindings + flows
# ============================================================

def _collect_port_table(arch: dict) -> Dict[str, Dict[str, dict]]:
    """
    收集所有实现的端口表。

    Returns:
        { impl_name: { "self": {port_name: port_info}, "subcomps": {subcomp_name: {port_name: port_info}} } }
    """
    # 先建立类型 -> 端口映射
    type_ports = {}
    for comp in arch.get("components", []):
        cat = comp.get("category", "")
        if "implementation" not in cat:
            type_ports[comp["name"]] = {
                p["name"]: p for p in comp.get("ports", [])
            }

    # 再为每个实现构建端口表
    port_table = {}
    for comp in arch.get("components", []):
        cat = comp.get("category", "")
        if "implementation" not in cat:
            continue

        impl_name = comp["name"]
        base_type = comp.get("base_type", "")
        port_table[impl_name] = {
            "self": type_ports.get(base_type, {}),
            "subcomps": {},
        }

        for sc in comp.get("subcomponents", []):
            sc_name = sc["name"]
            sc_classifier = sc.get("classifier", "")
            port_table[impl_name]["subcomps"][sc_name] = type_ports.get(sc_classifier, {})

    return port_table


def _collect_subcomp_names(arch: dict) -> Dict[str, List[str]]:
    """收集每个实现的子组件名"""
    result = {}
    for comp in arch.get("components", []):
        cat = comp.get("category", "")
        if "implementation" not in cat:
            continue
        impl_name = comp["name"]
        result[impl_name] = [sc["name"] for sc in comp.get("subcomponents", [])]
    return result


def _collect_bus_subcomps(arch: dict) -> Dict[str, List[str]]:
    """收集每个实现中类别为 bus/virtual bus 的子组件名"""
    result = {}
    for comp in arch.get("components", []):
        cat = comp.get("category", "")
        if "implementation" not in cat:
            continue
        impl_name = comp["name"]
        bus_names = []
        for sc in comp.get("subcomponents", []):
            sc_cat = sc.get("category", "")
            if sc_cat in ("bus", "virtual bus"):
                bus_names.append(sc["name"])
        result[impl_name] = bus_names
    return result


def _collect_processor_subcomps(arch: dict) -> Dict[str, List[str]]:
    """收集每个实现中类别为 processor/virtual processor 的子组件名"""
    result = {}
    for comp in arch.get("components", []):
        cat = comp.get("category", "")
        if "implementation" not in cat:
            continue
        impl_name = comp["name"]
        proc_names = []
        for sc in comp.get("subcomponents", []):
            sc_cat = sc.get("category", "")
            if sc_cat in ("processor", "virtual processor"):
                proc_names.append(sc["name"])
        result[impl_name] = proc_names
    return result


def _collect_ports_by_datatype(arch: dict) -> Dict[str, Dict[str, Dict[str, List[str]]]]:
    """
    按数据类型分组收集端口引用。

    Returns:
        { impl_name: { data_type: {"out": [port_refs], "in": [port_refs]} } }
    """
    # 先建立 类型名 -> 端口信息 映射
    type_ports = {}
    for comp in arch.get("components", []):
        cat = comp.get("category", "")
        if "implementation" not in cat:
            type_ports[comp["name"]] = comp.get("ports", [])

    result = {}
    for comp in arch.get("components", []):
        cat = comp.get("category", "")
        if "implementation" not in cat:
            continue

        impl_name = comp["name"]
        base_type = comp.get("base_type", "")
        by_type = {}  # data_type -> {in: [...], out: [...]}

        def _add_port(port_ref: str, pinfo: dict):
            dtype = pinfo.get("data_type") or "__untyped__"
            direction = pinfo.get("direction", "in")
            if dtype not in by_type:
                by_type[dtype] = {"in": [], "out": [], "inout": []}
            if direction == "in out":
                by_type[dtype]["inout"].append(port_ref)
            elif direction == "in":
                by_type[dtype]["in"].append(port_ref)
            elif direction == "out":
                by_type[dtype]["out"].append(port_ref)

        # 自身端口
        for pinfo in type_ports.get(base_type, []):
            _add_port(pinfo["name"], pinfo)

        # 子组件端口
        for sc in comp.get("subcomponents", []):
            sc_name = sc["name"]
            sc_classifier = sc.get("classifier", "")
            for pinfo in type_ports.get(sc_classifier, []):
                _add_port(f"{sc_name}.{pinfo['name']}", pinfo)

        # 排序去重
        for dtype in by_type:
            by_type[dtype]["in"] = sorted(set(by_type[dtype]["in"]))
            by_type[dtype]["out"] = sorted(set(by_type[dtype]["out"]))
            by_type[dtype]["inout"] = sorted(set(by_type[dtype]["inout"]))

        result[impl_name] = by_type

    return result


def _sanitize_datatype_key(dtype: str) -> str:
    """把数据类型名转换为合法的 JSON key（替换 :: . 等特殊字符）"""
    import re
    # Base_Types::Float -> Base_Types__Float
    # DataDictionary::SensorData -> DataDictionary__SensorData
    key = dtype.replace('::', '__')
    key = key.replace('.', '_')
    key = re.sub(r'[^A-Za-z0-9_]', '_', key)
    return key


def build_phase3_schema(phase2_arch: dict) -> dict:
    """
    阶段3 Schema：填充所有实现的 connections + bindings + flows。

    动态约束：
    1. connections 按数据类型分组，每组内的 source/target 都是同类型端口
       （天然保证连接两端数据类型一致）
    2. source 只能选 out/in out 端口，target 只能选 in/in out 端口
    3. binding target 只能选对应类别的子组件
    4. connection 绑定的 bus 只能选 bus 类别的子组件
    """
    port_table = _collect_port_table(phase2_arch)
    ports_by_dtype = _collect_ports_by_datatype(phase2_arch)
    subcomp_names = _collect_subcomp_names(phase2_arch)
    bus_subcomps = _collect_bus_subcomps(phase2_arch)
    proc_subcomps = _collect_processor_subcomps(phase2_arch)

    impl_names = list(subcomp_names.keys())

    impl_connections_props = {}
    impl_bindings_props = {}
    impl_flows_props = {}

    for impl_name in impl_names:
        # --- 子组件名 ---
        sc_names = sorted(subcomp_names.get(impl_name, []))
        bus_names = sorted(bus_subcomps.get(impl_name, []))
        proc_names = sorted(proc_subcomps.get(impl_name, []))

        # --- 按数据类型分组的 connections schema ---
        by_type = ports_by_dtype.get(impl_name, {})
        conn_groups = {}

        for dtype, dir_info in by_type.items():
            # source: out + inout 端口（同类型）
            source_refs = sorted(set(dir_info.get("out", []) + dir_info.get("inout", [])))
            # target: in + inout 端口（同类型）
            target_refs = sorted(set(dir_info.get("in", []) + dir_info.get("inout", [])))

            # 至少要有源有目标才能建连接
            if not source_refs or not target_refs:
                continue

            group_key = _sanitize_datatype_key(dtype)
            conn_groups[group_key] = {
                "type": "array",
                "description": (
                    f"数据类型为 '{dtype}' 的端口连接。"
                    f" source 和 target 都只能选该类型的端口，保证类型匹配。"
                ),
                "items": {
                    "type": "object",
                    "required": ["name", "source", "target"],
                    "additionalProperties": False,
                    "properties": {
                        "name": {
                            "type": "string",
                            "pattern": r"^[A-Za-z_][A-Za-z0-9_]*$",
                        },
                        "source": {
                            "type": "string",
                            "description": f"源端（{dtype} 类型，out/in out 方向）",
                            "enum": source_refs,
                        },
                        "target": {
                            "type": "string",
                            "description": f"目标端（{dtype} 类型，in/in out 方向）",
                            "enum": target_refs,
                        },
                        "bidirectional": {
                            "type": "boolean",
                            "default": False,
                        },
                        "bound_to_bus": {
                            "type": ["string", "null"],
                            "description": "绑定的总线子组件名",
                            "enum": bus_names if bus_names else [],
                            "default": None,
                        },
                        "properties": {
                            "type": "array",
                            "default": [],
                            "items": {
                                "type": "object",
                                "required": ["name", "value"],
                                "additionalProperties": False,
                                "properties": {
                                    "name": {"type": "string"},
                                    "value": {"type": "string"},
                                    "unit": {"type": ["string", "null"], "default": None},
                                },
                            },
                        },
                    },
                },
            }

        impl_connections_props[impl_name] = {
            "type": "object",
            "description": (
                f"{impl_name} 的连接（按数据类型分组）。"
                f" 每组内的连接两端都是同类型端口，天然类型匹配。"
                f" 类型为 '__untyped__' 的组是 event port 等无数据类型的连接。"
            ),
            "additionalProperties": False,
            "properties": conn_groups,
        }

        # --- bindings schema ---
        # 绑定的 source 可以是子组件名或连接名
        # 连接名是本阶段生成的，所以这里不能完全枚举
        # 折中：target 做枚举约束，source 不做
        impl_bindings_props[impl_name] = {
            "type": "array",
            "description": f"{impl_name} 的绑定列表",
            "items": {
                "type": "object",
                "required": ["kind", "source", "target"],
                "additionalProperties": False,
                "properties": {
                    "kind": {
                        "type": "string",
                        "enum": [
                            "actual_processor_binding",
                            "actual_memory_binding",
                            "actual_connection_binding",
                            "actual_subprogram_binding",
                        ],
                    },
                    "source": {
                        "type": "string",
                        "description": "被绑定的元素（子组件实例名或连接名）",
                    },
                    "target": {
                        "type": "string",
                        "description": "绑定目标（处理器/内存/总线子组件名）",
                        # 混合所有可能的目标
                        "enum": sorted(set(sc_names)),
                    },
                },
            },
        }

        # --- flows schema（端到端流） ---
        # flow 的 path 元素引用子组件流和连接，都是动态的
        # 这里只做格式约束，语义靠后处理
        impl_flows_props[impl_name] = {
            "type": "array",
            "description": f"{impl_name} 的端到端流列表",
            "items": {
                "type": "object",
                "required": ["name", "kind"],
                "additionalProperties": False,
                "properties": {
                    "name": {"type": "string", "pattern": r"^[A-Za-z_][A-Za-z0-9_]*$"},
                    "kind": {"type": "string", "enum": ["end to end flow"]},
                    "source": {"type": ["string", "null"], "default": None},
                    "target": {"type": ["string", "null"], "default": None},
                    "path": {
                        "type": "array",
                        "default": [],
                        "items": {"type": "string"},
                    },
                    "properties": {
                        "type": "array",
                        "default": [],
                        "items": {
                            "type": "object",
                            "required": ["name", "value"],
                            "additionalProperties": False,
                            "properties": {
                                "name": {"type": "string"},
                                "value": {"type": "string"},
                                "unit": {"type": ["string", "null"], "default": None},
                            },
                        },
                    },
                },
            },
        }

    return {
        "type": "object",
        "required": ["impl_connections", "impl_bindings", "impl_flows"],
        "additionalProperties": False,
        "properties": {
            "impl_connections": {
                "type": "object",
                "description": "每个实现的连接列表（按实现名索引）",
                "additionalProperties": False,
                "properties": impl_connections_props,
            },
            "impl_bindings": {
                "type": "object",
                "description": "每个实现的绑定列表（按实现名索引）",
                "additionalProperties": False,
                "properties": impl_bindings_props,
            },
            "impl_flows": {
                "type": "object",
                "description": "每个实现的端到端流列表（按实现名索引）",
                "additionalProperties": False,
                "properties": impl_flows_props,
            },
        },
    }


# ============================================================
# 合并阶段结果
# ============================================================

def merge_phase2_into_arch(phase1_arch: dict, phase2_result: dict) -> dict:
    """把阶段2的 subcomponents（按类别分组）合并到架构中，转为扁平格式"""
    arch = copy.deepcopy(phase1_arch)
    impl_subcomps = phase2_result.get("impl_subcomponents", {})

    # 构建 分组名 -> 类别 的反向映射
    GROUP_TO_CAT = {v: k for k, v in CAT_TO_GROUP.items()}

    for comp in arch.get("components", []):
        name = comp.get("name", "")
        if name not in impl_subcomps:
            continue

        grouped = impl_subcomps[name]
        flat = []
        for group_key, items in grouped.items():
            cat = GROUP_TO_CAT.get(group_key, group_key)
            for item in items:
                flat.append({
                    "name": item["name"],
                    "category": cat,
                    "classifier": item["classifier"],
                    "properties": item.get("properties", []),
                })
        comp["subcomponents"] = flat

    return arch


def merge_phase3_into_arch(phase2_arch: dict, phase3_result: dict) -> dict:
    """把阶段3的 connections（按数据类型分组）/bindings/flows 合并到架构中，转为扁平格式"""
    arch = copy.deepcopy(phase2_arch)
    impl_conns = phase3_result.get("impl_connections", {})
    impl_binds = phase3_result.get("impl_bindings", {})
    impl_flows = phase3_result.get("impl_flows", {})

    for comp in arch.get("components", []):
        name = comp.get("name", "")
        if name in impl_conns:
            # 按数据类型分组 -> 扁平数组
            grouped = impl_conns[name]
            flat = []
            for group_key, items in grouped.items():
                for item in items:
                    conn = dict(item)
                    # 补上 kind 字段（分组的都是 port connection）
                    if "kind" not in conn:
                        conn["kind"] = "port connection"
                    flat.append(conn)
            comp["connections"] = flat
        if name in impl_binds:
            comp["bindings"] = impl_binds[name]
        if name in impl_flows:
            comp["flows"] = impl_flows[name]

    return arch


# ============================================================
# 三阶段生成主函数
# ============================================================

def generate_phased(model, tokenizer, requirement: str,
                    max_tokens: int = 2048,
                    temperature: float = 0.0,
                    backend: str = "llguidance",
                    show_thinking: bool = False) -> dict:
    """
    三阶段渐进式生成 AADL 架构 JSON。

    Args:
        model, tokenizer: HF 模型和 tokenizer
        requirement: 自然语言需求
        max_tokens: 每阶段最大 token 数
        temperature: 采样温度
        backend: 约束解码后端
        show_thinking: 是否保留 thinking 块

    Returns:
        完整的架构 JSON dict
    """
    import torch
    import outlines
    from outlines.generator import get_json_schema_logits_processor

    outlines_model = outlines.from_transformers(model, tokenizer)

    # ---------- 阶段1 ----------
    print("\n" + "=" * 60)
    print("阶段 1/3：生成包 + 组件类型 + 实现骨架")
    print("=" * 60)

    phase1_prompt = _build_phase1_prompt(requirement)
    phase1_schema = build_phase1_schema()

    phase1_raw = _generate_with_schema(
        outlines_model, model, tokenizer,
        phase1_prompt, phase1_schema,
        max_tokens=max_tokens, temperature=temperature,
        backend=backend, show_thinking=show_thinking,
        phase_label="Phase1",
    )
    phase1_result = _trim_dict_keys(phase1_raw)

    # 标准化为统一格式（type_components + impl_components → components）
    # 下游所有辅助函数和 validator 都用统一格式
    phase1_result = _normalize_phase1(phase1_result)

    type_count = len(_get_type_names(phase1_result))
    impl_count = len(_get_impl_names(phase1_result))
    print(f"[Phase1] 生成 {type_count} 个类型 + {impl_count} 个实现")

    # ---------- 阶段2 ----------
    print("\n" + "=" * 60)
    print("阶段 2/3：生成所有实现的子组件（分类器引用约束）")
    print("=" * 60)

    phase2_schema = build_phase2_schema(phase1_result)
    type_count = len(_get_type_names(phase1_result))
    impl_count = len(_get_impl_names(phase1_result))
    print(f"[Context] 已定义 {type_count} 个类型，{impl_count} 个实现")

    if impl_count == 0:
        print("[Error] 阶段1没有生成任何实现组件！无法继续阶段2。")
        print("[Error] 请检查阶段1输出，确保 impl_components 数组不为空。")
        print("[Error] 类型列表：")
        for comp in phase1_result.get("components", []):
            cat = comp.get("category", "")
            if "implementation" not in cat:
                print(f"  - {comp.get('name')} ({cat})")
        raise ValueError("阶段1未生成任何实现组件")

    phase2_prompt = _build_phase2_prompt(requirement, phase1_result)

    phase2_raw = _generate_with_schema(
        outlines_model, model, tokenizer,
        phase2_prompt, phase2_schema,
        max_tokens=max_tokens, temperature=temperature,
        backend=backend, show_thinking=show_thinking,
        phase_label="Phase2",
    )
    phase2_result = _trim_dict_keys(phase2_raw)

    # 合并阶段2结果
    phase2_arch = merge_phase2_into_arch(phase1_result, phase2_result)

    # ---------- 阶段3 ----------
    print("\n" + "=" * 60)
    print("阶段 3/3：生成连接 + 绑定 + 流（端口方向 + 绑定目标约束）")
    print("=" * 60)

    phase3_schema = build_phase3_schema(phase2_arch)

    total_subcomps = sum(
        len(comp.get("subcomponents", []))
        for comp in phase2_arch.get("components", [])
        if "implementation" in comp.get("category", "")
    )
    print(f"[Context] 共有 {total_subcomps} 个子组件")

    phase3_prompt = _build_phase3_prompt(requirement, phase2_arch)

    phase3_raw = _generate_with_schema(
        outlines_model, model, tokenizer,
        phase3_prompt, phase3_schema,
        max_tokens=max_tokens, temperature=temperature,
        backend=backend, show_thinking=show_thinking,
        phase_label="Phase3",
    )
    phase3_result = _trim_dict_keys(phase3_raw)

    # 合并阶段3结果
    final_arch = merge_phase3_into_arch(phase2_arch, phase3_result)

    print("\n" + "=" * 60)
    print("三阶段生成完成 ✅")
    print("=" * 60)

    return final_arch


# ============================================================
# 底层生成函数（单轮 JSON Schema 约束生成）
# ============================================================

def _generate_with_schema(outlines_model, model, tokenizer,
                          prompt: str, schema: dict,
                          max_tokens: int, temperature: float,
                          backend: str, show_thinking: bool,
                          phase_label: str = "") -> dict:
    """用指定的 JSON Schema 做一轮约束生成"""
    import torch
    from outlines.generator import get_json_schema_logits_processor
    from transformers import TextIteratorStreamer
    import threading
    import re

    print(f"[{phase_label}] 编译 FSM...")
    t0 = time.time()

    logits_processor = get_json_schema_logits_processor(
        backend_name=backend,
        model=outlines_model,
        json_schema=json.dumps(schema),
    )

    compile_time = time.time() - t0
    print(f"[{phase_label}] FSM 编译完成，耗时 {compile_time:.1f}s")

    t1 = time.time()
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
    lp_list = [logits_processor]

    streamer = TextIteratorStreamer(
        tokenizer, skip_prompt=True, skip_special_tokens=True,
    )

    gen_kwargs = {
        "max_new_tokens": max_tokens,
        "logits_processor": lp_list,
        "pad_token_id": tokenizer.eos_token_id,
        "streamer": streamer,
    }
    if temperature > 0:
        gen_kwargs["do_sample"] = True
        gen_kwargs["temperature"] = temperature
        gen_kwargs["top_p"] = 0.9
    else:
        gen_kwargs["do_sample"] = False

    generated_text = []

    def generate_thread():
        with torch.no_grad():
            model.generate(**inputs, **gen_kwargs)

    thread = threading.Thread(target=generate_thread)
    thread.start()

    for new_text in streamer:
        print(new_text, end="", flush=True)
        generated_text.append(new_text)

    thread.join(timeout=1.0)
    result_text = "".join(generated_text)

    print()
    gen_time = time.time() - t1
    num_tokens = len(tokenizer.encode(result_text))
    speed = num_tokens / gen_time if gen_time > 0 else 0
    print(f"[{phase_label}] 生成完成，约 {num_tokens} tokens，耗时 {gen_time:.1f}s ({speed:.1f} t/s)")

    try:
        return json.loads(result_text.strip())
    except json.JSONDecodeError:
        match = re.search(r'\{.*\}', result_text, re.DOTALL)
        if match:
            return json.loads(match.group(0))
        raise


# ============================================================
# 三阶段 Prompt
# ============================================================

def _build_phase1_prompt(requirement: str) -> str:
    """阶段1 Prompt：生成包 + 组件类型 + 实现骨架"""
    return f"""你是一名资深 AADL 架构设计师。

请根据以下需求，设计 AADL 架构的**第一阶段：包定义、所有组件类型和实现骨架**。

【重要】本阶段必须生成完整的多级组件类型，包括所有层级的组件，
不仅仅是顶层 system。子组件在阶段2才会被实例化，但它们的类型必须在阶段1就定义好。

需求描述：
{requirement}

本阶段你需要生成：
1. package_name：有意义的包名（支持 :: 嵌套）
2. with_packages：依赖的包列表（用 Base_Types 等标准包时必须列出）
3. type_components：所有组件类型的完整定义
   - 顶层 system 类型
   - 所有 process 类型（system 的子组件类型）
   - 所有 thread 类型（process 的子组件类型）
   - 所有 device 类型、bus 类型、processor 类型、memory 类型
   - 所有 data 类型（自定义数据类型）
   - 所有 subprogram 类型
   - 每个类型需要定义：name、category、ports、accesses、flows、properties 等

4. impl_components：所有组件实现的骨架定义
   - 每个类型（除简单 data 类型外）都要有对应的实现
   - 实现必须包含：name、category、base_type（指向 type_components 中的类型名）
   - extends 可选
   - subcomponents、connections、bindings 全部留空数组，下一阶段再生成

【举例】一个飞行控制系统至少需要定义这些类型：
  type_components:
    - FlightControlSystem（system）
    - FlightControlProcess（process）
    - NavigationThread（thread）
    - GuidanceThread（thread）
    - SensorDevice（device）
    - ActuatorDevice（device）
    - MainProcessor（processor）
    - CanBus（bus）
    - SensorData（data）
    - ControlOutput（data）
  impl_components:
    - FlightControlSystem.impl（base_type: FlightControlSystem）
    - FlightControlProcess.impl（base_type: FlightControlProcess）
    - NavigationThread.impl（base_type: NavigationThread）
    - ... 以此类推

组件命名约定：
- 类型名：大驼峰（如 NavigationThread）
- 实现名：类型名.impl（如 NavigationThread.impl）
- 除简单 data 类型外，每个类型都要有对应的实现（即使是空的）
- base_type 必须对应 type_components 中的类型名

包含规则提示：
- system 包含 process、processor、memory、bus、device、thread group 等
- process 包含 thread、thread group、data、subprogram 等
- thread 包含 subprogram、data
- processor 包含 memory、bus、virtual processor 等
- bus 包含 virtual bus

请严格按 JSON Schema 输出，不要输出额外解释。
直接输出 JSON 对象。"""


def _build_phase2_prompt(requirement: str, phase1_result: dict) -> str:
    """阶段2 Prompt：生成子组件（按类别分组）"""
    # 提取已定义的类型和实现信息
    type_info = []
    impl_info = []
    type_cats = {}
    for comp in phase1_result.get("components", []):
        cat = comp.get("category", "")
        name = comp.get("name", "")
        if "implementation" not in cat:
            ports = [p["name"] for p in comp.get("ports", [])]
            type_info.append(f"  - {name}（{cat}），端口: {', '.join(ports) if ports else '无'}")
            type_cats.setdefault(cat, []).append(name)
        else:
            base = comp.get("base_type", "")
            impl_info.append(f"  - {name} → {base}")

    # 按类别列出来
    by_cat_lines = []
    for cat, names in sorted(type_cats.items()):
        by_cat_lines.append(f"    {cat}: {', '.join(names)}")

    return f"""你是一名资深 AADL 架构设计师。

根据以下需求和已定义的组件类型，请为**每个实现设计子组件**。

需求描述：
{requirement}

已定义的组件类型（按类别分组）：
{chr(10).join(by_cat_lines)}

需要填充子组件的实现：
{chr(10).join(impl_info)}

设计要求：
1. 子组件按类别分组填写：threads、processes、buses、data_components 等
2. 每个分组内的子组件，classifier 只能从该类别的类型中选择
3. 子组件类别必须符合包含规则（父组件类别决定了允许的子组件类别）
4. 子组件实例名用小写下划线（snake_case）
5. 根据需求合理设计子组件的数量和种类

输出格式：impl_subcomponents 对象，键是实现名，值是该实现的分组子组件对象。
分组名包括：systems / processes / threads / thread_groups / subprograms / subprogram_groups /
data_components / devices / processors / virtual_processors / memories / buses / virtual_buses / abstracts。
只有该实现允许包含、且有对应类型的分组才会出现。

请严格按 JSON Schema 输出，不要输出额外解释。
直接输出 JSON 对象。"""


def _build_phase3_prompt(requirement: str, phase2_arch: dict) -> str:
    """阶段3 Prompt：生成连接（按数据类型分组） + 绑定 + 流"""
    # 提取每个实现的子组件信息
    impl_subcomp_info = []
    for comp in phase2_arch.get("components", []):
        cat = comp.get("category", "")
        if "implementation" not in cat:
            continue
        name = comp.get("name", "")
        subcomps = comp.get("subcomponents", [])
        sc_info = []
        for sc in subcomps:
            sc_info.append(f"    * {sc['name']}: {sc['classifier']}（{sc['category']}）")
        impl_subcomp_info.append(f"  {name} 的子组件：\n{chr(10).join(sc_info) if sc_info else '    （无子组件）'}")

    return f"""你是一名资深 AADL 架构设计师。

根据以下需求和已定义的架构结构，请为**每个实现设计连接、绑定和流**。

需求描述：
{requirement}

各实现的子组件：
{chr(10).join(impl_subcomp_info)}

设计要求：
1. 连接按数据类型分组填写：同类型的端口才能互连，source 和 target 类型必须一致
2. 每个分组内，source 是 out/in out 方向的端口，target 是 in/in out 方向的端口
3. 端口引用格式：自身端口直接写名字，子组件端口用 '子组件名.端口名'
4. 分组名是数据类型的合法标识（如 Base_Types__Float、DataDictionary__SensorData），
   __untyped__ 组是 event port 等无数据类型的连接
5. actual_connection_binding 把连接绑定到总线子组件
6. actual_processor_binding 把线程/进程绑定到处理器子组件
7. 端到端流（end to end flow）描述跨子组件的完整数据流路径

输出格式：
- impl_connections：按实现名索引，每个实现内按数据类型分组的连接对象
- impl_bindings：按实现名索引的绑定列表
- impl_flows：按实现名索引的端到端流列表

请严格按 JSON Schema 输出，不要输出额外解释。
直接输出 JSON 对象。"""


# ============================================================
# 辅助函数（从 json_generator 复用，避免循环导入）
# ============================================================

def _trim_dict_keys(obj):
    """递归地去掉所有 dict key 的首尾空格"""
    if isinstance(obj, dict):
        return {k.strip(): _trim_dict_keys(v) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [_trim_dict_keys(item) for item in obj]
    else:
        return obj


# ============================================================
# 命令行入口（独立运行）
# ============================================================

def main():
    import argparse

    parser = argparse.ArgumentParser(description="三阶段渐进式 AADL 架构生成")
    parser.add_argument("--model", required=True, help="模型路径")
    parser.add_argument("--requirement", default=None, help="需求文本")
    parser.add_argument("--requirement-file", default=None, help="从文件读取需求")
    parser.add_argument("--output", default=None, help="输出 JSON 文件路径")
    parser.add_argument("--max-tokens", type=int, default=2048)
    parser.add_argument("--temperature", type=float, default=0.0)
    parser.add_argument("--load-in-8bit", action="store_true")
    parser.add_argument("--load-in-4bit", action="store_true")
    parser.add_argument("--backend", default="llguidance",
                        choices=["llguidance", "xgrammar", "outlines_core"])
    parser.add_argument("--show-thinking", action="store_true")
    parser.add_argument("--gen-prompt", action="store_true",
                        help="生成后自动生成 AADL 提示词")
    parser.add_argument("--prompt-format", default="by_component",
                        choices=["by_component", "by_category", "compact"])
    args = parser.parse_args()

    # 读取需求
    if args.requirement_file:
        with open(args.requirement_file, "r", encoding="utf-8") as f:
            requirement = f.read().strip()
        print(f"[Input] 从文件读取需求: {args.requirement_file}")
    elif args.requirement:
        requirement = args.requirement
    else:
        print("错误：请指定 --requirement 或 --requirement-file")
        sys.exit(1)

    print(f"[Input] 需求长度: {len(requirement)} 字符")

    # 加载模型
    from json_generator import load_model
    model, tokenizer = load_model(
        args.model,
        load_in_4bit=args.load_in_4bit,
        load_in_8bit=args.load_in_8bit,
    )

    # 三阶段生成
    arch = generate_phased(
        model, tokenizer, requirement,
        max_tokens=args.max_tokens,
        temperature=args.temperature,
        backend=args.backend,
        show_thinking=args.show_thinking,
    )

    # 语义校验
    from json_validator import quick_validate
    result = quick_validate(arch)
    print()
    print(result.summary())

    # 保存 JSON
    if args.output:
        os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(arch, f, indent=2, ensure_ascii=False)
        print(f"\n[Output] 架构 JSON 已保存: {args.output}")

    # 生成 AADL prompt
    if args.gen_prompt:
        if not result.valid:
            print("\n[Skip] 语义校验未通过，跳过生成 AADL 提示词")
        else:
            from json_to_prompt import build_prompt as build_aadl_prompt
            aadl_prompt = build_aadl_prompt(arch, fmt=args.prompt_format)

            if args.output:
                prompt_path = args.output.replace(".json", "") + f".{args.prompt_format}.prompt"
            else:
                prompt_path = f"aadl_prompt.{args.prompt_format}.txt"

            os.makedirs(os.path.dirname(prompt_path) or ".", exist_ok=True)
            with open(prompt_path, "w", encoding="utf-8") as f:
                f.write(aadl_prompt)
            print(f"[Output] AADL 生成提示词已保存: {prompt_path}")

    sys.exit(0 if result.valid else 1)


if __name__ == "__main__":
    main()
