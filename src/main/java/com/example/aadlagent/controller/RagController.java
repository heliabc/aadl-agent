
package com.example.aadlagent.controller;

import com.example.aadlagent.rag.RagService;
import com.example.aadlagent.rag.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam String query) {
        log.info("Received RAG search request: {}", query);

        SearchResult result = ragService.search(query);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("query", query);
        response.put("rewrittenQuery", result.getRewrittenQuery());
        response.put("searchTime", result.getSearchTime());
        response.put("results", result.getDocuments());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> getContext(@RequestParam String query) {
        log.info("Received RAG context request: {}", query);

        String context = ragService.getEnhancedContext(query);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("query", query);
        response.put("context", context);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "RagService");
        return ResponseEntity.ok(response);
    }
}
