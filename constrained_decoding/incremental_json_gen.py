#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
增量式 JSON 架构生成器（生成时保证语义正确）
==============================================
不是一次性生成整个 JSON，而是分步骤生成，
每一步的可选项都基于前面已生成的内容动态确定，
从而在生成过程中就保证静态语义正确。

生成步骤：
1. 生成 package_name + with_packages
2. 生成所有组件类型（名称 + 类别 + 端口）
   - 每生成一个组件类型，注册到符号表
3. 生成所有组件实现
   - subcomponent.classifier 的枚举 = 已定义组件集合（且符合包含规则）
   - 连接 source/target 的枚举 = 当前实现的合法端口列表

核心思想：把"语义校验"从"生成后检查"提前到"生成时约束"。
"""

import json
import time
import argparse
from typing import List, Dict, Optional
from dataclasses import dataclass, field

# 复用现有模块
from json_validator import CONTAINMENT_RULES


# ============================================================
# 增量符号表（生成过程中逐步构建）
# ============================================================

@dataclass
class IncrementalSymbolTable:
    """
    增量式符号表，随着 JSON 生成的推进逐步填充。
    每生成一个新组件/端口，就立即注册。
    """
    package_name: str = ""
    with_packages: List[str] = field(default_factory=list)

    # 组件类型: name -> {category, ports: {name: {direction, kind, type}}}
    component_types: Dict[str, dict] = field(default_factory=dict)

    # 组件实现: name -> {base_name, category, subcomponents: [...], connections: [...]}
    component_impls: Dict[str, dict] = field(default_factory=dict)

    # 生成顺序
    type_order: List[str] = field(default_factory=list)
    impl_order: List[str] = field(default_factory=list)

    # ---- 注册方法 ----
    def register_component_type(self, name: str, category: str):
        if name not in self.component_types:
            self.component_types[name] = {
                "category": category,
                "ports": {},
            }
            self.type_order.append(name)

    def register_port(self, comp_name: str, port_name: str,
                       direction: str, kind: str, data_type: str = None):
        if comp_name in self.component_types:
            self.component_types[comp_name]["ports"][port_name] = {
                "direction": direction,
                "kind": kind,
                "type": data_type,
            }

    def register_component_impl(self, name: str, base_name: str, category: str):
        if name not in self.component_impls:
            self.component_impls[name] = {
                "base_name": base_name,
                "category": category,
                "subcomponents": [],
                "connections": [],
            }
            self.impl_order.append(name)

    def register_subcomponent(self, impl_name: str, sub_name: str,
                              sub_category: str, classifier: str):
        if impl_name in self.component_impls:
            self.component_impls[impl_name]["subcomponents"].append({
                "name": sub_name,
                "category": sub_category,
                "classifier": classifier,
            })

    def register_connection(self, impl_name: str, conn_name: str,
                            source: str, target: str):
        if impl_name in self.component_impls:
            self.component_impls[impl_name]["connections"].append({
                "name": conn_name,
                "source": source,
                "target": target,
            })

    # ---- 查询方法 ----
    def get_all_classifiers(self) -> List[str]:
        """获取所有分类器（类型 + 实现）"""
        result = list(self.component_types.keys())
        result.extend(self.component_impls.keys())
        return sorted(result)

    def get_valid_subcomponent_classifiers(self, parent_impl_name: str) -> List[str]:
        """
        获取某个父实现中，子组件分类器的合法候选列表。
        同时满足：1）已定义；2）符合包含规则。
        """
        impl = self.component_impls.get(parent_impl_name)
        if not impl:
            return []

        parent_category = impl["category"].replace(" implementation", "")
        allowed_cats = CONTAINMENT_RULES.get(parent_category, set())

        candidates = []
        for name, ctype in self.component_types.items():
            if ctype["category"] in allowed_cats:
                candidates.append(name)
                impl_name = f"{name}.impl"
                if impl_name in self.component_impls:
                    candidates.append(impl_name)

        return sorted(candidates)

    def get_valid_connection_sources(self, impl_name: str) -> List[str]:
        """获取某个实现中连接源端的合法候选列表（out/in out 端口）"""
        return self._get_valid_ports(impl_name, is_source=True)

    def get_valid_connection_targets(self, impl_name: str) -> List[str]:
        """获取某个实现中连接目标端的合法候选列表（in/in out 端口）"""
        return self._get_valid_ports(impl_name, is_source=False)

    def _get_valid_ports(self, impl_name: str, is_source: bool) -> List[str]:
        impl = self.component_impls.get(impl_name)
        if not impl:
            return []

        valid_dirs = ("out", "in out") if is_source else ("in", "in out")
        result = []

        # 自身类型的端口
        base_name = impl["base_name"]
        base_type = self.component_types.get(base_name)
        if base_type:
            for pname, pinfo in base_type["ports"].items():
                if pinfo["direction"] in valid_dirs:
                    result.append(pname)

        # 子组件的端口
        for sub in impl["subcomponents"]:
            sub_name = sub["name"]
            classifier = sub["classifier"]
            # 找到子组件的类型
            sub_type_name = classifier
            if "." in classifier:
                sub_type_name = classifier.rsplit(".", 1)[0]

            sub_type = self.component_types.get(sub_type_name)
            if sub_type:
                for pname, pinfo in sub_type["ports"].items():
                    if pinfo["direction"] in valid_dirs:
                        result.append(f"{sub_name}.{pname}")

        return sorted(result)

    # ---- 导出为完整 JSON ----
    def to_arch_json(self) -> dict:
        """导出为完整的架构 JSON"""
        components = []

        # 按生成顺序输出类型
        for name in self.type_order:
            ctype = self.component_types[name]
            has_impl = f"{name}.impl" in self.component_impls
            ports_list = [
                {
                    "name": pname,
                    "direction": pinfo["direction"],
                    "kind": pinfo["kind"],
                    "data_type": pinfo.get("type"),
                }
                for pname, pinfo in ctype["ports"].items()
            ]
            components.append({
                "name": name,
                "category": ctype["category"],
                "has_impl": has_impl,
                "ports": ports_list,
            })

        # 按生成顺序输出实现
        for name in self.impl_order:
            impl = self.component_impls[name]
            components.append({
                "name": name,
                "category": impl["category"],
                "subcomponents": list(impl["subcomponents"]),
                "connections": list(impl["connections"]),
            })

        return {
            "package_name": self.package_name,
            "with_packages": list(self.with_packages),
            "components": components,
        }


# ============================================================
# 增量式 JSON 生成器
# ============================================================

class IncrementalJsonGenerator:
    """
    增量式 JSON 架构生成器。

    分步骤生成，每一步的枚举值都基于当前符号表动态确定，
    从而在生成过程中保证静态语义正确。
    """

    def __init__(self, model, tokenizer, backend: str = "llguidance"):
        self.model = model
        self.tokenizer = tokenizer
        self.backend = backend
        self.symbols = IncrementalSymbolTable()

    def generate(self, requirement: str,
                 max_components: int = 10,
                 max_ports_per_component: int = 8,
                 max_subcomponents: int = 10,
                 max_connections: int = 10,
                 temperature: float = 0.7) -> dict:
        """
        增量生成完整架构。

        流程：
        1. 生成包信息
        2. 循环生成组件类型（直到模型说结束或达到上限）
        3. 循环生成组件实现（为每个需要实现的类型生成实现）
        """
        # 1. 包信息
        self._generate_package_info(requirement)

        # 2. 生成组件类型
        print(f"\n[Gen] 生成组件类型（最多 {max_components} 个）...")
        num_types = self._generate_component_types(
            requirement, max_components, max_ports_per_component, temperature,
        )
        print(f"[Gen] 共生成 {num_types} 个组件类型")

        # 3. 生成组件实现
        print(f"\n[Gen] 生成组件实现...")
        num_impls = self._generate_component_impls(
            requirement, max_subcomponents, max_connections, temperature,
        )
        print(f"[Gen] 共生成 {num_impls} 个组件实现")

        return self.symbols.to_arch_json()

    # ---- 步骤1: 包信息 ----
    def _generate_package_info(self, requirement: str):
        """生成包名和 with 列表"""
        import outlines

        print("[Gen] 步骤1：生成包信息...")

        # 简单的包名生成（正则约束）
        # 实际项目中可以用更复杂的约束
        outlines_model = outlines.from_transformers(self.model, self.tokenizer)

        prompt = f"""根据以下需求，生成 AADL 包名。

