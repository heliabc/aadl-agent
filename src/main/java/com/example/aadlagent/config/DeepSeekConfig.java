package com.example.aadlagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek API 配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekConfig extends BaseOpenAiConfig {

    public DeepSeekConfig() {
        setApiKey("");
        setBaseUrl("https://api.deepseek.com");
        setChatModel("deepseek-v4-flash");
        setTimeout(600000);
    }
}
