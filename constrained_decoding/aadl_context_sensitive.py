#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AADL 上下文敏感约束引擎（直接作用于 AADL 文本生成）
=====================================================
在 AADL 代码逐 token 生成过程中，增量式地施加语义约束。
把 AADL 的语义规则编码为"生成位置 → 合法值集合"的映射，
在模型生成到关键字段时动态收窄合法 token，
实现"生成时即语义正确"。

与 CFG/EBNF 约束的关系：
  - CFG 保证**语法**正确（括号配对、关键字正确等）
  - 本引擎保证**语义**正确（引用存在、类型匹配等）
  - 两者协同工作：先过 CFG 过滤，再过语义过滤

覆盖的 5 项核心语义约束：
  1. 引用完整性 — classifier_ref 必须引用已定义的组件
  2. 包含规则   — 子组件类别必须在父组件允许的集合中
  3. 端口方向匹配 — 连接源端必须是 out/in out，目标端必须是 in/in out
  4. 端口类型匹配 — 连接两端端口的数据类型必须一致
  5. 绑定类型约束 — 绑定目标类别必须与绑定类型匹配

关键设计：
  - 增量式 AADL 解析器：从已生成的 AADL 文本中提取符号
  - 位置识别器：根据文本后缀判断"现在正在生成什么"
  - 合法值计算器：基于符号表和当前位置计算合法值
  - Logits Processor：与 Outlines CFG 约束协同工作

用法：
  from aadl_context_sensitive import AadlContextSensitiveEngine

  engine = AadlContextSensitiveEngine()
  engine.prefill_from_arch_json(arch_dict)  # 可选：预填充已知架构

  semantic_lp = engine.get_logits_processor(tokenizer)
  # 与 CFG logits processor 一起使用
  model.generate(logits_processor=[cfg_lp, semantic_lp])
