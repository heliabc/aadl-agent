
package com.example.aadlagent.rag;

import com.example.aadlagent.client.OllamaClient;
import com.example.aadlagent.rag.model.Document;
import com.example.aadlagent.config.RagConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class Reranker {

    private final OllamaClient ollamaClient;
    private final RagConfig ragConfig;
    private final ObjectMapper objectMapper;

    public Reranker(OllamaClient ollamaClient, RagConfig ragConfig) {
        this.ollamaClient = ollamaClient;
        this.ragConfig = ragConfig;
        this.objectMapper = new ObjectMapper();
    }

    public List<Document> rerank(String query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("Reranking {} documents for query: {}", documents.size(), query);

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
            log.warn("Reranking failed, returning original order");
            return documents;
        }

        List<Integer> ranks = parseRanking(response);

        if (ranks.isEmpty()) {
            log.warn("Failed to parse ranking, returning original order");
            return documents;
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

        int topK = ragConfig.getRerankTopK();
        if (reranked.size() > topK) {
            reranked = reranked.subList(0, topK);
        }

        log.info("Reranking completed, returned {} documents", reranked.size());

        return reranked;
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
