# 具体实施方式

## 实施例一

如图1所示，本实施例提供一种基于共享组件基准的AADL模型智能生成与修复方法。目标系统为车辆制动控制系统，其架构包括顶层系统组件BrakeSystem、制动设备组件BrakeSensor、制动控制进程组件BrakeControlProcess、处理器组件BrakeCPU及位于制动控制进程内部的制动控制线程组件BrakeControlTask。制动设备组件采集制动踏板信号并输出制动强度数据，制动控制进程组件接收制动强度数据并组织内部线程执行制动力分配运算，处理器组件提供运算执行资源。

**步骤S1：获取需求文档并进行需求分析，形成结构化需求数据。**

接收原始需求文档，其内容为非结构化文本：

"本系统为车辆制动控制系统。制动传感器采集制动踏板信号，输出制动强度数据。制动控制进程接收制动强度数据，由内部制动控制线程执行制动力分配计算。系统运行于BrakeCPU处理器上。要求制动传感器具有制动信号输出端口，制动控制进程具有制动信号输入端口，制动控制线程具有制动信号输入端口。制动传感器的输出端口连接至制动控制进程的输入端口，制动控制进程的输入端口委派至内部线程的输入端口。"

需求文档字符长度不超过预设直接处理阈值10000字符，直接通过大语言模型将非结构化需求文本转换为结构化需求数据，不执行分块操作。所述结构化需求数据包括需求编号、标题、描述、优先级、验收标准和依赖关系。解析结果如下：

| 需求编号 | 标题 | 描述 | 优先级 | 验收标准 | 依赖关系 |
|---------|------|------|--------|---------|---------|
| R01 | 制动信号采集 | 制动传感器采集制动踏板信号，输出制动强度数据 | 高 | 输出端口存在 | 无 |
| R02 | 制动控制处理 | 制动控制进程接收制动强度数据并组织内部线程处理 | 高 | 输入端口存在 | R01 |
| R03 | 制动力分配运算 | 制动控制线程执行制动力分配计算 | 高 | 线程具有输入端口 | R02 |
| R04 | 处理器部署 | 系统运行于BrakeCPU处理器上 | 中 | 存在处理器绑定属性 | R03 |

当需求文档长度超过直接处理阈值时，将文档按预设分块大小8000字符和重叠比例15%切分为多个分块，各分块并行调用大语言模型进行解析后合并结果。合并过程包括基于标题和描述的Jaccard相似度去重，以及数值约束矛盾的冲突检测。

**步骤S2：获取架构描述及模块约束。**

将步骤S1所得结构化需求数据输入架构设计智能体，输出以树状层级结构表征的架构描述。架构描述中，BrakeSystem为根组件，其下包含BrakeSensor（device类别）、BrakeControlProcess（process类别）、BrakeCPU（processor类别）；BrakeControlTask（thread类别）为BrakeControlProcess的子组件。各组件的类别和层级关系符合预设AADL组件包含规则：system允许包含process、processor、device；process允许包含thread。

将结构化需求数据和架构描述输入模块设计智能体，输出模块约束。模块约束表征端口要求和连接要求：BrakeSensor具有out data port，端口名为brake_out；BrakeControlProcess具有in data port，端口名为brake_in；BrakeControlTask具有in data port，端口名为task_in。连接要求为：BrakeSensor的输出端口brake_out连接至BrakeControlProcess的输入端口brake_in；BrakeControlProcess的输入端口brake_in委派至内部BrakeControlTask的输入端口task_in。模块约束中还包含模块层次信息，表征各模块对应的组件层级路径，如制动控制模块对应的组件层次为BrakeSystem → BrakeControlProcess → BrakeControlTask。模块约束还包含属性要求：制动控制进程部署于BrakeCPU上。

**步骤S3：对架构描述和模块约束进行解析，形成共享组件基准及生成约束描述。**

