package com.example.aadlagent.client;

import com.example.aadlagent.config.GeminiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

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
        String url = config.getBaseUrl() + "/models/" + model + ":generateContent?key=" + config.getApiKey();

        // 构建 Gemini generateContent 请求体
        Map<String, Object> requestBody = new HashMap<>();

        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> content = new HashMap<>();
        content.put("role", "user");

        List<Map<String, Object>> parts = new ArrayList<>();
        Map<String, Object> part = new HashMap<>();
        part.put("text", prompt);
        parts.add(part);
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
                if (responseBody != null) {
                    JsonNode root = objectMapper.readTree(responseBody);
                    JsonNode candidates = root.get("candidates");
                    if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                        JsonNode contentNode = candidates.get(0).get("content");
                        if (contentNode != null) {
                            JsonNode partsNode = contentNode.get("parts");
                            if (partsNode != null && partsNode.isArray() && partsNode.size() > 0) {
                                JsonNode textNode = partsNode.get(0).get("text");
                                if (textNode != null) {
                                    return textNode.asText();
                                }
                            }
                        }
                    }
                }
            }
            log.error("Gemini chat request failed with status: {}", response.getStatusCode());
            return null;
        } catch (RestClientException e) {
            log.error("Gemini chat request exception: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Gemini chat response parsing exception: {}", e.getMessage(), e);
            return null;
        }
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
