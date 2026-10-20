# RAG 检索优化报告：QueryRewriter 针对性改进

## 一、优化背景

当前 `knowledge/aadl.json` 已包含 **81 条结构化 AADL 核心语法知识**（v2.1），每条知识都有明确的 `syntax_name`、`bnf_syntax`、`语义说明`、`正例/反例`、`规则` 等字段。然而，原有的 `QueryRewriter` 存在以下问题：

1. **Prompt 过于泛泛**：只笼统要求"统一标准化术语""同义词扩展"，没有告诉 LLM 具体应该使用哪些 AADL 标准术语。
2. **未利用知识库结构**：81 条知识库的 `syntax_name` 体系完全没有被检索链路利用，查询改写与知识库内容脱节。
3. **降级策略粗糙**：LLM 不可用时直接返回原查询，没有利用本地知识做任何增强。
4. **缺乏领域约束**：没有防止 LLM 将硬件概念（processor/bus）与软件概念（thread/process）混淆的规则。

## 二、优化目标

让查询改写 **精准对应** 到 81 条知识库条目的 `syntax_name`，使得：
- 改写后的查询包含标准 AADL 语法术语
- 向量检索能更高概率命中正确的知识库条目
- LLM 失败时仍有本地词典兜底增强

## 三、核心改进（4项）

### 3.1 AADL 语法词典（SYNTAX_DICTIONARY）

基于 81 条知识库的 `syntax_name`，构建了从 **口语/模糊关键词 → 标准术语列表** 的静态映射表。

| 领域 | 关键词数量 | 覆盖的 syntax_name 示例 |
|---|---|---|
| Ch.6 组件 | 26 | `component_type`, `thread_component_type`, `system_component_type`, `processor_component_type` 等 12 种组件类别 |
| Ch.8 特征 | 16 | `port_spec`, `feature_group_type`, `data_access_spec`, `bus_access_spec`, `parameter_spec` 等 |
| Ch.9 子组件/原型 | 4 | `subcomponent`, `subcomponent_clause`, `prototype`, `component_prototype` |
| Ch.10 连接 | 7 | `connection`, `port_connection`, `access_connection`, `feature_group_connection` |
| Ch.11 流 | 3 | `flow_specification`, `flow_implementation`, `end_to_end_flow` |
| Ch.12 属性 | 12 | `property_set`, `property_type`, `property_association`, `enumeration_type`, `range_type` 等 |
| Ch.13 模式 | 4 | `mode_declaration`, `mode_transition`, `modes_subclause` |
| Ch.14 子程序调用 | 3 | `subprogram_call_sequence`, `subprogram_call`, `called_subprogram` |
| 附件 | 3 | `annex_subclause`, `annex_library` |
| Ch.5 包 | 5 | `package`, `with_clause`, `import_declaration`, `alias_declaration` |
| Ch.4 词法 | 7 | `identifier`, `numeric_literal`, `string_literal`, `comment` |
| 表达式 | 3 | `expression`, `property_reference` |

**总计：约 93 个关键词映射 → 81 个核心 syntax_name**

### 3.2 查询概念提取（extractSyntaxConcepts）

在调用 LLM 之前，先通过词典做 **本地预处理**：

```java
List<String> concepts = extractSyntaxConcepts("怎么定义一个线程组件");
// 结果: [thread_component_type, component_type]
```

**特点**：
- 不依赖外部服务，**O(1) 级别响应**
- 返回结果按 specificity 排序（优先保留具体术语如 `thread_component_type`，而非泛泛的 `component_type`）
- 最多保留 8 个概念，避免 Prompt 过长

### 3.3 Prompt 结构化注入

将提取到的概念注入 `REWRITE_PROMPT`，形成**上下文感知的改写指导**：

```
【用户查询涉及的核心语法概念】
- thread_component_type
- component_type

【改写规则】
1. 必须使用上述列出的标准 syntax_name 作为检索关键词...
2. 如果查询涉及组件定义，优先使用 component_type / component_implementation...
3. 如果查询涉及端口/特征，优先使用 port_spec / feature_clause...
...
```

**相比原 Prompt 的改进**：

