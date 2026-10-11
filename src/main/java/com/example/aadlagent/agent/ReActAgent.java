package com.example.aadlagent.agent;

import com.example.aadlagent.agent.tool.Tool;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.client.dto.ChatMessage;
import com.example.aadlagent.client.dto.ChatResponse;
import com.example.aadlagent.client.dto.ToolCall;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReAct Agent 抽象基类
 *
 * 实现 ReAct（Reasoning + Acting）模式：
 * Thought → Action（工具调用）→ Observation（工具结果）→ ... → Final Answer
 *
 * 子类需实现：
 * - getSystemPrompt()   系统提示词
 * - buildUserMessage()  构建初始用户消息
 * - getTools()          可用工具列表
 * - buildFinalOutput()  从最终回答构建输出
 * - getAgentName()      Agent 名称
 */
@Slf4j
public abstract class ReActAgent implements Agent<AgentInput, AgentOutput> {

    /** 最大 ReAct 迭代次数，防止无限循环（子类可覆盖） */
    protected int getMaxIterations() {
        return 6;
    }

    protected final ModelService modelService;
    protected final ObjectMapper objectMapper;

    protected ReActAgent(ModelService modelService) {
        this.modelService = modelService;
        this.objectMapper = new ObjectMapper();
        // 统一的 LLM 输出容错配置，所有子类共享
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        this.objectMapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
        this.objectMapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
        this.objectMapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
    }

    // ==================== 子类需实现的方法 ====================

    /** 系统提示词，定义 Agent 的角色、目标和行为规范 */
    protected abstract String getSystemPrompt();

    /** 根据输入构建初始用户消息 */
    protected abstract String buildUserMessage(AgentInput input);

    /** 可用工具列表（可为空，表示不使用工具） */
    protected abstract List<Tool> getTools();

    /** 根据 LLM 的最终回答构建输出结果 */
    protected abstract AgentOutput buildFinalOutput(AgentInput input, String finalContent, long totalTimeMs);

    /** 温度参数，默认 0.1 */
    protected double getTemperature() {
        return 0.1;
    }

    /** 最大 token 数，默认 16384 */
    protected int getMaxTokens() {
        return 16384;
    }

    /**
     * 知识库 domain（Qdrant collection 名称），用于 search_knowledge 工具
     * 如 "requirement", "architecture", "module", "aadl", "error_memory"
     * 默认为 "aadl"，子类按需覆盖
     */
    protected String getKnowledgeDomain() {
        return "aadl";
    }

    // ==================== ReAct 主循环 ====================

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("{} starting ReAct execution", getAgentName());
        log.info("Model: {} ({})", modelType.name(), llmClient.getModelName());
        log.info("Tools: {}", getToolNames());
        log.info("========================================");

        // 如果不支持工具调用或没有配置工具，降级为单次对话
        List<Tool> tools = getTools();
        if (!llmClient.supportsToolCalling() || tools == null || tools.isEmpty()) {
            if (!llmClient.supportsToolCalling()) {
                log.warn("模型 {} 不支持工具调用，降级为单次对话模式", modelType.name());
            } else {
                log.info("未配置工具，使用单次对话模式");
            }
            return executeFallback(input, llmClient, startTime);
        }

        // 构建工具定义列表
        List<Map<String, Object>> toolDefinitions = buildToolDefinitions(tools);

        // 初始化对话历史
        List<ChatMessage> messages = new ArrayList<>();

        // System message
        messages.add(ChatMessage.builder()
                .role("system")
                .content(getSystemPrompt())
                .build());

        // User message
        String userMessage = buildUserMessage(input);
        messages.add(ChatMessage.builder()
                .role("user")
                .content(userMessage)
                .build());

        AtomicBoolean cancelled = input.getCancelled();

