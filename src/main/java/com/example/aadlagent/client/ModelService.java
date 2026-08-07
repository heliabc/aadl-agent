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
    private final DoubaoClient doubaoClient;
    private final ChatGptClient chatGptClient;
    private final GeminiClient geminiClient;

    public ModelService(OllamaClient ollamaClient,
                        DeepSeekClient deepSeekClient,
                        DoubaoClient doubaoClient,
                        ChatGptClient chatGptClient,
                        GeminiClient geminiClient) {
        this.ollamaClient = ollamaClient;
        this.deepSeekClient = deepSeekClient;
        this.doubaoClient = doubaoClient;
        this.chatGptClient = chatGptClient;
        this.geminiClient = geminiClient;
    }

    public LlmClient getClient(ModelType modelType) {
        if (modelType == null) {
            return ollamaClient;
        }
        switch (modelType) {
            case DEEPSEEK:
                return deepSeekClient;
            case DOUBAO:
                return doubaoClient;
            case CHATGPT:
                return chatGptClient;
            case GEMINI:
                return geminiClient;
            case OLLAMA:
            default:
                return ollamaClient;
        }
    }

    public Map<String, Object> getModelStatus() {
        Map<String, Object> status = new HashMap<>();

        // Ollama（本地）
        Map<String, Object> ollamaStatus = new HashMap<>();
        ollamaStatus.put("type", "ollama");
        ollamaStatus.put("model", ollamaClient.getModelName());
        ollamaStatus.put("available", ollamaClient.isAvailable());
        ollamaStatus.put("displayName", "Ollama (本地)");
        status.put("ollama", ollamaStatus);

        // DeepSeek
        Map<String, Object> deepSeekStatus = new HashMap<>();
        deepSeekStatus.put("type", "deepseek");
        deepSeekStatus.put("model", deepSeekClient.getModelName());
        deepSeekStatus.put("available", deepSeekClient.isAvailable());
        deepSeekStatus.put("displayName", "DeepSeek");
        status.put("deepseek", deepSeekStatus);

        // 豆包（Doubao）
        Map<String, Object> doubaoStatus = new HashMap<>();
        doubaoStatus.put("type", "doubao");
        doubaoStatus.put("model", doubaoClient.getModelName());
        doubaoStatus.put("available", doubaoClient.isAvailable());
        doubaoStatus.put("displayName", "豆包（火山引擎）");
        status.put("doubao", doubaoStatus);

        // ChatGPT
        Map<String, Object> chatGptStatus = new HashMap<>();
        chatGptStatus.put("type", "chatgpt");
        chatGptStatus.put("model", chatGptClient.getModelName());
        chatGptStatus.put("available", chatGptClient.isAvailable());
        chatGptStatus.put("displayName", "ChatGPT (OpenAI)");
        status.put("chatgpt", chatGptStatus);

        // Gemini
        Map<String, Object> geminiStatus = new HashMap<>();
        geminiStatus.put("type", "gemini");
        geminiStatus.put("model", geminiClient.getModelName());
        geminiStatus.put("available", geminiClient.isAvailable());
        geminiStatus.put("displayName", "Gemini (Google)");
        status.put("gemini", geminiStatus);

        return status;
    }

    /**
     * 测试指定模型的API连接
     */
    public Map<String, Object> testConnection(ModelType modelType) {
        Map<String, Object> result = new HashMap<>();
        LlmClient client = getClient(modelType);
        result.put("model", modelType.name());
        result.put("displayName", getDisplayName(modelType));
        result.put("configured", client.isAvailable());
        
        if (!client.isAvailable()) {
            result.put("success", false);
            result.put("message", "模型未配置（API Key或基础URL缺失）");
            return result;
        }
        
        try {
            boolean connected = client.testConnection();
            result.put("success", connected);
            result.put("message", connected ? "连接成功" : "连接失败：API返回空响应");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接异常: " + e.getMessage());
        }
        return result;
    }

    private String getDisplayName(ModelType modelType) {
        switch (modelType) {
            case DEEPSEEK: return "DeepSeek";
            case DOUBAO: return "豆包（火山引擎）";
            case CHATGPT: return "ChatGPT (OpenAI)";
            case GEMINI: return "Gemini (Google)";
            case OLLAMA:
            default: return "Ollama (本地)";
        }
    }
}
