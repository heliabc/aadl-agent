package com.example.aadlagent.client;

import com.example.aadlagent.config.BaseOpenAiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 API 的通用客户端基类。
 * DeepSeek、豆包（Doubao）、ChatGPT 等兼容 OpenAI /chat/completions 格式的 API 均可继承此类。
 * 子类只需注入对应的配置类即可。
 */
@Slf4j
public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    protected final BaseOpenAiConfig config;
    protected final RestTemplate restTemplate;
    protected final ObjectMapper objectMapper;

    protected AbstractOpenAiCompatibleClient(BaseOpenAiConfig config) {
        this.config = config;
        this.restTemplate = createRestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getTimeout());
        factory.setReadTimeout(config.getTimeout());
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(factory);
        return restTemplate;
    }

    /** 获取客户端名称（用于日志），子类可覆盖 */
    protected String getClientName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public String chat(String prompt, Double temperature, Integer maxTokens) {
        return chat(prompt, temperature, maxTokens, null);
    }

    @Override
    public String chat(String prompt, Double temperature, Integer maxTokens, String modelName) {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            log.warn("{} API key is not configured", getClientName());
            return null;
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
            log.warn("{} base URL is not configured", getClientName());
            return null;
        }

        String url = config.getBaseUrl() + "/chat/completions";
        String model = (modelName != null && !modelName.isEmpty()) ? modelName : config.getChatModel();

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);

        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        requestBody.put("messages", new Object[]{message});
        requestBody.put("stream", false);

        if (temperature != null) {
            requestBody.put("temperature", temperature);
        }
        if (maxTokens != null) {
            requestBody.put("max_tokens", maxTokens);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + config.getApiKey());

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                String responseBody = response.getBody();
                if (responseBody == null || responseBody.trim().isEmpty()) {
                    log.warn("{} chat response: response body is null or empty", getClientName());
                    return null;
                }
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray() || choices.size() == 0) {
                    log.warn("{} chat response: choices is missing or empty, response body: {}",
                            getClientName(), truncateForLog(responseBody));
                    return null;
                }
                JsonNode messageNode = choices.get(0).get("message");
                if (messageNode == null) {
                    log.warn("{} chat response: choices[0].message is null, response body: {}",
                            getClientName(), truncateForLog(responseBody));
                    return null;
                }
                JsonNode content = messageNode.get("content");
                if (content == null || content.isNull() || isEmptyTextNode(content)) {
                    // 推理模型（如 DeepSeek R1/V4、豆包推理模型）可能将思考内容放在 reasoning_content
                    // 而 content 为空。此时尝试从 reasoning_content 提取文本作为兜底。
                    JsonNode reasoning = messageNode.get("reasoning_content");
                    if (reasoning != null && !reasoning.isNull() && !isEmptyTextNode(reasoning)) {
                        String reasoningText = extractText(reasoning);
                        if (reasoningText != null && !reasoningText.trim().isEmpty()) {
                            log.warn("{} chat response: content is empty but reasoning_content has text; " +
                                    "using reasoning_content as fallback ({} chars). " +
                                    "Consider switching to a non-reasoning model for better structured output.",
                                    getClientName(), reasoningText.length());
                            return reasoningText;
                        }
                    }
                    log.warn("{} chat response: message.content is empty and reasoning_content is also empty, " +
                            "response body: {}", getClientName(), truncateForLog(responseBody));
                    return null;
                }
                // content 可能是字符串，也可能是多模态数组（取第一个 text 部分）
                String text = extractText(content);
                if (text == null || text.trim().isEmpty()) {
                    log.warn("{} chat response: content text is empty, response body: {}",
                            getClientName(), truncateForLog(responseBody));
                    return null;
                }
                return text;
            } else {
                log.error("{} chat request failed with status: {}, response body: {}",
                        getClientName(), response.getStatusCode(),
                        truncateForLog(response.getBody()));
                return null;
            }
        } catch (RestClientException e) {
            log.error("{} chat request exception: {}", getClientName(), e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("{} chat response parsing exception: {}", getClientName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 截断响应体用于日志输出，避免日志过大。
     */
    private String truncateForLog(String body) {
        if (body == null) return "null";
        String trimmed = body.trim();
        if (trimmed.length() <= 500) return trimmed;
        return trimmed.substring(0, 500) + "... [truncated, total " + trimmed.length() + " chars]";
    }

    /**
     * 从 content 节点提取文本。content 可能是字符串，也可能是多模态数组（取所有 text 部分拼接）。
     */
    private String extractText(JsonNode content) {
        if (content == null || content.isNull()) return null;
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode textNode = part.get("text");
                if (textNode != null && !textNode.isNull()) {
                    sb.append(textNode.asText());
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * 判断 content 节点是否为空文本（字符串为空 或 数组中没有 text 部分）。
     */
    private boolean isEmptyTextNode(JsonNode content) {
        String text = extractText(content);
        return text == null || text.trim().isEmpty();
    }

    @Override
    public float[] embed(String text) {
        // RAG 嵌入统一使用 Ollama 本地模型，API 模型不提供嵌入能力
        log.warn("{} does not support embedding; use Ollama for RAG embeddings", getClientName());
        return null;
    }

    protected boolean isConfigured() {
        return config.getApiKey() != null && !config.getApiKey().isEmpty()
                && config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()
                && config.getChatModel() != null && !config.getChatModel().isEmpty();
    }

    @Override
    public boolean isAvailable() {
        return isConfigured();
    }

    @Override
    public String getModelName() {
        return config.getChatModel();
    }

    @Override
    public boolean checkModel(String modelName) {
        // OpenAI 兼容 API 通常不提供模型列表查询接口，只要配置了就认为可用
        return isConfigured();
    }
}
