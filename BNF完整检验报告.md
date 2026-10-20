# AADL 知识库 BNF 完整检验报告（v2.2 修复版）

**检验对象**：`knowledge/aadl.json` v2.2（81 条）
**对照基准**：AS5506C AADL Version 2.2 标准
**检验维度**：字段完整性、BNF 质量、示例质量、语义质量、规则质量、一致性
**版本历史**：v2.1（初检，B+）→ v2.2（修复后，A-）
**检验日期**：2026-10-19

---

## 一、总览

| 指标 | v2.1 | v2.2（修复后） | 变化 |
|---|---|---|---|
| 总条目数 | 81 | 81 | — |
| 覆盖章节 | 13 章 + Annex C | 13 章 + Annex C | — |
| **BNF 存在率** | 98.8% | **100%** | +1.2% |
| **BNF 含标准 `::=` 格式** | 61.7% | **100%** | +38.3% |
| BNF 无 PDF 噪声 | 100% | 100% | — |
| 正例 3 条 | 100% | 100% | — |
| 反例 2 条 | 100% | 100% | — |
| 示例含 code+description | 100% | 100% | — |
| **反例含 reason** | ~69% | **100%** | +31% |
| 语义说明三层完整 | 100% | 100% | — |
| terminal_list 非空 | 95.1% | 97.5% | +2.4% |
| non_terminal_list 非空 | 96.3% | 96.3% | — |
| **naming_rules 非空** | 79.0% | 93.8% | +14.8% |
| **legality_rules 非空** | 98.8% | **100%** | +1.2% |
| **consistency_rules 非空** | 97.5% | **100%** | +2.5% |

**综合质量等级：A-**

- ✅ BNF 格式合规率从 61.7% → 100%（最大提升）
- ✅ 反例 reason 字段覆盖率从 ~69% → 100%
- ✅ legality_rules / consistency_rules 均达 100%
- ⚠️ naming_rules 93.8%（剩余 5 条为词法底层，确实无命名规则）
- ⚠️ terminal_list 97.5%（剩余 2 条为 identifier 类，无固定终结符）
- ⚠️ non_terminal_list 96.3%（剩余 3 条为纯终结符词法条目）

---

## 二、本次修复内容（共 68+ 处）

### P0 严重（1 条）：component_prototype 空 BNF

**修复前**：`bnf_syntax` 字段为空字符串  
**修复后**：补充完整 BNF 产生式

```
component_prototype ::=
    component_category
    [ unique_component_classifier_reference ]
    [ array_dimension ]
```

同时补充了 `terminal_list`（12 种组件类别 + `[]`）。

### P1 重要（31 条）：BNF 缺少标准 `name ::=` 左部格式

为 31 条缺少 `::=` 的 BNF 补充标准左部格式，模式如下：

**修复前**（示例）：
```
component_category defining_component_type_identifier  
    extends unique_component_type_reference ...
```

**修复后**：
```
component_type_extension ::=
    component_category defining_component_type_identifier
    extends unique_component_type_reference ...
```

**31 条完整清单**：

| 类别 | 条目数 | 条目列表 |
|---|---|---|
| 组件扩展 | 2 | component_type_extension, component_implementation_extension |
| 特征组 | 2 | feature_group_type, feature_group_type_extension |
| 原型 | 2 | prototype, feature_prototype |
| 包/导入 | 2 | import_declaration, alias_declaration |
| 模式 | 3 | mode, modes_subclause, mode_transition_trigger |
| 附件 | 2 | annex_subclause, annex_library |
| 属性类型 | 6 | enumeration_type, range_type, units_type, record_type, classifier_type, reference_type |
| 属性声明 | 2 | property_type_declaration, property_definition_declaration |
| 子程序调用 | 1 | called_subprogram |
| 子组件 | 1 | subcomponent |
| 连接 | 1 | connection |
| 内部/处理器特征 | 2 | internal_feature, processor_feature |
| 包含属性 | 1 | contained_property_association |
| 数据集附件 | 2 | dataset_annex_library, dataset_annex_subclause |
| 特殊定制 | 2 | delimiters_and_operators, component_category |

其中 `delimiters_and_operators` 和 `component_category` 原为纯列表描述，已重写为标准 BNF 分层结构。

### P2 一般（命名规则 + 反例 reason）

#### 命名规则补充（9 条非词法条）

