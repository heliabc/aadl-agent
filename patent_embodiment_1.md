# 具体实施方式

## 实施例一

如图1所示，本实施例提供一种基于共享组件基准的AADL模型智能生成与修复方法。目标系统为车辆制动控制系统，其架构包括顶层系统组件BrakeSystem、制动设备组件BrakeSensor、制动控制进程组件BrakeControlProcess、处理器组件BrakeCPU及位于制动控制进程内部的制动控制线程组件BrakeControlTask。制动设备组件采集制动信号，制动控制进程组件组织制动逻辑处理，制动控制线程组件执行制动控制运算，处理器组件提供运算执行资源。

**步骤S1：获取需求文档并进行需求分析，形成结构化需求数据。**

接收原始需求文档，其内容为非结构化文本，描述如下：

"本系统为车辆制动控制系统。制动传感器采集制动踏板信号，输出制动强度数据。制动控制进程接收制动强度数据，由内部制动控制线程执行制动力分配计算。系统运行于BrakeCPU处理器上。要求制动传感器具有制动信号输出端口，制动控制进程具有制动信号输入端口，制动控制线程具有制动信号输入端口。制动传感器的输出端口连接至制动控制进程的输入端口，制动控制进程的输入端口委派至内部线程的输入端口。"

需求文档字符长度为280字符，不超过预设直接处理阈值10000字符，直接通过大语言模型将非结构化需求文本转换为结构化需求数据，不执行分块操作。所述结构化需求数据包括需求编号、标题、描述、优先级、验收标准和依赖关系。解析结果如下：

| 需求编号 | 标题 | 描述 | 优先级 | 验收标准 | 依赖关系 |
|---------|------|------|--------|---------|---------|
| R01 | 制动信号采集 | 制动传感器采集制动踏板信号，输出制动强度数据 | 高 | 输出端口存在且数据类型为Integer | 无 |
| R02 | 制动控制处理 | 制动控制进程接收制动强度数据并组织处理 | 高 | 输入端口存在且数据类型为Integer | R01 |
| R03 | 制动力分配运算 | 制动控制线程执行制动力分配计算 | 高 | 线程具有输入端口 | R02 |
| R04 | 处理器部署 | 系统运行于BrakeCPU处理器上 | 中 | 存在处理器绑定属性 | R03 |

当需求文档长度超过直接处理阈值时，将文档按预设分块大小8000字符和重叠比例15%切分为多个分块，各分块并行调用大语言模型进行解析后合并结果。合并过程包括基于标题和描述的Jaccard相似度去重，以及数值约束矛盾的冲突检测。

**步骤S2：获取架构描述及模块约束。**

将步骤S1所得结构化需求数据输入架构设计单元，输出以树状层级结构表征的架构描述。架构描述中，BrakeSystem为根组件，其下包含BrakeSensor（device类别）、BrakeControlProcess（process类别）、BrakeCPU（processor类别）；BrakeControlTask（thread类别）为BrakeControlProcess的子组件。各组件的类别和层级关系符合预设AADL组件包含规则：system允许包含process、processor、device；process允许包含thread。

将结构化需求数据和架构描述输入模块分析单元，输出模块约束。模块约束表征端口要求和连接要求：BrakeSensor具有out data port，端口名为brake_out，数据类型为Integer；BrakeControlProcess具有in data port，端口名为brake_in，数据类型为Integer；BrakeControlTask具有in data port，端口名为task_in，数据类型为Integer。连接要求为：BrakeSensor的输出端口brake_out连接至BrakeControlProcess的输入端口brake_in；BrakeControlProcess的输入端口brake_in委派至内部BrakeControlTask的输入端口task_in。模块约束中还包含模块层次信息，表征各模块对应的组件层级路径，如制动控制模块对应的组件层次为BrakeSystem → BrakeControlProcess → BrakeControlTask。

**步骤S3：对架构描述和模块约束进行解析，形成共享组件基准及生成约束描述。**

如图2所示，通过输入解析器对架构描述和模块约束进行统一解析。所述解析器从架构描述中提取组件信息，形成共享组件基准；从架构描述和模块约束中提取组件组成要求和模块要求，形成生成约束描述。两者来源于同一解析过程，保证生成阶段和校验阶段针对同一组组件信息进行处理。

共享组件基准如下：

| 组件名称 | 类别 | 父组件 | 子组件 | 特征 |
|---------|------|--------|--------|------|
| BrakeSystem | system | 无 | BrakeSensor, BrakeControlProcess, BrakeCPU | 无 |
| BrakeSensor | device | BrakeSystem | 无 | brake_out : out data port Integer |
| BrakeControlProcess | process | BrakeSystem | BrakeControlTask | brake_in : in data port Integer |
| BrakeControlTask | thread | BrakeControlProcess | 无 | task_in : in data port Integer |
| BrakeCPU | processor | BrakeSystem | 无 | 无 |

