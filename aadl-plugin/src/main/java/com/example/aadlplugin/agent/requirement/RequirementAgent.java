package com.example.aadlplugin.agent.requirement;

import com.example.aadlplugin.agent.Agent;
import com.example.aadlplugin.agent.AgentInput;
import com.example.aadlplugin.agent.AgentOutput;
import com.example.aadlplugin.client.LlmClient;
import com.example.aadlplugin.client.ModelService;
import com.example.aadlplugin.client.ModelType;
import com.example.aadlplugin.model.Requirement;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.logging.Logger;

public class RequirementAgent implements Agent<AgentInput, AgentOutput> {
    
    private static final Logger log = Logger.getLogger(RequirementAgent.class.getName());

    private static final String AGENT_NAME = "RequirementAgent";

    private final ModelService modelService;
    private final ObjectMapper objectMapper;
    private final RequirementPrompt prompt;
    private final int maxRetries;
    private final double temperature;
    private final int maxTokens;

    public RequirementAgent(ModelService modelService) {
        this(modelService, 3, 0.1, 8192);
    }

    public RequirementAgent(ModelService modelService, int maxRetries, double temperature, int maxTokens) {
        this.modelService = modelService;
        this.objectMapper = new ObjectMapper();
        this.prompt = new RequirementPrompt();
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
        log.info("RequirementAgent starting execution");
        log.info(String.format("Session ID: %s", input.getSessionId()));
        log.info(String.format("Model: %s (%s)", modelType.name(), llmClient.getModelName()));
        log.info("========================================");

        String requirementDoc = input.getContent();
        if (requirementDoc == null || requirementDoc.trim().isEmpty()) {
            log.severe("需求文档内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "需求文档内容不能为空");
        }

        log.info(String.format("需求文档长度: %d 字符", requirementDoc.length()));
        log.info(String.format("配置参数: temperature=%f, maxTokens=%d, maxRetries=%d", temperature, maxTokens, maxRetries));

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(requirementDoc, input.getRagContext());
        log.info(String.format("Prompt构建完成，长度: %d 字符", systemPrompt.length()));

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("----------------------------------------");
            log.info(String.format("第 %d/%d 次尝试", attempt, maxRetries));
            log.info(String.format("正在调用大模型... (类型: %s, 模型: %s)", modelType.name(), llmClient.getModelName()));

            long llmStartTime = System.currentTimeMillis();
            String llmResponse = llmClient.chat(systemPrompt, temperature, maxTokens);
            long llmTime = System.currentTimeMillis() - llmStartTime;

            log.info(String.format("LLM调用完成，耗时: %dms", llmTime));

            if (llmResponse == null || llmResponse.trim().isEmpty()) {
                log.warning("LLM返回空响应，准备重试");
                continue;
            }

            log.info(String.format("LLM响应长度: %d 字符", llmResponse.length()));
            log.info(String.format("LLM响应前200字符: %s", llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse));

            try {
                log.info("正在解析LLM响应...");
                List<Requirement> requirements = parseRequirements(llmResponse);

                if (requirements != null && !requirements.isEmpty()) {
                    String outputJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requirements);
                    long executionTime = System.currentTimeMillis() - startTime;

                    log.info("========================================");
                    log.info("RequirementAgent执行成功!");
                    log.info(String.format("提取需求数量: %d 条", requirements.size()));
                    log.info(String.format("总耗时: %dms", executionTime));
                    log.info("========================================");

                    for (int i = 0; i < requirements.size(); i++) {
                        Requirement req = requirements.get(i);
                        log.info(String.format("  %s: %s (优先级: %s)", req.getRequirementId(), req.getTitle(), req.getPriority()));
                    }

                    return AgentOutput.success(input.getSessionId(), outputJson, executionTime);
                }

                log.warning("解析出的需求列表为空，准备重试");

            } catch (Exception e) {
                log.warning(String.format("第%d次尝试解析LLM响应失败: %s", attempt, e.getMessage()));
                log.fine("详细错误: " + e.getMessage());
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.severe("========================================");
        log.severe("RequirementAgent执行失败!");
        log.severe(String.format("重试次数: %d 次", maxRetries));
        log.severe(String.format("总耗时: %dms", executionTime));
        log.severe("========================================");

        return AgentOutput.failure(input.getSessionId(),
                "需求条目化失败，已重试" + maxRetries + "次");
    }

    private List<Requirement> parseRequirements(String response) throws Exception {
        String jsonContent = extractJson(response);

        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            throw new IllegalArgumentException("无法从响应中提取JSON内容");
        }

        return objectMapper.readValue(jsonContent, new TypeReference<List<Requirement>>() {});
    }

    private String extractJson(String response) {
        int startIndex = response.indexOf('[');
        int endIndex = response.lastIndexOf(']');

        if (startIndex >= 0 && endIndex > startIndex) {
            return response.substring(startIndex, endIndex + 1);
        }

        startIndex = response.indexOf('{');
        endIndex = response.lastIndexOf('}');

        if (startIndex >= 0 && endIndex > startIndex) {
            return "[" + response.substring(startIndex, endIndex + 1) + "]";
        }

        return null;
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}