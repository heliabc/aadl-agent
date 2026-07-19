package com.example.aadlplugin.agent.module;

import com.example.aadlplugin.agent.Agent;
import com.example.aadlplugin.agent.AgentInput;
import com.example.aadlplugin.agent.AgentOutput;
import com.example.aadlplugin.client.LlmClient;
import com.example.aadlplugin.client.ModelService;
import com.example.aadlplugin.client.ModelType;
import com.example.aadlplugin.model.ModuleAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.logging.Logger;

public class ModuleAnalysisAgent implements Agent<AgentInput, AgentOutput> {
    
    private static final Logger log = Logger.getLogger(ModuleAnalysisAgent.class.getName());

    private static final String AGENT_NAME = "ModuleAnalysisAgent";

    private final ModelService modelService;
    private final ObjectMapper objectMapper;
    private final ModuleAnalysisPrompt prompt;
    private final int maxRetries;
    private final double temperature;
    private final int maxTokens;

    public ModuleAnalysisAgent(ModelService modelService) {
        this(modelService, 3, 0.1, 8192);
    }

    public ModuleAnalysisAgent(ModelService modelService, int maxRetries, double temperature, int maxTokens) {
        this.modelService = modelService;
        this.objectMapper = new ObjectMapper();
        this.prompt = new ModuleAnalysisPrompt();
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
        log.info("ModuleAnalysisAgent starting execution");
        log.info(String.format("Session ID: %s", input.getSessionId()));
        log.info(String.format("Model: %s (%s)", modelType.name(), llmClient.getModelName()));
        log.info("========================================");

        String requirementsJson = input.getContent();
        String architectureJson = input.getMetadata();

        if (requirementsJson == null || requirementsJson.trim().isEmpty()) {
            log.severe("需求列表内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "需求列表内容不能为空");
        }

        if (architectureJson == null || architectureJson.trim().isEmpty()) {
            log.severe("架构树内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "架构树内容不能为空");
        }

        log.info(String.format("需求列表长度: %d 字符", requirementsJson.length()));
        log.info(String.format("架构树长度: %d 字符", architectureJson.length()));
        log.info(String.format("配置参数: temperature=%f, maxTokens=%d, maxRetries=%d", temperature, maxTokens, maxRetries));

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(requirementsJson, architectureJson, input.getRagContext());
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
                ModuleAnalysisResult result = parseResult(llmResponse);

                if (result != null && result.getModules() != null && !result.getModules().isEmpty()) {
                    String outputJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
                    long executionTime = System.currentTimeMillis() - startTime;

                    log.info("========================================");
                    log.info("ModuleAnalysisAgent执行成功!");
                    log.info(String.format("分析模块数量: %d 个", result.getModules().size()));
                    log.info(String.format("总耗时: %dms", executionTime));
                    log.info("========================================");

                    for (int i = 0; i < result.getModules().size(); i++) {
                        ModuleAnalysisResult.Module module = result.getModules().get(i);
                        log.info(String.format("  %d: %s (层次: %s)", i + 1, module.getModuleName(),
                                String.join(" → ", module.getComponentHierarchy())));
                    }

                    return AgentOutput.success(input.getSessionId(), outputJson, executionTime);
                }

                log.warning("解析出的模块列表为空，准备重试");

            } catch (Exception e) {
                log.warning(String.format("第%d次尝试解析LLM响应失败: %s", attempt, e.getMessage()));
                log.fine("详细错误: " + e.getMessage());
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.severe("========================================");
        log.severe("ModuleAnalysisAgent执行失败!");
        log.severe(String.format("重试次数: %d 次", maxRetries));
        log.severe(String.format("总耗时: %dms", executionTime));
        log.severe("========================================");

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