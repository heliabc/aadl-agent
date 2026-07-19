package com.example.aadlplugin.client;

import com.example.aadlplugin.client.dto.ChatResponse;
import com.example.aadlplugin.client.dto.ChatMessage;
import com.example.aadlplugin.client.dto.EmbeddingResponse;
import com.example.aadlplugin.config.OllamaConfig;
import com.example.aadlplugin.util.HttpClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class OllamaClient implements LlmClient {

    private static final Logger log = Logger.getLogger(OllamaClient.class.getName());

    private final OllamaConfig config;

    public OllamaClient(OllamaConfig config) {
        this.config = config;
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

        String response = HttpClient.postJson(url, requestBody);

        if (response != null) {
            log.fine("Ollama chat raw response: " + response);

            ChatResponse chatResponse = HttpClient.parseJson(response, ChatResponse.class);

            if (chatResponse != null) {
                log.fine("Ollama chat parsed response: model=" + chatResponse.getModel() + ", done=" + chatResponse.isDone() + ", message=null=" + (chatResponse.getMessage() == null));

                if (chatResponse.getMessage() != null) {
                    String content = chatResponse.getMessage().getContent();
                    log.fine("Ollama chat message content: null=" + (content == null) + ", length=" + (content != null ? content.length() : -1));
                    return content;
                }
            }
        }
        log.severe("Ollama chat request failed");
        return null;
    }

    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            log.warning("Input text is empty, skipping embedding generation");
            return null;
        }

        String url = config.getBaseUrl() + "/api/embed";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getEmbeddingModel());
        requestBody.put("input", text);

        String response = HttpClient.postJson(url, requestBody);

        if (response != null) {
            EmbeddingResponse embeddingResponse = HttpClient.parseJson(response, EmbeddingResponse.class);

            if (embeddingResponse == null) {
                log.severe("Ollama returned null response body");
                return null;
            }

            log.fine("Ollama embedding response: model=" + embeddingResponse.getModel() +
                    ", embeddings count=" + (embeddingResponse.getEmbeddings() != null ? embeddingResponse.getEmbeddings().size() : "null") +
                    ", usage=" + embeddingResponse.getUsage());

            if (embeddingResponse.getEmbeddings() == null || embeddingResponse.getEmbeddings().isEmpty()) {
                log.severe("Ollama returned null or empty embeddings container");
                return null;
            }

            List<Float> embeddingList = embeddingResponse.getEmbeddings().get(0);
            if (embeddingList == null || embeddingList.isEmpty()) {
                log.severe("The first embedding vector is null or empty");
                return null;
            }

            float[] embedding = new float[embeddingList.size()];
            for (int i = 0; i < embeddingList.size(); i++) {
                Float value = embeddingList.get(i);
                embedding[i] = (value != null) ? value : 0.0f;
            }

            log.fine("Successfully generated embedding of dimension: " + embedding.length);
            return embedding;
        }

        log.severe("Ollama embedding request failed");
        return null;
    }

    public boolean checkModel(String modelName) {
        String url = config.getBaseUrl() + "/api/tags";

        String response = HttpClient.get(url);

        if (response != null) {
            return response.contains(modelName);
        }
        return false;
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
