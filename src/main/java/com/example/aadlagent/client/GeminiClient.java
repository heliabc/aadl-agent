package com.example.aadlagent.client;

import com.example.aadlagent.config.GeminiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.util.*;

/**
 * Google Gemini API 客户端。
 * 使用 Gemini REST API（generateContent 格式），与 OpenAI 格式不同。
 */
@Slf4j
@Component
public class GeminiClient implements LlmClient {

    private final GeminiConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GeminiClient(GeminiConfig config) {
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
                return false;
            }
        });
        return restTemplate;
    }

    @Override
    public String chat(String prompt, Double temperature, Integer maxTokens) {
        return chat(prompt, temperature, maxTokens, null);
    }

    @Override
    public String chat(String prompt, Double temperature, Integer maxTokens, String modelName) {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            log.warn("Gemini API key is not configured");
            return null;
        }

        String model = (modelName != null && !modelName.isEmpty()) ? modelName : config.getChatModel();
        String baseUrl = config.getBaseUrl();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        String url = baseUrl + "models/" + model + ":generateContent?key=" + config.getApiKey();

        // 构建 Gemini generateContent 请求体
        Map<String, Object> requestBody = new HashMap<>();

        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> content = new HashMap<>();
        content.put("role", "user");

        List<Map<String, Object>> parts = new ArrayList<>();
        Map<String, Object> partMap = new HashMap<>();
        partMap.put("text", prompt);
        parts.add(partMap);
        content.put("parts", parts);
        contents.add(content);
        requestBody.put("contents", contents);

        // generationConfig
        Map<String, Object> generationConfig = new HashMap<>();
        if (temperature != null) {
            generationConfig.put("temperature", temperature);
        }
        if (maxTokens != null) {
            generationConfig.put("maxOutputTokens", maxTokens);
        }
        if (!generationConfig.isEmpty()) {
            requestBody.put("generationConfig", generationConfig);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                String responseBody = response.getBody();
                if (responseBody == null || responseBody.trim().isEmpty()) {
                    log.warn("Gemini chat response: response body is null or empty");
                    return null;
                }
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode candidates = root.get("candidates");
                if (candidates == null || !candidates.isArray() || candidates.size() == 0) {
                    // 检查是否有 error 字段
                    JsonNode error = root.get("error");
                    if (error != null) {
                        log.error("Gemini chat response: API error - {}, response body: {}",
                                error, truncateForLog(responseBody));
                    } else {
                        log.warn("Gemini chat response: candidates is missing or empty, response body: {}",
                                truncateForLog(responseBody));
                    }
                    return null;
                }
                JsonNode candidate = candidates.get(0);
                JsonNode contentNode = candidate.get("content");
                if (contentNode == null) {
                    // Gemini Thinking 模型可能把思考内容放在顶层 thinking 字段，content 为空
                    JsonNode thinking = candidate.get("thinking");
                    if (thinking != null && !thinking.isNull() && !thinking.asText().trim().isEmpty()) {
                        String thinkingText = cleanThinkingContent(thinking.asText());
                        log.warn("Gemini chat response: content is null but thinking field has text; " +
                                "using thinking as fallback ({} chars). " +
                                "Consider switching to a non-thinking model for better structured output.",
                                thinkingText.length());
                        return thinkingText;
                    }
                    log.warn("Gemini chat response: candidates[0].content is null, response body: {}",
                            truncateForLog(responseBody));
                    return null;
                }
                JsonNode partsNode = contentNode.get("parts");
                if (partsNode == null || !partsNode.isArray() || partsNode.size() == 0) {
                    log.warn("Gemini chat response: content.parts is missing or empty, response body: {}",
                            truncateForLog(responseBody));
                    return null;
                }
                // 拼接所有 parts 中的 text 部分
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : partsNode) {
                    JsonNode textNode = part.get("text");
                    if (textNode != null && !textNode.isNull()) {
                        sb.append(textNode.asText());
                    }
                }
                String text = sb.toString();
                // 清理思考标签
                text = cleanThinkingContent(text);
                if (text.trim().isEmpty()) {
                    // content.parts 没有 text，尝试从 thinking 字段兜底
                    JsonNode thinking = candidate.get("thinking");
                    if (thinking != null && !thinking.isNull() && !thinking.asText().trim().isEmpty()) {
                        String thinkingText = cleanThinkingContent(thinking.asText());
                        log.warn("Gemini chat response: content text is empty but thinking field has text; " +
                                        "using thinking as fallback ({} chars). " +
                                        "Consider switching to a non-thinking model for better structured output.",
                                thinkingText.length());
                        return thinkingText;
                    }
                    log.warn("Gemini chat response: all parts text is empty, response body: {}",
                            truncateForLog(responseBody));
                    return null;
                }
                return text;
            } else {
                log.error("Gemini chat request failed with status: {}, response body: {}",
                        response.getStatusCode(), truncateForLog(response.getBody()));
                return null;
            }
        } catch (RestClientException e) {
            log.error("Gemini chat request exception: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Gemini chat response parsing exception: {}", e.getMessage(), e);
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
     * 移除思考模型返回的 <think>...</think> 思考标签及其内容。
     */
    private String cleanThinkingContent(String content) {
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
        // RAG 嵌入统一使用 Ollama 本地模型，Gemini 不提供嵌入能力
        log.warn("Gemini does not support embedding; use Ollama for RAG embeddings");
        return null;
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isEmpty()
                && config.getChatModel() != null && !config.getChatModel().isEmpty();
    }

    @Override
    public String getModelName() {
        return config.getChatModel();
    }

    @Override
    public boolean checkModel(String modelName) {
        // Gemini 不提供模型列表查询，只要配置了就认为可用
        return isAvailable();
    }
}
