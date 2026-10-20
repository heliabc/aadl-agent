
package com.example.aadlagent.rag;

import com.example.aadlagent.client.OllamaClient;
import com.example.aadlagent.rag.model.Document;
import com.example.aadlagent.config.RagConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.*;

@Slf4j
@Component
public class Reranker {

    private final OllamaClient ollamaClient;
    private final RagConfig ragConfig;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public Reranker(OllamaClient ollamaClient, RagConfig ragConfig) {
        this.ollamaClient = ollamaClient;
        this.ragConfig = ragConfig;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();
    }

    public List<Document> rerank(String query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("Reranking {} documents for query: {}", documents.size(), query);

        // 优先使用 cross-encoder 重排序服务
        List<Document> result = rerankWithCrossEncoder(query, documents);
        if (result != null && !result.isEmpty()) {
            return result;
        }

        // 降级：使用 LLM 提示词重排序
        log.warn("Cross-encoder reranker unavailable, falling back to LLM-based reranking");
        return rerankWithLLM(query, documents);
    }

    // ==================== Cross-Encoder 重排序 ====================

    private List<Document> rerankWithCrossEncoder(String query, List<Document> documents) {
        String endpoint = ragConfig.getRerankerEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            log.debug("Cross-encoder reranker endpoint not configured, skipping");
            return null;
        }

        try {
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("query", query);

            List<String> docContents = new ArrayList<>();
            for (Document doc : documents) {
                docContents.add(doc.getContent());
            }
            requestBody.put("documents", docContents);
            requestBody.put("top_k", ragConfig.getRerankTopK());
            requestBody.put("return_documents", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint + "/rerank",
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Cross-encoder reranker returned non-200: {}", response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                log.warn("Cross-encoder reranker response missing results array");
                return null;
            }

            // 按排序结果重新组装文档列表
            List<Document> reranked = new ArrayList<>();
            for (JsonNode item : results) {
                int idx = item.get("index").asInt();
                double score = item.get("relevance_score").asDouble();
                if (idx >= 0 && idx < documents.size()) {
                    Document doc = documents.get(idx);
                    doc.setScore(score);
                    reranked.add(doc);
                }
            }

            log.info("Cross-encoder reranking completed, returned {} documents (took {}ms)",
                    reranked.size(), root.path("took_ms").asDouble(0));

            return reranked;

        } catch (Exception e) {
            log.warn("Cross-encoder reranker failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== LLM 提示词重排序（降级兜底） ====================

    private List<Document> rerankWithLLM(String query, List<Document> documents) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("根据查询对文档排序，只输出JSON。\n");
        promptBuilder.append("查询：").append(query).append("\n\n");
        promptBuilder.append("文档：\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            // 截断文档内容，避免 prompt 过长
            String content = doc.getContent();
            if (content.length() > 150) {
                content = content.substring(0, 150) + "...";
            }
            promptBuilder.append(String.format("[%d] %s\n", i, content));
        }

        promptBuilder.append("\n只输出JSON：{\"rank\": [0,2,1]}\n数字是文档编号，按相关度从高到低。");

        String prompt = promptBuilder.toString();
        String response = ollamaClient.chat(prompt, 0.0, 512);

        if (response == null || response.trim().isEmpty()) {
            log.warn("LLM reranking empty response, returning original order");
            return truncateTopK(documents);
        }

        List<Integer> ranks = parseRankingJson(response, documents.size());

        if (ranks.isEmpty()) {
            log.warn("Failed to parse ranking, returning original order");
            return truncateTopK(documents);
        }

        List<Document> reranked = new ArrayList<>();
        Set<Integer> addedIndices = new HashSet<>();

        for (Integer rank : ranks) {
            int idx = rank;
            if (idx >= 0 && idx < documents.size() && !addedIndices.contains(idx)) {
                reranked.add(documents.get(idx));
                addedIndices.add(idx);
            }
        }

        for (int i = 0; i < documents.size(); i++) {
            if (!addedIndices.contains(i)) {
                reranked.add(documents.get(i));
            }
        }

        reranked = truncateTopK(reranked);
        log.info("LLM reranking completed, returned {} documents", reranked.size());
        return reranked;
    }

    private List<Document> truncateTopK(List<Document> documents) {
        int topK = ragConfig.getRerankTopK();
        if (documents.size() > topK) {
            return documents.subList(0, topK);
        }
        return documents;
    }

    /**
     * 从 LLM 输出中解析 JSON 格式的 rank 数组。
     * 支持 {"rank": [...]}、纯数组、以及从废话中提取 JSON。
     * 解析失败返回空列表。
     */
    private List<Integer> parseRankingJson(String response, int docCount) {
        List<Integer> ranks = new ArrayList<>();
        if (response == null || response.trim().isEmpty()) {
            return ranks;
        }
        String text = response.trim();

        // 1. 尝试直接解析
        try {
            JsonNode root = objectMapper.readTree(text);
            List<Integer> result = extractRankArray(root, docCount);
            if (result != null) return result;
        } catch (Exception ignored) {}

        // 2. 找第一个 { ... }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            try {
                JsonNode root = objectMapper.readTree(text.substring(firstBrace, lastBrace + 1));
                List<Integer> result = extractRankArray(root, docCount);
                if (result != null) return result;
            } catch (Exception ignored) {}
        }

        // 3. 找第一个 [ ... ]
        int firstBracket = text.indexOf('[');
        int lastBracket = text.lastIndexOf(']');
        if (firstBracket >= 0 && lastBracket > firstBracket) {
            try {
                JsonNode root = objectMapper.readTree(text.substring(firstBracket, lastBracket + 1));
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        if (item.isInt() || item.canConvertToInt()) {
                            int v = item.asInt();
                            if (v >= 0 && v < docCount) ranks.add(v);
                        }
                    }
                    if (!ranks.isEmpty()) return ranks;
                }
            } catch (Exception ignored) {}
        }

        return ranks;
    }

    private List<Integer> extractRankArray(JsonNode node, int docCount) {
        if (node == null) return null;
        JsonNode rankNode = node.has("rank") ? node.get("rank") : null;
        if (rankNode == null || !rankNode.isArray()) {
            if (node.isArray()) {
                rankNode = node;
            } else {
                return null;
            }
        }
        List<Integer> ranks = new ArrayList<>();
        for (JsonNode item : rankNode) {
            if (item.isInt() || item.canConvertToInt()) {
                int v = item.asInt();
                if (v >= 0 && v < docCount) ranks.add(v);
            }
        }
        return ranks.isEmpty() ? null : ranks;
    }
}
