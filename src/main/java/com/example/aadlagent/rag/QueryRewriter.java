package com.example.aadlagent.rag;

import com.example.aadlagent.client.OllamaClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class QueryRewriter {

    private final OllamaClient ollamaClient;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 200;

    // AADL领域专用改写Prompt
    private static final String REWRITE_PROMPT = """
你是AADL嵌入式实时系统专业查询重构助手，仅面向AADL建模、实时调度、系统需求知识库优化检索语句。
规则：
1. 统一标准化术语：口语词汇替换为AADL标准名词（Processor、RealTimeProcess、Scheduler、InterruptHandler、REQ-编号等）
2. 补全模糊语义，消除指代不明，完善实时调度、抢占优先级、时序约束相关描述
3. 同义词扩展：任务调度、实时进程、硬件处理器、需求约束等领域同义词补充
4. 不生成多余解释，仅输出一条优化后的检索查询文本

用户原始查询：{}
仅输出改写后的单行查询，无任何额外文字、序号、换行。
""";

    // AADL领域专用拆分Prompt
    private static final String DECOMPOSE_PROMPT = """
你是AADL嵌入式系统查询拆分助手，将用户复杂提问拆分为2~4条独立检索子查询，每条只包含单一AADL/实时调度意图。
约束：
1. 统一使用AADL标准专业词汇、REQ需求编号
2. 每条子查询简短，适合向量相似度检索
3. 仅每行输出一条子查询，禁止空行、注释、序号、解释文本

原始查询：{}
""";

    public QueryRewriter(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    /**
     * 查询优化改写（带完整重试降级）
     */
    public String rewrite(String query) {
        if (!StringUtils.hasText(query)) {
            log.warn("Input query is blank, return original");
            return query;
        }
        String trimmedQuery = query.trim();
        log.info("Start rewrite raw query: {}", trimmedQuery);

        // Ollama离线直接降级
        if (!ollamaClient.isAvailable()) {
            log.warn("Ollama unavailable, skip rewrite, use raw query");
            return trimmedQuery;
        }

        String prompt = String.format(REWRITE_PROMPT, trimmedQuery);
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String rewritten = ollamaClient.chat(prompt, 0.3, 512);
                if (StringUtils.hasText(rewritten)) {
                    String result = rewritten.trim();
                    log.info("Rewrite success, attempt {}, result: {}", attempt, result);
                    return result;
                }
                log.warn("Rewrite empty response, attempt {}/{}", attempt, MAX_RETRIES);
            } catch (Exception e) {
                log.warn("Rewrite LLM call error attempt {}/{} err={}", attempt, MAX_RETRIES, e.getMessage());
            }
            // 重试休眠
            if (attempt < MAX_RETRIES) {
                sleepSafe(RETRY_DELAY_MS);
            }
        }
        log.warn("Rewrite all {} retries failed, fallback raw query", MAX_RETRIES);
        return trimmedQuery;
    }

    /**
     * 多意图查询拆分（增加重试、过滤空行、领域Prompt）
     */
    public String[] decompose(String query) {
        if (!StringUtils.hasText(query)) {
            return new String[]{query};
        }
        String trimmedQuery = query.trim();
        log.info("Start decompose raw query: {}", trimmedQuery);

        // Ollama离线降级：不拆分，直接返回原句数组
        if (!ollamaClient.isAvailable()) {
            log.warn("Ollama unavailable, skip decompose");
            return new String[]{trimmedQuery};
        }

        String prompt = String.format(DECOMPOSE_PROMPT, trimmedQuery);
        String decomposedText = null;

        // 和rewrite保持一致重试逻辑
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String resp = ollamaClient.chat(prompt, 0.3, 512);
                if (StringUtils.hasText(resp)) {
                    decomposedText = resp;
                    break;
                }
                log.warn("Decompose empty response attempt {}/{}", attempt, MAX_RETRIES);
            } catch (Exception e) {
                log.warn("Decompose LLM error attempt {}/{} err={}", attempt, MAX_RETRIES, e.getMessage());
            }
            if (attempt < MAX_RETRIES) {
                sleepSafe(RETRY_DELAY_MS);
            }
        }

        // LLM全部失败，降级返回原句
        if (!StringUtils.hasText(decomposedText)) {
            log.warn("Decompose all retries failed, fallback single query");
            return new String[]{trimmedQuery};
        }

        // 分割并过滤空行、空白字符串，防止空向量
        List<String> subQueries = Arrays.stream(decomposedText.split("\\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        // 过滤后无有效子查询，降级原句
        if (subQueries.isEmpty()) {
            return new String[]{trimmedQuery};
        }
        return subQueries.toArray(new String[0]);
    }

    /**
     * 安全休眠，屏蔽中断异常
     */
    private void sleepSafe(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Thread sleep interrupted");
        }
    }
}