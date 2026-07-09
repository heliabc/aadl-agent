# AADL 语法指南

## 组件类型

AADL 定义了多种组件类型，每种类型用于描述不同的系统元素：

### 软件组件
- **thread**：可执行的线程组件，是系统中最基本的执行单元
- **process**：进程组件，包含多个线程，共享地址空间
- **data**：数据组件，用于在组件之间传递数据
- **subprogram**：子程序组件，可被线程调用的函数

### 硬件组件
- **processor**：处理器组件，执行软件线程
- **memory**：内存组件，存储数据和程序
- **device**：设备组件，如传感器、执行器等
- **bus**：总线组件，连接硬件组件

### 复合组件
- **system**：系统组件，可包含其他组件的容器

## 组件声明语法

```aadl
package PackageName
public
    component-type component-name is
        features
            feature-name: feature-type;
        end component-name;
end PackageName;
```

### 示例

```aadl
package OS_Arch
public
    thread SensorThread is
        features
            ReadData: in data port;
            SendData: out data port;
        end SensorThread;
    
    process SensorProcess is
        subcomponents
            Thread1: thread SensorThread;
        connections
            port Thread1.ReadData -> ...;
        end SensorProcess;
    
    system Top_System is
        subcomponents
            Process1: process SensorProcess;
            CPU: processor;
        connections
            binding Process1 -> CPU;
        end Top_System;
end OS_Arch;
```

## 特性（Features）

特性用于定义组件的接口，包括：

- **port**：端口，用于数据或事件传递
  - `in`：输入端口
  - `out`：输出端口
  - `inout`：双向端口
- **event port**：事件端口
- **data port**：数据端口
- **subprogram access**：子程序访问点

## 连接（Connections）

连接用于建立组件之间的通信关系：

```aadl
connections
    port Component1.OutputPort -> Component2.InputPort;
    event port EventSource.EventOut -> EventSink.EventIn;
    binding ProcessComponent -> ProcessorComponent;
```

## 属性（Properties）

属性用于为组件添加额外的元信息：

```aadl
properties
    Timing_Properties::Execution_Time => 10 ms;
    Memory_Properties::Memory_Size => 64 Kbytes;
```

## 组件实现（Implementation）

组件可以有实现部分，定义其内部结构：

```aadl
thread SensorThread implementation impl is
    subcomponents
        Reader: subprogram DataReader;
    connections
        port Reader.Output -> self.ReadData;
    end impl;
```

## 包（Package）

AADL 文件以包开始和结束：

```aadl
package PackageName
public
    -- 组件声明
end PackageName;
```

## 关键字大小写

AADL 关键字不区分大小写，但通常约定使用小写。组件名称和特性名称区分大小写。

## 分号规则

每个语句必须以分号结尾，包括：
- 组件声明结束：`end component-name;`
- 包结束：`end PackageName;`
- 特性声明：`feature-name: feature-type;`
- 连接声明：`port ... -> ...;`