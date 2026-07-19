package com.example.aadlplugin.client;

public interface LlmClient {

    String chat(String prompt, Double temperature, Integer maxTokens);

    float[] embed(String text);

    boolean isAvailable();

    String getModelName();
}