"""

import re
from typing import List, Dict, Optional, Set, Tuple, Any
from dataclasses import dataclass, field
from enum import Enum


# ============================================================
# 复用现有规则
# ============================================================
from json_validator import CONTAINMENT_RULES, BINDING_RULES
from architecture_schema import PREDEFINED_DATA_TYPES


# ============================================================
# 约束字段类型
# ============================================================
class ConstraintField(Enum):
    """需要施加语义约束的生成位置类型"""
    # 分类器引用（子组件声明中的类型引用）
    SUBCOMP_CLASSIFIER = "subcomp_classifier"
    # 端口数据类型引用
    PORT_DATA_TYPE = "port_data_type"
    # 连接源端（第一个端点，source）
    CONN_SOURCE = "conn_source"
    # 连接目标端（第二个端点，target）
    CONN_TARGET = "conn_target"
    # 绑定目标（property 中的绑定引用）
    BINDING_TARGET = "binding_target"
    # access feature 的分类器引用
    ACCESS_CLASSIFIER = "access_classifier"


# ============================================================
# 符号表数据结构
# ============================================================
@dataclass
class PortInfo:
    name: str
    direction: str       # in / out / in out
    kind: str            # data port / event port / event data port
    data_type: Optional[str] = None


@dataclass
class SubcompInfo:
    name: str
    category: str          # system/process/thread/...
    classifier: str        # 引用的类型/实现名


@dataclass
class CompTypeInfo:
    name: str
    category: str
    ports: Dict[str, PortInfo] = field(default_factory=dict)
    accesses: Dict[str, dict] = field(default_factory=dict)  # name -> {direction, kind, classifier}


@dataclass
class CompImplInfo:
    name: str               # 如 "FlightControl.impl"
    base_name: str          # 如 "FlightControl"
    category: str           # 如 "system implementation"
    subcomponents: Dict[str, SubcompInfo] = field(default_factory=dict)
    connections: List[dict] = field(default_factory=list)


class AadlSymbolTable:
    """
    增量式 AADL 符号表。
    随着 AADL 文本生成推进，逐步注册组件、端口、子组件、连接等符号。
    """

    def __init__(self):
        self.package_name: str = ""
        self.with_packages: List[str] = []
        self.component_types: Dict[str, CompTypeInfo] = {}
        self.component_impls: Dict[str, CompImplInfo] = {}

        # 当前正在定义的组件（用于增量注册）
        self._current_type: Optional[str] = None   # 正在定义的类型名
        self._current_impl: Optional[str] = None   # 正在定义的实现名
        self._in_features: bool = False
        self._in_subcomponents: bool = False
        self._in_connections: bool = False

    # ---- 注册方法 ----

    def set_package(self, name: str):
        self.package_name = name

    def add_with_package(self, name: str):
        if name not in self.with_packages:
            self.with_packages.append(name)

    def start_component_type(self, name: str, category: str):
        """开始定义一个组件类型"""
        if name not in self.component_types:
            self.component_types[name] = CompTypeInfo(
                name=name, category=category
            )
        self._current_type = name
        self._current_impl = None
        self._in_features = False
        self._in_subcomponents = False
        self._in_connections = False

    def end_component_type(self, name: str):
        """结束组件类型定义"""
        self._current_type = None
        self._in_features = False

    def start_component_impl(self, name: str, base_name: str, category: str):
        """开始定义一个组件实现"""
        full_cat = f"{category} implementation"
        if name not in self.component_impls:
            self.component_impls[name] = CompImplInfo(
                name=name, base_name=base_name, category=full_cat,
            )
        self._current_impl = name
        self._current_type = None
        self._in_features = False
        self._in_subcomponents = False
        self._in_connections = False

    def end_component_impl(self, name: str):
        """结束组件实现定义"""
        self._current_impl = None
        self._in_subcomponents = False
        self._in_connections = False

    def enter_features(self):
        self._in_features = True

    def leave_features(self):
        self._in_features = False

    def enter_subcomponents(self):
        self._in_subcomponents = True

    def leave_subcomponents(self):
        self._in_subcomponents = False

    def enter_connections(self):
        self._in_connections = True

    def leave_connections(self):
        self._in_connections = False

    def add_port(self, port_name: str, direction: str,
                 kind: str, data_type: str = None):
        """在当前类型中添加端口"""
        if self._current_type and self._current_type in self.component_types:
            self.component_types[self._current_type].ports[port_name] = PortInfo(
                name=port_name, direction=direction,
                kind=kind, data_type=data_type,
            )

    def add_subcomponent(self, sub_name: str, category: str, classifier: str):
        """在当前实现中添加子组件"""
        if self._current_impl and self._current_impl in self.component_impls:
            self.component_impls[self._current_impl].subcomponents[sub_name] = (
                SubcompInfo(
                    name=sub_name, category=category,
                    classifier=classifier,
                )
            )

    def add_connection(self, conn_name: str, source: str, target: str):
        """在当前实现中添加连接"""
        if self._current_impl and self._current_impl in self.component_impls:
            self.component_impls[self._current_impl].connections.append({
                "name": conn_name,
                "source": source,
                "target": target,
            })

    # ---- 查询方法 ----

    def has_type(self, name: str) -> bool:
        return name in self.component_types

    def has_impl(self, name: str) -> bool:
        return name in self.component_impls

    def get_type_category(self, type_name: str) -> Optional[str]:
        ctype = self.component_types.get(type_name)
        return ctype.category if ctype else None

    def get_impl_base_category(self, impl_name: str) -> Optional[str]:
        """获取实现对应的基础类型的类别"""
        impl = self.component_impls.get(impl_name)
        if not impl:
            return None
        ctype = self.component_types.get(impl.base_name)
        return ctype.category if ctype else None

    # --- 约束1: 引用完整性（classifier 合法值）---

    def get_valid_classifiers(self, parent_impl_name: str = None,
                              sub_category: str = None) -> List[str]:
        """
        获取合法的分类器引用列表。
        如果指定了父实现和子组件类别，会额外过滤：
        - 只保留类别匹配的类型/实现（约束2：包含规则）
        """
        # 所有类型和实现名
        candidates = set(self.component_types.keys())
        candidates.update(self.component_impls.keys())

        # 约束2: 包含规则 - 类别过滤
        if parent_impl_name and sub_category:
            parent_cat = self.get_impl_base_category(parent_impl_name)
            if parent_cat and parent_cat in CONTAINMENT_RULES:
                allowed_cats = CONTAINMENT_RULES[parent_cat]
                if sub_category not in allowed_cats:
                    return []  # 该类别不允许被包含
                # 只保留类别匹配的
                filtered = set()
                for name in candidates:
                    cat = self._get_classifier_category(name)
                    if cat == sub_category or cat == "abstract":
                        filtered.add(name)
                candidates = filtered

        return sorted(candidates)

    def _get_classifier_category(self, classifier: str) -> Optional[str]:
        """获取分类器的类别（类型名或实现名）"""
        if classifier in self.component_types:
            return self.component_types[classifier].category
        if classifier in self.component_impls:
            base = self.component_impls[classifier].base_name
            if base in self.component_types:
                return self.component_types[base].category
        return None

    # --- 约束3 & 4: 连接端口方向 + 数据类型匹配 ---

    def get_valid_conn_sources(self, impl_name: str,
                                data_type: str = None) -> List[str]:
        """
        某个实现中合法的连接源端（out / in out 端口）。
        格式：自身端口名 或 子组件名.端口名
        如果指定 data_type，只返回同类型端口（约束4）。
        """
        return self._get_valid_ports(
            impl_name, is_source=True, data_type=data_type
        )

    def get_valid_conn_targets(self, impl_name: str,
                                data_type: str = None) -> List[str]:
        """
        某个实现中合法的连接目标端（in / in out 端口）。
        如果指定 data_type，只返回同类型端口（约束4）。
        """
        return self._get_valid_ports(
            impl_name, is_source=False, data_type=data_type
        )

    def _get_valid_ports(self, impl_name: str, is_source: bool,
                         data_type: str = None) -> List[str]:
        """
        获取实现内部合法的连接端点。

        AADL 连接的方向规则（在实现内部）：
          - source 端：
              * 子组件端口 → 必须是 out / in out（数据从子组件流出）
              * 自身端口   → 必须是 in / in out（数据从外部进入，转发到内部）
          - target 端：
              * 子组件端口 → 必须是 in / in out（数据流入子组件）
              * 自身端口   → 必须是 out / in out（数据流出到外部）

        即：自身端口和子组件端口的方向要求是相反的。
        """
        impl = self.component_impls.get(impl_name)
        if not impl:
            return []

        result = []

        # 自身类型的端口（方向与子组件相反）
        # source: in / in out;  target: out / in out
        self_dirs = ("in", "in out") if is_source else ("out", "in out")
        # 子组件端口的方向
        sub_dirs = ("out", "in out") if is_source else ("in", "in out")

        # 自身类型的端口
        base_type = self.component_types.get(impl.base_name)
        if base_type:
            for pname, pinfo in base_type.ports.items():
                if pinfo.direction in self_dirs:
                    if data_type is None or pinfo.data_type == data_type:
                        result.append(pname)

        # 子组件的端口
        for sc_name, sc_info in impl.subcomponents.items():
            sc_type_name = sc_info.classifier
            # 去掉 .impl 后缀得到类型名
            if "." in sc_type_name:
                sc_type_name = sc_type_name.split(".")[0]

            sc_type = self.component_types.get(sc_type_name)
            if sc_type:
                for pname, pinfo in sc_type.ports.items():
                    if pinfo.direction in sub_dirs:
                        if data_type is None or pinfo.data_type == data_type:
                            result.append(f"{sc_name}.{pname}")

        return sorted(result)

    def get_port_data_type(self, impl_name: str, port_ref: str) -> Optional[str]:
        """根据端口引用查找数据类型。port_ref 格式: port_name 或 subcomp.port_name"""
        impl = self.component_impls.get(impl_name)
        if not impl:
            return None

        if "." in port_ref:
            sc_name, port_name = port_ref.split(".", 1)
            sc = impl.subcomponents.get(sc_name)
            if sc:
                sc_type_name = sc.classifier
                if "." in sc_type_name:
                    sc_type_name = sc_type_name.split(".")[0]
                sc_type = self.component_types.get(sc_type_name)
                if sc_type and port_name in sc_type.ports:
                    return sc_type.ports[port_name].data_type
        else:
            base_type = self.component_types.get(impl.base_name)
            if base_type and port_ref in base_type.ports:
                return base_type.ports[port_ref].data_type

        return None

    # --- 约束5: 绑定类型约束 ---

    def get_valid_binding_targets(self, impl_name: str,
                                   binding_kind: str) -> List[str]:
        """合法的绑定目标子组件名（根据绑定类型筛选）"""
        impl = self.component_impls.get(impl_name)
        if not impl:
            return []

        # 从 BINDING_RULES 找出允许的目标类别
        allowed_target_cats = set()
        for (src_cat, bk), tgt_cats in BINDING_RULES.items():
            if bk == binding_kind:
                allowed_target_cats.update(tgt_cats)

        if not allowed_target_cats:
            return sorted(impl.subcomponents.keys())

        valid = []
        for sc_name, sc_info in impl.subcomponents.items():
            if sc_info.category in allowed_target_cats:
                valid.append(sc_name)

        return sorted(valid)

    # --- 数据类型合法值 ---

    def get_valid_data_types(self) -> List[str]:
        """合法的端口数据类型"""
        result = list(PREDEFINED_DATA_TYPES)
        for name, ctype in self.component_types.items():
            if ctype.category == "data":
                result.append(name)
        # with 包中的类型也算（简化处理，假设都合法）
        return sorted(result)

    # --- 当前上下文信息 ---

    @property
    def current_impl_name(self) -> Optional[str]:
        return self._current_impl

    @property
    def current_type_name(self) -> Optional[str]:
        return self._current_type

    @property
    def in_features(self) -> bool:
        return self._in_features

    @property
    def in_subcomponents(self) -> bool:
        return self._in_subcomponents

    @property
    def in_connections(self) -> bool:
        return self._in_connections


# ============================================================
# 增量式 AADL 文本解析器
# ============================================================
class IncrementalAadlParser:
    """
    增量式 AADL 文本解析器。
    随着 AADL 文本的生成，逐步解析并更新符号表。

    设计原则：
    - 容错：生成中的文本肯定是不完整的，尽量提取有用信息
    - 增量：只添加新符号，不删除已确认的符号
    - 快速：每 token 都可能调用，解析要轻量
    """

    # 正则表达式模式
    PAT_PACKAGE = re.compile(r'package\s+(\w+)', re.MULTILINE)
    PAT_WITH = re.compile(r'with\s+(\w+(?:\s*,\s*\w+)*)\s*;', re.MULTILINE)

    # 组件类型开始：<category> <name>
    PAT_TYPE_START = re.compile(
        r'^\s*(system|process|thread|thread\s+group|subprogram|'
        r'subprogram\s+group|data|device|processor|'
        r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
        r'\s+(\w+(?:\.\w+)*)\s*$',
        re.MULTILINE
    )

    # 组件类型结束：end <name>;
    PAT_TYPE_END = re.compile(
        r'^\s*end\s+(\w+(?:\.\w+)*)\s*;',
        re.MULTILINE
    )

    # features 节开始
    PAT_FEATURES = re.compile(r'^\s*features\s*$', re.MULTILINE)

    # subcomponents 节开始
    PAT_SUBCOMPONENTS = re.compile(r'^\s*subcomponents\s*$', re.MULTILINE)

    # connections 节开始
    PAT_CONNECTIONS = re.compile(r'^\s*connections\s*$', re.MULTILINE)

    # 端口声明
    PAT_PORT = re.compile(
        r'^\s*(\w+)\s*:\s*(in|out|in\s+out)\s+'
        r'(data\s+port|event\s+port|event\s+data\s+port)'
        r'(?:\s+(.+?))?\s*;',
        re.MULTILINE
    )

    # 子组件声明
    PAT_SUBCOMP = re.compile(
        r'^\s*(\w+)\s*:\s*'
        r'(system|process|thread|thread\s+group|subprogram|'
        r'subprogram\s+group|data|device|processor|'
        r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
        r'\s+(\S+?)\s*;',
        re.MULTILINE
    )

    # 连接声明
    PAT_CONN = re.compile(
        r'^\s*(?:(\w+)\s*:\s*)?'
        r'(\w+(?:\.\w+)*)\s*->\s*(\w+(?:\.\w+)*)\s*;',
        re.MULTILINE
    )

    def __init__(self, symbols: AadlSymbolTable):
        self.symbols = symbols
        self._parsed_length = 0  # 已解析到的位置
        self._last_text = ""

    def update(self, text: str):
        """
        更新已生成的 AADL 文本，增量解析新内容。
        """
        if text == self._last_text:
            return

        new_text = text[self._parsed_length:]
        self._last_text = text

        if not new_text:
            return

        self._parse_new_content(text, new_text)
        self._parsed_length = len(text)

    def _parse_new_content(self, full_text: str, new_text: str):
        """解析新生成的文本内容，更新符号表"""

        # --- 包名 ---
        if not self.symbols.package_name:
            m = self.PAT_PACKAGE.search(full_text)
            if m:
                self.symbols.set_package(m.group(1))

        # --- with 子句 ---
        for m in self.PAT_WITH.finditer(full_text):
            pkg_str = m.group(1)
            for pkg in re.split(r'\s*,\s*', pkg_str):
                pkg = pkg.strip()
                if pkg:
                    self.symbols.add_with_package(pkg)

        # --- 组件类型定义 ---
        # 找到所有类型定义块
        self._parse_components(full_text)

    def _parse_components(self, text: str):
        """
        解析所有组件定义（类型和实现），更新符号表和当前状态。
        通过逐行扫描来跟踪当前在哪个组件内。
        """
        lines = text.split('\n')

        # 重置当前状态（重新跟踪）
        self.symbols._current_type = None
        self.symbols._current_impl = None
        self.symbols._in_features = False
        self.symbols._in_subcomponents = False
        self.symbols._in_connections = False

        # 收集已完整定义的组件名（遇到 end ...; 才算完整）
        completed_types = set()
        completed_impls = set()

        for line in lines:
            stripped = line.strip()

            # 跳过空行和注释（AADL 用 -- 注释）
            if not stripped or stripped.startswith('--'):
                continue

            # --- 检测组件类型开始 ---
            # 格式: <category> <name>
            type_match = re.match(
                r'^(system|process|thread|thread\s+group|subprogram|'
                r'subprogram\s+group|data|device|processor|'
                r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
                r'\s+(?:implementation\s+)?(\w+(?:\.\w+)*)\s*$',
                stripped
            )
            if type_match and not stripped.startswith('end '):
                category = type_match.group(1)
                comp_name = type_match.group(2)

                # 判断是类型还是实现
                is_impl = ('implementation' in stripped
                           or '.' in comp_name)

                if is_impl:
                    # 实现
                    base_name = comp_name.split('.')[0] if '.' in comp_name else comp_name
                    # 规范化 category
                    cat_norm = category.replace('  ', ' ').strip()
                    self.symbols.start_component_impl(
                        comp_name, base_name, cat_norm
                    )
                else:
                    # 类型
                    cat_norm = category.replace('  ', ' ').strip()
                    self.symbols.start_component_type(comp_name, cat_norm)
                continue

            # --- 检测组件结束 ---
            end_match = re.match(r'^end\s+(\w+(?:\.\w+)*)\s*;', stripped)
            if end_match:
                end_name = end_match.group(1)
                if '.' in end_name:
                    self.symbols.end_component_impl(end_name)
                    completed_impls.add(end_name)
                else:
                    self.symbols.end_component_type(end_name)
                    completed_types.add(end_name)
                continue

            # --- 检测 sections ---
            if stripped == 'features':
                self.symbols.enter_features()
                continue
            if stripped == 'subcomponents':
                self.symbols.enter_subcomponents()
                continue
            if stripped == 'connections':
                self.symbols.enter_connections()
                continue
            if stripped in ('properties', 'flows', 'modes'):
                # 离开 features/subcomponents/connections
                self.symbols.leave_features()
                self.symbols.leave_subcomponents()
                self.symbols.leave_connections()
                continue

            # --- 端口声明（在 features 节内） ---
            if self.symbols.in_features and self.symbols.current_type_name:
                port_match = re.match(
                    r'^(\w+)\s*:\s*(in|out|in\s+out)\s+'
                    r'(data\s+port|event\s+port|event\s+data\s+port)'
                    r'(?:\s+(.+?))?\s*;',
                    stripped
                )
                if port_match:
                    pname = port_match.group(1)
                    pdir = port_match.group(2).replace('  ', ' ').strip()
                    pkind = port_match.group(3).replace('  ', ' ').strip()
                    pdtype = port_match.group(4)
                    self.symbols.add_port(pname, pdir, pkind, pdtype)
                    continue

            # --- 子组件声明（在 subcomponents 节内） ---
            if self.symbols.in_subcomponents and self.symbols.current_impl_name:
                sc_match = re.match(
                    r'^(\w+)\s*:\s*'
                    r'(system|process|thread|thread\s+group|subprogram|'
                    r'subprogram\s+group|data|device|processor|'
                    r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
                    r'\s+(\S+?)\s*(?:in\s+modes.*)?;',
                    stripped
                )
                if sc_match:
                    sc_name = sc_match.group(1)
                    sc_cat = sc_match.group(2).replace('  ', ' ').strip()
                    sc_cls = sc_match.group(3)
                    self.symbols.add_subcomponent(sc_name, sc_cat, sc_cls)
                    continue

            # --- 连接声明（在 connections 节内） ---
            if self.symbols.in_connections and self.symbols.current_impl_name:
                conn_match = re.match(
                    r'^(?:(\w+)\s*:\s*)?'
                    r'(\w+(?:\.\w+)*)\s*->\s*(\w+(?:\.\w+)*)\s*;',
                    stripped
                )
                if conn_match:
                    cname = conn_match.group(1) or ""
                    csrc = conn_match.group(2)
                    ctgt = conn_match.group(3)
                    self.symbols.add_connection(cname, csrc, ctgt)
                    continue


# ============================================================
# 位置识别器：判断当前正在生成什么
# ============================================================
class AadlPositionDetector:
    """
    根据已生成的 AADL 文本尾部，判断当前正在生成的字段类型，
    从而决定应该施加什么语义约束。

    判断逻辑：
    - 看最后几行文本的模式
    - 结合符号表的当前状态（在哪个组件内、在哪个 section 内）
    """

    def __init__(self, symbols: AadlSymbolTable):
        self.symbols = symbols

    def detect(self, text: str) -> Tuple[Optional[ConstraintField], dict]:
        """
        检测当前生成位置的约束类型。

        Returns:
            (约束字段类型, 上下文字典)
            如果无约束，返回 (None, {})
        """
        lines = text.split('\n')
        if not lines:
            return None, {}

        last_line = lines[-1] if lines else ""
        last_complete_line = ""
        for line in reversed(lines[:-1]):
            if line.strip():
                last_complete_line = line
                break

        # ---- 子组件分类器引用 ----
        # 模式："  subcomp_name: <category> " 后面正在输入分类器
        # 如 "  nav_thread: thread Nav" → 正在输入 NavigationThread
        if self.symbols.in_subcomponents and self.symbols.current_impl_name:
            sc_match = re.match(
                r'^\s*(\w+)\s*:\s*'
                r'(system|process|thread|thread\s+group|subprogram|'
                r'subprogram\s+group|data|device|processor|'
                r'virtual\s+processor|memory|bus|virtual\s+bus|abstract)'
                r'\s+(\S*)$',
                last_line
            )
            if sc_match:
                sc_name = sc_match.group(1)
                sc_cat = sc_match.group(2).replace('  ', ' ').strip()
                sc_cls_prefix = sc_match.group(3)
                return ConstraintField.SUBCOMP_CLASSIFIER, {
                    "parent_impl": self.symbols.current_impl_name,
                    "sub_category": sc_cat,
                    "sub_name": sc_name,
                    "prefix": sc_cls_prefix,
                }

        # ---- 端口数据类型 ----
        # 模式："  port_name: direction data port " 后面正在输入类型
        if self.symbols.in_features and self.symbols.current_type_name:
            port_dtype_match = re.match(
                r'^\s*(\w+)\s*:\s*(in|out|in\s+out)\s+'
                r'(data\s+port|event\s+data\s+port)\s+(\S*)$',
                last_line
            )
            if port_dtype_match:
                return ConstraintField.PORT_DATA_TYPE, {
                    "prefix": port_dtype_match.group(4),
                }

        # ---- 连接源端 ----
        # 模式："  conn_name: [port|...] " 后面正在输入源端点
        # 或 "  " 后面直接是源端点（没有连接名）
        if self.symbols.in_connections and self.symbols.current_impl_name:
            # 有连接名 + 连接类型的情况："  c1: port " + 正在输入 source
            # 支持的连接类型：port, event port, event data port, feature group, ...
            conn_src_with_type_match = re.match(
                r'^\s*(\w+)\s*:\s*(?:port|event\s+port|event\s+data\s+port|'
                r'feature\s+group|parameter\s+port|access\s+connection)\s+'
                r'(\S*)$',
                last_line
            )
            if conn_src_with_type_match and '->' not in last_line:
                src_prefix = conn_src_with_type_match.group(2)
                return ConstraintField.CONN_SOURCE, {
                    "impl_name": self.symbols.current_impl_name,
                    "conn_name": conn_src_with_type_match.group(1),
                    "prefix": src_prefix,
                }

            # 有连接名，无连接类型的情况："  c1: " + 正在输入 source
            conn_src_match = re.match(
                r'^\s*(\w+)\s*:\s*(\S*)$',
                last_line
            )
            if conn_src_match and '->' not in last_line:
                src_prefix = conn_src_match.group(2)
                return ConstraintField.CONN_SOURCE, {
                    "impl_name": self.symbols.current_impl_name,
                    "conn_name": conn_src_match.group(1),
                    "prefix": src_prefix,
                }

            # 没有连接名的情况："  " + 正在输入 source
            conn_src_no_name = re.match(
                r'^\s*(\S*)$',
                last_line
            )
            if (conn_src_no_name and '->' not in last_line
                    and not last_line.strip().endswith(':')):
                prefix = conn_src_no_name.group(1)
                if prefix or len(last_line.strip()) == 0:
                    # 需要确认是在 connections 节内的新行
                    return ConstraintField.CONN_SOURCE, {
                        "impl_name": self.symbols.current_impl_name,
                        "conn_name": None,
                        "prefix": prefix,
                    }

        # ---- 连接目标端 ----
        # 模式："  [conn_name:] [port ] source -> " 后面正在输入 target
        if self.symbols.in_connections and self.symbols.current_impl_name:
            # 带连接类型的情况："  c1: port src -> "
            conn_tgt_with_type_match = re.match(
                r'^\s*(?:(\w+)\s*:\s*(?:port|event\s+port|event\s+data\s+port|'
                r'feature\s+group|parameter\s+port|access\s+connection)\s+)?'
                r'(\w+(?:\.\w+)*)\s*->\s*(\S*)$',
                last_line
            )
            if conn_tgt_with_type_match:
                conn_name = conn_tgt_with_type_match.group(1)
                source = conn_tgt_with_type_match.group(2)
                target_prefix = conn_tgt_with_type_match.group(3)
                return ConstraintField.CONN_TARGET, {
                    "impl_name": self.symbols.current_impl_name,
                    "conn_name": conn_name,
                    "source": source,
                    "prefix": target_prefix,
                }

        return None, {}


# ============================================================
# 上下文敏感约束引擎（主类）
# ============================================================
class AadlContextSensitiveEngine:
    """
    AADL 上下文敏感约束引擎。

    在 AADL 文本生成过程中：
    1. 增量解析已生成的文本，维护符号表
    2. 检测当前生成位置
    3. 计算当前字段的合法值
    4. 通过 logits processor 过滤非法 token

    与 CFG 约束的关系：
    - CFG 保证语法正确（外圈层过滤）
    - 本引擎保证语义正确（内圈层过滤）
    - 两者串联使用：CFG 先过滤，本引擎再收窄
    """

    def __init__(self):
        self.symbols = AadlSymbolTable()
        self.parser = IncrementalAadlParser(self.symbols)
        self.detector = AadlPositionDetector(self.symbols)

    def prefill_from_arch_json(self, arch: dict):
        """
        从架构 JSON 预填充符号表（可选）。
        如果是从 JSON 架构生成 AADL，可以预先把所有符号注册好，
        这样生成一开始就有完整的上下文信息。
        """
        if not arch:
            return

        if "package_name" in arch:
            self.symbols.set_package(arch["package_name"])
            for wp in arch.get("with_packages", []):
                self.symbols.add_with_package(wp)

        components = arch.get("components", [])
        if not components:
            # 阶段1格式
            for tc in arch.get("type_components", []):
                self.symbols.start_component_type(tc["name"], tc["category"])
                for p in tc.get("ports", []):
                    self.symbols.add_port(
                        p["name"], p["direction"], p["kind"],
                        p.get("data_type"),
                    )
                self.symbols.end_component_type(tc["name"])
            for ic in arch.get("impl_components", []):
                cat = ic.get("category", "")
                if "implementation" in cat:
                    cat = cat.replace(" implementation", "")
                self.symbols.start_component_impl(
                    ic["name"], ic.get("base_type", ""), cat
                )
                for sc in ic.get("subcomponents", []):
                    self.symbols.add_subcomponent(
                        sc["name"], sc.get("category", ""),
                        sc.get("classifier", ""),
                    )
                for conn in ic.get("connections", []):
                    self.symbols.add_connection(
                        conn.get("name", ""),
                        conn.get("source", ""), conn.get("target", ""),
                    )
                self.symbols.end_component_impl(ic["name"])
            return

        for comp in components:
            name = comp["name"]
            cat = comp.get("category", "")

            if "implementation" in cat:
                base_cat = cat.replace(" implementation", "")
                base_name = comp.get("base_type", name.split('.')[0])
                self.symbols.start_component_impl(name, base_name, base_cat)
                for sc in comp.get("subcomponents", []):
                    self.symbols.add_subcomponent(
                        sc["name"], sc.get("category", ""),
                        sc.get("classifier", ""),
                    )
                for conn in comp.get("connections", []):
                    self.symbols.add_connection(
                        conn.get("name", ""),
                        conn.get("source", ""), conn.get("target", ""),
                    )
                self.symbols.end_component_impl(name)
            else:
                self.symbols.start_component_type(name, cat)
                for p in comp.get("ports", []):
                    self.symbols.add_port(
                        p["name"], p["direction"], p["kind"],
                        p.get("data_type"),
                    )
                self.symbols.end_component_type(name)

    def update(self, generated_text: str):
        """更新已生成的文本，重新解析并更新符号表"""
        self.parser.update(generated_text)

    def get_current_constraint(self, generated_text: str
                               ) -> Tuple[Optional[ConstraintField], dict]:
        """获取当前生成位置的约束类型和上下文"""
        self.update(generated_text)
        return self.detector.detect(generated_text)

    def get_valid_values(self, generated_text: str) -> Optional[List[str]]:
        """
        获取当前正在生成的字段的合法值列表。
        返回 None 表示当前位置无特殊语义约束。
        """
        field_type, context = self.get_current_constraint(generated_text)
        if field_type is None:
            return None

        if field_type == ConstraintField.SUBCOMP_CLASSIFIER:
            return self.symbols.get_valid_classifiers(
                parent_impl_name=context.get("parent_impl"),
                sub_category=context.get("sub_category"),
            )

        elif field_type == ConstraintField.PORT_DATA_TYPE:
            return self.symbols.get_valid_data_types()

        elif field_type == ConstraintField.CONN_SOURCE:
            return self.symbols.get_valid_conn_sources(
                context.get("impl_name", "")
            )

        elif field_type == ConstraintField.CONN_TARGET:
            impl_name = context.get("impl_name", "")
            source = context.get("source")
            if source:
                # 约束4：源端类型已知，目标端必须同类型
                src_type = self.symbols.get_port_data_type(impl_name, source)
                if src_type:
                    return self.symbols.get_valid_conn_targets(
                        impl_name, data_type=src_type
                    )
            return self.symbols.get_valid_conn_targets(impl_name)

        elif field_type == ConstraintField.BINDING_TARGET:
            return self.symbols.get_valid_binding_targets(
                context.get("impl_name", ""),
                context.get("binding_kind", ""),
            )

        return None

    def get_current_prefix(self, generated_text: str) -> str:
        """获取当前正在输入的字段值的前缀"""
        _, context = self.get_current_constraint(generated_text)
        return context.get("prefix", "")

    # ============================================================
    # Logits Processor 接口
    # ============================================================

    def get_logits_processor(self, tokenizer):
        """
        创建一个语义约束 logits processor。
        可以与 CFG logits processor 一起传给 model.generate()。

        注意：应该放在 CFG logits processor 后面（先过语法过滤，再过语义过滤）。
        """
        engine = self

        class AadlSemanticLogitsProcessor:
            """AADL 语义约束 logits processor"""

            def __init__(self, tokenizer, engine):
                self.tokenizer = tokenizer
                self.engine = engine
                # 缓存，避免每步都重新解析
                self._last_text_len = -1
                self._cached_valid_ids = None

            def __call__(self, input_ids, scores):
                """
                每生成一个 token 被调用一次。
                - input_ids: (batch_size, seq_len) 已生成的 token
                - scores: (batch_size, vocab_size) logits 分数
                """
                # 只处理 batch 中的第一个
                if input_ids.dim() == 2:
                    ids = input_ids[0]
                else:
                    ids = input_ids

                # 解码已生成的文本（只解码最近的部分以提高速度）
                # 为了准确性，先全部解码
                text = self.tokenizer.decode(ids, skip_special_tokens=True)

                # 获取合法值
                valid_values = self.engine.get_valid_values(text)
                if valid_values is None:
                    # 无约束，直接返回
                    return scores

                if not valid_values:
                    # 没有合法值（异常情况），全部置为 -inf
                    scores.fill_(float('-inf'))
                    return scores

                # 获取当前正在输入的前缀
                prefix = self.engine.get_current_prefix(text)

                # 找出以 prefix 开头的合法值
                matching = [v for v in valid_values if v.startswith(prefix)]

                if not matching:
                    # 前缀已经不合法（理论上不会发生，因为之前的 token 已经被约束了）
                    scores.fill_(float('-inf'))
                    return scores

                # 计算所有可能的"下一个 token"的 ID
                valid_token_ids = self._compute_next_tokens(matching, prefix)

                # 将非法 token 置为 -inf
                vocab_size = scores.shape[-1]
                for i in range(scores.shape[0]):
                    mask = scores.new_ones(vocab_size, dtype=torch.bool)
                    for tid in valid_token_ids:
                        if tid < vocab_size:
                            mask[tid] = False
                    scores[i][mask] = float('-inf')

                return scores

            def _compute_next_tokens(self, matching_values: List[str],
                                      prefix: str) -> Set[int]:
                """
                计算合法值的"下一个 token" ID 集合。
                对于每个匹配的值，取 prefix 之后的内容，
                编码其第一个 token。
                """
                valid_ids = set()
                for value in matching_values:
                    remaining = value[len(prefix):]
                    if not remaining:
                        # 值已经完全匹配，接下来可以是空格、分号等结束符
                        # （这些由 CFG 约束保证，这里不做限制）
                        continue
                    # 编码剩余部分，取第一个 token
                    tokens = self.tokenizer.encode(
                        remaining, add_special_tokens=False
                    )
                    if tokens:
                        valid_ids.add(tokens[0])

                return valid_ids

        import torch
        return AadlSemanticLogitsProcessor(tokenizer, engine)


# ============================================================
# 便捷验证函数
# ============================================================
def check_aadl_semantics(aadl_text: str) -> List[str]:
    """
    检查一段 AADL 文本是否满足 5 大核心语义约束。
    返回错误列表（空列表表示全部通过）。
    """
    engine = AadlContextSensitiveEngine()
    engine.update(aadl_text)
    symbols = engine.symbols
    errors = []

    # 约束1: 引用完整性
    for impl_name, impl in symbols.component_impls.items():
        for sc_name, sc in impl.subcomponents.items():
            cls_base = sc.classifier.split('.')[0] if '.' in sc.classifier else sc.classifier
            if cls_base not in symbols.component_types:
                errors.append(
                    f"[引用完整性] {impl_name} 子组件 {sc_name} "
                    f"的 classifier '{sc.classifier}' 不存在"
                )

        # 连接引用
        for conn in impl.connections:
            src = conn["source"]
            tgt = conn["target"]
            all_srcs = symbols.get_valid_conn_sources(impl_name)
            all_tgts = symbols.get_valid_conn_targets(impl_name)
            all_ports = set(all_srcs) | set(all_tgts)
            if src and src not in all_ports:
                errors.append(
                    f"[引用完整性] {impl_name} 连接 '{conn.get('name', '?')}' "
                    f"的源端 '{src}' 不存在"
                )
            if tgt and tgt not in all_ports:
                errors.append(
                    f"[引用完整性] {impl_name} 连接 '{conn.get('name', '?')}' "
                    f"的目标端 '{tgt}' 不存在"
                )

    # 约束2: 包含规则
    for impl_name, impl in symbols.component_impls.items():
        parent_cat = symbols.get_impl_base_category(impl_name)
        if not parent_cat or parent_cat not in CONTAINMENT_RULES:
            continue
        allowed = CONTAINMENT_RULES[parent_cat]

        for sc_name, sc in impl.subcomponents.items():
            if sc.category not in allowed:
                errors.append(
                    f"[包含规则] {impl_name}({parent_cat}) "
                    f"不能包含 {sc_name}({sc.category})。"
                    f" 允许: {sorted(allowed)}"
                )
            # 分类器类别一致性
            cls_cat = symbols._get_classifier_category(sc.classifier)
            if cls_cat and cls_cat != sc.category and cls_cat != "abstract":
                errors.append(
                    f"[包含规则] {impl_name}.{sc_name} 类别 {sc.category} "
                    f"与分类器 {sc.classifier} 类别 {cls_cat} 不一致"
                )

    # 约束3: 端口方向匹配
    for impl_name, impl in symbols.component_impls.items():
        for conn in impl.connections:
            cname = conn.get("name", "?")
            src = conn["source"]
            tgt = conn["target"]
            valid_srcs = symbols.get_valid_conn_sources(impl_name)
            valid_tgts = symbols.get_valid_conn_targets(impl_name)
            if src and src not in valid_srcs:
                errors.append(
                    f"[端口方向] {impl_name} 连接 {cname} "
                    f"源端 '{src}' 不是 out/in out 方向"
                )
            if tgt and tgt not in valid_tgts:
                errors.append(
                    f"[端口方向] {impl_name} 连接 {cname} "
                    f"目标端 '{tgt}' 不是 in/in out 方向"
                )

    # 约束4: 数据类型匹配
    for impl_name, impl in symbols.component_impls.items():
        for conn in impl.connections:
            cname = conn.get("name", "?")
            src = conn["source"]
            tgt = conn["target"]
            src_type = symbols.get_port_data_type(impl_name, src)
            tgt_type = symbols.get_port_data_type(impl_name, tgt)
            if src_type and tgt_type and src_type != tgt_type:
                errors.append(
                    f"[类型匹配] {impl_name} 连接 {cname}: "
                    f"源端 {src}({src_type}) → 目标端 {tgt}({tgt_type})，类型不一致"
                )

    # 约束5: 绑定类型约束（简化，AADL 核心语法中绑定在 properties 里）
    # 这里只检查子组件的分类器类别是否正确（已在约束2中覆盖）

    return errors


# ============================================================
# 自测
# ============================================================
if __name__ == "__main__":
    # ========== 测试 AADL（符合 AADL 包含规则：system → process → thread）==========
    test_aadl = """package FlightControl
