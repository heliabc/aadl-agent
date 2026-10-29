"""
属性环境 - 维护符号表、作用域和语义约束
=========================================
在增量式生成过程中，每解析到一个新元素就更新符号表，
遇到引用时检查约束是否满足。
"""

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set, Tuple


# AADL 包含规则：父组件类别 -> 允许的子组件类别
CONTAINMENT_RULES: Dict[str, Set[str]] = {
    "system": {"system", "process", "thread", "data", "bus", "device", "processor", "memory", "subprogram", "abstract"},
    "process": {"thread", "data", "subprogram", "subprogram group", "abstract"},
    "thread": {"data", "subprogram", "abstract"},
    "bus": {"bus", "abstract"},
    "device": {"data", "abstract"},
    "processor": {"abstract"},
    "memory": {"abstract"},
    "data": {"data", "abstract"},
    "subprogram": {"data", "subprogram", "abstract"},
    "abstract": {"abstract"},
}


@dataclass
class FeatureInfo:
    """特征（端口）信息"""
    name: str
    direction: str  # "in", "out", "in out"
    data_type: str  # 数据类型的 classifier 名
    kind: str = "data port"


@dataclass
class ComponentTypeInfo:
    """组件类型信息"""
    name: str
    category: str
    features: Dict[str, FeatureInfo] = field(default_factory=dict)


@dataclass
class SubcomponentInfo:
    """子组件信息"""
    name: str
    category: str
    classifier: str  # 引用的组件类型名


@dataclass
class ConnectionInfo:
    """连接信息"""
    name: str
    source_path: List[str]  # [子组件名, 端口名]
    target_path: List[str]  # [子组件名, 端口名]


@dataclass
class ComponentImplInfo:
    """组件实现信息"""
    name: str           # 实现名（不包含类型名）
    type_name: str      # 对应的类型名
    category: str
    subcomponents: Dict[str, SubcomponentInfo] = field(default_factory=dict)
    connections: List[ConnectionInfo] = field(default_factory=list)


@dataclass
class PackageInfo:
    """包信息"""
    name: str
    component_types: Dict[str, ComponentTypeInfo] = field(default_factory=dict)
    component_impls: Dict[str, ComponentImplInfo] = field(default_factory=dict)


