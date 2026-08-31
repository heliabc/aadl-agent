
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
        promptBuilder.append("请根据以下查询语句，对提供的文档进行相关性排序：\n");
        promptBuilder.append("查询：").append(query).append("\n\n");
        promptBuilder.append("文档列表：\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            promptBuilder.append(String.format("%d. %s\n", i + 1, doc.getContent()));
        }

        promptBuilder.append("\n请输出排序后的文档序号，按相关性从高到低排列，用逗号分隔。例如：1,3,2,4");

        String prompt = promptBuilder.toString();
        String response = ollamaClient.chat(prompt, 0.0, 1024);

        if (response == null || response.trim().isEmpty()) {
            log.warn("LLM reranking failed, returning original order");
            return truncateTopK(documents);
        }

        List<Integer> ranks = parseRanking(response);

        if (ranks.isEmpty()) {
            log.warn("Failed to parse ranking, returning original order");
            return truncateTopK(documents);
        }

        List<Document> reranked = new ArrayList<>();
        Set<Integer> addedIndices = new HashSet<>();

        for (Integer rank : ranks) {
            int idx = rank - 1;
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

    private List<Integer> parseRanking(String response) {
        List<Integer> ranks = new ArrayList<>();

        try {
            String cleanResponse = response.replaceAll("[^0-9,]", "");
            String[] parts = cleanResponse.split(",");

            for (String part : parts) {
                part = part.trim();
                if (!part.isEmpty()) {
                    ranks.add(Integer.parseInt(part));
                }
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse ranking response: {}", response);
        }

        return ranks;
    }
}
