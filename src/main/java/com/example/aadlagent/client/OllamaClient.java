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
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    String.class
            );
            
            log.debug("Ollama chat raw response (status={}): {}", rawResponse.getStatusCode(), rawResponse.getBody());

            ResponseEntity<ChatResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    ChatResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                ChatResponse chatResponse = response.getBody();
                if (chatResponse != null) {
                    log.debug("Ollama chat parsed response: model={}, done={}, message=null={}", 
                            chatResponse.getModel(), chatResponse.isDone(), 
                            chatResponse.getMessage() == null);
                    
                    if (chatResponse.getMessage() != null) {
                        String content = chatResponse.getMessage().getContent();
                        log.debug("Ollama chat message content: null={}, length={}", 
                                content == null, content != null ? content.length() : -1);
                        return content;
                    }
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
        if (text == null || text.trim().isEmpty()) {
            log.warn("Input text is empty, skipping embedding generation");
            return null;
        }

        String url = config.getBaseUrl() + "/api/embed";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getEmbeddingModel());
        requestBody.put("input", text);

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
                
                if (embeddingResponse == null) {
                    log.error("Ollama returned null response body");
                    return null;
                }
                
                log.debug("Ollama embedding response: model={}, embeddings count={}, usage={}",
                        embeddingResponse.getModel(),
                        embeddingResponse.getEmbeddings() != null ? embeddingResponse.getEmbeddings().size() : "null",
                        embeddingResponse.getUsage());
                
                if (embeddingResponse.getEmbeddings() == null || embeddingResponse.getEmbeddings().isEmpty()) {
                    log.error("Ollama returned null or empty embeddings container");
                    return null;
                }
                
                List<Float> embeddingList = embeddingResponse.getEmbeddings().get(0);
                if (embeddingList == null || embeddingList.isEmpty()) {
                    log.error("The first embedding vector is null or empty");
                    return null;
                }
                
                float[] embedding = new float[embeddingList.size()];
                for (int i = 0; i < embeddingList.size(); i++) {
                    Float value = embeddingList.get(i);
                    embedding[i] = (value != null) ? value : 0.0f;
                }
                
                log.debug("Successfully generated embedding of dimension: {}", embedding.length);
                return embedding;
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