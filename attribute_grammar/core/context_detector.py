"""
精确上下文检测器 v4
====================
有状态的上下文检测器，基于 Earley 状态变化 + 语义状态机。

原理：
1. 每次调用时，获取当前 Earley 状态（期望的 terminal + 活跃规则）
2. 与上一次的状态比较，检测状态转移
3. 维护一个语义上下文状态机，跟踪"正在生成什么"
4. 结合属性环境，给出精确的语义上下文

为什么需要有状态？
- Earley 是字符级 lexer，LLM 是 token 级生成
- 一个标识符可能由多个 LLM token 组成
- Earley 只能在"标识符开始"时检测到 conn_segment/classifier 活跃
- 之后 Earley 认为标识符已完成，但 LLM 还在生成标识符的后续 token
- 所以需要状态机来维持"标识符生成中"的状态
"""

from dataclasses import dataclass, field
from typing import Optional, List, Tuple, Set
import re
from lark import Lark, UnexpectedEOF, UnexpectedInput

from .attribute_env import AttributeEnvironment
from .attribute_parser import IncrementalAttributeParser


@dataclass
class SemanticContext:
    """语义上下文"""
    context_type: str  # "none" | "feature_data_type" | "subcomponent_classifier" |
                       # "conn_end_first_segment" | "conn_end_second_segment"
    is_source: Optional[bool] = None
    info: dict = None

    def __post_init__(self):
        if self.info is None:
            self.info = {}


@dataclass
class _DetectorState:
    """检测器内部状态"""
    # 当前语义上下文状态
    current_ctx: str = "none"
    # 连接端点相关
    conn_is_source: bool = True
    conn_segment_index: int = 0
    # subcomponent 类别（用于 subcomponent_classifier 上下文）
    subcomp_category: str = ""
    # 上一次的文本长度（用于检测新追加了什么）
    last_text_len: int = 0


