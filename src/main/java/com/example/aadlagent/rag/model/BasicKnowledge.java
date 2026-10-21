package com.example.aadlagent.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AADL 语法知识条目。
 *
 * 支持两种格式的 JSON 反序列化（向后兼容）：
 * 1. 旧格式：title + content + category + tags
 * 2. 新格式（推荐）：syntax_name + category + bnf_syntax + natural_language_semantics
 *    + valid_examples + invalid_examples 等结构化字段
 *
 * 无论哪种格式，getFormattedContent() 都会返回适合做 embedding 和 RAG 注入的纯文本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BasicKnowledge {

    // ===== 基础字段（旧格式兼容） =====
    private String id;
    private String title;
    private String content;

    @JsonProperty("category")
    private String section;

    private List<String> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private float[] embedding;

    // ===== 新格式结构化字段 =====
    @JsonProperty("syntax_name")
    private String syntaxName;

    @JsonProperty("applicable_constructs")
    private List<String> applicableConstructs;

    @JsonProperty("abbreviation_note")
    private String abbreviationNote;

    @JsonProperty("parent_rule")
    private String parentRule;

    @JsonProperty("children_rule")
    private List<String> childrenRule;

    @JsonProperty("parsing_priority")
    private String parsingPriority;

    @JsonProperty("bnf_syntax")
    private String bnfSyntax;

    @JsonProperty("meta_symbol_definition")
    private Map<String, String> metaSymbolDefinition;

    @JsonProperty("terminal_list")
    private List<String> terminalList;

    @JsonProperty("non_terminal_list")
    private List<String> nonTerminalList;

    @JsonProperty("natural_language_semantics")
    private Map<String, String> naturalLanguageSemantics;

    @JsonProperty("syntax_constraint")
    private String syntaxConstraint;

    @JsonProperty("valid_examples")
    private List<Map<String, String>> validExamples;

    @JsonProperty("invalid_examples")
    private List<Map<String, String>> invalidExamples;

    @JsonProperty("naming_rules")
    private List<String> namingRules;

    @JsonProperty("legality_rules")
    private List<String> legalityRules;

    @JsonProperty("consistency_rules")
    private List<String> consistencyRules;

    private String semantics;

    // 非终结符速查表（运行时注入，来自知识库交叉引用）
    // key: 非终结符名称，value: 一句话中文释义
    private transient java.util.Map<String, String> nonTerminalSummaries;

    public void setNonTerminalSummaries(java.util.Map<String, String> summaries) {
        this.nonTerminalSummaries = summaries;
        // content 需要重新生成
        this.content = null;
    }

    /**
     * 将 BNF 元符号替换为中文描述，避免小模型把 {} [] 等元符号当真写进代码。
     * - { X }*  →  【零次或多次：X】
     * - { X }+  →  【一次或多次：X】
     * - [ X ]   →  【可选：X】
     * - ::=     →  定义为
     * - |       →  或
     * 注意：只替换单行模式，多行的由警告 + 正例/反例兜底。
     */
    private String renderBnfSafe(String bnf) {
        if (bnf == null || bnf.isEmpty()) return "";
        String result = bnf;

        // 替换 { X }* → 【零次或多次：X】（单行）
        result = result.replaceAll("\\{\\s*([^{}]+?)\\s*\\}\\s*\\*", "【零次或多次：$1】");
        // 替换 { X }+ → 【一次或多次：X】（单行）
        result = result.replaceAll("\\{\\s*([^{}]+?)\\s*\\}\\s*\\+", "【一次或多次：$1】");
        // 替换 [ X ] → 【可选：X】（单行，不含嵌套）
        result = result.replaceAll("\\[\\s*([^\\[\\]]+?)\\s*\\]", "【可选：$1】");

        return result;
    }

    /**
     * 返回适合做 embedding 和 prompt 注入的格式化纯文本。
     * 新格式：按"标题→BNF→语义→正例→反例→规则"的顺序组织。
     * 旧格式：直接返回 content。
     */
    public String getFormattedContent() {
        // 旧格式兼容
        if (bnfSyntax == null && content != null && !content.isEmpty()) {
            return content;
        }

        StringBuilder sb = new StringBuilder();

        // 顶部重要提醒（小模型容易忽略 BNF 元符号概念，放在最前面强化记忆）
        if (bnfSyntax != null && !bnfSyntax.isEmpty()) {
            sb.append("═══════════════════════════════════════\n");
            sb.append("⚠️  语法知识阅读须知\n");
            sb.append("  1. BNF 里的 {}（花括号）和 []（方括号）是【语法说明符号】，不是代码字符！\n");
            sb.append("  2. { X }* 表示 X 可以写 0 次或多次；{ X }+ 表示 X 至少写 1 次\n");
            sb.append("  3. [ X ] 表示 X 是可选的，可以写也可以不写\n");
            sb.append("  4. 只有「终结符」列表里出现的字符，才是代码里必须原样写的\n");
            sb.append("  5. 真实 AADL 代码里几乎不用 {}，唯一例外是附件的 {** 和 **}\n");
            sb.append("═══════════════════════════════════════\n\n");
        }

        // 标题行
        if (syntaxName != null) {
            sb.append("【语法单元】").append(syntaxName).append("\n");
        }
        if (section != null) {
            sb.append("【章节】").append(section).append("\n");
        }
        if (abbreviationNote != null && !abbreviationNote.isEmpty()) {
            sb.append("【缩写说明】").append(abbreviationNote).append("\n");
        }
        if (applicableConstructs != null && !applicableConstructs.isEmpty()) {
            sb.append("【适用组件】").append(String.join(", ", applicableConstructs)).append("\n");
        }
        sb.append("\n");

        // BNF
        if (bnfSyntax != null && !bnfSyntax.isEmpty()) {
            sb.append("【BNF 语法规则（重要说明）】\n");
            sb.append("  ⚠️  这是语法规则描述，不是代码模板！\n");
            sb.append("  ⚠️  BNF 中的 {} 花括号和 [] 方括号是【元符号】，表示「重复」和「可选」，绝对不能写进 AADL 代码！\n");
            sb.append("  ⚠️  只有「终结符」列表里的字符才是代码中必须原样写的。\n\n");
            sb.append("【BNF 产生式】\n").append(renderBnfSafe(bnfSyntax)).append("\n\n");

            // 非终结符速查表（交叉引用增强）
            if (nonTerminalList != null && !nonTerminalList.isEmpty()
                && nonTerminalSummaries != null && !nonTerminalSummaries.isEmpty()) {
                int found = 0;
                StringBuilder ntSb = new StringBuilder();
                ntSb.append("【BNF 中非终结符速查（点击/查找同名条目查看完整写法）】\n");
                for (String nt : nonTerminalList) {
                    String summary = nonTerminalSummaries.get(nt);
                    if (summary != null) {
                        ntSb.append("  • ").append(nt).append(" → ").append(summary).append("\n");
                        found++;
                    }
                }
                if (found > 0) {
                    sb.append(ntSb).append("\n");
                }
            }
        }

        // 元符号解释（对小模型很重要）
        if (metaSymbolDefinition != null && !metaSymbolDefinition.isEmpty()) {
            sb.append("【BNF 符号解释】\n");
            for (Map.Entry<String, String> e : metaSymbolDefinition.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" → ").append(e.getValue()).append("\n");
            }
            sb.append("\n");
        }

        // 终结符 / 非终结符
        if (terminalList != null && !terminalList.isEmpty()) {
            sb.append("【终结符（必须原样写的关键字/符号）】")
              .append(String.join("、", terminalList)).append("\n");
        }
        if (nonTerminalList != null && !nonTerminalList.isEmpty()) {
            sb.append("【非终结符（需要继续查表展开，不能直接写）】")
              .append(String.join("、", nonTerminalList)).append("\n");
        }
        if ((terminalList != null && !terminalList.isEmpty())
            || (nonTerminalList != null && !nonTerminalList.isEmpty())) {
            sb.append("\n");
        }

        // 语义
        if (naturalLanguageSemantics != null && !naturalLanguageSemantics.isEmpty()) {
            sb.append("【语义说明】\n");
            if (naturalLanguageSemantics.containsKey("summary")) {
                sb.append("  概括：").append(naturalLanguageSemantics.get("summary")).append("\n");
            }
            if (naturalLanguageSemantics.containsKey("syntax_semantics")) {
                sb.append("  语法含义：").append(naturalLanguageSemantics.get("syntax_semantics")).append("\n");
            }
            if (naturalLanguageSemantics.containsKey("domain_semantics")) {
                sb.append("  领域含义：").append(naturalLanguageSemantics.get("domain_semantics")).append("\n");
            }
            sb.append("\n");
        }

        // 语法约束
        if (syntaxConstraint != null && !syntaxConstraint.isEmpty()) {
            sb.append("【语法约束（BNF 表达不了的限制）】\n")
              .append(syntaxConstraint).append("\n\n");
        }

        // 正例
        if (validExamples != null && !validExamples.isEmpty()) {
            sb.append("【正确示例】\n");
            int idx = 1;
            for (Map<String, String> ex : validExamples) {
                sb.append("  例 ").append(idx++).append("：\n");
                if (ex.get("code") != null) {
                    sb.append("    ").append(ex.get("code").replace("\n", "\n    ")).append("\n");
                }
                if (ex.get("description") != null) {
                    sb.append("    说明：").append(ex.get("description")).append("\n");
                }
            }
            sb.append("\n");
        }

        // 反例
        if (invalidExamples != null && !invalidExamples.isEmpty()) {
            sb.append("【错误示例】\n");
            int idx = 1;
            for (Map<String, String> ex : invalidExamples) {
                sb.append("  反 ").append(idx++).append("：\n");
                if (ex.get("code") != null) {
                    sb.append("    ").append(ex.get("code").replace("\n", "\n    ")).append("\n");
                }
                if (ex.get("description") != null) {
                    sb.append("    场景：").append(ex.get("description")).append("\n");
                }
                if (ex.get("reason") != null) {
                    sb.append("    原因：").append(ex.get("reason")).append("\n");
                }
            }
            sb.append("\n");
        }

        // 规则
        if (namingRules != null && !namingRules.isEmpty()) {
            sb.append("【命名规则】\n");
            for (String r : namingRules) sb.append("  - ").append(r).append("\n");
            sb.append("\n");
        }
        if (legalityRules != null && !legalityRules.isEmpty()) {
            sb.append("【合法性规则】\n");
            for (String r : legalityRules) sb.append("  - ").append(r).append("\n");
            sb.append("\n");
        }
        if (consistencyRules != null && !consistencyRules.isEmpty()) {
            sb.append("【一致性规则】\n");
            for (String r : consistencyRules) sb.append("  - ").append(r).append("\n");
            sb.append("\n");
        }

        // 标准原文语义（简短追加）
        if (semantics != null && !semantics.isEmpty()) {
            sb.append("【标准原文】").append(semantics).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * 兼容旧逻辑：content 为空但有新格式字段时，用格式化文本填充。
     * 这样 embedding、RAG payload 等依赖 getContent() 的地方都能正常工作。
     */
    public String getContent() {
        if (content == null || content.isEmpty()) {
            content = getFormattedContent();
        }
        return content;
    }

    /**
     * 兼容旧逻辑：title 为空时用 syntax_name 填充。
     */
    public String getTitle() {
        if ((title == null || title.isEmpty()) && syntaxName != null) {
            title = syntaxName;
        }
        return title;
    }

    /**
     * 获取 tags：旧格式直接用；新格式从 syntax_name 和 category 派生关键词。
     */
    public List<String> getTags() {
        if (tags != null && !tags.isEmpty()) {
            return tags;
        }
        List<String> derived = new ArrayList<>();
        if (syntaxName != null) derived.add(syntaxName);
        if (section != null) derived.add(section);
        if (parentRule != null) derived.add(parentRule);
        if (childrenRule != null) derived.addAll(childrenRule);
        return derived;
    }
}