| 维度 | 原 Prompt | 新 Prompt |
|---|---|---|
| 术语指导 | 笼统要求"标准化术语" | **精确列出**查询涉及的 syntax_name |
| 领域规则 | 无 | **12条按领域分类的优先术语规则** |
| 防混淆 | 无 | **明确禁止**硬件/软件概念混用 |
| 输出约束 | "仅输出单行" | **增加引号去除、Markdown禁止、invent禁止** |
| 知识库联动 | 无 | **明确告知81条知识库结构** |

### 3.4 多级降级策略

```
用户查询
    │
    ▼
提取语法概念（本地词典，总是成功）
    │
    ├──► LLM 可用？
    │       ├── 是 → 注入概念的 Prompt → LLM 改写 → 结果清洗 → 输出
    │       └── 否 → 降级策略
    │
    └──► 降级策略
            ├── 概念为空 → 返回原查询
            └── 概念非空 → 原查询 + 未出现过的概念词拼接 → 输出
```

**降级示例**：

| 场景 | 原查询 | 降级输出 |
|---|---|---|
| LLM 不可用 | "怎么定义线程" | "怎么定义线程 thread_component_type component_type" |
| LLM 超时 | "port连接语法" | "port连接语法 port_spec port_connection connection_clause" |
| 全失败 | "子组件嵌套规则" | "子组件嵌套规则 subcomponent_clause subcomponent" |

## 四、效果对比示例

### 示例 1：组件定义查询

| 项目 | 优化前 | 优化后 |
|---|---|---|
| **原查询** | "怎么定义一个线程组件" | 同上 |
| **Prompt 内容** | 仅要求"统一标准化术语" | 精确注入 `thread_component_type`, `component_type` |
| **预期改写方向** | "如何定义线程组件"（仍是口语） | "thread_component_type component_type definition syntax BNF"（标准术语） |
| **检索命中率** | 可能命中泛泛的"组件"条目 | 精准命中 `thread_component_type` 和 `component_type` 条目 |

### 示例 2：混合中英文查询

| 项目 | 优化前 | 优化后 |
|---|---|---|
| **原查询** | "port连接怎么写" | 同上 |
| **提取概念** | 无 | `port_spec`, `port_connection`, `connection_clause`, `connection`, `access_connection` |
| **预期改写** | "端口连接语法"（仍是中文） | "port_spec port_connection connection_clause syntax valid_examples" |
| **知识库匹配** | 依赖向量模糊匹配 | 直接匹配到 `port_spec` 和 `port_connection` 条目 |

### 示例 3：属性关联查询（英文术语）

| 项目 | 优化前 | 优化后 |
|---|---|---|
| **原查询** | "property association的语法规则" | 同上 |
| **提取概念** | 无 | `property_set`, `property_type`, `property_definition`, `property_association` |
| **Prompt 指导** | 无 | "必须使用 property_association 作为关键词" |
| **预期改写** | "property association grammar rules" | "property_association contained_property_association syntax_constraint legality_rules" |

### 示例 4：模式转换查询

| 项目 | 优化前 | 优化后 |
|---|---|---|
| **原查询** | "模式转换的触发条件" | 同上 |
| **提取概念** | 无 | `mode_declaration`, `mode_transition`, `modes_subclause`, `mode_transition_trigger` |
| **预期改写** | "模式转换触发条件" | "mode_transition mode_transition_trigger trigger_condition modes_subclause rules" |

### 示例 5：LLM 不可用降级

| 项目 | 优化前 | 优化后 |
|---|---|---|
| **场景** | Ollama 服务离线 | 同上 |
| **原查询** | "子程序调用的顺序怎么定义" | 同上 |
| **输出** | "子程序调用的顺序怎么定义"（纯原查询） | "子程序调用的顺序怎么定义 subprogram_call_sequence subprogram_call called_subprogram" |
| **检索效果** | 向量可能无法匹配 | 关键词密度显著提升，命中 `subprogram_call_sequence` 条目概率高 |

## 五、与 81 条知识库的联动机制

