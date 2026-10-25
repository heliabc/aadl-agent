#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
动态符号表（从 JSON 预构建版本）
=================================
从阶段一输出的架构 JSON 中一次性预构建符号表，
供阶段二 AADL 生成时查询和约束使用。

支持的查询：
- 组件类型是否存在
- 组件实现是否存在
- 某个组件的端口列表
- 某个实现的子组件列表
- 某个实现的合法连接源端/目标端列表
- 子组件分类器候选列表
"""

from typing import List, Dict, Optional, Set
from dataclasses import dataclass, field


# ============================================================
# 数据结构
# ============================================================

@dataclass
class PortInfo:
    """端口信息"""
    name: str
    direction: str       # in / out / in out
    kind: str            # data port / event port / event data port
    data_type: Optional[str] = None


@dataclass
class SubcomponentInfo:
    """子组件信息"""
    name: str
    category: str          # 子组件类别（system/process/thread/...）
    classifier: str        # 分类器引用（如 NavigationThread.impl）


@dataclass
class ComponentTypeInfo:
    """组件类型信息"""
    name: str
    category: str
    ports: Dict[str, PortInfo] = field(default_factory=dict)
    has_impl: bool = True


@dataclass
class ComponentImplInfo:
    """组件实现信息"""
    name: str               # 完整名，如 FlightControlSystem.impl
    base_name: str          # 基础类型名，如 FlightControlSystem
    category: str           # 实现类别，如 system implementation
    subcomponents: Dict[str, SubcomponentInfo] = field(default_factory=dict)
    connections: List[dict] = field(default_factory=list)


# ============================================================
# 符号表主类
# ============================================================

class SymbolTable:
    """
    预构建符号表（从 JSON 架构一次性加载）。

    所有信息在生成开始前就已完整，不需要增量解析。
    """

    def __init__(self):
        self.package_name: str = ""
        self.with_packages: List[str] = []
        self.component_types: Dict[str, ComponentTypeInfo] = {}
        self.component_impls: Dict[str, ComponentImplInfo] = {}

    # --------------------------------------------------------
    # 构建方法
    # --------------------------------------------------------
    @classmethod
    def from_json(cls, arch: dict) -> "SymbolTable":
        """从架构 JSON 构建符号表"""
        st = cls()
        st.package_name = arch.get("package_name", "")
        st.with_packages = arch.get("with_packages", [])

        for comp in arch.get("components", []):
            name = comp["name"]
            category = comp.get("category", "")

            if "implementation" in category:
                # 组件实现
                base_name = name.rsplit(".", 1)[0] if "." in name else name
                impl = ComponentImplInfo(
                    name=name,
                    base_name=base_name,
                    category=category,
                )
                # 子组件
                for sub in comp.get("subcomponents", []):
                    impl.subcomponents[sub["name"]] = SubcomponentInfo(
                        name=sub["name"],
                        category=sub["category"],
                        classifier=sub["classifier"],
                    )
                # 连接
                impl.connections = comp.get("connections", [])

                st.component_impls[name] = impl
            else:
                # 组件类型
                ctype = ComponentTypeInfo(
                    name=name,
                    category=category,
                    has_impl=comp.get("has_impl", True),
                )
                # 端口
                for port in comp.get("ports", []):
                    ctype.ports[port["name"]] = PortInfo(
                        name=port["name"],
                        direction=port["direction"],
                        kind=port["kind"],
                        data_type=port.get("data_type"),
                    )
                st.component_types[name] = ctype

        return st

    # --------------------------------------------------------
    # 查询方法
    # --------------------------------------------------------

    def has_component_type(self, name: str) -> bool:
        """组件类型是否存在"""
        return name in self.component_types

    def has_component_impl(self, name: str) -> bool:
        """组件实现是否存在"""
        return name in self.component_impls

    def get_component_type(self, name: str) -> Optional[ComponentTypeInfo]:
        """获取组件类型信息"""
        return self.component_types.get(name)

    def get_component_impl(self, name: str) -> Optional[ComponentImplInfo]:
        """获取组件实现信息"""
        return self.component_impls.get(name)

    def get_ports_of_type(self, type_name: str) -> Dict[str, PortInfo]:
        """获取某个组件类型的所有端口"""
        ctype = self.component_types.get(type_name)
        if ctype:
            return ctype.ports
        return {}

    def get_all_component_names(self) -> List[str]:
        """获取所有组件类型名"""
        return list(self.component_types.keys())

    def get_all_impl_names(self) -> List[str]:
        """获取所有组件实现名"""
        return list(self.component_impls.keys())

    def get_all_classifiers(self) -> Set[str]:
        """获取所有分类器名（类型 + 实现）"""
        return set(self.component_types.keys()) | set(self.component_impls.keys())

    def get_subcomponent_classifiers(self, impl_name: str) -> List[str]:
        """
        获取某个实现中，子组件分类器的候选列表。
        根据包含规则筛选合法的分类器。
        """
        impl = self.component_impls.get(impl_name)
        if not impl:
            return []

        parent_cat = impl.category.replace(" implementation", "")

        # 包含规则（简化版，与 json_validator 保持一致）
        from json_validator import CONTAINMENT_RULES
        allowed_cats = CONTAINMENT_RULES.get(parent_cat, set())

        # 找出所有类别符合的分类器
        candidates = []
        for name, ctype in self.component_types.items():
            if ctype.category in allowed_cats:
                # 类型名
                candidates.append(name)
                # 如果有实现，也加入 impl 名
                impl_name = f"{name}.impl"
                if impl_name in self.component_impls:
                    candidates.append(impl_name)

        return sorted(candidates)

    def get_connection_sources(self, impl_name: str) -> List[str]:
        """
        获取某个实现中，连接源端的合法候选列表。
        包括：自身端口（out/in out）+ 子组件端口（out/in out）。
        """
        impl = self.component_impls.get(impl_name)
        if not impl:
            return []

        sources = []

        # 自身类型的 out / in out 端口
        type_info = self.component_types.get(impl.base_name)
        if type_info:
            for pname, pinfo in type_info.ports.items():
                if pinfo.direction in ("out", "in out"):
                    sources.append(pname)

        # 子组件的 out / in out 端口
        for sub_name, sub_info in impl.subcomponents.items():
            # 解析子组件类型名
            classifier = sub_info.classifier
            sub_type = classifier.rsplit(".", 1)[0] if "." in classifier else classifier

            sub_type_info = self.component_types.get(sub_type)
            if sub_type_info:
                for pname, pinfo in sub_type_info.ports.items():
                    if pinfo.direction in ("out", "in out"):
                        sources.append(f"{sub_name}.{pname}")

        return sorted(sources)

    def get_connection_targets(self, impl_name: str) -> List[str]:
        """
        获取某个实现中，连接目标端的合法候选列表。
        包括：自身端口（in/in out）+ 子组件端口（in/in out）。
        """
        impl = self.component_impls.get(impl_name)
        if not impl:
            return []

        targets = []

        # 自身类型的 in / in out 端口
        type_info = self.component_types.get(impl.base_name)
        if type_info:
            for pname, pinfo in type_info.ports.items():
                if pinfo.direction in ("in", "in out"):
                    targets.append(pname)

        # 子组件的 in / in out 端口
        for sub_name, sub_info in impl.subcomponents.items():
            classifier = sub_info.classifier
            sub_type = classifier.rsplit(".", 1)[0] if "." in classifier else classifier

            sub_type_info = self.component_types.get(sub_type)
            if sub_type_info:
                for pname, pinfo in sub_type_info.ports.items():
                    if pinfo.direction in ("in", "in out"):
                        targets.append(f"{sub_name}.{pname}")

        return sorted(targets)

    # --------------------------------------------------------
    # 调试输出
    # --------------------------------------------------------
    def summary(self) -> str:
        """返回符号表摘要"""
        lines = [
            f"包名: {self.package_name}",
            f"依赖包: {', '.join(self.with_packages) if self.with_packages else '无'}",
            f"组件类型: {len(self.component_types)} 个",
        ]
        for name, ctype in self.component_types.items():
            lines.append(f"  - {name} ({ctype.category}): "
                         f"{len(ctype.ports)} 个端口")
        lines.append(f"组件实现: {len(self.component_impls)} 个")
        for name, impl in self.component_impls.items():
            lines.append(
                f"  - {name} ({impl.category}): "
                f"{len(impl.subcomponents)} 个子组件, "
                f"{len(impl.connections)} 个连接"
            )
        return "\n".join(lines)


# ============================================================
# 便捷函数
# ============================================================

def build_symbol_table(arch: dict) -> SymbolTable:
    """从架构 JSON 构建符号表"""
    return SymbolTable.from_json(arch)


if __name__ == "__main__":
    # 自测
    import json

    test_arch = {
        "package_name": "TestSys",
        "with_packages": ["Base_Types"],
        "components": [
            {
                "name": "Sensor",
                "category": "device",
                "has_impl": False,
                "ports": [
                    {"name": "data_out", "direction": "out",
                     "kind": "data port", "data_type": "Base_Types::Float"},
                ],
            },
            {
                "name": "Controller",
                "category": "thread",
                "has_impl": True,
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
                "subcomponents": [],
                "connections": [],
            },
            {
                "name": "FlightControl",
                "category": "system",
                "has_impl": True,
                "ports": [
                    {"name": "input", "direction": "in",
                     "kind": "data port", "data_type": "Base_Types::Float"},
                    {"name": "output", "direction": "out",
                     "kind": "data port", "data_type": "Base_Types::Float"},
                ],
            },
            {
                "name": "FlightControl.impl",
                "category": "system implementation",
                "subcomponents": [
                    {"name": "sensor", "category": "device",
                     "classifier": "Sensor"},
                    {"name": "controller", "category": "thread",
                     "classifier": "Controller.impl"},
                ],
                "connections": [
                    {"name": "c1", "source": "sensor.data_out",
                     "target": "controller.sensor_in"},
                    {"name": "c2", "source": "controller.control_out",
                     "target": "output"},
                ],
            },
        ],
    }

    st = SymbolTable.from_json(test_arch)
    print(st.summary())

    print("\n--- 连接源端候选 (FlightControl.impl) ---")
    for s in st.get_connection_sources("FlightControl.impl"):
        print(f"  ← {s}")

    print("\n--- 连接目标端候选 (FlightControl.impl) ---")
    for t in st.get_connection_targets("FlightControl.impl"):
        print(f"  → {t}")
