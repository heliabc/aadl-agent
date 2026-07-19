package com.example.aadlplugin.agent.architecture;

import com.example.aadlplugin.agent.Agent;
import com.example.aadlplugin.agent.AgentInput;
import com.example.aadlplugin.agent.AgentOutput;
import com.example.aadlplugin.client.LlmClient;
import com.example.aadlplugin.client.ModelService;
import com.example.aadlplugin.client.ModelType;
import com.example.aadlplugin.model.AadlArchitectureModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.logging.Logger;

public class AadlArchitectureAgent implements Agent<AgentInput, AgentOutput> {
    
    private static final Logger log = Logger.getLogger(AadlArchitectureAgent.class.getName());

    private static final String AGENT_NAME = "AadlArchitectureAgent";

    private final ModelService modelService;
    private final ObjectMapper objectMapper;
    private final AadlArchitecturePrompt prompt;
    private final int maxRetries;
    private final double temperature;
    private final int maxTokens;

    public AadlArchitectureAgent(ModelService modelService) {
        this(modelService, 3, 0.1, 8192);
    }

    public AadlArchitectureAgent(ModelService modelService, int maxRetries, double temperature, int maxTokens) {
        this.modelService = modelService;
        this.objectMapper = JsonMapper.builder()
                .disable(SerializationFeature.WRITE_NULL_MAP_VALUES)
                .build();
        this.prompt = new AadlArchitecturePrompt();
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
        log.info("AadlArchitectureAgent starting execution");
        log.info(String.format("Session ID: %s", input.getSessionId()));
        log.info(String.format("Model: %s (%s)", modelType.name(), llmClient.getModelName()));
        log.info("========================================");

        String requirementsJson = input.getContent();
        if (requirementsJson == null || requirementsJson.trim().isEmpty()) {
            log.severe("需求列表内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "需求列表内容不能为空");
        }

        log.info(String.format("需求列表长度: %d 字符", requirementsJson.length()));
        log.info(String.format("配置参数: temperature=%f, maxTokens=%d, maxRetries=%d", temperature, maxTokens, maxRetries));

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(requirementsJson, input.getRagContext());
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
                AadlArchitectureModel architecture = parseArchitecture(llmResponse);

                if (architecture != null && architecture.getName() != null) {
                    String outputJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(architecture);
                    long executionTime = System.currentTimeMillis() - startTime;

                    log.info("========================================");
                    log.info("AadlArchitectureAgent执行成功!");
                    log.info(String.format("根节点名称: %s", architecture.getName()));
                    log.info(String.format("子组件数量: %d", architecture.getChildren() != null ? architecture.getChildren().size() : 0));
                    log.info(String.format("总耗时: %dms", executionTime));
                    log.info("========================================");

                    printArchitectureTree(architecture, 0);

                    return AgentOutput.success(input.getSessionId(), outputJson, executionTime);
                }

                log.warning("解析出的架构模型为空，准备重试");

            } catch (Exception e) {
                log.warning(String.format("第%d次尝试解析LLM响应失败: %s", attempt, e.getMessage()));
                log.fine("详细错误: " + e.getMessage());
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.severe("========================================");
        log.severe("AadlArchitectureAgent执行失败!");
        log.severe(String.format("重试次数: %d 次", maxRetries));
        log.severe(String.format("总耗时: %dms", executionTime));
        log.severe("========================================");

        return AgentOutput.failure(input.getSessionId(),
                "AADL架构生成失败，已重试" + maxRetries + "次");
    }

    private AadlArchitectureModel parseArchitecture(String response) throws Exception {
        String jsonContent = extractJson(response);

        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            throw new IllegalArgumentException("无法从响应中提取JSON内容");
        }

        return objectMapper.readValue(jsonContent, AadlArchitectureModel.class);
    }

    private String extractJson(String response) {
        int startIndex = response.indexOf('{');
        int endIndex = response.lastIndexOf('}');

        if (startIndex >= 0 && endIndex > startIndex) {
            return response.substring(startIndex, endIndex + 1);
        }

        return null;
    }

    private void printArchitectureTree(AadlArchitectureModel model, int indent) {
        if (model == null) {
            return;
        }
        String prefix = "  ".repeat(indent);
        log.info(prefix + "+ " + model.getName() + " (" + model.getType() + ")");

        if (model.getChildren() != null && !model.getChildren().isEmpty()) {
            for (AadlArchitectureModel child : model.getChildren()) {
                printArchitectureTree(child, indent + 1);
            }
        }
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}