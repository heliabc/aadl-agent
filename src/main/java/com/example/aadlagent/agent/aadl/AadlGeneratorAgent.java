package com.example.aadlagent.agent.aadl;

import com.example.aadlagent.agent.Agent;
import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.util.AadlInputParser;
import com.example.aadlagent.util.AadlReferenceValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AadlGeneratorAgent implements Agent<AgentInput, AgentOutput> {

    private static final String AGENT_NAME = "AadlGeneratorAgent";

    private final ModelService modelService;
    private final AadlGeneratorPrompt prompt;

    @Value("${agent.aadl.max-retries:3}")
    private int maxRetries;

    @Value("${agent.aadl.temperature:0.1}")
    private double temperature;

    @Value("${agent.aadl.max-tokens:16384}")
    private int maxTokens;

    public AadlGeneratorAgent(ModelService modelService) {
        this.modelService = modelService;
        this.prompt = new AadlGeneratorPrompt();
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("AadlGeneratorAgent starting execution");
        log.info("Session ID: {}", input.getSessionId());
        log.info("Model: {} ({})", modelType.name(), llmClient.getModelName());
        log.info("========================================");

        String architectureJson = input.getContent();
        String modulesJson = input.getMetadata();

        if (architectureJson == null || architectureJson.trim().isEmpty()) {
            log.error("架构树内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "架构树内容不能为空");
        }

        if (modulesJson == null || modulesJson.trim().isEmpty()) {
            log.error("模块分析内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "模块分析内容不能为空");
        }

        log.info("架构树长度: {} 字符", architectureJson.length());
        log.info("模块分析长度: {} 字符", modulesJson.length());
        log.info("配置参数: temperature={}, maxTokens={}", temperature, maxTokens);

        // 硬编码解析架构树和模块分析，生成结构化清单（替代原始 JSON 注入提示词）
        log.info("正在解析架构树和模块分析...");
        AadlInputParser inputParser = new AadlInputParser();
        AadlInputParser.ParseResult parseResult = inputParser.parse(architectureJson, modulesJson);
        log.info("解析完成，清单长度: {} 字符，组件真值表: {} 个",
                parseResult.manifestText.length(), parseResult.archComponents.size());

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(parseResult.manifestText, input.getRagContext());
        log.info("Prompt构建完成，长度: {} 字符", systemPrompt.length());

        if (input.isCancelled()) {
            log.info("任务已取消，AadlGeneratorAgent停止执行");
            return AgentOutput.cancelled(input.getSessionId());
        }

        log.info("----------------------------------------");
        log.info("正在调用大模型... (类型: {}, 模型: {})", modelType.name(), llmClient.getModelName());

        long llmStartTime = System.currentTimeMillis();
        String llmResponse = llmClient.chat(systemPrompt, temperature, maxTokens);
        long llmTime = System.currentTimeMillis() - llmStartTime;

        log.info("LLM调用完成，耗时: {}ms", llmTime);

        if (input.isCancelled()) {
            log.info("任务已取消，AadlGeneratorAgent停止执行");
            return AgentOutput.cancelled(input.getSessionId());
        }

        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("LLM返回空响应");
            return AgentOutput.failure(input.getSessionId(), "LLM返回空响应");
        }

        log.info("LLM响应长度: {} 字符", llmResponse.length());
        log.info("LLM响应前200字符: {}", llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse);

        try {
            log.info("正在解析LLM响应...");
            String aadlContent = extractAadlContent(llmResponse);

            // 引用完整性验证与自动修正（不依赖大模型）
            AadlReferenceValidator.ValidationResult validationResult =
                    validateAndFixReferences(aadlContent, parseResult);
            aadlContent = validationResult.fixedContent;

            // 将验证结果以注释形式嵌入 AADL 代码顶部
            aadlContent = embedValidationReport(aadlContent, validationResult);

            long executionTime = System.currentTimeMillis() - startTime;
            int componentCount = countComponents(aadlContent);
            int connectionCount = countConnections(aadlContent);

            log.info("========================================");
            log.info("AadlGeneratorAgent执行完成!");
            log.info("组件数量: {} 个", componentCount);
            log.info("连接数量: {} 个", connectionCount);
            log.info("总耗时: {}ms", executionTime);
            log.info("========================================");

            printAadlSummary(aadlContent);

            return AgentOutput.success(input.getSessionId(), aadlContent, executionTime);

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("解析LLM响应失败: {}", e.getMessage());
            log.debug("详细错误:", e);
            return AgentOutput.failure(input.getSessionId(), "解析LLM响应失败: " + e.getMessage());
        }
    }

    private String extractAadlContent(String response) {
        String cleaned = response.trim();

        if (cleaned.startsWith("```aadl")) {
            int start = cleaned.indexOf("```aadl") + 7;
            int end = cleaned.lastIndexOf("```");
            if (end > start) {
                cleaned = cleaned.substring(start, end).trim();
            }
        } else if (cleaned.startsWith("```")) {
            int start = cleaned.indexOf("```") + 3;
            int end = cleaned.lastIndexOf("```");
            if (end > start) {
                cleaned = cleaned.substring(start, end).trim();
            }
        }

        if (cleaned.startsWith("package")) {
            cleaned = fixMissingEndStatements(cleaned);

            java.util.regex.Matcher pkgMatcher = java.util.regex.Pattern.compile("^\\s*package\\s+(\\w+)\\s*").matcher(cleaned);
            String packageName = "System";
            if (pkgMatcher.find()) {
                packageName = pkgMatcher.group(1);
            }
            
            if (!cleaned.matches(".*end\\s+" + packageName + "\\s*;\\s*$")) {
                cleaned = cleaned.replaceAll("\\s*end\\s+\\w*\\s*;\\s*$", "").trim();
                cleaned = cleaned + "\nend " + packageName + ";\n";
            }
        }

        return cleaned;
    }

    /**
     * 引用完整性验证与自动修正。
     * 复用 AadlInputParser 解析出的组件真值表，检测并自动修正 AADL 代码中的：
     * - 悬空引用（subcomponents 引用了未声明的组件）
     * - 缺失声明（有类型声明但缺实现声明，或反之）
     * - 遗漏组件（架构树中存在但 AADL 中缺失）
     * - 幻觉组件（AADL 中声明了但架构树中不存在）
     * - 类型不匹配
     *
     * @return ValidationResult 包含修正后的代码 + errors/warnings/fixes 列表
     */
    private AadlReferenceValidator.ValidationResult validateAndFixReferences(
            String aadlContent, AadlInputParser.ParseResult parseResult) {
        log.info("========================================");
        log.info("开始引用完整性验证（基于解析器真值表）");

        AadlReferenceValidator validator = new AadlReferenceValidator();
        AadlReferenceValidator.ValidationResult validationResult =
                validator.validate(aadlContent, parseResult);

        // 记录验证结果到日志
        if (!validationResult.errors.isEmpty()) {
            log.warn("引用完整性验证发现 {} 个错误:", validationResult.errors.size());
            for (int i = 0; i < validationResult.errors.size(); i++) {
                log.warn("  [ERROR] {}", validationResult.errors.get(i));
                if (i < validationResult.suggestions.size() && validationResult.suggestions.get(i) != null
                        && !validationResult.suggestions.get(i).isEmpty()) {
                    log.warn("  [SUGGEST] {}", validationResult.suggestions.get(i));
                }
            }
        }
        if (!validationResult.warnings.isEmpty()) {
            log.info("引用完整性验证发现 {} 个警告:", validationResult.warnings.size());
            for (String warning : validationResult.warnings) {
                log.info("  [WARN] {}", warning);
            }
        }
        if (!validationResult.fixes.isEmpty()) {
            log.info("自动修正应用了 {} 项修复:", validationResult.fixes.size());
            for (String fix : validationResult.fixes) {
                log.info("  [FIX] {}", fix);
            }
        }

        if (validationResult.hasIssues()) {
            log.info("引用完整性验证完成: {} 错误, {} 警告, {} 修复",
                    validationResult.errors.size(),
                    validationResult.warnings.size(),
                    validationResult.fixes.size());
        } else {
            log.info("引用完整性验证通过，无问题");
        }
        log.info("========================================");

        return validationResult;
    }

    /**
     * 将验证结果以 AADL 注释（--）形式嵌入代码顶部。
     * 这样用户在查看生成的 AADL 文件时即可看到验证报告。
     * 每个错误下方附带对应的修复建议。
     */
    private String embedValidationReport(String aadlContent,
                                          AadlReferenceValidator.ValidationResult result) {
        StringBuilder report = new StringBuilder();

        report.append("-- =========================================================\n");
        report.append("-- AADL 模型验证报告\n");
        report.append("-- =========================================================\n");

        if (result.errors.isEmpty() && result.warnings.isEmpty() && result.fixes.isEmpty()) {
            report.append("-- 验证通过，无问题。\n");
        } else {
            // 错误 + 修复建议
            if (!result.errors.isEmpty()) {
                report.append(String.format("-- [错误] (%d 项):%n", result.errors.size()));
                for (int i = 0; i < result.errors.size(); i++) {
                    report.append(String.format("--   %d. %s%n", i + 1, result.errors.get(i)));
                    // 附带修复建议
                    if (i < result.suggestions.size() && result.suggestions.get(i) != null
                            && !result.suggestions.get(i).isEmpty()) {
                        report.append(String.format("--      修复建议: %s%n", result.suggestions.get(i)));
                    }
                }
            }

            // 警告
            if (!result.warnings.isEmpty()) {
                report.append(String.format("-- [警告] (%d 项):%n", result.warnings.size()));
                for (int i = 0; i < result.warnings.size(); i++) {
                    report.append(String.format("--   %d. %s%n", i + 1, result.warnings.get(i)));
                }
            }

            // 自动修复
            if (!result.fixes.isEmpty()) {
                report.append(String.format("-- [自动修复] (%d 项):%n", result.fixes.size()));
                for (int i = 0; i < result.fixes.size(); i++) {
                    report.append(String.format("--   %d. %s%n", i + 1, result.fixes.get(i)));
                }
            }

            report.append(String.format("-- 汇总: %d 错误, %d 警告, %d 修复%n",
                    result.errors.size(), result.warnings.size(), result.fixes.size()));
        }

        report.append("-- =========================================================\n\n");

        return report.toString() + aadlContent;
    }

    private String fixMissingEndStatements(String aadlContent) {
        log.info("========================================");
        log.info("开始修复缺失的 end 语句");
        
        String[] lines = aadlContent.split("\n");
        log.info("总行数: {}", lines.length);
        
        int fixedCount = 0;
        
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            
            if (trimmed.startsWith("thread ") || trimmed.startsWith("process ") || 
                trimmed.startsWith("system ") || trimmed.startsWith("processor ") ||
                trimmed.startsWith("memory ") || trimmed.startsWith("device ") ||
                trimmed.startsWith("bus ") || trimmed.startsWith("data ") ||
                trimmed.startsWith("subprogram ")) {
                
                if (trimmed.contains("implementation")) {
                    continue;
                }
                
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2) {
                    String componentName = parts[1];
                    
                    log.info("检测到组件声明: {} {}", parts[0], componentName);
                    
                    boolean hasEnd = false;
                    for (int j = i + 1; j < lines.length; j++) {
                        String nextTrimmed = lines[j].trim();
                        if (nextTrimmed.equals("end " + componentName + ";")) {
                            hasEnd = true;
                            log.info("  找到对应的 end 语句，无需修复");
                            break;
                        }
                    }
                    
                    if (!hasEnd) {
                        log.info("  未找到对应的 end 语句，添加: end {};", componentName);
                        lines[i] = lines[i] + "\nend " + componentName + ";";
                        fixedCount++;
                    }
                }
            }
        }
        
        log.info("修复完成，共添加 {} 个 end 语句", fixedCount);
        log.info("========================================");
        
        return String.join("\n", lines).trim();
    }

    private int countComponents(String aadlContent) {
        Pattern pattern = Pattern.compile("\\b(thread|process|processor|device|memory|system|bus)\\b");
        Matcher matcher = pattern.matcher(aadlContent);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int countConnections(String aadlContent) {
        Pattern pattern = Pattern.compile("connections");
        Matcher matcher = pattern.matcher(aadlContent);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private void printAadlSummary(String aadlContent) {
        String[] lines = aadlContent.split("\n");
        log.info("AADL文件预览（前20行）:");
        int displayLines = Math.min(lines.length, 20);
        for (int i = 0; i < displayLines; i++) {
            log.info("  {}", lines[i]);
        }
        if (lines.length > 20) {
            log.info("  ... (共 {} 行)", lines.length);
        }
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}