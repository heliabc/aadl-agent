package com.example.aadlagent.client;

public interface LlmClient {

    String chat(String prompt, Double temperature, Integer maxTokens);

    float[] embed(String text);

    boolean isAvailable();

    String getModelName();
}