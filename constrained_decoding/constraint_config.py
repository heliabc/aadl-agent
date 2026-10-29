#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AADL 约束配置框架
=================
所有约束模块独立开关，通过配置自由组合，支持对比实验和消融研究。

约束层级：
  ┌─────────────────────────────────────────┐
  │  语义约束（Semantic Constraints）        │
  │  ├─ 引用完整性（subcomponent classifier）│
  │  ├─ 包含规则（ containment ）           │
  │  ├─ 端口方向匹配（port direction）      │
  │  ├─ 端口类型匹配（port data type）      │
  │  ├─ 绑定约束（binding）                 │
  │  ├─ 端口数据类型（port data type ref）  │
  │  ├─ 实现-类型对应（impl base_type）     │
  │  ├─ 连接 bound_to_bus                   │
  │  ├─ Flow 端点引用                       │
  │  ├─ 属性名合法性                        │
  │  └─ 命名唯一性（负向过滤）              │
  └─────────────────────────────────────────┘
  ┌─────────────────────────────────────────┐
  │  文法约束（CFG Constraint）              │
  │  └─ Lark EBNF → llguidance FSM          │
  └─────────────────────────────────────────┘

使用方式：
  from constraint_config import ConstraintConfig, PRESETS

  # 1. 使用预设
  config = PRESETS["cfg_semantic"]

  # 2. 自定义
  config = ConstraintConfig(
      cfg_enabled=True,
      semantic_classifier_ref=True,
      semantic_port_direction=True,
      semantic_port_data_type=False,  # 关掉某条做消融
  )

  # 3. 传给生成器
  generator = ConstrainedAadlGenerator(model, tokenizer, config)
