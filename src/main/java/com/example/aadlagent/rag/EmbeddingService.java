package com.example.aadlagent.rag;

import com.example.aadlagent.client.OllamaClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmbeddingService {

    private final OllamaClient ollamaClient;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 200;

    public EmbeddingService(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            log.warn("Input text is empty, cannot generate embedding");
            return null;
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                float[] embedding = ollamaClient.embed(text);
                if (embedding != null && embedding.length > 0) {
                    log.debug("Generated embedding of dimension: {} (attempt {})", embedding.length, attempt);
                    return embedding;
                }
                
                log.warn("Embedding generation returned null or empty array (attempt {}/{})", attempt, MAX_RETRIES);
                
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
            } catch (Exception e) {
                log.warn("Failed to generate embedding (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        log.error("Embedding generation failed after {} attempts for text: {}", MAX_RETRIES, text.length() > 50 ? text.substring(0, 50) + "..." : text);
        return null;
    }

    public boolean isAvailable() {
        return ollamaClient.isAvailable();
    }
}