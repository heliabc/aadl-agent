
package com.example.aadlagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagConfig {

    private int topK = 5;

    private int rerankTopK = 3;

    private int rrfK = 60;

    /**
     * Cross-encoder 重排序服务地址（如 http://localhost:8081）
     * 为空则跳过 cross-encoder，直接使用 LLM 降级方案
     */
    private String rerankerEndpoint = "";
}
