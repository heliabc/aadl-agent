
package com.example.aadlagent.rag;

import com.example.aadlagent.client.OllamaClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class QueryRewriter {

    private final OllamaClient ollamaClient;

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
            return query;
        }

        log.info("Rewriting query: {}", query);

        String prompt = String.format(REWRITE_PROMPT, query);
        String rewritten = ollamaClient.chat(prompt, 0.3, 512);

        if (rewritten != null && !rewritten.trim().isEmpty()) {
            log.info("Rewritten query: {}", rewritten);
            return rewritten.trim();
        }

        return query;
    }

    public String[] decompose(String query) {
        log.info("Decomposing query: {}", query);

        String prompt = String.format("""
你是一个专业的查询分解助手。请将以下查询分解为2-4个更具体的子查询，每个子查询针对一个独立的意图。

输入：%s

请输出子查询列表，每行一个，不要包含序号。
""", query);

        String decomposed = ollamaClient.chat(prompt, 0.3, 512);

        if (decomposed != null && !decomposed.trim().isEmpty()) {
            return decomposed.trim().split("\\n");
        }

        return new String[]{query};
    }
}
