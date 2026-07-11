package com.example.aadlagent.rag;

import com.example.aadlagent.client.OllamaClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmbeddingService {

    private final OllamaClient ollamaClient;

    public EmbeddingService(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public float[] embed(String text) {
        if (!ollamaClient.isAvailable()) {
            log.warn("Ollama not available, cannot generate embedding");
            return null;
        }
        try {
            float[] embedding = ollamaClient.embed(text);
            if (embedding == null || embedding.length == 0) {
                log.warn("Embedding generation returned null or empty array");
                return null;
            }
            log.debug("Generated embedding of dimension: {}", embedding.length);
            return embedding;
        } catch (Exception e) {
            log.error("Failed to generate embedding: {}", e.getMessage());
            return null;
        }
    }

    public boolean isAvailable() {
        return ollamaClient.isAvailable();
    }
}