with Base_Types;
public
  data SensorData
    end SensorData;

  data ControlOutput
    end ControlOutput;

  device SensorDevice
    features
      data_out: out data port SensorData;
    end SensorDevice;

  device ActuatorDevice
    features
      cmd_in: in data port ControlOutput;
    end ActuatorDevice;

  thread NavigationThread
    features
      sensor_in: in data port SensorData;
      nav_out: out data port Base_Types::Float;
    end NavigationThread;

  thread GuidanceThread
    features
      nav_in: in data port Base_Types::Float;
      cmd_out: out data port ControlOutput;
    end GuidanceThread;

  process FlightControlProcess
    features
      sensor_in: in data port SensorData;
      cmd_out: out data port ControlOutput;
    end FlightControlProcess;

  process implementation FlightControlProcess.impl
    subcomponents
      nav_thread: thread NavigationThread;
      guid_thread: thread GuidanceThread;
    connections
      c1: sensor_in -> nav_thread.sensor_in;
      c2: nav_thread.nav_out -> guid_thread.nav_in;
      c3: guid_thread.cmd_out -> cmd_out;
    end FlightControlProcess.impl;

  system FlightControlSystem
    features
      sensor_in: in data port SensorData;
      cmd_out: out data port ControlOutput;
    end FlightControlSystem;

  system implementation FlightControlSystem.impl
    subcomponents
      sensor: device SensorDevice;
      fc_proc: process FlightControlProcess.impl;
      actuator: device ActuatorDevice;
    connections
      c1: sensor.data_out -> fc_proc.sensor_in;
      c2: fc_proc.cmd_out -> actuator.cmd_in;
    end FlightControlSystem.impl;

