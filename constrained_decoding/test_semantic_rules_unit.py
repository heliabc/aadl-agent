"""
语义规则单元验证脚本
直接调用底层方法验证每条规则的逻辑正确性，不依赖位置检测器
"""
import os
import sys
from typing import List, Set

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from aadl_semantic_full import FullAadlSymbolTable
from aadl_context_sensitive import AadlSymbolTable, SubcompInfo, CompTypeInfo, CompImplInfo, PortInfo
from json_validator import CONTAINMENT_RULES, BINDING_RULES


# ============================================================
# 测试工具
# ============================================================
class TestResult:
    def __init__(self, name: str):
        self.name = name
        self.passed = 0
        self.failed = 0
        self.failures: List[str] = []

    def add_pass(self):
        self.passed += 1

    def add_fail(self, msg: str):
        self.failed += 1
        self.failures.append(msg)

    @property
    def total(self):
        return self.passed + self.failed

    @property
    def rate(self):
        if self.total == 0:
            return 0.0
        return self.passed / self.total * 100

    def summary(self) -> str:
        status = "✅" if self.rate >= 90 else ("⚠️" if self.rate >= 60 else "❌")
        return f"{status} {self.name}: {self.passed}/{self.total} 通过 ({self.rate:.1f}%)"


def build_test_symbols() -> FullAadlSymbolTable:
    """构建一个测试用的符号表，包含各种类别的组件"""
    base = AadlSymbolTable()
    sym = FullAadlSymbolTable(base)

    # 组件类型
    test_types = {
        "sensor_data": ("data", []),
        "control_data": ("data", []),
        "my_thread": ("thread", [
            ("in_port", "in", "data port", "sensor_data"),
            ("out_port", "out", "data port", "control_data"),
            ("event_in", "in", "event port", None),
        ]),
        "my_device": ("device", [
            ("dev_out", "out", "data port", "sensor_data"),
        ]),
        "my_processor": ("processor", []),
        "my_memory": ("memory", []),
        "my_bus": ("bus", []),
        "my_virtual_processor": ("virtual processor", []),
        "my_system": ("system", [
            ("sys_in", "in", "data port", "sensor_data"),
            ("sys_out", "out", "data port", "control_data"),
        ]),
        "my_process": ("process", []),
        "my_abstract": ("abstract", []),
    }

    for name, (cat, ports) in test_types.items():
        base.component_types[name] = CompTypeInfo(name=name, category=cat)
        for pname, pdir, pkind, pdtype in ports:
            base.component_types[name].ports[pname] = PortInfo(
                name=pname, direction=pdir, kind=pkind, data_type=pdtype
            )

    # 组件实现
    test_impls = {
        "my_system.sys_impl": ("system", "my_system", [
            ("proc", "process", "my_process"),
            ("sensor_dev", "device", "my_device"),
            ("cpu", "processor", "my_processor"),
            ("ram", "memory", "my_memory"),
            ("datab", "bus", "my_bus"),
            ("ctrl_thread", "thread", "my_thread"),
        ]),
        "my_process.proc_impl": ("process", "my_process", [
            ("ctrl_thread", "thread", "my_thread"),
            ("vp", "virtual processor", "my_virtual_processor"),
        ]),
    }

    for impl_full, (cat, base_name, subs) in test_impls.items():
        impl_name = impl_full.split(".")[1]
        base.component_impls[impl_full] = CompImplInfo(
            name=impl_name, category=cat, base_name=base_name
        )
        for sname, scat, scls in subs:
            base.component_impls[impl_full].subcomponents[sname] = SubcompInfo(
                name=sname, category=scat, classifier=scls
            )

    return sym


def _lower_list(lst: List[str]) -> Set[str]:
    return {s.lower() for s in lst}


