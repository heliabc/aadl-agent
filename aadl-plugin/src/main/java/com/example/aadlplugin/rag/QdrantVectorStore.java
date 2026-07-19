package com.example.aadlplugin.rag;

import com.example.aadlplugin.config.QdrantConfig;
import com.example.aadlplugin.rag.model.Document;
import com.example.aadlplugin.util.HttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.logging.Logger;

public class QdrantVectorStore {

    private static final Logger log = Logger.getLogger(QdrantVectorStore.class.getName());

    private final QdrantConfig qdrantConfig;
    private final ObjectMapper objectMapper;
    private volatile boolean available = false;

    public QdrantVectorStore(QdrantConfig qdrantConfig) {
        this.qdrantConfig = qdrantConfig;
        this.objectMapper = new ObjectMapper();
    }

    public void init() {
        testConnection();
        printExistingCollections();

        for (String collection : qdrantConfig.getCollections()) {
            ensureCollectionExists(collection);
        }

        available = true;
        log.info("Qdrant vector store initialized successfully");
    }

    private void testConnection() {
        try {
            String url = getBaseUrl() + "/collections";
            String response = HttpClient.get(url);
            if (response != null) {
                log.info("Qdrant connection test successful");
            } else {
                log.warning("Qdrant connection test failed");
            }
        } catch (Exception e) {
            log.warning("Qdrant connection test failed: " + e.getMessage());
        }
    }

    private String getBaseUrl() {
        String scheme = qdrantConfig.isUseTls() ? "https" : "http";
        return scheme + "://" + qdrantConfig.getHost() + ":" + qdrantConfig.getPort();
    }