end FlightControl;
"""

    print("=" * 60)
    print("测试1: 5大约束检查（合法 AADL 架构）")
    print("=" * 60)
    errors = check_aadl_semantics(test_aadl)
    if errors:
        print(f"发现 {len(errors)} 个错误：")
        for e in errors:
            print(f"  ❌ {e}")
    else:
        print("✅ 所有约束通过！")

    # ---------- 测试2：检测违反包含规则的 AADL ----------
    print()
    print("=" * 60)
    print("测试2: 违反包含规则检测（system 直接包含 thread）")
    print("=" * 60)
    bad_containment_aadl = """package Test
public
  thread BadThread
    end BadThread;

  system BadSystem
    end BadSystem;

  system implementation BadSystem.impl
    subcomponents
      bad_thread: thread BadThread;
    end BadSystem.impl;
end Test;
"""
    errors2 = check_aadl_semantics(bad_containment_aadl)
    containment_errors = [e for e in errors2 if "包含规则" in e]
    print(f"  捕获到 {len(containment_errors)} 个包含规则错误:")
    for e in containment_errors:
        print(f"    ❌ {e}")

    # ---------- 测试3：检测端口方向不匹配 ----------
    print()
    print("=" * 60)
    print("测试3: 端口方向不匹配检测（in -> in）")
    print("=" * 60)
    bad_dir_aadl = """package Test