# ============================================================
# 1. 包含规则（CONTAINMENT_RULES）
# ============================================================
def test_containment_rules(sym: FullAadlSymbolTable) -> TestResult:
    """验证包含规则：父类别能包含的子类别是否正确"""
    result = TestResult("1. 包含规则 (containment)")

    # 正向测试：每个父类别里应该能包含的子类别
    test_cases = [
        # (parent_cat, child_cat, should_allow)
        ("system", "system", True),
        ("system", "process", True),
        ("system", "processor", True),
        ("system", "memory", True),
        ("system", "bus", True),
        ("system", "device", True),
        ("system", "data", True),
        ("system", "thread", False),
        ("process", "thread", True),
        ("process", "data", True),
        ("process", "subprogram", True),
        ("process", "thread group", True),
        ("process", "system", False),
        ("thread", "subprogram", True),
        ("thread", "data", True),
        ("thread", "system", False),
        ("device", "data", True),
        ("device", "subprogram", False),
        ("processor", "system", False),
    ]

    for parent_cat, child_cat, should_allow in test_cases:
        allowed = CONTAINMENT_RULES.get(parent_cat, set())
        is_allowed = child_cat in allowed
        if is_allowed == should_allow:
            result.add_pass()
        else:
            result.add_fail(
                f"{parent_cat} 包含 {child_cat}: "
                f"预期{'允许' if should_allow else '不允许'}，实际{'允许' if is_allowed else '不允许'}"
            )

    return result


# ============================================================
# 2. 子组件分类器引用（containment + 类型过滤）
# ============================================================
def test_subcomp_classifier(sym: FullAadlSymbolTable) -> TestResult:
    """验证子组件分类器：containment 规则 + 类型过滤"""
    result = TestResult("2. 子组件分类器引用")

    # 在 process 实现里，thread 类别的子组件，合法分类器应包含 my_thread
    valid_thread = sym._sym.get_valid_classifiers("my_process.proc_impl", "thread")
    valid_lower = _lower_list(valid_thread)

    if "my_thread" in valid_lower:
        result.add_pass()
    else:
        result.add_fail("process 里 thread 子组件的合法分类器应包含 my_thread")

    if "my_processor" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("process 里 thread 子组件的合法分类器不应包含 my_processor")

    # 在 system 实现里，processor 类别的子组件
    valid_proc = sym._sym.get_valid_classifiers("my_system.sys_impl", "processor")
    valid_lower = _lower_list(valid_proc)

    if "my_processor" in valid_lower:
        result.add_pass()
    else:
        result.add_fail("system 里 processor 子组件的合法分类器应包含 my_processor")

    if "my_thread" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("system 里 processor 子组件的合法分类器不应包含 my_thread")

    # system 里不能直接包含 thread（containment 规则），所以 thread 子组件的合法分类器应该为空
    valid_sys_thread = sym._sym.get_valid_classifiers("my_system.sys_impl", "thread")
    if len(valid_sys_thread) == 0:
        result.add_pass()
    else:
        result.add_fail("system 里 thread 子组件的合法分类器应为空（system 不能直接包含 thread）")

    return result


