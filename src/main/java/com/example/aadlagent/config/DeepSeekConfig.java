package com.example.aadlagent.config;

import lombok.Data;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
public class DeepSeekConfig {

    private String apiKey = "sk-70720c5e725345c1b6a287a4fe5cde6b";

    private String baseUrl = "https://api.deepseek.com";

    private String chatModel = "deepseek-v4-flash";

    private String embeddingModel = "deepseek-embedding";

    private int timeout = 600000;
}