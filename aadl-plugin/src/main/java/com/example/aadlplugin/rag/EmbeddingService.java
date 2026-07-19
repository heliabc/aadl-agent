package com.example.aadlplugin.rag;

import com.example.aadlplugin.client.OllamaClient;
import java.util.logging.Logger;

public class EmbeddingService {
    
    private static final Logger log = Logger.getLogger(EmbeddingService.class.getName());

    private final OllamaClient ollamaClient;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 200;

    public EmbeddingService(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            log.warning("Input text is empty, cannot generate embedding");
            return null;
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                float[] embedding = ollamaClient.embed(text);
                if (embedding != null && embedding.length > 0) {
                    log.fine(String.format("Generated embedding of dimension: %d (attempt %d)", embedding.length, attempt));
                    return embedding;
                }
                
                log.warning(String.format("Embedding generation returned null or empty array (attempt %d/%d)", attempt, MAX_RETRIES));
                
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
            } catch (Exception e) {
                log.warning(String.format("Failed to generate embedding (attempt %d/%d): %s", attempt, MAX_RETRIES, e.getMessage()));
                
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
        
        log.severe(String.format("Embedding generation failed after %d attempts for text: %s", MAX_RETRIES, text.length() > 50 ? text.substring(0, 50) + "..." : text));
        return null;
    }

    public boolean isAvailable() {
        return ollamaClient.isAvailable();
    }
}