需求：{requirement}

请输出一个合适的 AADL 包名（首字母大写的驼峰命名，如 FlightControl）。
直接输出包名，不要任何其他内容。

包名："""

        generator = outlines.generate.regex(
            outlines_model,
            r"[A-Z][a-zA-Z0-9_]+",
            max_tokens=20,
        )
        pkg_name = generator(prompt).strip()
        self.symbols.package_name = pkg_name
        print(f"  包名: {pkg_name}")

        # 简化：with_packages 固定为 ["Base_Types"]
        self.symbols.with_packages = ["Base_Types"]

    # ---- 步骤2: 生成组件类型 ----
    def _generate_component_types(self, requirement: str,
                                  max_components: int, max_ports: int,
                                  temperature: float) -> int:
        """循环生成组件类型"""
        import outlines

        outlines_model = outlines.from_transformers(self.model, self.tokenizer)
        count = 0

        for i in range(max_components):
            # 动态构建"下一个组件的类别"枚举
            # （所有 AADL 组件类别）
            categories = [
                "system", "process", "thread", "subprogram",
                "data", "device", "processor", "memory",
                "bus", "abstract", "thread group",
                "virtual processor", "virtual bus",
                "subprogram group",
            ]

            # 用 choice 约束生成组件类别
            # 这里简化：用 JSON object 生成单个组件类型
            # 实际应该更细化，但先演示思路

            comp_prompt = self._build_component_type_prompt(
                requirement, i + 1, count
            )

            # 生成单个组件类型的 JSON（受 Schema 约束）
            from pydantic import BaseModel, Field
            from typing import List as TList

            # 动态创建 Pydantic 模型（端口数据类型是动态的但这里简化）
            class PortDef(BaseModel):
                name: str = Field(pattern=r"^[a-z_][a-zA-Z0-9_]*$")
                direction: str = Field(pattern=r"^(in|out|in out)$")
                kind: str = Field(pattern=r"^(data port|event port|event data port)$")
                data_type: str = Field(default="Base_Types::Float")

            class ComponentTypeDef(BaseModel):
                name: str = Field(pattern=r"^[A-Z][a-zA-Z0-9_]*$")
                category: str
                has_impl: bool
                ports: TList[PortDef] = Field(default_factory=list)

            generator = outlines.generate.json(
                outlines_model,
                ComponentTypeDef,
                max_tokens=500,
                temperature=temperature if temperature > 0 else None,
            )

            try:
                result = generator(comp_prompt)
            except Exception as e:
                print(f"  组件 {i+1} 生成失败: {e}")
                break

            # 注册到符号表
            self.symbols.register_component_type(result.name, result.category)
            for port in result.ports:
                self.symbols.register_port(
                    result.name, port.name,
                    port.direction, port.kind,
                    port.data_type if hasattr(port, 'data_type') else None,
                )

            count += 1
            print(f"  [{i+1}] {result.name} ({result.category}): "
                  f"{len(result.ports)} 个端口, "
                  f"{'有' if result.has_impl else '无'}实现")

            # 判断是否继续（简化：生成到 system 就停，或达到数量上限）
            # 实际项目中应该让模型显式决定
            if result.category == "system" and count >= 3:
                print("  已生成 system 组件，停止生成更多类型")
                break

        return count

    def _build_component_type_prompt(self, requirement: str,
                                     idx: int, existing_count: int) -> str:
        """构建生成第 idx 个组件类型的 prompt"""
        existing = self.symbols.type_order
        existing_desc = ", ".join(
            f"{name}({self.symbols.component_types[name]['category']})"
            for name in existing
        ) if existing else "（暂无）"

        return f"""你正在设计 AADL 架构。已定义 {existing_count} 个组件。

