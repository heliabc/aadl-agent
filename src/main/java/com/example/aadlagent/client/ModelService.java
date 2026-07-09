package com.example.aadlagent.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class ModelService {

    private final OllamaClient ollamaClient;
    private final DeepSeekClient deepSeekClient;

    public ModelService(OllamaClient ollamaClient, DeepSeekClient deepSeekClient) {
        this.ollamaClient = ollamaClient;
        this.deepSeekClient = deepSeekClient;
    }

    public LlmClient getClient(ModelType modelType) {
        switch (modelType) {
            case DEEPSEEK:
                return deepSeekClient;
            case OLLAMA:
            default:
                return ollamaClient;
        }
    }

    public Map<String, Object> getModelStatus() {
        Map<String, Object> status = new HashMap<>();
        
        Map<String, Object> ollamaStatus = new HashMap<>();
        ollamaStatus.put("type", "ollama");
        ollamaStatus.put("model", ollamaClient.getModelName());
        ollamaStatus.put("available", ollamaClient.isAvailable());
        status.put("ollama", ollamaStatus);

        Map<String, Object> deepSeekStatus = new HashMap<>();
        deepSeekStatus.put("type", "deepseek");
        deepSeekStatus.put("model", deepSeekClient.getModelName());
        deepSeekStatus.put("available", deepSeekClient.isAvailable());
        status.put("deepseek", deepSeekStatus);

        return status;
    }
}