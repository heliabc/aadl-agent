package com.example.aadlagent.agent.tool;

import com.example.aadlagent.util.AadlReferenceValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AADL 代码验证工具
 *
 * 对给定的 AADL 代码执行静态语法和引用完整性验证，
 * 返回错误列表、警告列表和自动修复结果。
 *
 * 用途：在 ReAct 修复循环中，每次 LLM 修改代码后调用此工具验证，
 * 让 LLM 知道哪些错误还在、哪些已修复，实现"修改-验证-再修改"的闭环。
 */
@Slf4j
@Component
public class ValidateAadlTool implements Tool {

    private static final String TOOL_NAME = "validate_aadl";

    private final AadlReferenceValidator validator;

    public ValidateAadlTool() {
        this.validator = new AadlReferenceValidator();
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "验证 AADL 代码的语法正确性和引用完整性，返回错误、警告和自动修复结果。" +
                "每次修改 AADL 代码后都应该调用此工具检查是否还有错误。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> codeParam = new LinkedHashMap<>();
        codeParam.put("type", "string");
        codeParam.put("description", "需要验证的完整 AADL 代码（package 包代码）");
        props.put("aadl_code", codeParam);

        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("aadl_code")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String aadlCode = (String) arguments.get("aadl_code");
        if (aadlCode == null || aadlCode.trim().isEmpty()) {
            return "错误：aadl_code 参数不能为空";
        }

        try {
            AadlReferenceValidator.ValidationResult result = validator.validateSyntax(aadlCode);

            StringBuilder sb = new StringBuilder();
            sb.append("=== AADL 验证结果 ===\n");

            // 错误
            if (result.errors != null && !result.errors.isEmpty()) {
                sb.append("\n【错误】(").append(result.errors.size()).append(" 项):\n");
                for (int i = 0; i < result.errors.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(result.errors.get(i));
                    if (result.suggestions != null && i < result.suggestions.size()
                            && result.suggestions.get(i) != null) {
                        sb.append("\n     建议: ").append(result.suggestions.get(i));
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("\n【错误】: 无\n");
            }

            // 警告
            if (result.warnings != null && !result.warnings.isEmpty()) {
                sb.append("\n【警告】(").append(result.warnings.size()).append(" 项):\n");
                for (int i = 0; i < result.warnings.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(result.warnings.get(i)).append("\n");
                }
            } else {
                sb.append("【警告】: 无\n");
            }

            // 自动修复
            if (result.fixes != null && !result.fixes.isEmpty()) {
                sb.append("\n【自动修复】(").append(result.fixes.size()).append(" 项):\n");
                for (int i = 0; i < result.fixes.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(result.fixes.get(i)).append("\n");
                }
                if (result.fixedContent != null && !result.fixedContent.isEmpty()) {
                    sb.append("\n【自动修复后的代码预览】(前20行):\n");
                    String[] lines = result.fixedContent.split("\n");
                    int preview = Math.min(20, lines.length);
                    for (int i = 0; i < preview; i++) {
                        sb.append("  ").append(lines[i]).append("\n");
                    }
                    if (lines.length > 20) {
                        sb.append("  ... (共 ").append(lines.length).append(" 行)\n");
                    }
                    // 完整修复后代码（用于达到迭代上限时提取返回）
                    sb.append("\n【完整修复后代码】:\n```aadl\n").append(result.fixedContent).append("\n```\n");
                }
            }

            sb.append("\n【汇总】: ").append(result.errors != null ? result.errors.size() : 0).append(" 错误, ")
                    .append(result.warnings != null ? result.warnings.size() : 0).append(" 警告, ")
                    .append(result.fixes != null ? result.fixes.size() : 0).append(" 项自动修复\n");

            log.info("ValidateAadlTool: {} 错误, {} 警告, {} 自动修复",
                    result.errors != null ? result.errors.size() : 0,
                    result.warnings != null ? result.warnings.size() : 0,
                    result.fixes != null ? result.fixes.size() : 0);

            return sb.toString();

        } catch (Exception e) {
            log.error("ValidateAadlTool execution error: {}", e.getMessage(), e);
            return "验证工具执行异常: " + e.getMessage();
        }
    }
}
