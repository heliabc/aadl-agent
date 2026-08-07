package com.example.aadlagent.agent.aadl;

import com.example.aadlagent.agent.Agent;
import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.util.AadlReferenceValidator;
import com.example.aadlagent.util.AadlReferenceValidator.ValidationResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AadlFixerAgent implements Agent<AgentInput, AgentOutput> {

    private static final String AGENT_NAME = "AadlFixerAgent";

    private final ModelService modelService;
    private final AadlReferenceValidator validator;
    private final AadlFixerPrompt prompt;

    @Value("${agent.aadl-fixer.temperature:0.1}")
    private double temperature;

    @Value("${agent.aadl-fixer.max-tokens:16384}")
    private int maxTokens;

    public AadlFixerAgent(ModelService modelService, AadlReferenceValidator validator) {
        this.modelService = modelService;
        this.validator = validator;
        this.prompt = new AadlFixerPrompt();
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("AadlFixerAgent starting execution");
        log.info("Session ID: {}", input.getSessionId());
        log.info("Model: {} ({})", modelType.name(), llmClient.getModelName());
        log.info("========================================");

        // 第1步：清理输入的AADL代码，移除静态分析注入的各种注释标记，避免干扰LLM
        String rawAadl = input.getContent();
        String errors = input.getMetadata();

        if (rawAadl == null || rawAadl.trim().isEmpty()) {
            log.error("AADL内容为空，无法修复");
            return AgentOutput.failure(input.getSessionId(), "AADL内容不能为空");
        }

        String currentAadl = sanitizeAadlContent(rawAadl);
        log.info("输入AADL清理完成: {} → {} 字符", rawAadl.length(), currentAadl.length());

        if (errors == null || errors.trim().isEmpty()) {
            // 如果没有传入错误，先运行一次静态语法检查获取错误
            log.info("未传入错误信息，运行静态语法分析获取初始错误...");
            ValidationResult initialResult = validator.validateSyntax(currentAadl);
            // 先应用自动修复
            currentAadl = initialResult.fixedContent != null ? initialResult.fixedContent : currentAadl;
            currentAadl = sanitizeAadlContent(currentAadl);
            if (initialResult.errors.isEmpty()) {
                log.info("初始代码无错误，无需修复");
                long executionTime = System.currentTimeMillis() - startTime;
                return AgentOutput.success(input.getSessionId(), currentAadl, executionTime);
            }
            errors = formatValidationErrors(initialResult);
        }

        log.info("AADL内容长度: {} 字符", currentAadl.length());
        log.info("错误列表长度: {} 字符", errors.length());
        log.info("配置参数: temperature={}, maxTokens={}", temperature, maxTokens);

        String structuredErrors = normalizeErrors(errors);
        log.info("错误格式: {}", isJsonFormat(errors) ? "结构化JSON" : "原始文本/静态分析报告");

        // 第2步：构建Prompt并调用LLM（单次调用，不自动迭代）
        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(currentAadl, structuredErrors, input.getRagContext());
        log.info("Prompt构建完成，长度: {} 字符", systemPrompt.length());

        if (input.isCancelled()) {
            log.info("任务已取消，AadlFixerAgent停止执行");
            return AgentOutput.cancelled(input.getSessionId());
        }

        log.info("----------------------------------------");
        log.info("正在调用大模型... (类型: {}, 模型: {})", modelType.name(), llmClient.getModelName());

        long llmStartTime = System.currentTimeMillis();
        String llmResponse = llmClient.chat(systemPrompt, temperature, maxTokens);
        long llmTime = System.currentTimeMillis() - llmStartTime;

        log.info("LLM调用完成，耗时: {}ms", llmTime);

        if (input.isCancelled()) {
            log.info("任务已取消，AadlFixerAgent停止执行");
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
            String fixedAadl = extractAadlContent(llmResponse);

            // 第3步：统计修复标记、做一次语法验证（仅用于日志报告，不自动继续修复）
            int fixMarkerCount = countFixMarkers(fixedAadl);
            if (fixMarkerCount == 0) {
                log.warn("修复后的代码中未检测到任何 -- [修复] 标记，可能未按要求标注修改点");
            } else {
                log.info("修复后的代码中检测到 {} 个 -- [修复] 标记", fixMarkerCount);
            }

            // 清理后做验证，用于日志报告剩余错误数（返回给用户看，由用户决定是否继续修复）
            String cleanFixedAadl = sanitizeAadlContent(fixedAadl);
            ValidationResult validationAfter = validator.validateSyntax(cleanFixedAadl);

            long executionTime = System.currentTimeMillis() - startTime;
            int componentCount = countComponents(cleanFixedAadl);
            int connectionCount = countConnections(cleanFixedAadl);

            log.info("========================================");
            log.info("AadlFixerAgent执行完成!");
            log.info("组件数量: {} 个", componentCount);
            log.info("连接数量: {} 个", connectionCount);
            log.info("修复标记: {} 个", fixMarkerCount);
            log.info("剩余错误: {} 个", validationAfter.errors.size());
            log.info("剩余警告: {} 个", validationAfter.warnings.size());
            log.info("总耗时: {}ms", executionTime);
            log.info("========================================");

            printAadlSummary(fixedAadl);

            // 返回修复后的代码（保留LLM添加的 -- [修复] 标记）
            // 用户看到结果后如果还有错误，会再次点击修复按钮，届时前端传入本次结果作为新的输入
            return AgentOutput.success(input.getSessionId(), fixedAadl, executionTime);

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("解析LLM响应失败: {}", e.getMessage());
            log.debug("详细错误:", e);
            return AgentOutput.failure(input.getSessionId(), "解析LLM响应失败: " + e.getMessage());
        }
    }

    /**
     * 清理AADL代码中静态分析器和修复过程注入的注释标记。
     * 这些注释会干扰LLM判断，在传入LLM之前必须清理掉。
     *
     * 需要清理的内容：
     * 1. 顶部验证报告块（-- [错误]、-- [警告]、-- [自动修复] 等开头的整行）
     * 2. 行尾的 -- [自动修正]、-- [自动修复]、-- [修复]、-- [修复建议] 标记注释
     * 3. 行尾的 -- [ERROR]、-- [WARNING] 注释
     *
     * 注意：用户原始的正常AADL注释（普通 -- 注释，不包含上述标记的）会被保留。
     * LLM输出中的 -- [修复] 标记会保留在最终输出中返回给用户，
     * 但在传给验证器做语法检查之前会被清理掉。
     */
    private String sanitizeAadlContent(String aadlContent) {
        if (aadlContent == null || aadlContent.isEmpty()) {
            return aadlContent;
        }

        String[] lines = aadlContent.split("\n");
        List<String> cleanedLines = new ArrayList<>();

        // 匹配整行是验证报告注释的模式
        Pattern reportLinePattern = Pattern.compile(
                "^\\s*--\\s*\\[(错误|警告|自动修复|ERROR|WARNING|错误报告|验证报告|修复建议)\\]"
        );

        // 匹配行尾的修复/自动修正/修复建议标记注释
        Pattern trailingMarkerPattern = Pattern.compile(
                "\\s+--\\s*\\[(自动修正|自动修复|修复|修复建议|ERROR|WARNING|错误|警告)\\][^\\r\\n]*$"
        );

        for (String line : lines) {
            // 检查是否是纯报告行
            Matcher reportMatcher = reportLinePattern.matcher(line);
            if (reportMatcher.find()) {
                String beforeComment = line.substring(0, reportMatcher.start()).trim();
                if (beforeComment.isEmpty()) {
                    continue; // 整行都是报告注释，跳过
                }
            }

            // 移除行尾的标记注释
            Matcher trailingMatcher = trailingMarkerPattern.matcher(line);
            String cleaned = trailingMatcher.replaceAll("");
            cleaned = cleaned.replaceAll("\\s+$", "");

            // 如果清理后行为空且原行是纯标记注释行，跳过
            if (cleaned.trim().isEmpty()) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--")) {
                    String content = trimmed.replaceAll("^--\\s*", "");
                    if (content.matches("\\s*\\[(自动修正|自动修复|修复|修复建议|ERROR|WARNING|错误|警告|自动修复)\\][^\\[]*")) {
                        continue;
                    }
                }
            }

            cleanedLines.add(cleaned);
        }

        // 移除顶部连续空行
        while (!cleanedLines.isEmpty() && cleanedLines.get(0).trim().isEmpty()) {
            cleanedLines.remove(0);
        }

        // 移除底部连续空行
        while (!cleanedLines.isEmpty() && cleanedLines.get(cleanedLines.size() - 1).trim().isEmpty()) {
            cleanedLines.remove(cleanedLines.size() - 1);
        }

        return String.join("\n", cleanedLines);
    }

    /**
     * 将 ValidationResult 格式化为文本错误信息
     */
    private String formatValidationErrors(ValidationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("静态语法分析发现以下错误：\n\n");

        int idx = 1;
        for (String error : result.errors) {
            sb.append(idx++).append(". ").append(error).append("\n");
        }

        if (!result.warnings.isEmpty()) {
            sb.append("\n警告：\n");
            for (String warning : result.warnings) {
                sb.append(idx++).append(". ").append(warning).append("\n");
            }
        }

        if (!result.fixes.isEmpty()) {
            sb.append("\n已自动应用的修复（无需再次处理）：\n");
            for (String fix : result.fixes) {
                sb.append("- ").append(fix).append("\n");
            }
        }

        return sb.toString();
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

            Matcher pkgMatcher = Pattern.compile("^\\s*package\\s+(\\w+)\\s*").matcher(cleaned);
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

    private String fixMissingEndStatements(String aadlContent) {
        String[] lines = aadlContent.split("\n");
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
                    
                    boolean hasEnd = false;
                    for (int j = i + 1; j < lines.length; j++) {
                        String nextTrimmed = lines[j].trim();
                        if (nextTrimmed.equals("end " + componentName + ";")) {
                            hasEnd = true;
                            break;
                        }
                    }
                    
                    if (!hasEnd) {
                        lines[i] = lines[i] + "\nend " + componentName + ";";
                        fixedCount++;
                    }
                }
            }
        }
        
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

    /**
     * 统计修复代码中 -- [修复] 标记的数量。
     */
    private int countFixMarkers(String aadlContent) {
        if (aadlContent == null || aadlContent.isEmpty()) {
            return 0;
        }
        Pattern pattern = Pattern.compile("--\\s*\\[修复\\]");
        Matcher matcher = pattern.matcher(aadlContent);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private void printAadlSummary(String aadlContent) {
        String[] lines = aadlContent.split("\n");
        log.info("修复后的AADL预览（前20行）:");
        int displayLines = Math.min(lines.length, 20);
        for (int i = 0; i < displayLines; i++) {
            log.info("  {}", lines[i]);
        }
        if (lines.length > 20) {
            log.info("  ... (共 {} 行)", lines.length);
        }
    }

    private boolean isJsonFormat(String text) {
        String trimmed = text.trim();
        return trimmed.startsWith("[") || trimmed.startsWith("{");
    }

    private String normalizeErrors(String errors) {
        if (isJsonFormat(errors)) {
            return formatJsonErrors(errors);
        }
        return errors;
    }

    private String formatJsonErrors(String jsonErrors) {
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            List<Map<String, Object>> errorList = mapper.readValue(jsonErrors, 
                    new TypeReference<List<Map<String, Object>>>() {});
            
            StringBuilder formatted = new StringBuilder();
            for (int i = 0; i < errorList.size(); i++) {
                Map<String, Object> error = errorList.get(i);
                formatted.append(i + 1).append(". ")
                        .append(error.get("errorType")).append(" - ")
                        .append(error.get("message"));
                
                if (error.containsKey("lineNumber") && error.get("lineNumber") != null) {
                    formatted.append(" (行: ").append(error.get("lineNumber")).append(")");
                }
                
                if (error.containsKey("componentName") && error.get("componentName") != null) {
                    formatted.append(" [").append(error.get("componentName")).append("]");
                }
                
                if (error.containsKey("suggestion") && error.get("suggestion") != null) {
                    formatted.append("\n   建议: ").append(error.get("suggestion"));
                }
                
                formatted.append("\n");
            }
            
            return formatted.toString();
        } catch (Exception e) {
            log.warn("Failed to parse JSON errors, using raw text: {}", e.getMessage());
            return jsonErrors;
        }
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}
