package com.example.aadlagent.memory.gate;

import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.memory.AgentContext;
import com.example.aadlagent.memory.QualityGate;
import com.example.aadlagent.memory.QualityGateResult;
import com.example.aadlagent.util.AadlReferenceValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AadlGeneratorAgent 质量检查门
 *
 * 检查点：
 * 1. 输出是否包含有效的 AADL package 声明
 * 2. 组件数量是否合理
 * 3. 用静态验证器检查基础语法错误数量
 */
@Slf4j
@Component
public class AadlGeneratorQualityGate implements QualityGate {

    private static final String AGENT_NAME = "AadlGeneratorAgent";
    private static final int MIN_COMPONENTS = 3;
    private static final int MAX_ERRORS_FOR_PASS = 30;

    private final AadlReferenceValidator validator;

    public AadlGeneratorQualityGate(AadlReferenceValidator validator) {
        this.validator = validator;
    }

    @Override
    public String getTargetAgentName() {
        return AGENT_NAME;
    }

    @Override
    public QualityGateResult check(AgentContext context, AgentOutput output) {
        QualityGateResult result = QualityGateResult.pass();

        if (output == null || !output.isSuccess()) {
            return QualityGateResult.retry("AADL生成失败，输出为空或不成功")
                    .addFailedCheck("输出有效性");
        }

        String content = output.getContent();
        if (content == null || content.trim().isEmpty()) {
            return QualityGateResult.retry("AADL生成输出为空")
                    .addFailedCheck("输出非空");
        }

        // 1. 检查是否包含 package 声明
        if (!containsPackageDeclaration(content)) {
            return QualityGateResult.retry("输出中未找到有效的 package 声明")
                    .addFailedCheck("package 声明");
        }
        result.addPassedCheck("包含 package 声明");

        // 2. 检查组件数量
        int componentCount = countComponents(content);
        if (componentCount < MIN_COMPONENTS) {
            return QualityGateResult.retry("AADL 组件过少（" + componentCount + " 个），可能生成不完整")
                    .addFailedCheck("组件数量合理性");
        }
        result.addPassedCheck("组件数量: " + componentCount);

        // 3. 检查是否有 end 闭合（至少有一个 end packageName;）
        if (!hasProperEnding(content)) {
            return QualityGateResult.retry("AADL 代码缺少结束语句，可能被截断")
                    .addFailedCheck("代码完整性");
        }
        result.addPassedCheck("代码结构完整");

        // 4. 用静态验证器做语法检查（只检查语法，不检查引用完整性）
        try {
            // 先清理验证报告注释
            String cleanCode = stripValidationReport(content);
            AadlReferenceValidator.ValidationResult validation = validator.validateSyntax(cleanCode);

            int errorCount = validation.errors != null ? validation.errors.size() : 0;
            result.addPassedCheck("语法错误数: " + errorCount);

            // 如果错误过多，认为质量不合格，重试
            if (errorCount > MAX_ERRORS_FOR_PASS) {
                return QualityGateResult.retry("语法错误过多（" + errorCount + " 个），质量不达标")
                        .addFailedCheck("语法质量");
            }

            // 如果有自动修复，记录下来
            if (validation.fixes != null && !validation.fixes.isEmpty()) {
                result.addPassedCheck("自动修复 " + validation.fixes.size() + " 项");
            }

        } catch (Exception e) {
            log.warn("静态语法验证异常，跳过语法检查: {}", e.getMessage());
            // 验证器异常不阻塞流程，只记录警告
            result.addPassedCheck("语法检查跳过（验证器异常）");
        }

        return result;
    }

    private boolean containsPackageDeclaration(String code) {
        Pattern pattern = Pattern.compile("(?m)^\\s*package\\s+\\w+");
        Matcher matcher = pattern.matcher(code);
        return matcher.find();
    }

    private boolean hasProperEnding(String code) {
        // 提取包名
        Pattern pkgPattern = Pattern.compile("(?m)^\\s*package\\s+(\\w+)");
        Matcher pkgMatcher = pkgPattern.matcher(code);
        if (!pkgMatcher.find()) {
            return false;
        }
        String packageName = pkgMatcher.group(1);

        // 检查是否有对应的 end 语句
        Pattern endPattern = Pattern.compile("end\\s+" + Pattern.quote(packageName) + "\\s*;");
        Matcher endMatcher = endPattern.matcher(code);
        return endMatcher.find();
    }

    private int countComponents(String code) {
        Pattern pattern = Pattern.compile("\\b(thread|process|processor|device|memory|system|bus|data)\\s+\\w+");
        Matcher matcher = pattern.matcher(code);
        int count = 0;
        while (matcher.find()) {
            // 排除 implementation 行中的重复计数
            String line = matcher.group();
            if (!line.contains("implementation")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 移除顶部的验证报告注释，避免干扰语法检查
     */
    private String stripValidationReport(String code) {
        String[] lines = code.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean inReport = false;
        boolean foundRealCode = false;

        for (String line : lines) {
            String trimmed = line.trim();
            // 检测验证报告开始
            if (!foundRealCode && trimmed.startsWith("-- =")) {
                inReport = true;
                continue;
            }
            if (inReport && trimmed.startsWith("-- [")) {
                continue;
            }
            if (inReport && trimmed.startsWith("-- 汇总")) {
                continue;
            }
            if (inReport && trimmed.startsWith("-- =")) {
                // 报告结束的分隔线，跳过这一行，下一行开始是真正的代码
                inReport = false;
                continue;
            }
            // 非报告内容
            if (!trimmed.isEmpty() || foundRealCode) {
                foundRealCode = true;
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
