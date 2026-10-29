#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AADL 架构语义校验器 v2
======================
基于真实 AADL 示例（osate-examples）补充完善。

校验维度（11 项）：
1.  包含规则（Containment）
2.  引用完整性（Reference Integrity）
3.  端口方向匹配（Port Direction）
4.  数据类型合法性（Data Type Validity）
5.  数据类型匹配（Type Matching，警告级）
6.  实现存在性（Implementation）
7.  绑定规则（Binding）
8.  总线绑定（Bus Binding）
9.  属性合法性（Property）
10. extends 继承合法性（Inheritance）
11. Flow 引用完整性（Flow Reference）
"""

from typing import List, Dict, Set, Optional
from dataclasses import dataclass, field

from architecture_schema import (
    PREDEFINED_DATA_TYPES,
    STANDARD_PACKAGES,
    is_predefined_type,
    is_qualified_name,
    get_local_name,
    is_in_package,
)


# ============================================================
# AADL 组件包含规则
# 来源：SAE AS5506C 标准 + osate-examples 验证
# ============================================================

CONTAINMENT_RULES = {
    "system": {
        "system", "process", "processor", "virtual processor",
        "memory", "bus", "virtual bus", "device", "data",
        "subprogram", "subprogram group", "thread group", "abstract",
    },
    "process": {
        "thread", "thread group", "subprogram",
        "subprogram group", "data", "abstract",
    },
    "thread": {
        "subprogram", "data",
    },
    "thread group": {
        "thread", "thread group", "data",
        "subprogram", "subprogram group", "abstract",
    },
    "data": {
        "data", "subprogram",
    },
    "processor": {
        "memory", "bus", "virtual bus",
        "virtual processor", "abstract",
    },
    "virtual processor": {
        "virtual processor", "memory",
        "virtual bus", "abstract",
    },
    "memory": {
        "memory",
    },
    "bus": {
        "virtual bus",
    },
    "virtual bus": {
        "virtual bus",
    },
    "device": {
        "data",
    },
    "abstract": {
        "system", "process", "processor", "virtual processor",
        "memory", "bus", "virtual bus", "device",
        "thread", "thread group", "data",
        "subprogram", "subprogram group", "abstract",
    },
    "subprogram": {
        "data",
    },
    "subprogram group": {
        "subprogram", "subprogram group", "data",
    },
}


# ============================================================
# 绑定规则
# ============================================================

BINDING_RULES = {
    # (源类别, 绑定类型): 允许的目标类别集合
    ("process", "actual_processor_binding"): {"processor", "virtual processor"},
    ("thread", "actual_processor_binding"): {"processor", "virtual processor"},
    ("thread group", "actual_processor_binding"): {"processor", "virtual processor"},
    ("subprogram", "actual_processor_binding"): {"processor", "virtual processor"},
    ("data", "actual_memory_binding"): {"memory"},
    ("process", "actual_memory_binding"): {"memory"},
    ("thread", "actual_memory_binding"): {"memory"},
    ("port connection", "actual_connection_binding"): {"bus", "virtual bus"},
    ("access connection", "actual_connection_binding"): {"bus", "virtual bus"},
    ("subprogram", "actual_subprogram_binding"): {"subprogram"},
}


# ============================================================
# 合法属性表（组件类别 → 允许的属性集合）
# 来源：AS5506C + osate-examples 常用属性
# ============================================================

PROPERTY_RULES = {
    "all": {
        "Documentation",
        "Source_Text",
        "Model_Root",
        "Category",
    },
    # ---- 线程 ----
    "thread": {
        "Dispatch_Protocol",
        "Period",
        "Deadline",
        "Compute_Execution_Time",
        "compute_execution_time",
        "Stack_Size",
        "Priority",
        "Dispatch_Offset",
        "Time_Slot",
        "Required_Processor",
        "reference_processor",
        "Actual_Processor_Binding",
        "Actual_Memory_Binding",
        "Reference_Processor",
        "Execution_Time",
        "Latency",
        "Input_Rate",
        "Output_Rate",
        "Input_Time",
        "Output_Time",
        "Capacity",
    },
    # ---- 线程组 ----
    "thread group": {
        "Actual_Processor_Binding",
        "Actual_Memory_Binding",
        "Priority",
    },
    # ---- 进程 ----
    "process": {
        "Dispatch_Protocol",
        "Period",
        "Deadline",
        "Source_Stack_Size",
        "Actual_Processor_Binding",
        "Actual_Memory_Binding",
        "Virtual_Processor_Binding",
        "Required_Processor",
        "Reference_Processor",
        "Input_Rate",
        "Output_Rate",
        "Input_Time",
        "Output_Time",
        "Capacity",
    },
    # ---- 处理器 ----
    "processor": {
        "MIPS",
        "MIPSCapacity",
        "Processor_Model",
        "Clock_Period",
        "Supported_Connection_Services",
        "Available_Processor_Speed",
        "Total_Memory_Size",
        "Execution_Time",
        "Supported_Thread_Speed",
    },
    # ---- 虚拟处理器 ----
    "virtual processor": {
        "MIPS",
        "Period",
        "Time_Slot",
        "Dispatch_Protocol",
        "Actual_Processor_Binding",
    },
    # ---- 内存 ----
    "memory": {
        "Memory_Size",
        "Access_Time",
        "Word_Size",
        "Memory_Protocol",
        "Read_Time",
        "Write_Time",
        "Data_Size",
        "By_Count",
        "Per_Read_Cost",
        "Per_Write_Cost",
        "Per_Byte_Read_Cost",
        "Per_Byte_Write_Cost",
    },
    # ---- 总线 ----
    "bus": {
        "Bus_Width",
        "Bandwidth",
        "BandWidthCapacity",
        "Max_Message_Size",
        "Protocol",
        "Arbitration",
        "Latency",
        "Transmission_Time",
        "Maximum_Throughput",
        "Minimum_Throughput",
        "Timing",
        "Per_Message_Cost",
        "Per_Byte_Cost",
        "Allowed_Connection_Binding_Class",
        "Allowed_Connection_Binding_Class_Or_Descendant",
    },
    # ---- 虚拟总线 ----
    "virtual bus": {
        "Bus_Width",
        "Max_Message_Size",
        "Protocol",
        "Actual_Bus_Binding",
    },
    # ---- 设备 ----
    "device": {
        "Device_Model",
        "Actual_Processor_Binding",
        "Period",
        "Compute_Execution_Time",
        "compute_execution_time",
        "Deadline",
        "Dispatch_Protocol",
        "Priority",
        "Execution_Time",
        "Latency",
        "Input_Rate",
        "Output_Rate",
        "Input_Time",
        "Output_Time",
        "Capacity",
    },
    # ---- 数据 ----
    "data": {
        "Data_Size",
        "Data_Representation",
        "data_representation",
        "Enumerators",
        "enumerators",
        "Dimension",
        "Data_Model",
    },
    # ---- 子程序 ----
    "subprogram": {
        "Compute_Execution_Time",
        "compute_execution_time",
        "Execution_Time",
        "Deadline",
        "Stack_Size",
        "Priority",
    },
    # ---- 子程序组 ----
    "subprogram group": {
        "Compute_Execution_Time",
        "Execution_Time",
    },
    # ---- 数据端口 ----
    "data port": {
        "Data_Resolution",
        "Source_Data_Size",
        "Destination_Data_Size",
        "Queue_Size",
        "Overwrite_Allowed",
        "Rate",
        "Timing",
        "Input_Rate",
        "Output_Rate",
    },
    # ---- 事件端口 ----
    "event port": {
        "Queue_Size",
        "Overwrite_Allowed",
        "Timing",
        "Rate",
    },
    # ---- 事件数据端口 ----
    "event data port": {
        "Queue_Size",
        "Overwrite_Allowed",
        "Timing",
        "Rate",
        "Data_Size",
    },
    # ---- 连接 ----
    "port connection": {
        "Timing",
        "Filter",
        "Actual_Connection_Binding",
        "actual_connection_binding",
        "Maximum_Throughput",
        "Minimum_Throughput",
        "Latency",
        "Connection_Service",
    },
    # ---- 系统 ----
    "system": {
        "Actual_Processor_Binding",
        "Actual_Memory_Binding",
        "Actual_Bus_Binding",
        "Virtual_Processor_Binding",
        "Virtual_Bus_Binding",
        "Reference_Processor",
        "Input_Rate",
        "Output_Rate",
    },
    # ---- abstract ----
    "abstract": {
        "Actual_Processor_Binding",
        "Actual_Memory_Binding",
        "Actual_Bus_Binding",
    },
    # ---- feature group ----
    "feature group": {
        "Inverse_Of",
    },
    # ---- access connection ----
    "access connection": {
        "Actual_Connection_Binding",
        "Timing",
    },
    # ---- parameter connection ----
    "parameter connection": {
        "Timing",
        "Actual_Connection_Binding",
    },
    # ---- bus access ----
    "bus access": {
        "Bus_Access_Priority",
    },
    # ---- data access ----
    "data access": {
        "Data_Size",
        "Timing",
    },
    # ---- subprogram access ----
    "subprogram access": {
        "Execution_Time",
    },
    # ---- feature group connection ----
    "feature group connection": {
        "Actual_Connection_Binding",
        "Timing",
    },
}


def get_valid_properties(category: str) -> Set[str]:
    """获取某组件类别允许的属性名集合（含通用属性）"""
    result = set(PROPERTY_RULES.get("all", set()))
    result.update(PROPERTY_RULES.get(category, set()))
    return result


def get_port_valid_properties(port_kind: str) -> Set[str]:
    """获取某类端口允许的属性名集合"""
    result = set(PROPERTY_RULES.get("all", set()))
    result.update(PROPERTY_RULES.get(port_kind, set()))
    return result


# ============================================================
# 校验结果
# ============================================================

@dataclass
class ValidationResult:
    valid: bool = True
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)

    def error(self, msg: str):
        self.valid = False
        self.errors.append(msg)

    def warn(self, msg: str):
        self.warnings.append(msg)

    def summary(self) -> str:
        if self.valid and not self.warnings:
            return "✅ 校验通过（无错误，无警告）"
        status = "✅ 通过" if self.valid else "❌ 失败"
        lines = [
            f"{status}：{len(self.errors)} 个错误，{len(self.warnings)} 个警告",
        ]
        if self.errors:
            lines.append("错误：")
            lines.extend(f"  ✗ {e}" for e in self.errors)
        if self.warnings:
            lines.append("警告：")
            lines.extend(f"  ⚠ {w}" for w in self.warnings)
        return "\n".join(lines)


# ============================================================
# 语义校验器
# ============================================================

class ArchitectureValidator:
    """架构 JSON 语义校验器"""

    def validate(self, arch: dict) -> ValidationResult:
        result = ValidationResult()

        package_name = arch.get("package_name", "")
        with_packages = arch.get("with_packages", [])
        components = arch.get("components", [])

        # ---- 第一步：收集所有组件类型和实现 ----
        component_types = {}   # name -> info dict
        component_impls = {}   # name -> info dict
        data_components = []   # data 类别组件名列表

        for comp in components:
            name = comp.get("name", "")
            cat = comp.get("category", "")
            if "implementation" in cat:
                component_impls[name] = comp
            else:
                component_types[name] = comp
                if cat == "data":
                    data_components.append(name)

        # ---- 校验1：实现必须对应类型 ----
        for impl_name, impl in component_impls.items():
            base = impl.get("base_type", "")
            if not base:
                result.error(f"实现 {impl_name} 缺少 base_type 字段")
                continue
            if base not in component_types:
                result.error(f"实现 {impl_name} 对应的类型 {base} 不存在")

        # ---- 校验2：extends 继承合法性 ----
        # 类型继承
        for name, comp in component_types.items():
            parent = comp.get("extends")
            if parent:
                parent_local = get_local_name(parent)
                if is_qualified_name(parent):
                    # 跨包继承，假设来自 with 的包，不深究（警告）
                    if not is_in_package(parent, with_packages):
                        result.warn(
                            f"继承警告：{name} 继承自 {parent}，"
                            f"但父类所在包不在 with 列表中"
                        )
                else:
                    # 同包继承，必须存在
                    if parent_local not in component_types:
                        result.error(
                            f"继承错误：{name} 继承自 {parent}，"
                            f"但父类型不存在"
                        )
                    else:
                        # 类别必须兼容（父类类别可以相同或更抽象）
                        parent_cat = component_types[parent_local].get("category", "")
                        child_cat = comp.get("category", "")
                        if parent_cat != child_cat and parent_cat != "abstract":
                            result.error(
                                f"继承错误：{name}({child_cat}) 不能继承自 "
                                f"{parent}({parent_cat})，类别不兼容"
                            )

        # 实现继承
        for impl_name, impl in component_impls.items():
            parent = impl.get("extends")
            if parent:
                parent_local = get_local_name(parent)
                if is_qualified_name(parent):
                    if not is_in_package(parent, with_packages):
                        result.warn(
                            f"继承警告：实现 {impl_name} 继承自 {parent}，"
                            f"但父实现所在包不在 with 列表中"
                        )
                else:
                    if parent_local not in component_impls:
                        result.error(
                            f"继承错误：实现 {impl_name} 继承自 {parent}，"
                            f"但父实现不存在"
                        )

        # ---- 校验3：包含规则 + 子组件引用 ----
        for impl_name, impl in component_impls.items():
            base = impl.get("base_type", "")
            parent_type = component_types.get(base, {})
            parent_cat = parent_type.get("category", "")
            if not parent_cat:
                continue

            allowed = CONTAINMENT_RULES.get(parent_cat, set())
            for sub in impl.get("subcomponents", []):
                sub_cat = sub.get("category", "")
                sub_classifier = sub.get("classifier", "")
                sub_name = sub.get("name", "?")

                # 3a: 子组件类别合法性
                if sub_cat not in allowed:
                    result.error(
                        f"包含违规：{impl_name}（{parent_cat}）"
                        f"不能包含 {sub_name}（{sub_cat}）。"
                        f"{parent_cat} 允许包含: {sorted(allowed)}"
                    )

                # 3b: 分类器引用存在性
                cls_local = get_local_name(sub_classifier)
                if is_qualified_name(sub_classifier):
                    # 跨包引用，假设合法（不校验外部包内容）
                    if not is_in_package(sub_classifier, with_packages):
                        result.warn(
                            f"跨包引用警告：{impl_name}.{sub_name} 的分类器 "
                            f"'{sub_classifier}' 所在包不在 with 列表中"
                        )
                else:
                    # 同包引用
                    if cls_local not in component_types and \
                       cls_local not in component_impls:
                        result.error(
                            f"引用违规：{impl_name} 中子组件 "
                            f"{sub_name} 的分类器 '{sub_classifier}' 不存在"
                        )

                # 3c: 分类器类别与子组件类别一致
                if not is_qualified_name(sub_classifier):
                    cls_type = component_types.get(cls_local)
                    if not cls_type and "." in cls_local:
                        cls_base = cls_local.split(".")[0]
                        cls_type = component_types.get(cls_base)
                    if cls_type:
                        cls_cat = cls_type.get("category", "")
                        if cls_cat != sub_cat and cls_cat != "abstract":
                            result.error(
                                f"类别不匹配：{impl_name}.{sub_name} "
                                f"声明类别为 {sub_cat}，"
                                f"但分类器 {sub_classifier} 的类别是 {cls_cat}"
                            )

        # ---- 校验4：数据类型合法性 ----
        for comp_name, comp in component_types.items():
            for port in comp.get("ports", []):
                data_type = port.get("data_type")
                port_kind = port.get("kind", "")

                if port_kind in ("data port", "event data port"):
                    if not data_type:
                        result.error(
                            f"类型错误：{comp_name}.{port.get('name', '?')}"
                            f"（{port_kind}）缺少数据类型"
                        )
                        continue

                    # 检查类型是否合法
                    predefined_ok = is_predefined_type(data_type)
                    custom_ok = get_local_name(data_type) in data_components
                    external_ok = is_in_package(data_type, with_packages)

                    if not (predefined_ok or custom_ok or external_ok):
                        result.error(
                            f"数据类型错误：{comp_name}.{port.get('name', '?')}"
                            f" 的类型 '{data_type}' 不存在。"
                            f"预定义类型: {len(PREDEFINED_DATA_TYPES)} 个，"
                            f"自定义 data 组件: {data_components or '无'}，"
                            f"with 包: {with_packages or '无'}"
                        )

        # ---- 校验5：端口方向匹配 + 数据类型匹配 ----
        for impl_name, impl in component_impls.items():
            base = impl.get("base_type", "")
            parent_type = component_types.get(base, {})

            # 构建端口查找表
            port_table = self._build_port_table(
                impl, parent_type, component_types, component_impls, with_packages
            )

            for conn in impl.get("connections", []):
                conn_name = conn.get("name", "?")
                conn_kind = conn.get("kind", "port connection")

                # 只校验端口连接的方向
                if conn_kind != "port connection":
                    # access 连接等不做方向校验
                    continue

                src = conn.get("source", "")
                tgt = conn.get("target", "")

                # 5a: 源端和目标端存在
                src_info = port_table.get(src)
                tgt_info = port_table.get(tgt)

                if not src_info:
                    result.error(
                        f"引用违规：{impl_name} 连接 {conn_name} 的"
                        f"源端 '{src}' 不存在"
                    )
                if not tgt_info:
                    result.error(
                        f"引用违规：{impl_name} 连接 {conn_name} 的"
                        f"目标端 '{tgt}' 不存在"
                    )

                if not src_info or not tgt_info:
                    continue

                # 5b: 方向匹配
                src_dir = src_info.get("direction", "")
                tgt_dir = tgt_info.get("direction", "")
                if src_dir not in ("out", "in out"):
                    result.error(
                        f"方向错误：{impl_name} 连接 {conn_name} 的"
                        f"源端 {src} 是 {src_dir}，必须是 out 或 in out"
                    )
                if tgt_dir not in ("in", "in out"):
                    result.error(
                        f"方向错误：{impl_name} 连接 {conn_name} 的"
                        f"目标端 {tgt} 是 {tgt_dir}，必须是 in 或 in out"
                    )

                # 5c: 数据类型匹配
                src_type = src_info.get("type")
                tgt_type = tgt_info.get("type")
                if src_type and tgt_type and src_type != tgt_type:
                    result.error(
                        f"类型不匹配：{impl_name} 连接 {conn_name} "
                        f"源端类型 {src_type} ≠ 目标端类型 {tgt_type}"
                    )

        # ---- 校验6：总线绑定 + 绑定规则 ----
        for impl_name, impl in component_impls.items():
            # 收集当前实现内的 bus 子组件
            bus_instances = {}  # 实例名 -> 类别
            for sub in impl.get("subcomponents", []):
                if sub.get("category") in ("bus", "virtual bus"):
                    bus_instances[sub.get("name", "")] = sub.get("category", "")

            # 检查连接的 bound_to_bus
            for conn in impl.get("connections", []):
                bound_bus = conn.get("bound_to_bus")
                if bound_bus and bound_bus not in bus_instances:
                    result.error(
                        f"总线绑定错误：{impl_name} 连接 "
                        f"{conn.get('name', '?')} 绑定的总线 "
                        f"'{bound_bus}' 不存在。"
                        f"可用 bus: {sorted(bus_instances.keys()) or '无'}"
                    )

            # 检查 bindings 数组
            for binding in impl.get("bindings", []):
                bkind = binding.get("kind", "")
                bsrc = binding.get("source", "")
                btgt = binding.get("target", "")

                # 绑定目标必须是子组件
                tgt_sub = None
                for sub in impl.get("subcomponents", []):
                    if sub.get("name") == btgt:
                        tgt_sub = sub
                        break
                if not tgt_sub:
                    result.error(
                        f"绑定错误：{impl_name} 绑定 "
                        f"'{bkind}' 的目标 '{btgt}' 不是子组件"
                    )
                    continue

                # 确定源类别
                src_cat = self._get_element_category(
                    bsrc, impl, component_types, component_impls, with_packages
                )

                # 确定目标类别
                tgt_classifier = tgt_sub.get("classifier", "")
                tgt_cls_local = get_local_name(tgt_classifier)
                tgt_type = component_types.get(tgt_cls_local)
                if not tgt_type and "." in tgt_cls_local:
                    tgt_type = component_types.get(tgt_cls_local.split(".")[0])
                tgt_cat = tgt_type.get("category", "") if tgt_type else ""

                if src_cat and tgt_cat and bkind:
                    key = (src_cat, bkind)
                    allowed_tgt = BINDING_RULES.get(key, set())
                    if allowed_tgt and tgt_cat not in allowed_tgt:
                        result.error(
                            f"绑定类型错误：{impl_name} 中 "
                            f"{bsrc}（{src_cat}）的 {bkind} "
                            f"不能绑定到 {btgt}（{tgt_cat}）。"
                            f"允许绑定到: {sorted(allowed_tgt)}"
                        )

        # ---- 校验7：属性合法性（警告级） ----
        for comp_name, comp in component_types.items():
            cat = comp.get("category", "")
            valid_props = get_valid_properties(cat)
            for prop in comp.get("properties", []):
                if not isinstance(prop, dict):
                    result.warn(
                        f"属性警告：{comp_name}（{cat}）的属性格式不正确"
                        f"（应为对象，实际为 {type(prop).__name__}），已跳过"
                    )
                    continue
                prop_name = prop.get("name", "")
                # 去掉包限定前缀做检查
                prop_local = get_local_name(prop_name)
                if prop_local not in valid_props and prop_name not in valid_props:
                    result.warn(
                        f"属性警告：{comp_name}（{cat}）的属性 "
                        f"'{prop_name}' 可能不合法"
                    )

            # 端口属性
            for port in comp.get("ports", []):
                port_kind = port.get("kind", "")
                valid_port_props = get_port_valid_properties(port_kind)
                for prop in port.get("properties", []):
                    if not isinstance(prop, dict):
                        continue
                    prop_name = prop.get("name", "")
                    prop_local = get_local_name(prop_name)
                    if prop_local not in valid_port_props and prop_name not in valid_port_props:
                        result.warn(
                            f"属性警告：{comp_name}.{port.get('name', '?')}"
                            f"（{port_kind}）的属性 '{prop_name}' 可能不合法"
                        )

        # ---- 校验8：Flow 引用完整性 ----
        for comp_name, comp in component_types.items():
            port_names = {p["name"] for p in comp.get("ports", [])}
            for flow in comp.get("flows", []):
                fkind = flow.get("kind", "")
                fsrc = flow.get("source")
                ftgt = flow.get("target")

                # flow path: 两端都要存在
                if fkind == "flow path":
                    if fsrc and fsrc not in port_names:
                        result.error(
                            f"Flow 错误：{comp_name} 的 flow "
                            f"{flow.get('name', '?')} 源端 {fsrc} 不存在"
                        )
                    if ftgt and ftgt not in port_names:
                        result.error(
                            f"Flow 错误：{comp_name} 的 flow "
                            f"{flow.get('name', '?')} 目标端 {ftgt} 不存在"
                        )
                # flow source: 源端必须是 out
                elif fkind == "flow source":
                    if fsrc and fsrc not in port_names:
                        result.error(
                            f"Flow 错误：{comp_name} 的 flow source "
                            f"{flow.get('name', '?')} 源端 {fsrc} 不存在"
                        )
                # flow sink: 目标端必须是 in
                elif fkind == "flow sink":
                    if ftgt and ftgt not in port_names:
                        result.error(
                            f"Flow 错误：{comp_name} 的 flow sink "
                            f"{flow.get('name', '?')} 目标端 {ftgt} 不存在"
                        )

        return result

    # ---- 辅助：构建端口查找表（支持跨层级） ----
    def _build_port_table(self, impl: dict, parent_type: dict,
                          component_types: dict, component_impls: dict,
                          with_packages: List[str]) -> Dict[str, dict]:
        """
        构建实现内所有可访问端口的查找表。
        key: 端口路径（如 'port' / 'sub.port' / 'sub.subsub.port'）
        """
        table = {}

        # 自身端口
        for port in parent_type.get("ports", []):
            table[port["name"]] = {
                "direction": port["direction"],
                "kind": port.get("kind", ""),
                "type": port.get("data_type"),
            }

        # 递归展开子组件端口
        for sub in impl.get("subcomponents", []):
            sub_name = sub["name"]
            classifier = sub.get("classifier", "")
            cls_local = get_local_name(classifier)
            cls_base = cls_local.split(".")[0]

            # 跨包引用的子组件，不展开内部端口（我们不知道外部包内容）
            if is_qualified_name(classifier):
                # 只加一层（类型的端口），如果能推断的话
                # 保守起见，跳过跨包子组件的内部端口展开
                continue

            sub_type = component_types.get(cls_base, {})
            if not sub_type:
                continue

            # 直接子组件的端口
            for port in sub_type.get("ports", []):
                key = f"{sub_name}.{port['name']}"
                table[key] = {
                    "direction": port["direction"],
                    "kind": port.get("kind", ""),
                    "type": port.get("data_type"),
                }

            # 如果子组件有实现，递归展开
            if cls_local in component_impls:
                sub_impl = component_impls[cls_local]
                nested = self._build_port_table(
                    sub_impl, sub_type, component_types,
                    component_impls, with_packages
                )
                # 只加多层嵌套的（带点号的），顶层端口已经在上面加过了
                for k, v in nested.items():
                    if "." in k:
                        table[f"{sub_name}.{k}"] = v

        return table

    # ---- 辅助：获取元素类别 ----
    def _get_element_category(self, elem_name: str, impl: dict,
                              component_types: dict, component_impls: dict,
                              with_packages: List[str]) -> Optional[str]:
        """获取实现内某个元素的类别（子组件/连接/自身端口等）"""
        # 先查子组件
        for sub in impl.get("subcomponents", []):
            if sub.get("name") == elem_name:
                classifier = sub.get("classifier", "")
                cls_local = get_local_name(classifier)
                cls_base = cls_local.split(".")[0]
                t = component_types.get(cls_base)
                return t.get("category") if t else None
        # 查连接（连接本身的类别）
        for conn in impl.get("connections", []):
            if conn.get("name") == elem_name:
                return conn.get("kind", "port connection")
        return None


# ============================================================
# 快速校验入口
# ============================================================

def quick_validate(arch: dict) -> ValidationResult:
    return ArchitectureValidator().validate(arch)


if __name__ == "__main__":
    import json
    import sys

    if len(sys.argv) > 1:
        with open(sys.argv[1], "r", encoding="utf-8") as f:
            arch = json.load(f)
        result = quick_validate(arch)
        print(result.summary())
    else:
        print("用法: python json_validator.py <arch.json>")
