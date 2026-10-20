package com.example.aadlagent.rag;

import com.example.aadlagent.config.QdrantConfig;
import com.example.aadlagent.rag.model.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Slf4j
@Service
public class QdrantVectorStore {

    private final QdrantConfig qdrantConfig;
    private final RestTemplate restTemplate;
    private volatile boolean available = false;

    public QdrantVectorStore(QdrantConfig qdrantConfig) {
        this.qdrantConfig = qdrantConfig;
        this.restTemplate = new RestTemplate();
    }

    @PostConstruct
    public void init() {
        boolean connectionOk = testConnection();
        
        if (!connectionOk) {
            log.warn("Qdrant not available, running in degraded mode");
            available = false;
            return;
        }
        
        try {
            printExistingCollections();
            
            for (String collection : qdrantConfig.getCollections()) {
                ensureCollectionExists(collection);
            }
            
            available = true;
            log.info("Qdrant vector store initialized successfully");
        } catch (Exception e) {
            log.warn("Qdrant initialization failed: {}", e.getMessage());
            available = false;
        }
    }

    private boolean testConnection() {
        try {
            String url = getBaseUrl() + "/collections";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Qdrant connection test successful");
                return true;
            } else {
                log.warn("Qdrant connection test failed: {}", response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            log.warn("Qdrant connection test failed: {}", e.getMessage());
            return false;
        }
    }

    private String getBaseUrl() {
        String scheme = qdrantConfig.isUseTls() ? "https" : "http";
        return scheme + "://" + qdrantConfig.getHost() + ":" + qdrantConfig.getPort();
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (qdrantConfig.getApiKey() != null && !qdrantConfig.getApiKey().isEmpty()) {
            headers.set("api-key", qdrantConfig.getApiKey());
        }
        return headers;
    }

    private void printExistingCollections() {
        try {
            List<String> collections = listCollections();
            log.info("Existing Qdrant collections: {}", collections);
        } catch (Exception e) {
            log.warn("Failed to list existing collections: {}", e.getMessage());
        }
    }

    public List<String> listCollections() {
        try {
            String url = getBaseUrl() + "/collections";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class, createHeaders());
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> result = (Map<String, Object>) response.getBody().get("result");
                if (result != null) {
                    List<Map<String, Object>> collections = (List<Map<String, Object>>) result.get("collections");
                    if (collections != null) {
                        return collections.stream()
                                .map(c -> c.get("name").toString())
                                .collect(java.util.stream.Collectors.toList());
                    }
                } else {
                    List<Map<String, Object>> collections = (List<Map<String, Object>>) response.getBody().get("collections");
                    if (collections != null) {
                        return collections.stream()
                                .map(c -> c.get("name").toString())
                                .collect(java.util.stream.Collectors.toList());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to list collections: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private boolean collectionExists(String collectionName) {
        return listCollections().contains(collectionName);
    }

    /**
     * 确保 collection 存在，不存在则创建
     * 供外部调用（如启动自检）
     */
    public void ensureCollection(String collectionName) {
        if (!available) {
            return;
        }
        ensureCollectionExists(collectionName);
    }

    private void ensureCollectionExists(String collectionName) {
        if (!collectionExists(collectionName)) {
            createCollection(collectionName);
        }
    }

    private void createCollection(String collectionName) {
        log.info("Creating Qdrant collection: {}", collectionName);
        
        try {
            String url = getBaseUrl() + "/collections/" + collectionName;
            
            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> vectors = new HashMap<>();
            vectors.put("size", qdrantConfig.getEmbeddingSize());
            vectors.put("distance", "Cosine");
            requestBody.put("vectors", vectors);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                waitForCollectionReady(collectionName);
                log.info("Qdrant collection '{}' created successfully", collectionName);
            } else {
                log.error("Failed to create collection {}: {}", collectionName, response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to create collection {}: {}", collectionName, e.getMessage());
        }
    }

    private void waitForCollectionReady(String collectionName) {
        int maxAttempts = 20;
        int delayMs = 100;
        
        for (int i = 0; i < maxAttempts; i++) {
            try {
                String url = getBaseUrl() + "/collections/" + collectionName;
                ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    // Qdrant 返回结构：{ result: { status: "green", ... } }
                    // status 是字符串（"green"/"yellow"/"red"），不是对象
                    Map<String, Object> result = (Map<String, Object>) response.getBody().get("result");
                    if (result != null) {
                        Object status = result.get("status");
                        if (status != null && "green".equalsIgnoreCase(status.toString())) {
                            log.info("Collection '{}' is ready", collectionName);
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Waiting for collection '{}' to be ready: {}", collectionName, e.getMessage());
            }
            
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        log.warn("Collection '{}' may not be fully ready after waiting", collectionName);
    }

    public boolean isAvailable() {
        return available;
    }

    public void upsert(String collectionName, Document document) {
        if (!available) {
            log.debug("Qdrant not available, skipping upsert");
            return;
        }

        try {
            ensureCollectionExists(collectionName);
            
            String url = getBaseUrl() + "/collections/" + collectionName + "/points";
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("points", Collections.singletonList(buildPoint(document)));
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("Upserted document to collection {}: {}", collectionName, document.getId());
            } else {
                log.error("Failed to upsert document to {}: {}", collectionName, response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to upsert document to {}: {}", collectionName, e.getMessage());
        }
    }

    public void upsertBatch(String collectionName, List<Document> documents) {
        if (!available) {
            log.debug("Qdrant not available, skipping batch upsert");
            return;
        }

        try {
            ensureCollectionExists(collectionName);
            
            String url = getBaseUrl() + "/collections/" + collectionName + "/points";
            
            Map<String, Object> requestBody = new HashMap<>();
            List<Map<String, Object>> points = new ArrayList<>();
            for (Document document : documents) {
                points.add(buildPoint(document));
            }
            requestBody.put("points", points);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Batch upserted {} documents to collection {}", documents.size(), collectionName);
            } else {
                log.error("Failed to batch upsert to {}: {}", collectionName, response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to batch upsert to {}: {}", collectionName, e.getMessage());
        }
    }

    private Map<String, Object> buildPoint(Document document) {
        Map<String, Object> point = new HashMap<>();
        
        String docId = document.getId();
        try {
            UUID.fromString(docId);
            point.put("id", docId);
        } catch (IllegalArgumentException e) {
            point.put("id", UUID.nameUUIDFromBytes(docId.getBytes()).toString());
        }
        
        List<Float> vectorList = new ArrayList<>(document.getEmbedding().length);
        for (float v : document.getEmbedding()) {
            vectorList.add(v);
        }
        point.put("vector", vectorList);
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", document.getContent() != null ? document.getContent() : "");
        payload.put("doc_id", document.getId() != null ? document.getId() : "");
        payload.put("title", document.getTitle() != null ? document.getTitle() : "");
        payload.put("source", document.getSource() != null ? document.getSource() : "");
        payload.put("category", document.getCategory() != null ? document.getCategory() : "");
        payload.put("agent_type", document.getAgentType() != null ? document.getAgentType() : "");
        payload.put("payload", document.getPayload() != null ? document.getPayload() : "");
        point.put("payload", payload);
        
        return point;
    }

    public void delete(String collectionName, String documentId) {
        if (!available) {
            log.debug("Qdrant not available, skipping delete");
            return;
        }

        try {
            String url = getBaseUrl() + "/collections/" + collectionName + "/points/delete";
            
            String docId = documentId;
            try {
                UUID.fromString(docId);
            } catch (IllegalArgumentException e) {
                docId = UUID.nameUUIDFromBytes(documentId.getBytes()).toString();
            }
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("points", Collections.singletonList(docId));
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Deleted document {} from {}", documentId, collectionName);
            } else {
                log.error("Failed to delete document from {}: {}", collectionName, response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to delete document from {}: {}", collectionName, e.getMessage());
        }
    }

    /**
     * 批量删除点。用于孤儿点清理：Qdrant 中存在但 JSON 中已不存在的文档。
     *
     * @param collectionName 集合名
     * @param documentIds    原始文档 id 列表（非 UUID 会自动哈希）
     */
    public void deleteBatch(String collectionName, List<String> documentIds) {
        if (!available || documentIds == null || documentIds.isEmpty()) {
            return;
        }

        try {
            String url = getBaseUrl() + "/collections/" + collectionName + "/points/delete";

            List<String> pointIds = new ArrayList<>(documentIds.size());
            for (String docId : documentIds) {
                try {
                    UUID.fromString(docId);
                    pointIds.add(docId);
                } catch (IllegalArgumentException e) {
                    pointIds.add(UUID.nameUUIDFromBytes(docId.getBytes()).toString());
                }
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("points", pointIds);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Batch deleted {} documents from {}", documentIds.size(), collectionName);
            } else {
                log.error("Failed to batch delete from {}: {}", collectionName, response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to batch delete from {}: {}", collectionName, e.getMessage());
        }
    }

    public List<Document> search(String collectionName, float[] queryVector, int topK) {
        if (!available) {
            log.debug("Qdrant not available, returning empty results");
            return new ArrayList<>();
        }

        if (queryVector == null || queryVector.length == 0) {
            log.warn("Query vector is null or empty, returning empty results for {}", collectionName);
            return new ArrayList<>();
        }

        try {
            ensureCollectionExists(collectionName);
            
            String url = getBaseUrl() + "/collections/" + collectionName + "/points/search";

            List<Float> vectorList = new ArrayList<>(queryVector.length);
            for (float v : queryVector) {
                vectorList.add(v);
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("vector", vectorList);
            requestBody.put("limit", topK);
            requestBody.put("with_payload", true);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> resultList = (List<Map<String, Object>>) response.getBody().get("result");
                
                if (resultList == null || resultList.isEmpty()) {
                    log.debug("Qdrant REST API returned empty results for collection: {}", collectionName);
                    return new ArrayList<>();
                }

                List<Document> documents = new ArrayList<>();
                
                for (Map<String, Object> point : resultList) {
                    Double score = (Double) point.get("score");
                    String id = point.get("id").toString();
                    Map<String, Object> payload = (Map<String, Object>) point.get("payload");
                    
                    Document doc = parsePayloadToDocument(id, score, payload);
                    if (doc != null) {
                        documents.add(doc);
                    }
                }
                
                log.debug("Qdrant REST API returned {} documents for collection: {}", documents.size(), collectionName);
                return documents;
            }
            
            log.warn("Qdrant REST API returned non-success status: {}", response.getStatusCode());
            
        } catch (Exception e) {
            log.error("Failed to search Qdrant via REST API for {}: {}", collectionName, e.getMessage());
        }
        
        return new ArrayList<>();
    }

    private Document parsePayloadToDocument(String id, Double score, Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        
        try {
            String content = payload.get("text") != null ? payload.get("text").toString() : 
                           (payload.get("content") != null ? payload.get("content").toString() : "");
            
            if (content.isEmpty()) {
                return null;
            }
            
            Document document = new Document();
            // 非 UUID 的文档 id 在写入 Qdrant 时会被哈希成点 id，这里从 payload 还原原始 id
            Object docId = payload.get("doc_id");
            if (docId != null && !docId.toString().isEmpty()) {
                document.setId(docId.toString());
            } else {
                document.setId(id);
            }
            document.setContent(content);
            document.setScore(score != null ? score.floatValue() : 0.0f);
            
            if (payload.containsKey("title")) {
                document.setTitle(payload.get("title").toString());
            }
            if (payload.containsKey("category")) {
                document.setCategory(payload.get("category").toString());
            }
            if (payload.containsKey("agent_type")) {
                document.setAgentType(payload.get("agent_type").toString());
            }
            if (payload.containsKey("source")) {
                document.setSource(payload.get("source").toString());
            }
            if (payload.containsKey("payload")) {
                document.setPayload(payload.get("payload").toString());
            }
            
            return document;
        } catch (Exception e) {
            log.warn("Failed to parse payload to document: {}", e.getMessage());
            return null;
        }
    }

    public List<Document> keywordSearch(String collectionName, String query, int topK) {
        if (!available) {
            log.debug("Qdrant not available, returning empty results");
            return new ArrayList<>();
        }
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // 获取集合中所有文档（81条量级，全量扫描没问题）
            List<Document> allDocs = getAllDocuments(collectionName);
            if (allDocs.isEmpty()) {
                log.warn("Keyword search: no documents found in collection {}", collectionName);
                return new ArrayList<>();
            }

            String[] keywords = query.toLowerCase().split("\\s+");
            List<Map.Entry<Document, Double>> scored = new ArrayList<>();

            for (Document doc : allDocs) {
                // 在 title + tags + content 中统计关键词命中数
                String searchText = (doc.getTitle() != null ? doc.getTitle() : "")
                        + " " + (doc.getTags() != null ? String.join(" ", doc.getTags()) : "")
                        + " " + (doc.getContent() != null ? doc.getContent() : "");
                String lower = searchText.toLowerCase();

                int matchCount = 0;
                for (String kw : keywords) {
                    if (kw.isEmpty()) continue;
                    if (lower.contains(kw)) {
                        matchCount++;
                    }
                }

                if (matchCount > 0) {
                    double score = (double) matchCount / keywords.length;
                    // title 命中加权：术语精确匹配 title 的文档更相关
                    if (doc.getTitle() != null) {
                        String titleLower = doc.getTitle().toLowerCase();
                        for (String kw : keywords) {
                            if (titleLower.equals(kw) || titleLower.contains(kw + "_") || titleLower.contains("_" + kw)) {
                                score += 0.3;  // title 命中加 0.3 分
                                break;
                            }
                        }
                    }
                    scored.add(new AbstractMap.SimpleEntry<>(doc, score));
                }
            }

            // 按分数降序排列
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            int limit = Math.min(topK, scored.size());
            List<Document> results = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                Document doc = scored.get(i).getKey();
                doc.setScore(scored.get(i).getValue());
                results.add(doc);
            }

            log.info("Qdrant keyword search: {} matches from {} docs (query: {})",
                    results.size(), allDocs.size(),
                    query.length() > 60 ? query.substring(0, 60) + "..." : query);

            return results;

        } catch (Exception e) {
            log.error("Qdrant keyword search failed for {}: {}", collectionName, e.getMessage());
            return new ArrayList<>();
        }
    }

    public long count(String collectionName) {
        if (!available) {
            return 0;
        }

        try {
            String url = getBaseUrl() + "/collections/" + collectionName + "/points/count";
            Map<String, Object> requestBody = new HashMap<>();
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> result = (Map<String, Object>) response.getBody().get("result");
                // Qdrant count 接口返回：{ result: { count: N } }
                if (result != null && result.get("count") != null) {
                    return ((Number) result.get("count")).longValue();
                }
            }
        } catch (Exception e) {
            log.error("Failed to count points in {}: {}", collectionName, e.getMessage());
        }
        return 0;
    }

    /**
     * 获取集合中的所有文档（带翻页的 scroll）。
     * 用于孤儿点清理：对比 Qdrant 与 JSON 的差异。
     */
    public List<Document> getAllDocuments(String collectionName) {
        if (!available) {
            return new ArrayList<>();
        }

        try {
            ensureCollectionExists(collectionName);
            
            String url = getBaseUrl() + "/collections/" + collectionName + "/points/scroll";
            List<Document> allDocs = new ArrayList<>();
            String nextOffset = null;
            int pageSize = 100;

            do {
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("limit", pageSize);
                requestBody.put("with_payload", true);
                if (nextOffset != null) {
                    requestBody.put("offset", nextOffset);
                }

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, createHeaders());
                ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    break;
                }

                Map<String, Object> body = response.getBody();
                // Qdrant scroll API: result 是对象，包含 points 数组和 next_page_offset
                Map<String, Object> resultObj = (Map<String, Object>) body.get("result");
                List<Map<String, Object>> resultList = null;
                if (resultObj != null) {
                    Object pointsObj = resultObj.get("points");
                    if (pointsObj instanceof List) {
                        resultList = (List<Map<String, Object>>) pointsObj;
                    }
                    // 翻页 offset 在 result 对象内部
                    Object npo = resultObj.get("next_page_offset");
                    nextOffset = (npo != null) ? npo.toString() : null;
                } else {
                    nextOffset = null;
                }

                if (resultList != null) {
                    for (Map<String, Object> point : resultList) {
                        String id = point.get("id").toString();
                        Map<String, Object> payload = (Map<String, Object>) point.get("payload");
                        Document doc = parsePayloadToDocument(id, null, payload);
                        if (doc != null) {
                            allDocs.add(doc);
                        }
                    }
                }

            } while (nextOffset != null);

            return allDocs;
        } catch (Exception e) {
            log.error("Failed to get all documents from {}: {}", collectionName, e.getMessage());
        }
        return new ArrayList<>();
    }

    public void clearCollection(String collectionName) {
        if (!available) {
            log.debug("Qdrant not available, skipping clear");
            return;
        }

        try {
            String url = getBaseUrl() + "/collections/" + collectionName;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(createHeaders()), String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Deleted collection: {}", collectionName);
            }
            
            ensureCollectionExists(collectionName);
            log.info("Cleared and recreated collection successfully: {}", collectionName);
        } catch (Exception e) {
            log.error("Failed to clear collection {}: {}", collectionName, e.getMessage());
        }
    }
}
