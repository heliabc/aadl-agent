package com.example.aadlplugin.controller;

import com.example.aadlplugin.rag.RagService;
import com.example.aadlplugin.rag.model.SearchResult;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class RagController {

    private static final Logger log = Logger.getLogger(RagController.class.getName());

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    public Map<String, Object> search(String query) {
        log.info("Received RAG search request: " + query);

        SearchResult result = ragService.search(query);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("query", query);
        response.put("rewrittenQuery", result.getRewrittenQuery());
        response.put("searchTime", result.getSearchTime());
        response.put("results", result.getDocuments());

        return response;
    }

    public Map<String, Object> getContext(String query) {
        log.info("Received RAG context request: " + query);

        String context = ragService.getEnhancedContext(query);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("query", query);
        response.put("context", context);

        return response;
    }

    public Map<String, Object> getContext(String query, String agentType) {
        log.info("Received RAG context request: " + query + ", agentType: " + agentType);

        String context = ragService.getEnhancedContext(query, agentType);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("query", query);
        response.put("agentType", agentType);
        response.put("context", context);

        return response;
    }

    public Map<String, Object> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "RagService");
        return response;
    }
}