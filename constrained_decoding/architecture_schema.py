#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AADL 架构 JSON Schema 定义
==========================
定义阶段一输出的结构化架构 JSON 的数据模型与 Schema。

基于真实 AADL 示例（osate-examples）补充的核心概念：
- Component Type（组件类型 + 端口 + access features + flows + 属性 + extends）
- Component Implementation（实现名灵活 + 子组件 + 连接 + 绑定 + flows + 属性 + extends）
- Port（方向、种类、数据类型，支持跨包限定名 ::）
- Access Feature（bus access / data access 等）
- Connection（port connection / bus access / feature group 等多种类型）
- Binding（processor/memory/connection/subprogram 绑定）
- Flow（flow path / flow source / flow sink / end to end flow）
- Property（属性名 + 值 + 单位，支持 applies_to）
- 数据类型（预定义 Base_Types + 自定义 data 组件 + 跨包引用）
"""

from typing import List, Optional, Literal, Union
from pydantic import BaseModel, Field


# ============================================================
# 枚举类型
# ============================================================

ComponentCategory = Literal[
    "system", "process", "thread", "thread group",
    "subprogram", "subprogram group",
    "data", "device", "processor", "virtual processor",
    "memory", "bus", "virtual bus",
    "abstract",
]

# 实现类别（与类型类别一一对应，加 implementation 后缀）
ComponentImplCategory = Literal[
    "system implementation", "process implementation",
    "thread implementation", "thread group implementation",
    "subprogram implementation", "subprogram group implementation",
    "data implementation", "device implementation",
    "processor implementation", "virtual processor implementation",
    "memory implementation",
    "bus implementation", "virtual bus implementation",
    "abstract implementation",
]

PortDirection = Literal["in", "out", "in out"]

PortKind = Literal[
    "data port", "event port", "event data port",
]

AccessDirection = Literal["provides", "requires"]

AccessKind = Literal[
    "bus access", "data access",
    "subprogram access", "subprogram group access",
]

# 连接类型（真实 AADL 中有多种连接）
ConnectionKind = Literal[
    "port connection",          # 端口连接（最常用）
    "feature group connection",  # feature group 连接
    "access connection",        # access 连接（bus access 等）
]

# 绑定类型
BindingKind = Literal[
    "actual_processor_binding",    # 处理器绑定
    "actual_memory_binding",       # 内存绑定
    "actual_connection_binding",   # 连接绑定（到 bus）
    "actual_subprogram_binding",   # 子程序绑定
]

# 数据类型来源
DataTypeSource = Literal[
    "predefined",    # 预定义（来自 with 的标准包，如 Base_Types::Float）
    "custom",        # 自定义（本包内的 data 组件）
    "external",      # 外部包（来自 with 的其他包，如 speed_regulation::icd::speed）
]

# Flow 类型
FlowKind = Literal[
    "flow path",       # 流路径（类型内，从输入到输出）
    "flow source",     # 流源（输出作为源）
    "flow sink",       # 流汇（输入作为汇）
    "end to end flow",  # 端到端流（实现内，跨子组件）
]


# ============================================================
# 属性定义
# ============================================================

class PropertyDef(BaseModel):
    """属性定义"""
    name: str = Field(..., description="属性名（可用包限定，如 SEI::MIPS）",
                      pattern=r"^[A-Za-z_][A-Za-z0-9_:]*(::[A-Za-z_][A-Za-z0-9_]*)*$")
    value: str = Field(..., description="属性值（字符串形式，如 '20ms'、'Periodic'、'5.0 MIPS'）")
    unit: Optional[str] = Field(None, description="单位（如 ms, kb, MIPS 等）")
    applies_to: Optional[List[str]] = Field(
        None,
        description="属性应用到的元素列表（如子组件名、连接名、端口名）。None 表示应用到自身。",
    )


# ============================================================
# 端口 / 特征定义
# ============================================================

class PortDef(BaseModel):
    """端口定义"""
    name: str = Field(..., description="端口名称",
                      pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    direction: PortDirection = Field(..., description="端口方向")
    kind: PortKind = Field(..., description="端口种类")
    data_type: Optional[str] = Field(
        None,
        description=(
            "数据类型。"
            "data port 和 event data port 需要。"
            "格式："
            "预定义类型用 '包名::类型名'（如 Base_Types::Float）；"
            "自定义 data 组件直接用组件名（如 SensorData）；"
            "跨包引用用 '包名::类型名'（如 speed_regulation::icd::speed）。"
        ),
    )
    data_type_source: Optional[DataTypeSource] = Field(
        None,
        description="数据类型来源：predefined / custom / external",
    )
    properties: List[PropertyDef] = Field(
        default_factory=list, description="端口属性",
    )


class AccessDef(BaseModel):
    """Access 特征定义（如 bus access, data access）"""
    name: str = Field(..., description="access 特征名",
                      pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    direction: AccessDirection = Field(..., description="方向：provides / requires")
    kind: AccessKind = Field(..., description="access 种类")
    classifier: str = Field(
        ...,
        description="分类器引用（如 'Network' 或 'HardwareParts::Network'）",
    )
    properties: List[PropertyDef] = Field(
        default_factory=list, description="access 属性",
    )


class FeatureGroupDef(BaseModel):
    """Feature Group 定义"""
    name: str
    classifier: str
    direction: Optional[PortDirection] = None


# ============================================================
# 子组件 / 连接 / 绑定 定义
# ============================================================

class SubcomponentDef(BaseModel):
    """子组件定义"""
    name: str = Field(..., description="子组件实例名",
                      pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    category: ComponentCategory = Field(..., description="子组件类别")
    classifier: str = Field(
        ...,
        description=(
            "分类器引用。"
            "同包内：类型名（如 NavigationThread）或实现名（如 NavigationThread.i）；"
            "跨包引用：包名::组件名（如 speed_regulation::software::image_acquisition.i）。"
        ),
    )
    properties: List[PropertyDef] = Field(
        default_factory=list, description="子组件属性",
    )


class ConnectionDef(BaseModel):
    """连接定义（支持多种连接类型）"""
    name: str = Field(..., description="连接名称",
                      pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    kind: ConnectionKind = Field(
        "port connection", description="连接种类",
    )
    source: str = Field(
        ...,
        description=(
            "源端引用。"
            "端口连接：'组件.端口' 或 '端口'（自身端口）；"
            "access 连接：'组件.access名'。"
        ),
    )
    target: str = Field(
        ...,
        description=(
            "目标端引用。"
            "端口连接：'组件.端口' 或 '端口'；"
            "access 连接：'组件.access名'。"
        ),
    )
    bidirectional: bool = Field(
        False,
        description="是否双向（bus access 连接常用 <-> 表示双向）",
    )
    bound_to_bus: Optional[str] = Field(
        None,
        description="绑定的总线子组件实例名（Actual_Connection_Binding）",
    )
    properties: List[PropertyDef] = Field(
        default_factory=list, description="连接属性",
    )


class BindingDef(BaseModel):
    """绑定关系定义"""
    kind: BindingKind = Field(..., description="绑定种类")
    source: str = Field(
        ..., description="被绑定的元素（子组件实例名或连接名）",
    )
    target: str = Field(
        ..., description="绑定目标（处理器/内存/总线 子组件实例名）",
    )


# ============================================================
# Flow 定义
# ============================================================

class FlowDef(BaseModel):
    """流定义"""
    name: str = Field(..., description="流名称",
                      pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    kind: FlowKind = Field(..., description="流种类")
    source: Optional[str] = Field(
        None,
        description="源端（flow path / flow source / end to end flow 需要）",
    )
    target: Optional[str] = Field(
        None,
        description="目标端（flow path / flow sink / end to end flow 需要）",
    )
    path: Optional[List[str]] = Field(
        None,
        description=(
            "端到端流的完整路径（子组件流名 + 连接名 序列）。"
            "如 ['sensor.f0', 'c1', 'processor.f0']"
        ),
    )
    properties: List[PropertyDef] = Field(
        default_factory=list, description="流属性（如 Latency 等）",
    )


# ============================================================
# 组件类型 / 实现
# ============================================================

class ComponentDef(BaseModel):
    """
    统一组件声明（类型 + 实现合并为一个模型）。

    用 category 字段区分：
      - 类型：category 不带 "implementation" 后缀（如 "process", "system"）
      - 实现：category 带 "implementation" 后缀（如 "process implementation"）

    设计考量：
    合并为单一模型而非 Union[ComponentType, ComponentImpl]，
    是因为 JSON Schema 的 oneOf/anyOf 对约束解码（llguidance 等）不友好，
    扁平结构能提供更强的格式约束保证。
    语义区分由下游 validator 负责。
    """
    name: str = Field(
        ...,
        description=(
            "组件名称。"
            "类型名用大驼峰（如 NavigationThread）；"
            "实现名格式为 '类型名.标识'（如 NavThread.i / Sys.impl）。"
        ),
        pattern=r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)?$",
    )
    category: str = Field(
        ...,
        description=(
            "组件类别。"
            "类型类别：system / process / thread / thread group / "
            "subprogram / subprogram group / data / device / processor / "
            "virtual processor / memory / bus / virtual bus / abstract。"
            "实现类别：以上类别加 ' implementation' 后缀，如 'process implementation'。"
        ),
    )
    base_type: Optional[str] = Field(
        None,
        description="【实现专用】对应的组件类型名（仅实现组件需要）",
    )
    extends: Optional[str] = Field(
        None,
        description="继承的父类型/父实现名（同包内用名称，跨包用 :: 限定）",
    )
    has_impl: Optional[bool] = Field(
        True,
        description="【类型专用】是否有对应的实现（类型组件用）",
    )
    # ---- 类型层特征（类型和实现都可以有属性，类型层特征主要在类型上） ----
    ports: List[PortDef] = Field(
        default_factory=list, description="端口列表（类型组件的对外接口）",
    )
    accesses: List[AccessDef] = Field(
        default_factory=list, description="Access 特征列表（bus access / data access 等）",
    )
    feature_groups: List[FeatureGroupDef] = Field(
        default_factory=list, description="Feature Group 列表",
    )
    # ---- 实现层结构（实现组件才有子组件、连接等） ----
    subcomponents: List[SubcomponentDef] = Field(
        default_factory=list, description="【实现专用】子组件列表",
    )
    connections: List[ConnectionDef] = Field(
        default_factory=list, description="【实现专用】连接列表",
    )
    bindings: List[BindingDef] = Field(
        default_factory=list, description="【实现专用】绑定列表",
    )
    # ---- 流（类型和实现都有，但种类不同） ----
    flows: List[FlowDef] = Field(
        default_factory=list,
        description=(
            "流定义列表。"
            "类型上：flow path / flow source / flow sink；"
            "实现上：end to end flow。"
        ),
    )
    # ---- 属性（类型和实现都可以有） ----
    properties: List[PropertyDef] = Field(
        default_factory=list, description="属性列表",
    )
    calls: List[dict] = Field(
        default_factory=list,
        description="子程序调用序列（thread implementation 的 call map）",
    )


# ============================================================
# 数据类型相关常量和工具
# ============================================================

# 标准预定义数据类型（来自 Base_Types 等标准包）
PREDEFINED_DATA_TYPES = {
    # Base_Types 标准类型
    "Base_Types::Boolean",
    "Base_Types::Integer",
    "Base_Types::Float",
    "Base_Types::String",
    "Base_Types::Character",
    "Base_Types::Universal_Integer",
    "Base_Types::Universal_Real",
    # 整数
    "Base_Types::Int8",
    "Base_Types::Int16",
    "Base_Types::Int32",
    "Base_Types::Int64",
    "Base_Types::UInt8",
    "Base_Types::UInt16",
    "Base_Types::UInt32",
    "Base_Types::UInt64",
    # 浮点
    "Base_Types::Float32",
    "Base_Types::Float64",
    # 时间
    "Base_Types::Time",
}

# 标准包名（用于识别预定义类型来源）
STANDARD_PACKAGES = {"Base_Types", "base_types"}


def is_predefined_type(type_name: str) -> bool:
    """判断是否是标准预定义数据类型"""
    return type_name in PREDEFINED_DATA_TYPES


def is_qualified_name(name: str) -> bool:
    """判断是否是跨包限定名（包含 ::）"""
    return "::" in name


def get_package_prefix(name: str) -> Optional[str]:
    """获取限定名的包前缀。如 'pkg::sub::Foo' → 'pkg::sub'"""
    if "::" not in name:
        return None
    parts = name.split("::")
    return "::".join(parts[:-1])


def get_local_name(name: str) -> str:
    """获取限定名的本地部分。如 'pkg::Foo' → 'Foo'"""
    if "::" not in name:
        return name
    return name.split("::")[-1]


def is_in_package(type_name: str, with_packages: List[str]) -> bool:
    """判断类型是否来自 with 列表中的某个包"""
    if not is_qualified_name(type_name):
        return False
    prefix = get_package_prefix(type_name)
    # 精确匹配或前缀匹配（speed_regulation::icd::speed 来自 speed_regulation::icd）
    for pkg in with_packages:
        if prefix == pkg or prefix.startswith(pkg + "::"):
            return True
    return False


# ============================================================
# 完整架构描述
# ============================================================

class ArchitectureModel(BaseModel):
    """
    完整的 AADL 架构描述模型。

    这是阶段一的输出，也是阶段二的输入。
    包含生成完整 AADL 代码所需的所有结构化信息。
    """
    package_name: str = Field(
        ...,
        description="AADL 包名（支持嵌套包名，如 'speed_regulation::software'）",
        pattern=r"^[A-Za-z_][A-Za-z0-9_]*(::[A-Za-z_][A-Za-z0-9_]*)*$",
    )
    with_packages: List[str] = Field(
        default_factory=list,
        description="依赖的包列表（with 子句），如 ['Base_Types', 'speed_regulation::icd']",
    )
    components: List[ComponentDef] = Field(
        ...,
        description=(
            "组件列表，按生成顺序排列。"
            "每个元素是统一的 ComponentDef。"
            "类型组件在前，对应的实现在后。"
            "通过 category 字段区分类型/实现：带 implementation 后缀的是实现。"
        ),
        min_length=1,
    )


# ============================================================
# 工具函数
# ============================================================

def get_json_schema_dict() -> dict:
    """获取 ArchitectureModel 的 JSON Schema 字典"""
    return ArchitectureModel.model_json_schema()


def validate_json(data: dict) -> tuple[bool, list[str]]:
    """
    用 Pydantic 验证 JSON 结构正确性。

    Returns:
        (is_valid, error_messages)
    """
    try:
        ArchitectureModel(**data)
        return True, []
    except Exception as e:
        errors = []
        if hasattr(e, 'errors'):
            for err in e.errors():
                loc = " -> ".join(str(x) for x in err['loc'])
                errors.append(f"[{loc}] {err['msg']}")
        else:
            errors.append(str(e))
        return False, errors


if __name__ == "__main__":
    import json
    schema = get_json_schema_dict()
    print(json.dumps(schema, indent=2, ensure_ascii=False))
    print(f"\nSchema 大小: {len(json.dumps(schema))} 字符")
    print(f"预定义数据类型数: {len(PREDEFINED_DATA_TYPES)}")
