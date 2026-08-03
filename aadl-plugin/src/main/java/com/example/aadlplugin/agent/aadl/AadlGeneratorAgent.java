package com.example.aadlplugin.agent.aadl;

import com.example.aadlplugin.agent.Agent;
import com.example.aadlplugin.agent.AgentInput;
import com.example.aadlplugin.agent.AgentOutput;
import com.example.aadlplugin.client.LlmClient;
import com.example.aadlplugin.client.ModelService;
import com.example.aadlplugin.client.ModelType;
import com.example.aadlplugin.util.AadlInputParser;
import com.example.aadlplugin.util.AadlReferenceValidator;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AadlGeneratorAgent implements Agent<AgentInput, AgentOutput> {
    
    private static final Logger log = Logger.getLogger(AadlGeneratorAgent.class.getName());

    private static final String AGENT_NAME = "AadlGeneratorAgent";

    private final ModelService modelService;
    private final AadlGeneratorPrompt prompt;
    private final int maxRetries;
    private final double temperature;
    private final int maxTokens;

    public AadlGeneratorAgent(ModelService modelService) {
        this(modelService, 3, 0.1, 16384);
    }

    public AadlGeneratorAgent(ModelService modelService, int maxRetries, double temperature, int maxTokens) {
        this.modelService = modelService;
        this.prompt = new AadlGeneratorPrompt();
        this.maxRetries = maxRetries;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("AadlGeneratorAgent starting execution");
        log.info(String.format("Session ID: %s", input.getSessionId()));
        log.info(String.format("Model: %s (%s)", modelType.name(), llmClient.getModelName()));
        log.info("========================================");

        String architectureJson = input.getContent();
        String modulesJson = input.getMetadata();

        if (architectureJson == null || architectureJson.trim().isEmpty()) {
            log.severe("架构树内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "架构树内容不能为空");
        }

        if (modulesJson == null || modulesJson.trim().isEmpty()) {
            log.severe("模块分析内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "模块分析内容不能为空");
        }

        log.info(String.format("架构树长度: %d 字符", architectureJson.length()));
        log.info(String.format("模块分析长度: %d 字符", modulesJson.length()));
        log.info(String.format("配置参数: temperature=%f, maxTokens=%d", temperature, maxTokens));

        // 硬编码解析架构树和模块分析，生成结构化清单（替代原始 JSON 注入提示词）
        log.info("正在解析架构树和模块分析...");
        AadlInputParser inputParser = new AadlInputParser();
        AadlInputParser.ParseResult parseResult = inputParser.parse(architectureJson, modulesJson);
        log.info(String.format("解析完成，清单长度: %d 字符，组件真值表: %d 个",
                parseResult.manifestText.length(), parseResult.archComponents.size()));

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(parseResult.manifestText, input.getRagContext());
        log.info(String.format("Prompt构建完成，长度: %d 字符", systemPrompt.length()));

        log.info("----------------------------------------");
        log.info(String.format("正在调用大模型... (类型: %s, 模型: %s)", modelType.name(), llmClient.getModelName()));

        long llmStartTime = System.currentTimeMillis();
        String llmResponse = llmClient.chat(systemPrompt, temperature, maxTokens);
        long llmTime = System.currentTimeMillis() - llmStartTime;

        log.info(String.format("LLM调用完成，耗时: %dms", llmTime));

        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.severe("LLM返回空响应");
            return AgentOutput.failure(input.getSessionId(), "LLM返回空响应");
        }

        log.info(String.format("LLM响应长度: %d 字符", llmResponse.length()));
        log.info(String.format("LLM响应前200字符: %s", llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse));

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
            log.info(String.format("组件数量: %d 个", componentCount));
            log.info(String.format("连接数量: %d 个", connectionCount));
            log.info(String.format("总耗时: %dms", executionTime));
            log.info("========================================");

            printAadlSummary(aadlContent);

            return AgentOutput.success(input.getSessionId(), aadlContent, executionTime);

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.severe(String.format("解析LLM响应失败: %s", e.getMessage()));
            log.fine("详细错误: " + e.getMessage());
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
            log.warning(String.format("引用完整性验证发现 %d 个错误:", validationResult.errors.size()));
            for (String error : validationResult.errors) {
                log.warning(String.format("  [ERROR] %s", error));
            }
        }
        if (!validationResult.warnings.isEmpty()) {
            log.info(String.format("引用完整性验证发现 %d 个警告:", validationResult.warnings.size()));
            for (String warning : validationResult.warnings) {
                log.info(String.format("  [WARN] %s", warning));
            }
        }
        if (!validationResult.fixes.isEmpty()) {
            log.info(String.format("自动修正应用了 %d 项修复:", validationResult.fixes.size()));
            for (String fix : validationResult.fixes) {
                log.info(String.format("  [FIX] %s", fix));
            }
        }

        if (validationResult.hasIssues()) {
            log.info(String.format("引用完整性验证完成: %d 错误, %d 警告, %d 修复",
                    validationResult.errors.size(),
                    validationResult.warnings.size(),
                    validationResult.fixes.size()));
        } else {
            log.info("引用完整性验证通过，无问题");
        }
        log.info("========================================");

        return validationResult;
    }

    /**
     * 将验证结果以 AADL 注释（--）形式嵌入代码顶部。
     * 这样用户在查看生成的 AADL 文件时即可看到验证报告。
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
            // 错误
            if (!result.errors.isEmpty()) {
                report.append(String.format("-- [错误] (%d 项):%n", result.errors.size()));
                for (int i = 0; i < result.errors.size(); i++) {
                    report.append(String.format("--   %d. %s%n", i + 1, result.errors.get(i)));
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
        log.info(String.format("总行数: %d", lines.length));

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

                    log.info(String.format("检测到组件声明: %s %s", parts[0], componentName));

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
                        log.info(String.format("  未找到对应的 end 语句，添加: end %s;", componentName));
                        lines[i] = lines[i] + "\nend " + componentName + ";";
                        fixedCount++;
                    }
                }
            }
        }

        log.info(String.format("修复完成，共添加 %d 个 end 语句", fixedCount));
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
            log.info(String.format("  %s", lines[i]));
        }
        if (lines.length > 20) {
            log.info(String.format("  ... (共 %d 行)", lines.length));
        }
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}