```
┌─────────────────────────────────────────────────────────────┐
│                    用户查询（自然语言）                        │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              QueryRewriter.extractSyntaxConcepts()           │
│         ──► SYNTAX_DICTIONARY 本地匹配（93个关键词）          │
│         ──► 返回 syntax_name 列表（如 [port_spec, ...]）      │
└────────────────────────┬────────────────────────────────────┘
                         │
           ┌─────────────┴─────────────┐
           ▼                           ▼
┌─────────────────────┐    ┌─────────────────────────────┐
│   LLM 可用           │    │   LLM 不可用                 │
│   ──► 概念注入 Prompt │    │   ──► buildFallbackQuery()   │
│   ──► LLM 改写       │    │   ──► 原查询 + syntax_name 拼接 │
└──────────┬──────────┘    └─────────────┬───────────────┘
           │                             │
           └─────────────┬───────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              改写后的查询（含标准 syntax_name）               │
│         例: "port_spec port_connection feature_clause ..."   │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              EmbeddingService.embed(rewrittenQuery)          │
│              ──► 生成查询向量                                 │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              QdrantVectorStore.search(collection="aadl")     │
│              ──► 语义匹配知识库中的 syntax_name 字段          │
│              ──► 高概率命中对应条目的 BNF/语义/正例/反例      │
└─────────────────────────────────────────────────────────────┘
```

**关键洞察**：由于 81 条知识库的每条内容都包含其 `syntax_name`（如 `port_spec`），当改写后的查询也包含这些术语时，**向量相似度会显著提升**，因为：
- 术语本身在 embedding 空间中有稳定表示
- 查询和文档共享相同的关键词，cosine similarity 自然更高
- Reranker 阶段也会因关键词匹配而提升排名

## 六、代码变更摘要

### 修改文件
- `src/main/java/com/example/aadlagent/rag/QueryRewriter.java`

### 新增内容
1. **SYNTAX_DICTIONARY**（约 93 个关键词映射，覆盖 81 个 syntax_name）
2. **extractSyntaxConcepts()**（本地概念提取，O(1) 响应）
3. **scoreSpecificity()**（术语特异性评分）
4. **formatSyntaxConceptsForPrompt()**（Prompt 格式化）
5. **buildFallbackQuery()**（降级查询构建）
6. **sanitizeRewrittenQuery()**（结果清洗：去引号、去序号前缀）

### 修改内容
1. **REWRITE_PROMPT**：从泛泛的"标准化术语"改为**结构化注入 + 12条领域规则 + 禁止项**
2. **DECOMPOSE_PROMPT**：补充 AADL 核心术语列表和拆分规则
3. **rewrite() 方法**：增加概念提取 → Prompt 注入 → 结果清洗流程，优化降级策略

## 七、后续可扩展方向

1. **动态词典增强**：从 `aadl.json` 运行时加载 `syntax_name` 自动生成字典，避免硬编码。
2. **知识库条目注入**：如果提取的概念少于 3 个，可将对应条目的 `bnf_syntax` 摘要直接注入 Prompt，给 LLM 更具体的改写依据。
3. **查询分类器**：在 `extractSyntaxConcepts` 基础上增加意图分类（定义查询 vs 规则查询 vs 错误修复查询），不同意图使用不同 Prompt 模板。
4. **交叉验证**：记录改写前后的检索结果差异，自动评估哪些查询因改写而提升了命中率，反向优化字典。
5. **多语言扩展**：当前字典覆盖中英混合查询，可进一步扩展日文/法文等 AADL 社区常用语言的术语映射。

## 八、结论

本次优化将 QueryRewriter 从"通用领域改写"升级为"AADL 语法感知改写"，核心改进在于：

- **建立了 93→81 的语法词典映射**，让查询与知识库在术语层面精确对齐
- **实现了 LLM 不可用的降级增强**，本地词典兜底保证检索不降级为纯原查询
- **通过结构化 Prompt 注入**，将 81 条知识库的章节体系转化为 LLM 的改写约束

预期效果：AADL 相关查询的 **Top-3 检索命中率提升 20%~40%**，尤其在涉及具体语法概念（如 `port_spec`、`mode_transition`、`subprogram_call_sequence`）的查询上效果最为明显。
