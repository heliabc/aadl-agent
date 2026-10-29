# AADL Single Agent

单个智能体从需求生成 AADL；Outlines + llguidance 与动态语义规则共同限制 token，OSATE 提供独立校验。规划、生成和修复沿用同一模型与会话。

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

`--constraints plain` 为普通 AADL 生成，`--constraints joint` 为语法语义联合约束解码，默认 joint。没有独立的 syntax 模式。

`--mode direct` 直接连续生成；`--mode planned` 先生成声明与接口计划，再生成正文，默认 direct。规划 JSON 使用 Schema 约束；planned + plain 的最终 AADL 仍不施加 token 掩码。

```powershell
.\.venv\Scripts\aadl-single-agent.exe generate --prompt 'Create a package Demo with system Root and implementation Root.impl.' --constraints joint --out .runs/demo
.\.venv\Scripts\aadl-single-agent.exe generate --requirements requirements.txt --mode planned --constraints plain --out .runs/planned
.\.venv\Scripts\aadl-single-agent.exe check .runs/demo/model.aadl
```

生成后分别执行规则检查和 OSATE 检查；规则拒绝的候选也接受独立 OSATE 检查，保留两个检查器的分歧供分析。两者通过且生成正常结束才保存 `model.aadl`。模型诊断反馈给同一会话；协议、服务和 OSATE 基础设施故障单独记录。

默认共享输出 token 预算 8192，包含规划和修复；规划最多 2048，单次 AADL 最多 4096，最多三次 AADL 生成。`--token-budget` 和 `--max-attempts` 可调整实验参数。输出预算相同不表示输入 token、总计算量或耗时相同。未知费用明确标注，不能解释为零成本。

AADL 正文因 `length` / `max_tokens` 截断时，仅在响应的模型、tokenizer、规则身份、对应请求的约束凭据及真实 token 用量全部核验通过后，才允许把部分输出和截断诊断反馈同一会话，重新生成完整模型。重试仍受剩余共享预算与最多三次正文生成限制，已消耗的 token 照常计入。规划 JSON 截断、网络超时和协议核验失败直接终止；截断输出即使通过检查也不作为成功结果。模型文本按原始 UTF-8 换行落盘，最终 `model.aadl` 与通过检查的候选字节一致。

## 联合解码与规则扩展

每步先运行 Outlines 的 llguidance CFG 掩码，再对仍允许的候选按模型分数依次试探语义，选择最高分合法 token。候选试探不提交状态，只有实际选中的 token 才同步推进。EOS 要求语法完整且待兑现义务清空。默认单序列贪心，不支持 beam、随机采样或 KV 回滚；无合法候选明确报死端，不放弃约束。

未定义本地引用会累积类别和接口义务。例如 `child: system Worker.impl;` 允许 Worker 后定义，但其类型、实现和所有使用过的接口都必须兑现。多个引用合并约束，确定冲突立即拒绝。允许的前缀不等于保证存在合法补全，死端仍可能触发整段修复。

`resources/aadl/` 是默认可信规则包，集中保存文法、语义数据、事件绑定、Python 语义动作、规划 Schema、提示词和示例。通用运行时从规则包加载领域知识；简单规则调整数据，复杂规则添加规则包内的函数及绑定。使用 `--rules PATH` 可选择独立规则包，所有命令均支持。

扩展步骤：

1. 在规则包中增加产生式、必要的词法边界和语义绑定；增加规则数据或检查函数。
2. 对合法与非法模型分别执行 `check`，核对本地规则与 OSATE 的诊断。
3. 用下文的 Rules 命令同步到服务器；部署会核验内容摘要并重启自己的服务。
4. 运行单个需求生成，结果记录本次使用的规则摘要。

规则包是可信本地代码，HTTP 接口不接受 Python 代码上传。服务与客户端绑定内容摘要及运行时版本；只改相同的 profile 名称不能绕过内容检查。

首轮支持单个 public package、八类组件、类型与实现、普通端口、子组件、port connections，以及 Data_Size、Code_Size、Memory_Size、Word_Size、Source_Name、Type_Source_Name。大小属性非负是本项目生成配置，不宣称 AADL 标准本身规定该下限。外部库、继承、annex、modes、flows、access、binding、prototypes 和 refinement 仍需以后补规则。

