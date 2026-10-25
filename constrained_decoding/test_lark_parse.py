"""验证 Lark 语法能否解析真实 AADL 代码"""
from lark import Lark

with open('grammars/aadl_core.lark', 'r', encoding='utf-8') as f:
    grammar = f.read()

parser = Lark(grammar, start='start')

# 测试用例 1: 简单 package + system type
test1 = """package MyPackage
public
system FlightControl
  features
    sensor_data: in data port Base_Types::Integer;
    command: out data port Base_Types::Float;
  end FlightControl;
end MyPackage;
"""

# 测试用例 2: 带组件实现 + connections
test2 = """package FlightSys
public
data Int32
end Int32;

system Sensor
  features
    output: out data port Int32;
  end Sensor;

system Sensor.impl
  subcomponents
    dev: device SensorDevice;
  connections
    c1: dev.output -> output;
  end Sensor.impl;
end FlightSys;
"""

# 测试用例 3: 带 modes 和 properties
test3 = """package ModeTest
public
system Controller
  features
    input: in data port Data::Float;
    output: out data port Data::Float;
  modes
    normal: initial mode;
    degraded: mode;
  properties
    Dispatch_Protocol => Periodic;
  end Controller;
end ModeTest;
"""

# 测试用例 4: 带 flows
test4 = """package FlowTest
public
system Pipe
  features
    in_port: in data port Data::Float;
    out_port: out data port Data::Float;
  flows
    f1: path flow in_port -> out_port;
  end Pipe;

system Pipe.impl
  subcomponents
    p1: process Proc;
  flows
    f1: flow in_port -> p1.in_port -> p1.out_port -> out_port;
  end Pipe.impl;
end FlowTest;
"""

for i, test in enumerate([test1, test2, test3, test4], 1):
    try:
        tree = parser.parse(test)
        print(f"测试用例 {i}: 通过 ✓")
    except Exception as e:
        print(f"测试用例 {i}: 失败 ✗")
        print(f"  错误: {e}")
