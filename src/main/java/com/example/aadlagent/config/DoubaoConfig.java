package com.example.aadlagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 豆包（字节跳动火山引擎）API 配置。
 * 豆包 API 兼容 OpenAI 格式，端点为 https://ark.cn-beijing.volces.com/api/v3
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "doubao")
public class DoubaoConfig extends BaseOpenAiConfig {

    public DoubaoConfig() {
        // 默认值：API Key 留空（用户后续填写）
        setApiKey("");
        setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        setChatModel("");
        setEmbeddingModel("");
        setTimeout(600000);
    }
}
