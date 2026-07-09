package com.example.aadlagent.agent.requirement;

import com.example.aadlagent.agent.Agent;
import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.model.Requirement;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class RequirementAgent implements Agent<AgentInput, AgentOutput> {

    private static final String AGENT_NAME = "RequirementAgent";

    private final ModelService modelService;
    private final ObjectMapper objectMapper;
    private final RequirementPrompt prompt;

    @Value("${agent.requirement.max-retries:3}")
    private int maxRetries;

    @Value("${agent.requirement.temperature:0.1}")
    private double temperature;

    @Value("${agent.requirement.max-tokens:8192}")
    private int maxTokens;

    public RequirementAgent(ModelService modelService) {
        this.modelService = modelService;
        this.objectMapper = new ObjectMapper();
        this.prompt = new RequirementPrompt();
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("RequirementAgent starting execution");
        log.info("Session ID: {}", input.getSessionId());
        log.info("Model: {} ({})", modelType.name(), llmClient.getModelName());
        log.info("========================================");

        String requirementDoc = input.getContent();
        if (requirementDoc == null || requirementDoc.trim().isEmpty()) {
            log.error("需求文档内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "需求文档内容不能为空");
        }

        log.info("需求文档长度: {} 字符", requirementDoc.length());
        log.info("配置参数: temperature={}, maxTokens={}, maxRetries={}", temperature, maxTokens, maxRetries);

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(requirementDoc, input.getRagContext());
        log.info("Prompt构建完成，长度: {} 字符", systemPrompt.length());

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("----------------------------------------");
            log.info("第 {}/{} 次尝试", attempt, maxRetries);
            log.info("正在调用大模型... (类型: {}, 模型: {})", modelType.name(), llmClient.getModelName());

            long llmStartTime = System.currentTimeMillis();
            String llmResponse = llmClient.chat(systemPrompt, temperature, maxTokens);
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
                List<Requirement> requirements = parseRequirements(llmResponse);

                if (requirements != null && !requirements.isEmpty()) {
                    String outputJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requirements);
                    long executionTime = System.currentTimeMillis() - startTime;

                    log.info("========================================");
                    log.info("RequirementAgent执行成功!");
                    log.info("提取需求数量: {} 条", requirements.size());
                    log.info("总耗时: {}ms", executionTime);
                    log.info("========================================");

                    for (int i = 0; i < requirements.size(); i++) {
                        Requirement req = requirements.get(i);
                        log.info("  {}: {} (优先级: {})", req.getRequirementId(), req.getTitle(), req.getPriority());
                    }

                    return AgentOutput.success(input.getSessionId(), outputJson, executionTime);
                }

                log.warn("解析出的需求列表为空，准备重试");

            } catch (Exception e) {
                log.warn("第{}次尝试解析LLM响应失败: {}", attempt, e.getMessage());
                log.debug("详细错误:", e);
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.error("========================================");
        log.error("RequirementAgent执行失败!");
        log.error("重试次数: {} 次", maxRetries);
        log.error("总耗时: {}ms", executionTime);
        log.error("========================================");

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