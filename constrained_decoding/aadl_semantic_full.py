#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AADL 上下文敏感约束引擎（完整版 — 基于原始引擎扩展）
=====================================================
在 aadl_context_sensitive.py 的基础上，扩展更多语义约束：

新增约束：
  6. 数据类型合法性  — 端口数据类型必须是预定义/自定义 data
  7. 命名唯一性     — 组件/端口/子组件名不能重名（负向过滤）
  8. 连接 bound_to_bus — 必须绑定到已有的 bus 子组件
  9. 实现 base_type — 必须引用同类别已存在的类型
  10. 连接种类匹配   — port connection 只能连 data port 等
  11. Flow 引用      — flow 端点必须是已有端口，方向匹配
  12. 属性名合法性   — 属性名必须是该类别允许的
"""

import re
import sys
import os
from typing import List, Dict, Optional, Set, Tuple

# 复用原始引擎
from aadl_context_sensitive import (
    AadlContextSensitiveEngine, AadlSymbolTable,
    IncrementalAadlParser, ConstraintField,
    PortInfo, SubcompInfo, CompTypeInfo, CompImplInfo,
)
from json_validator import (
    get_valid_properties, get_port_valid_properties, PROPERTY_RULES,
)
from architecture_schema import PREDEFINED_DATA_TYPES


# ============================================================
# 扩展约束字段类型
# ============================================================
class ExtraConstraintField:
    """新增的约束字段类型"""
    PORT_DATA_TYPE = "port_data_type"        # 端口数据类型
    CONN_BOUND_BUS = "conn_bound_bus"        # 连接绑定的 bus
    IMPL_BASE_TYPE = "impl_base_type"        # 实现的 base_type
    FLOW_ENDPOINT = "flow_endpoint"          # flow 端点
    PROPERTY_NAME = "property_name"          # 属性名
    COMPONENT_NAME = "component_name"        # 组件名（命名唯一性，负向）
    PORT_NAME = "port_name"                  # 端口名（命名唯一性，负向）
    SUBCOMP_NAME = "subcomp_name"            # 子组件名（命名唯一性，负向）


# ============================================================
# 扩展符号表（增加查询方法）
# ============================================================
class FullAadlSymbolTable:
    """在原始符号表基础上增加更多查询方法和状态属性的包装类"""

    def __init__(self, symbols: AadlSymbolTable):
        self._sym = symbols
        # 额外的状态属性（原始符号表没有的）
        self._in_properties: bool = False
        self._in_flows: bool = False

    def __getattr__(self, name):
        """代理到原始符号表"""
        return getattr(self._sym, name)

    @property
    def in_properties(self) -> bool:
        return self._in_properties

    @property
    def in_flows(self) -> bool:
        return self._in_flows

    # ---- 新增查询方法 ----

    def get_valid_data_types(self) -> List[str]:
        """合法的数据类型：预定义 + 自定义 data + 跨包（假设合法）"""
        results = list(PREDEFINED_DATA_TYPES)
        for name, info in self._sym.component_types.items():
            if info.category == "data":
                results.append(name)
        return sorted(results)

    def get_valid_bound_buses(self, impl_name: str) -> List[str]:
        """连接可绑定的 bus 子组件"""
        impl = self._sym.component_impls.get(impl_name)
        if not impl:
            return []
        results = []
        for sname, sub in impl.subcomponents.items():
            if sub.category in ("bus", "virtual bus"):
                results.append(sname)
        return sorted(results)

    def get_valid_base_types(self, category: str) -> List[str]:
        """实现可以引用的 base_type（同类别类型）"""
        cat = category.replace(" implementation", "").strip()
        results = []
        for name, info in self._sym.component_types.items():
            if info.category == cat or info.category == "abstract":
                results.append(name)
        return sorted(results)

    def get_valid_connection_sources(self, impl_name: str) -> List[str]:
        """连接的合法源端列表（子组件.out_port + 自身的 in_port）

        源端可以是：
        - 子组件的 out / in out 端口（格式：subcomp.port）
        - 实现自身的 in / in out 端口（格式：port_name，从外部引入）
        """
        impl = self._sym.component_impls.get(impl_name)
        if not impl:
            return []

        results = []

        # 子组件的 out / in out 端口
        for sname, sub in impl.subcomponents.items():
            type_info = self._sym.component_types.get(sub.classifier)
            if not type_info:
                # 试试 impl 名（可能引用的是实现）
                base_name = sub.classifier.split(".")[0] if "." in sub.classifier else sub.classifier
                type_info = self._sym.component_types.get(base_name)
            if type_info:
                for pname, pinfo in type_info.ports.items():
                    if pinfo.direction in ("out", "in out"):
                        results.append(f"{sname}.{pname}")

        # 自身的 in / in out 端口（从父组件传入）
        base_type = self._sym.component_types.get(impl.base_name)
        if base_type:
            for pname, pinfo in base_type.ports.items():
                if pinfo.direction in ("in", "in out"):
                    results.append(pname)

        return sorted(results)

    def get_valid_connection_targets(self, impl_name: str,
                                      source: str = "") -> List[str]:
        """连接的合法目标端列表

        目标端可以是：
        - 子组件的 in / in out 端口（格式：subcomp.port）
        - 实现自身的 out / in out 端口（格式：port_name，向外输出）
        """
        impl = self._sym.component_impls.get(impl_name)
        if not impl:
            return []

        results = []

        # 子组件的 in / in out 端口
        for sname, sub in impl.subcomponents.items():
            type_info = self._sym.component_types.get(sub.classifier)
            if not type_info:
                base_name = sub.classifier.split(".")[0] if "." in sub.classifier else sub.classifier
                type_info = self._sym.component_types.get(base_name)
            if type_info:
                for pname, pinfo in type_info.ports.items():
                    if pinfo.direction in ("in", "in out"):
                        results.append(f"{sname}.{pname}")

        # 自身的 out / in out 端口（向外输出）
        base_type = self._sym.component_types.get(impl.base_name)
        if base_type:
            for pname, pinfo in base_type.ports.items():
                if pinfo.direction in ("out", "in out"):
                    results.append(pname)

        # TODO: 如果有 source，还可以做类型匹配过滤
        return sorted(results)

    def get_used_component_names(self) -> Set[str]:
        """已使用的组件名"""
        names = set()
        for name in self._sym.component_types:
            names.add(name)
        for name in self._sym.component_impls:
            base = name.split(".")[0] if "." in name else name
            names.add(base)
        return names

    def get_used_port_names(self, type_name: str) -> Set[str]:
        """某类型已使用的端口名"""
        type_info = self._sym.component_types.get(type_name)
        if type_info:
            return set(type_info.ports.keys())
        return set()

    def get_used_subcomp_names(self, impl_name: str) -> Set[str]:
        """某实现已使用的子组件名"""
        impl = self._sym.component_impls.get(impl_name)
        if impl:
            return set(impl.subcomponents.keys())
        return set()

    def get_valid_flow_endpoints(self, type_name: str, flow_kind: str,
                                  is_source: bool = True) -> List[str]:
        """flow 的合法端点（端口名）
        - flow source: 端点必须是 out / in out 方向
        - flow sink: 端点必须是 in / in out 方向
        - flow path source: 端点必须是 out / in out 方向
        - flow path sink: 端点必须是 in / in out 方向
        """
        type_info = self._sym.component_types.get(type_name)
        if not type_info:
            return []
        # 判断方向要求
        if flow_kind == "flow source" or (flow_kind == "flow path" and is_source):
            allowed_dirs = ("out", "in out")
        elif flow_kind == "flow sink" or (flow_kind == "flow path" and not is_source):
            allowed_dirs = ("in", "in out")
        else:
            return []
        results = []
        for pname, pinfo in type_info.ports.items():
            if pinfo.direction in allowed_dirs:
                results.append(pname)
        return sorted(results)

    def get_valid_properties(self, category: str) -> List[str]:
        """某类别组件允许的属性名"""
        return sorted(get_valid_properties(category))

    def get_valid_port_properties(self, port_kind: str) -> List[str]:
        """某类端口允许的属性名"""
        return sorted(get_port_valid_properties(port_kind))


# ============================================================
# 扩展位置检测器
# ============================================================
class FullPositionDetector:
    """在原始位置检测器基础上，增加更多位置的识别。

    所有节位置判断都直接从文本扫描，不依赖符号表状态，
    这样即使原始 parser 不解析 flows/properties 节也能正常工作。
    """

    CAT_RE = (
        r'(?:system|process|thread|thread\s+group|subprogram|'
        r'subprogram\s+group|data|device|processor|'
        r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
    )

    def __init__(self, symbols: FullAadlSymbolTable):
        self.sym = symbols

    # ---- 辅助：从文本判断位置 ----

    def _is_in_section(self, lines: List[str], section_name: str) -> bool:
        """反向扫描，判断当前是否在某个 section 内

        AADL 的 section 是同级顺序排列的（features → flows → properties...），
        不是嵌套的。所以反向扫描时，遇到目标 section 名就 depth+1，
        遇到 end 就返回，遇到其他 section 名**不影响**（因为它们在目标 section 之前/之外）。
        """
        depth = 0
        for line in reversed(lines):
            stripped = line.strip()
            if not stripped or stripped.startswith("--"):
                continue
            # 组件结束（退出所有 section）
            if re.match(r'^end\s+\w+(?:\.\w+)?\s*;?\s*$', stripped):
                return depth > 0
            # section 开始
            if stripped == section_name:
                depth += 1
            # 其他 section 名忽略（同级顺序排列，不嵌套）
        return depth > 0

    def _get_current_type_name(self, lines: List[str]) -> Optional[str]:
        """反向扫描，找当前组件类型名
        支持 extends 继承语法：system Name extends Other.Name
        """
        for line in reversed(lines):
            stripped = line.strip()
            m = re.match(rf'^{self.CAT_RE}\s+(\w+)'
                         r'(?:\s+extends\s+[\w.:]+)?\s*;?\s*$', stripped)
            if m:
                # 排除 implementation 行
                if 'implementation' in stripped:
                    continue
                return m.group(1)
            if re.match(r'^end\s+\w+(?:\.\w+)?\s*;?\s*$', stripped):
                return None
        return None

    def _get_current_impl_name(self, lines: List[str]) -> Optional[str]:
        """反向扫描，找当前实现名
        支持 extends 继承语法：system implementation Name.Impl extends Other.Name
        """
        for line in reversed(lines):
            stripped = line.strip()
            # 匹配实现声明（可能带 extends）
            m = re.match(
                rf'^{self.CAT_RE}\s+implementation\s+(\w+\.\w+)'
                r'(?:\s+extends\s+[\w.:]+)?\s*;?\s*$',
                stripped
            )
            if m:
                return m.group(1)
            if re.match(r'^end\s+\w+(?:\.\w+)?\s*;?\s*$', stripped):
                return None
        return None

    def _is_in_component_type(self, lines: List[str]) -> bool:
        name = self._get_current_type_name(lines)
        return name is not None

    def _is_in_component_impl(self, lines: List[str]) -> bool:
        name = self._get_current_impl_name(lines)
        return name is not None

    def detect_extra(self, text: str) -> Tuple[Optional[str], dict]:
        """
        检测原始检测器不覆盖的位置。
        返回 (约束类型, 上下文) 或 (None, {})
        """
        lines = text.split('\n')
        if not lines:
            return None, {}

        last_line = lines[-1]

        # 从文本判断当前位置上下文
        in_features = self._is_in_section(lines, "features")
        in_flows = self._is_in_section(lines, "flows")
        in_properties = self._is_in_section(lines, "properties")
        in_connections = self._is_in_section(lines, "connections")
        in_subcomponents = self._is_in_section(lines, "subcomponents")
        current_type_name = self._get_current_type_name(lines)
        current_impl_name = self._get_current_impl_name(lines)

        # 1. 端口数据类型
        # 模式："  port_name: direction data port " 后面正在输入类型
        if in_features and current_type_name:
            port_dtype_match = re.match(
                r'^\s*(\w+)\s*:\s*(in|out|in\s+out)\s+'
                r'(data\s+port|event\s+data\s+port)\s+(\S*)$',
                last_line
            )
            if port_dtype_match:
                return ExtraConstraintField.PORT_DATA_TYPE, {
                    "port_name": port_dtype_match.group(1),
                    "direction": port_dtype_match.group(2).replace("  ", " ").strip(),
                    "port_kind": port_dtype_match.group(3).replace("  ", " ").strip(),
                    "prefix": port_dtype_match.group(4),
                }

        # 2. 实现的 base_type
        # 模式 A："  system implementation BaseNamePrefix" 正在输入 base name（还没打 .）
        impl_base_start_match = re.match(
            r'^\s*(system|process|thread|thread\s+group|subprogram|'
            r'subprogram\s+group|data|device|processor|'
            r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
            r'\s+implementation\s+(\S*)$',
            last_line
        )
        if impl_base_start_match:
            cat = impl_base_start_match.group(1).replace("  ", " ").strip()
            return ExtraConstraintField.IMPL_BASE_TYPE, {
                "category": cat,
                "prefix": impl_base_start_match.group(2),
            }

        # 模式 B："  system implementation BaseName.ImplNamePrefix" 正在输入 impl name
        # （这个模式不是 base_type 约束，是 impl name 的，暂不处理）

        # 3. 连接源端
        # 模式："  conn_name: port SourcePrefix" 或 "  conn_name: SourcePrefix"
        # 或 "  SourcePrefix"（无连接名）
        if in_connections and current_impl_name and '->' not in last_line:
            # 带连接类型："  c1: port SrcPrefix"
            conn_src_type_match = re.match(
                r'^\s*(\w+)\s*:\s*(?:port|event\s+port|event\s+data\s+port|'
                r'feature\s+group|parameter\s+port|access\s+connection)\s+'
                r'(\S*)$',
                last_line
            )
            if conn_src_type_match:
                return "conn_source", {
                    "impl_name": current_impl_name,
                    "conn_name": conn_src_type_match.group(1),
                    "prefix": conn_src_type_match.group(2),
                }

            # 不带连接类型："  c1: SrcPrefix"
            conn_src_match = re.match(
                r'^\s*(\w+)\s*:\s*(\S*)$',
                last_line
            )
            if conn_src_match and not last_line.strip().endswith(':'):
                return "conn_source", {
                    "impl_name": current_impl_name,
                    "conn_name": conn_src_match.group(1),
                    "prefix": conn_src_match.group(2),
                }

        # 4. 连接目标端
        # 模式："  ... -> TargetPrefix"
        if in_connections and current_impl_name and '->' in last_line:
            # 带连接类型："  c1: port src -> TgtPrefix"
            conn_tgt_type_match = re.match(
                r'^\s*(?:(\w+)\s*:\s*(?:port|event\s+port|event\s+data\s+port|'
                r'feature\s+group|parameter\s+port|access\s+connection)\s+)?'
                r'(\w+(?:\.\w+)*)\s*->\s*(\S*)$',
                last_line
            )
            if conn_tgt_type_match:
                return "conn_target", {
                    "impl_name": current_impl_name,
                    "conn_name": conn_tgt_type_match.group(1),
                    "source": conn_tgt_type_match.group(2),
                    "prefix": conn_tgt_type_match.group(3),
                }

        # 5. 连接 bound_to_bus
        # 模式："... -> ... bound to bus BusName" 正在输入 BusName
        if in_connections and current_impl_name:
            bound_bus_match = re.match(
                r'^.*bound\s+to\s+bus\s+(\S*)$',
                last_line,
                re.IGNORECASE
            )
            if bound_bus_match:
                return ExtraConstraintField.CONN_BOUND_BUS, {
                    "impl_name": current_impl_name,
                    "prefix": bound_bus_match.group(1),
                }

        # 6. Flow 端点
        # flow source
        flow_src_match = re.match(
            r'^\s*(\w+)\s*:\s*flow\s+source\s+(\S*)$',
            last_line
        )
        if flow_src_match and in_flows and current_type_name:
            return ExtraConstraintField.FLOW_ENDPOINT, {
                "flow_name": flow_src_match.group(1),
                "flow_kind": "flow source",
                "is_source": True,
                "type_name": current_type_name,
                "prefix": flow_src_match.group(2),
            }

        # flow sink
        flow_sink_match = re.match(
            r'^\s*(\w+)\s*:\s*flow\s+sink\s+(\S*)$',
            last_line
        )
        if flow_sink_match and in_flows and current_type_name:
            return ExtraConstraintField.FLOW_ENDPOINT, {
                "flow_name": flow_sink_match.group(1),
                "flow_kind": "flow sink",
                "is_source": False,
                "type_name": current_type_name,
                "prefix": flow_sink_match.group(2),
            }

        # flow path target
        flow_path_tgt_match = re.match(
            r'^\s*(\w+)\s*:\s*flow\s+path\s+(\w+)\s*->\s*(\S*)$',
            last_line
        )
        if flow_path_tgt_match and in_flows and current_type_name:
            return ExtraConstraintField.FLOW_ENDPOINT, {
                "flow_name": flow_path_tgt_match.group(1),
                "flow_kind": "flow path",
                "is_source": False,
                "source": flow_path_tgt_match.group(2),
                "type_name": current_type_name,
                "prefix": flow_path_tgt_match.group(3),
            }

        # flow path source
        flow_path_src_match = re.match(
            r'^\s*(\w+)\s*:\s*flow\s+path\s+(\S*)$',
            last_line
        )
        if (flow_path_src_match and '->' not in last_line
                and in_flows and current_type_name):
            return ExtraConstraintField.FLOW_ENDPOINT, {
                "flow_name": flow_path_src_match.group(1),
                "flow_kind": "flow path",
                "is_source": True,
                "type_name": current_type_name,
                "prefix": flow_path_src_match.group(2),
            }

        # 5. 属性名
        # 模式1："  PropName =>" 正在输入 PropName（还没写完）
        # 模式2："  Prop" 正在输入属性名前缀
        if in_properties:
            # 正在输入属性名（行首单词，后面可能有空格或 =>）
            prop_name_match = re.match(r'^\s*(\S*)\s*(?:=>.*)?$', last_line)
            if prop_name_match:
                prefix = prop_name_match.group(1)
                # 判断是组件属性还是实现属性
                if current_type_name:
                    type_info = self.sym.component_types.get(current_type_name.lower())
                    if type_info:
                        return ExtraConstraintField.PROPERTY_NAME, {
                            "category": type_info.category,
                            "is_port": False,
                            "prefix": prefix,
                        }
                elif current_impl_name:
                    impl = self.sym.component_impls.get(current_impl_name.lower())
                    if impl:
                        base_type = self.sym.component_types.get(impl.base_name.lower())
                        if base_type:
                            return ExtraConstraintField.PROPERTY_NAME, {
                                "category": base_type.category,
                                "is_port": False,
                                "prefix": prefix,
                            }

        # 6. 命名唯一性（组件名）— 只做检测，返回约束类型
        # 模式："  system " 后面正在输入名字
        if not current_type_name and not current_impl_name:
            comp_name_match = re.match(
                r'^\s*(system|process|thread|thread\s+group|subprogram|'
                r'subprogram\s+group|data|device|processor|'
                r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
                r'\s+(\S*)$',
                last_line
            )
            if comp_name_match:
                cat = comp_name_match.group(1).replace("  ", " ").strip()
                return ExtraConstraintField.COMPONENT_NAME, {
                    "category": cat,
                    "prefix": comp_name_match.group(2),
                }

        # 7. 子组件名（命名唯一性）
        if in_subcomponents and current_impl_name:
            # 只在只有名字和冒号时才匹配（还没输入类别）
            sub_name_only = re.match(r'^\s*(\w*)\s*:\s*$', last_line)
            if sub_name_only:
                return ExtraConstraintField.SUBCOMP_NAME, {
                    "impl_name": current_impl_name,
                    "prefix": sub_name_only.group(1),
                }

        return None, {}


# ============================================================
# 完整版约束引擎
# ============================================================
class FullAadlSemanticEngine:
    """
    完整版 AADL 语义约束引擎（12+ 约束，可配置化）。

    基于原始引擎扩展，保留原有 5 大约束，新增 7+ 约束。
    每条约束独立开关，支持对比实验和消融研究。
    """

    # 原始引擎约束字段 → 配置开关名
    _BASE_FIELD_TO_CONFIG = {
        ConstraintField.SUBCOMP_CLASSIFIER: "semantic_classifier_ref",
        # 注：SUBCOMP_CLASSIFIER 同时覆盖了包含规则，
        # 因为 get_valid_classifiers 内部会检查 containment
        ConstraintField.PORT_DATA_TYPE: "semantic_port_data_type",
        ConstraintField.CONN_SOURCE: "semantic_port_direction",
        ConstraintField.CONN_TARGET: "semantic_port_direction",
        # CONN_TARGET 同时覆盖了类型匹配（semantic_port_type_match）
        ConstraintField.BINDING_TARGET: "semantic_binding",
    }

    # 新增约束字段 → 配置开关名
    _EXTRA_FIELD_TO_CONFIG = {
        "port_data_type": "semantic_port_data_type",
        "impl_base_type": "semantic_impl_base_type",
        "conn_bound_bus": "semantic_conn_bound_bus",
        "flow_endpoint": "semantic_flow_endpoint",
        "property_name": "semantic_property_name",
        "component_name": "semantic_naming_unique",
        "port_name": "semantic_naming_unique",
        "subcomp_name": "semantic_naming_unique",
    }

    def __init__(self, config=None):
        """
        Args:
            config: ConstraintConfig 实例，None 表示全部启用（默认）
        """
        self.base_engine = AadlContextSensitiveEngine()
        self.symbols = FullAadlSymbolTable(self.base_engine.symbols)
        self.extra_detector = FullPositionDetector(self.symbols)

        # 配置：None 表示全部启用
        self.config = config  # ConstraintConfig 或 None

    def _is_constraint_enabled(self, config_key: str) -> bool:
        """检查某条约束是否启用"""
        if self.config is None:
            return True  # 无配置时全部启用
        return getattr(self.config, config_key, True)

    def prefill_from_arch_json(self, arch: dict):
        """预填充符号表"""
        self.base_engine.prefill_from_arch_json(arch)
        # 重新包装（因为 base_engine 的 symbols 引用可能没变，但保险起见）
        self.symbols = FullAadlSymbolTable(self.base_engine.symbols)
        self.extra_detector.sym = self.symbols

    def update(self, generated_text: str):
        """更新已生成文本"""
        self.base_engine.update(generated_text)
        self.symbols = FullAadlSymbolTable(self.base_engine.symbols)
        self.extra_detector.sym = self.symbols
        # 补充解析：原始 parser 不识别 extends 等语法，
        # 这里用正则扫描补充组件类型和实现到符号表
        self._augment_symbol_table(generated_text)

    def _augment_symbol_table(self, text: str):
        """补充原始 parser 没解析到的符号（带 extends 的实现等）"""
        from symbol_table import ComponentTypeInfo, ComponentImplInfo, SubcomponentInfo

        lines = text.split("\n")
        current_type_cat = None
        current_type_name = None
        current_impl_name = None
        current_impl_cat = None
        current_impl_base = None

        for line in lines:
            stripped = line.strip()
            if not stripped or stripped.startswith("--"):
                continue

            # 组件类型（带 extends）
            m = re.match(
                r'^(system|process|thread|thread\s+group|subprogram|'
                r'subprogram\s+group|data|device|processor|'
                r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
                r'\s+(\w+)(?:\s+extends\s+[\w.:]+)?\s*$',
                stripped, re.IGNORECASE)
            if m and 'implementation' not in stripped.lower():
                cat = m.group(1).replace("  ", " ").strip().lower()
                name = m.group(2).lower()
                if name not in self.symbols._sym.component_types:
                    self.symbols._sym.component_types[name] = ComponentTypeInfo(
                        name=name, category=cat
                    )
                current_type_name = name
                current_type_cat = cat
                current_impl_name = None
                continue

            # 组件实现（带 extends）
            m = re.match(
                r'^(system|process|thread|thread\s+group|subprogram|'
                r'subprogram\s+group|data|device|processor|'
                r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
                r'\s+implementation\s+(\w+)\.(\w+)'
                r'(?:\s+extends\s+[\w.:]+)?\s*;?\s*$',
                stripped, re.IGNORECASE)
            if m:
                cat = m.group(1).replace("  ", " ").strip().lower()
                base = m.group(2).lower()
                impl = m.group(3).lower()
                full_name = f"{base}.{impl}"
                if full_name not in self.symbols._sym.component_impls:
                    self.symbols._sym.component_impls[full_name] = ComponentImplInfo(
                        name=impl,
                        category=cat,
                        base_name=base,
                    )
                # 确保 base type 也存在
                if base not in self.symbols._sym.component_types:
                    self.symbols._sym.component_types[base] = ComponentTypeInfo(
                        name=base, category=cat
                    )
                current_impl_name = full_name
                current_impl_cat = cat
                current_impl_base = base
                current_type_name = None
                continue

            # end 退出
            if re.match(r'^end\s+\w+(?:\.\w+)?\s*;?\s*$', stripped, re.IGNORECASE):
                current_type_name = None
                current_impl_name = None
                continue

    def get_valid_values(self, generated_text: str) -> Optional[List[str]]:
        """获取当前位置的合法值列表

        优先级：新增约束 > 原始引擎约束。
        因为新增检测器基于文本模式匹配，对 properties/flows 等原始 parser
        不识别的节也能正确判断。

        如果对应约束在配置中被关闭，返回 None（不约束）。
        """
        # 先查新增约束（优先级更高，因为位置检测更准确）
        self.update(generated_text)
        field_type, context = self.extra_detector.detect_extra(generated_text)
        if field_type is not None:
            config_key = self._EXTRA_FIELD_TO_CONFIG.get(
                field_type, ""
            )
            if config_key and not self._is_constraint_enabled(config_key):
                return None  # 约束被关闭，不限制
            return self._resolve_extra_constraint(field_type, context)

        # 再查原始引擎的 5 大约束
        base_field, base_ctx = self.base_engine.get_current_constraint(
            generated_text
        )
        if base_field is None:
            return None

        config_key = self._BASE_FIELD_TO_CONFIG.get(base_field, "")
        if config_key and not self._is_constraint_enabled(config_key):
            return None  # 约束被关闭

        # CONN_TARGET 同时涉及方向和类型匹配，需要分别检查
        if base_field == ConstraintField.CONN_TARGET:
            # 如果方向约束被关闭但类型匹配还开着，需要特殊处理
            # 简化：只要其中一个开着就返回约束（粒度由配置控制）
            dir_enabled = self._is_constraint_enabled("semantic_port_direction")
            type_enabled = self._is_constraint_enabled("semantic_port_type_match")
            if not dir_enabled and not type_enabled:
                return None
            # 如果只开类型匹配，返回的是类型过滤后的目标列表（已经包含了方向过滤）
            # 要精确控制需要改原始引擎，这里简化处理

        return self.base_engine.get_valid_values(generated_text)

    def _resolve_extra_constraint(self, field_type: str, context: dict) -> Optional[List[str]]:
        """解析新增约束，返回合法值列表"""
        if field_type == "conn_source":
            impl_name = context.get("impl_name", "")
            if not self._is_constraint_enabled("semantic_port_direction"):
                return None
            return self.symbols.get_valid_connection_sources(impl_name)

        elif field_type == "conn_target":
            impl_name = context.get("impl_name", "")
            if not self._is_constraint_enabled("semantic_port_direction"):
                return None
            return self.symbols.get_valid_connection_targets(impl_name)

        elif field_type == ExtraConstraintField.PORT_DATA_TYPE:
            return self.symbols.get_valid_data_types()

        elif field_type == ExtraConstraintField.IMPL_BASE_TYPE:
            cat = context.get("category", "")
            return self.symbols.get_valid_base_types(cat)

        elif field_type == ExtraConstraintField.CONN_BOUND_BUS:
            impl_name = context.get("impl_name", "")
            return self.symbols.get_valid_bound_buses(impl_name)

        elif field_type == ExtraConstraintField.FLOW_ENDPOINT:
            type_name = context.get("type_name", "")
            flow_kind = context.get("flow_kind", "")
            is_source = context.get("is_source", True)
            return self.symbols.get_valid_flow_endpoints(
                type_name, flow_kind, is_source
            )

        elif field_type == ExtraConstraintField.PROPERTY_NAME:
            cat = context.get("category", "")
            if cat:
                return self.symbols.get_valid_properties(cat)
            return None

        # 命名唯一性约束不做正向枚举（名字空间太大）
        # 可以在 logits processor 中做负向过滤（屏蔽已使用的名字）
        elif field_type in (ExtraConstraintField.COMPONENT_NAME,
                            ExtraConstraintField.PORT_NAME,
                            ExtraConstraintField.SUBCOMP_NAME):
            return None  # 命名唯一性用负向过滤

        return None

    def get_current_prefix(self, generated_text: str) -> str:
        """获取当前输入值的前缀"""
        # 先查新增检测器
        self.update(generated_text)
        _, ctx = self.extra_detector.detect_extra(generated_text)
        if ctx.get("prefix"):
            return ctx["prefix"]
        # 再查原始引擎
        return self.base_engine.get_current_prefix(generated_text)

    def get_logits_processor(self, tokenizer):
        """创建语义约束 logits processor"""
        engine = self

        class FullSemanticLogitsProcessor:
            def __init__(self, tokenizer, engine):
                self.tokenizer = tokenizer
                self.engine = engine

            def __call__(self, input_ids, scores):
                import torch

                if input_ids.dim() == 2:
                    ids = input_ids[0]
                else:
                    ids = input_ids

                text = self.tokenizer.decode(ids, skip_special_tokens=True)

                valid_values = self.engine.get_valid_values(text)
                if valid_values is None:
                    return scores

                if not valid_values:
                    scores.fill_(float('-inf'))
                    return scores

                prefix = self.engine.get_current_prefix(text)
                valid_ids = set()
                for val in valid_values:
                    if prefix and not val.startswith(prefix):
                        continue
                    remaining = val[len(prefix):]
                    if not remaining:
                        continue
                    try:
                        tok_ids = self.tokenizer.encode(
                            remaining, add_special_tokens=False
                        )
                        if tok_ids:
                            valid_ids.add(tok_ids[0])
                    except Exception:
                        pass

                if not valid_ids:
                    return scores

                vocab_size = scores.size(-1)
                mask = torch.ones(vocab_size, dtype=torch.bool,
                                  device=scores.device)
                for tid in valid_ids:
                    if tid < vocab_size:
                        mask[tid] = False
                scores = scores.masked_fill(mask, float('-inf'))

                return scores

        return FullSemanticLogitsProcessor(tokenizer, engine)
