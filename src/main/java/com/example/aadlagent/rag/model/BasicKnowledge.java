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
            sb.append("【BNF 语法】\n").append(bnfSyntax).append("\n\n");
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
