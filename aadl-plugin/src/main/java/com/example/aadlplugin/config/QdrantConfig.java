package com.example.aadlplugin.config;

import java.util.Arrays;
import java.util.List;

public class QdrantConfig {

    private boolean useTls = false;
    private String host = "localhost";
    private int port = 6333;
    private String apiKey;
    private int embeddingSize = 4096;
    private List<String> collections = Arrays.asList("requirement", "architecture", "module", "aadl");
    private int maxRetries = 3;
    private long retryDelayMs = 200;

    public QdrantConfig() {
    }

    public boolean isUseTls() {
        return useTls;
    }

    public void setUseTls(boolean useTls) {
        this.useTls = useTls;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getEmbeddingSize() {
        return embeddingSize;
    }

    public void setEmbeddingSize(int embeddingSize) {
        this.embeddingSize = embeddingSize;
    }

    public List<String> getCollections() {
        return collections;
    }

    public void setCollections(List<String> collections) {
        this.collections = collections;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }
}
