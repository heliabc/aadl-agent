package com.example.aadlagent.agent.aadl;

import com.example.aadlagent.agent.Agent;
import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.memory.ErrorMemoryService;
import com.example.aadlagent.util.AadlReferenceValidator;
import com.example.aadlagent.util.AadlReferenceValidator.ValidationResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
    private final ErrorMemoryService errorMemoryService;

    @Value("${agent.aadl-fixer.temperature:0.1}")
    private double temperature;

    @Value("${agent.aadl-fixer.max-tokens:16384}")
    private int maxTokens;

    /** 是否启用错误记忆自动归档 */
    @Value("${agent.aadl-fixer.auto-archive-errors:true}")
    private boolean autoArchiveErrors;

    public AadlFixerAgent(ModelService modelService, AadlReferenceValidator validator,
                          ErrorMemoryService errorMemoryService) {
        this.modelService = modelService;
        this.validator = validator;
        this.prompt = new AadlFixerPrompt();
        this.errorMemoryService = errorMemoryService;
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();
        log.info("[DEBUG AadlFixerAgent.execute] 开始执行，sessionId: {}", input.getSessionId());

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("AadlFixerAgent starting execution");
        log.info("Session ID: {}", input.getSessionId());
        log.info("Model: {} ({})", modelType.name(), llmClient.getModelName());
        log.info("========================================");

        // 第1步：清理输入的AADL代码，先移除静态分析标记，再彻底删除所有注释，避免干扰LLM
        String rawAadl = input.getContent();
        String errors = input.getMetadata();

        if (rawAadl == null || rawAadl.trim().isEmpty()) {
            log.error("AADL内容为空，无法修复");
            return AgentOutput.failure(input.getSessionId(), "AADL内容不能为空");
        }

        String currentAadl = stripAllComments(sanitizeAadlContent(rawAadl));
        log.info("输入AADL清理完成（已删除所有注释）: {} → {} 字符", rawAadl.length(), currentAadl.length());

        if (errors == null || errors.trim().isEmpty()) {
            // 如果没有传入错误，先运行一次静态语法检查获取错误
            log.info("未传入错误信息，运行静态语法分析获取初始错误...");
            ValidationResult initialResult = validator.validateSyntax(currentAadl);
            // 先应用自动修复（fixedContent 已是纯净代码，可直接使用）
            if (initialResult.fixedContent != null) {
                currentAadl = initialResult.fixedContent;
            }
            if (initialResult.errors.isEmpty()) {
                log.info("初始代码无错误，无需修复");
                long executionTime = System.currentTimeMillis() - startTime;
                return AgentOutput.success(input.getSessionId(), currentAadl, executionTime);
            }
            errors = formatValidationErrors(initialResult);
        }

        log.info("AADL内容长度: {} 字符", currentAadl.length());
        log.info("错误/修复建议长度: {} 字符", errors.length());
        log.info("配置参数: temperature={}, maxTokens={}", temperature, maxTokens);

        String structuredErrors = normalizeErrors(errors);
        log.info("输入格式: {}", isJsonFormat(errors) ? "结构化JSON" : "文本格式（错误或修复建议）");

        // 修复前的验证结果（用于错误记忆归档对比）
        ValidationResult beforeFixResult = validator.validateSyntax(currentAadl);
        log.info("修复前错误数: {} 个", beforeFixResult.errors.size());

        // 第2步：构建Prompt并调用LLM（单次调用，不自动迭代）
        // 注意：传给 LLM 的 AADL 代码必须是无注释的纯净版本
        log.info("正在构建Prompt...");

        // 从错误记忆库中检索相似错误的历史修复经验
        String errorMemoryContext = "";
        if (errorMemoryService != null && autoArchiveErrors) {
            try {
                List<com.example.aadlagent.rag.model.ErrorCorrection> similarFixes =
                        errorMemoryService.retrieveSimilarFixes(structuredErrors, 3);
                if (!similarFixes.isEmpty()) {
                    errorMemoryContext = errorMemoryService.formatForPrompt(similarFixes);
                    log.info("从错误记忆库召回 {} 条相似错误修复经验", similarFixes.size());
                }
            } catch (Exception e) {
                log.warn("错误记忆检索失败: {}", e.getMessage());
            }
        }

        // 合并 RAG 上下文和错误记忆上下文
        String combinedRagContext = input.getRagContext();
        if (errorMemoryContext != null && !errorMemoryContext.isEmpty()) {
            if (combinedRagContext != null && !combinedRagContext.isEmpty()) {
                combinedRagContext = combinedRagContext + "\n\n" + errorMemoryContext;
            } else {
                combinedRagContext = errorMemoryContext;
            }
        }

        String systemPrompt = prompt.buildPrompt(currentAadl, structuredErrors, combinedRagContext);
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

            // 自动归档被修复的错误到长期记忆库
            if (autoArchiveErrors && errorMemoryService != null) {
                try {
                    int archived = errorMemoryService.archiveErrorFixes(
                            currentAadl, cleanFixedAadl, beforeFixResult, validationAfter);
                    if (archived > 0) {
                        log.info("已归档 {} 条新的错误修正记录到长期记忆", archived);
                    }
                } catch (Exception e) {
                    log.warn("错误记忆归档失败: {}", e.getMessage());
                    // 归档失败不影响主流程
                }
            }

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
     * 彻底删除 AADL 代码中的所有注释（整行 -- 注释 + 行尾 -- 注释）。
     * 用于迭代修复时，确保每次传入 LLM 的都是纯净的代码，避免注释干扰。
     *
     * 注意：
     * - 保留 annex 块（{** ... **}）内部的内容
     * - 保留字符串字面量中的 "--"
     * - 仅删除真正的 AADL 注释
     */
    private String stripAllComments(String aadlContent) {
        if (aadlContent == null || aadlContent.isEmpty()) {
            return aadlContent;
        }

        // 先保护 annex 块（EMV2 等 {** ... **}）
        List<String> annexBlocks = new ArrayList<>();
        String protectedContent = aadlContent;
        Pattern annexPattern = Pattern.compile("\\{\\*\\*[\\s\\S]*?\\*\\*\\}");
        Matcher annexMatcher = annexPattern.matcher(aadlContent);
        while (annexMatcher.find()) {
            annexBlocks.add(annexMatcher.group());
        }
        // 用占位符替换
        for (int i = 0; i < annexBlocks.size(); i++) {
            protectedContent = protectedContent.replace(annexBlocks.get(i), "@@ANNEX_" + i + "@@");
        }

        // 逐行处理
        String[] lines = protectedContent.split("\n", -1);
        List<String> cleanedLines = new ArrayList<>();

        for (String line : lines) {
            // 查找行中第一个真正的注释起始位置 "--"
            // 注意：字符串字面量中的 "--" 不应该被当作注释
            // 简单处理：找第一个 "--"，且前面不是引号内的
            int commentPos = findCommentPosition(line);
            if (commentPos >= 0) {
                String beforeComment = line.substring(0, commentPos).trim();
                if (beforeComment.isEmpty()) {
                    // 整行都是注释，跳过
                    continue;
                } else {
                    // 保留代码部分，去掉注释
                    cleanedLines.add(beforeComment);
                }
            } else {
                // 没有注释，整行保留
                cleanedLines.add(line);
            }
        }

        String result = String.join("\n", cleanedLines);

        // 恢复 annex 块
        for (int i = 0; i < annexBlocks.size(); i++) {
            result = result.replace("@@ANNEX_" + i + "@@", annexBlocks.get(i));
        }

        // 移除顶部连续空行
        while (!cleanedLines.isEmpty() && cleanedLines.get(0).trim().isEmpty()) {
            cleanedLines.remove(0);
        }

        return result;
    }

    /**
     * 查找一行中 AADL 注释 "--" 的起始位置。
     * 会跳过字符串字面量中的 "--"。
     */
    private int findCommentPosition(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '-' && line.charAt(i + 1) == '-') {
                return i;
            }
        }
        return -1;
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
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
                    .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                    .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
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

    // ==================== 消融实验支持 ====================

    /**
     * 消融实验修复方法
     *
     * 修复流水线（模块按顺序执行，每个模块可独立消融 = 输入透传/传空）：
     *
     *  rawAadl + rawErrors
     *       │
     *       ▼
     *  [模块1] 静态语法检测（staticAnalysis）
     *    输入：原始代码 + 用户错误信息
     *    输出：自动修复后代码 + 检测到的错误列表
     *    消融：代码原样返回，错误列表用用户输入（可能为空）
     *       │
     *       ▼
     *  [模块2] 错误解析 Agent（errorParser）
     *    输入：原始错误文本
     *    输出：结构化错误 JSON
     *    消融：错误文本原样透传，不做解析
     *       │
     *       ▼
     *  [模块3] RAG 检索（rag）
     *    输入：错误 + 代码
     *    输出：检索到的参考知识
     *    消融：知识输出为空（null）
     *       │
     *       ▼
     *  [模块4] Prompt 构建（prompt）
     *    输入：代码 + 错误 + RAG 知识
     *    输出：完整 prompt
     *    消融：使用极简 prompt
     *       │
     *       ▼
     *  [模块5] Fixer Agent / LLM（fixer）
     *    输入：prompt
     *    输出：修复后代码
     *    消融：（一般不消融，保留接口）
     *
     * @param rawAadl 原始 AADL 代码
     * @param rawErrors 用户提供的错误/建议（可为空）
     * @param modelType 模型类型
     * @param config 消融配置
     * @return 修复后的代码
     */
    public String fixForAblation(String rawAadl, String rawErrors,
                                  ModelType modelType,
                                  com.example.aadlagent.ablation.AblationConfig config) {
        long startTime = System.currentTimeMillis();
        LlmClient llmClient = modelService.getClient(modelType);

        // ===== 输入清理（始终做，不算消融模块） =====
        String code = stripAllComments(sanitizeAadlContent(rawAadl));
        String errors = rawErrors;

        // ===== 模块5：静态语法分析（生成后/修复前都做） =====
        // 作用：检测错误 + 自动修复低风险问题 + 生成错误列表
        // 消融：不做检测和修复，代码原样返回，错误用用户输入（可能为空）
        if (config.staticAnalysis) {
            ValidationResult staticResult = validator.validateSyntax(code);
            if (staticResult.fixedContent != null) {
                code = staticResult.fixedContent;
            }
            // 如果用户没给错误信息，用静态检测的结果；如果用户给了，保留用户的
            if (errors == null || errors.trim().isEmpty()) {
                if (!staticResult.errors.isEmpty()) {
                    errors = formatValidationErrors(staticResult);
                }
            }
        }
        // 消融 staticAnalysis：code 不变，errors 用用户原始输入（可能为 null/空）

        // ===== 模块6：Fixer Agent =====
        // Fixer 内部包含：错误解析 + RAG + Prompt + LLM 调用
        // 消融 fixerAgent：直接返回代码，不做 LLM 修复
        if (!config.fixerAgent) {
            log.warn("[消融实验 {}] fixerAgent 被消融，直接返回静态分析后代码", config.getLabel());
            return code;
        }

        // --- Fixer 内部：RAG（外部传入，这里只判断开关） ---
        // 见重载方法

        // --- Fixer 内部：Prompt 构建 ---
        // 完整 Prompt 或极简 Prompt
        String systemPrompt;
        if (config.prompt) {
            systemPrompt = prompt.buildPrompt(code, errors, null);
        } else {
            systemPrompt = prompt.buildMinimalPrompt(code, errors);
        }

        String llmResponse = llmClient.chat(systemPrompt, temperature, maxTokens);

        // 解析结果
        try {
            String fixedAadl = extractAadlContent(llmResponse);
            long time = System.currentTimeMillis() - startTime;
            log.info("[消融实验 {}] 修复完成，耗时 {}ms", config.getLabel(), time);
            return fixedAadl;
        } catch (Exception e) {
            log.error("[消融实验 {}] 解析失败: {}", config.getLabel(), e.getMessage());
            return code;
        }
    }

    /**
     * 带 RAG 上下文的消融修复（RAG 在外部检索好后传入）
     *
     * 消融说明：
     * - staticAnalysis=false: 跳过静态语法分析，代码原样，错误用原始输入
     * - fixerAgent=false: 跳过 Fixer Agent，直接返回代码
     * - rag=false: 不使用 RAG 知识（即使传入了也忽略）
     * - prompt=false: 使用极简 prompt 而不是完整 prompt
     */
    public String fixForAblation(String rawAadl, String rawErrors, String ragContext,
                                  ModelType modelType,
                                  com.example.aadlagent.ablation.AblationConfig config) {
        // 只有 rag 启用时，才使用传入的 ragContext；否则忽略
        if (!config.rag) {
            ragContext = null;
        }

        long startTime = System.currentTimeMillis();
        LlmClient llmClient = modelService.getClient(modelType);

        String code = stripAllComments(sanitizeAadlContent(rawAadl));
        String errors = rawErrors;

        // 模块5：静态语法分析
        if (config.staticAnalysis) {
            ValidationResult staticResult = validator.validateSyntax(code);
            if (staticResult.fixedContent != null) {
                code = staticResult.fixedContent;
            }
            if (errors == null || errors.trim().isEmpty()) {
                if (!staticResult.errors.isEmpty()) {
                    errors = formatValidationErrors(staticResult);
                }
            }
        }

        // 模块6：Fixer Agent
        if (!config.fixerAgent) {
            return code;
        }

        // Prompt（完整 or 极简）
        String systemPrompt;
        if (config.prompt) {
            systemPrompt = prompt.buildPrompt(code, errors, ragContext);
        } else {
            systemPrompt = prompt.buildMinimalPrompt(code, errors);
        }

        String llmResponse = llmClient.chat(systemPrompt, temperature, maxTokens);

        try {
            String fixedAadl = extractAadlContent(llmResponse);
            long time = System.currentTimeMillis() - startTime;
            log.info("[消融实验 {}] 修复完成，耗时 {}ms", config.getLabel(), time);
            return fixedAadl;
        } catch (Exception e) {
            log.error("[消融实验 {}] 解析失败: {}", config.getLabel(), e.getMessage());
            return code;
        }
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}