| 条目 | 新增规则数 | 核心内容 |
|---|---|---|
| unit_identifier | 3 | 命名规则、缩写惯例、大小写规则 |
| with_clause | 3 | 包名匹配、大小写、重复引用 |
| property_association | 3 | 属性名引用、applies to 元素引用 |
| property_reference | 3 | 属性名指向、路径有效性 |
| import_declaration | 3 | 包/属性集名存在性、逗号分隔 |
| modes_subclause | 3 | 模式名唯一性、命名规则 |
| mode_transition_trigger | 3 | 特征引用有效性、保留字 |
| contained_property_association | 3 | 属性标识符、包含路径有效性 |
| dataset_annex_subclause | 3 | 数据集引用、逗号分隔 |

#### 一致性规则补充（8 条）

| 条目 | 新增规则数 |
|---|---|
| identifier | 3 |
| keywords | 3 |
| string_literal | 3 |
| property_type | 3 |
| unit_identifier | 3 |
| numeric_literal | 3 |
| comment | 3 |
| delimiters_and_operators | 3 |
| expression | 3 |

#### 合法性规则补充（1 条）

| 条目 | 新增规则数 |
|---|---|
| string_literal | 4 |

#### 反例 reason 字段统一（25 条）

为 25 条缺少 `reason` 字段的反例补充了错误原因说明。现在全部 162 个反例（81 × 2）均包含 `code`、`description`、`reason` 三个字段。

#### keywords terminal_list 填充

原 `keywords` 条目的 `terminal_list` 为空，现补充了完整的 AADL 关键字列表（约 70+ 个关键字，按类别分组）。

---

## 三、剩余已知局限（均为合理现象）

### 3.1 terminal_list 缺失（2 条，可接受）

| 条目 | 原因 |
|---|---|
| identifier | 标识符由字母数字下划线组成，没有固定的关键字终结符 |
| unit_identifier | 同上，单位标识符遵循 identifier 规则 |

**结论**：语法上合理，不需要修复。

### 3.2 non_terminal_list 缺失（3 条，可接受）

| 条目 | 原因 |
|---|---|
| comment | 注释是纯终结符结构（`--` + 任意文本），不含非终结符 |
| keywords | 关键字列表本身就是终结符集合 |
| delimiters_and_operators | 分隔符/运算符全是终结符 |

**结论**：语法上合理，不需要修复。

### 3.3 naming_rules 缺失（5 条，可接受）

| 条目 | 原因 |
|---|---|
| numeric_literal | 数字字面量没有"命名"概念 |
| string_literal | 字符串没有"命名"概念 |
| comment | 注释没有"命名"概念 |
| delimiters_and_operators | 分隔符是固定符号，没有命名问题 |
| expression | 表达式是计算结构，没有命名问题 |

**结论**：这 5 条均为词法底层/表达式条目，"命名规则"概念不适用，不需要修复。

### 3.4 syntax_name 与 BNF 左部单复数不一致（1 条，可接受）

| 条目 | syntax_name | BNF 左部 | 说明 |
|---|---|---|---|
| keywords | `keywords` | `keyword` | 命名风格选择（集合名 vs 元素名），不影响理解 |

---

## 四、详细检验结果

### 4.1 字段完整性

| 字段 | 覆盖率 | 状态 |
|---|---|---|
| syntax_name | 81/81 (100%) | ✅ |
| category | 81/81 (100%) | ✅ |
| parent_rule | 81/81 (100%) | ✅ |
| bnf_syntax | 81/81 (100%) | ✅ |
| meta_symbol_definition | 81/81 (100%) | ✅ |
| terminal_list | 79/81 (97.5%) | ⚠️ 2 条 identifier 类合理缺失 |
| non_terminal_list | 78/81 (96.3%) | ⚠️ 3 条纯终结法条合理缺失 |
| natural_language_semantics | 81/81 (100%) | ✅ |
| syntax_constraint | 81/81 (100%) | ✅ |
| valid_examples | 81/81 (100%) | ✅ |
| invalid_examples | 81/81 (100%) | ✅ |
| naming_rules | 76/81 (93.8%) | ⚠️ 5 条词法条合理缺失 |
| legality_rules | 81/81 (100%) | ✅ |
| consistency_rules | 81/81 (100%) | ✅ |

### 4.2 BNF 质量