如图2所示，通过输入解析器对架构描述和模块约束进行统一解析。所述解析器从架构描述中提取组件信息，形成共享组件基准；从架构描述和模块约束中提取组件组成要求和模块要求，形成生成约束描述。两者来源于同一解析过程，保证生成阶段和校验阶段针对同一组组件信息进行处理。

共享组件基准如下：

| 组件名称 | 类别 | 父组件 | 子组件 | 特征 |
|---------|------|--------|--------|------|
| BrakeSystem | system | 无 | BrakeSensor, BrakeControlProcess, BrakeCPU | 无 |
| BrakeSensor | device | BrakeSystem | 无 | brake_out : out data port |
| BrakeControlProcess | process | BrakeSystem | BrakeControlTask | brake_in : in data port |
| BrakeControlTask | thread | BrakeControlProcess | 无 | task_in : in data port |
| BrakeCPU | processor | BrakeSystem | 无 | 无 |

端口特征brake_out、brake_in、task_in分别归属于其所在组件，与参与组件对应的组件相区分，不作为独立组件参与一对一匹配。

生成约束描述以文本清单形式表征待生成模型的组件组成及模块要求，内容如下：

```
系统名称：BrakeSystem
组件组成：
  - BrakeSensor (device)，特征：brake_out : out data port
  - BrakeControlTask (thread)，特征：task_in : in data port
  - BrakeControlProcess (process)，特征：brake_in : in data port
    - 内部子组件：ctrl_task : thread BrakeControlTask.impl
    - 内部连接：brake_in -> ctrl_task.task_in (输入委派)
  - BrakeCPU (processor)
连接要求：
  - BrakeSensor.brake_out -> BrakeControlProcess.brake_in
属性要求：
  - Actual_Processor_Binding => (reference (cpu)) applies to proc
```

**步骤S4：将所述生成约束描述编入大语言模型的生成提示，生成候选AADL模型。**

将生成约束描述编入大语言模型的生成提示。同时，通过检索增强生成获得领域知识上下文：根据当前生成任务的类型对查询进行改写，对领域知识库同时执行向量语义检索和关键词检索，采用倒数排名融合算法将两路结果融合为统一排序，经重排序后取前K条作为领域知识上下文注入生成提示。所述领域知识库包括AADL语法基础知识条目、示例知识条目和代码示例条目。

大语言模型根据生成提示输出的候选AADL模型如下：

```
package BrakeSystem_Pkg
public

  device BrakeSensor
  features
    brake_out : out data port;
  end BrakeSensor;

  thread BrakeControlTask
  features
    task_in : in data port;
  end BrakeControlTask;

  thread implementation BrakeControlTask.impl
  end BrakeControlTask.impl;

  process BrakeControlProcess
  features
    brake_in : in data port;
  end BrakeControlProcess;

  processor BrakeCPU
  end BrakeCPU;

  system BrakeSystem
  end BrakeSystem;

  system implementation BrakeSystem.impl
  subcomponents
    sensor : device BrakeSensor;
    proc   : process BrakeControlProcess;
    cpu    : processor BrakeCPU;
  connections
    c1 : port sensor.brake_out -> proc.brake_in;
  properties
  Actual_Processor_Binding => (reference (cpu)) applies to proc;
  end BrakeSystem.impl;

end BrakeSystem_Pkg;
```

在本实施例中，候选模型存在以下问题：BrakeControlProcess缺少implementation声明，BrakeControlTask未被实例化，进程内部缺少输入委派连接。后续组件结构校验将检出这些问题。

**步骤S5：建立组件对应关系，实施组件结构校验。**

如图3所示，解析候选AADL模型，获得模型结构信息，包括组件声明、组件实例引用、组件特征及连接关系。依据共享组件基准，建立候选AADL模型中的组件与架构描述中的组件之间的对应关系。

对候选AADL模型中的组件和共享组件基准中的组件按类别分组。同类别组件的名称规范化处理包括统一字符大小写、去除预设前缀或后缀修饰部分及去除分隔字符。本实施例中，候选模型中的组件名称与基准中的组件名称经规范化后一致，相似度为1.0，达到预设匹配阈值0.85，建立一对一对应关系。各组件对应结果如下：

