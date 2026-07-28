package com.example.aadlagent.client;

public interface LlmClient {

    String chat(String prompt, Double temperature, Integer maxTokens);

    /**
     * 使用指定模型名称进行对话
     * @param modelName 模型名称，为null或空时使用默认模型
     */
    String chat(String prompt, Double temperature, Integer maxTokens, String modelName);

    float[] embed(String text);

    boolean isAvailable();

    String getModelName();

    /**
     * 检查指定模型是否可用
     */
    boolean checkModel(String modelName);
}
