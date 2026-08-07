package com.example.aadlagent.client;

import com.example.aadlagent.config.BaseOpenAiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;

import java.util.Arrays;
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
        // 配置错误处理器，让我们能够自己处理HTTP错误响应而不抛出异常
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                // 不将HTTP错误状态码视为错误，让我们自己处理响应
                return false;
            }
        });
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

        String url = config.getBaseUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "chat/completions";
        String model = (modelName != null && !modelName.isEmpty()) ? modelName : config.getChatModel();

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);

        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        requestBody.put("messages", Arrays.asList(message));
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
                            return cleanThinkingContent(reasoningText);
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
                // 清理推理模型返回的 <think>...</think> 思考标签
                String cleaned = cleanThinkingContent(text);
                if (cleaned == null || cleaned.trim().isEmpty()) {
                    log.warn("{} chat response: content is empty after cleaning thinking tags; " +
                            "original content length={}", getClientName(), text.length());
                }
                return cleaned;
            } else {
                String errorBody = response.getBody();
                String errorMessage = extractErrorMessage(errorBody);
                log.error("{} chat request failed with status: {}, error: {}",
                        getClientName(), response.getStatusCode(), errorMessage);
                return null;
            }
        } catch (HttpStatusCodeException e) {
            String errorBody = e.getResponseBodyAsString();
            String errorMessage = extractErrorMessage(errorBody);
            log.error("{} chat request failed with status: {}, error: {}",
                    getClientName(), e.getStatusCode(), errorMessage);
            return null;
        } catch (RestClientException e) {
            log.error("{} chat request exception: {}", getClientName(), e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("{} chat response parsing exception: {}", getClientName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从错误响应体中提取错误信息
     */
    private String extractErrorMessage(String errorBody) {
        if (errorBody == null || errorBody.trim().isEmpty()) {
            return "empty response";
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode error = root.get("error");
            if (error != null) {
                JsonNode message = error.get("message");
                if (message != null && !message.isNull()) {
                    return message.asText();
                }
                return error.toString();
            }
            // 如果没有error字段，返回截断的响应体
            return truncateForLog(errorBody);
        } catch (Exception e) {
            return truncateForLog(errorBody);
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

    /**
     * 移除 qwen3、DeepSeek R1、豆包推理模型等在 content 中返回的 <think>...</think> 思考标签及其内容。
     * 这些标签会干扰后续的 JSON 提取与解析。
     */
    protected String cleanThinkingContent(String content) {
        if (content == null) {
            return null;
        }
        // 移除完整的 <think>...</think> 块（包括跨行内容）
        String result = content.replaceAll("(?s)<think>.*?</think>", "");
        // 也处理 </think> 开头的情况（某些模型可能输出格式不规范）
        result = result.replaceAll("(?s).*?</think>", "");
        // 处理未闭合的 <think> 标签（输出被截断，只有开始标签没有结束标签）
        int thinkStart = result.indexOf("<think>");
        if (thinkStart >= 0) {
            result = result.substring(0, thinkStart);
        }
        return result.trim();
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

    /**
     * 测试API连接是否真正可用（发送一个简单的测试请求）
     */
    public boolean testConnection() {
        if (!isConfigured()) {
            log.warn("{} testConnection failed: not configured", getClientName());
            return false;
        }
        try {
            log.info("Testing {} connection...", getClientName());
            String testResponse = chat("Hi", 0.0, 10);
            boolean available = testResponse != null && !testResponse.trim().isEmpty();
            log.info("{} connection test: {}", getClientName(), available ? "SUCCESS" : "FAILED");
            return available;
        } catch (Exception e) {
            log.error("{} connection test failed with exception: {}", getClientName(), e.getMessage());
            return false;
        }
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