        // ReAct 循环
        for (int iteration = 1; iteration <= getMaxIterations(); iteration++) {
            if (cancelled != null && cancelled.get()) {
                log.info("任务已取消，{} 停止执行", getAgentName());
                return AgentOutput.cancelled(input.getSessionId());
            }

            log.info("\n--- ReAct 迭代 {}/{} ---", iteration, getMaxIterations());

            // 调用 LLM
            ChatResponse response = llmClient.chatWithTools(
                    messages, toolDefinitions,
                    getTemperature(), getMaxTokens(), null);

            if (response == null || response.getMessage() == null) {
                log.error("LLM 返回空响应，终止 ReAct 循环");
                return AgentOutput.failure(input.getSessionId(), "LLM 返回空响应");
            }

            ChatMessage assistantMsg = response.getMessage();
            messages.add(assistantMsg);

            // 检查是否有工具调用
            List<ToolCall> toolCalls = assistantMsg.getToolCalls();

            if (toolCalls != null && !toolCalls.isEmpty()) {
                log.info("模型调用了 {} 个工具", toolCalls.size());

                for (ToolCall toolCall : toolCalls) {
                    String toolName = toolCall.getFunction() != null
                            ? toolCall.getFunction().getName() : null;
                    String argsStr = toolCall.getFunction() != null
                            ? toolCall.getFunction().getArgumentsAsString() : "{}";

                    if (toolName == null || toolName.isEmpty()) {
                        log.warn("工具调用名称为空，跳过");
                        continue;
                    }

                    log.info("  执行工具: {}", toolName);

                    // 解析参数
                    Map<String, Object> args;
                    try {
                        args = objectMapper.readValue(argsStr,
                                new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        log.warn("工具 {} 参数解析失败: {}", toolName, e.getMessage());
                        args = new HashMap<>();
                    }

                    // 执行工具
                    String observation = executeTool(toolName, args, tools);

                    // 将工具结果作为 tool role 消息加入历史
                    messages.add(ChatMessage.builder()
                            .role("tool")
                            .toolCallId(toolCall.getId())
                            .content(observation)
                            .build());

                    String obsPreview = observation.length() > 200
                            ? observation.substring(0, 200) + "..."
                            : observation;
                    log.info("  观察结果 ({} 字符): {}", observation.length(),
                            obsPreview.replace("\n", " "));
                }

                // 继续下一轮
                continue;
            }

            // 没有工具调用 → Final Answer
            String finalContent = assistantMsg.getContent();
            if (finalContent != null && !finalContent.trim().isEmpty()) {
                long totalTime = System.currentTimeMillis() - startTime;
                log.info("ReAct 完成，共 {} 轮迭代，耗时 {}ms", iteration, totalTime);
                return buildFinalOutput(input, finalContent, totalTime);
            }

            log.warn("第 {} 轮：LLM 既无工具调用也无文本内容，继续下一轮", iteration);
        }

        // 达到最大迭代次数
        log.warn("达到最大迭代次数 {}，尝试返回最后一次有效内容", getMaxIterations());
        long totalTime = System.currentTimeMillis() - startTime;

        // 1. 优先找 assistant 的文本内容
        String lastContent = findLastContent(messages);
        if (lastContent != null && !lastContent.trim().isEmpty()) {
            return buildFinalOutput(input, lastContent, totalTime);
        }

        // 2. 从 validate_aadl 工具的观察结果中提取最后一次的代码
        String lastValidatedCode = extractLastValidatedCode(messages);
        if (lastValidatedCode != null && !lastValidatedCode.trim().isEmpty()) {
            log.info("从 validate_aadl 结果中提取到最后一次生成的代码（{} 字符）", lastValidatedCode.length());
            return buildFinalOutput(input, lastValidatedCode, totalTime);
        }

        return AgentOutput.failure(input.getSessionId(),
                "达到最大迭代次数 " + getMaxIterations() + "，未能获得最终答案");
    }

    // ==================== 辅助方法 ====================

    private String executeTool(String toolName, Map<String, Object> args, List<Tool> tools) {
        for (Tool tool : tools) {
            if (tool.getName().equals(toolName)) {
                try {
                    // 自动注入知识库 domain（search_knowledge 工具专用，LLM 无需感知）
                    if ("search_knowledge".equals(toolName)) {
                        args.put("_knowledge_domain", getKnowledgeDomain());
                    }
                    return tool.execute(args);
                } catch (Exception e) {
                    log.error("工具 {} 执行异常: {}", toolName, e.getMessage(), e);
                    return "工具执行异常: " + e.getMessage();
                }
            }
        }
        return "错误：未知工具 '" + toolName + "'，可用工具: " + getToolNames();
    }

    private List<Map<String, Object>> buildToolDefinitions(List<Tool> tools) {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Tool tool : tools) {
            defs.add(tool.toToolDefinition());
        }
        return defs;
    }

    private String getToolNames() {
        List<Tool> tools = getTools();
        if (tools == null || tools.isEmpty()) return "none";
        List<String> names = new ArrayList<>();
        for (Tool tool : tools) {
            names.add(tool.getName());
        }
        return String.join(", ", names);
    }

    private String findLastContent(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole())
                    && msg.getContent() != null
                    && !msg.getContent().trim().isEmpty()) {
                return msg.getContent();
            }
        }
        return null;
    }

    /**
     * 从 validate_aadl 工具的观察结果中提取最后一次生成的 AADL 代码
     * ValidateAadlTool 会在观察结果中包含 "修正后代码" 部分
     */
    private String extractLastValidatedCode(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (!"tool".equals(msg.getRole())) continue;
            String content = msg.getContent();
            if (content == null || content.isEmpty()) continue;

            // 尝试从 "完整修复后代码" 标记中提取（ValidateAadlTool 输出的完整代码）
            int codeIdx = content.indexOf("完整修复后代码");
            if (codeIdx > 0) {
                int backtickIdx = content.indexOf("```", codeIdx);
                if (backtickIdx > 0) {
                    int codeStart = content.indexOf("\n", backtickIdx);
                    if (codeStart > 0) {
                        int codeEnd = content.indexOf("```", codeStart + 1);
                        if (codeEnd > codeStart) {
                            return content.substring(codeStart + 1, codeEnd).trim();
                        }
                    }
                }
            }

            // 再试试从 "修正后代码" 中提取（兼容旧格式）
            int fixIdx = content.indexOf("修正后代码");
            if (fixIdx > 0) {
                int backtickIdx = content.indexOf("```", fixIdx);
                if (backtickIdx > 0) {
                    int codeStart = content.indexOf("\n", backtickIdx);
                    if (codeStart > 0) {
                        int codeEnd = content.indexOf("```", codeStart + 1);
                        if (codeEnd > codeStart) {
                            return content.substring(codeStart + 1, codeEnd).trim();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 降级模式：不支持工具调用时，退化为单次对话
     */
    private AgentOutput executeFallback(AgentInput input, LlmClient llmClient, long startTime) {
        String prompt = getSystemPrompt() + "\n\n" + buildUserMessage(input);
        String response = llmClient.chat(prompt, getTemperature(), getMaxTokens());
        long totalTime = System.currentTimeMillis() - startTime;

        if (response == null || response.trim().isEmpty()) {
            return AgentOutput.failure(input.getSessionId(), "LLM 返回空响应");
        }
        return buildFinalOutput(input, response, totalTime);
    }
}