| 候选模型组件名 | 基准组件名 | 类别 | 规范化后名称 | 相似度 | 对应状态 |
|--------------|-----------|------|------------|--------|---------|
| BrakeSensor | BrakeSensor | device | brakesensor | 1.0 | 已对应 |
| BrakeControlProcess | BrakeControlProcess | process | brakecontrolprocess | 1.0 | 已对应 |
| BrakeControlTask | BrakeControlTask | thread | brakecontroltask | 1.0 | 已对应 |
| BrakeCPU | BrakeCPU | processor | brakecpu | 1.0 | 已对应 |
| BrakeSystem | BrakeSystem | system | brakesystem | 1.0 | 已对应 |

组件结构校验包括组件对应校验、组件声明及引用校验，以及组件类别包含关系校验。本实施例中，所有组件均建立对应关系，无组件缺失和额外组件。组件声明及引用校验发现以下错误：

错误1（组件声明缺失）：候选模型中，BrakeControlProcess缺少implementation声明，仅有type声明。该process在BrakeSystem.impl中被引用为`proc : process BrakeControlProcess`，引用未指向实现声明，形成组件声明缺失错误信息。

错误2（子组件缺失）：共享组件基准中BrakeControlProcess包含子组件BrakeControlTask，但候选模型中BrakeControlProcess无implementation声明，其子组件BrakeControlTask未被实例化，进程内部缺少输入委派连接。形成子组件缺失信息。

**步骤S6：存在结构错误时进行修正，重新解析及组件结构复查。**

针对错误1和错误2，补充BrakeControlProcess的implementation声明，在其中实例化BrakeControlTask，并建立输入委派连接：

```
process implementation BrakeControlProcess.impl
  subcomponents
    ctrl_task : thread BrakeControlTask.impl;
  connections
    c_inner : port brake_in -> ctrl_task.task_in;
end BrakeControlProcess.impl;
```

将BrakeSystem.impl中proc的引用修改为指向实现声明：

```
proc   : process BrakeControlProcess.impl;
```

修正后的模型重新解析，形成更新的组件声明、实例引用、特征及连接关系。重新建立与共享组件基准之间的组件对应关系，对应关系不变。组件结构复查结果：错误1和错误2均已消除，剩余组件结构错误数量为0。

**步骤S7：根据剩余结构错误数量，选择性执行连接与属性校验。**

剩余组件结构错误数量为0，不大于预设校验阈值5，依据当前模型结构信息执行连接与属性校验。

连接端点存在性校验：连接c1的源端sensor.brake_out中，sensor实例对应的组件BrakeSensor的features中声明了brake_out端口，存在性校验通过。目标端proc.brake_in中，proc实例对应的组件BrakeControlProcess的features中声明了brake_in端口，存在性校验通过。内部连接c_inner的源端brake_in为BrakeControlProcess自身特征，目标端ctrl_task.task_in为子组件BrakeControlTask的特征，端点存在性校验通过。

连接方向校验：连接c1为port连接，源端brake_out为out方向，目标端brake_in为in方向，数据从输出端口流向输入端口，方向校验通过。内部连接c_inner为port连接，源端brake_in为BrakeControlProcess自身特征（in方向），目标端ctrl_task.task_in为子组件BrakeControlTask的特征（in方向），属于父组件自身特征与内部子组件特征的委派连接，按照父子委派连接规则，in方向的父组件端口委派至in方向的子组件端口，方向校验通过。

属性引用校验：属性Actual_Processor_Binding的应用目标为proc，在BrakeSystem.impl的subcomponents中存在proc实例声明，属性引用校验通过。属性值(reference (cpu))中，cpu为子组件实例名称，在subcomponents中存在声明，引用校验通过。属性值的外层列表定界符为括号对()，格式校验通过。

