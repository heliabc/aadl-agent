package com.example.aadlagent.config;

import com.example.aadlagent.client.ModelType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "agent.rewrite")
public class RewriteConfig {
    
    /**
     * 模型类型：OLLAMA 或 DEEPSEEK
     */
    private ModelType modelType = ModelType.OLLAMA;
    
    /**
     * Ollama模型名称（当modelType为OLLAMA时生效）
     * 如果为空，则使用ollama.chat-model配置的默认模型
     */
    private String ollamaModelName;
    
    /**
     * 温度参数
     */
    private double temperature = 0.3;
    
    /**
     * 最大token数
     */
    private int maxTokens = 512;
}