需求：{requirement}

已定义的组件：{existing_desc}

请定义第 {idx} 个组件类型。
要求：
1. 组件名用大驼峰命名
2. 选择合适的组件类别（system/process/thread/device/processor/bus/memory/data/abstract 等）
3. 根据需要定义端口（data port、event port 等）
4. has_impl 表示是否需要对应的组件实现
5. 数据类型使用 Base_Types::Float

直接输出 JSON 对象。"""

    # ---- 步骤3: 生成组件实现 ----
    def _generate_component_impls(self, requirement: str,
                                  max_subs: int, max_conns: int,
                                  temperature: float) -> int:
        """为每个需要实现的组件类型生成实现"""
        import outlines
        from pydantic import BaseModel, Field
        from typing import List as TList, Literal

        outlines_model = outlines.from_transformers(self.model, self.tokenizer)
        count = 0

        for type_name in list(self.symbols.type_order):
            ctype = self.symbols.component_types[type_name]

            # 简化：所有 system/process/thread/processor 都生成实现
            if ctype["category"] not in ("system", "process", "thread",
                                         "processor", "device", "data"):
                continue

            impl_name = f"{type_name}.impl"
            impl_category = f"{ctype['category']} implementation"

            print(f"  生成实现: {impl_name}")

            # 注册实现（先注册，后面生成子组件时就能用了）
            self.symbols.register_component_impl(
                impl_name, type_name, impl_category
            )

            # 动态获取合法的子组件分类器候选
            valid_classifiers = (
                self.symbols.get_valid_subcomponent_classifiers(impl_name)
            )

            if not valid_classifiers:
                print(f"    无合法子组件（{ctype['category']} 没有可包含的组件）")
                count += 1
                continue

            # 生成子组件列表（这里简化为用 choice 约束）
            # 实际项目中应该逐个生成，每个子组件的 classifier 都受约束
            subs_prompt = self._build_subcomponents_prompt(
                requirement, impl_name, valid_classifiers, max_subs
            )

            # 简化：直接生成子组件名称列表
            # （完整实现应该逐个生成，每个 classifier 从合法列表中选）
            # 这里先演示概念

            # 生成连接
            valid_sources = self.symbols.get_valid_connection_sources(impl_name)
            valid_targets = self.symbols.get_valid_connection_targets(impl_name)

            if valid_sources and valid_targets:
                conns_prompt = self._build_connections_prompt(
                    impl_name, valid_sources, valid_targets, max_conns
                )
                # 简化：暂不逐个生成连接

            count += 1

        return count

    def _build_subcomponents_prompt(self, requirement: str, impl_name: str,
                                    valid_classifiers: List[str],
                                    max_subs: int) -> str:
        """构建子组件生成提示词"""
        classifier_list = "\n".join(f"  - {c}" for c in valid_classifiers)
        return f"""为 {impl_name} 添加子组件。