连接与属性校验未发现错误，第二校验结果为空。

**步骤S8：根据校验结果实施规则修正。**

第一校验结果和第二校验结果均未发现符合预设修正规则的模型内容，不实施规则修正。当前AADL模型即为步骤S6结构修正后的模型。

**步骤S9：输出当前AADL模型及校验信息。**

输出的AADL模型如下：

```
package BrakeSystem_Pkg
public

  -- 1. 传感器设备声明
  device BrakeSensor
  features
    brake_out : out data port;
  end BrakeSensor;

  -- 2. 线程声明与实现
  thread BrakeControlTask
  features
    task_in : in data port;
  end BrakeControlTask;

  thread implementation BrakeControlTask.impl
  end BrakeControlTask.impl;

  -- 3. 进程声明与实现
  process BrakeControlProcess
  features
    brake_in : in data port;
  end BrakeControlProcess;

  process implementation BrakeControlProcess.impl
  subcomponents
    ctrl_task : thread BrakeControlTask.impl;
  connections
    c_inner : port brake_in -> ctrl_task.task_in;
  end BrakeControlProcess.impl;

  -- 4. 处理器声明
  processor BrakeCPU
  end BrakeCPU;

  -- 5. 顶层系统声明与实现
  system BrakeSystem
  end BrakeSystem;

  system implementation BrakeSystem.impl
  subcomponents
    sensor : device BrakeSensor;
    proc   : process BrakeControlProcess.impl;
    cpu    : processor BrakeCPU;
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
| 组件结构校验 | 错误 | BrakeControlTask未被实例化，进程内部缺少输入委派连接 | 已修正 |
| 组件结构复查 | — | 修正后复查，剩余结构错误数量为0 | 通过 |
| 连接与属性校验 | — | 连接端点存在性、方向、属性引用及格式均通过 | 通过 |
| 规则修正 | — | 无符合修正规则的模型内容 | 无修正 |

本实施例中，共享组件基准在生成约束描述形成和候选模型组件校验两个过程中提供共同参照；结构修正改变候选模型后，以重新解析所得模型结构信息支撑后续校验；分层校验机制根据结构错误数量决定是否继续执行连接与属性校验，避免无意义的计算和混杂的错误信息。

实施例一


## 实施例二

如图4所示，本实施例结合实施例一的系统说明结构修正、复查及分层校验。所述校验阈值取5，以第一校验结果中的错误记录条数作为比较依据；该取值用于说明本实施例的处理过程。

候选AADL模型中已经声明制动控制线程BrakeControlTask的类型及其输入端口task_in，制动控制进程BrakeControlProcess内部的线程实例ctrl_task引用所述类型对应的实现BrakeControlTask.impl，但候选模型缺少BrakeControlProcess的implementation声明。其余组件声明、组件类别及包含关系满足本实施例的组件结构校验规则。

如图4所示，初次组件结构校验识别所述进程实例所引用实现的声明缺失。步骤S5依据该实例引用所指向的分类器补全对应的进程实现声明，所补全的实现与已有进程类型关联，已有类型中的输入端口描述予以保留。补全内容如下：

```
process implementation BrakeControlProcess.impl
  subcomponents
    ctrl_task : thread BrakeControlTask.impl;
  connections
    c_inner : port brake_in -> ctrl_task.task_in;
