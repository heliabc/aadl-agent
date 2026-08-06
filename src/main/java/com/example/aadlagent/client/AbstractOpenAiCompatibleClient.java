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
                if (responseBody != null) {
                    JsonNode root = objectMapper.readTree(responseBody);
                    JsonNode choices = root.get("choices");
                    if (choices != null && choices.isArray() && choices.size() > 0) {
                        JsonNode messageNode = choices.get(0).get("message");
                        if (messageNode != null) {
                            JsonNode content = messageNode.get("content");
                            if (content != null) {
                                return content.asText();
                            }
                        }
                    }
                }
            }
            log.error("{} chat request failed with status: {}", getClientName(), response.getStatusCode());
            return null;
        } catch (RestClientException e) {
            log.error("{} chat request exception: {}", getClientName(), e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("{} chat response parsing exception: {}", getClientName(), e.getMessage(), e);
            return null;
        }
    }

    @Override
    public float[] embed(String text) {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            log.warn("{} API key is not configured", getClientName());
            return null;
        }
        if (config.getEmbeddingModel() == null || config.getEmbeddingModel().isEmpty()) {
            log.warn("{} embedding model is not configured", getClientName());
            return null;
        }

        String url = config.getBaseUrl() + "/embeddings";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getEmbeddingModel());
        requestBody.put("input", text);

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
                if (responseBody != null) {
                    JsonNode root = objectMapper.readTree(responseBody);
                    JsonNode data = root.get("data");
                    if (data != null && data.isArray() && data.size() > 0) {
                        JsonNode embeddingNode = data.get(0).get("embedding");
                        if (embeddingNode != null && embeddingNode.isArray()) {
                            List<Float> embeddingList = objectMapper.convertValue(embeddingNode,
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, Float.class));
                            float[] embedding = new float[embeddingList.size()];
                            for (int i = 0; i < embeddingList.size(); i++) {
                                embedding[i] = embeddingList.get(i);
                            }
                            return embedding;
                        }
                    }
                }
            }
            log.error("{} embedding request failed with status: {}", getClientName(), response.getStatusCode());
            return null;
        } catch (RestClientException e) {
            log.error("{} embedding request exception: {}", getClientName(), e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("{} embedding response parsing exception: {}", getClientName(), e.getMessage(), e);
            return null;
        }
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