# ============================================================
# 3. 端口方向匹配
# ============================================================
def test_port_direction(sym: FullAadlSymbolTable) -> TestResult:
    """验证连接端点的方向约束
    AADL 连接方向规则（实现内部）：
      - 源端：子组件端口是 out/in out，自身端口是 in/in out
      - 目标端：子组件端口是 in/in out，自身端口是 out/in out
    """
    result = TestResult("3. 端口方向匹配")

    # 连接源端
    valid_sources = sym._sym.get_valid_conn_sources("my_system.sys_impl")
    sources_lower = _lower_list(valid_sources)

    # 自身端口 sys_in (in) 应该在源端列表里（数据从外部进入实现）
    if "sys_in" in sources_lower:
        result.add_pass()
    else:
        result.add_fail("连接源端应包含自身端口 sys_in (in)")

    # 自身端口 sys_out (out) 不应该在源端列表里
    if "sys_out" not in sources_lower:
        result.add_pass()
    else:
        result.add_fail("连接源端不应包含自身端口 sys_out (纯 out)")

    # 子组件端口 sensor_dev.dev_out (out) 应该在源端列表里
    if "sensor_dev.dev_out" in sources_lower:
        result.add_pass()
    else:
        result.add_fail("连接源端应包含子组件端口 sensor_dev.dev_out (out)")

    # 连接目标端
    valid_targets = sym._sym.get_valid_conn_targets("my_system.sys_impl")
    targets_lower = _lower_list(valid_targets)

    # 自身端口 sys_out (out) 应该在目标端列表里（数据流出到外部）
    if "sys_out" in targets_lower:
        result.add_pass()
    else:
        result.add_fail("连接目标端应包含自身端口 sys_out (out)")

    # 自身端口 sys_in (in) 不应该在目标端列表里
    if "sys_in" not in targets_lower:
        result.add_pass()
    else:
        result.add_fail("连接目标端不应包含自身端口 sys_in (纯 in)")

    return result


# ============================================================
# 4. 端口类型匹配
# ============================================================
def test_port_type_match(sym: FullAadlSymbolTable) -> TestResult:
    """验证端口类型匹配：指定 data_type 时，只返回同类型端口"""
    result = TestResult("4. 端口类型匹配")

    # 目标端 + data_type=sensor_data：应该返回 in 方向且类型为 sensor_data 的端口
    valid_targets = sym._sym.get_valid_conn_targets(
        "my_system.sys_impl", data_type="sensor_data"
    )
    targets_lower = _lower_list(valid_targets)

    # ctrl_thread.in_port 是 in 方向，data_type=sensor_data，应该在列表里
    if "ctrl_thread.in_port" in targets_lower:
        result.add_pass()
    else:
        result.add_fail("目标端(data_type=sensor_data) 应包含 ctrl_thread.in_port")

    # sensor_dev.dev_out 是 out 方向，不在目标端（方向不匹配）
    if "sensor_dev.dev_out" not in targets_lower:
        result.add_pass()
    else:
        result.add_fail("目标端不应包含 sensor_dev.dev_out (out 方向)")

    # 源端 + data_type=control_data：应该返回 out 方向且类型为 control_data 的端口
    valid_sources = sym._sym.get_valid_conn_sources(
        "my_system.sys_impl", data_type="control_data"
    )
    sources_lower = _lower_list(valid_sources)

    # ctrl_thread.out_port 是 out 方向，data_type=control_data，应该在列表里
    if "ctrl_thread.out_port" in sources_lower:
        result.add_pass()
    else:
        result.add_fail("源端(data_type=control_data) 应包含 ctrl_thread.out_port")

    return result


# ============================================================
# 5. 绑定约束（处理器/内存/总线绑定）
# ============================================================
def test_binding_constraints(sym: FullAadlSymbolTable) -> TestResult:
    """验证绑定约束：Actual_Processor_Binding 的值只能是 processor 类别子组件"""
    result = TestResult("5. 绑定约束 (binding)")

    # processor 绑定（binding_kind 是 "actual_processor_binding"）
    valid_proc = sym._sym.get_valid_binding_targets(
        "my_system.sys_impl", "actual_processor_binding"
    )
    valid_lower = _lower_list(valid_proc)

    if "cpu" in valid_lower:
        result.add_pass()
    else:
        result.add_fail("processor 绑定应包含 cpu (processor 子组件)")

    if "ram" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("processor 绑定不应包含 ram (memory 子组件)")

    if "ctrl_thread" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("processor 绑定不应包含 ctrl_thread (thread 子组件)")

    # memory 绑定
    valid_mem = sym._sym.get_valid_binding_targets(
        "my_system.sys_impl", "actual_memory_binding"
    )
    valid_lower = _lower_list(valid_mem)

    if "ram" in valid_lower:
        result.add_pass()
    else:
        result.add_fail("memory 绑定应包含 ram (memory 子组件)")

    if "cpu" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("memory 绑定不应包含 cpu (processor 子组件)")

    return result


