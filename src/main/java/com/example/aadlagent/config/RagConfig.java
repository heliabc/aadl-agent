
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

    /**
     * 按 agentType 粒度禁用 RAG 的列表。
     * 例如 ["requirement", "architecture", "module"] 表示这三个 agent 不走 RAG。
     * 列表为空时，所有 agent 都使用 RAG（前提是 rag.enabled=true）。
     * 用途：某些 agent 的 prompt 已经包含足够的规则和示例，不需要 RAG 补充，
     *       禁用后可以节省检索时间并避免引入噪声。
     */
    private java.util.List<String> disabledAgents = new java.util.ArrayList<>();

    public boolean isAgentDisabled(String agentType) {
        if (agentType == null || disabledAgents == null || disabledAgents.isEmpty()) {
            return false;
        }
        return disabledAgents.contains(agentType);
    }

    private int topK = 5;

    private int rerankTopK = 3;

    private int rrfK = 60;

    /**
     * Cross-encoder 重排序服务地址（如 http://localhost:8081）
     * 为空则跳过 cross-encoder，直接使用 LLM 降级方案
     */
    private String rerankerEndpoint = "";
}