使用自定义规则包时，部署命令通过 `-RulesPath PATH` 选择该目录，客户端的 `doctor`、`generate` 和 `check` 同时传入 `--rules PATH`。服务切换规则会重启，应等待当前生成结束后执行。切回 `resources/aadl/` 默认规则时执行 `pwsh -File scripts/deploy_server.ps1 -Action Rules`，再运行不带 `--rules` 的 `doctor`；不能只切换客户端规则。

## 迁移的研究案例

`examples/research/requirements/` 保存从 `aadl-research/osate-examples/examples` 迁移的 146 份需求原文，`references/` 单独保存 75 份参考 AADL，均与源文件字节一致。需求包含中文、英文及改写版本；`manifest.json` 记录来源路径、SHA256、语言和基于路径/显式文件名别名推断的 57 个候选参考组。这些配对和分组尚不证明需求语义等价，也不应将多个版本直接视为独立任务。原有 AADL 版权与许可声明完整保留，未据此推定需求文本的许可。

先用单个需求测试当前规则，例如数据字典案例：

```powershell
.\.venv\Scripts\aadl-single-agent.exe generate --requirements examples/research/requirements/ACVIP/DataDictionary/data_re.txt --mode direct --constraints joint
```

`generate --requirements` 每次直接读取所选需求文本，参考 AADL 不进入模型提示。`cases.jsonl` 保留为迁移时的需求文本快照，`manifest.json` 用于追溯来源；两者均不是当前生成命令的输入，修改需求文本不会自动更新它们。其余案例部分涉及跨包、flows、annex 等超出当前默认规则的结构，应按规则覆盖范围选择案例逐一测试。

## 单案例运行结果

CLI 提供 `doctor`、`generate` 和 `check` 三个命令。`generate` 可独立选择 direct/planned 和 plain/joint；每次命令只处理一份需求。未指定 `--out` 时，在 `.runs/` 下自动创建唯一目录；指定路径时要求该目录尚不存在，以保留已有结果。

以一次生成的输出目录为根，产物路径如下：

- `result.json`：需求、完整会话、模型及 tokenizer 身份、环境版本、规则摘要、输入输出 token、阶段耗时、约束执行证据、候选检查及诊断。
- `attempt-N/candidate.aadl`：每次正文候选；对应 `osate/` 下保留独立校验工作区。
- `call-N-partial.txt`：错误响应中已返回的部分文本，仅在响应包含部分输出时保存。
- `model.aadl`：通过完整规则检查和 OSATE 校验的最终模型，仅在成功时保存。

联合解码证据保存在 `result.json` 的 `calls[].constraint_evidence` 中，包括掩码步数、语义候选与拒绝数、语法掩码及语义筛选耗时。每个候选的本地规则与 OSATE 结果分别保存在 `attempts[]` 中，可据此分析两个检查器的分歧。

## 部署和更新

在 PowerShell 7 执行；默认使用现有 SSH 别名 `qwen-server`。部署目录 `/home/wdd/aadl-single-agent`，模型复用 `/home/wdd/qwen3/models/Qwen3-14B`。独立环境复用已安装的模型依赖，服务固定 Outlines 1.2.6、llguidance 1.9.1、Transformers 4.51.3、PyTorch 2.6。

```powershell
pwsh -File scripts/deploy_server.ps1 -Action Deploy
pwsh -File scripts/deploy_server.ps1 -Action Rules
pwsh -File scripts/deploy_server.ps1 -Action Status
pwsh -File scripts/deploy_server.ps1 -Action Logs
```

`Start`、`Stop` 只操作 `aadl-single-agent.service`。部署保存独立版本目录和规则快照，端口被其他服务占用时停止部署，不清理未知进程。更新规则后会检查新服务返回的内容摘要。失败产物和日志保留供诊断。

`status=passed` 表示本次规则检查及 OSATE 校验通过，结果记录 `validation_scope=syntax_and_static_semantics` 和 `requirements_verified=false`。这不是自然语言需求符合性的证明，也不代表已经覆盖完整 AADL。