端口特征brake_out、brake_in、task_in分别归属于其所在组件，与参与组件对应的组件相区分，不作为独立组件参与一对一匹配。

生成约束描述以文本清单形式表征待生成模型的组件组成及模块要求，内容如下：

```
系统名称：BrakeSystem
组件组成：
  - BrakeSensor (device)，特征：brake_out : out data port Integer
  - BrakeControlProcess (process)，特征：brake_in : in data port Integer
    - BrakeControlTask (thread)，特征：task_in : in data port Integer
  - BrakeCPU (processor)
连接要求：
  - BrakeSensor.brake_out -> BrakeControlProcess.brake_in
  - BrakeControlProcess.brake_in -> BrakeControlTask.task_in (输入委派)
```

**步骤S4：将所述生成约束描述编入大语言模型的生成提示，生成候选AADL模型。**

将生成约束描述编入大语言模型的生成提示。同时，通过检索增强生成获得领域知识上下文：根据当前生成任务的类型对查询"AADL brake system model"进行改写，对领域知识库同时执行向量语义检索和关键词检索，采用倒数排名融合算法将两路结果融合为统一排序，经重排序后取前K条作为领域知识上下文注入生成提示。所述领域知识库包括AADL语法基础知识条目、示例知识条目和代码示例条目。

大语言模型根据生成提示输出的候选AADL模型如下：

```
package BrakeSystem_Pkg
public

  device BrakeSensor
  features
    brake_out : out data port Integer;
  end BrakeSensor;

  process BrakeControlProcess
  features
    brake_in : in data port Integer;
  end BrakeControlProcess;

  thread BrakeControlTask
  features
    task_in : in data port Integer;
  end BrakeControlTask;

  processor BrakeCPU
  end BrakeCPU;

  system BrakeSystem
  end BrakeSystem;

  system implementation BrakeSystem.impl
  subcomponents
    sensor : device BrakeSensor;
    proc : process BrakeControlProcess;
    cpu : processor BrakeCPU;
  connections
    c1 : port sensor.brake_out -> proc.brake_in;
  properties
    Actual_Processor_Binding => (reference (cpu)) applies to proc;
  end BrakeSystem.impl;

end BrakeSystem_Pkg;
```

在本实施例中，候选模型未包含BrakeControlTask的implementation声明，且未包含BrakeControlProcess的implementation声明，BrakeControlTask未被实例化。后续组件结构校验将检出这些问题。

**步骤S5：建立组件对应关系，实施组件结构校验。**

如图3所示，解析候选AADL模型，获得模型结构信息，包括组件声明、组件实例引用、组件特征及连接关系。依据共享组件基准，建立候选AADL模型中的组件与架构描述中的组件之间的对应关系。

对候选AADL模型中的组件和共享组件基准中的组件按类别分组。同类别组件的名称规范化处理包括统一字符大小写、去除预设前缀或后缀修饰部分及去除分隔字符。本实施例中，候选模型中的组件名称为"BrakeSensor"，基准中的组件名称同为"BrakeSensor"，规范化后均为"brakesensor"，名称相似度为1.0，达到预设匹配阈值0.85，建立一对一对应关系。各组件对应结果如下：

| 候选模型组件名 | 基准组件名 | 类别 | 规范化后名称 | 相似度 | 对应状态 |
|--------------|-----------|------|------------|--------|---------|
| BrakeSensor | BrakeSensor | device | brakesensor | 1.0 | 已对应 |
| BrakeControlProcess | BrakeControlProcess | process | brakecontrolprocess | 1.0 | 已对应 |
| BrakeControlTask | BrakeControlTask | thread | brakecontroltask | 1.0 | 已对应 |
| BrakeCPU | BrakeCPU | processor | brakecpu | 1.0 | 已对应 |
| BrakeSystem | BrakeSystem | system | brakesystem | 1.0 | 已对应 |

组件结构校验包括组件对应校验、组件声明及引用校验，以及组件类别包含关系校验。本实施例中，所有组件均建立对应关系，无组件缺失和额外组件。组件声明及引用校验发现以下错误：

错误1（组件声明缺失）：候选模型中，BrakeControlProcess缺少implementation声明，仅有type声明。该process在BrakeSystem.impl中被引用为`proc : process BrakeControlProcess`，但缺少`process implementation BrakeControlProcess.impl`，形成组件声明缺失错误信息。

错误2（组件声明缺失）：候选模型中，BrakeControlTask缺少implementation声明，仅有type声明。形成组件声明缺失错误信息。

错误3（子组件缺失）：共享组件基准中BrakeControlProcess包含子组件BrakeControlTask，但候选模型中BrakeControlProcess无implementation声明，其子组件BrakeControlTask未被实例化。形成子组件缺失信息。

**步骤S6：存在结构错误时进行修正，重新解析及组件结构复查。**

