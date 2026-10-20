
package com.example.aadlagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagConfig {

    /**
     * 是否启用 RAG 检索增强。
     * true  = 正常执行 RAG（向量检索+关键词检索+融合+重排）
     * false = 完全跳过 RAG，getEnhancedContext 返回空字符串，
     *         模型直接基于 prompt 生成，不注入任何知识库内容。
     */
    private boolean enabled = true;

    private int topK = 5;

    private int rerankTopK = 3;

    private int rrfK = 60;

    /**
     * Cross-encoder 重排序服务地址（如 http://localhost:8081）
     * 为空则跳过 cross-encoder，直接使用 LLM 降级方案
     */
    private String rerankerEndpoint = "";
}
