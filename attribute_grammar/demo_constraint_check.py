#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
属性文法约束解码 - Demo 验证脚本
=================================
不依赖 LLM，直接用已知的 AADL 代码验证：
1. 属性环境能否正确提取语义信息
2. 语义约束检查是否正确（包含规则、引用完整性、端口匹配）
3. 违反约束时能否正确报错

用法:
    python demo_constraint_check.py
"""

import os
import sys
import traceback

# 把项目根目录加入 path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core import IncrementalAttributeParser, AttributeEnvironment


GRAMMAR_PATH = os.path.join(os.path.dirname(__file__), "grammars", "aadl_mvp.lark")


# ============================================================
# 测试用例 1：合法的 AADL 代码（应该通过）
# ============================================================
VALID_AADL = """package FlightControl is
public
  data SensorData
  end SensorData;

  data ControlCommand
  end ControlCommand;

  system Sensor
    features
      out_port : out data port SensorData;
  end Sensor;

  system Controller
    features
      in_port : in data port SensorData;
      out_port : out data port ControlCommand;
  end Controller;

  system Actuator
    features
      in_port : in data port ControlCommand;
  end Actuator;

  system FlightSys
  end FlightSys;

  system implementation FlightSys.impl
    subcomponents
      sensor1 : system Sensor;
      controller1 : system Controller;
      actuator1 : system Actuator;
    connections
      c1 : port sensor1.out_port -> controller1.in_port;
      c2 : port controller1.out_port -> actuator1.in_port;
  end FlightSys.impl;
end FlightControl;
"""


def test_valid_aadl():
    """测试：合法 AADL 代码应该通过所有检查"""
    print("=" * 60)
    print("测试 1：合法 AADL 代码")
    print("=" * 60)

    parser = IncrementalAttributeParser(GRAMMAR_PATH)
    try:
        env = parser.parse_full_text(VALID_AADL)
        print("✅ 解析成功，所有约束通过")
        print()
        print("  包:", list(env.packages.keys()))
        pkg = env.packages["FlightControl"]
        print("  组件类型:", list(pkg.component_types.keys()))
        print("  组件实现:", list(pkg.component_impls.keys()))
        print()
        # 检查 Sensor 的 features
        sensor = pkg.component_types["Sensor"]
        print(f"  Sensor.features: {list(sensor.features.keys())}")
        print(f"    out_port: direction={sensor.features['out_port'].direction}, type={sensor.features['out_port'].data_type}")
        print()
        # 检查 FlightSys.impl 的子组件和连接
        impl = pkg.component_impls["FlightSys.impl"]
        print(f"  FlightSys.impl 子组件: {list(impl.subcomponents.keys())}")
        print(f"  FlightSys.impl 连接数: {len(impl.connections)}")
        for conn in impl.connections:
            print(f"    {conn.name}: {'.'.join(conn.source_path)} -> {'.'.join(conn.target_path)}")
        print()
        return True
    except Exception as e:
        print(f"❌ 解析失败: {e}")
        traceback.print_exc()
        return False


# ============================================================
# 测试用例 2：包含规则违反（process 里包含 system）
# ============================================================
INVALID_CONTAINMENT = """package Test is
public
  system BadSys
  end BadSys;

  process Proc
  end Proc;

  process implementation Proc.impl
    subcomponents
      bad : system BadSys;
  end Proc.impl;
end Test;
"""


def test_containment_violation():
    """测试：包含规则违反应该报错"""
    print("=" * 60)
    print("测试 2：包含规则违反（process 包含 system）")
    print("=" * 60)

    parser = IncrementalAttributeParser(GRAMMAR_PATH)
    try:
        env = parser.parse_full_text(INVALID_CONTAINMENT)
        print("❌ 应该报错但没有报错！")
        return False
    except AssertionError as e:
        print(f"✅ 正确捕获到包含规则违反: {e}")
        return True
    except Exception as e:
        print(f"⚠️  其他错误: {e}")
        return False


# ============================================================
# 测试用例 3：引用完整性违反（引用不存在的类型）
# ============================================================
INVALID_REFERENCE = """package Test is
public
  system Foo
    features
      f1 : in data port NonExistentType;
  end Foo;
end Test;
"""


def test_reference_violation():
    """测试：引用不存在的类型应该报错"""
    print("=" * 60)
    print("测试 3：引用完整性违反（引用不存在的类型）")
    print("=" * 60)

    parser = IncrementalAttributeParser(GRAMMAR_PATH)
    try:
        env = parser.parse_full_text(INVALID_REFERENCE)
        print("❌ 应该报错但没有报错！")
        return False
    except AssertionError as e:
        print(f"✅ 正确捕获到引用完整性违反: {e}")
        return True
    except Exception as e:
        print(f"⚠️  其他错误: {e}")
        return False


# ============================================================
# 测试用例 4：端口方向不匹配（连接 in -> in）
# ============================================================
INVALID_PORT_DIRECTION = """package Test is
public
  data D
  end D;

  system A
    features
      f1 : in data port D;
  end A;

  system B
    features
      f1 : in data port D;
  end B;

  system Sys
  end Sys;

  system implementation Sys.impl
    subcomponents
      a : system A;
      b : system B;
    connections
      bad_conn : port a.f1 -> b.f1;
  end Sys.impl;