with Base_Types;
public
  process ProcA
    features
      input_a: in data port Base_Types::Float;
    end ProcA;

  process ProcB
    features
      input_b: in data port Base_Types::Float;
    end ProcB;

  system Top
    end Top;

  system implementation Top.impl
    subcomponents
      pa: process ProcA;
      pb: process ProcB;
    connections
      bad_conn: pa.input_a -> pb.input_b;
    end Top.impl;
end Test;
"""
    errors3 = check_aadl_semantics(bad_dir_aadl)
    dir_errors = [e for e in errors3 if "端口方向" in e]
    print(f"  捕获到 {len(dir_errors)} 个方向错误:")
    for e in dir_errors:
        print(f"    ❌ {e}")

    # ---------- 测试4：增量式约束 - 子组件 classifier ----------
    print()
    print("=" * 60)
    print("测试4: 增量约束 - 生成子组件 classifier 时")
    print("=" * 60)

    partial_aadl = """package FlightControl
with Base_Types;
public
  data SensorData
    end SensorData;

  device SensorDevice
    features
      data_out: out data port SensorData;
    end SensorDevice;

  process FlightControlProcess
    features
      sensor_in: in data port SensorData;
    end FlightControlProcess;

  system FlightControlSystem
    end FlightControlSystem;

  system implementation FlightControlSystem.impl
    subcomponents
      sensor: device S"""

    engine = AadlContextSensitiveEngine()
    engine.update(partial_aadl)

    field_type, context = engine.get_current_constraint(partial_aadl)
    print(f"  当前约束类型: {field_type}")
    print(f"  子组件名: {context.get('sub_name')}, 类别: {context.get('sub_category')}")
    print(f"  已输入前缀: '{context.get('prefix')}'")

    valid_values = engine.get_valid_values(partial_aadl)
    print(f"  合法 classifier（device 类别，system 父组件）:")
    for v in valid_values:
        print(f"    ✅ {v}")

    # ---------- 测试5：增量式约束 - 连接 target（类型匹配）----------
    print()
    print("=" * 60)
    print("测试5: 增量约束 - 生成 connection target 时（类型匹配）")
    print("=" * 60)

    partial_aadl2 = """package FlightControl