end BrakeControlProcess.impl;
```

完成补全后，重新解析当前AADL模型，获得已有进程类型、补全的进程实现及进程实例引用之间的关系，并重新建立候选模型组件与共享组件基准之间的对应关系。组件结构复查以该更新结果检查声明及引用，所述缺失实现声明引起的错误不再计入第一校验结果；在本实施例所述其余结构均满足校验规则的条件下，剩余组件结构错误数量为0。

如图4所示，由于所述数量0不大于校验阈值5，执行连接与属性校验。对于制动设备BrakeSensor与制动控制进程BrakeControlProcess之间的连接c1，两者均为顶层系统BrakeSystem.impl内部的子组件，按同级连接规则检查制动设备的输出端口brake_out和处理进程的输入端口brake_in；对于处理进程输入端口brake_in与内部运算线程输入端口task_in之间的连接c_inner，按父子输入委派关系检查，使父端点的输入方向与子端点的输入方向依据各自层级角色参与判断。

对于运算线程输出端口与处理进程输出端口之间的连接，按子端点至父端点的输出委派关系检查；对于访问连接，根据相应访问特征的提供与需要关系进行判断。端点存在性、特征类别和数据类型依据当前模型中对应的声明信息确定。

在另一初次组件结构校验未发现错误的候选模型中，顶层系统的组件实现设有处理器绑定属性关联，以已声明的处理进程实例proc作为应用目标，以已声明的处理器实例cpu作为引用值。该模型直接执行连接与属性校验，属性应用目标校验将所述处理进程实例名称proc与该组件实现中提取的子组件实例名称进行比较，能够找到对应声明。若一个属性关联另含未声明的应用目标proc2，则对该目标单独形成属性引用错误信息。

当该模型中所述处理器绑定属性关联的引用值仅具有自身的定界符而缺少外层列表定界符时，即属性值写为`reference cpu`而非`reference (cpu)`，连接与属性校验形成对应格式错误信息。步骤S7应用属性值格式修正规则，为该引用值补充外层列表定界符，修正为`reference (cpu)`，并输出当前AADL模型及该校验过程中形成的信息。该处理说明在未触发步骤S5的情况下，根据后续校验结果实施规则修正的过程。

对于另一候选模型，若实施结构修正后仍形成6条组件结构错误记录，并形成2条额外组件提示记录，则第一校验结果中用于比较的数量为6。由于6大于校验阈值5，如图4右侧分支所示，暂缓本次连接与属性校验，并记录暂缓状态；2条提示记录不参与所述数量的计算。对仍符合修正规则的模型内容执行步骤S7，并输出模型及当前校验过程形成的信息。

当初次组件结构校验即未发现错误时，不执行步骤S5的结构修正和复查，以初次解析所得模型结构信息及第一校验结果直接进行阈值判断。上述处理分别覆盖声明补全后的继续校验、剩余错误超过阈值的暂缓校验以及初次无错误的处理路径。

若候选模型包含跨行附件描述，则根据附件起止定界符识别其内部内容行，添加注释保护标记后再解析外围组件结构。例如，附件内部表示状态或行为结束的内容行因带有所述标记而被跳过，避免该内容行参与外围组件声明的结束位置识别；模型输出前移除标记，恢复原有附件内容及缩进。

实施例二


## 实施例三

本实施例在前述实施例的基础上，采用多智能体协同形成模型生成输入，并提供模型辅助修复方式。

需求分析智能体根据目标系统需求形成需求描述，架构设计智能体根据需求描述形成架构描述，模块设计智能体形成与所述架构描述相关的模块约束。对所述架构描述和模块约束实施步骤S3的解析，得到共享组件基准及生成约束描述，由模型生成智能体依据所述生成约束描述形成候选AADL模型。

以实施例一的车辆制动控制系统为例，需求分析智能体接收制动控制系统的需求文档，经过需求分析输出结构化需求数据，包括制动信号采集、制动控制处理、制动力分配运算和处理器部署四项需求。架构设计智能体根据所述需求描述，输出以树状层级结构表征的架构描述，其中BrakeSystem为根组件，其下包含BrakeSensor（device类别）、BrakeControlProcess（process类别）、BrakeCPU（processor类别），BrakeControlTask（thread类别）为BrakeControlProcess的子组件。模块设计智能体根据需求描述和架构描述，输出模块约束，表征各组件的端口要求和连接要求，包括BrakeSensor的输出端口brake_out、BrakeControlProcess的输入端口brake_in、BrakeControlTask的输入端口task_in，以及采集设备至处理进程的连接要求和处理进程至运算线程的输入委派要求。模块约束还包含属性要求：制动控制进程部署于BrakeCPU上。

对所述架构描述和模块约束实施步骤S3的解析，得到共享组件基准及生成约束描述。所述共享组件基准记录各组件的名称、类别、父组件、子组件及所属特征，其中端口特征归属于其所在组件。所述生成约束描述以文本清单形式表征待生成模型的组件组成及模块要求，与共享组件基准来源于同一解析过程。模型生成智能体依据所述生成约束描述，并结合通过检索增强生成获得的领域知识上下文，形成候选AADL模型。

所述候选AADL模型按照步骤S4至S8进行组件结构校验、结构修正及分层校验。模型生成所依据的组件信息与组件校验所依据的组件信息均来源于共享组件基准，所述基准在候选模型修正前后的组件对应过程中继续作为架构参照。

如图5所示，响应于模型修复指令，根据待修复AADL模型的校验信息检索相关修复案例。将模型内容、校验信息及检索得到的案例形成修复输入，由模型修复智能体生成修复后的AADL模型。

以实施例一中候选模型缺少BrakeControlProcess的implementation声明为例，步骤S4至S8的校验过程输出校验信息，其中包含组件声明缺失的错误记录。步骤S5补充了BrakeControlProcess.impl声明及内部委派连接c_inner后，模型通过校验并输出。当用户对输出模型进行修改后引入新的错误时，例如误删内部连接c_inner或将c_inner的目标端口写为ctrl_task.brake_in而非ctrl_task.task_in，由模型修复指令触发模型辅助修复。模型修复智能体接收待修复AADL模型及其校验信息，同时通过检索增强生成从错误记忆库中检索相似错误的历史修复案例。所述错误记忆库从历史修复任务中提取错误模式并生成错误指纹，包括错误类型和标准化错误消息。检索时根据当前校验信息中的错误类型和消息与历史错误指纹进行匹配，取相似度最高的前K条修复案例作为修复输入的上下文。

模型修复智能体将待修复模型、校验信息及检索得到的修复案例编入修复提示，由大语言模型生成修复后的AADL模型。以实施例一为例，修复后的模型恢复了内部连接c_inner，其目标端口修正为ctrl_task.task_in：

```
process implementation BrakeControlProcess.impl
  subcomponents
    ctrl_task : thread BrakeControlTask.impl;
  connections
    c_inner : port brake_in -> ctrl_task.task_in;