class AttributeEnvironment:
    """
    属性环境：增量式维护 AADL 模型的语义属性
    
    维护内容：
    - 符号表：所有已定义的组件类型、实现、特征等
    - 当前作用域：当前在哪个包/类型/实现里
    - 语义约束检查：引用完整性、包含规则、端口匹配等
    """

    def __init__(self):
        self.packages: Dict[str, PackageInfo] = {}
        self.current_package: Optional[PackageInfo] = None
        self.current_type: Optional[ComponentTypeInfo] = None
        self.current_impl: Optional[ComponentImplInfo] = None
        self.current_feature: Optional[dict] = None  # 正在解析的 feature 中间状态
        self.current_subcomponent: Optional[dict] = None  # 正在解析的 subcomponent 中间状态
        self.current_connection: Optional[dict] = None  # 正在解析的 connection 中间状态

    # ---- 作用域管理 ----

    def enter_package(self, name: str):
        """进入一个包"""
        pkg = PackageInfo(name=name)
        self.packages[name] = pkg
        self.current_package = pkg

    def exit_package(self, name: str):
        """离开包（验证名字一致）"""
        assert self.current_package and self.current_package.name == name, \
            f"包名不匹配：期望 {self.current_package.name if self.current_package else None}，实际 {name}"
        self.current_package = None

    def enter_component_type(self, category: str, name: str):
        """进入组件类型"""
        assert self.current_package is not None, "组件类型必须在包内定义"
        ctype = ComponentTypeInfo(name=name, category=category)
        self.current_package.component_types[name] = ctype
        self.current_type = ctype

    def exit_component_type(self, name: str):
        """离开组件类型"""
        assert self.current_type and self.current_type.name == name, \
            f"类型名不匹配：期望 {self.current_type.name if self.current_type else None}，实际 {name}"
        self.current_type = None

    def enter_component_impl(self, category: str, type_name: str, impl_name: str):
        """进入组件实现"""
        assert self.current_package is not None, "组件实现必须在包内定义"
        # 引用完整性：类型必须存在
        assert type_name in self.current_package.component_types, \
            f"引用的类型不存在：{type_name}"
        cimpl = ComponentImplInfo(
            name=impl_name,
            type_name=type_name,
            category=category,
        )
        self.current_package.component_impls[f"{type_name}.{impl_name}"] = cimpl
        self.current_impl = cimpl

    def exit_component_impl(self, type_name: str, impl_name: str):
        """离开组件实现"""
        full_name = f"{type_name}.{impl_name}"
        assert self.current_impl and f"{self.current_impl.type_name}.{self.current_impl.name}" == full_name, \
            f"实现名不匹配"
        self.current_impl = None

    # ---- 特征（端口）----

    def start_feature(self, name: str):
        """开始解析一个 feature"""
        self.current_feature = {"name": name}

    def set_feature_direction(self, direction: str):
        """设置 feature 方向"""
        assert self.current_feature is not None
        self.current_feature["direction"] = direction

    def set_feature_type(self, classifier: str):
        """设置 feature 数据类型并完成 feature"""
        assert self.current_feature is not None
        assert self.current_type is not None, "feature 必须在组件类型内定义"

        # 引用完整性：数据类型必须存在
        assert self._classifier_exists(classifier), \
            f"引用的数据类型不存在：{classifier}"

        # 类型必须是 data 类别
        type_info = self._get_type_info(classifier)
        assert type_info and type_info.category == "data", \
            f"端口类型必须是 data 类别：{classifier} 是 {type_info.category if type_info else 'unknown'}"

        feature = FeatureInfo(
            name=self.current_feature["name"],
            direction=self.current_feature["direction"],
            data_type=classifier,
        )
        self.current_type.features[feature.name] = feature
        self.current_feature = None

    # ---- 子组件 ----

    def start_subcomponent(self, name: str):
        """开始解析子组件"""
        self.current_subcomponent = {"name": name}

    def set_subcomponent_category(self, category: str):
        """设置子组件类别"""
        assert self.current_subcomponent is not None
        self.current_subcomponent["category"] = category

        # 包含规则检查：父实现的类别必须允许这个子组件类别
        if self.current_impl is not None:
            parent_cat = self.current_impl.category
            allowed = CONTAINMENT_RULES.get(parent_cat, set())
            assert category in allowed, \
                f"包含规则违反：{parent_cat} 不能包含 {category}"

    def set_subcomponent_classifier(self, classifier: str):
        """设置子组件 classifier 并完成子组件"""
        assert self.current_subcomponent is not None
        assert self.current_impl is not None

        # 引用完整性：classifier 必须存在
        assert self._classifier_exists(classifier), \
            f"引用的组件类型不存在：{classifier}"

        # 类别一致性：classifier 的类别必须和声明的类别一致
        type_info = self._get_type_info(classifier)
        assert type_info and type_info.category == self.current_subcomponent["category"], \
            f"类别不匹配：声明 {self.current_subcomponent['category']}，但 {classifier} 是 {type_info.category if type_info else 'unknown'}"

        subcomp = SubcomponentInfo(
            name=self.current_subcomponent["name"],
            category=self.current_subcomponent["category"],
            classifier=classifier,
        )
        self.current_impl.subcomponents[subcomp.name] = subcomp
        self.current_subcomponent = None

    # ---- 连接 ----

    def start_connection(self, name: str):
        """开始解析连接"""
        self.current_connection = {"name": name}

    def set_connection_source(self, path: List[str]):
        """设置连接源端"""
        assert self.current_connection is not None
        self.current_connection["source"] = path

        # 验证源端合法性
        self._validate_conn_end(path, expected_direction="out")

    def set_connection_target(self, path: List[str]):
        """设置连接目标端并完成连接"""
        assert self.current_connection is not None
        assert self.current_impl is not None

        self.current_connection["target"] = path

        # 验证目标端合法性
        self._validate_conn_end(path, expected_direction="in")

        # 数据类型匹配检查
        src_type = self._get_conn_end_type(self.current_connection["source"])
        tgt_type = self._get_conn_end_type(path)
        assert src_type == tgt_type, \
            f"连接数据类型不匹配：源端 {src_type}，目标端 {tgt_type}"

        conn = ConnectionInfo(
            name=self.current_connection["name"],
            source_path=self.current_connection["source"],
            target_path=path,
        )
        self.current_impl.connections.append(conn)
        self.current_connection = None

    def _validate_conn_end(self, path: List[str], expected_direction: str):
        """验证连接端点的方向"""
        assert self.current_impl is not None
        assert len(path) >= 1, "连接路径不能为空"

        if len(path) == 1:
            # 引用当前实现的类型的 feature
            assert self.current_impl.type_name in self.current_package.component_types, \
                f"类型不存在：{self.current_impl.type_name}"
            type_info = self.current_package.component_types[self.current_impl.type_name]
            feature_name = path[0]
            assert feature_name in type_info.features, \
                f"端口不存在：{feature_name}（在类型 {type_info.name} 中）"
            feature = type_info.features[feature_name]
        else:
            # 引用子组件的 feature
            subcomp_name = path[0]
            feature_name = path[1]
            assert subcomp_name in self.current_impl.subcomponents, \
                f"子组件不存在：{subcomp_name}"
            subcomp = self.current_impl.subcomponents[subcomp_name]
            type_info = self._get_type_info(subcomp.classifier)
            assert type_info is not None, f"类型不存在：{subcomp.classifier}"
            assert feature_name in type_info.features, \
                f"端口不存在：{feature_name}（在 {subcomp.classifier} 中）"
            feature = type_info.features[feature_name]

        # 方向检查
        if expected_direction == "out":
            assert feature.direction in ("out", "in out"), \
                f"源端口方向错误：{feature.name} 是 {feature.direction}，需要 out 或 in out"
        elif expected_direction == "in":
            assert feature.direction in ("in", "in out"), \
                f"目标端口方向错误：{feature.name} 是 {feature.direction}，需要 in 或 in out"

    def _get_conn_end_type(self, path: List[str]) -> Optional[str]:
        """获取连接端点的数据类型"""
        if not self.current_impl:
            return None

        if len(path) == 1:
            type_info = self.current_package.component_types.get(self.current_impl.type_name)
            if not type_info:
                return None
            feature = type_info.features.get(path[0])
            return feature.data_type if feature else None
        else:
            subcomp = self.current_impl.subcomponents.get(path[0])
            if not subcomp:
                return None
            type_info = self._get_type_info(subcomp.classifier)
            if not type_info:
                return None
            feature = type_info.features.get(path[1])
            return feature.data_type if feature else None

    # ---- 辅助方法 ----

    def _classifier_exists(self, name: str) -> bool:
        """检查一个 classifier（组件类型）是否存在"""
        if not self.current_package:
            return False
        return name in self.current_package.component_types

    def _get_type_info(self, name: str) -> Optional[ComponentTypeInfo]:
        """获取组件类型信息"""
        if not self.current_package:
            return None
        return self.current_package.component_types.get(name)

    def get_available_types_by_category(self, category: str) -> List[str]:
        """获取指定类别的所有可用类型名"""
        if not self.current_package:
            return []
        return [
            name for name, t in self.current_package.component_types.items()
            if t.category == category
        ]

    def get_allowed_subcomponent_categories(self) -> Set[str]:
        """获取当前实现允许的子组件类别"""
        if not self.current_impl:
            return set()
        return CONTAINMENT_RULES.get(self.current_impl.category, set())

    def get_available_features(self, subcomp_name: Optional[str] = None, direction: Optional[str] = None) -> List[str]:
        """获取可用的端口名列表
        
        Args:
            subcomp_name: 子组件名，None 表示当前实现的类型
            direction: 方向过滤，None 表示所有方向
        """
        if not self.current_impl:
            return []

        if subcomp_name is None:
            # 当前实现的类型的 feature
            type_info = self._get_type_info(self.current_impl.type_name)
            if not type_info:
                return []
            features = type_info.features.values()
        else:
            # 子组件的类型的 feature
            subcomp = self.current_impl.subcomponents.get(subcomp_name)
            if not subcomp:
                return []
            type_info = self._get_type_info(subcomp.classifier)
            if not type_info:
                return []
            features = type_info.features.values()

        if direction:
            features = [f for f in features if f.direction == direction or f.direction == "in out"]

        return [f.name for f in features]

    def get_subcomponent_names_by_category(self, category: str) -> List[str]:
        """获取指定类别的所有子组件名"""
        if not self.current_impl:
            return []
        return [
            name for name, s in self.current_impl.subcomponents.items()
            if s.category == category
        ]

    def get_subcomponent_names(self) -> List[str]:
        """获取所有子组件名"""
        if not self.current_impl:
            return []
        return list(self.current_impl.subcomponents.keys())
