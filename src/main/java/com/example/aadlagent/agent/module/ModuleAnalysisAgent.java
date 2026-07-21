package com.example.aadlagent.agent.module;

import com.example.aadlagent.agent.Agent;
import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.model.ModuleAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ModuleAnalysisAgent implements Agent<AgentInput, AgentOutput> {

    private static final String AGENT_NAME = "ModuleAnalysisAgent";

    private final ModelService modelService;
    private final ObjectMapper objectMapper;
    private final ModuleAnalysisPrompt prompt;

    @Value("${agent.module.max-retries:3}")
    private int maxRetries;

    @Value("${agent.module.temperature:0.1}")
    private double temperature;

    @Value("${agent.module.max-tokens:8192}")
    private int maxTokens;

    public ModuleAnalysisAgent(ModelService modelService) {
        this.modelService = modelService;
        this.objectMapper = new ObjectMapper();
        this.prompt = new ModuleAnalysisPrompt();
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("ModuleAnalysisAgent starting execution");
        log.info("Session ID: {}", input.getSessionId());
        log.info("Model: {} ({})", modelType.name(), llmClient.getModelName());
        log.info("========================================");

        String requirementsJson = input.getContent();
        String architectureJson = input.getMetadata();

        if (requirementsJson == null || requirementsJson.trim().isEmpty()) {
            log.error("需求列表内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "需求列表内容不能为空");
        }

        if (architectureJson == null || architectureJson.trim().isEmpty()) {
            log.error("架构树内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "架构树内容不能为空");
        }

        log.info("需求列表长度: {} 字符", requirementsJson.length());
        log.info("架构树长度: {} 字符", architectureJson.length());
        log.info("配置参数: temperature={}, maxTokens={}, maxRetries={}", temperature, maxTokens, maxRetries);

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(requirementsJson, architectureJson, input.getRagContext());
        log.info("Prompt构建完成，长度: {} 字符", systemPrompt.length());

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (input.isCancelled()) {
                log.info("任务已取消，ModuleAnalysisAgent停止执行");
                return AgentOutput.cancelled(input.getSessionId());
            }

            log.info("----------------------------------------");
            log.info("第 {}/{} 次尝试", attempt, maxRetries);
            log.info("正在调用大模型... (类型: {}, 模型: {})", modelType.name(), llmClient.getModelName());

            long llmStartTime = System.currentTimeMillis();
            String llmResponse = llmClient.chat(systemPrompt, temperature, maxTokens);
            long llmTime = System.currentTimeMillis() - llmStartTime;

            log.info("LLM调用完成，耗时: {}ms", llmTime);

            if (input.isCancelled()) {
                log.info("任务已取消，ModuleAnalysisAgent停止执行");
                return AgentOutput.cancelled(input.getSessionId());
            }

            if (llmResponse == null || llmResponse.trim().isEmpty()) {
                log.warn("LLM返回空响应，准备重试");
                continue;
            }

            log.info("LLM响应长度: {} 字符", llmResponse.length());
            log.info("LLM响应前200字符: {}", llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse);

            try {
                log.info("正在解析LLM响应...");
                ModuleAnalysisResult result = parseResult(llmResponse);

                if (result != null && result.getModules() != null && !result.getModules().isEmpty()) {
                    String outputJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
                    long executionTime = System.currentTimeMillis() - startTime;

                    log.info("========================================");
                    log.info("ModuleAnalysisAgent执行成功!");
                    log.info("分析模块数量: {} 个", result.getModules().size());
                    log.info("总耗时: {}ms", executionTime);
                    log.info("========================================");

                    for (int i = 0; i < result.getModules().size(); i++) {
                        ModuleAnalysisResult.Module module = result.getModules().get(i);
                        log.info("  {}: {} (层次: {})", i + 1, module.getModuleName(), 
                                String.join(" → ", module.getComponentHierarchy()));
                    }

                    return AgentOutput.success(input.getSessionId(), outputJson, executionTime);
                }

                log.warn("解析出的模块列表为空，准备重试");

            } catch (Exception e) {
                log.warn("第{}次尝试解析LLM响应失败: {}", attempt, e.getMessage());
                log.debug("详细错误:", e);
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.error("========================================");
        log.error("ModuleAnalysisAgent执行失败!");
        log.error("重试次数: {} 次", maxRetries);
        log.error("总耗时: {}ms", executionTime);
        log.error("========================================");

        return AgentOutput.failure(input.getSessionId(),
                "模块分析失败，已重试" + maxRetries + "次");
    }

    private ModuleAnalysisResult parseResult(String response) throws Exception {
        String jsonContent = extractJson(response);

        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            throw new IllegalArgumentException("无法从响应中提取JSON内容");
        }

        return objectMapper.readValue(jsonContent, ModuleAnalysisResult.class);
    }

    private String extractJson(String response) {
        int startIndex = response.indexOf('{');
        int endIndex = response.lastIndexOf('}');

        if (startIndex >= 0 && endIndex > startIndex) {
            return response.substring(startIndex, endIndex + 1);
        }

        return null;
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}