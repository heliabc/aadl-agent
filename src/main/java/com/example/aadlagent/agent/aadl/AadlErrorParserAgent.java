package com.example.aadlagent.agent.aadl;

import com.example.aadlagent.agent.Agent;
import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AadlErrorParserAgent implements Agent<AgentInput, AgentOutput> {

    private static final String AGENT_NAME = "AadlErrorParserAgent";

    private final ModelService modelService;
    private final AadlErrorParserPrompt prompt;
    private final ObjectMapper objectMapper;

    @Value("${agent.aadl-error-parser.temperature:0.1}")
    private double temperature;

    @Value("${agent.aadl-error-parser.max-tokens:8192}")
    private int maxTokens;

    public AadlErrorParserAgent(ModelService modelService) {
        this.modelService = modelService;
        this.prompt = new AadlErrorParserPrompt();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("AadlErrorParserAgent starting execution");
        log.info("Session ID: {}", input.getSessionId());
        log.info("Model: {} ({})", modelType.name(), llmClient.getModelName());
        log.info("========================================");

        String aadlContent = input.getContent();
        String rawErrors = input.getMetadata();

        if (aadlContent == null || aadlContent.trim().isEmpty()) {
            log.error("AADL内容为空，无法解析错误");
            return AgentOutput.failure(input.getSessionId(), "AADL内容不能为空");
        }

        if (rawErrors == null || rawErrors.trim().isEmpty()) {
            log.warn("错误列表为空，返回空错误列表");
            long executionTime = System.currentTimeMillis() - startTime;
            return AgentOutput.success(input.getSessionId(), "[]", executionTime);
        }

        log.info("AADL内容长度: {} 字符", aadlContent.length());
        log.info("原始错误长度: {} 字符", rawErrors.length());
        log.info("配置参数: temperature={}, maxTokens={}", temperature, maxTokens);

        log.info("正在构建Prompt...");
        String systemPrompt = prompt.buildPrompt(aadlContent, rawErrors, input.getRagContext());
        log.info("Prompt构建完成，长度: {} 字符", systemPrompt.length());

        if (input.isCancelled()) {
            log.info("任务已取消，AadlErrorParserAgent停止执行");
            return AgentOutput.cancelled(input.getSessionId());
        }

        log.info("----------------------------------------");
        log.info("正在调用大模型... (类型: {}, 模型: {})", modelType.name(), llmClient.getModelName());

        long llmStartTime = System.currentTimeMillis();
        String llmResponse = llmClient.chat(systemPrompt, temperature, maxTokens);
        long llmTime = System.currentTimeMillis() - llmStartTime;

        log.info("LLM调用完成，耗时: {}ms", llmTime);

        if (input.isCancelled()) {
            log.info("任务已取消，AadlErrorParserAgent停止执行");
            return AgentOutput.cancelled(input.getSessionId());
        }

        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("LLM返回空响应");
            return AgentOutput.failure(input.getSessionId(), "LLM返回空响应");
        }

        log.info("LLM响应长度: {} 字符", llmResponse.length());
        log.info("LLM响应前200字符: {}", llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse);

        try {
            log.info("正在解析LLM响应...");
            String structuredErrors = extractJsonContent(llmResponse);

            List<Map<String, Object>> errorList = parseErrors(structuredErrors);
            log.info("解析完成，共识别 {} 个错误", errorList.size());

            for (int i = 0; i < errorList.size(); i++) {
                Map<String, Object> error = errorList.get(i);
                log.info("  错误 {}: type={}, severity={}, line={}", 
                        i + 1,
                        error.get("errorType"),
                        error.get("severity"),
                        error.get("lineNumber"));
            }

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("========================================");
            log.info("AadlErrorParserAgent执行完成!");
            log.info("识别错误数: {} 个", errorList.size());
            log.info("总耗时: {}ms", executionTime);
            log.info("========================================");

            return AgentOutput.success(input.getSessionId(), structuredErrors, executionTime);

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("解析LLM响应失败: {}", e.getMessage());
            log.debug("详细错误:", e);
            return AgentOutput.failure(input.getSessionId(), "解析LLM响应失败: " + e.getMessage());
        }
    }

    private String extractJsonContent(String response) {
        String cleaned = response.trim();

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

        int jsonStart = cleaned.indexOf('[');
        int jsonEnd = cleaned.lastIndexOf(']');
        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            cleaned = cleaned.substring(jsonStart, jsonEnd + 1);
        }

        return cleaned;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseErrors(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(json, List.class);
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}
