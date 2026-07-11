package com.example.aadlagent.rag;

import com.example.aadlagent.config.QdrantConfig;
import com.example.aadlagent.rag.model.Document;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithGlance.Value;
import io.qdrant.client.grpc.Points.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QdrantVectorStore {

    private final QdrantConfig qdrantConfig;
    private QdrantClient qdrantClient;
    private volatile boolean available = false;
    private final RestTemplate restTemplate;

    public QdrantVectorStore(QdrantConfig qdrantConfig) {
        this.qdrantConfig = qdrantConfig;
        this.restTemplate = new RestTemplate();
    }

    @PostConstruct
    public void init() {
        initQdrantClient();
        printExistingCollections();
        
        for (String collection : qdrantConfig.getCollections()) {
            ensureCollectionExists(collection);
        }
        
        available = true;
        log.info("Qdrant vector store initialized successfully");
    }

    private void initQdrantClient() {
        try {
            QdrantGrpcClient.Builder grpcBuilder = QdrantGrpcClient.newBuilder(
                    qdrantConfig.getHost(), 
                    qdrantConfig.getPort(), 
                    qdrantConfig.isUseTls()
            );
            
            if (qdrantConfig.getApiKey() != null && !qdrantConfig.getApiKey().isEmpty()) {
                grpcBuilder.withApiKey(qdrantConfig.getApiKey());
            }
            
            QdrantGrpcClient grpcClient = grpcBuilder.build();
            qdrantClient = new QdrantClient(grpcClient);
            log.info("Qdrant native client initialized: {}:{}", qdrantConfig.getHost(), qdrantConfig.getPort());
        } catch (Exception e) {
            log.error("Failed to initialize Qdrant native client: {}", e.getMessage());
            throw new RuntimeException("Qdrant client initialization failed", e);
        }
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
        if (qdrantClient == null) {
            throw new IllegalStateException("Qdrant client not initialized");
        }
        
        return retryWithBackoff(() -> {
            Object result = qdrantClient.listCollectionsAsync().get();
            if (result instanceof List) {
                List<?> list = (List<?>) result;
                if (list.isEmpty()) {
                    return Collections.emptyList();
                }
                if (list.get(0) instanceof String) {
                    return (List<String>) list;
                } else if (list.get(0) instanceof Collections.CollectionDescription) {
                    return list.stream()
                            .map(item -> ((Collections.CollectionDescription) item).getName())
                            .collect(Collectors.toList());
                }
            }
            return Collections.emptyList();
        }, "listCollections");
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
        log.info("Creating Qdrant collection: {}", collectionName);
        
        retryWithBackoff(() -> {
            Collections.VectorParams vectorParams = Collections.VectorParams.newBuilder()
                    .setDistance(Collections.Distance.Cosine)
                    .setSize(qdrantConfig.getEmbeddingSize())
                    .build();
            
            qdrantClient.createCollectionAsync(collectionName, vectorParams).get();
            log.info("Qdrant create_collection RPC returned successfully for '{}'", collectionName);
            return null;
        }, "createCollection-" + collectionName);
        
        waitForCollectionReady(collectionName);
        log.info("Qdrant collection '{}' created successfully", collectionName);
    }

    private void waitForCollectionReady(String collectionName) {
        int maxAttempts = 20;
        int delayMs = 100;
        
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Collections.CollectionInfo info = qdrantClient.getCollectionInfoAsync(collectionName).get();
                if (info.getStatus() == Collections.CollectionStatus.Green) {
                    log.info("Collection '{}' is ready", collectionName);
                    return;
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

    private <T> T retryWithBackoff(RetryableOperation<T> operation, String operationName) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= qdrantConfig.getMaxRetries(); attempt++) {
            try {
                return operation.execute();
            } catch (Exception e) {
                lastException = e;
                log.warn("Qdrant operation '{}' failed on attempt {}: {}", operationName, attempt, e.getMessage());
                
                if (attempt < qdrantConfig.getMaxRetries()) {
                    try {
                        Thread.sleep(qdrantConfig.getRetryDelayMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Operation interrupted", ie);
                    }
                }
            }
        }
        
        throw new RuntimeException("Qdrant operation '" + operationName + "' failed after " + qdrantConfig.getMaxRetries() + " attempts", lastException);
    }

    @FunctionalInterface
    private interface RetryableOperation<T> {
        T execute() throws Exception;
    }

    public boolean isAvailable() {
        return available && qdrantClient != null;
    }

    /**
     * 原生客户端：构建保存到 Qdrant 中的 Metadata Payload
     */
    private Map<String, Value> buildPayload(Document document) {
        Map<String, Value> payload = new HashMap<>();
        payload.put("text", ValueFactory.value(document.getContent() != null ? document.getContent() : ""));
        payload.put("title", ValueFactory.value(document.getTitle() != null ? document.getTitle() : ""));
        payload.put("source", ValueFactory.value(document.getSource() != null ? document.getSource() : ""));
        payload.put("category", ValueFactory.value(document.getCategory() != null ? document.getCategory() : ""));
        payload.put("payload", ValueFactory.value(document.getPayload() != null ? document.getPayload() : ""));
        
        if (document.getTags() != null && !document.getTags().isEmpty()) {
            payload.put("tags", ValueFactory.value(document.getTags()));
        }
        if (document.getHardwareInterfaces() != null && !document.getHardwareInterfaces().isEmpty()) {
            payload.put("hardwareInterfaces", ValueFactory.value(document.getHardwareInterfaces()));
        }
        if (document.getSensors() != null && !document.getSensors().isEmpty()) {
            payload.put("sensors", ValueFactory.value(document.getSensors()));
        }
        if (document.getActuators() != null && !document.getActuators().isEmpty()) {
            payload.put("actuators", ValueFactory.value(document.getActuators()));
        }
        if (document.getApplicationDomains() != null && !document.getApplicationDomains().isEmpty()) {
            payload.put("applicationDomains", ValueFactory.value(document.getApplicationDomains()));
        }
        if (document.getSafetyLevels() != null && !document.getSafetyLevels().isEmpty()) {
            payload.put("safetyLevels", ValueFactory.value(document.getSafetyLevels()));
        }
        if (document.getSchedulingPolicies() != null && !document.getSchedulingPolicies().isEmpty()) {
            payload.put("schedulingPolicies", ValueFactory.value(document.getSchedulingPolicies()));
        }
        
        return payload;
    }

    /**
     * 辅助解析 UUID 的 ID 构建工具
     */
    private PointId parsePointId(String docId) {
        try {
            UUID.fromString(docId);
            return PointId.newBuilder().setUuid(docId).build();
        } catch (IllegalArgumentException e) {
            // 如果不是标准的 UUID 格式，则采用一致性哈希将其转换为确定性的 UUID
            return PointId.newBuilder().setUuid(UUID.nameUUIDFromBytes(docId.getBytes()).toString()).build();
        }
    }

    /**
     * 去 LangChain4j：原生 SDK 写入单条数据
     */
    public void upsert(String collectionName, Document document) {
        if (!available) {
            log.debug("Qdrant not available, skipping upsert");
            return;
        }

        try {
            ensureCollectionExists(collectionName);
            
            retryWithBackoff(() -> {
                PointId pointId = parsePointId(document.getId());

                List<Float> vectorList = new ArrayList<>(document.getEmbedding().length);
                for (float v : document.getEmbedding()) {
                    vectorList.add(v);
                }

                PointStruct point = PointStruct.newBuilder()
                        .setId(pointId)
                        .setVectors(Vectors.newBuilder()
                                .setVector(Vector.newBuilder().addAllData(vectorList).build())
                                .build())
                        .putAllPayload(buildPayload(document))
                        .build();

                qdrantClient.upsertAsync(collectionName, java.util.Collections.singletonList(point)).get();
                return null;
            }, "upsert-" + document.getId());
            
            log.debug("Upserted document to collection {}: {}", collectionName, document.getId());
        } catch (Exception e) {
            log.error("Failed to upsert document to {}: {}", collectionName, e.getMessage());
        }
    }

    /**
     * 去 LangChain4j：原生 SDK 批量写入数据
     */
    public void upsertBatch(String collectionName, List<Document> documents) {
        if (!available) {
            log.debug("Qdrant not available, skipping batch upsert");
            return;
        }

        try {
            ensureCollectionExists(collectionName);
            
            retryWithBackoff(() -> {
                List<PointStruct> points = new ArrayList<>();
                for (Document document : documents) {
                    PointId pointId = parsePointId(document.getId());

                    List<Float> vectorList = new ArrayList<>(document.getEmbedding().length);
                    for (float v : document.getEmbedding()) {
                        vectorList.add(v);
                    }

                    PointStruct point = PointStruct.newBuilder()
                            .setId(pointId)
                            .setVectors(Vectors.newBuilder()
                                    .setVector(Vector.newBuilder().addAllData(vectorList).build())
                                    .build())
                            .putAllPayload(buildPayload(document))
                            .build();
                    points.add(point);
                }
                
                qdrantClient.upsertAsync(collectionName, points).get();
                return null;
            }, "upsertBatch-" + collectionName);
            
            log.info("Batch upserted {} documents to collection {}", documents.size(), collectionName);
        } catch (Exception e) {
            log.error("Failed to batch upsert to {}: {}", collectionName, e.getMessage());
        }
    }

    /**
     * 原生 SDK：物理删除单条数据
     */
    public void delete(String collectionName, String documentId) {
        if (!available) {
            log.debug("Qdrant not available, skipping delete");
            return;
        }

        try {
            PointId pointId = parsePointId(documentId);
            PointsSelector selector = PointsSelector.newBuilder()
                    .setPoints(PointsIdsList.newBuilder().addPoints(pointId).build())
                    .build();

            qdrantClient.deleteAsync(collectionName, selector).get();
            log.info("Deleted document {} from {}", documentId, collectionName);
        } catch (Exception e) {
            log.error("Failed to delete document from {}: {}", collectionName, e.getMessage());
        }
    }

    /**
     * 基于官方 REST API 实现的高效、无 Bug 向量检索
     */
    public List<Document> search(String collectionName, float[] queryVector, int topK) {
        if (!available) {
            log.debug("Qdrant not available, returning empty results");
            return new ArrayList<>();
        }

        if (queryVector == null || queryVector.length == 0) {
            log.warn("Query vector is null or empty, returning empty results for {}", collectionName);
            return new ArrayList<>();
        }

        return searchViaRestApi(collectionName, queryVector, topK);
    }

    private List<Document> searchViaRestApi(String collectionName, float[] queryVector, int topK) {
        try {
            ensureCollectionExists(collectionName);
            
            String scheme = qdrantConfig.isUseTls() ? "https" : "http";
            String url = scheme + "://" + qdrantConfig.getHost() + ":" + qdrantConfig.getPort() 
                    + "/collections/" + collectionName + "/points/search";

            List<Float> vectorList = new ArrayList<>(queryVector.length);
            for (float v : queryVector) {
                vectorList.add(v);
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("vector", vectorList);
            requestBody.put("limit", topK);
            requestBody.put("with_payload", true);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (qdrantConfig.getApiKey() != null && !qdrantConfig.getApiKey().isEmpty()) {
                headers.set("api-key", qdrantConfig.getApiKey());
            }

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    Map.class
            );

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
            document.setId(id);
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
        // 预留给关键词全文匹配检索
        return new ArrayList<>();
    }

    /**
     * 原生 SDK：查询集合中的文档点总数
     */
    public long count(String collectionName) {
        if (!available) {
            return 0;
        }

        try {
            CountResult result = qdrantClient.countAsync(collectionName, null, true).get();
            return result.getCount();
        } catch (Exception e) {
            log.error("Failed to count points in {}: {}", collectionName, e.getMessage());
            return 0;
        }
    }

    /**
     * 原生 SDK：采用 Scroll 滚动技术获取集合中前 100 条文档
     */
    public List<Document> getAllDocuments(String collectionName) {
        if (!available) {
            return new ArrayList<>();
        }

        try {
            ensureCollectionExists(collectionName);
            
            ScrollPoints scrollPoints = ScrollPoints.newBuilder()
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setLimit(100)
                    .build();

            ScrollResponse response = qdrantClient.scrollAsync(collectionName, scrollPoints).get();
            List<RetrievedPoint> points = response.getResultList();
            
            List<Document> documents = new ArrayList<>();
            for (RetrievedPoint point : points) {
                String id = point.getId().getUuid();
                if (id == null || id.isEmpty()) {
                    id = String.valueOf(point.getId().getNum());
                }
                
                Document doc = parseGrpcPayloadToDocument(id, point.getPayloadMap());
                if (doc != null) {
                    documents.add(doc);
                }
            }
            return documents;

        } catch (Exception e) {
            log.error("Failed to get all documents from {}: {}", collectionName, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 原生 SDK 彻底清除集合数据（最彻底的清除方式为直接物理删除集合并重建）
     */
    public void clearCollection(String collectionName) {
        if (!available) {
            log.debug("Qdrant not available, skipping clear");
            return;
        }

        try {
            if (collectionExists(collectionName)) {
                qdrantClient.deleteCollectionAsync(collectionName).get();
                log.info("Physically deleted collection for clearing: {}", collectionName);
            }
            ensureCollectionExists(collectionName);
            log.info("Cleared and recreated collection successfully: {}", collectionName);
        } catch (Exception e) {
            log.error("Failed to clear collection {}: {}", collectionName, e.getMessage());
        }
    }

    /**
     * 原生 gRPC 荷载转换为内部 Document DTO
     */
    private Document parseGrpcPayloadToDocument(String id, Map<String, Value> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        
        try {
            String content = getGrpcString(payload, "text");
            if (content.isEmpty()) {
                content = getGrpcString(payload, "content");
            }
            if (content.isEmpty()) {
                return null;
            }

            Document doc = new Document();
            doc.setId(id);
            doc.setContent(content);
            doc.setScore(1.0f); // 默认相似度得分占位
            doc.setTitle(getGrpcString(payload, "title"));
            doc.setSource(getGrpcString(payload, "source"));
            doc.setCategory(getGrpcString(payload, "category"));
            doc.setPayload(getGrpcString(payload, "payload"));

            doc.setTags(parseGrpcList(payload.get("tags")));
            doc.setHardwareInterfaces(parseGrpcList(payload.get("hardwareInterfaces")));
            doc.setSensors(parseGrpcList(payload.get("sensors")));
            doc.setActuators(parseGrpcList(payload.get("actuators")));
            doc.setApplicationDomains(parseGrpcList(payload.get("applicationDomains")));
            doc.setSafetyLevels(parseGrpcList(payload.get("safetyLevels")));
            doc.setSchedulingPolicies(parseGrpcList(payload.get("schedulingPolicies")));

            return doc;
        } catch (Exception e) {
            log.warn("Failed to parse gRPC payload to Document: {}", e.getMessage());
            return null;
        }
    }

    private String getGrpcString(Map<String, Value> payload, String key) {
        Value val = payload.get(key);
        return val != null ? val.getStringValue() : "";
    }

    private List<String> parseGrpcList(Value value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (value.hasListValue()) {
            return value.getListValue().getValuesList().stream()
                    .map(Value::getStringValue)
                    .collect(Collectors.toList());
        }
        String str = value.getStringValue();
        if (str != null && !str.isEmpty()) {
            return Arrays.asList(str.split(","));
        }
        return new ArrayList<>();
    }
}