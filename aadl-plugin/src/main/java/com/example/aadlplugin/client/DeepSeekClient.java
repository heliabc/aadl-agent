package com.example.aadlplugin.client;

import com.example.aadlplugin.config.DeepSeekConfig;
import com.example.aadlplugin.util.HttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class DeepSeekClient implements LlmClient {

    private static final Logger log = Logger.getLogger(DeepSeekClient.class.getName());

    private final DeepSeekConfig config;
    private final ObjectMapper objectMapper;

    public DeepSeekClient(DeepSeekConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    public String chat(String prompt, Double temperature, Integer maxTokens) {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            log.severe("DeepSeek API key is not configured");
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

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String response = HttpClient.postJson(url, requestBody, headers);

        if (response != null) {
            try {
                JsonNode root = objectMapper.readTree(response);
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
            } catch (Exception e) {
                log.severe("DeepSeek chat response parsing exception: " + e.getMessage());
            }
        }
        log.severe("DeepSeek chat request failed");
        return null;
    }

    public float[] embed(String text) {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            log.severe("DeepSeek API key is not configured");
            return null;
        }

        String url = config.getBaseUrl() + "/embeddings";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getEmbeddingModel());
        requestBody.put("input", text);

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String response = HttpClient.postJson(url, requestBody, headers);

        if (response != null) {
            try {
                JsonNode root = objectMapper.readTree(response);
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
            } catch (Exception e) {
                log.severe("DeepSeek embedding response parsing exception: " + e.getMessage());
            }
        }
        log.severe("DeepSeek embedding request failed");
        return null;
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
