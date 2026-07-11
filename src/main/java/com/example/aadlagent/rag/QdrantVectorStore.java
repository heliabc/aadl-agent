package com.example.aadlagent.rag;

import com.example.aadlagent.config.QdrantConfig;
import com.example.aadlagent.rag.model.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QdrantVectorStore {

    private final QdrantConfig qdrantConfig;
    private final ConcurrentHashMap<String, QdrantEmbeddingStore> stores = new ConcurrentHashMap<>();
    private QdrantClient qdrantClient;
    private volatile boolean available = false;

    public QdrantVectorStore(QdrantConfig qdrantConfig) {
        this.qdrantConfig = qdrantConfig;
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
                    return java.util.Collections.emptyList();
                }
                if (list.get(0) instanceof String) {
                    return (List<String>) list;
                } else if (list.get(0) instanceof Collections.CollectionDescription) {
                    return list.stream()
                            .map(item -> ((Collections.CollectionDescription) item).getName())
                            .collect(Collectors.toList());
                }
            }
            return java.util.Collections.emptyList();
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

    private QdrantEmbeddingStore getStore(String collectionName) {
        ensureCollectionExists(collectionName);
        
        return stores.computeIfAbsent(collectionName, name -> {
            QdrantEmbeddingStore store = QdrantEmbeddingStore.builder()
                    .collectionName(name)
                    .host(qdrantConfig.getHost())
                    .port(qdrantConfig.getPort())
                    .apiKey(qdrantConfig.getApiKey())
                    .useTls(qdrantConfig.isUseTls())
                    .build();
            log.debug("Created Qdrant store for collection: {}", name);
            return store;
        });
    }

    public boolean isAvailable() {
        return available && qdrantClient != null;
    }

    public void upsert(String collectionName, Document document) {
        if (!available) {
            log.debug("Qdrant not available, skipping upsert");
            return;
        }

        try {
            ensureCollectionExists(collectionName);
            
            retryWithBackoff(() -> {
                QdrantEmbeddingStore store = getStore(collectionName);
                
                Embedding embedding = Embedding.from(document.getEmbedding());
                
                Map<String, String> metadataMap = buildMetadataMap(document);
                Metadata metadata = Metadata.from(metadataMap);
                
                TextSegment segment = TextSegment.from(
                    document.getContent() != null ? document.getContent() : "",
                    metadata
                );
                
                store.add(embedding, segment);
                return null;
            }, "upsert-" + document.getId());
            
            log.debug("Upserted document to collection {}: {}", collectionName, document.getId());
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
            
            retryWithBackoff(() -> {
                QdrantEmbeddingStore store = getStore(collectionName);
                
                List<Embedding> embeddings = new ArrayList<>();
                List<TextSegment> segments = new ArrayList<>();
                
                for (Document document : documents) {
                    Embedding embedding = Embedding.from(document.getEmbedding());
                    
                    Map<String, String> metadataMap = buildMetadataMap(document);
                    Metadata metadata = Metadata.from(metadataMap);
                    
                    TextSegment segment = TextSegment.from(
                        document.getContent() != null ? document.getContent() : "",
                        metadata
                    );
                    
                    embeddings.add(embedding);
                    segments.add(segment);
                }
                
                store.addAll(embeddings, segments);
                return null;
            }, "upsertBatch-" + collectionName);
            
            log.debug("Batch upserted {} documents to collection {}", documents.size(), collectionName);
        } catch (Exception e) {
            log.error("Failed to batch upsert to {}: {}", collectionName, e.getMessage());
        }
    }

    private Map<String, String> buildMetadataMap(Document document) {
        Map<String, String> metadataMap = new HashMap<>();
        metadataMap.put("title", document.getTitle() != null ? document.getTitle() : "");
        metadataMap.put("source", document.getSource() != null ? document.getSource() : "");
        metadataMap.put("category", document.getCategory() != null ? document.getCategory() : "");
        metadataMap.put("payload", document.getPayload() != null ? document.getPayload() : "");
        
        if (document.getTags() != null && !document.getTags().isEmpty()) {
            metadataMap.put("tags", String.join(",", document.getTags()));
        }
        if (document.getHardwareInterfaces() != null && !document.getHardwareInterfaces().isEmpty()) {
            metadataMap.put("hardwareInterfaces", String.join(",", document.getHardwareInterfaces()));
        }
        if (document.getSensors() != null && !document.getSensors().isEmpty()) {
            metadataMap.put("sensors", String.join(",", document.getSensors()));
        }
        if (document.getActuators() != null && !document.getActuators().isEmpty()) {
            metadataMap.put("actuators", String.join(",", document.getActuators()));
        }
        if (document.getApplicationDomains() != null && !document.getApplicationDomains().isEmpty()) {
            metadataMap.put("applicationDomains", String.join(",", document.getApplicationDomains()));
        }
        if (document.getSafetyLevels() != null && !document.getSafetyLevels().isEmpty()) {
            metadataMap.put("safetyLevels", String.join(",", document.getSafetyLevels()));
        }
        if (document.getSchedulingPolicies() != null && !document.getSchedulingPolicies().isEmpty()) {
            metadataMap.put("schedulingPolicies", String.join(",", document.getSchedulingPolicies()));
        }
        
        return metadataMap;
    }

    public void delete(String collectionName, String documentId) {
        if (!available) {
            log.debug("Qdrant not available, skipping delete");
            return;
        }

        try {
            log.warn("Delete operation not supported in QdrantEmbeddingStore 0.30.0");
        } catch (Exception e) {
            log.error("Failed to delete document from {}: {}", collectionName, e.getMessage());
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

        log.error("SEARCH DEBUG: collection={}, vector length={}", collectionName, queryVector.length);

        try {
            ensureCollectionExists(collectionName);
            
            QdrantEmbeddingStore store = getStore(collectionName);
            Embedding queryEmbedding = Embedding.from(queryVector);
            
            log.error("SEARCH DEBUG: Embedding dimension={}", queryEmbedding.dimension());
            
            List<EmbeddingMatch<TextSegment>> results = store.findRelevant(queryEmbedding, topK);
            
            return results.stream()
                    .map(this::parseDocument)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to search in {}: {}", collectionName, e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<Document> keywordSearch(String collectionName, String query, int topK) {
        if (!available) {
            log.debug("Qdrant not available, returning empty results");
            return new ArrayList<>();
        }

        return new ArrayList<>();
    }

    public long count(String collectionName) {
        if (!available) {
            return 0;
        }

        try {
            QdrantEmbeddingStore store = getStore(collectionName);
            return 0;
        } catch (Exception e) {
            log.error("Failed to count points in {}: {}", collectionName, e.getMessage());
            return 0;
        }
    }

    public List<Document> getAllDocuments(String collectionName) {
        if (!available) {
            return new ArrayList<>();
        }

        try {
            QdrantEmbeddingStore store = getStore(collectionName);
            List<EmbeddingMatch<TextSegment>> results = store.findRelevant(Embedding.from(new float[qdrantConfig.getEmbeddingSize()]), 100);
            
            return results.stream()
                    .map(this::parseDocument)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get all documents from {}: {}", collectionName, e.getMessage());
            return new ArrayList<>();
        }
    }

    public void clearCollection(String collectionName) {
        if (!available) {
            log.debug("Qdrant not available, skipping clear");
            return;
        }

        try {
            log.warn("Clear collection operation not supported in QdrantEmbeddingStore 0.30.0");
        } catch (Exception e) {
            log.error("Failed to clear collection {}: {}", collectionName, e.getMessage());
        }
    }

    private Document parseDocument(EmbeddingMatch<TextSegment> match) {
        try {
            TextSegment segment = match.embedded();
            
            Metadata metadata = segment.metadata();
            String title = metadata != null ? metadata.getString("title") : "";
            String source = metadata != null ? metadata.getString("source") : "";
            String category = metadata != null ? metadata.getString("category") : "";
            String tagsStr = metadata != null ? metadata.getString("tags") : "";
            String payload = metadata != null ? metadata.getString("payload") : "";
            
            List<String> tags = parseList(tagsStr);
            
            float[] embedding = null;
            if (match.embedding() != null) {
                float[] vector = match.embedding().vector();
                embedding = Arrays.copyOf(vector, vector.length);
            }
            
            Document.DocumentBuilder builder = Document.builder()
                    .id(UUID.randomUUID().toString())
                    .content(segment.text())
                    .payload(payload)
                    .title(title)
                    .source(source)
                    .category(category)
                    .tags(tags)
                    .embedding(embedding)
                    .score(match.score());
            
            if (metadata != null) {
                builder.hardwareInterfaces(parseList(metadata.getString("hardwareInterfaces")));
                builder.sensors(parseList(metadata.getString("sensors")));
                builder.actuators(parseList(metadata.getString("actuators")));
                builder.applicationDomains(parseList(metadata.getString("applicationDomains")));
                builder.safetyLevels(parseList(metadata.getString("safetyLevels")));
                builder.schedulingPolicies(parseList(metadata.getString("schedulingPolicies")));
            }
            
            return builder.build();

        } catch (Exception e) {
            log.error("Failed to parse document: {}", e.getMessage());
            return null;
        }
    }

    private List<String> parseList(String value) {
        if (value == null || value.isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.asList(value.split(","));
    }
}