package com.example.aadlagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ollama")
public class OllamaConfig {

    private String baseUrl = "http://localhost:11434";

    private String chatModel = "qwen3:8B";

    private String embeddingModel = "qwen3-embedding:latest";

    private int timeout = 300000;
}
