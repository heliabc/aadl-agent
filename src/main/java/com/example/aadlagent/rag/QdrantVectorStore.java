package com.example.aadlagent.rag;

import com.example.aadlagent.config.QdrantConfig;
import com.example.aadlagent.rag.model.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QdrantVectorStore {

    private final QdrantConfig qdrantConfig;
    private final ConcurrentHashMap<String, QdrantEmbeddingStore> stores = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private volatile boolean available = false;

    public QdrantVectorStore(QdrantConfig qdrantConfig) {
        this.qdrantConfig = qdrantConfig;
    }

    @PostConstruct
    public void init() {
        executorService.submit(this::initializeCollections);
    }

    private void initializeCollections() {
        try {
            for (String collection : qdrantConfig.getCollections()) {
                createStore(collection);
            }
            available = true;
            log.info("Qdrant vector store initialized successfully");
        } catch (Exception e) {
            log.warn("Qdrant not available: {}. Falling back to memory store.", e.getMessage());
            available = false;
        }
    }

    private QdrantEmbeddingStore createStore(String collectionName) {
        QdrantEmbeddingStore store = QdrantEmbeddingStore.builder()
                .collectionName(collectionName)
                .host(qdrantConfig.getHost())
                .port(qdrantConfig.getPort())
                .apiKey(qdrantConfig.getApiKey())
                .useTls(qdrantConfig.isUseTls())
                .build();
        stores.put(collectionName, store);
        log.info("Created Qdrant store for collection: {}", collectionName);
        return store;
    }

    private QdrantEmbeddingStore getStore(String collectionName) {
        return stores.computeIfAbsent(collectionName, this::createStore);
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
            QdrantEmbeddingStore store = getStore(collectionName);
            
            Embedding embedding = Embedding.from(document.getEmbedding());
            
            Map<String, String> metadataMap = new HashMap<>();
            metadataMap.put("title", document.getTitle() != null ? document.getTitle() : "");
            metadataMap.put("source", document.getSource() != null ? document.getSource() : "");
            metadataMap.put("category", document.getCategory() != null ? document.getCategory() : "");
            
            if (document.getTags() != null && !document.getTags().isEmpty()) {
                metadataMap.put("tags", String.join(",", document.getTags()));
            }
            
            Metadata metadata = Metadata.from(metadataMap);
            
            TextSegment segment = TextSegment.from(
                document.getContent() != null ? document.getContent() : "",
                metadata
            );
            
            store.add(embedding, segment);
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
            QdrantEmbeddingStore store = getStore(collectionName);
            
            List<Embedding> embeddings = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();
            
            for (Document document : documents) {
                Embedding embedding = Embedding.from(document.getEmbedding());
                
                Map<String, String> metadataMap = new HashMap<>();
                metadataMap.put("title", document.getTitle() != null ? document.getTitle() : "");
                metadataMap.put("source", document.getSource() != null ? document.getSource() : "");
                metadataMap.put("category", document.getCategory() != null ? document.getCategory() : "");
                
                if (document.getTags() != null && !document.getTags().isEmpty()) {
                    metadataMap.put("tags", String.join(",", document.getTags()));
                }
                
                Metadata metadata = Metadata.from(metadataMap);
                
                TextSegment segment = TextSegment.from(
                    document.getContent() != null ? document.getContent() : "",
                    metadata
                );
                
                embeddings.add(embedding);
                segments.add(segment);
            }
            
            store.addAll(embeddings, segments);
            log.debug("Batch upserted {} documents to collection {}", documents.size(), collectionName);
        } catch (Exception e) {
            log.error("Failed to batch upsert to {}: {}", collectionName, e.getMessage());
        }
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

        try {
            QdrantEmbeddingStore store = getStore(collectionName);
            Embedding queryEmbedding = Embedding.from(queryVector);
            
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
            
            List<String> tags = new ArrayList<>();
            if (tagsStr != null && !tagsStr.isEmpty()) {
                tags = Arrays.asList(tagsStr.split(","));
            }
            
            float[] embedding = null;
            if (match.embedding() != null) {
                float[] vector = match.embedding().vector();
                embedding = Arrays.copyOf(vector, vector.length);
            }
            
            return Document.builder()
                    .id(UUID.randomUUID().toString())
                    .content(segment.text())
                    .title(title)
                    .source(source)
                    .category(category)
                    .tags(tags)
                    .embedding(embedding)
                    .score(match.score())
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse document: {}", e.getMessage());
            return null;
        }
    }
}