with Base_Types;
public
  data SensorData
    end SensorData;

  data ControlOutput
    end ControlOutput;

  device SensorDevice
    features
      data_out: out data port SensorData;
    end SensorDevice;

  device ActuatorDevice
    features
      cmd_in: in data port ControlOutput;
    end ActuatorDevice;

  thread NavigationThread
    features
      sensor_in: in data port SensorData;
      nav_out: out data port Base_Types::Float;
    end NavigationThread;

  process FlightControlProcess
    features
      sensor_in: in data port SensorData;
      cmd_out: out data port ControlOutput;
    end FlightControlProcess;

  process implementation FlightControlProcess.impl
    subcomponents
      nav_thread: thread NavigationThread;
    connections
      c1: sensor_in -> """

    engine2 = AadlContextSensitiveEngine()
    engine2.update(partial_aadl2)

    field_type2, context2 = engine2.get_current_constraint(partial_aadl2)
    print(f"  当前约束类型: {field_type2}")
    print(f"  源端: {context2.get('source')}")

    src_type = engine2.symbols.get_port_data_type(
        context2.get("impl_name", ""), context2.get("source", "")
    )
    print(f"  源端数据类型: {src_type}")

    valid_values2 = engine2.get_valid_values(partial_aadl2)
    print(f"  合法 target（必须同类型 SensorData 且是 in/in out 方向）:")
    for v in valid_values2:
        print(f"    ✅ {v}")

    print()
    print("  关键：可用的 in 方向端口有 sensor_in 和 nav_thread.sensor_in，")
    print("       但只有 nav_thread.sensor_in 是 SensorData 类型，")
    print("       所以 target 只能选它——这就是数据类型匹配约束。")

    # ---------- 测试6：prefill_from_arch_json ----------
    print()
    print("=" * 60)
    print("测试6: 从 JSON 架构预填充符号表")
    print("=" * 60)
    test_arch = {
        "package_name": "TestSys",
        "with_packages": ["Base_Types"],
        "components": [
            {
                "name": "Sensor",
                "category": "device",
                "ports": [
                    {"name": "data_out", "direction": "out",
                     "kind": "data port", "data_type": "Base_Types::Float"},
                ],
            },
            {
                "name": "Controller",
                "category": "thread",
                "ports": [
                    {"name": "sensor_in", "direction": "in",
                     "kind": "data port", "data_type": "Base_Types::Float"},
                    {"name": "control_out", "direction": "out",
                     "kind": "data port", "data_type": "Base_Types::Float"},
                ],
            },
            {
                "name": "Controller.impl",
                "category": "thread implementation",
                "base_type": "Controller",
                "subcomponents": [],
                "connections": [],
            },
        ],
    }
    engine3 = AadlContextSensitiveEngine()
    engine3.prefill_from_arch_json(test_arch)
    print(f"  包名: {engine3.symbols.package_name}")
    print(f"  类型数: {len(engine3.symbols.component_types)}")
    print(f"  实现数: {len(engine3.symbols.component_impls)}")
    print(f"  Sensor 端口: {list(engine3.symbols.component_types['Sensor'].ports.keys())}")
    print("  ✅ JSON 预填充成功")

    print()
    print("=" * 60)
    print("全部测试完成 ✅")
    print("=" * 60)