# ============================================================
# 6. 实现 base_type
# ============================================================
def test_impl_base_type(sym: FullAadlSymbolTable) -> TestResult:
    """验证实现的 base_type 必须是同类别类型"""
    result = TestResult("6. 实现 base_type")

    valid = sym.get_valid_base_types("system")
    valid_lower = _lower_list(valid)

    if "my_system" in valid_lower:
        result.add_pass()
    else:
        result.add_fail("system 实现的 base_type 应包含 my_system")

    if "my_thread" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("system 实现的 base_type 不应包含 my_thread")

    if "my_processor" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("system 实现的 base_type 不应包含 my_processor")

    # thread 实现的 base_type
    valid_thread = sym.get_valid_base_types("thread")
    valid_lower = _lower_list(valid_thread)

    if "my_thread" in valid_lower:
        result.add_pass()
    else:
        result.add_fail("thread 实现的 base_type 应包含 my_thread")

    if "my_system" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("thread 实现的 base_type 不应包含 my_system")

    return result


# ============================================================
# 7. 端口数据类型
# ============================================================
def test_port_data_type(sym: FullAadlSymbolTable) -> TestResult:
    """验证端口数据类型的合法性"""
    result = TestResult("7. 端口数据类型")

    valid_types = sym.get_valid_data_types()
    types_lower = _lower_list(valid_types)

    # data 类型组件应该在列表里
    if "sensor_data" in types_lower:
        result.add_pass()
    else:
        result.add_fail("端口数据类型应包含 data 类别组件 sensor_data")

    if "control_data" in types_lower:
        result.add_pass()
    else:
        result.add_fail("端口数据类型应包含 data 类别组件 control_data")

    # thread 类型不应该在列表里
    if "my_thread" not in types_lower:
        result.add_pass()
    else:
        result.add_fail("端口数据类型不应包含 thread 类别组件 my_thread")

    # system 类型也不应该
    if "my_system" not in types_lower:
        result.add_pass()
    else:
        result.add_fail("端口数据类型不应包含 system 类别组件 my_system")

    return result


# ============================================================
# 8. 连接 bound_to_bus
# ============================================================
def test_conn_bound_bus(sym: FullAadlSymbolTable) -> TestResult:
    """验证连接可绑定的 bus 子组件"""
    result = TestResult("8. 连接 bound_to_bus")

    valid = sym.get_valid_bound_buses("my_system.sys_impl")
    valid_lower = _lower_list(valid)

    if "datab" in valid_lower:
        result.add_pass()
    else:
        result.add_fail("连接绑定 bus 应包含 datab (bus 子组件)")

    if "cpu" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("连接绑定 bus 不应包含 cpu (processor 子组件)")

    if "ram" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("连接绑定 bus 不应包含 ram (memory 子组件)")

    if "ctrl_thread" not in valid_lower:
        result.add_pass()
    else:
        result.add_fail("连接绑定 bus 不应包含 ctrl_thread (thread 子组件)")

    return result


# ============================================================
# 9. Flow 端点
# ============================================================
def test_flow_endpoints(sym: FullAadlSymbolTable) -> TestResult:
    """验证 Flow 端点的合法性"""
    result = TestResult("9. Flow 端点")

    # flow path source
    valid_src = sym.get_valid_flow_endpoints("my_system", "flow path", True)
    src_lower = _lower_list(valid_src)

    if "sys_out" in src_lower:
        result.add_pass()
    else:
        result.add_fail("flow path source 应包含 sys_out (out data port)")

    if "sys_in" not in src_lower:
        result.add_pass()
    else:
        result.add_fail("flow path source 不应包含 sys_in (in data port)")

    # flow path sink
    valid_sink = sym.get_valid_flow_endpoints("my_system", "flow path", False)
    sink_lower = _lower_list(valid_sink)

    if "sys_in" in sink_lower:
        result.add_pass()
    else:
        result.add_fail("flow path sink 应包含 sys_in (in data port)")

    if "sys_out" not in sink_lower:
        result.add_pass()
    else:
        result.add_fail("flow path sink 不应包含 sys_out (out data port)")

    return result


