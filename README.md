# AADL Single Agent

单个智能体从一份需求生成一个 AADL 源文件，其中只包含一个 package 或一个 property set；同一 package 内可以包含多个类型、实现及合法后定义。完整方法是语法语义联合解码加反馈修复：Outlines + llguidance 与产生式绑定的 Python 属性规则共同限制 token，OSATE 提供独立校验。生成和修复沿用同一模型。

当前本地版本 0.9.0 默认使用本地、确定性的检索增强，实验协议为 `two-group-repair` v3。检索内容仅作为提示证据，不定义工程中的符号、不代替真实依赖，也不改变语法语义判定。生成范围固定为单个 `FILE` 块和单个模型单元；显式提供的既有公共依赖仍可参与检查，但不会生成第二个源码文件。

## 安装与连接

客户端使用 Python 3.11–3.13 和 PowerShell 7，模型运行在服务器。`config.yaml` 分别配置模型服务 URL 与本地 OSATE CLI 启动器文件：

```yaml
base_url: http://10.96.234.51:8001/v1
osate_cli: .tools/osate-cli-0.3.0/bin/osate-cli.bat
```

程序读取 `/v1/capabilities` 自动发现模型、约束能力与规则版本，并要求 `generated_file_limit=1`。切换兼容服务只修改 URL；本地规则包必须与该服务的规则包一致，旧的多文件服务不能冒充当前能力，否则在生成前明确报错。

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
base_url: http://10.96.234.51:8001/v1
osate_cli: D:/tools/osate-cli-0.3.0/bin/osate-cli.bat
```

`osate_cli` 指向文件而非安装目录；相对路径按 `config.yaml` 所在目录解析。当前仓库配置是本机复用同级 `aadl-research` 已有 CLI 的示例；迁移到其他电脑时需改为自己的已有 CLI，或上面的项目 `.tools/` 路径。所有 CLI 子命令读取相同配置，`--config` 可以放在子命令前后，例如 `aadl-single-agent --config local.yaml doctor` 或 `aadl-single-agent doctor --config local.yaml`。`check` 可离线执行，不连接模型服务，但仍需包含 `base_url` 和 CLI 路径的完整配置。路径无效或 CLI 启动失败会明确报错并给出手动安装命令，程序不会自动下载安装，也不会静默换用其他安装。每次 AADL 检查使用独立工作区并保留诊断与原始输出。

## 完整方法与一次生成基线

产品 `generate` 只运行完整方法：联合约束生成、完整检查、预算内反馈修复。普通大模型基线只通过实验入口运行，生成一次后评估，错误原样保存，不反馈、不重试。两组都使用文件容器约束；只有完整方法施加 AADL 语法与静态语义掩码。

产品入口不再接受旧的 `--mode` / `--constraints` 选项。语义状态直接从生成中的 AADL 前缀建立，合法后定义通过 Pending 义务处理；接口传输和结果保存所用的 JSON 不属于声明规划。当前生成请求不接受声明计划；统计工具仍可读取显式导入的旧格式结果。

```powershell
.\.venv\Scripts\aadl-single-agent.exe generate --prompt 'Create a package Demo with system Root and implementation Root.impl.' --out .runs/demo
.\.venv\Scripts\aadl-single-agent.exe generate --requirements example/requirements/ACVIP/DataDictionary/data_re.txt --out .runs/project
.\.venv\Scripts\aadl-single-agent.exe check .runs/demo/project/model_001.aadl
```

生成响应采用下面的单文件容器。源文件正文行增加一个 `|` 前缀；客户端只去掉第一个 `|`，保存为 `model_001.aadl`。模型不指定磁盘路径，正文中的 `END_FILE` 等文字不会被误当成控制行。

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

容器严格使用 LF 换行，只允许一个 `FILE` 至 `END_FILE` 块；第二个 `FILE` 的第一个 `F` 即不可接受。生成后分别执行本地规则和独立 OSATE 检查。即使规则拒绝或响应截断，已解析出的候选源码仍交给 OSATE；不会补全缺失的控制行、分号或结束名。只有容器完整、生成正常结束、所有适用检查通过，才保存最终 `project/model_001.aadl` 及显式依赖快照。模型诊断反馈给同一会话；协议、服务、规则规格和 OSATE 基础设施故障单独记录。

完整方法默认共享输出 token 预算 8192，包含工程容器和全部修复，最多六次单文件完整候选生成，即一次初始生成加最多五次修复；普通基线固定一次。产品入口可用 `--max-attempts 1` 至 `6` 调低上限，当前实验协议固定使用 `6`。预算提前耗尽时停止，不保证一定生成六次。生成请求使用剩余预算，并受服务公开的 `max_new_tokens` 上限约束。输出预算相同不表示实际 token、调用次数、总计算量或耗时相同；首轮比较和预算内最终比较分别报告。未知费用保持未知，不能解释为零成本。

修复反馈策略 v2 的证据 JSON 最多为 16384 个 UTF-8 字节，其中当前候选源码视图最多 6144 字节、最近已评估候选历史最多 4096 字节和三次记录。诊断单项仍最多 512 个字符、1024 字节。摘要保留一个可定位的 error、相关源码、规则建议、真实停止信息及少量 Pending；省略与截断均记录。该上限包括证据中的源码视图和历史，不包括原始需求及固定修复提示；它约束输入提示大小，不改变 8192 输出 token 预算。完整 OSATE 诊断、Pending 和原始候选继续保存在对应结果中。

每轮修复保留最初请求中的完整需求和依赖。短候选在预算内提供全文；长候选通过当前文法的具名绑定选择错误结构、所属结构头部及共享绑定叶文本的关联片段。关联只是语法线索，不宣称已经完成名称解析；多解析分支、解析不可用、遗漏行和截断都有标记。精确重复行仅在提示视图中压缩，原始响应、源码、行号范围与 SHA256 保留，不补写结束符。源码视图只读取本轮候选，不读取参考答案或 RAG 文件。

反馈同时携带最近最多三次已评估候选的错误、代码变化、旧诊断复现、停止原因及完整校验结论；字节预算不足时明确标记省略。反馈优先定位 error，warning 不抢占首要位置；由同一模型重写完整文件，程序不代改源码。`candidate_changed` 记录候选字节是否改变，`focus_pattern_reobserved` 记录具体构造是否重现，`focus_diagnostic_reobserved` 记录同一源文件内原错误签名是否重现。缺少有效检查时保持未知；错误签名消失不表示修复成功，最终仍须通过全部适用检查。

`attempts[*].repair_feedback` 保存准备给下一次调用的完整证据投影；实际调用的 `calls[*].repair_feedback` 保存证据和消息哈希、字节数及来源候选编号。首调用该字段为空。反馈策略进入批次续跑指纹，修改策略必须开启新批次；历史结果不改写。`repair_feedback_seconds` 单独记录证据构造耗时，并已包含在端到端耗时中，不能再次相加。时间截止仍直接终止，不增加超时重试。

工程响应因 `length` / `max_tokens` 截断，或联合解码遇到无合法 token 的死端时，仅在模型、tokenizer、规则版本、上下文摘要、对应请求的约束凭据及真实 token 用量核验通过后，才允许把部分输出和诊断反馈同一会话，重新生成整个工程。已消耗 token 照常计入预算。网络超时和协议核验失败直接终止；截断输出即使通过静态检查也不作为成功结果。原始响应原样保存，最终源文件与通过检查的候选文件字节一致。

## 显式依赖与实例检查

用可重复的 `--context DIR` 提供只读依赖。程序只递归读取这些目录内的 `.aadl` 文件，冻结文件名和文本快照，并将 `context_sha256` 绑定到每个请求和响应。它不会自动读取同目录参考答案或猜测依赖目录。依赖内容会进入提示词与本地/服务端规则环境；最终工程把快照保存在 `context/dir_001/` 等子目录，便于独立复查。

## 本地检索与独立需求构造核对

`resources/retrieval/aadl.json` 的版本为 `aadl-prompt-knowledge-3-single-source`，包含 8 条知识及 7 个单文件最小例子，保存标准知识的转述、适用条件、来源版本和独立验证记录。例子通过本地规则和 OSATE 检查，不包含生成第二个文件的指导。通用检索器使用 BM25、知识库中冻结的中英术语映射及稳定排序，每次最多选择 3 个完整条目，总提示证据最多 6000 个 Unicode 字符；不会截断代码块来填满预算。没有相关条目时明确保存零命中。无需向量数据库、embedding 服务、查询改写模型或新增连接配置。

初次查询只来自原始需求；修复查询使用需求、首要错误诊断和少量 Pending 观察。每次替换检索证据，不累积上一轮片段。检索不新增模型调用，所有实际输入 token 已包含检索文本，不重复收费。知识库损坏或记录不一致属于基础设施错误，不能静默切换为未检索生成。

```powershell
# 产品入口仍只提供联合生成与修复，默认检索开启。
.\.venv\Scripts\python.exe -X utf8 -m aadl_single_agent generate --requirements example/requirements/ACVIP/DataDictionary/data_re.txt --out .runs/rag-example
# 开发对照：同一版本、同一输入、只改变检索开关，各自创建新批次。
.\.venv\Scripts\python.exe -X utf8 scripts/run_experiments.py run example/requirements/ACVIP/DataDictionary/data_re.txt --conditions joint-repair --retrieval off
.\.venv\Scripts\python.exe -X utf8 scripts/run_experiments.py run example/requirements/ACVIP/DataDictionary/data_re.txt --conditions joint-repair --retrieval on
# 两组正式比较：两组共用完全相同的初始检索证据。
.\.venv\Scripts\python.exe -X utf8 scripts/run_experiments.py run example/requirements/ACVIP/DataDictionary/data_re.txt --retrieval on
```

实验协议为 `two-group-repair` v3，保留 `plain-once` 与 `joint-repair` 两组、8192 共享输出 token，分别最多 1 次和 6 次调用。知识库哈希、检索策略、开关、单文件范围及尝试上限进入续跑指纹；初始证据在登记批次时冻结，并与两组的实际首调用逐一核对。`result.json` 的 `provenance.retrieval`、`calls[*].retrieval` 保存命中条目、完整注入文本、分数、来源和哈希；CSV/TXT 增加检索开关、耗时和证据摘要。旧 v1/v2 和六组批次继续离线汇总，原先三次上限的记录保持原含义，不以 v3 执行或合并解释。

以下命令用现有短需求创建两组新实验；示例原文标为来源未核实，不能据此声称独立原始需求评测：

```powershell
.\.venv\Scripts\python.exe -B -X utf8 scripts/run_experiments.py run example/requirements/ACVIP/DataDictionary/data_re.txt --retrieval on
```

检索知识绝不通过 `--context` 加载。例如示例中的 `B.impl` 不能兑现当前工程对 `B.impl` 的缺失定义。知识库不读取 `example/reference`、`Generate` 或历史成功/错误记忆；修改知识后应重新独立验证、冻结并创建新批次，不能将当前测试答案或同源改写纳入检索库。

可选 `review` 命令评估预先明确编写的可见 AST 构造断言。清单绑定需求文件原始字节的 SHA256；未提供清单时不自动推断自然语言需求。核对独立于生成、修复与 OSATE，可报告“静态通过、构造核对未通过”。它不证明行为、继承后接口、单位等价或全部需求满足。未知产生式及不可能的字段路径明确报错，不能以零匹配冒充成功。

```powershell
.\.venv\Scripts\python.exe -X utf8 -m aadl_single_agent review path/to/model_001.aadl --requirements example/requirements/ACVIP/DataDictionary/data_re.txt --spec checks.json --out .runs/construction-check.json
# 批量核对时，checks目录按相对需求路径保存同名.json，所有清单先校验并冻结。
.\.venv\Scripts\python.exe -X utf8 scripts/run_experiments.py run example/requirements/subset --review-root checks --retrieval on
```

清单形状如下。字段路径沿直系产生式逐级定位，`within` 限定所属声明；`count` 表示准确数量。`case_sensitive: false` 适用于 AADL 名称，字符串字面值默认保持大小写及内部空格差异。批次结束后逐候选保存独立 `requirement-review.json`，CSV/TXT 分别记录首候选与静态接受候选的 `met/not_met/not_evaluable` 状态；检查清单不会进入模型提示。

```json
{
  "schema_version": 1,
  "requirements_sha256": "填写需求文件原始字节的SHA256",
  "assertions": [
    {"id": "one_source_file", "kind": "file_count", "count": 1},
    {"id": "typed_event_input", "count": 1, "selector": {
      "production": "feature",
      "fields": {
        "declaration_name": {"text": "command", "case_sensitive": false},
        "feature_spec/port_spec/direction": {"text": "in", "case_sensitive": false},
        "feature_spec/port_spec/typed_port_kind": {"text": "event data port", "case_sensitive": false},
        "feature_spec/port_spec/classifier_reference": {"text": "Payload", "case_sensitive": false}
      },
      "within": {"production": "component_type", "fields": {
        "declaration_name": {"text": "Unit", "case_sensitive": false}
      }}
    }}
  ]
}
```

```powershell
.\.venv\Scripts\aadl-single-agent.exe generate --requirements example/requirements/ACVIP/DataDictionary/data_re.txt --context dependencies --context shared-library --out .runs/with-context
.\.venv\Scripts\aadl-single-agent.exe check architecture/model_001.aadl --context dependencies
.\.venv\Scripts\aadl-single-agent.exe check architecture/model_001.aadl --instance-root 'App::Root.impl'
```

`check` 接受单个 AADL 文件或仅含一个目标文件的目录，并用 `--context` 提供既有依赖。多目标输入返回 `unsupported_input_scope`，不记为规则执行故障；不要将同时包含生成文件和 `context/` 的结果目录直接当成生成源码输入。生成文件与显式依赖共同复制到隔离的 OSATE 工程检查，依赖文件的错误也会使校验失败。底层参考工程校验仍支持多个文件。

仅在传入 `--instance-root Pkg::Root.impl` 时实例化，不自动挑选根实现。检查器要求明确的 OSATE 成功输出、新产生的 `.aaxl2` 及匹配的实例对象，并继续读取工作区诊断；退出码为零本身不代表成功。随后调用规则包的 `validate_instance`，记录 `local_instance_rules`；缺少该能力会明确报 unsupported，不作为通过。OSATE 实例化成功不等于已覆盖 AS5506D §13 的全部实例一致性要求，结果仅证明本次实际执行的检查通过。

实例产物可以直接以 `SystemInstance` 为根，也可以是包含一个直接子级 `SystemInstance` 的 `xmi:XMI`。两种形态均核验实例名、分类器引用、源码位置及产物身份；XMI 中的其他顶层数据对象不算系统内部组件。

## 联合解码与规则扩展

每步先运行 Outlines 的 llguidance 工程容器掩码，再对候选按模型分数依次试探；joint 还对解出的 AADL 源码运行 llguidance CFG 与属性语义检查，选择最高分合法 token。容器掩码与 AADL 掩码分别计数，避免将 plain 的格式限制当作语言正确性约束。候选试探不提交状态，只有实际选中的 token 才同步推进；多语句 token 和 UTF-8 字节按同一桥接路径处理。joint 的 EOS 要求工程完整、每个文件语法完整且待兑现义务清空。默认单序列贪心，不支持 beam、随机采样或 KV 回滚；无合法候选明确报死端，不放弃约束。

未定义本地引用会累积类别和接口义务。例如 `child: system Worker.impl;` 允许 Worker 后定义，但其类型、实现和所有使用过的接口都必须兑现。多个引用合并约束，确定冲突立即拒绝。允许的前缀不等于保证存在合法补全，死端仍可能触发整段修复。

唯一 `END_FILE` 的首个 `E` 出现后，已无法继续追加定义，也不允许再生成第二个文件，因此该边界同时要求源码语法完整、当前规则范围内的义务闭合。同文件内先引用、后定义仍可保留 Pending；显式依赖在生成前即已冻结，不等待以后补文件。完整容器仍须收到 `END_FILE`、`END_PROJECT` 的全部标记及换行。未完整 UTF-8 字节先经过当前容器和源码文法的只读字节探测，字符完整后再执行属性检查。例如标识符位置不能借未完整 Unicode 字节暂时绕过 ASCII 词法约束，字符串和注释中的合法 Unicode 则继续允许。候选探测始终不提交状态。

即使存在合法补全，模型也可能反复生成语法允许的空白或注释，直到达到 token 上限。此时记录为截断失败，按共享预算尝试修复；不会解除约束，也不会将不完整候选保存为成功模型。

生成协议 2（`generation_protocol_version=2`）保留连续 32 个相同非空完整行的停止条件，并增加精确周期后缀检测：最后 2048 个 Unicode 字符内，周期最长 256 字符、至少重复 8 次且总跨度至少 256 字符。两种条件均只在实际选中 token 后观察，统一用于正文及服务的通用 JSON Schema 请求；保留原始空格和 CR，不修改候选排名或语法合法性。`termination.kind=repetition` 记录单位、阶段和实际用量，按截断处理；仅启用修复的组可在剩余预算内修复；一次生成基线直接结束。历史协议 1 只有完整行检测。

协议 2 同时明确 600 秒单次生成合作式截止，在候选试探前后检查；单次试探可能略过截止点，因此它不是硬实时保证，也不是整个 HTTP 请求或 OSATE 检查的时限。服务端返回已停止 worker 的 HTTP 200 `length` 结果，客户端核验协议、策略、模型与规则身份、用量、选中 token 数、阶段及停止证据后，记录不可重试的 `generation_timeout`；它计入效果失败，当前条件结束，批次继续后续条件。网络失联、未核验的 HTTP 错误和解析资源故障仍属于基础设施问题。旧 HTTP 504 记录保持原分类，不能事后改写成新协议的已核验截止。

默认规则包 `resources/aadl/` 的两个维护入口是：

- `aadl.lark`：定义 AADL CFG、词法边界和产生式；Lark 与 llguidance 使用同一份语法源。
- `semantics.py`：受信任的 Python 规则模块。`BINDINGS` 声明产生式的具名字段，`HANDLERS[(产生式, 属性名)]` 注册属性函数，`CONSTRAINTS` 注册必须求值的约束；`STRUCTURAL` 显式列出只承担结构的产生式。`evaluate` 组合项目环境与其余静态检查，`METADATA` 提供标准来源、规则范围和生成、修复提示词。

生成与修复使用不同的任务说明：`generation_prompt` 说明如何把需求转换为单文件源码，`repair_prompt` 聚焦修改已被拒绝的候选。领域指导覆盖三种端口形式、类型与实现、同文件后定义及连接；检索仅补充相关单文件例子。plain/joint 使用相同的初始提示与检索入口。修复保留原始需求和只读依赖，附最新定位诊断、受限候选源码视图、最近三次评估摘要及当前检索证据；不会无限累积历史全文。

规则包可提供 `repair_advice(sources, diagnostics, declarations)`，返回带 `id/source/line/observed/explanation/suggestion` 的建议，行号从 1 开始。通用流程复制输入、验证建议结构，并把建议记录在失败候选的 `repair_advice` 字段，再与原始诊断共同发送给同一模型。建议不自动改写文件、不改变语法或语义判定，也不加入 token 掩码。新增领域修复建议只需修改此规则函数；未提供该可选函数的独立规则包仍可使用原始诊断。异常或非法返回值属于规则规格故障，不冒充模型错误。

例如错误源码 `import Vendor::Library;` 会得到文件、行号及 `with Vendor::Library;` 建议，避免把解析器随后报告的缺少 `end` 误解为需要更改包名；被导入库必须是显式提供的真实依赖，不能把标准文档标题当作库名。截断的 `importLibrary` 只有在源码提供目标命名空间时才得到条件性建议；字符串、注释、Annex 内容、合法别名及真正的同名标识符不因此改名。

每个产生式必须归入 `BINDINGS` 或 `STRUCTURAL`；`REQUIRED_ATTRIBUTES` 独立列出各语义产生式必须注册的属性，约束必须引用已注册的属性。漏绑、新增未知绑定或规则返回值不合法都会报告规则规格故障。文法中的 `-> alias` 可以让不同词法边界的产生式保留相同语义节点；源产生式和别名均须登记。使用 `--rules PATH` 选择独立规则包，所有命令均支持。runtime 4 不再读取旧版 `aadl.ag` 表达式文件；`semantics.py` 是安装到服务端的可信代码，不是沙箱，也不是模型可上传执行的内容。

例如，`BINDINGS['component_type']` 把声明名称与 `component_end` 绑定到同一声明；`BINDINGS['subcomponent']` 绑定子组件类别和分类器引用。`system Root end Other;` 会产生名称冲突；`child: system Worker.impl;` 的目标尚未出现时可以暂存引用义务，待同一文件内后续定义 `Worker` 与 `Worker.impl` 后再核对类别及接口。仅仅存在同名声明不足以通过类型检查。

项目环境由已生成语法树、显式 context 和规则包声明的标准库组成。继承属性传递作用域等上下文，综合属性汇总声明事实，引用检查连接定义与使用位置。每次候选使用隔离的语法和属性快照，拒绝候选不修改已提交状态。结果区分 `Satisfied`、`Pending`、`Conflict`；确定冲突拒绝候选，前向引用等未知事实可以保留到项目闭合时检查。`Pending` 表示当前未知，不是已通过，也不保证此前缀存在合法补全。

属性函数通过只读 `ctx.get(node, attribute)` 与 `ctx.lookup(namespace, key)` 读取依赖。通用求值器记录这些依赖并为候选分叉缓存；新增无关符号不会使已有引用目标属性重算，目标定义变化或缺失目标后来出现则触发更新。声明字段、名称、类别、作用域、声明索引和分类器目标已经使用该接口，结束名与引用类别约束也由注册函数执行。其余复杂静态检查仍由同一规则文件内的 Python 检查程序执行，不宣称全部检查都已改为增量方程。属性依赖环明确报告规则规格错误；当前不支持通用循环属性的不动点求解，这与 AADL 继承环或包含环的模型错误分开处理。

规则故障和解析资源上限分别使用 `RuleDefinitionError`、`ParseResourceError` 报告为基础设施失败，不能伪装成模型语义错误或静默解除掩码。AADL 分类器继承和实现包含环由领域规则检查，不等同于属性求值器的循环依赖能力。

扩展步骤：

1. 在 `aadl.lark` 调整产生式与词法边界，并在 `semantics.py` 同步维护对应绑定、属性规则、诊断和规则范围；仅承担结构的产生式也必须显式声明。
2. 对具有明确预期的合法与非法工程分别执行 `check`，核对本地规则与 OSATE 的诊断；模型源码示例和历史断言计数不能代替本次检查记录。
3. 用下文的 Rules 命令同步到服务器；部署会核验内容摘要并重启自己的服务。
4. 运行单个需求生成，结果记录本次使用的规则摘要。

```powershell
.\.venv\Scripts\aadl-single-agent.exe check path/to/model_001.aadl
.\.venv\Scripts\aadl-single-agent.exe generate --requirements example/requirements/ACVIP/DataDictionary/data_re.txt
```

`path/to/model_001.aadl` 应替换为待检查的单个生成文件；既有依赖用 `--context DIR` 另行指定。当前实验不生成多个包或多个属性集文件，单文件入口限制属于已选工具兼容范围，不宣称标准语言本身只允许单文件工程。`example/reference/` 只保存来源及参考材料，生成命令不会自动读取其中的模型。

服务与客户端使用同一份文件快照，并按两个文件的名称及原始字节计算 SHA256；换行或注释变化也会改变摘要。此外核对编译后的 `compiled_grammar_sha256` 与 `runtime_version`。同步时必须保留文件字节，不能仅凭相同 profile 名称认定规则一致。HTTP 接口只传规则摘要及只读依赖文本，不接收 Python 代码或可执行规则上传；改变运行时接口或语法树适配能力需要完整部署。

默认包以 SAE AS5506D（2022-04）核心语言为目标，并记录对照的 OSATE 源码 revision。当前源码入口接受一个 package 或 property set；规则继续处理导入、可见性、继承和多类架构元素，跨包名称解析仅使用已提供的真实依赖。具体已实现检查和已知缺口以 `semantics.py` 的 `METADATA` 为准。语法接受某个结构不代表其全部静态语义均已实现；annex 内部语言、自然语言需求符合性及完整 AS5506D 符合性均不作保证。同文件分类器允许后定义，`system` 可以包含同类 `system`，但真实包含环不能因此被接受。

原型名称解析还要考虑继承范围。例如同包中的 `Derived extends Base` 使用继承原型 `T`，而 `Base` 尚未定义时，保留继承范围义务，不虚构同名包级分类器；在同一包后续定义的 `Base` 可以提供 `T: data`。缺少原型、类别错误或关闭文件时仍无定义都会失败。同名包级分类器不能抢先覆盖继承原型。未绑定的泛型库声明本身合法，不要求凭空生成一个同名实际分类器。

原型约束和实际参数分别使用自己的声明范围：`Types::G` 中 `T: data D` 的约束指向 `Types::D`，在 `P::H extends Types::G(T => data E)` 中实际参数指向 `P::E`；再检查 E 是否满足 D 的继承要求。实际参数的基类尚未定义时继续 Pending，不能把尚无继承证据当成已确定不兼容。

原型匹配遵循可确定的 `Prototype_Substitution_Rule`：`Classifier_Match` 比较组件类型，允许同类型的不同实现；`Type_Extension` 比较组件类型的相同或扩展关系。默认值读取规则包绑定的 OSATE `Modeling_Properties` 定义，当前为 `Type_Extension`，不是程序写死的值；AS5506D 附录 A.7 的该属性没有显式默认，正文和相关替换属性的默认措辞也存在差异，因此这里采用的默认是已记录来源的工具配置。属性范围尚未闭合时保留 Pending。`Signature_Match`、需要完整上下文求值的策略暂未实现，明确记录未兑现检查并阻止最终成功，不用继承关系代替签名匹配。

属性名字遵循 §11.1 N4：自定义类型、常量和属性即使在自身 property set 中引用也使用限定名。例如数值边界应写 `PS::Low .. PS::High`，裸 `Low .. High` 不能借助本地同名常量通过；预声明名字仍可裸用。`applies to (P::Base)` 使用 property set 自己的导入范围，必须声明 `with P;`，且目标应为 public 分类器。

使用自定义规则包时，部署命令通过 `-RulesPath PATH` 选择该目录，客户端的 `doctor`、`generate` 和 `check` 同时传入 `--rules PATH`。服务切换规则会重启，应等待当前生成结束后执行。切回 `resources/aadl/` 默认规则时执行 `pwsh -File scripts/deploy_server.ps1 -Action Rules -Port 8001`，再运行不带 `--rules` 的 `doctor`；不能只切换客户端规则。

## 规则与标准、OSATE 的对应关系

下表按当前 `semantics.py` 的真实 `rule_id` 分组，列出已经核读的标准条款和 OSATE 对照入口。标准依据是本地提供的 SAE AS5506D（2022-04）；OSATE 链接固定到 `b8308b7`，不跟随 master。源码中的旧版标准页码不能直接当作 AS5506D 页码。这里的源码入口说明相关语义在 OSATE 中如何分布，并不表示逐行移植、结果完全等价，或覆盖该章节的全部规则；实际安装的 CLI 发行包另以版本与二进制摘要记录，不能仅凭此 revision 推定二者相同。

“前缀”阶段指 joint 候选试探，以及本地规则对当前语法快照的检查：只有相关字段已经确定时才拒绝冲突，尚可由后定义兑现的引用保持 Pending。“闭合”阶段要求完整工程不存在未兑现义务。OSATE 声明检查在生成后独立执行；实例阶段仅在显式指定 `--instance-root` 后执行。

| 当前规则组 | 标准依据与适用节点 | 已核实的 OSATE 文件、函数位置 | 执行阶段与现有回归例子 |
| --- | --- | --- | --- |
| `name.*`、`import.*`、`reference.*`、`path.*` | §4.2 包、命名与可见性；§4.3–4.4 分类器及其引用。适用于 package、类型、实现、导入、别名及成员路径。 | [Aadl2Validator.checkEndId，L1299](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L1299)；[PropertiesScopeProvider.scope_Classifier，L87](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2.properties/src/org/osate/xtext/aadl2/properties/scoping/PropertiesScopeProvider.java#L87)；[PropertiesLinkingService.findNamedElementInAadlPackage，L1169](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2.properties/src/org/osate/xtext/aadl2/properties/linking/PropertiesLinkingService.java#L1169)。分别涉及结束名、重命名作用域、导入包的 public 查找。 | 前缀确定冲突及闭合兑现。结束名 `end Wrong`、缺少 Payload、跨文件后定义构成检查例子。例如引用在前、定义在后可以通过，工程结束仍无定义则拒绝。 |
| `inheritance.*`、`containment.*`、`feature.owner_category` | §4.3–4.5、各组件类别章节；实现继承特别对应 §4.4 N6，类别对应 L3–L4。适用于类型/实现 extension、refinement、subcomponent。 | [Aadl2Validator.checkExtendCycles，L2881](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L2881)；[checkExtensionAndRealizationHierarchy，L3494](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L3494)；[checkForCyclicDeclarations，L1012](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L1012)。 | 前缀可确定的 refinement/类别冲突立即拒绝；未知祖先到闭合兑现。例如，B extends A 时 B.i 可以继承 A.i；删去类型继承后必须拒绝。允许 system 包含 system 不代表允许包含环。 |
| `prototype.*` | §4.7 原型及绑定。适用于 prototype、prototype binding、实际分类器及接口方向。 | [Aadl2ScopeProvider.scope_PrototypeBinding_formal，L245](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/scoping/Aadl2ScopeProvider.java#L245)；[Aadl2Validator.checkComponentPrototypeBindingCategory，L3980](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L3980)；[checkFeaturePrototypeBindingDirection，L4044](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L4044)。 | 绑定字段确定后检查；前向分类器在闭合兑现。例如，绑定已声明的 Item 与绑定不存在的形式原型形成正反例。 |
| `connection.*`、`feature_group.*`、`interface.*` | §8.2 feature group；§9.2、§9.4–9.5 端口、访问与 feature group 连接。适用于两端接口、父子边界方向、访问能力、分类器匹配。 | [Aadl2Validator.checkConnectionDirection，L5983](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L5983)；[checkPortConnectionClassifiers，L5582](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L5582)；[checkAccessConnectionProvidesRequires，L6635](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L6635)；[checkFeatureGroupConnectionClassifiers，L8616](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L8616)。 | 已知端点检查与未知接口义务合并；闭合必须兑现。例如，data 子组件到 requires data access 合法，改为 provides 则拒绝；互补接口连接合法，反转不兼容方向则拒绝。 |
| `parameter.*`、`calls.*` | §5.2 调用序列；§9.3 N2、L1–L3 参数端点、调用先后、方向及入边数量。适用于 subprogram call、parameter connection 和适用模式。 | [Aadl2ScopeProvider.scope_SubprogramCall_calledSubprogram，L171](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/scoping/Aadl2ScopeProvider.java#L171)；[Aadl2Validator.checkOneSequencePerMode，L788](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L788)；[checkParameterConnectionClassifiers，L6278](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L6278)。这些入口不表示已核实 OSATE 对本地每一条参数规则都会报错。 | 引用及端点信息确定后检查；模式交集等需等待相应声明完成。反转调用顺序、错误端口方向、数据类型不匹配及重叠模式多入边分别构成反例。 |
| `property.*` | §11 属性类型、常量、值与关联；重复关联特别对应 §11.3 N15，绑定适用类别对应 §11.3 L10。适用于 property set、property association、内联属性和 contained applies-to。 | [Aadl2Validator.checkForDuplicatePropertyAssociations，L1058](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L1058)；[checkPropertyDefinition，L7105](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L7105)；[checkPropertyConstant，L7128](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L7128)；[PropertiesLinkingService.findNamedElementInPredeclaredPropertySets，L1200](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2.properties/src/org/osate/xtext/aadl2/properties/linking/PropertiesLinkingService.java#L1200)。 | 前缀值域/单位检查及完整关联检查；依赖未解时 Pending。例如，不同端口各设置一次 Data_Size 合法，同一列表重复则拒绝。源码中重复属性检查会跳过部分 in-binding/数组情况，不能把该方法等同于本地规则的全部判定。 |
| `array.*` | §4.5 子组件数组及选择；相关属性 contained path 规则见 §11。适用于 array dimension、implementation choices、属性目标中的索引。 | [Aadl2Validator.checkSubcomponentImplementationReferenceList，L3800](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L3800)；[checkArraySizeIsAadlintegerNoUnits，L8102](https://github.com/osate/osate2/blob/b8308b7/core/org.osate/xtext/aadl2/validation/Aadl2Validator.java#L8102)。 | 可确定的维数、正整数和已知边界检查；开放维数不冒充已完成实例维度检查。例如，大小为 2 的 c，属性目标 c[2].o 合法，c[3].o 越界；不能据此推断普通连接端点允许数组索引。 |
| `modes.*` | §12 模式、转换与 required-mode 映射；§4.4 L6–L7 约束类型与实现的模式声明。适用于 mode、transition、in modes、component mode binding。 | [Aadl2Validator.checkComponentImplementationModes，L3576](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L3576)；[Aadl2ScopeProvider.scope_ModeBinding_derivedMode，L655](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/scoping/Aadl2ScopeProvider.java#L655)。 | 字段及模式集合确定后检查；未知引用闭合兑现。检查父模式到子组件 required mode 的映射，以及实现不应新增的模式声明/子句。 |
| `flows.*` | §10.1 flow specification 方向；§10.2 flow implementation 连续性、连接顺序及模式覆盖。适用于 flow spec、flow implementation、end-to-end flow。 | [Aadl2Validator.checkFlowConnectionEnds，L1554](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L1554)；[checkFlowSegmentModes，L1970](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L1970)；[checkFlowFeatureDirection，L6928](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.xtext.aadl2/src/org/osate/xtext/aadl2/validation/Aadl2Validator.java#L6928)。 | 端点/段可解析后检查，跨引用义务到闭合兑现。例如，`i -> c1 -> u.f -> c2 -> o` 与 c2 接到另一子组件输出的不连续变体。这里验证结构连续性，不证明运行时数据传播或时延满足需求。 |
| `instance.*` | §13.1 C1 的部分分类器/进程线程检查、C3 的局部端点完整性、C9 的 thread/processor/memory 存在要求。适用于 OSATE 实际导出的 SystemInstance XML。 | [InstantiateModel.createSystemInstance，L386](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.aadl2.instantiation/src/org/osate/aadl2/instantiation/InstantiateModel.java#L386)；[fillSystemInstance，L456](https://github.com/osate/osate2/blob/b8308b7/core/org.osate.aadl2.instantiation/src/org/osate/aadl2/instantiation/InstantiateModel.java#L456)。这些是实例构建入口，不是本地 C9 检查的等价实现证明。 | 仅显式实例化成功后运行 `validate_instance`。空 system 实例不能满足本地 C9；声明示例或历史规则断言通过不能代替实际实例检查记录。 |

上表中的例子说明规则适用边界；它们不是当前实验命令会自动执行的测试集。语法解析、属性语义检查与独立 OSATE 工程检查分别记录，不能把某一层通过解释为其他层也已通过。

另外，`plan.*` 是历史 planned 路径附加的计划兑现约束（当前入口不再调用），不是 AADL 标准规则；属性处理器漏注册、依赖循环及试探隔离属于运行时机制，不应冒充 OSATE 语言规则；历史回归记录只对应其注明的版本和日期。OSATE 的结束名检查使用 warning，而本地把不能匹配的结束名作为冲突；类似分歧必须分别保留两方诊断，不能强行改为相同结果。预声明属性集的原文来源、原始摘要及仅对内置源码进行的单位分隔正规化记录保存在 `BUILTIN_SOURCE_PROVENANCE`，不改写用户源码。

2026-10-31 对当前安装的 OSATE CLI 0.3.0 做独立工程对照后，以下差异按标准条款保留，并不视为“全部检查通过”：

| 例子 | 本地判定及依据 | 当前 OSATE 实测 |
|---|---|---|
| `list of reference => ()` 显式默认值 | 拒绝，§11.1.2 L2；省略默认值时列表隐式为空不等于允许显式默认值。 | 接受。 |
| `Items +=> (1) in modes(active)` | 拒绝，§11.3 L6。 | 接受。 |
| 普通 flow specification 的 `in modes(change)` 指向转换 | 拒绝，§10.1 和 §12；flow refinement 的规则另行处理。 | 接受。 |
| 继承转换与新增转换对同一状态、同一输入指定不同目的状态 | 拒绝，由 §4.3(17) 的继承规则和 §12 L3 的确定性要求共同推出。 | 接受。 |
| 别名 `S renames system Q::S` 或 `renames Q::all` 与本包 S 重名 | 拒绝，§4.2 N14–N16。 | 接受。 |
| 同包 public/private 分文件，或同一实现的合法 public/private 双重声明 | 接受，§4.2 N2、L2–L3、(9)–(10)；公开实现不能含内部结构，extends/原型绑定写在公开声明。 | 报 duplicate，部分例子同时报链接错误。 |
| actual 不满足显式或内置默认的 `Type_Extension` 原型分类器约束 | 拒绝，§4.7 L4/L7/L8。 | 本轮反例未报出该冲突。 |

这些差异只描述本次安装版本与具体输入。最终工程仍要求本地检查和 OSATE 都通过，因此标准允许但当前 OSATE 拒绝的形式也不会保存为成功结果；失败原文和两方诊断继续保留。

## 需求与参考资料

`example/requirements/` 保存直接交给生成器的需求文本；`example/reference/` 保存参考模型、原始需求材料和来源记录。`example/reference/manifest.json` 记录来源类别、family、固定上游版本、文件摘要和关联范围，文件路径相对 `example/` 根目录解析。参考模型不自动进入提示词；仅用户显式传入的 `--context DIR` 会成为模型可见的依赖。

旧资料完整保留 146 份需求文本、75 份参考 AADL 和 57 个候选参考组。146 份含中文、英文和改写版本，需求来源标为 `unverified_legacy`；文件来自公开模型目录不能证明它们是原作者需求，路径配对也不证明语义等价。`stepper-motor_output` 中的 33 份文本描述 WBS，归入 `arp4761_wbs`，不计作步进电机项目。真正的 stepper-motor 参考模型另行保留。评估按 family 保持同源版本在同一分组，不能把 146 份文本当成 146 个独立原始任务。

新增原始需求来源分成两个 family，需求入口放在 `example/requirements/public/`，原始资料和参考模型按相同来源目录放在 `example/reference/public/`：

| family | 原始来源 | 纳入范围与限制 |
|---|---|---|
| `verse_opensut`（OpenSUT） | [GaloisInc/VERSE-OpenSUT，固定提交 2b441e6](https://github.com/GaloisInc/VERSE-OpenSUT/tree/2b441e6e6ff00b176a46d4d2577d19aca9f2f4e4) 的 `REQUIREMENTS.sdoc` / `REQUIREMENTS.rst` | 输入为 [core_architecture.md](example/requirements/public/verse_opensut/core_architecture.md)，包含原始 `TA2-REQ-66` 至 `TA2-REQ-84` 的 19 项需求及 README 原段。保留原始需求、README、LICENSE 和核心 AADL；GUMBO、Resolute 等材料作为辅助参考。行为、时序或实现层要求不能仅凭 AADL 静态检查认定满足。 |
| `alisa_situational_awareness`（ASSA） | [osate/alisa-examples，固定提交 2eb11ca](https://github.com/osate/alisa-examples/tree/2eb11ca21d188fa278421442ee65b2389403e4e5) 的 ASSA Common / System 需求与目标 | 输入 [system.md](example/requirements/public/alisa_situational_awareness/system.md) 对应完整原始 `MIS-SSS.reqdoc` 的 `MIS_SSS` 文档，保留 284 项 `SYS_*` 要求的原文、ID、行号和 `for` 绑定；其余 9 份原文档留在参考目录。保留 reqdoc、goaldoc、reqspec、goals 和 Notice，不纳入 RefArch。保留来源中的教学错误，不能默认参考模型有效；输入不从模型反向编写。 |

版权、许可和公开发布声明随各自来源保存，不从参考模型的许可推断旧需求文本许可。每个输入仍需核对当前规则覆盖范围、依赖和验收义务；公开原始需求存在不等于项目全部行为可以由当前生成器实现或验证。

2026-11-02 对新增公开参考模型执行了独立 OSATE 工程检查：OpenSUT 核心两文件在当前环境报告 16 个 error，涉及 `AbstractFeatureClassifier` 引用；ASSA Common + System 报告 11 个 error、27 个 warning，涉及 bus 分类器、接口和 EMV2。原件保持不变，诊断摘要保存在对应 `example/reference/public/` 案例的 `validation.json`，来源清单记录对应验证状态；旧临时工作区已清理。因此两组参考均不是已经验证的标准答案；可以使用原始需求评价新生成工程的独立 OSATE 有效性，不计算参考匹配分，也不把来源项目的行为要求标记为已满足。

另纳入 [A3D 架构参考工程](example/reference/public/a3d/) 的 7 个案例：225 份工程 AADL、共享库 97 份 AADL，以及 63 对原始 A3D JSON/XMI 实例。工程源码按上游仓库保留目录，共享库只保存一份；元数据、标注、schema、原始说明与许可放在 `dataset/`，其内部沿用上游的 `dataset/models/` 等路径。它们不新增独立评测需求任务，`requirement_ids=[]`、`is_gold_reference=false`，也不自动进入生成提示或 RAG。后续补齐的派生需求通过单独的 `derived_requirement_ids` 关联，见下方说明。

| 案例与源码 | 工程 AADL / 关联 A3D 实例 | 当前环境的核心检查与实例化 |
|---|---:|---|
| [APS 客船通信](example/reference/public/a3d/sources/aps/) | 13 / 43 | 821 error、21 warning；包括单引号字符串和包结束名称问题，主入口实例化未执行。固定提交实际为 13 个 AADL，已纠正筛选时的 14 个计数。 |
| [QFCS_V2 冗余飞控](example/reference/public/a3d/sources/smaccm/models/QFCS_V2/) | 38 / 1 | 0 error、7 warning；`FCS::FCS.impl` 和 A3D 对应的双通道输入子系统实例化通过。AGREE 未验证。 |
| [SMACCM Phase2 无人机](example/reference/public/a3d/sources/smaccm/models/SmaccmPhase2/) | 142 / 2 | 83 error、52 warning；例如 `IVORY.aadl` 重复赋值 `Period`，实例化未执行。保留 `todo.txt` 及外部实现代码引用。 |
| [Paparazzi 无人机](example/reference/public/a3d/sources/aadlib/examples/paparazzi/) | 10 / 7 | 4 error、6 warning；例如 bus access 使用的 SPI 分类器类别不匹配，4 个调度入口实例化未执行。 |
| [ADIRU 惯性参考系统](example/reference/public/a3d/sources/aadlib/examples/adiru/) | 9 / 4 | 2 error、1 warning；当前 ARINC653 库缺少 `Memory_Type` / `Code_Memory`，4 个部署与故障变体实例化未执行。 |
| [AOCS 卫星姿轨控](example/reference/public/a3d/sources/aadlib/examples/aocs/) | 4 / 1 | 9 error、0 warning；共享处理器库所需的 Ocarina `Deployment` 属性集不可解析，实例化未执行。 |
| [FMS 飞行管理](example/reference/public/a3d/sources/aadlib/examples/fms/) | 9 / 5 | 18 error、9 warning；包括 bus 分类器和 `Deployment` 依赖问题，实例化未执行。 |

2026-11-03 使用本地 `osate-cli 0.3.0` 按案例隔离执行解析、链接与指定根实例化。工程检查失败时，根实例化明确记为 `not_run`。当前分发有 EMV2、Behavior Annex 解析器，没有 AGREE、REAL 等专用解析器；未知附录被默认解析器接收不代表其语义检查或证明通过。本轮没有执行附录证明、代码生成或完整软件构建。完整诊断、原路径映射、依赖选择、工具环境和各验证范围见 [validation.json](example/reference/public/a3d/validation/validation.json)；原始文件在验证前后逐字节一致，错误仍可用于架构阅读，但不能作为已验证答案。

[来源清单](example/reference/manifest.json) 保存固定提交、Git blob / SHA256 摘要、入口、许可和 A3D ID 对应关系。63 个实例均用根 `componentImplementation` / `classifier` 与固定源码的实际声明交叉核对；例如 ADIRU CSV 指向 `adiru_memory.aadl`，根声明实际在 `model.aadl`，两种记录同时保留。当前固定上游快照不等于论文采集时版本，后者提交未知；按子模型扩展取得完整工程也不表示已有实例与当前源码结构完全相同。A3D 集合许可不替代各工程许可，Paparazzi 的 GNU-GPL 来源注释与 AADLib 根许可同时保留。

6UCubeSat、Obeo CruiseControl、SecuritySystem 和 PolyORB 仅保留候选索引与暂缓原因。源文件精确重复、保守文本候选和忽略 href 的实例候选已记入清单；语义近重复尚未审定，7 个工程及其变体不能据此视为独立评测样本。

从项目根目录可复现导入、隔离验证和清单核验（首次导入需要访问固定 GitHub 提交；验证器默认读取 `config.yaml`，可用 `--config` 选择配置或用 `--osate-cli` 显式覆盖）：

```powershell
.\.venv\Scripts\python.exe scripts/import_a3d_references.py
.\.venv\Scripts\python.exe scripts/validate_a3d_references.py
.\.venv\Scripts\python.exe scripts/index_a3d_references.py
.\.venv\Scripts\python.exe scripts/index_a3d_references.py --check
```

导入器拒绝覆盖字节不同的原件；适配或修复应另存。清单追加会改变实验使用的整体 SHA256，所以已在原目录保留 [导入前清单](example/reference/manifest.pre-a3d-0f38481bd0c3.json)，保证其相对路径仍可解析。旧冻结选集如需旧清单应显式指定该快照；已有批次的续跑身份规则不变，不修改旧 hash 来绕过检查。

按后续明确授权，7 个工程已配套英文的 **派生示例需求，共 206 条**，放在 `example/derived_requirements/a3d/`。它们依据固定源码、内嵌契约和作者设计说明归纳，不冒充独立的原始需求；可用于架构重建示例、工具演示和人工学习，不能用于独立的需求到模型评测。原 `example/requirements/` 的 148 份文件及生成 API、RAG 配置保持不变，默认递归该目录不会选入这些派生文本。

| 派生需求入口 | 条数 | 覆盖范围 |
|---|---:|---|
| [APS](example/derived_requirements/a3d/a3d_aps/system.md) | 35 | 客船、远控、云、5G、应急与岸端系统的层次、通信接口和安全属性意图。 |
| [QFCS_V2](example/derived_requirements/a3d/a3d_qfcs_v2/system.md) | 27 | 四通道飞控、输入选择、控制律接口、输出组织及选定契约。 |
| [SMACCM Phase2](example/derived_requirements/a3d/a3d_smaccm_phase2/system.md) | 34 | 无人机、地面站、遥控器、PX4/ODROID、软硬件部署与选定行为约束。 |
| [Paparazzi](example/derived_requirements/a3d/a3d_paparazzi/system.md) | 30 | 自动驾驶与线控划分、SPI、任务集合、模式和四种调度配置。 |
| [ADIRU](example/derived_requirements/a3d/a3d_adiru/system.md) | 28 | 四分区组织、部署资源、健康监测和 DeOS/VxWorks653/EMV2 变体边界。 |
| [AOCS](example/derived_requirements/a3d/a3d_aocs/system.md) | 24 | 传感器、执行器、看门狗、中央计算机、控制进程与通信绑定。 |
| [FMS](example/derived_requirements/a3d/a3d_fms/system.md) | 28 | 四处理模块、CAN/AFDX、交换机、虚拟链路及网络映射。 |

[逐条追溯记录](example/reference/public/a3d/requirements/) 是需求正文的可编辑来源：每条保存 ID、派生类别、源码行范围、文件与行片段 SHA256；同时保存原始需求文献的查找结果、版本对应限制和已知缺口。源内作者写的需求注释标为 `author_requirement`，形式契约标为 `embedded_contract`；它们仍可能受模型设计影响，整个输入始终标为 `derived_example`。清单用独立的 `derived_requirements` 集合登记，`independent_evaluation_eligible=false`，不会加入现有 `requirements` 或其评测分组。

例如 Paparazzi 的论文任务频率与当前模型不同，文本只采用固定模型的时序；ADIRU 的分区 ID 和部分预算数值存在疑点，未转成有效部署要求。保留这些限制使示例可追溯，也意味着需求只覆盖已明确的架构意图，不声称完整功能规格或参考模型已经满足需求。原始来源文件和已有 OSATE 结果均不因需求整理而改变。

修改追溯 JSON 后可重建并核验正文；只改生成的 Markdown 会触发未登记改动检查。显式运行生成器时可以使用这些示例，但不要将其混入独立评测选集：

```powershell
.\.venv\Scripts\python.exe scripts/curate_a3d_requirements.py
.\.venv\Scripts\python.exe scripts/curate_a3d_requirements.py --check
.\.venv\Scripts\python.exe scripts/index_a3d_references.py --check
.\.venv\Scripts\aadl-single-agent.exe generate --requirements example/derived_requirements/a3d/a3d_aocs/system.md
```

补齐需求前的清单另保存在 `a3d.requirement_examples.previous_manifest` 所指的同目录快照中，以保留之前实验的原始清单哈希；新正文的文件数和条目数另行统计，不改变原需求计数。

单份需求可直接生成，例如：

```powershell
.\.venv\Scripts\aadl-single-agent.exe generate --requirements example/requirements/ACVIP/DataDictionary/data_re.txt
```

## 两组生成实验

实验入口 `scripts/run_experiments.py` 支持一个 UTF-8 TXT/Markdown 文件或递归目录。当前实验协议是 `two-group-repair` version 3，与生成协议 2 分开；默认只运行以下两组并共用初始检索证据，不运行 JSON 预声明或六组消融。

| 条件 | AADL 解码约束 | 正文调用上限 | 失败后处理 |
|---|---|---:|---|
| `plain-once` | 无；仍使用统一文件容器 | 1 | 保存原始错误，只做独立评估 |
| `joint-repair` | 语法和静态语义联合约束 | 6 | 在剩余预算内反馈并重新生成整个工程 |

两组共用 Qwen3-14B、初始提示、规则、依赖及验收标准，均使用共享 8192 输出 token；完整方法的修复消耗不能另开预算。首轮比较观察联合生成的初步效果，预算内最终比较观察联合解码加修复的整体效果；两组不能单独分离语义约束与修复的因果贡献。

初始请求由两条消息组成：system 放置共用领域指南和一次完整容器规范，最后的 user 保留实际需求及显式依赖。修复仍使用同一初始基底并追加最近的定位反馈，不再重复发送整份领域指南。调用记录保存每条消息原文，实验审计逐字比较同一输入两组的首轮请求；提示变化必须开启新批次。

```powershell
.\.venv\Scripts\python.exe scripts/run_experiments.py run example/requirements/ACVIP/DataDictionary/data_re.txt --output-root Generate
.\.venv\Scripts\python.exe scripts/run_experiments.py run example/requirements/ACVIP/DataDictionary/data_re.txt --conditions plain-once --output-root Generate
.\.venv\Scripts\python.exe scripts/run_experiments.py resume --batch <新协议批次ID> --output-root Generate
.\.venv\Scripts\python.exe scripts/run_experiments.py summarize --output-root Generate
```

可指定 `--config`、`--rules`、只读 `--context` 和 `--instance-root`。两组的尝试上限由条件固定，不能把 `plain-once` 改为自动修复。条件执行顺序随案例轮换；输入、规则、代码、配置、协议和依赖哈希进入续跑身份。

批量输入使用 `example/requirements` 中明确选定的目录。正式选集应先冻结需求字节、来源、家族、适用范围和评测资格；派生需求及范围外任务分别统计，不进入独立核心任务指标。所有旧生成与调试结果已清理，下一次运行创建新批次；如需分析外部保留的旧结果，离线汇总仍按其原协议解释。

每个新批次默认先运行一次通用合成需求 warmup：`Produce one minimal complete project using the loaded language rules.`，不读取开发或留出需求；只尝试一次，默认最多 512 输出 token，可用 `--warmup-token-budget` 调整或显式 `--no-warmup` 禁用。其状态和原始结果保存在 `_warmup/<batch>/warmup.json` 与 `run/result.json`，不进入正式 jobs、CSV、成功率或 token 成本分母。warmup 中断不重放；基础设施故障停止批次，其余失败保留记录后继续正式任务。

统一入口还提供离线诊断，不连接模型或 OSATE。规则回归直接读取 `semantics.py` 的 `METHOD_REGRESSIONS`，覆盖前缀、候选判定、重复试探隔离和完整合法见证，不依赖已删除的 conformance 目录：

```powershell
.\.venv\Scripts\python.exe scripts/run_experiments.py diagnose regress
.\.venv\Scripts\python.exe scripts/run_experiments.py diagnose freeze <已有result.json> --output .runs/diagnostic-snapshot
.\.venv\Scripts\python.exe scripts/run_experiments.py diagnose replay .runs/diagnostic-snapshot --prefixes 3 --max-characters 4096 --output .runs/diagnostic-replay.json
.\.venv\Scripts\python.exe -B scripts/run_experiments.py diagnose feedback-regress
.\.venv\Scripts\python.exe -B scripts/run_experiments.py diagnose feedback <已有result.json>
```

`freeze` 保留结果原始字节、返回正文/可读取的部分输出及其哈希，机制证据单独存入 `mechanism.json`。`replay` 用当前策略在最多 512 个文本边界观察周期，并对每次正文调用的少量前缀试探候选文本；规划 JSON 只回放周期检测。它不是 tokenizer 或概率分布的重现，不宣称精确的原始首次停止 token；试探计量与原始机制证据分开，不并入实验效果统计。诊断输出要求新路径，不覆盖已有结果。

`feedback` 从结果目录内的 `attempt-N/raw-response.txt` 重新解析候选并核对原始哈希，以当前反馈策略离线重建消息。它区分历史实际发送的反馈与因超时或次数上限而没有发送的反馈；不调用模型、OSATE 或检索，也不将重建视为修复成功。`feedback-regress` 检查源码关联、短历史、字节上限、未知证据和只读行为。默认直接在控制台输出；仅显式提供 `--output` 时写入一个新 JSON，拒绝覆盖旧文件。

默认结果根 `Generate/` 相对命令的工作目录解析，以下命令均从项目根目录执行。输入路径映射保留需求目录层次：`example/requirements/` 内的单文件或子目录均相对此需求根映射；外部单文件相对其父目录，外部目录相对其自身，也可用 `--input-root` 显式指定。以数据字典为例，case 目录是 `Generate/ACVIP/DataDictionary/data_re/`；每次 `run` 创建新的 batch，条件目录为 `<case>/<batch>/<condition>/`。结果目录应放在所选需求目录之外，避免结果文本混入需求枚举。

- `Generate/manifest.json`：批次、输入和配置身份、条件调度状态及输出位置。
- `Generate/experiments.csv`：每个 case / batch / condition 的结果和成本；未执行、失败、中断及未知用量保留明确状态。
- `Generate/summary.csv` / `summary.json`：按实验协议、batch、family、来源类别和 condition 汇总；不同批次不合并成新的独立需求。
- `<case>/result.txt`：该 case 各批次的可读汇总（注明实验协议）。
- `<case>/<batch>/requirements.<原扩展>`：本批使用的需求快照。
- `<case>/<batch>/<condition>/result.json`：完整会话、候选诊断、独立 OSATE 证据、版本身份和用量。

`resume` 使用原批次冻结的输入及配置，只继续 `not_started` 条件；可加 `--batch ID` 选择批次。已经完成或失败的条件不覆盖、不重新调用模型；遗留 `running` 标为 `interrupted` 并保留现场，也不自动重跑。需要重试失败条件时显式执行新的 `run`，形成新批次，避免把未知的历史消耗归零后继续。输入、规则、依赖或运行环境身份变化会停止该批，不能静默混入同一比较。`summarize` 根据已有记录重建汇总，不连接模型服务。

## 指标与解释范围

指标设计借鉴 [Type-Constrained Code Generation §5](https://arxiv.org/pdf/2504.09246v2#page=16) 对编译错误、功能正确性与运行成本的分开评估，以及 [ChopChop §6](https://arxiv.org/html/2509.00360v2#S6) 对无约束、语法约束、语义约束的比较和每生成 token 的判定开销统计。[Projectional Decoding §4](https://arxiv.org/html/2605.30054v1#S4) 也明确区分语法有效、语义有效和执行答案正确。这些是指标设计依据，不表示本项目已复现它们的算法或可直接比较跨任务分数。

主指标以完整工程为单位，并与本地规则接收状态分开：

| 指标 | 本项目口径 |
|---|---|
| 首轮工程 OSATE 有效率 `first_osate_rate` | 可评估条件中，第一次正文尝试的工程容器完整、生成正常结束且独立 OSATE 通过的数量 / `n_evaluable`。 |
| 预算内工程 OSATE 有效率 `budget_osate_rate` | 可评估条件中，在共享预算内至少有一次完整、正常结束且独立 OSATE 通过的数量 / `n_evaluable`。 |
| 本地最终接收状态 | 保留 `status=passed` 等原始状态；最终保存还要求适用的本地规则和实例检查通过，不能与 OSATE 单独有效率混写。 |
| 本地与 OSATE 分歧 | 仅比较正常生成、容器有效且完整的候选，分别计 `local_pass_osate_fail` 与 `local_fail_osate_pass`；分歧本身不自动证明语义误拒或哪一方正确。 |
| 生成与修复成本 | 输入/输出 token、正文尝试次数、规划/生成/修复及验证阶段耗时、总耗时；未知用量保持未知，另保留已知下界。 |
| 解码开销 | 分列容器掩码、AADL CFG、属性语义和候选选择的实际计时；结合对应正文生成 token 计算每 token 开销。 |

`n_evaluable` 是本组已经终结且没有基础设施故障的条件数；规划失败、截断、解码死端和模型无效输出均保留在此分母中。基础设施失败、中断和未启动另列，不伪装成模型效果；`first_osate_rate_all_started` / `budget_osate_rate_all_started` 另外以所有已启动条件为分母，只让有实际通过证据的记录贡献分子。汇总同时报告已排定、已启动、已终结、未启动、中断、基础设施失败及可评估数量。比较不同条件时先检查完成的任务集合与排除原因，不能把未完成批次的百分比直接作为公平对比。

上述有效率是实际运行记录的描述统计，并不表示输入都是独立原始需求。汇总同时按来源、`generation_scope_status`（范围内、范围外、待核实）及评测资格分组。`independent_core_evaluable` 仅接收明确具有独立评测资格且在核心单文件范围内的记录；相应独立核心有效率使用这一更严格分母。派生需求不进入该分母，来源或范围未知也不自动视为合格。范围判断绑定需求 SHA256，并保存原文行号、摘录及判定依据；文件变化后不能沿用旧判断。

当前 148 份原有及公开需求的范围核对为 14 份范围内、106 份范围外、28 份待核实；另有 7 份 A3D 派生需求单独保留。146 份旧需求的原始来源资格仍未核实，两个公开原始需求的完整任务范围仍待核实，因此不能将这些文件数量用作已经确认的独立核心任务数量。

最多六次正文尝试是在同一会话内使用诊断反馈的顺序修复，不是六个独立采样，因此预算内有效率不称 `pass@6`。OSATE 静态检查通过不证明自然语言需求满足、功能正确或安全性质成立；需求覆盖/满足默认记为未评估，不能用参考模型相似度、Pending 数量、冲突次数或属性计算次数替代。只有另有独立、可执行的需求验收依据时才能增加相应正确性结论。

当前错误分类包括容器、已明确识别的语法错误、静态语义、实例、未完成、死端与基础设施等原因；规划 Schema 和计划兑现仅用于显式导入旧结果时的读取兼容。开放前缀的 Pending 不当作已经确定的静态错误；工程闭合后仍未兑现的义务才记为闭合失败。各分类可能重叠，不应相加冒充失败任务总数。

时间字段中，`aadl_syntax_seconds` 只计实际 CFG 工作，`semantic_seconds` 计候选 AST 前缀/属性语义工作，`transport_mask_seconds` 单独计容器掩码；`candidate_selection_seconds` 包含排序、字节桥接及内部候选检查，不能与其嵌套项直接相加。这些解码计时不含服务端规则和依赖初始化，总成本另看客户端调用及端到端耗时。每 token 开销以 `generation_completion_tokens` 为分母，包含工程容器和实际选中的 EOS，以及修复正文，排除 `planning_completion_tokens`；用量未知或分母为零时不填零开销。gcd 的解码语义计数和时间应为零，但完整候选生成后的语义检查仍会发生，必须按阶段区分。执行计数说明约束实际运行，不能直接解释为质量提升。

HTTP 错误响应可能仍附带服务端计量。表中的 `error_response_type` 保留如 `generation_timeout` 的子类；`server_reported_error_*` 单独导出这些计数和耗时，并标为 `unverified_error_response`，不混入正常核验的 `decoder_*` 或开销均值。汇总同时给出正常计量和错误响应计量的覆盖率；空值表示缺测，不能解释为零耗时。历史协议 1 的 HTTP 504 截止归为基础设施失败，旧记录保持此口径；解释这批结果时仍需报告该子类、`all_started` 通过率和可评估覆盖率。

协议 2 新增 `generation_protocol_version`、`termination_events`（含 call/phase）、`generation_timeout_calls` / `generation_timeout_phases` 和 `repetition_stop_calls`。经客户端核验的 `generation_timeout` 纳入 `n_evaluable`，不进行当前条件的后续修复调用。汇总按 batch 和 selection role 隔离，不能将旧协议结果与新停止策略混为同一试验。

`candidate_search_trace` 的嵌套格式版本为 2，与生成协议版本分开。`step_metrics` 保留每一次候选搜索的数量、耗时和终止状态；`steps` 只保存最近 32 步的详细拒绝记录，每步最多 16 条、每条最多 512 字符。截断标志仅描述日志保留范围，不限制实际候选搜索。`diagnose regress` 同时检查停止阈值，并记录合法长重复文本也会被启发式停止的反例，不能据此估计真实任务误停率。


细分计时还记录 prefix parse、attribute evaluation/setup、semantic equation、registered constraint、project parse、ranking、score transfer、token decode、candidate search 和 repetition detection。`semantic_seconds` 包含 prefix parse 与 attribute evaluation，后者又包含 setup/equation/registered 子项；候选选择包含 ranking 和 candidate search，同步重放也可能包含解码/解析/属性计算，不能将这些嵌套时间相加。`candidate_search_max_seconds` 和 `candidate_tests_max_per_step` 跨调用取最大值，其余计数/时间累加；有缺测时完整值保持空，仅另记已知下界。有限 `candidate_search_trace` 记录截断边界，保留原始结构，不把截断 trace 当作全体候选统计。

## 单条件运行记录

产品 CLI 提供 `doctor`、`generate`、`check` 和 `review` 四个命令，`generate` 每次处理一份需求，保留底层单次运行及调试用途：未指定 `--out` 时在 `.runs/` 下创建唯一目录，指定路径时要求目录尚不存在。论文实验统一从 `scripts/run_experiments.py` 进入，单文件实验也使用此入口；独立 `generate` 结果不会自动并入实验统计。实验脚本在每个条件目录调用相同生成流程；不同条件和不同批次的候选、检查工作区互相隔离。

每个条件目录中的文件如下：

- `result.json`：需求、完整会话、模型及 tokenizer 身份、环境版本、规则/编译语法摘要、标准及 OSATE revision、context 快照、输入输出 token、阶段耗时、约束执行证据、候选检查及诊断。
- `attempt-N/raw-response.txt`：该次模型响应原文，包括容器或未完成的部分。
- `attempt-N/project/`：该次提取的 `model_001.aadl` 等候选及 `context/` 快照；目录内仅放 AADL 源文件。
- `attempt-N/osate/`：独立校验工作区、OSATE 产物；实例检查结果另在 `result.json` 的 `attempts[].local_instance_rules` 中记录。
- `call-N-partial.txt`：错误响应中已返回的部分文本，仅在响应包含部分输出时保存。
- `project/`：通过适用规则和 OSATE 检查的最终工程，只在成功时保存，包含生成文件与显式依赖快照。

约束执行证据保存在 `result.json` 的 `calls[].constraint_evidence` 中。plain 的 AADL 语法/语义开关必须为 false，gcd 只开启 AADL 语法，joint 同时开启两者。每个候选的本地规则与 OSATE 结果分别保存在 `attempts[]` 中；例如 OSATE 可能把错误结束名仅记为 warning，本地属性规则仍会拒绝，应保留双方判定。

`constraint_evidence.mechanism_trace` 保存版本化的联合解码观测日志。`pending_reports_changed` 只在实际选中 token 被提交后记录 `pending_added` 和 `pending_no_longer_reported`，不记录候选试探、候选选择或 EOS 可行性查询；拒绝次数继续由原有计数保存。事件的 token 区间是输出序列中从零开始的半开区间，字符偏移按生成工程容器的 Unicode 字符计数；一次同步多个 token 时只记录两端状态，不虚构中间状态。plain/gcd 明确标为 disabled。

Pending 报告不再出现可能来自改判、改写或解析分支变化，不能自动称为单条义务兑现，也不能据此计算兑现率。只有实际选中 EOS 后，既有 `finish` 检查有效、没有 Pending 且没有残留 UTF-8 字节时，才记录一次 `project_obligations_closed`；这只证明当前规则包实现范围内的工程全局闭合，不证明自然语言需求满足。截断、死端及未完成检查没有这项闭合证据。`committed_token_count` 标明日志实际覆盖的提交边界，不含 EOS；`closed_after_token_count` 则包含实际 EOS。异常时响应中的实际 token 序列可能长于已提交边界，日志不会补造未同步尾部；旧记录缺少此字段表示当时未记录，不能补写成在线观测或视为零次变化。

## 部署和更新

在 PowerShell 7 执行；默认使用现有 SSH 别名 `qwen-server`。部署目录 `/home/wdd/aadl-single-agent`，模型复用 `/home/wdd/qwen3/models/Qwen3-14B`。独立环境复用已安装的模型依赖，服务固定 Outlines 1.2.6、llguidance 1.9.1、Transformers 4.51.3、PyTorch 2.6。

```powershell
pwsh -File scripts/deploy_server.ps1 -Action Deploy -Port 8001
pwsh -File scripts/deploy_server.ps1 -Action Rules -Port 8001
pwsh -File scripts/deploy_server.ps1 -Action Status
pwsh -File scripts/deploy_server.ps1 -Action Logs
```

`Start`、`Stop` 只操作 `aadl-single-agent.service`。部署保存独立版本目录和规则快照，端口被其他服务占用时停止部署，不清理未知进程。更新规则后会检查新服务返回的内容摘要。就绪检查要求 plain/joint；可选的 GCD/JSON Schema 不作为部署门槛。推理部署包不包含 `example`、历史结果或 `temp`。

本地版本 0.9.0 修改了词法前缀及服务校验代码，使用它之前必须执行完整 `-Action Deploy`，仅替换规则文件不能升级服务代码。本次仓库维护没有更新远端服务；旧服务与当前规则不一致时会明确拒绝生成。运行时版本一致后，若只修改 `aadl.lark` 和 `semantics.py`，可执行 `-Action Rules -Port 8001` 同步规则；客户端与服务器的文件摘要、编译语法摘要及运行时版本都必须一致。运行时代码再次变化时仍使用完整 Deploy。

## 数据与工作目录维护

`Generate` 保存新实验批次和汇总；清空后下一次实验自动初始化。`.runs`、`.cache` 是可重建工作目录，当前仓库不携带旧生成成功率或调试运行记录。删除失败且可移动的项目放入根目录 `temp`，该目录不参与版本控制或部署。

A3D 导入索引位于 `example/reference/public/a3d/import-records.json`，离线 `--check` 不依赖下载缓存。参考资料的来源快照、诊断摘要和实例产物属于资料溯源，继续保留。旧诊断日志中的工作区路径仅描述当时环境，记录的可用性字段说明工作区已清理；保留的实例文件另有可解析的路径和 SHA256。

RAG 示例的当前本地规则和 OSATE 证据保存于 `resources/retrieval/validation/`，不会引用生成实验目录。规则或示例改变后应重新校验并更新摘要；验证通过只说明相应语法和静态检查通过。

离线维护回归覆盖终结符续接、两种实例 XML、无规划接口规则包、单文件检查入口及统计资格。默认只在控制台输出，不创建实验结果：

```powershell
.\.venv\Scripts\python.exe -B scripts/run_experiments.py diagnose regress
.\.venv\Scripts\python.exe -B scripts/run_experiments.py diagnose feedback-regress
.\.venv\Scripts\python.exe -B scripts/run_experiments.py diagnose maintenance-regress
```