| 检查项 | 结果 |
|---|---|
| 无 BNF 的条目 | 0 条 ✅ |
| 不含 `::=` 的条目 | 0 条 ✅ |
| 含 PDF 噪声的条目 | 0 条 ✅ |
| syntax_name 与 BNF 左部一致 | 98.8%（仅 keywords 单复数差异）✅ |

### 4.3 示例质量

| 检查项 | 结果 |
|---|---|
| 正例 = 3 条 | 81/81 ✅ |
| 反例 = 2 条 | 81/81 ✅ |
| 正例含 code+description | 81/81 ✅ |
| 反例含 code+description+reason | 81/81 ✅ |

### 4.4 语义质量

| 检查项 | 结果 |
|---|---|
| 含 summary | 81/81 ✅ |
| 含 syntax_semantics | 81/81 ✅ |
| 含 domain_semantics | 81/81 ✅ |

---

## 五、章节分布

81 条条目覆盖 AS5506C 标准的 13 个章节 + Annex C：

| 大章节 | 条目数 | 细分布 |
|---|---|---|
| Ch.4 核心概念 / 词法 | 11 | 4.1~4.11 |
| Ch.5 包 | 6 | 5.1~5.6 |
| Ch.6 组件类型 | 7 | 6.1~6.7 |
| Ch.7 执行平台 | 6 | 7.2~7.7 |
| Ch.8 特征 | 11 | 8.1~8.11 |
| Ch.9 子组件与原型 | 5 | 9.1~9.4 |
| Ch.10 连接 | 7 | 10.1~10.6 |
| Ch.11 流 | 3 | 11.1~11.3 |
| Ch.12 属性集 | 13 | 12.1~12.4 |
| Ch.13 模式 | 5 | 13.1~13.5 |
| Ch.14 子程序调用 | 3 | 14.1~14.3 |
| Annex C 表达式 | 2 | Annex C.1 |

---

## 六、质量等级评定

### v2.1 → v2.2 对比

| 维度 | v2.1 (B+) | v2.2 (A-) | 提升 |
|---|---|---|---|
| BNF 格式合规 | 61.7% | 100% | +38.3% ⭐最大提升 |
| 示例完整性 | 100% | 100% | — |
| 反例 reason 覆盖 | ~69% | 100% | +31% |
| 规则完整性 | 79~99% | 94~100% | 均衡提升 |
| 语义完整性 | 100% | 100% | — |
| 章节覆盖 | 完整 | 完整 | — |

### 为什么是 A- 不是 A

扣分项在于：
1. **naming_rules 93.8%**（5 条词法条合理缺失，但仍是指标上的扣分项）
2. **terminal_list 97.5%**（2 条 identifier 类合理缺失）
3. **non_terminal_list 96.3%**（3 条纯终结法条合理缺失）
4. **表达式颗粒度较粗**（expression 一条覆盖，未拆 relation/term/factor）

这些都是"合理缺失"而非"质量缺陷"，但从指标完整性角度仍有提升空间。

### 达到 A+ 需要

- 将表达式拆分为 relation / simple_expression / term / factor / primary（+4~5 条）
- 增加 refinement 规则覆盖（+24 条，价值评估待定）
- 增加流细分（flow_source_spec, flow_sink_spec 等，+3~5 条）
- 为词法条目的"合理缺失"字段添加说明性内容（如 terminal_list 注明"无固定终结符"）

---

## 七、结论

经过 P0/P1/P2 三级修复，`aadl.json` v2.2 的 BNF 知识库质量从 **B+ 提升至 A-**，核心改进：

1. **BNF 格式完全标准化**：31 条补全 `name ::=` 左部，1 条补全空 BNF，合规率 61.7% → 100%
2. **反例质量统一**：25 条反例补充 `reason` 字段，所有反例均含错误原因说明
3. **规则字段全面补全**：legality_rules / consistency_rules 均达 100%，naming_rules 达 93.8%
4. **关键字列表充实**：keywords 条目补充了 70+ 个 AADL 关键字的 terminal_list

81 条知识库已可以作为高质量的 AADL 核心语法 RAG 知识源，每条都有标准的 BNF 产生式、三层语义说明、3 正 2 反示例、以及命名/合法性/一致性规则。

---

*报告生成时间：2026-10-19*
*知识库版本：v2.2*
*检验工具：程序化校验 + 人工抽样复核*
*对照标准：AS5506C AADL Version 2.2*
