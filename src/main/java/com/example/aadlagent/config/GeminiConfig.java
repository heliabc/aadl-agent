package com.example.aadlagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Google Gemini API 配置。
 * Gemini API 使用 REST 格式，端点为 https://generativelanguage.googleapis.com/v1/models
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "gemini")
public class GeminiConfig {

    /** API Key，为空则该模型不可用 */
    private String apiKey = "";

    /** API 基础地址 */
    private String baseUrl = "https://generativelanguage.googleapis.com/v1";

    /** 对话模型名称 */
    private String chatModel = "gemini-2.0-flash";

    /** 请求超时时间（毫秒） */
    private int timeout = 600000;
}
