# AADL Single Agent

单个智能体从一份需求生成一个 AADL 工程，可包含多个 package、property set 和源文件。Outlines + llguidance 与产生式绑定的 Python 属性规则共同限制 token，OSATE 提供独立校验。规划、生成和修复沿用同一模型与会话。

## 安装与连接

客户端使用 Python 3.11–3.13 和 PowerShell 7，模型运行在服务器。`config.yaml` 分别配置模型服务 URL 与本地 OSATE CLI 启动器文件：

```yaml
base_url: http://10.96.234.51:8000/v1
osate_cli: .tools/osate-cli-0.3.0/bin/osate-cli.bat
```

程序读取 `/v1/capabilities` 自动发现模型、约束能力与规则版本。切换兼容服务只修改 URL；本地规则包必须与该服务的规则包一致，否则在生成前明确报错。

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e .
```

没有 OSATE CLI 时，先将 `config.yaml` 的 `osate_cli` 设置为上方的 `.tools/osate-cli-0.3.0/bin/osate-cli.bat`，再在项目目录手动执行以下命令。脚本从 [OSATE 官方 0.3.0 发布页](https://github.com/osate/aadl-tooling/releases/tag/osate-cli-v0.3.0) 安装 Windows x64 便携包，自带 Java 运行时，默认放在项目 `.tools/osate-cli-0.3.0/`：

```powershell
pwsh -File scripts/install_osate.ps1
.\.venv\Scripts\aadl-single-agent.exe doctor
```

脚本固定官方归档 SHA256，新下载通过发布元数据、归档摘要、组件及启动版本核验后才安装。已有完整安装离线验证并复用，不完整目录不覆盖；有 ZIP 缓存时同时验证摘要，无缓存时只报告组件与版本检查。失败的部分下载、解压目录及日志保留在 `.tools/` 下。可用 `-ToolsDirectory D:/tools` 指定其他安装根目录。

已有可用 CLI 时无需下载或运行安装脚本，将 `osate_cli` 改为其启动器文件，例如：

```yaml
base_url: http://10.96.234.51:8000/v1
osate_cli: D:/tools/osate-cli-0.3.0/bin/osate-cli.bat
```

`osate_cli` 指向文件而非安装目录；相对路径按 `config.yaml` 所在目录解析。当前仓库配置是本机复用同级 `aadl-research` 已有 CLI 的示例；迁移到其他电脑时需改为自己的已有 CLI，或上面的项目 `.tools/` 路径。所有 CLI 子命令读取相同配置，`--config` 可以放在子命令前后，例如 `aadl-single-agent --config local.yaml doctor` 或 `aadl-single-agent doctor --config local.yaml`。`check` 可离线执行，不连接模型服务，但仍需包含 `base_url` 和 CLI 路径的完整配置。路径无效或 CLI 启动失败会明确报错并给出手动安装命令，程序不会自动下载安装，也不会静默换用其他安装。每次 AADL 检查使用独立工作区并保留诊断与原始输出。

## 两条生成路径

`--constraints plain` 仅约束工程容器格式，不施加 AADL 语法或语义掩码；`--constraints joint` 同时约束容器、AADL 语法和属性语义，默认 joint。没有独立的 syntax 模式。

`--mode direct` 直接生成工程；`--mode planned` 先生成声明与接口计划，再生成工程，默认 direct。规划阶段只输出 Schema 约束的 JSON，进入生成阶段才启用工程容器指令。planned + plain 的 AADL 正文仍不施加语言掩码，计划只用于后续检查与反馈。

```powershell
.\.venv\Scripts\aadl-single-agent.exe generate --prompt 'Create a package Demo with system Root and implementation Root.impl.' --constraints joint --out .runs/demo
.\.venv\Scripts\aadl-single-agent.exe generate --requirements requirements.txt --mode planned --constraints plain --out .runs/planned
.\.venv\Scripts\aadl-single-agent.exe check .runs/demo/project
```

生成响应采用下面的容器。每个源文件正文行增加一个 `|` 前缀；客户端只去掉第一个 `|`，按接收顺序分配 `model_001.aadl`、`model_002.aadl` 等文件名。模型不指定磁盘路径，正文中的 `END_FILE` 等文字不会被误当成控制行。

```text
AADL-PROJECT 1
FILE
|package Demo
|public
|system Root
|end Root;
|end Demo;
END_FILE
END_PROJECT
```

容器严格使用 LF 换行；可重复 `FILE` 至 `END_FILE` 块。生成后分别执行本地规则和独立 OSATE 检查。即使规则拒绝或响应截断，已解析出的文件仍写入该次候选工程并交给 OSATE；不会补全缺失的控制行、分号或结束名。只有容器完整、生成正常结束、所有适用检查通过，才保存最终 `project/`。模型诊断反馈给同一会话；协议、服务、规则规格和 OSATE 基础设施故障单独记录。

默认共享输出 token 预算 8192，包含规划、工程容器和全部修复；规划最多 2048，最多三次整工程生成。生成请求使用剩余预算，并受服务公开的 `max_new_tokens` 上限约束，没有固定 4096 的正文上限。`--token-budget` 和 `--max-attempts` 可调整参数，尝试次数不能超过三次。输出预算相同不表示输入 token、总计算量或耗时相同。未知费用明确标注，不能解释为零成本。

工程响应因 `length` / `max_tokens` 截断，或联合解码遇到无合法 token 的死端时，仅在模型、tokenizer、规则版本、上下文摘要、对应请求的约束凭据及真实 token 用量核验通过后，才允许把部分输出和诊断反馈同一会话，重新生成整个工程。已消耗 token 照常计入预算。规划 JSON 截断、网络超时和协议核验失败直接终止；截断输出即使通过静态检查也不作为成功结果。原始响应原样保存，最终源文件与通过检查的候选文件字节一致。

## 显式依赖与实例检查

用可重复的 `--context DIR` 提供只读依赖。程序只递归读取这些目录内的 `.aadl` 文件，冻结文件名和文本快照，并将 `context_sha256` 绑定到每个请求和响应。它不会自动读取同目录参考答案或猜测依赖目录。依赖内容会进入提示词与本地/服务端规则环境；最终工程把快照保存在 `context/dir_001/` 等子目录，便于独立复查。

```powershell
.\.venv\Scripts\aadl-single-agent.exe generate --requirements requirements.txt --context dependencies --context shared-library --out .runs/with-context
.\.venv\Scripts\aadl-single-agent.exe check architecture --context dependencies
.\.venv\Scripts\aadl-single-agent.exe check architecture --instance-root 'App::Root.impl'
```

`check` 接受单个 AADL 文件或工程目录：文件只检查该文件及显式 context；目录递归包含所有 `.aadl`。所有源文件复制到一个隔离工程，OSATE 对整个工作区检查，不再只检查某个候选文件。因此嵌套目录中的缺分号错误也会使工程失败。

仅在传入 `--instance-root Pkg::Root.impl` 时实例化，不自动挑选根实现。检查器要求明确的 OSATE 成功输出、新产生的 `.aaxl2` 及匹配的实例对象，并继续读取工作区诊断；退出码为零本身不代表成功。随后调用规则包的 `validate_instance`，记录 `local_instance_rules`；缺少该能力会明确报 unsupported，不作为通过。OSATE 实例化成功不等于已覆盖 AS5506D §13 的全部实例一致性要求，结果仅证明本次实际执行的检查通过。

## 联合解码与规则扩展

每步先运行 Outlines 的 llguidance 工程容器掩码，再对候选按模型分数依次试探；joint 还对解出的 AADL 源码运行 llguidance CFG 与属性语义检查，选择最高分合法 token。容器掩码与 AADL 掩码分别计数，避免将 plain 的格式限制当作语言正确性约束。候选试探不提交状态，只有实际选中的 token 才同步推进；多语句 token 和 UTF-8 字节按同一桥接路径处理。joint 的 EOS 要求工程完整、每个文件语法完整且待兑现义务清空。默认单序列贪心，不支持 beam、随机采样或 KV 回滚；无合法候选明确报死端，不放弃约束。

未定义本地引用会累积类别和接口义务。例如 `child: system Worker.impl;` 允许 Worker 后定义，但其类型、实现和所有使用过的接口都必须兑现。多个引用合并约束，确定冲突立即拒绝。允许的前缀不等于保证存在合法补全，死端仍可能触发整段修复。

即使存在合法补全，模型也可能反复生成语法允许的空白或注释，直到达到 token 上限。此时记录为截断失败，按共享预算尝试修复；不会解除约束，也不会将不完整候选保存为成功模型。

为保留整工程修复的预算，三种服务请求（plain、joint、规划 JSON）统一在连续 32 个完全相同、非空且已换行结束的原始输出行后提前停止；空行重置计数，比较保留缩进、尾空格和 CR。该条件只结束当前生成，不改变候选 token 排名或语法合法性。响应记录 `termination.kind=repetition` 和实际用量，按截断处理；规划截断直接终止，工程截断在剩余预算内将本地及 OSATE 诊断反馈同一智能体。真实超时、解析资源故障仍属于基础设施错误，保留可取得的部分输出与用量，不自动重试。

默认规则包 `resources/aadl/` 的两个维护入口是：

- `aadl.lark`：定义 AADL CFG、词法边界和产生式；Lark 与 llguidance 使用同一份语法源。
- `semantics.py`：受信任的 Python 规则模块。`BINDINGS` 声明产生式的具名字段，`HANDLERS[(产生式, 属性名)]` 注册属性函数，`CONSTRAINTS` 注册必须求值的约束；`STRUCTURAL` 显式列出只承担结构的产生式。`evaluate` 组合项目环境与其余静态检查，`METADATA` 提供标准来源、规则范围、规划 Schema 和各阶段提示词。

每个产生式必须归入 `BINDINGS` 或 `STRUCTURAL`；`REQUIRED_ATTRIBUTES` 独立列出各语义产生式必须注册的属性，约束必须引用已注册的属性。漏绑、新增未知绑定或规则返回值不合法都会报告规则规格故障。文法中的 `-> alias` 可以让不同词法边界的产生式保留相同语义节点；源产生式和别名均须登记。使用 `--rules PATH` 选择独立规则包，所有命令均支持。runtime 4 不再读取旧版 `aadl.ag` 表达式文件；`semantics.py` 是安装到服务端的可信代码，不是沙箱，也不是模型可上传执行的内容。

例如，`BINDINGS['component_type']` 把声明名称与 `component_end` 绑定到同一声明；`BINDINGS['subcomponent']` 绑定子组件类别和分类器引用。`system Root end Other;` 会产生名称冲突；`child: system Worker.impl;` 的目标尚未出现时可以暂存引用义务，待后续文件定义 `Worker` 与 `Worker.impl` 后再核对类别及接口。仅仅存在同名声明不足以通过类型检查。

项目环境由已生成语法树、显式 context 和规则包声明的标准库组成。继承属性传递作用域等上下文，综合属性汇总声明事实，引用检查连接定义与使用位置。每次候选使用隔离的语法和属性快照，拒绝候选不修改已提交状态。结果区分 `Satisfied`、`Pending`、`Conflict`；确定冲突拒绝候选，前向引用等未知事实可以保留到项目闭合时检查。`Pending` 表示当前未知，不是已通过，也不保证此前缀存在合法补全。

属性函数通过只读 `ctx.get(node, attribute)` 与 `ctx.lookup(namespace, key)` 读取依赖。通用求值器记录这些依赖并为候选分叉缓存；新增无关符号不会使已有引用目标属性重算，目标定义变化或缺失目标后来出现则触发更新。声明字段、名称、类别、作用域、声明索引和分类器目标已经使用该接口，结束名与引用类别约束也由注册函数执行。其余复杂静态检查仍由同一规则文件内的 Python 检查程序执行，不宣称全部检查都已改为增量方程。属性依赖环明确报告规则规格错误；当前不支持通用循环属性的不动点求解，这与 AADL 继承环或包含环的模型错误分开处理。

规则故障和解析资源上限分别使用 `RuleDefinitionError`、`ParseResourceError` 报告为基础设施失败，不能伪装成模型语义错误或静默解除掩码。AADL 分类器继承和实现包含环由领域规则检查，不等同于属性求值器的循环依赖能力。

扩展步骤：

1. 在 `aadl.lark` 调整产生式与词法边界，并在 `semantics.py` 同步维护对应绑定、属性规则、诊断和规则范围；仅承担结构的产生式也必须显式声明。
2. 运行下面的离线回归，再对合法与非法工程分别执行 `check`，核对本地规则与 OSATE 的诊断。
3. 用下文的 Rules 命令同步到服务器；部署会核验内容摘要并重启自己的服务。
4. 运行单个需求生成，结果记录本次使用的规则摘要。

```powershell
.\.venv\Scripts\python.exe examples/conformance/check_rules.py
.\.venv\Scripts\python.exe examples/conformance/check_runtime.py
.\.venv\Scripts\aadl-single-agent.exe check examples/conformance/projects/forward
.\.venv\Scripts\aadl-single-agent.exe generate --requirements examples/conformance/forward_requirements.txt --mode direct --constraints joint
```

`forward_requirements.txt` 是自然语言需求；`projects/forward` 是独立的检查示例，包含两个包、合法后定义、system 子组件及端口连接。生成命令不会自动读取检查示例。`semantic_rules/` 保存原型、流、模式、数组等正反例。两个 `check_*.py` 是本地规则与运行时回归入口，不调用大模型，也不是批量生成脚本；其通过不能代替服务器验收。

服务与客户端使用同一份文件快照，并按两个文件的名称及原始字节计算 SHA256；换行或注释变化也会改变摘要。此外核对编译后的 `compiled_grammar_sha256` 与 `runtime_version`。同步时必须保留文件字节，不能仅凭相同 profile 名称认定规则一致。HTTP 接口只传规则摘要及只读依赖文本，不接收 Python 代码或可执行规则上传；改变运行时接口或语法树适配能力需要完整部署。

默认包以 SAE AS5506D（2022-04）核心语言为目标，并记录对照的 OSATE 源码 revision。规则已扩展到多包和 property set，以及导入、可见性、继承和多类架构元素；具体已实现检查和已知缺口以 `semantics.py` 的 `METADATA` 为准。语法接受某个结构不代表其全部静态语义均已实现；annex 内部语言、自然语言需求符合性及完整 AS5506D 符合性均不作保证。本地或跨文件分类器允许后定义，`system` 可以包含同类 `system`，但真实包含环不能因此被接受。

使用自定义规则包时，部署命令通过 `-RulesPath PATH` 选择该目录，客户端的 `doctor`、`generate` 和 `check` 同时传入 `--rules PATH`。服务切换规则会重启，应等待当前生成结束后执行。切回 `resources/aadl/` 默认规则时执行 `pwsh -File scripts/deploy_server.ps1 -Action Rules`，再运行不带 `--rules` 的 `doctor`；不能只切换客户端规则。

## 规则与标准、OSATE 的对应关系

下表按当前 `semantics.py` 的真实 `rule_id` 分组，列出已经核读的标准条款和 OSATE 对照入口。标准依据是本地提供的 SAE AS5506D（2022-04）；OSATE 链接固定到 `b8308b7`，不跟随 master。源码中的旧版标准页码不能直接当作 AS5506D 页码。这里的源码入口说明相关语义在 OSATE 中如何分布，并不表示逐行移植、结果完全等价，或覆盖该章节的全部规则；实际安装的 CLI 发行包另以版本与二进制摘要记录，不能仅凭此 revision 推定二者相同。

“前缀”阶段指 joint 候选试探，以及本地规则对当前语法快照的检查：只有相关字段已经确定时才拒绝冲突，尚可由后定义兑现的引用保持 Pending。“闭合”阶段要求完整工程不存在未兑现义务。OSATE 声明检查在生成后独立执行；实例阶段仅在显式指定 `--instance-root` 后执行。

| 当前规则组 | 标准依据与适用节点 | 已核实的 OSATE 文件、函数位置 | 执行阶段与现有回归例子 |
| --- | --- | --- | --- |
| `name.*`、`import.*`、`reference.*`、`path.*` | §4.2 包、命名与可见性；§4.3–4.4 分类器及其引用。适用于 package、类型、实现、导入、别名及成员路径。 | [Aadl2Validator.checkEndId，L1299](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L1299)；[PropertiesScopeProvider.scope_Classifier，L87](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2.properties/src/org/osate/xtext/aadl2/properties/scoping/PropertiesScopeProvider.java#L87)；[PropertiesLinkingService.findNamedElementInAadlPackage，L1169](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2.properties/src/org/osate/xtext/aadl2/properties/linking/PropertiesLinkingService.java#L1169)。分别涉及结束名、重命名作用域、导入包的 public 查找。 | 前缀确定冲突及闭合兑现。`check_rules.py` 的 `end Wrong`、缺少 Payload、跨文件后定义断言；`projects/forward/Main.aadl` 与 `Parts.aadl`。例如引用在前、定义在后可以通过，工程结束仍无定义则拒绝。 |
| `inheritance.*`、`containment.*`、`feature.owner_category` | §4.3–4.5、各组件类别章节；实现继承特别对应 §4.4 N6，类别对应 L3–L4。适用于类型/实现 extension、refinement、subcomponent。 | [Aadl2Validator.checkExtendCycles，L2881](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L2881)；[checkExtensionAndRealizationHierarchy，L3494](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L3494)；[checkForCyclicDeclarations，L1012](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L1012)。 | 前缀可确定的 refinement/类别冲突立即拒绝；未知祖先到闭合兑现。`check_rules.py` 的 `ancestry`、`forward_ancestry` 和 `refinement_prefix`：B extends A 时 B.i 可以继承 A.i；删去类型继承后必须拒绝。允许 system 包含 system 不代表允许包含环。 |
| `prototype.*` | §4.7 原型及绑定。适用于 prototype、prototype binding、实际分类器及接口方向。 | [Aadl2ScopeProvider.scope_PrototypeBinding_formal，L245](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/scoping/Aadl2ScopeProvider.java#L245)；[Aadl2Validator.checkComponentPrototypeBindingCategory，L3980](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L3980)；[checkFeaturePrototypeBindingDirection，L4044](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L4044)。 | 绑定字段确定后检查；前向分类器在闭合兑现。`semantic_rules/prototype_valid` 与 `prototype_invalid`：绑定已声明的 Item 与绑定不存在的形式原型形成正反例。 |
| `connection.*`、`feature_group.*`、`interface.*` | §8.2 feature group；§9.2、§9.4–9.5 端口、访问与 feature group 连接。适用于两端接口、父子边界方向、访问能力、分类器匹配。 | [Aadl2Validator.checkConnectionDirection，L5983](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L5983)；[checkPortConnectionClassifiers，L5582](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L5582)；[checkAccessConnectionProvidesRequires，L6635](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L6635)；[checkFeatureGroupConnectionClassifiers，L8616](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L8616)。 | 已知端点检查与未知接口义务合并；闭合必须兑现。`check_rules.py` 的 `access`、`group`、`inverse`：data 子组件到 requires data access 合法，改为 provides 则拒绝；互补接口连接合法，反转不兼容方向则拒绝。 |
| `parameter.*`、`calls.*` | §5.2 调用序列；§9.3 N2、L1–L3 参数端点、调用先后、方向及入边数量。适用于 subprogram call、parameter connection 和适用模式。 | [Aadl2ScopeProvider.scope_SubprogramCall_calledSubprogram，L171](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/scoping/Aadl2ScopeProvider.java#L171)；[Aadl2Validator.checkOneSequencePerMode，L788](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L788)；[checkParameterConnectionClassifiers，L6278](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L6278)。这些入口不表示已核实 OSATE 对本地每一条参数规则都会报错。 | 引用及端点信息确定后检查；模式交集等需等待相应声明完成。`parameter_calls.aadl` 与 `check_rules.py` 中反转调用顺序、错误端口方向、数据类型不匹配及重叠模式多入边变体。 |
| `property.*` | §11 属性类型、常量、值与关联；重复关联特别对应 §11.3 N15，绑定适用类别对应 §11.3 L10。适用于 property set、property association、内联属性和 contained applies-to。 | [Aadl2Validator.checkForDuplicatePropertyAssociations，L1058](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L1058)；[checkPropertyDefinition，L7105](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L7105)；[checkPropertyConstant，L7128](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L7128)；[PropertiesLinkingService.findNamedElementInPredeclaredPropertySets，L1200](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2.properties/src/org/osate/xtext/aadl2/properties/linking/PropertiesLinkingService.java#L1200)。 | 前缀值域/单位检查及完整关联检查；依赖未解时 Pending。`check_rules.py` 的 `inline` 正反例：不同端口各设置一次 Data_Size 合法，同一列表重复则拒绝；`semantic_rules/binding_valid`、`binding_invalid`。源码中重复属性检查会跳过部分 in-binding/数组情况，不能把该方法等同于本地规则的全部判定。 |
| `array.*` | §4.5 子组件数组及选择；相关属性 contained path 规则见 §11。适用于 array dimension、implementation choices、属性目标中的索引。 | [Aadl2Validator.checkSubcomponentImplementationReferenceList，L3800](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L3800)；[checkArraySizeIsAadlintegerNoUnits，L8102](https://github.com/osate/osate2/blob/b8308b7/core/org.osate/xtext/aadl2/validation/Aadl2Validator.java#L8102)。 | 可确定的维数、正整数和已知边界检查；开放维数不冒充已完成实例维度检查。`semantic_rules/array_valid`、`array_invalid`：大小为 2 的 c，属性目标 c[2].o 合法，c[3].o 越界；不能据此推断普通连接端点允许数组索引。 |
| `modes.*` | §12 模式、转换与 required-mode 映射；§4.4 L6–L7 约束类型与实现的模式声明。适用于 mode、transition、in modes、component mode binding。 | [Aadl2Validator.checkComponentImplementationModes，L3576](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L3576)；[Aadl2ScopeProvider.scope_ModeBinding_derivedMode，L655](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/scoping/Aadl2ScopeProvider.java#L655)。 | 字段及模式集合确定后检查；未知引用闭合兑现。`semantic_rules/modes_valid`、`modes_invalid` 检查父模式到子组件 required mode 的映射；`check_rules.py` 的 `type_modes`、`requires_modes` 检查实现不应新增的模式声明/子句。 |
| `flows.*` | §10.1 flow specification 方向；§10.2 flow implementation 连续性、连接顺序及模式覆盖。适用于 flow spec、flow implementation、end-to-end flow。 | [Aadl2Validator.checkFlowConnectionEnds，L1554](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L1554)；[checkFlowSegmentModes，L1970](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L1970)；[checkFlowFeatureDirection，L6928](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L6928)。 | 端点/段可解析后检查，跨引用义务到闭合兑现。`semantic_rules/flow_valid`、`flow_invalid`：`i -> c1 -> u.f -> c2 -> o` 与 c2 接到另一子组件输出的不连续变体。这里验证结构连续性，不证明运行时数据传播或时延满足需求。 |
| `instance.*` | §13.1 C1 的部分分类器/进程线程检查、C3 的局部端点完整性、C9 的 thread/processor/memory 存在要求。适用于 OSATE 实际导出的 SystemInstance XML。 | [InstantiateModel.createSystemInstance，L386](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.aadl2.instantiation/src/org/osate/aadl2/instantiation/InstantiateModel.java#L386)；[fillSystemInstance，L456](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.aadl2.instantiation/src/org/osate/aadl2/instantiation/InstantiateModel.java#L456)。这些是实例构建入口，不是本地 C9 检查的等价实现证明。 | 仅显式实例化成功后运行 `validate_instance`。空 system 实例不能满足本地 C9；目前 `examples/conformance/` 没有提交专用 `.aaxl2` 正反例，不能用声明示例或 `check_rules.py` 通过代替这层验收。 |

上表的示例路径均相对 `examples/conformance/`。`check_rules.py` 顶部的 `POSITIVE`/`NEGATIVE` 字典主要隔离语法识别，只有脚本内实际调用 `engine.validate` 的断言才同时测试本地语义；`semantic_rules/` 中的正反 AADL 需要分别通过 `check` 命令与 OSATE 对照，不能把目录存在当成每次自动执行的证据。

另外，`plan.*` 是用户选择 planned 路径后附加的计划兑现约束，不是 AADL 标准规则；属性处理器漏注册、依赖循环及试探隔离属于运行时机制，由 `check_runtime.py` 和 `check_rules.py` 的漏绑变体验证，也不应冒充 OSATE 语言规则。OSATE 的结束名检查使用 warning，而本地把不能匹配的结束名作为冲突；类似分歧必须分别保留两方诊断，不能强行改为相同结果。预声明属性集的原文来源、原始摘要及仅对内置源码进行的单位分隔正规化记录保存在 `BUILTIN_SOURCE_PROVENANCE`，不改写用户源码。

## 迁移的研究案例

`examples/research/requirements/` 保存从 `aadl-research/osate-examples/examples` 迁移的 146 份需求原文，`references/` 单独保存 75 份参考 AADL，均与源文件字节一致。需求包含中文、英文及改写版本；`manifest.json` 记录来源路径、SHA256、语言和基于路径/显式文件名别名推断的 57 个候选参考组。这些配对和分组尚不证明需求语义等价，也不应将多个版本直接视为独立任务。原有 AADL 版权与许可声明完整保留，未据此推定需求文本的许可。

先用单个需求测试当前规则，例如数据字典案例：

```powershell
.\.venv\Scripts\aadl-single-agent.exe generate --requirements examples/research/requirements/ACVIP/DataDictionary/data_re.txt --mode direct --constraints joint
```

`generate --requirements` 每次直接读取所选需求文本，参考 AADL 不会自动进入模型提示。只有用户显式通过 `--context` 指定的目录才作为依赖输入。`cases.jsonl` 保留为迁移时的需求文本快照，`manifest.json` 用于追溯来源；两者均不是当前生成命令的输入，修改需求文本不会自动更新它们。案例涉及的结构和语义须逐项对照规则范围，不能仅凭文件能解析就认定受支持。

## 单案例运行结果

CLI 提供 `doctor`、`generate` 和 `check` 三个命令。`generate` 可独立选择 direct/planned 和 plain/joint；每次命令只处理一份需求。未指定 `--out` 时，在 `.runs/` 下自动创建唯一目录；指定路径时要求该目录尚不存在，以保留已有结果。

以一次生成的输出目录为根，产物路径如下：

- `result.json`：需求、完整会话、模型及 tokenizer 身份、环境版本、规则/编译语法摘要、标准及 OSATE revision、context 快照、输入输出 token、阶段耗时、约束执行证据、候选检查及诊断。
- `attempt-N/raw-response.txt`：该次模型响应原文，包括容器或未完成的部分。
- `attempt-N/project/`：该次提取的 `model_001.aadl` 等候选及 `context/` 快照；目录内仅放 AADL 源文件。
- `attempt-N/osate/`：独立校验工作区、OSATE 产物；实例检查结果另在 `result.json` 的 `attempts[].local_instance_rules` 中记录。
- `call-N-partial.txt`：错误响应中已返回的部分文本，仅在响应包含部分输出时保存。
- `project/`：通过适用规则和 OSATE 检查的最终工程，只在成功时保存，包含生成文件与显式依赖快照。

联合解码证据保存在 `result.json` 的 `calls[].constraint_evidence` 中，区分 `transport_mask_steps`、`aadl_cfg_candidate_checks`、`semantic_evaluations` 和 `attribute_conflicts`，并保存实际计时与开关状态。plain 的 AADL 语法/语义开关必须为 false。每个候选的本地规则与 OSATE 结果分别保存在 `attempts[]` 中，可分析两者分歧；例如 OSATE 可能把错误结束名仅记为 warning，本地属性规则仍会拒绝。

## 部署和更新

在 PowerShell 7 执行；默认使用现有 SSH 别名 `qwen-server`。部署目录 `/home/wdd/aadl-single-agent`，模型复用 `/home/wdd/qwen3/models/Qwen3-14B`。独立环境复用已安装的模型依赖，服务固定 Outlines 1.2.6、llguidance 1.9.1、Transformers 4.51.3、PyTorch 2.6。

```powershell
pwsh -File scripts/deploy_server.ps1 -Action Deploy
pwsh -File scripts/deploy_server.ps1 -Action Rules
pwsh -File scripts/deploy_server.ps1 -Action Status
pwsh -File scripts/deploy_server.ps1 -Action Logs
```

`Start`、`Stop` 只操作 `aadl-single-agent.service`。部署保存独立版本目录和规则快照，端口被其他服务占用时停止部署，不清理未知进程。更新规则后会检查新服务返回的内容摘要。失败产物和日志保留供诊断。

首次迁移到工程及 Python 属性规则运行时 `runtime_version=4`，必须执行完整 `-Action Deploy`，仅替换规则文件不能升级服务代码。运行时版本一致后，若只修改 `aadl.lark` 和 `semantics.py`，可执行 `-Action Rules` 同步规则；客户端与服务器的文件摘要、编译语法摘要及运行时版本都必须一致。运行时代码再次变化时仍使用完整 Deploy。

2026-10-30 已完成 runtime 4 的真实 Qwen3-14B 在线验收。使用 `examples/conformance/forward_requirements.txt` 的同一份自然语言需求，`direct/plain`、`direct/joint`、`planned/plain`、`planned/joint` 四种条件均在首次工程生成后通过本地规则和 OSATE。输出包含两个包、跨文件前向引用、system 子组件及两条端口连接；direct 两组各消耗 155 个生成 token，planned 两组各消耗 390 个（含规划），均计入共享 8192 token 预算。结果保存在 `.runs/runtime4-imports-{direct,planned}-{plain,joint}/result.json`。

独立的错误结束名见证在真实推理中记录了 2 次选取 token 前的语义冲突拒绝，最终生成匹配的 `end S;`，并通过本地规则及 OSATE；请求、实际 token 序列和掩码证据保存在 `.runs/runtime4-imports-end-name-witness/result.json`。此次四组与见证的规则 SHA-256 均为 `c8e136e2e8f8a26ef72bb75d60dae9016edcc5139e0ed9c069c21e80102b988d`。离线规则回归通过 736 项断言，通用运行时回归也通过。此前失败候选及诊断仍保留；上述结果是单案例流程验收，不代表通用生成准确率或完整标准覆盖。

`status=passed` 表示本次适用规则及 OSATE 检查通过。默认记录 `validation_scope=syntax_and_static_semantics`；显式实例化时记录 `syntax_and_static_semantics_and_osate_instantiation_checks`。`requirements_verified` 始终为 false；这些结果不证明自然语言需求符合性，也不代表完整 AADL 或全部实例一致性规则已覆盖。
