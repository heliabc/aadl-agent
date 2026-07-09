package com.example.aadlagent.client;

import com.example.aadlagent.client.dto.ChatResponse;
import com.example.aadlagent.client.dto.ChatMessage;
import com.example.aadlagent.client.dto.EmbeddingResponse;
import com.example.aadlagent.config.OllamaConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OllamaClient implements LlmClient {

    private final OllamaConfig config;
    private final RestTemplate restTemplate;

    public OllamaClient(OllamaConfig config) {
        this.config = config;
        this.restTemplate = new RestTemplate();
    }

    public String chat(String prompt, Double temperature, Integer maxTokens) {
        String url = config.getBaseUrl() + "/api/chat";

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

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<ChatResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    ChatResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                ChatResponse chatResponse = response.getBody();
                if (chatResponse != null && chatResponse.getMessage() != null) {
                    return chatResponse.getMessage().getContent();
                }
            }
            log.error("Ollama chat request failed with status: {}", response.getStatusCode());
            return null;
        } catch (RestClientException e) {
            log.error("Ollama chat request exception: {}", e.getMessage(), e);
            return null;
        }
    }

    public float[] embed(String text) {
        String url = config.getBaseUrl() + "/api/embeddings";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getEmbeddingModel());
        requestBody.put("prompt", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<EmbeddingResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    EmbeddingResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                EmbeddingResponse embeddingResponse = response.getBody();
                if (embeddingResponse != null && embeddingResponse.getEmbedding() != null) {
                    List<Float> embeddingList = embeddingResponse.getEmbedding();
                    float[] embedding = new float[embeddingList.size()];
                    for (int i = 0; i < embeddingList.size(); i++) {
                        embedding[i] = embeddingList.get(i);
                    }
                    return embedding;
                }
            }
            log.error("Ollama embedding request failed with status: {}", response.getStatusCode());
            return null;
        } catch (RestClientException e) {
            log.error("Ollama embedding request exception: {}", e.getMessage(), e);
            return null;
        }
    }

    public boolean checkModel(String modelName) {
        String url = config.getBaseUrl() + "/api/tags";

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                String responseBody = response.getBody();
                return responseBody.contains(modelName);
            }
            return false;
        } catch (RestClientException e) {
            log.warn("Ollama tags request exception: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return checkModel(config.getChatModel());
    }

    @Override
    public String getModelName() {
        return config.getChatModel();
    }
}