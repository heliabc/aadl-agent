package com.example.aadlagent.agent.architecture;

import com.example.aadlagent.agent.Agent;
import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.model.AadlArchitectureModel;
import com.example.aadlagent.util.JsonRepairUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AadlArchitectureAgent implements Agent<AgentInput, AgentOutput> {

    private static final String AGENT_NAME = "AadlArchitectureAgent";

    private final ModelService modelService;
    private final ObjectMapper objectMapper;
    private final AadlArchitecturePrompt prompt;

    @Value("${agent.architecture.max-retries:3}")
    private int maxRetries;

    @Value("${agent.architecture.temperature:0.1}")
    private double temperature;

    @Value("${agent.architecture.max-tokens:8192}")
    private int maxTokens;

    public AadlArchitectureAgent(ModelService modelService) {
        this.modelService = modelService;
        this.objectMapper = JsonMapper.builder()
                .disable(SerializationFeature.WRITE_NULL_MAP_VALUES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonParser.Feature.ALLOW_COMMENTS)
                .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
                .build();
        this.prompt = new AadlArchitecturePrompt();
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("AadlArchitectureAgent starting execution");
        log.info("Session ID: {}", input.getSessionId());
        log.info("Model: {} ({})", modelType.name(), llmClient.getModelName());
        log.info("========================================");

        String requirementsJson = input.getContent();
        if (requirementsJson == null || requirementsJson.trim().isEmpty()) {
            log.error("需求列表内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "需求列表内容不能为空");
        }

        log.info("需求列表长度: {} 字符", requirementsJson.length());
        log.info("配置参数: temperature={}, maxTokens={}, maxRetries={}", temperature, maxTokens, maxRetries);

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(requirementsJson, input.getRagContext());
        log.info("Prompt构建完成，长度: {} 字符", systemPrompt.length());

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (input.isCancelled()) {
                log.info("任务已取消，AadlArchitectureAgent停止执行");
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
                log.info("任务已取消，AadlArchitectureAgent停止执行");
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
                AadlArchitectureModel architecture = parseArchitecture(llmResponse);

                if (architecture != null && architecture.getName() != null) {
                    String outputJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(architecture);
                    long executionTime = System.currentTimeMillis() - startTime;

                    log.info("========================================");
                    log.info("AadlArchitectureAgent执行成功!");
                    log.info("根节点名称: {}", architecture.getName());
                    log.info("子组件数量: {}", architecture.getSubcomponents() != null ? architecture.getSubcomponents().size() : 0);
                    log.info("总耗时: {}ms", executionTime);
                    log.info("========================================");

                    printArchitectureTree(architecture, 0);

                    return AgentOutput.success(input.getSessionId(), outputJson, executionTime);
                }

                log.warn("解析出的架构模型为空，准备重试");

            } catch (Exception e) {
                log.warn("第{}次尝试解析LLM响应失败: {}", attempt, e.getMessage());
                log.debug("详细错误:", e);
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.error("========================================");
        log.error("AadlArchitectureAgent执行失败!");
        log.error("重试次数: {} 次", maxRetries);
        log.error("总耗时: {}ms", executionTime);
        log.error("========================================");

        return AgentOutput.failure(input.getSessionId(),
                "AADL架构生成失败，已重试" + maxRetries + "次");
    }

    private AadlArchitectureModel parseArchitecture(String response) throws Exception {
        String jsonContent = JsonRepairUtil.extractAndFixJson(response);

        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            throw new IllegalArgumentException("无法从响应中提取JSON内容");
        }

        // 兼容两种格式：
        //   1. 扁平结构：{"name": "xxx", "type": "system", ...}
        //   2. 带 root 包装：{"root": {"name": "xxx", ...}}
        // 先尝试扁平结构（直接解析），如果 name 为空再尝试提取 root
        AadlArchitectureModel architecture = objectMapper.readValue(jsonContent, AadlArchitectureModel.class);

        if (architecture != null && architecture.getName() == null) {
            // 可能是带 root 包装的格式，尝试提取 root 节点
            com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(jsonContent);
            if (rootNode.has("root")) {
                com.fasterxml.jackson.databind.JsonNode inner = rootNode.get("root");
                architecture = objectMapper.treeToValue(inner, AadlArchitectureModel.class);
            }
        }

        return architecture;
    }

    private void printArchitectureTree(AadlArchitectureModel model, int indent) {
        if (model == null) {
            return;
        }
        String prefix = "  ".repeat(indent);
        log.info("{}+ {} ({})", prefix, model.getName(), model.getType());

        if (model.getSubcomponents() != null && !model.getSubcomponents().isEmpty()) {
            for (AadlArchitectureModel child : model.getSubcomponents()) {
                printArchitectureTree(child, indent + 1);
            }
        }
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}