end Test;
"""


def test_port_direction_violation():
    """测试：端口方向不匹配应该报错"""
    print("=" * 60)
    print("测试 4：端口方向不匹配（in -> in）")
    print("=" * 60)

    parser = IncrementalAttributeParser(GRAMMAR_PATH)
    try:
        env = parser.parse_full_text(INVALID_PORT_DIRECTION)
        print("❌ 应该报错但没有报错！")
        return False
    except AssertionError as e:
        print(f"✅ 正确捕获到端口方向违反: {e}")
        return True
    except Exception as e:
        print(f"⚠️  其他错误: {e}")
        traceback.print_exc()
        return False


# ============================================================
# 测试用例 5：数据类型不匹配
# ============================================================
INVALID_DATA_TYPE = """package Test is
public
  data D1
  end D1;

  data D2
  end D2;

  system A
    features
      out : out data port D1;
  end A;

  system B
    features
      inp : in data port D2;
  end B;

  system Sys
  end Sys;

  system implementation Sys.impl
    subcomponents
      a : system A;
      b : system B;
    connections
      bad_conn : port a.out -> b.inp;
  end Sys.impl;
end Test;
"""


def test_data_type_violation():
    """测试：数据类型不匹配应该报错"""
    print("=" * 60)
    print("测试 5：数据类型不匹配（D1 -> D2）")
    print("=" * 60)

    parser = IncrementalAttributeParser(GRAMMAR_PATH)
    try:
        env = parser.parse_full_text(INVALID_DATA_TYPE)
        print("❌ 应该报错但没有报错！")
        return False
    except AssertionError as e:
        print(f"✅ 正确捕获到数据类型不匹配: {e}")
        return True
    except Exception as e:
        print(f"⚠️  其他错误: {e}")
        return False


# ============================================================
# 测试用例 6：增量式验证 - 属性环境的查询功能
# ============================================================
def test_env_queries():
    """测试属性环境的查询接口"""
    print("=" * 60)
    print("测试 6：属性环境查询接口")
    print("=" * 60)

    parser = IncrementalAttributeParser(GRAMMAR_PATH)
    env = parser.parse_full_text(VALID_AADL)
    pkg = env.packages["FlightControl"]

    # 切换到 FlightSys.impl 作用域来测试查询
    env.current_package = pkg
    env.current_impl = pkg.component_impls["FlightSys.impl"]

    # 测试：按类别查类型
    data_types = env.get_available_types_by_category("data")
    print(f"  data 类别的类型: {data_types}")
    assert set(data_types) == {"SensorData", "ControlCommand"}, f"期望两个 data 类型，实际 {data_types}"

    # 测试：允许的子组件类别
    allowed = env.get_allowed_subcomponent_categories()
    print(f"  system 允许的子组件类别: {sorted(allowed)[:5]}...")
    assert "system" in allowed and "process" in allowed, "system 应该能包含 system 和 process"

    # 测试：子组件名查询
    subcomps = env.get_subcomponent_names()
    print(f"  FlightSys.impl 子组件: {subcomps}")
    assert set(subcomps) == {"sensor1", "controller1", "actuator1"}

    # 测试：子组件的端口查询
    sensor_ports = env.get_available_features("sensor1")
    print(f"  sensor1 的端口: {sensor_ports}")
    assert "out_port" in sensor_ports

    # 测试：按方向查端口
    out_ports = env.get_available_features("controller1", direction="out")
    in_ports = env.get_available_features("controller1", direction="in")
    print(f"  controller1 的 out 端口: {out_ports}")
    print(f"  controller1 的 in 端口: {in_ports}")
    assert "out_port" in out_ports and "in_port" not in out_ports
    assert "in_port" in in_ports and "out_port" not in in_ports

    print("✅ 所有查询接口正常")
    print()
    return True


# ============================================================
# 主函数
# ============================================================
def main():
    print()
    print("🚀 属性文法约束解码 - Demo 验证")
    print()

    tests = [
        test_valid_aadl,
        test_containment_violation,
        test_reference_violation,
        test_port_direction_violation,
        test_data_type_violation,
        test_env_queries,
    ]

    passed = 0
    failed = 0

    for test in tests:
        try:
            if test():
                passed += 1
            else:
                failed += 1
        except Exception as e:
            print(f"❌ 测试异常: {e}")
            traceback.print_exc()
            failed += 1
        print()

    print("=" * 60)
    print(f"测试结果：{passed} 通过，{failed} 失败")
    print("=" * 60)

    return failed == 0


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