# ============================================================
# 10. 属性名合法性
# ============================================================
def test_property_names(sym: FullAadlSymbolTable) -> TestResult:
    """验证属性名的合法性"""
    result = TestResult("10. 属性名合法性")

    # system 类别属性
    sys_props = sym.get_valid_properties("system")
    sys_lower = _lower_list(sys_props)

    if "actual_processor_binding" in sys_lower:
        result.add_pass()
    else:
        result.add_fail("system 属性应包含 Actual_Processor_Binding")

    if "actual_memory_binding" in sys_lower:
        result.add_pass()
    else:
        result.add_fail("system 属性应包含 Actual_Memory_Binding")

    # thread 类别属性
    thread_props = sym.get_valid_properties("thread")
    thread_lower = _lower_list(thread_props)

    if "dispatch_protocol" in thread_lower:
        result.add_pass()
    else:
        result.add_fail("thread 属性应包含 Dispatch_Protocol")

    if "period" in thread_lower:
        result.add_pass()
    else:
        result.add_fail("thread 属性应包含 Period")

    # data 类别不应该有 Dispatch_Protocol
    data_props = sym.get_valid_properties("data")
    data_lower = _lower_list(data_props)
    if "dispatch_protocol" not in data_lower:
        result.add_pass()
    else:
        result.add_fail("data 属性不应包含 Dispatch_Protocol")

    return result


# ============================================================
# 11. 命名唯一性
# ============================================================
def test_naming_uniqueness(sym: FullAadlSymbolTable) -> TestResult:
    """验证命名唯一性（已定义的名字不能再用）"""
    result = TestResult("11. 命名唯一性")

    used = sym.get_used_component_names()
    used_lower = _lower_list(used)

    if "my_system" in used_lower:
        result.add_pass()
    else:
        result.add_fail("已定义的 my_system 应在命名集合里")

    if "my_thread" in used_lower:
        result.add_pass()
    else:
        result.add_fail("已定义的 my_thread 应在命名集合里")

    if "my_processor" in used_lower:
        result.add_pass()
    else:
        result.add_fail("已定义的 my_processor 应在命名集合里")

    if "new_component" not in used_lower:
        result.add_pass()
    else:
        result.add_fail("未定义的 new_component 不应在命名集合里")

    return result


# ============================================================
# 主函数
# ============================================================
def main():
    sym = build_test_symbols()

    tests = [
        test_containment_rules(sym),
        test_subcomp_classifier(sym),
        test_port_direction(sym),
        test_port_type_match(sym),
        test_binding_constraints(sym),
        test_impl_base_type(sym),
        test_port_data_type(sym),
        test_conn_bound_bus(sym),
        test_flow_endpoints(sym),
        test_property_names(sym),
        test_naming_uniqueness(sym),
    ]

    print("=" * 60)
    print("语义规则单元验证（11 条规则）")
    print("=" * 60)
    print()

    total_pass = 0
    total_fail = 0
    for t in tests:
        print(t.summary())
        total_pass += t.passed
        total_fail += t.failed
        if t.failures and t.failed <= 5:
            for f in t.failures:
                print(f"    ❌ {f}")
        elif t.failures:
            print(f"    （共 {t.failed} 个失败，略）")
        print()

    print("=" * 60)
    total = total_pass + total_fail
    rate = total_pass / total * 100 if total > 0 else 0
    print(f"总计: {total_pass}/{total} 通过 ({rate:.1f}%)")
    print("=" * 60)


if __name__ == "__main__":
    main()
