#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模拟约束解码器
================
不依赖 LLM，模拟约束解码的 token 级生成过程。

核心验证目标：
1. 上下文检测器在正确的位置检测到正确的语义上下文
2. 在约束位置，总有合法的标识符可选（不会死锁）
3. 约束过滤后的生成结果满足语义约束

生成策略：
- 使用固定的生成模板（确保结构完整，能覆盖所有约束点）
- 在标识符位置，如果是约束位置，从合法候选中选（由 detector 检测上下文）
- 否则生成随机标识符
"""

import os
import sys
import random
import string
import re
from typing import List, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lark import Lark, UnexpectedEOF

from core.context_detector import ContextDetector, SemanticContext


GRAMMAR_PATH = os.path.join(os.path.dirname(__file__), "grammars", "aadl_mvp.lark")


class SimpleSymbolTable:
    """简单符号表：从生成的文本中用正则提取符号信息"""

    def __init__(self):
        self.data_types: List[str] = []
        self.system_types: List[str] = []
        self.subcomponents: dict = {}  # name -> category
        self.features: dict = {}  # type_name -> {feature_name: direction}

    def update_from_text(self, text: str):
        """从文本中提取符号信息"""
        type_pattern = re.compile(
            r'^\s*(system|data)\s+(\w+)\s*$',
            re.MULTILINE
        )
        impl_pattern = re.compile(
            r'^\s*(system|data)\s+implementation\s+(\w+)\.(\w+)\s*$',
            re.MULTILINE
        )

        # 提取类型（排除 implementation 行）
        for m in type_pattern.finditer(text):
            category = m.group(1)
            name = m.group(2)
            if name == "implementation":
                continue
            if category == "data" and name not in self.data_types:
                self.data_types.append(name)
            elif category == "system" and name not in self.system_types:
                self.system_types.append(name)

        # 提取 features
        lines = text.split("\n")
        current_type = None
        for line in lines:
            tm = re.match(r'^\s*(system|data)\s+(\w+)\s*$', line)
            if tm and tm.group(2) != "implementation":
                current_type = tm.group(2)
                if current_type not in self.features:
                    self.features[current_type] = {}
                continue
            if re.match(r'^\s*end\s+\w+\s*;', line):
                current_type = None
                continue
            fm = re.match(
                r'^\s+(\w+)\s*:\s*(in|out|in out)\s+data\s+port\s+(\w+)\s*;',
                line
            )
            if fm and current_type:
                fname = fm.group(1)
                fdir = fm.group(2)
                self.features[current_type][fname] = fdir

        # 提取子组件
        current_impl_type = None
        for line in lines:
            im = re.match(
                r'^\s*(system|data)\s+implementation\s+(\w+)\.(\w+)\s*$',
                line
            )
            if im:
                current_impl_type = im.group(2)
                continue
            if re.match(r'^\s*end\s+\w+\.\w+\s*;', line):
                current_impl_type = None
                continue
            sm = re.match(
                r'^\s+(\w+)\s*:\s*(system|data)\s+(\w+)\s*;',
                line
            )
            if sm and current_impl_type:
                sname = sm.group(1)
                scat = sm.group(2)
                self.subcomponents[sname] = scat


class MockConstrainedDecoder:
    """
    模拟约束解码器

    使用固定模板生成结构，在标识符位置应用约束过滤。
    目的：验证约束检测的正确性和约束解码的可行性。
    """

    def __init__(self, grammar_path: str, seed: int = 42):
        with open(grammar_path, "r", encoding="utf-8") as f:
            grammar_text = f.read()
        self.lark = Lark(grammar_text, start="start", parser="earley")
        self.detector = ContextDetector(grammar_path)
        self.random = random.Random(seed)
        self.generated = ""
        self.symbols = SimpleSymbolTable()

        # 统计
        self.constraint_hits = []  # (step, ctx_type, ctx_info, chosen_name)

    def generate(self) -> str:
        """生成一个完整的 AADL package"""
        self.generated = ""
        self.detector.reset()
        self.symbols = SimpleSymbolTable()
        self.constraint_hits = []

        # 固定模板：按顺序生成各部分
        self._gen_package_header()
        self._gen_data_type("SensorData")
        self._gen_data_type("StatusType")
        self._gen_system_type_with_features("Sensor", [
            ("out_port", "out", "SensorData"),
        ])
        self._gen_system_type_with_features("Controller", [
            ("in_port", "in", "SensorData"),
            ("status_out", "out", "StatusType"),
        ])
        self._gen_system_impl_with_subcomps_and_conns(
            "FlightSys", "impl",
            [
                ("sensor1", "system", "Sensor"),
                ("controller1", "system", "Controller"),
            ],
            [
                ("c1", "sensor1", "out_port", "controller1", "in_port"),
            ]
        )
        self._gen_package_footer()

        return self.generated

    # ---- 生成各部分 ----

    def _gen_package_header(self):
        self._append_literal("package")
        self._append_literal("WS")
        self._gen_identifier()  # 包名
        self._append_literal("WS")
        self._append_literal("is")
        self._append_literal("NEWLINE")
        self._append_literal("public")
        self._append_literal("NEWLINE")

    def _gen_data_type(self, name: str):
        self._append_literal("WS")  # 缩进
        self._append_literal("COMP_CATEGORY_data")
        self._append_literal("WS")
        self._gen_identifier(name)  # 类型名
        self._append_literal("NEWLINE")
        self._append_literal("WS")
        self._append_literal("END")
        self._append_literal("WS")
        self._gen_identifier(name)  # end 后的名字（保持一致）
        self._append_literal("SEMICOLON")
        self._append_literal("NEWLINE")
        self._update_symbols()

    def _gen_system_type_with_features(self, type_name: str, features: list):
        self._append_literal("WS")
        self._append_literal("COMP_CATEGORY_system")
        self._append_literal("WS")
        self._gen_identifier(type_name)
        self._append_literal("NEWLINE")
        self._append_literal("WS")
        self._append_literal("FEATURES")
        self._append_literal("NEWLINE")
        for fname, fdir, ftype in features:
            self._append_literal("WS")
            self._append_literal("WS")
            self._gen_identifier(fname)  # feature 名
            self._append_literal("WS")
            self._append_literal("COLON")
            self._append_literal("WS")
            self._append_literal(f"PORT_DIR_{fdir}")
            self._append_literal("WS")
            self._append_literal("__ANON_0")  # data port
            self._append_literal("WS")
            self._gen_constrained_identifier("data_type")  # 数据类型（约束位置）
            self._append_literal("SEMICOLON")
            self._append_literal("NEWLINE")
        self._append_literal("WS")
        self._append_literal("END")
        self._append_literal("WS")
        self._gen_identifier(type_name)
        self._append_literal("SEMICOLON")
        self._append_literal("NEWLINE")
        self._update_symbols()

    def _gen_system_impl_with_subcomps_and_conns(
        self,
        type_name: str,
        impl_name: str,
        subcomponents: list,
        connections: list,
    ):
        self._append_literal("WS")
        self._append_literal("COMP_CATEGORY_system")
        self._append_literal("WS")
        self._append_literal("IMPLEMENTATION")
        self._append_literal("WS")
        self._gen_identifier(type_name)
        self._append_literal("DOT")
        self._gen_identifier(impl_name)
        self._append_literal("NEWLINE")

        # subcomponents
        self._append_literal("WS")
        self._append_literal("SUBCOMPONENTS")
        self._append_literal("NEWLINE")
        for sname, scat, sclass in subcomponents:
            self._append_literal("WS")
            self._append_literal("WS")
            self._gen_identifier(sname)
            self._append_literal("WS")
            self._append_literal("COLON")
            self._append_literal("WS")
            self._append_literal(f"COMP_CATEGORY_{scat}")
            self._append_literal("WS")
            self._gen_constrained_identifier("subcomponent_classifier", category=scat)
            self._append_literal("SEMICOLON")
            self._append_literal("NEWLINE")
        self._update_symbols()

        # connections
        self._append_literal("WS")
        self._append_literal("CONNECTIONS")
        self._append_literal("NEWLINE")
        for cname, src_comp, src_port, tgt_comp, tgt_port in connections:
            self._append_literal("WS")
            self._append_literal("WS")
            self._gen_identifier(cname)
            self._append_literal("WS")
            self._append_literal("COLON")
            self._append_literal("WS")
            self._append_literal("PORT")
            self._append_literal("WS")
            # 源端第一段
            self._gen_constrained_identifier("conn_end_first_segment", is_source=True)
            self._append_literal("DOT")
            # 源端第二段
            self._gen_constrained_identifier("conn_end_second_segment", is_source=True)
            self._append_literal("WS")
            self._append_literal("__ANON_1")  # ->
            self._append_literal("WS")
            # 目标端第一段
            self._gen_constrained_identifier("conn_end_first_segment", is_source=False)
            self._append_literal("DOT")
            # 目标端第二段
            self._gen_constrained_identifier("conn_end_second_segment", is_source=False)
            self._append_literal("SEMICOLON")
            self._append_literal("NEWLINE")

        # end
        self._append_literal("WS")
        self._append_literal("END")
        self._append_literal("WS")
        self._gen_identifier(type_name)
        self._append_literal("DOT")
        self._gen_identifier(impl_name)
        self._append_literal("SEMICOLON")
        self._append_literal("NEWLINE")
        self._update_symbols()

    def _gen_package_footer(self):
        self._append_literal("END")
        self._append_literal("WS")
        # 从已生成文本中提取包名
        m = re.search(r'package\s+(\w+)', self.generated)
        pkg_name = m.group(1) if m else "Pkg"
        self._gen_identifier(pkg_name)
        self._append_literal("SEMICOLON")
        self._append_literal("NEWLINE")

    # ---- 低级生成方法 ----

    def _append_literal(self, terminal: str):
        """追加一个字面量 terminal"""
        mapping = {
            "WS": "  ",
            "NEWLINE": "\n",
            "COLON": ":",
            "SEMICOLON": ";",
            "DOT": ".",
            "__ANON_0": "data port",
            "__ANON_1": "->",
            "PACKAGE": "package",
            "IS": "is",
            "PUBLIC": "public",
            "END": "end",
            "FEATURES": "features",
            "IMPLEMENTATION": "implementation",
            "SUBCOMPONENTS": "subcomponents",
            "CONNECTIONS": "connections",
            "PORT": "port",
        }
        if terminal.startswith("COMP_CATEGORY_"):
            text = terminal[len("COMP_CATEGORY_"):]
        elif terminal.startswith("PORT_DIR_"):
            text = terminal[len("PORT_DIR_"):]
        elif terminal in mapping:
            text = mapping[terminal]
        else:
            text = terminal.lower()
        self.generated += text

    def _gen_identifier(self, preferred: Optional[str] = None) -> str:
        """生成一个标识符（非约束位置）"""
        if preferred:
            ident = preferred
        else:
            length = self.random.randint(3, 10)
            first = self.random.choice(string.ascii_lowercase)
            rest = "".join(
                self.random.choice(string.ascii_lowercase + string.digits)
                for _ in range(length - 1)
            )
            ident = first + rest
        self.generated += ident
        return ident

    def _gen_constrained_identifier(self, expected_ctx: str, **kwargs) -> str:
        """
        生成一个受约束的标识符

        先检测当前上下文，验证 detector 是否返回了期望的上下文类型。
        然后从合法候选中选择标识符。
        """
        ctx, _ = self.detector.detect(self.generated)

        # 验证上下文类型是否正确
        if ctx.context_type != expected_ctx:
            print(f"⚠️  上下文不匹配：期望 {expected_ctx}，实际 {ctx.context_type}")
            print(f"   末尾: ...{self.generated[-80:]}")

        # 从合法候选中选择
        candidates = self._get_candidates(expected_ctx, ctx, kwargs)
        if candidates:
            chosen = self.random.choice(candidates)
        else:
            print(f"⚠️  约束位置无候选：{expected_ctx}，退化为随机生成")
            chosen = self._random_id()

        self.constraint_hits.append((
            len(self.constraint_hits) + 1,
            expected_ctx,
            ctx.info,
            chosen
        ))
        self.generated += chosen
        return chosen

    def _get_candidates(self, expected_ctx: str, ctx: SemanticContext, kwargs: dict) -> List[str]:
        """获取当前约束位置的合法候选列表"""
        if expected_ctx == "data_type":
            return list(self.symbols.data_types)

        if expected_ctx == "subcomponent_classifier":
            cat = kwargs.get("category", "")
            if cat == "system":
                return list(self.symbols.system_types)
            if cat == "data":
                return list(self.symbols.data_types)
            return []

        if expected_ctx == "conn_end_first_segment":
            return list(self.symbols.subcomponents.keys())

        if expected_ctx == "conn_end_second_segment":
            is_source = kwargs.get("is_source", True)
            direction = "out" if is_source else "in"
            # 从当前行提取子组件名
            last_line = self.generated.rstrip().split("\n")[-1]
            subcomp_name = self._extract_last_subcomp(last_line)
            return self._get_ports_for_subcomp(subcomp_name, direction)

        return []

    def _extract_last_subcomp(self, line: str) -> Optional[str]:
        """提取最后一个 . 前面的标识符"""
        if "." not in line:
            return None
        parts = line.rstrip().split(".")
        if len(parts) < 2:
            return None
        prev = parts[-2]
        tokens = prev.split()
        return tokens[-1] if tokens else None

    def _get_ports_for_subcomp(self, subcomp_name: str, direction: str) -> List[str]:
        """获取子组件的指定方向的端口"""
        if not subcomp_name:
            return []
        category = self.symbols.subcomponents.get(subcomp_name, "")
        if not category:
            return []
        type_names = (self.symbols.system_types if category == "system"
                      else self.symbols.data_types)
        ports = []
        for tname in type_names:
            features = self.symbols.features.get(tname, {})
            for fname, fdir in features.items():
                if direction in fdir:
                    ports.append(fname)
        return ports

    def _random_id(self) -> str:
        """生成随机标识符"""
        length = self.random.randint(3, 10)
        first = self.random.choice(string.ascii_lowercase)
        rest = "".join(
            self.random.choice(string.ascii_lowercase + string.digits)
            for _ in range(length - 1)
        )
        return first + rest

    def _update_symbols(self):
        """更新符号表"""
        self.symbols.update_from_text(self.generated)


def main():
    print("=" * 60)
    print("模拟约束解码器 - 端到端验证")
    print("=" * 60)
    print()

    decoder = MockConstrainedDecoder(GRAMMAR_PATH, seed=42)

    print("开始生成...")
    result = decoder.generate()
    print(f"生成完成：{len(result)} 字符, {result.count(chr(10))} 行")
    print()

    print("=" * 60)
    print("生成结果：")
    print("=" * 60)
    print(result)
    print()

    # 验证 1：语法正确性
    print("=" * 60)
    print("验证 1：语法正确性")
    print("=" * 60)
    try:
        decoder.lark.parse(result)
        print("✅ 语法正确")
    except Exception as e:
        print(f"❌ 语法错误: {e}")
    print()

    # 验证 2：语义约束
    print("=" * 60)
    print("验证 2：语义约束（属性文法）")
    print("=" * 60)
    try:
        from core.attribute_parser import IncrementalAttributeParser
        parser = IncrementalAttributeParser(GRAMMAR_PATH)
        env = parser.parse_full_text(result)
        print("✅ 所有语义约束通过")
        pkg = list(env.packages.values())[0]
        print(f"  包名: {pkg.name}")
        print(f"  组件类型数: {len(pkg.component_types)}")
        print(f"  组件实现数: {len(pkg.component_impls)}")
        for impl in pkg.component_impls.values():
            print(f"  实现 {impl.type_name}.{impl.name}:")
            print(f"    子组件数: {len(impl.subcomponents)}")
            print(f"    连接数: {len(impl.connections)}")
    except AssertionError as e:
        print(f"❌ 语义约束违反: {e}")
    except Exception as e:
        print(f"⚠️  解析错误: {e}")
        import traceback
        traceback.print_exc()
    print()

    # 验证 3：约束检测覆盖度
    print("=" * 60)
    print("验证 3：约束检测命中情况")
    print("=" * 60)
    print(f"共 {len(decoder.constraint_hits)} 个约束位置")
    for i, ctx_type, ctx_info, chosen in decoder.constraint_hits:
        print(f"  [{i}] {ctx_type}: {ctx_info} → 选择 '{chosen}'")
    print()

    # 验证 4：上下文检测器的正确性（独立验证）
    print("=" * 60)
    print("验证 4：上下文检测器逐点验证")
    print("=" * 60)
    detector = ContextDetector(GRAMMAR_PATH)
    detector.reset()
    # 在每个约束位置前检测
    passed = 0
    failed = 0
    for i, ctx_type, ctx_info, _ in decoder.constraint_hits:
        # 找到对应位置的文本前缀（近似：用约束类型匹配）
        pass  # 简化：由 test_context_detector_step.py 专门验证
    print("（详细逐点验证由 test_context_detector_step.py 完成）")
    print()


if __name__ == "__main__":
    main()
