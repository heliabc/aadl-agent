package com.example.aadlagent.client;

import com.example.aadlagent.config.DeepSeekConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DeepSeekClient implements LlmClient {

    private final DeepSeekConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DeepSeekClient(DeepSeekConfig config) {
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

    public String chat(String prompt, Double temperature, Integer maxTokens) {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            log.error("DeepSeek API key is not configured");
            return null;
        }

        String url = config.getBaseUrl() + "/chat/completions";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getChatModel());

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
                    url,
                    HttpMethod.POST,
                    request,
                    String.class
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
            log.error("DeepSeek chat request failed with status: {}", response.getStatusCode());
            return null;
        } catch (RestClientException e) {
            log.error("DeepSeek chat request exception: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("DeepSeek chat response parsing exception: {}", e.getMessage(), e);
            return null;
        }
    }

    public float[] embed(String text) {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            log.error("DeepSeek API key is not configured");
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
                    url,
                    HttpMethod.POST,
                    request,
                    String.class
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
            log.error("DeepSeek embedding request failed with status: {}", response.getStatusCode());
            return null;
        } catch (RestClientException e) {
            log.error("DeepSeek embedding request exception: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("DeepSeek embedding response parsing exception: {}", e.getMessage(), e);
            return null;
        }
    }

    public boolean isConfigured() {
        return config.getApiKey() != null && !config.getApiKey().isEmpty();
    }

    @Override
    public boolean isAvailable() {
        return isConfigured();
    }

    @Override
    public String getModelName() {
        return config.getChatModel();
    }
}