"""
增量式属性文法解析器
====================
自顶向下遍历解析树，从 AADL 文本中提取语义信息，更新属性环境。
使用自顶向下遍历是因为作用域管理（进入/退出包、类型、实现）需要父节点先处理。
"""

from typing import Optional, List
from lark import Lark, Token, Tree

from .attribute_env import AttributeEnvironment


class IncrementalAttributeParser:
    """
    增量式属性文法解析器
    
    用法：
        parser = IncrementalAttributeParser(grammar_path)
        env = parser.parse_full_text("package P is ...")
    """

    def __init__(self, grammar_path: str):
        with open(grammar_path, "r", encoding="utf-8") as f:
            grammar_text = f.read()
        self.lark = Lark(grammar_text, start="start", parser="earley")

    def parse_full_text(self, text: str) -> AttributeEnvironment:
        """
        解析完整文本，构建属性环境
        
        Args:
            text: 已生成的 AADL 文本
            
        Returns:
            更新后的属性环境
        """
        env = AttributeEnvironment()
        tree = self.lark.parse(text)
        self._walk_top_down(tree, env)
        return env

    def _walk_top_down(self, tree: Tree, env: AttributeEnvironment):
        """自顶向下遍历解析树，在进入节点时处理语义动作"""
        if isinstance(tree, Token):
            return

        rule = tree.data

        # ---- 进入节点时的处理 ----

        if rule == "package_spec":
            name = self._get_first_child_text(tree, "package_name")
            if name:
                env.enter_package(name)

        elif rule == "component_type":
            category = self._get_first_child_text(tree, "comp_category")
            name = self._get_first_child_text(tree, "comp_name")
            if category and name:
                env.enter_component_type(category, name)

        elif rule == "component_impl":
            category = self._get_first_child_text(tree, "comp_category")
            # component_impl 里有两个 comp_name：类型名和结束的类型名
            comp_names = self._get_all_child_text(tree, "comp_name")
            impl_names = self._get_all_child_text(tree, "impl_name")
            type_name = comp_names[0] if comp_names else None
            impl_name = impl_names[0] if impl_names else None
            if category and type_name and impl_name:
                env.enter_component_impl(category, type_name, impl_name)

        elif rule == "feature_decl":
            name = self._get_first_child_text(tree, "feature_name")
            if name:
                env.start_feature(name)

        elif rule == "port_spec":
            direction = self._get_first_child_text(tree, "port_dir")
            classifier = self._get_first_child_text(tree, "classifier_ref")
            if direction:
                env.set_feature_direction(direction)
            if classifier:
                env.set_feature_type(classifier)

        elif rule == "subcomp_decl":
            name = self._get_first_child_text(tree, "subcomp_name")
            category = self._get_first_child_text(tree, "comp_category")
            classifier = self._get_first_child_text(tree, "classifier_ref")
            if name:
                env.start_subcomponent(name)
            if category:
                env.set_subcomponent_category(category)
            if classifier:
                env.set_subcomponent_classifier(classifier)

        elif rule == "conn_decl":
            name = self._get_first_child_text(tree, "conn_name")
            conn_ends = self._get_all_conn_end_paths(tree)
            if name:
                env.start_connection(name)
            if len(conn_ends) >= 1:
                env.set_connection_source(conn_ends[0])
            if len(conn_ends) >= 2:
                env.set_connection_target(conn_ends[1])

        # ---- 递归遍历子节点 ----
        for child in tree.children:
            if isinstance(child, Tree):
                # 跳过 None 子节点（可选规则未匹配时产生）
                if child.data is not None:
                    self._walk_top_down(child, env)

        # ---- 离开节点时的处理 ----

        if rule == "component_type":
            # 取第二个 comp_name（end 后面那个）
            comp_names = self._get_all_child_text(tree, "comp_name")
            if len(comp_names) >= 2:
                env.exit_component_type(comp_names[-1])
            elif len(comp_names) == 1:
                env.exit_component_type(comp_names[0])

        elif rule == "component_impl":
            comp_names = self._get_all_child_text(tree, "comp_name")
            impl_names = self._get_all_child_text(tree, "impl_name")
            if comp_names and impl_names:
                env.exit_component_impl(comp_names[-1], impl_names[-1])

        elif rule == "package_spec":
            # 取第二个 package_name（end 后面那个）
            pkg_names = self._get_all_child_text(tree, "package_name")
            if len(pkg_names) >= 2:
                env.exit_package(pkg_names[-1])
            elif len(pkg_names) == 1:
                env.exit_package(pkg_names[0])

    # ---- 辅助方法 ----

    def _get_first_child_text(self, tree: Tree, child_rule: str) -> Optional[str]:
        """获取第一个指定规则的子节点的文本值"""
        for child in tree.children:
            if isinstance(child, Tree) and child.data == child_rule:
                return self._get_leaf_text(child)
        return None

    def _get_all_child_text(self, tree: Tree, child_rule: str) -> List[str]:
        """获取所有指定规则的子节点的文本值"""
        results = []
        for child in tree.children:
            if isinstance(child, Tree) and child.data == child_rule:
                text = self._get_leaf_text(child)
                if text:
                    results.append(text)
        return results

    def _get_leaf_text(self, tree: Tree) -> Optional[str]:
        """获取叶子节点（只有一个 terminal 子节点）的文本值"""
        for child in tree.children:
            if isinstance(child, Token):
                return str(child)
            elif isinstance(child, Tree):
                # 递归查找（比如 classifier_ref → classifier_name → IDENT）
                result = self._get_leaf_text(child)
                if result:
                    return result
        return None

    def _get_all_conn_end_paths(self, tree: Tree) -> List[List[str]]:
        """获取所有 conn_end 的路径列表"""
        results = []
        for child in tree.children:
            if isinstance(child, Tree) and child.data == "conn_end":
                path = []
                for seg_child in child.children:
                    if isinstance(seg_child, Tree) and seg_child.data in ("conn_first", "conn_segment", "conn_rest"):
                        text = self._get_leaf_text(seg_child)
                        if text:
                            path.append(text)
                if path:
                    results.append(path)
        return results