"""

from dataclasses import dataclass, field
from typing import Dict, List


# ============================================================
# 约束配置类
# ============================================================
@dataclass
class ConstraintConfig:
    """
    AADL 生成约束配置。每条约束独立开关。

    命名规则：
      - cfg_*: 文法层面的约束
      - semantic_*: 语义层面的约束
    """

    # ---- 文法约束（CFG） ----
    cfg_enabled: bool = True
    """是否启用 CFG 文法约束（Lark → llguidance）"""

    # ---- 第一梯队语义约束（核心，高影响力） ----
    semantic_classifier_ref: bool = True
    """子组件 classifier 必须引用已定义的组件类型"""

    semantic_containment: bool = True
    """子组件类别必须在父组件允许的包含集合中"""

    semantic_port_direction: bool = True
    """连接源端必须是 out/in_out，目标端必须是 in/in_out"""

    semantic_port_type_match: bool = True
    """连接两端端口的数据类型必须匹配"""

    semantic_binding: bool = True
    """绑定目标类别必须与绑定声明匹配"""

    # ---- 第二梯队语义约束（重要，中影响力） ----
    semantic_port_data_type: bool = True
    """端口声明时的数据类型必须是已定义的 data 组件或标准类型"""

    semantic_impl_base_type: bool = True
    """实现的 base_type 必须是已定义的同类别组件类型"""

    semantic_conn_bound_bus: bool = True
    """连接的 bound_to_bus 必须是子组件中的 bus"""

    semantic_flow_endpoint: bool = True
    """Flow 的 source/sink 必须引用存在的端口，且方向正确"""

    semantic_property_name: bool = True
    """属性名必须是 AADL 标准属性或已定义的属性"""

    # ---- 第三梯队语义约束（锦上添花，低影响力） ----
    semantic_naming_unique: bool = False
    """命名唯一性（组件/子组件/端口名不重复）—— 默认关闭，因为是负向过滤"""

    # ---- 配置方法 ----

    def enable_all_semantic(self):
        """启用所有语义约束"""
        for f in self.__dataclass_fields__:
            if f.startswith("semantic_"):
                setattr(self, f, True)
        return self

    def disable_all_semantic(self):
        """停用所有语义约束"""
        for f in self.__dataclass_fields__:
            if f.startswith("semantic_"):
                setattr(self, f, False)
        return self

    def enabled_semantic_count(self) -> int:
        """已启用的语义约束数量"""
        return sum(1 for f in self.__dataclass_fields__
                   if f.startswith("semantic_") and getattr(self, f))

    def summary(self) -> str:
        """配置摘要字符串"""
        lines = []
        lines.append("Constraint Configuration:")
        lines.append(f"  CFG 文法约束:    {'✅ 启用' if self.cfg_enabled else '❌ 停用'}")
        lines.append(f"  语义约束总数:    {self.enabled_semantic_count()}/11 启用")
        lines.append("  语义约束明细:")

        semantic_items = [
            ("semantic_classifier_ref",     "子组件分类器引用"),
            ("semantic_containment",        "包含规则"),
            ("semantic_port_direction",     "端口方向匹配"),
            ("semantic_port_type_match",    "端口类型匹配"),
            ("semantic_binding",            "绑定约束"),
            ("semantic_port_data_type",     "端口数据类型"),
            ("semantic_impl_base_type",     "实现 base_type"),
            ("semantic_conn_bound_bus",     "连接 bound_to_bus"),
            ("semantic_flow_endpoint",      "Flow 端点"),
            ("semantic_property_name",      "属性名合法性"),
            ("semantic_naming_unique",      "命名唯一性"),
        ]
        for key, label in semantic_items:
            status = "✅" if getattr(self, key) else "❌"
            lines.append(f"    {status} {label}")
        return "\n".join(lines)


# ============================================================
# 给 ConstraintConfig 添加 replace 便捷方法
# ============================================================
def _config_replace(self, **kwargs):
    """返回一个修改了指定字段的新配置（不可变风格）"""
    from dataclasses import replace
    return replace(self, **kwargs)


ConstraintConfig.replace = _config_replace


# ============================================================
# 预设配置（实验常用组合）
# ============================================================
PRESETS: Dict[str, ConstraintConfig] = {

    # 无约束：纯模型生成（baseline）
    "baseline": ConstraintConfig(
        cfg_enabled=False,
        semantic_classifier_ref=False,
        semantic_containment=False,
        semantic_port_direction=False,
        semantic_port_type_match=False,
        semantic_binding=False,
        semantic_port_data_type=False,
        semantic_impl_base_type=False,
        semantic_conn_bound_bus=False,
        semantic_flow_endpoint=False,
        semantic_property_name=False,
        semantic_naming_unique=False,
    ),

    # 仅 CFG：只有文法约束，无语义约束
    "cfg_only": ConstraintConfig(
        cfg_enabled=True,
        semantic_classifier_ref=False,
        semantic_containment=False,
        semantic_port_direction=False,
        semantic_port_type_match=False,
        semantic_binding=False,
        semantic_port_data_type=False,
        semantic_impl_base_type=False,
        semantic_conn_bound_bus=False,
        semantic_flow_endpoint=False,
        semantic_property_name=False,
        semantic_naming_unique=False,
    ),

    # CFG + 完整语义：所有约束都启用（full）
    "cfg_semantic": ConstraintConfig(
        cfg_enabled=True,
    ).enable_all_semantic(),

    # CFG + 核心语义（前5条，原始引擎的约束）
    "cfg_semantic_core": ConstraintConfig(
        cfg_enabled=True,
        semantic_classifier_ref=True,
        semantic_containment=True,
        semantic_port_direction=True,
        semantic_port_type_match=True,
        semantic_binding=True,
        semantic_port_data_type=False,
        semantic_impl_base_type=False,
        semantic_conn_bound_bus=False,
        semantic_flow_endpoint=False,
        semantic_property_name=False,
        semantic_naming_unique=False,
    ),

    # ---- 消融实验预设：每次只关一条 ----

    "ablation_no_classifier": ConstraintConfig(
        cfg_enabled=True,
    ).enable_all_semantic().replace(semantic_classifier_ref=False),

    "ablation_no_port_direction": ConstraintConfig(
        cfg_enabled=True,
    ).enable_all_semantic().replace(semantic_port_direction=False),

    "ablation_no_port_type_match": ConstraintConfig(
        cfg_enabled=True,
    ).enable_all_semantic().replace(semantic_port_type_match=False),

    "ablation_no_property_name": ConstraintConfig(
        cfg_enabled=True,
    ).enable_all_semantic().replace(semantic_property_name=False),
}


# ============================================================
# 便捷函数
# ============================================================
def get_preset(name: str) -> ConstraintConfig:
    """获取预设配置"""
    if name not in PRESETS:
        raise ValueError(
            f"未知预设: {name}\n"
            f"可用预设: {', '.join(PRESETS.keys())}"
        )
    return PRESETS[name]


def list_presets() -> List[str]:
    """列出所有预设名称"""
    return list(PRESETS.keys())


def print_all_presets():
    """打印所有预设配置摘要"""
    print("=" * 60)
    print("可用预设配置")
    print("=" * 60)
    for name, config in PRESETS.items():
        print(f"\n【{name}】")
        print(config.summary())
    print("=" * 60)


if __name__ == "__main__":
    print_all_presets()
