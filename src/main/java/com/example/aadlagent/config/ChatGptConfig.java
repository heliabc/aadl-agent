package com.example.aadlagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI ChatGPT API 配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "chatgpt")
public class ChatGptConfig extends BaseOpenAiConfig {

    public ChatGptConfig() {
        // 默认值：API Key 留空（用户后续填写）
        setApiKey("");
        setBaseUrl("https://api.openai.com/v1");
        setChatModel("gpt-4o-mini");
        setTimeout(600000);
    }
}
