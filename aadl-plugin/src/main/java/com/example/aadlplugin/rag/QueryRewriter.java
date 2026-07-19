package com.example.aadlplugin.rag;

import com.example.aadlplugin.client.OllamaClient;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class QueryRewriter {
    
    private static final Logger log = Logger.getLogger(QueryRewriter.class.getName());

    private final OllamaClient ollamaClient;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 200;

    private static final String REWRITE_PROMPT = "你是AADL嵌入式实时系统专业查询重构助手，仅面向AADL建模、实时调度、系统需求知识库优化检索语句。\n" +
            "规则：\n" +
            "1. 统一标准化术语：口语词汇替换为AADL标准名词（Processor、RealTimeProcess、Scheduler、InterruptHandler、REQ-编号等）\n" +
            "2. 补全模糊语义，消除指代不明，完善实时调度、抢占优先级、时序约束相关描述\n" +
            "3. 同义词扩展：任务调度、实时进程、硬件处理器、需求约束等领域同义词补充\n" +
            "4. 不生成多余解释，仅输出一条优化后的检索查询文本\n" +
            "\n" +
            "用户原始查询：{}\n" +
            "仅输出改写后的单行查询，无任何额外文字、序号、换行。\n";

    private static final String DECOMPOSE_PROMPT = "你是AADL嵌入式系统查询拆分助手，将用户复杂提问拆分为2~4条独立检索子查询，每条只包含单一AADL/实时调度意图。\n" +
            "约束：\n" +
            "1. 统一使用AADL标准专业词汇、REQ需求编号\n" +
            "2. 每条子查询简短，适合向量相似度检索\n" +
            "3. 仅每行输出一条子查询，禁止空行、注释、序号、解释文本\n" +
            "\n" +
            "原始查询：{}\n";

    public QueryRewriter(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public String rewrite(String query) {
        if (!hasText(query)) {
            log.warning("Input query is blank, return original");
            return query;
        }
        String trimmedQuery = query.trim();
        log.info(String.format("Start rewrite raw query: %s", trimmedQuery));

        if (!ollamaClient.isAvailable()) {
            log.warning("Ollama unavailable, skip rewrite, use raw query");
            return trimmedQuery;
        }

        String prompt = String.format(REWRITE_PROMPT, trimmedQuery);
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String rewritten = ollamaClient.chat(prompt, 0.3, 512);
                if (hasText(rewritten)) {
                    String result = rewritten.trim();
                    log.info(String.format("Rewrite success, attempt %d, result: %s", attempt, result));
                    return result;
                }
                log.warning(String.format("Rewrite empty response, attempt %d/%d", attempt, MAX_RETRIES));
            } catch (Exception e) {
                log.warning(String.format("Rewrite LLM call error attempt %d/%d err=%s", attempt, MAX_RETRIES, e.getMessage()));
            }
            if (attempt < MAX_RETRIES) {
                sleepSafe(RETRY_DELAY_MS);
            }
        }
        log.warning(String.format("Rewrite all %d retries failed, fallback raw query", MAX_RETRIES));
        return trimmedQuery;
    }

    public String[] decompose(String query) {
        if (!hasText(query)) {
            return new String[]{query};
        }
        String trimmedQuery = query.trim();
        log.info(String.format("Start decompose raw query: %s", trimmedQuery));

        if (!ollamaClient.isAvailable()) {
            log.warning("Ollama unavailable, skip decompose");
            return new String[]{trimmedQuery};
        }

        String prompt = String.format(DECOMPOSE_PROMPT, trimmedQuery);
        String decomposedText = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String resp = ollamaClient.chat(prompt, 0.3, 512);
                if (hasText(resp)) {
                    decomposedText = resp;
                    break;
                }
                log.warning(String.format("Decompose empty response attempt %d/%d", attempt, MAX_RETRIES));
            } catch (Exception e) {
                log.warning(String.format("Decompose LLM error attempt %d/%d err=%s", attempt, MAX_RETRIES, e.getMessage()));
            }
            if (attempt < MAX_RETRIES) {
                sleepSafe(RETRY_DELAY_MS);
            }
        }

        if (!hasText(decomposedText)) {
            log.warning("Decompose all retries failed, fallback single query");
            return new String[]{trimmedQuery};
        }

        List<String> subQueries = Arrays.stream(decomposedText.split("\\n"))
                .map(String::trim)
                .filter(this::hasText)
                .collect(Collectors.toList());

        if (subQueries.isEmpty()) {
            return new String[]{trimmedQuery};
        }
        return subQueries.toArray(new String[0]);
    }

    private void sleepSafe(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warning("Thread sleep interrupted");
        }
    }

    private boolean hasText(String str) {
        return str != null && !str.trim().isEmpty();
    }
}
