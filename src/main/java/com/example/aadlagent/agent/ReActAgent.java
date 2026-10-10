package com.example.aadlagent.agent;

import com.example.aadlagent.agent.tool.Tool;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.client.dto.ChatMessage;
import com.example.aadlagent.client.dto.ChatResponse;
import com.example.aadlagent.client.dto.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
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

    /** 最大 ReAct 迭代次数，防止无限循环 */
    protected static final int MAX_REACT_ITERATIONS = 6;

    protected final ModelService modelService;
    protected final ObjectMapper objectMapper;

    protected ReActAgent(ModelService modelService) {
        this.modelService = modelService;
        this.objectMapper = new ObjectMapper();
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
        for (int iteration = 1; iteration <= MAX_REACT_ITERATIONS; iteration++) {
            if (cancelled != null && cancelled.get()) {
                log.info("任务已取消，{} 停止执行", getAgentName());
                return AgentOutput.cancelled(input.getSessionId());
            }

            log.info("\n--- ReAct 迭代 {}/{} ---", iteration, MAX_REACT_ITERATIONS);

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
                            ? toolCall.getFunction().getArguments() : "{}";

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
        log.warn("达到最大迭代次数 {}，返回最后一次有效内容", MAX_REACT_ITERATIONS);
        long totalTime = System.currentTimeMillis() - startTime;

        String lastContent = findLastContent(messages);
        if (lastContent != null && !lastContent.trim().isEmpty()) {
            return buildFinalOutput(input, lastContent, totalTime);
        }

        return AgentOutput.failure(input.getSessionId(),
                "达到最大迭代次数 " + MAX_REACT_ITERATIONS + "，未能获得最终答案");
    }

    // ==================== 辅助方法 ====================

    private String executeTool(String toolName, Map<String, Object> args, List<Tool> tools) {
        for (Tool tool : tools) {
            if (tool.getName().equals(toolName)) {
                try {
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