针对错误1，补充BrakeControlProcess的implementation声明，并在其中实例化BrakeControlTask，同时建立输入委派连接：

```
process implementation BrakeControlProcess.impl
  subcomponents
    task : thread BrakeControlTask.impl;
  connections
    c_delegate : port brake_in -> task.task_in;
end BrakeControlProcess.impl;
```

将BrakeSystem.impl中proc的引用修改为指向implementation：

```
proc : process BrakeControlProcess.impl;
```

针对错误2，补充BrakeControlTask的implementation声明：

```
thread implementation BrakeControlTask.impl
end BrakeControlTask.impl;
```

修正后的模型重新解析，形成更新的组件声明、实例引用、特征及连接关系。重新建立与共享组件基准之间的组件对应关系，对应关系不变。组件结构复查结果：错误1、错误2和错误3均已消除，剩余组件结构错误数量为0。

**步骤S7：根据剩余结构错误数量，选择性执行连接与属性校验。**

剩余组件结构错误数量为0，不大于预设校验阈值2，依据当前模型结构信息执行连接与属性校验。

连接端点存在性校验：连接c1的源端sensor.brake_out中，sensor实例对应的组件BrakeSensor的features中声明了brake_out端口，存在性校验通过。目标端proc.brake_in中，proc实例对应的组件BrakeControlProcess的features中声明了brake_in端口，存在性校验通过。委派连接c_delegate的源端brake_in为BrakeControlProcess自身特征，目标端task.task_in为子组件BrakeControlTask的特征，端点存在性校验通过。

连接方向校验：连接c1为port连接，源端brake_out为out方向，目标端brake_in为in方向，数据从输出端口流向输入端口，方向校验通过。委派连接c_delegate为port连接，源端brake_in为BrakeControlProcess自身特征（in方向），目标端task.task_in为子组件BrakeControlTask的特征（in方向），属于父组件自身特征与内部子组件特征的委派连接，按照父子委派连接规则，in方向的父组件端口委派至in方向的子组件端口，方向校验通过。

属性引用校验：属性Actual_Processor_Binding的应用目标为proc，在BrakeSystem.impl的subcomponents中存在proc实例声明，属性引用校验通过。属性值(reference (cpu))中，cpu为子组件实例名称，在subcomponents中存在声明，引用校验通过。属性值的外层列表定界符为括号对()，格式校验通过。

连接与属性校验未发现错误，第二校验结果为空。

**步骤S8：根据校验结果实施规则修正。**

第一校验结果和第二校验结果均未发现符合预设修正规则的模型内容，不实施规则修正。当前AADL模型即为步骤S6结构修正后的模型。

**步骤S9：输出当前AADL模型及校验信息。**

输出的AADL模型如下：

```
package BrakeSystem_Pkg
public

  device BrakeSensor
  features
    brake_out : out data port Integer;
  end BrakeSensor;

  process BrakeControlProcess
  features
    brake_in : in data port Integer;
  end BrakeControlProcess;

  process implementation BrakeControlProcess.impl
  subcomponents
    task : thread BrakeControlTask.impl;
  connections
    c_delegate : port brake_in -> task.task_in;
  end BrakeControlProcess.impl;

  thread BrakeControlTask
  features
    task_in : in data port Integer;
  end BrakeControlTask;

  thread implementation BrakeControlTask.impl
  end BrakeControlTask.impl;

  processor BrakeCPU
  end BrakeCPU;

  system BrakeSystem
  end BrakeSystem;

  system implementation BrakeSystem.impl
  subcomponents
    sensor : device BrakeSensor;
    proc : process BrakeControlProcess.impl;
    cpu : processor BrakeCPU;
  connections
    c1 : port sensor.brake_out -> proc.brake_in;
  properties
    Actual_Processor_Binding => (reference (cpu)) applies to proc;
  end BrakeSystem.impl;

end BrakeSystem_Pkg;
```

输出的校验信息如下：

| 阶段 | 类型 | 内容 | 状态 |
|------|------|------|------|
| 组件结构校验 | 错误 | BrakeControlProcess缺少implementation声明 | 已修正 |
| 组件结构校验 | 错误 | BrakeControlTask缺少implementation声明 | 已修正 |
| 组件结构校验 | 错误 | BrakeControlTask未被实例化 | 已修正 |
| 组件结构复查 | — | 修正后复查，剩余结构错误数量为0 | 通过 |
| 连接与属性校验 | — | 连接端点存在性、方向、属性引用及格式均通过 | 通过 |
| 规则修正 | — | 无符合修正规则的模型内容 | 无修正 |

本实施例中，共享组件基准在生成约束描述形成和候选模型组件校验两个过程中提供共同参照；结构修正改变候选模型后，以重新解析所得模型结构信息支撑后续校验；分层校验机制根据结构错误数量决定是否继续执行连接与属性校验，避免无意义的计算和混杂的错误信息。

实施例一