end BrakeControlProcess.impl;
```

对修复后的AADL模型进行模型内部一致性校验，确定其组件声明、引用及连接约束的符合情况，并输出模型及校验结果。所述模型内部一致性校验包括模型自身的组件声明、引用及连接约束校验，不涉及与架构描述之间的组件对应校验。再次实施模型辅助修复时，由相应的模型修复指令触发。对该模型实施与架构描述之间的组件对应校验时，以共享组件基准为参照执行前述组件结构校验及分层处理。

所述模型辅助修复过程中，修复前后代码的差异被提取并归档至错误记忆库。以实施例一为例，修复前的模型缺少BrakeControlProcess的implementation声明，修复后补充了该声明、内部子组件ctrl_task及委派连接c_inner。所述差异被提取为错误模式"missing_implementation_declaration"，生成错误指纹后归档。当后续模型出现相同错误模式时，错误记忆库中的历史修复案例被检索并作为修复输入的上下文，实现修复经验的跨模型复用。

上述方法可由本发明的基于共享组件基准的AADL模型智能生成与修复系统实现，其中，基准构建单元向模型生成单元提供生成约束描述，并向结构校验单元提供共享组件基准；结构修正单元向分层校验单元提供重新获得的模型结构信息及复查结果，结果生成单元根据相应校验结果形成输出。

所述系统的各单元可由电子设备中的处理器执行存储器所存储的计算机程序实现。所述计算机程序还可存储于计算机可读存储介质中，在被处理器执行时实现本发明的方法。

实施例三
