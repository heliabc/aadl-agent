package com.example.aadlplugin.agent.aadl;

import com.example.aadlplugin.agent.Agent;
import com.example.aadlplugin.agent.AgentInput;
import com.example.aadlplugin.agent.AgentOutput;
import com.example.aadlplugin.client.LlmClient;
import com.example.aadlplugin.client.ModelService;
import com.example.aadlplugin.client.ModelType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AadlErrorParserAgent implements Agent<AgentInput, AgentOutput> {
    
    private static final Logger log = Logger.getLogger(AadlErrorParserAgent.class.getName());

    private static final String AGENT_NAME = "AadlErrorParserAgent";

    private final ModelService modelService;
    private final AadlErrorParserPrompt prompt;
    private final ObjectMapper objectMapper;
    private final double temperature;
    private final int maxTokens;

    public AadlErrorParserAgent() {
        this.modelService = null;
        this.prompt = new AadlErrorParserPrompt();
        this.objectMapper = new ObjectMapper();
        this.temperature = 0.1;
        this.maxTokens = 8192;
    }

    public AadlErrorParserAgent(ModelService modelService) {
        this(modelService, 0.1, 8192);
    }

    public AadlErrorParserAgent(ModelService modelService, double temperature, int maxTokens) {
        this.modelService = modelService;
        this.prompt = new AadlErrorParserPrompt();
        this.objectMapper = new ObjectMapper();
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("AadlErrorParserAgent starting execution");
        log.info(String.format("Session ID: %s", input.getSessionId()));
        log.info(String.format("Model: %s (%s)", modelType.name(), llmClient.getModelName()));
        log.info("========================================");

        String aadlContent = input.getContent();
        String rawErrors = input.getMetadata();

        if (aadlContent == null || aadlContent.trim().isEmpty()) {
            log.severe("AADL内容为空，无法解析错误");
            return AgentOutput.failure(input.getSessionId(), "AADL内容不能为空");
        }

        if (rawErrors == null || rawErrors.trim().isEmpty()) {
            log.warning("错误信息为空，返回空错误列表");
            long executionTime = System.currentTimeMillis() - startTime;
            return AgentOutput.success(input.getSessionId(), "[]", executionTime);
        }

        log.info(String.format("AADL内容长度: %d 字符", aadlContent.length()));
        log.info(String.format("原始错误长度: %d 字符", rawErrors.length()));
        log.info(String.format("配置参数: temperature=%f, maxTokens=%d", temperature, maxTokens));

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(aadlContent, rawErrors, input.getRagContext());
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
            String jsonContent = extractJsonContent(llmResponse);
            
            List<Map<String, Object>> parsedErrors = parseErrors(jsonContent);
            
            String outputJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsedErrors);
            
            long executionTime = System.currentTimeMillis() - startTime;

            log.info("========================================");
            log.info("AadlErrorParserAgent执行完成!");
            log.info(String.format("解析出错误数量: %d 个", parsedErrors.size()));
            log.info(String.format("总耗时: %dms", executionTime));
            log.info("========================================");

            return AgentOutput.success(input.getSessionId(), outputJson, executionTime);

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.severe(String.format("解析LLM响应失败: %s", e.getMessage()));
            log.fine("详细错误:" + e.getMessage());
            return AgentOutput.failure(input.getSessionId(), "解析LLM响应失败: " + e.getMessage());
        }
    }

    private String extractJsonContent(String response) {
        String cleaned = response.trim();

        Pattern jsonPattern = Pattern.compile("\\[\\s*\\{.*}\\s*\\]", Pattern.DOTALL);
        Matcher matcher = jsonPattern.matcher(cleaned);
        
        if (matcher.find()) {
            return matcher.group();
        }

        if (cleaned.startsWith("```json")) {
            int start = cleaned.indexOf("```json") + 7;
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

        return cleaned;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseErrors(String jsonContent) throws Exception {
        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(jsonContent, List.class);
        } catch (Exception e) {
            log.warning("解析JSON失败，尝试清理后重新解析: " + e.getMessage());
            
            String cleaned = jsonContent.replaceAll("\\s+", " ").trim();
            if (!cleaned.startsWith("[")) {
                cleaned = "[" + cleaned;
            }
            if (!cleaned.endsWith("]")) {
                cleaned = cleaned + "]";
            }
            
            return objectMapper.readValue(cleaned, List.class);
        }
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}
