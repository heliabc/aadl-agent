package com.example.aadlagent.agent.aadl;

import com.example.aadlagent.agent.Agent;
import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
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
        log.info("配置参数: temperature={}, maxTokens={}, maxRetries={}", temperature, maxTokens, maxRetries);

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(architectureJson, modulesJson, input.getRagContext());
        log.info("Prompt构建完成，长度: {} 字符", systemPrompt.length());

        List<String> previousErrors = new ArrayList<>();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("----------------------------------------");
            log.info("第 {}/{} 次尝试", attempt, maxRetries);
            log.info("正在调用大模型... (类型: {}, 模型: {})", modelType.name(), llmClient.getModelName());

            String promptWithFeedback = systemPrompt;
            if (!previousErrors.isEmpty()) {
                StringBuilder feedbackBuilder = new StringBuilder();
                feedbackBuilder.append("\n\n【上一次生成错误反馈】\n");
                feedbackBuilder.append("请修复以下AADL语法问题：\n");
                for (int i = 0; i < previousErrors.size(); i++) {
                    feedbackBuilder.append(String.format("%d. %s\n", i + 1, previousErrors.get(i)));
                }
                feedbackBuilder.append("\n请基于以上反馈重新生成完整的AADL模型。");
                promptWithFeedback = systemPrompt + feedbackBuilder.toString();
                log.info("已添加错误反馈，Prompt长度: {} 字符", promptWithFeedback.length());
            }

            long llmStartTime = System.currentTimeMillis();
            String llmResponse = llmClient.chat(promptWithFeedback, temperature, maxTokens);
            long llmTime = System.currentTimeMillis() - llmStartTime;

            log.info("LLM调用完成，耗时: {}ms", llmTime);

            if (llmResponse == null || llmResponse.trim().isEmpty()) {
                log.warn("LLM返回空响应，准备重试");
                continue;
            }

            log.info("LLM响应长度: {} 字符", llmResponse.length());
            log.info("LLM响应前200字符: {}", llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse);

            try {
                log.info("正在解析LLM响应...");
                String aadlContent = extractAadlContent(llmResponse);

                List<String> validationErrors = validateAadl(aadlContent);

                if (validationErrors.isEmpty()) {
                    long executionTime = System.currentTimeMillis() - startTime;
                    int componentCount = countComponents(aadlContent);
                    int connectionCount = countConnections(aadlContent);

                    log.info("========================================");
                    log.info("AadlGeneratorAgent执行成功!");
                    log.info("组件数量: {} 个", componentCount);
                    log.info("连接数量: {} 个", connectionCount);
                    log.info("总耗时: {}ms", executionTime);
                    log.info("========================================");

                    printAadlSummary(aadlContent);

                    return AgentOutput.success(input.getSessionId(), aadlContent, executionTime);
                }

                log.warn("AADL内容验证失败，检测到 {} 个问题，准备重试", validationErrors.size());
                for (String error : validationErrors) {
                    log.warn("  - {}", error);
                }
                previousErrors = validationErrors;

            } catch (Exception e) {
                log.warn("第{}次尝试解析LLM响应失败: {}", attempt, e.getMessage());
                log.debug("详细错误:", e);
                previousErrors = List.of("解析LLM响应失败: " + e.getMessage());
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.error("========================================");
        log.error("AadlGeneratorAgent执行失败!");
        log.error("重试次数: {} 次", maxRetries);
        log.error("总耗时: {}ms", executionTime);
        log.error("========================================");

        return AgentOutput.failure(input.getSessionId(),
                "AADL模型生成失败，已重试" + maxRetries + "次");
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

    private List<String> validateAadl(String aadlContent) {
        List<String> errors = new ArrayList<>();

        if (aadlContent == null || aadlContent.trim().isEmpty()) {
            errors.add("AADL内容为空");
            return errors;
        }

        String trimmed = aadlContent.trim();
        String[] lines = aadlContent.split("\n");

        if (!trimmed.startsWith("package")) {
            errors.add("AADL内容必须以 'package' 关键字开头");
        }

        java.util.regex.Matcher pkgMatcher = java.util.regex.Pattern.compile("^\\s*package\\s+(\\w+)").matcher(trimmed);
        String packageName = null;
        if (pkgMatcher.find()) {
            packageName = pkgMatcher.group(1);
        }

        if (!trimmed.matches(".*end\\s+(\\w+)\\s*;\\s*$")) {
            errors.add("AADL内容必须以 'end packageName;' 结尾");
        } else if (packageName != null && !trimmed.matches(".*end\\s+" + packageName + "\\s*;\\s*$")) {
            errors.add("package名称与结尾的end语句不匹配，期望: 'end " + packageName + ";'");
        }

        List<String> missingEnds = findMissingEndStatements(aadlContent);
        if (!missingEnds.isEmpty()) {
            for (String missing : missingEnds) {
                errors.add("缺少组件结束语句 'end " + missing + ";'");
            }
        }

        List<String> duplicateEnds = findDuplicateEndStatements(aadlContent);
        if (!duplicateEnds.isEmpty()) {
            for (String duplicate : duplicateEnds) {
                errors.add("存在重复的结束语句 'end " + duplicate + ";'");
            }
        }

        List<String> missingSemicolons = findMissingSemicolons(aadlContent);
        if (!missingSemicolons.isEmpty()) {
            for (String line : missingSemicolons) {
                errors.add("缺少分号结尾: " + line);
            }
        }

        List<String> invalidConnections = findInvalidConnections(aadlContent);
        if (!invalidConnections.isEmpty()) {
            for (String conn : invalidConnections) {
                errors.add("连接语法不正确: " + conn);
            }
        }

        return errors;
    }

    private List<String> findMissingEndStatements(String aadlContent) {
        List<String> missingEnds = new ArrayList<>();
        String[] lines = aadlContent.split("\n");

        Pattern componentPattern = Pattern.compile("^(\\s*)(thread|process|system|processor|memory|device|bus|data|subprogram)\\s+(\\w+)");
        Stack<String> componentStack = new Stack<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("end ") && line.endsWith(";")) {
                String endName = line.substring(4, line.length() - 1).trim();
                if (!componentStack.isEmpty() && componentStack.peek().equals(endName)) {
                    componentStack.pop();
                } else {
                    if (!componentStack.isEmpty()) {
                        missingEnds.add(componentStack.peek());
                    }
                }
                continue;
            }

            Matcher matcher = componentPattern.matcher(line);
            if (matcher.find() && !line.contains("implementation")) {
                String componentName = matcher.group(3);
                componentStack.push(componentName);
            }
        }

        while (!componentStack.isEmpty()) {
            missingEnds.add(componentStack.pop());
        }

        return missingEnds;
    }

    private List<String> findDuplicateEndStatements(String aadlContent) {
        List<String> duplicates = new ArrayList<>();
        String[] lines = aadlContent.split("\n");
        java.util.Map<String, Integer> endCount = new java.util.HashMap<>();

        Pattern endPattern = Pattern.compile("end\\s+(\\w+)\\s*;");

        for (String line : lines) {
            Matcher matcher = endPattern.matcher(line.trim());
            if (matcher.find()) {
                String name = matcher.group(1);
                endCount.put(name, endCount.getOrDefault(name, 0) + 1);
            }
        }

        for (java.util.Map.Entry<String, Integer> entry : endCount.entrySet()) {
            if (entry.getValue() > 1) {
                duplicates.add(entry.getKey());
            }
        }

        return duplicates;
    }

    private List<String> findMissingSemicolons(String aadlContent) {
        List<String> missing = new ArrayList<>();
        String[] lines = aadlContent.split("\n");

        Pattern statementPattern = Pattern.compile("^(\\s*)(thread|process|system|processor|memory|device|bus|data|subprogram|end|connection|port|property)\\s+");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }

            Matcher matcher = statementPattern.matcher(line);
            if (matcher.find()) {
                String keyword = matcher.group(2);
                if (!line.contains("implementation") && !line.contains("(") && !line.contains("{") && !line.endsWith(";")) {
                    missing.add("第" + (i + 1) + "行: " + line);
                }
            }
        }

        return missing;
    }

    private List<String> findInvalidConnections(String aadlContent) {
        List<String> invalid = new ArrayList<>();
        String[] lines = aadlContent.split("\n");

        Pattern connectionPattern = Pattern.compile("connection\\s+(\\w+)\\s+:\\s+(\\w+)");
        Pattern portPattern = Pattern.compile("port\\s+(\\w+)\\s+:\\s+(\\w+)");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("connection")) {
                Matcher matcher = connectionPattern.matcher(line);
                if (!matcher.find()) {
                    invalid.add("第" + (i + 1) + "行: " + line);
                }
            }
        }

        return invalid;
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