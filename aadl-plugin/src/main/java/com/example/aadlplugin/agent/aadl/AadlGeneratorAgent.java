package com.example.aadlplugin.agent.aadl;

import com.example.aadlplugin.agent.Agent;
import com.example.aadlplugin.agent.AgentInput;
import com.example.aadlplugin.agent.AgentOutput;
import com.example.aadlplugin.client.LlmClient;
import com.example.aadlplugin.client.ModelService;
import com.example.aadlplugin.client.ModelType;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
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

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(architectureJson, modulesJson, input.getRagContext());
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