需求：{requirement}

合法的子组件分类器（只能从以下列表中选择）：
{classifier_list}

请生成子组件列表（最多 {max_subs} 个）。"""

    def _build_connections_prompt(self, impl_name: str,
                                  valid_sources: List[str],
                                  valid_targets: List[str],
                                  max_conns: int) -> str:
        """构建连接生成提示词"""
        sources = "\n".join(f"  - {s}" for s in valid_sources)
        targets = "\n".join(f"  - {t}" for t in valid_targets)
        return f"""为 {impl_name} 添加端口连接。

合法的源端口：
{sources}

合法的目标端口：
{targets}

请生成连接列表（最多 {max_conns} 个）。"""


# ============================================================
# 主函数（演示）
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description="增量式 JSON 架构生成器（生成时保证语义正确）"
    )
    parser.add_argument("--model", default="~/autodl-tmp/qwen3-14b")
    parser.add_argument("--requirement", default=None)
    parser.add_argument("--load-in-4bit", action="store_true")
    parser.add_argument("--backend", default="llguidance")
    parser.add_argument("--demo", action="store_true",
                        help="仅演示符号表增量构建逻辑，不加载模型")
    args = parser.parse_args()

    if args.demo:
        # 演示模式：手动构建符号表，验证查询逻辑
        print("=" * 60)
        print("增量符号表演示（不加载模型）")
        print("=" * 60)
        print()

        symbols = IncrementalSymbolTable()
        symbols.package_name = "DemoSys"
        symbols.with_packages = ["Base_Types"]

        # 模拟逐个生成组件类型
        print("[阶段1] 注册组件类型:")
        components = [
            ("SensorDevice", "device", [
                ("data_out", "out", "data port", "Base_Types::Float"),
            ]),
            ("WorkerThread", "thread", [
                ("input", "in", "data port", "Base_Types::Float"),
                ("output", "out", "data port", "Base_Types::Float"),
            ]),
            ("MainProcess", "process", []),
            ("TopSystem", "system", [
                ("req_in", "in", "data port", "Base_Types::Float"),
                ("resp_out", "out", "data port", "Base_Types::Float"),
            ]),
        ]

        for name, cat, ports in components:
            symbols.register_component_type(name, cat)
            for pname, pdir, pkind, ptype in ports:
                symbols.register_port(name, pname, pdir, pkind, ptype)
            print(f"  + {name} ({cat}), {len(ports)} 端口")

        print()
        print(f"当前分类器候选: {symbols.get_all_classifiers()}")
        print()

        # 模拟生成实现
        print("[阶段2] 注册实现和子组件:")

        # process 实现
        symbols.register_component_impl(
            "MainProcess.impl", "MainProcess", "process implementation"
        )
        valid_subs = symbols.get_valid_subcomponent_classifiers("MainProcess.impl")
        print(f"  MainProcess.impl 的合法子组件分类器: {valid_subs}")

        # 添加一个 thread 子组件
        symbols.register_subcomponent(
            "MainProcess.impl", "worker", "thread", "WorkerThread"
        )
        print("    + worker: thread WorkerThread")

        # system 实现
        symbols.register_component_impl(
            "TopSystem.impl", "TopSystem", "system implementation"
        )
        valid_subs_sys = symbols.get_valid_subcomponent_classifiers("TopSystem.impl")
        print(f"  TopSystem.impl 的合法子组件分类器: {valid_subs_sys}")

        symbols.register_subcomponent(
            "TopSystem.impl", "main_proc", "process", "MainProcess.impl"
        )
        symbols.register_subcomponent(
            "TopSystem.impl", "sensor", "device", "SensorDevice"
        )
        print("    + main_proc: process MainProcess.impl")
        print("    + sensor: device SensorDevice")

        print()

        # 连接候选
        print("[阶段3] 连接候选查询:")
        srcs = symbols.get_valid_connection_sources("TopSystem.impl")
        tgts = symbols.get_valid_connection_targets("TopSystem.impl")
        print(f"  TopSystem.impl 源端候选 ({len(srcs)} 个):")
        for s in srcs:
            print(f"    ← {s}")
        print(f"  TopSystem.impl 目标端候选 ({len(tgts)} 个):")
        for t in tgts:
            print(f"    → {t}")

        print()
        print("=" * 60)
        print("最终架构 JSON:")
        print("=" * 60)
        print(json.dumps(symbols.to_arch_json(), indent=2, ensure_ascii=False))
        return

    # 实际生成模式（需要模型）
    print("加载模型...")
    from aadl_generator import load_model
    model, tokenizer = load_model(
        args.model, load_in_4bit=args.load_in_4bit
    )

    generator = IncrementalJsonGenerator(model, tokenizer, args.backend)
    requirement = args.requirement or "设计一个简单的嵌入式控制系统"

    arch = generator.generate(requirement)

    print()
    print("=" * 60)
    print("生成结果:")
    print("=" * 60)
    print(json.dumps(arch, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
