
package com.example.aadlagent.rag;

import com.example.aadlagent.client.OllamaClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class QueryRewriter {

    private final OllamaClient ollamaClient;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 200;

    private static final String REWRITE_PROMPT = """
你是一个专业的查询重构助手。你的任务是优化用户的查询语句，使其更适合进行信息检索。

请根据以下原则对查询进行重构：
1. 扩展同义词：将查询中的关键词替换为更通用或更具体的同义词
2. 明确意图：将模糊的查询转换为明确的问题
3. 添加上下文：根据查询主题添加相关的背景信息
4. 分解复杂查询：将复杂的多意图查询分解为多个独立的子查询

输入：{}

请输出重构后的查询语句，不要包含任何额外的说明文字。
""";

    public QueryRewriter(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public String rewrite(String query) {
        if (query == null || query.trim().isEmpty()) {
            log.warn("Input query is null or empty, returning as-is");
            return query;
        }

        String trimmedQuery = query.trim();
        log.info("Rewriting query: {}", trimmedQuery);

        if (!ollamaClient.isAvailable()) {
            log.warn("Ollama not available for query rewriting, returning original query");
            return trimmedQuery;
        }

        String prompt = String.format(REWRITE_PROMPT, trimmedQuery);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String rewritten = ollamaClient.chat(prompt, 0.3, 512);

                if (rewritten != null && !rewritten.trim().isEmpty()) {
                    String result = rewritten.trim();
                    log.info("Rewritten query (attempt {}): {}", attempt, result);
                    return result;
                }

                log.warn("Query rewriting returned empty result (attempt {}/{})", attempt, MAX_RETRIES);

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

            } catch (Exception e) {
                log.warn("Query rewriting failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.warn("Query rewriting failed after {} attempts, returning original query", MAX_RETRIES);
        return trimmedQuery;
    }

    public String[] decompose(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new String[]{query};
        }

        String trimmedQuery = query.trim();
        log.info("Decomposing query: {}", trimmedQuery);

        String prompt = String.format("""
你是一个专业的查询分解助手。请将以下查询分解为2-4个更具体的子查询，每个子查询针对一个独立的意图。

输入：%s

请输出子查询列表，每行一个，不要包含序号。
""", trimmedQuery);

        String decomposed = ollamaClient.chat(prompt, 0.3, 512);

        if (decomposed != null && !decomposed.trim().isEmpty()) {
            return decomposed.trim().split("\\n");
        }

        return new String[]{trimmedQuery};
    }
}