    private Map<String, String> createHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (qdrantConfig.getApiKey() != null && !qdrantConfig.getApiKey().isEmpty()) {
            headers.put("api-key", qdrantConfig.getApiKey());
        }
        return headers;
    }

    private void printExistingCollections() {
        try {
            List<String> collections = listCollections();
            log.info("Existing Qdrant collections: " + collections);
        } catch (Exception e) {
            log.warning("Failed to list existing collections: " + e.getMessage());
        }
    }

    public List<String> listCollections() {
        try {
            String url = getBaseUrl() + "/collections";
            String response = HttpClient.get(url, createHeaders());

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode result = root.get("result");
                JsonNode collectionsNode;
                if (result != null) {
                    collectionsNode = result.get("collections");
                } else {
                    collectionsNode = root.get("collections");
                }

                if (collectionsNode != null && collectionsNode.isArray()) {
                    List<String> collections = new ArrayList<>();
                    for (JsonNode node : collectionsNode) {
                        String name = node.has("name") ? node.get("name").asText() : null;
                        if (name != null) {
                            collections.add(name);
                        }
                    }
                    return collections;
                }
            }
        } catch (Exception e) {
            log.severe("Failed to list collections: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    private boolean collectionExists(String collectionName) {
        return listCollections().contains(collectionName);
    }

    private void ensureCollectionExists(String collectionName) {
        if (!collectionExists(collectionName)) {
            createCollection(collectionName);
        }
    }

    private void createCollection(String collectionName) {
        log.info("Creating Qdrant collection: " + collectionName);

        try {
            String url = getBaseUrl() + "/collections/" + collectionName;

            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> vectors = new HashMap<>();
            vectors.put("size", qdrantConfig.getEmbeddingSize());
            vectors.put("distance", "Cosine");
            requestBody.put("vectors", vectors);

            String response = HttpClient.putJson(url, requestBody, createHeaders());

            if (response != null) {
                waitForCollectionReady(collectionName);
                log.info("Qdrant collection '" + collectionName + "' created successfully");
            } else {
                log.severe("Failed to create collection " + collectionName);
            }
        } catch (Exception e) {
            log.severe("Failed to create collection " + collectionName + ": " + e.getMessage());
        }
    }

    private void waitForCollectionReady(String collectionName) {
        int maxAttempts = 20;
        int delayMs = 100;

        for (int i = 0; i < maxAttempts; i++) {
            try {
                String url = getBaseUrl() + "/collections/" + collectionName;
                String response = HttpClient.get(url, createHeaders());

                if (response != null) {
                    JsonNode root = objectMapper.readTree(response);
                    JsonNode status = root.get("status");
                    if (status != null) {
                        JsonNode ready = status.get("ready");
                        if (ready != null && ready.asBoolean()) {
                            log.info("Collection '" + collectionName + "' is ready");
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                log.fine("Waiting for collection '" + collectionName + "' to be ready: " + e.getMessage());
            }

            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.warning("Collection '" + collectionName + "' may not be fully ready after waiting");
    }

    public boolean isAvailable() {
        return available;
    }

    public void upsert(String collectionName, Document document) {
        if (!available) {
            log.fine("Qdrant not available, skipping upsert");
            return;
        }

        try {
            ensureCollectionExists(collectionName);

            String url = getBaseUrl() + "/collections/" + collectionName + "/points";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("points", Collections.singletonList(buildPoint(document)));

            String response = HttpClient.putJson(url, requestBody, createHeaders());

            if (response != null) {
                log.fine("Upserted document to collection " + collectionName + ": " + document.getId());
            } else {
                log.severe("Failed to upsert document to " + collectionName);
            }
        } catch (Exception e) {
            log.severe("Failed to upsert document to " + collectionName + ": " + e.getMessage());
        }
    }

    public void upsertBatch(String collectionName, List<Document> documents) {
        if (!available) {
            log.fine("Qdrant not available, skipping batch upsert");
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

            String response = HttpClient.putJson(url, requestBody, createHeaders());

            if (response != null) {
                log.info("Batch upserted " + documents.size() + " documents to collection " + collectionName);
            } else {
                log.severe("Failed to batch upsert to " + collectionName);
            }
        } catch (Exception e) {
            log.severe("Failed to batch upsert to " + collectionName + ": " + e.getMessage());
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
            log.fine("Qdrant not available, skipping delete");
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

            String response = HttpClient.postJson(url, requestBody, createHeaders());

            if (response != null) {
                log.info("Deleted document " + documentId + " from " + collectionName);
            } else {
                log.severe("Failed to delete document from " + collectionName);
            }
        } catch (Exception e) {
            log.severe("Failed to delete document from " + collectionName + ": " + e.getMessage());
        }
    }

    public List<Document> search(String collectionName, float[] queryVector, int topK) {
        if (!available) {
            log.fine("Qdrant not available, returning empty results");
            return new ArrayList<>();
        }

        if (queryVector == null || queryVector.length == 0) {
            log.warning("Query vector is null or empty, returning empty results for " + collectionName);
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

            String response = HttpClient.postJson(url, requestBody, createHeaders());

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode resultList = root.get("result");

                if (resultList == null || !resultList.isArray()) {
                    log.fine("Qdrant REST API returned empty results for collection: " + collectionName);
                    return new ArrayList<>();
                }

                List<Document> documents = new ArrayList<>();

                for (JsonNode point : resultList) {
                    Double score = point.has("score") ? point.get("score").asDouble() : null;
                    String id = point.has("id") ? point.get("id").asText() : null;
                    JsonNode payloadNode = point.get("payload");

                    if (id != null && payloadNode != null) {
                        Document doc = parsePayloadToDocument(id, score, payloadNode);
                        if (doc != null) {
                            documents.add(doc);
                        }
                    }
                }

                log.fine("Qdrant REST API returned " + documents.size() + " documents for collection: " + collectionName);
                return documents;
            }

            log.warning("Qdrant REST API returned non-success status");

        } catch (Exception e) {
            log.severe("Failed to search Qdrant via REST API for " + collectionName + ": " + e.getMessage());
        }

        return new ArrayList<>();
    }

    private Document parsePayloadToDocument(String id, Double score, JsonNode payload) {
        if (payload == null) {
            return null;
        }

        try {
            String content = payload.has("text") ? payload.get("text").asText() :
                    (payload.has("content") ? payload.get("content").asText() : "");

            if (content.isEmpty()) {
                return null;
            }

            Document document = new Document();
            document.setId(id);
            document.setContent(content);
            document.setScore(score != null ? score.floatValue() : 0.0f);

            if (payload.has("title")) {
                document.setTitle(payload.get("title").asText());
            }
            if (payload.has("category")) {
                document.setCategory(payload.get("category").asText());
            }
            if (payload.has("agent_type")) {
                document.setAgentType(payload.get("agent_type").asText());
            }
            if (payload.has("source")) {
                document.setSource(payload.get("source").asText());
            }
            if (payload.has("payload")) {
                document.setPayload(payload.get("payload").asText());
            }

            return document;
        } catch (Exception e) {
            log.warning("Failed to parse payload to document: " + e.getMessage());
            return null;
        }
    }

    public List<Document> keywordSearch(String collectionName, String query, int topK) {
        if (!available) {
            log.fine("Qdrant not available, returning empty results");
            return new ArrayList<>();
        }
        return new ArrayList<>();
    }

    public long count(String collectionName) {
        if (!available) {
            return 0;
        }

        try {
            String url = getBaseUrl() + "/collections/" + collectionName + "/points/count";
            String response = HttpClient.postJson(url, new HashMap<>(), createHeaders());

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                if (root.has("count")) {
                    return root.get("count").asLong();
                }
            }
        } catch (Exception e) {
            log.severe("Failed to count points in " + collectionName + ": " + e.getMessage());
        }
        return 0;
    }

    public List<Document> getAllDocuments(String collectionName) {
        if (!available) {
            return new ArrayList<>();
        }

        try {
            ensureCollectionExists(collectionName);

            String url = getBaseUrl() + "/collections/" + collectionName + "/points/scroll";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("limit", 100);
            requestBody.put("with_payload", true);

            String response = HttpClient.postJson(url, requestBody, createHeaders());

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode resultList = root.get("result");

                if (resultList == null || !resultList.isArray()) {
                    return new ArrayList<>();
                }

                List<Document> documents = new ArrayList<>();
                for (JsonNode point : resultList) {
                    String id = point.has("id") ? point.get("id").asText() : null;
                    JsonNode payloadNode = point.get("payload");

                    if (id != null && payloadNode != null) {
                        Document doc = parsePayloadToDocument(id, null, payloadNode);
                        if (doc != null) {
                            documents.add(doc);
                        }
                    }
                }
                return documents;
            }
        } catch (Exception e) {
            log.severe("Failed to get all documents from " + collectionName + ": " + e.getMessage());
        }
        return new ArrayList<>();
    }

    public void clearCollection(String collectionName) {
        if (!available) {
            log.fine("Qdrant not available, skipping clear");
            return;
        }

        try {
            String url = getBaseUrl() + "/collections/" + collectionName;
            String response = HttpClient.delete(url, createHeaders());

            if (response != null) {
                log.info("Deleted collection: " + collectionName);
            }

            ensureCollectionExists(collectionName);
            log.info("Cleared and recreated collection successfully: " + collectionName);
        } catch (Exception e) {
            log.severe("Failed to clear collection " + collectionName + ": " + e.getMessage());
        }
    }
}