class ContextDetector:
    """
    有状态的精确上下文检测器
    
    用法：
        detector = ContextDetector(grammar_path)
        ctx, env = detector.detect(current_text)
        # 每次生成新 token 后调用一次，检测器维护内部状态
    """

    def __init__(self, grammar_path: str):
        self.parser = IncrementalAttributeParser(grammar_path)
        with open(grammar_path, "r", encoding="utf-8") as f:
            grammar_text = f.read()
        self.lark = Lark(grammar_text, start="start", parser="earley")
        self._state = _DetectorState()

    def reset(self):
        """重置状态（新一轮生成时调用）"""
        self._state = _DetectorState()

    def detect(self, text: str) -> Tuple[SemanticContext, AttributeEnvironment]:
        """
        检测当前文本末尾的语义上下文
        
        Args:
            text: 当前已生成的完整文本
            
        Returns:
            (SemanticContext, AttributeEnvironment)
        """
        # 1. 获取当前 Earley 状态
        active_rules, expected_terminals = self._get_earley_state(text)
        active_set = set(active_rules)
        expected_set = set(expected_terminals)

        # 2. 获取属性环境
        env = self._get_attribute_env(text)

        # 3. 检测状态转移，更新内部状态机
        self._update_state(active_set, expected_set, env, text)

        # 4. 根据内部状态构建返回值
        ctx = self._build_context(env, text)

        return ctx, env

    # ---- Earley 状态 ----

    def _get_earley_state(self, text: str) -> Tuple[List[str], List[str]]:
        """获取 Earley 解析器在当前文本末尾的状态"""
        try:
            self.lark.parse(text)
            return [], []
        except UnexpectedEOF as e:
            rules = []
            for item in e.state:
                rule = item[0]
                dot = item[1]
                origin_str = str(rule.origin)
                m = re.search(r"RULE', '([^']+)'", origin_str)
                if m:
                    rule_name = m.group(1)
                else:
                    rule_name = origin_str
                if dot == 0:  # 只记录刚开始的规则
                    rules.append(rule_name)
            expected = list(e.expected) if hasattr(e, 'expected') else []
            return rules, expected
        except Exception:
            return [], []

    def _get_attribute_env(self, text: str) -> AttributeEnvironment:
        """
        获取基于当前已生成内容的属性环境
        
        策略：
        1. 先尝试完整解析 + 完整语义分析
        2. 如果不完整，补全 package end 后再解析
        3. 如果语义分析失败，用轻量级解析只提取结构信息
        4. 都不行，返回空环境
        """
        # 尝试 1：完整解析 + 完整语义
        try:
            self.lark.parse(text)
            env = self.parser.parse_full_text(text)
            self._fix_env_scope(env)
            return env
        except Exception:
            pass

        # 尝试 2：补全 package end 后解析
        completed = self._try_complete_package(text)
        if completed:
            try:
                self.lark.parse(completed)
                env = self.parser.parse_full_text(completed)
                self._fix_env_scope(env)
                return env
            except AssertionError:
                # 语义检查失败，但语法正确，用轻量级提取
                try:
                    tree = self.lark.parse(completed)
                    env = self._extract_env_from_tree(tree)
                    self._fix_env_scope(env)
                    return env
                except Exception:
                    pass
            except Exception:
                pass

        # 都不行，返回空环境
        return AttributeEnvironment()

    def _fix_env_scope(self, env: AttributeEnvironment):
        """修复 env 的作用域：离开包后 current_package 为 None，重新设回以便查询"""
        if env.packages and env.current_package is None:
            env.current_package = list(env.packages.values())[0]

    def _try_complete_package(self, text: str) -> Optional[str]:
        """尝试补全 package 的 end 语句"""
        pkg_match = re.search(r"package\s+(\w+)", text)
        if not pkg_match:
            return None
        pkg_name = pkg_match.group(1)
        suffix = f"\nend {pkg_name};\n"
        return text.rstrip() + suffix

    def _extract_env_from_tree(self, tree) -> AttributeEnvironment:
        """
        轻量级：从解析树中提取基本符号信息，不做语义检查
        只提取类型名、类别、feature 名、子组件名等
        """
        env = AttributeEnvironment()
        self._walk_tree(tree, env)
        # 重新设置 current_package（离开包后它变成了 None，但查询需要它）
        if env.packages and env.current_package is None:
            env.current_package = list(env.packages.values())[0]
        return env

    def _walk_tree(self, node, env: AttributeEnvironment):
        """递归遍历解析树，提取符号信息"""
        if not hasattr(node, 'data'):
            return

        rule = node.data

        if rule == 'package_spec':
            # 提取包名并进入包作用域
            pkg_name = ""
            for child in node.children:
                if hasattr(child, 'data') and child.data == 'package_name':
                    pkg_name = str(child.children[0])
                    break
            if pkg_name:
                env.enter_package(pkg_name)
                # 遍历子节点
                for child in node.children:
                    self._walk_tree(child, env)
                env.leave_package()
            return

        if rule == 'component_type':
            # 提取类别和名字
            category = ""
            name = ""
            for child in node.children:
                if hasattr(child, 'data'):
                    if child.data == 'comp_category':
                        category = str(child.children[0])
                    elif child.data == 'comp_name' and not name:
                        name = str(child.children[0])
            if name and category:
                env.add_component_type(name, category)
                # 遍历子节点找 features
                for child in node.children:
                    self._walk_tree(child, env)
            return

        if rule == 'component_impl':
            # 提取实现信息
            type_name = ""
            impl_name = ""
            category = ""
            for child in node.children:
                if hasattr(child, 'data'):
                    if child.data == 'comp_category':
                        category = str(child.children[0])
                    elif child.data == 'comp_name':
                        type_name = str(child.children[0])
                    elif child.data == 'impl_name':
                        impl_name = str(child.children[0])
            if type_name and impl_name:
                # 确保类型存在
                if env.current_package and type_name not in env.current_package.component_types:
                    env.add_component_type(type_name, category)
                env.enter_component_impl(type_name, impl_name)
                for child in node.children:
                    self._walk_tree(child, env)
                env.leave_component_impl()
            return

        if rule == 'feature_decl':
            fname = ""
            fdir = ""
            for child in node.children:
                if hasattr(child, 'data'):
                    if child.data == 'feature_name':
                        fname = str(child.children[0])
                    elif child.data == 'port_dir':
                        fdir = str(child.children[0])
            if fname and env.current_type:
                env.add_feature(fname, "data port", fdir)
            return

        if rule == 'subcomp_decl':
            sname = ""
            scat = ""
            for child in node.children:
                if hasattr(child, 'data'):
                    if child.data == 'subcomp_name':
                        sname = str(child.children[0])
                    elif child.data == 'comp_category':
                        scat = str(child.children[0])
            if sname and env.current_impl:
                env.add_subcomponent(sname, scat, "")
            return

        # 默认：继续遍历子节点
        for child in node.children:
            self._walk_tree(child, env)

    # ---- 状态机 ----

    def _update_state(
        self,
        active_set: Set[str],
        expected_set: Set[str],
        env: AttributeEnvironment,
        text: str,
    ):
        """
        根据 Earley 状态变化 + 文本增量，更新语义上下文状态机

        状态转移逻辑：
        1. 检测到 conn_segment 活跃（dot=0）+ 期望 IDENT → 进入 conn_end 状态
        2. 检测到 classifier 活跃 + 期望 IDENT → 进入 classifier 状态
        3. 在"标识符生成中"状态时，只有当新追加的字符包含非标识符字符时，才退出
           （而不是看 Earley 期不期望 IDENT，因为 Earley 是字符级的，可能认为标识符已完成）
        """
        last_line = text.rstrip().split("\n")[-1]
        s = self._state

        # 计算新追加的文本
        new_text = text[s.last_text_len:] if s.last_text_len > 0 else ""
        s.last_text_len = len(text)

        # ===== 第一步：检测标识符结束 =====
        # 如果当前在"标识符生成中"状态，且新追加了非标识符字符，
        # 先重置为 none，然后继续检测是否有新的上下文开始
        if s.current_ctx in (
            "conn_end_first_segment",
            "conn_end_second_segment",
            "feature_data_type",
            "subcomponent_classifier",
        ):
            if new_text:
                is_all_id_chars = all(
                    c.isalnum() or c == "_" for c in new_text
                )
                if not is_all_id_chars:
                    # 标识符结束，重置状态（不 return，继续检测新的上下文）
                    s.current_ctx = "none"
                    s.conn_segment_index = 0
                    s.subcomp_category = ""

        # 检测"即将开始生成 conn_segment"
        new_conn_segment = ("conn_segment" in active_set and "IDENT" in expected_set)

        # 检测"即将开始生成 classifier"
        new_classifier = (
            "classifier_name" in active_set or "classifier_ref" in active_set
        ) and ("IDENT" in expected_set or "CURSOR" in expected_set)

        # ===== 第二步：处理 conn_segment 开始 =====
        if new_conn_segment and s.current_ctx in ("none",):
            # 判断是源端还是目标端
            is_source = "->" not in last_line

            # 判断当前端点是第一段还是第二段
            # 如果是目标端，只看 -> 后面的部分有没有 .
            after_port = last_line.split(" port ")[-1] if " port " in last_line else ""
            if not is_source and "->" in after_port:
                after_arrow = after_port.split("->")[-1]
                has_dot = "." in after_arrow
            else:
                has_dot = "." in after_port

            if has_dot:
                s.current_ctx = "conn_end_second_segment"
                s.conn_segment_index = 1
            else:
                s.current_ctx = "conn_end_first_segment"
                s.conn_segment_index = 0
            s.conn_is_source = is_source
            return

        # ===== 第三步：处理 classifier 开始 =====
        if new_classifier and s.current_ctx == "none":
            if "data port" in last_line and (
                re.search(r'data port\s', last_line) or last_line.rstrip().endswith("data port")
            ):
                s.current_ctx = "feature_data_type"
                return

            for cat in {"system", "process", "thread", "data", "bus"}:
                # 用正则匹配，允许任意数量的空格
                if re.search(rf':\s+{cat}\s', last_line) or re.search(rf':\s+{cat}$', last_line):
                    s.current_ctx = "subcomponent_classifier"
                    s.subcomp_category = cat
                    return

    def _build_context(self, env: AttributeEnvironment, text: str) -> SemanticContext:
        """根据内部状态构建 SemanticContext"""
        s = self._state

        if s.current_ctx == "none":
            return SemanticContext(context_type="none")

        if s.current_ctx == "feature_data_type":
            return SemanticContext(
                context_type="feature_data_type",
                info={"constraint": "must_be_data_category"},
            )

        if s.current_ctx == "subcomponent_classifier":
            return SemanticContext(
                context_type="subcomponent_classifier",
                info={
                    "constraint": "must_match_subcomp_category",
                    "category": s.subcomp_category,
                },
            )

        if s.current_ctx in ("conn_end_first_segment", "conn_end_second_segment"):
            return SemanticContext(
                context_type=s.current_ctx,
                is_source=s.conn_is_source,
                info={
                    "is_source": s.conn_is_source,
                    "segment_index": s.conn_segment_index,
                },
            )

        return SemanticContext(context_type="none")
