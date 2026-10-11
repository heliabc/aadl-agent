package com.example.aadlagent.rag;

import com.example.aadlagent.rag.model.Document;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 本地向量存储（纯内存 + JSON 持久化）
 * 
 * 替代 Qdrant，无需外部依赖，适合本地开发和小规模知识库场景。
 * 向量检索使用纯 Java 实现的余弦相似度计算。
 */
@Slf4j
@Component
public class LocalVectorStore {

    private static final String STORAGE_FILE = "./output/local_vector_store.json";

    /**
     * 内存存储：collectionName -> (docId -> Document)
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Document>> collections = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public LocalVectorStore() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        loadFromDisk();
        log.info("LocalVectorStore initialized, {} collections loaded", collections.size());
    }

    @PreDestroy
    public void shutdown() {
        saveToDisk();
        log.info("LocalVectorStore persisted to disk");
    }

    // ========== 集合管理 ==========

    public List<String> listCollections() {
        return new ArrayList<>(collections.keySet());
    }

    public void ensureCollection(String collectionName) {
        collections.computeIfAbsent(collectionName, k -> new ConcurrentHashMap<>());
    }

    public boolean isAvailable() {
        return true; // 本地存储始终可用
    }

    public long count(String collectionName) {
        ConcurrentHashMap<String, Document> col = collections.get(collectionName);
        return col != null ? col.size() : 0;
    }

    public void clearCollection(String collectionName) {
        ConcurrentHashMap<String, Document> col = collections.get(collectionName);
        if (col != null) {
            col.clear();
        }
        saveToDisk();
        log.info("Cleared collection: {}", collectionName);
    }

    // ========== 写入操作 ==========

    public void upsert(String collectionName, Document document) {
        ensureCollection(collectionName);
        collections.get(collectionName).put(document.getId(), document);
        saveToDisk();
    }

    public void upsertBatch(String collectionName, List<Document> documents) {
        ensureCollection(collectionName);
        ConcurrentHashMap<String, Document> col = collections.get(collectionName);
        for (Document doc : documents) {
            col.put(doc.getId(), doc);
        }
        saveToDisk();
        log.info("LocalVectorStore: batch upserted {} documents to collection {}", documents.size(), collectionName);
    }

    public void delete(String collectionName, String documentId) {
        ConcurrentHashMap<String, Document> col = collections.get(collectionName);
        if (col != null) {
            col.remove(documentId);
            saveToDisk();
        }
    }

    // ========== 向量检索 ==========

    /**
     * 余弦相似度向量搜索
     */
    public List<Document> search(String collectionName, float[] queryVector, int topK) {
        ConcurrentHashMap<String, Document> col = collections.get(collectionName);
        if (col == null || col.isEmpty() || queryVector == null) {
            return new ArrayList<>();
        }

        List<Document> results = new ArrayList<>();

        for (Document doc : col.values()) {
            if (doc.getEmbedding() == null || doc.getEmbedding().length == 0) continue;

            double similarity = cosineSimilarity(queryVector, doc.getEmbedding());

            // 用 score 字段存储相似度
            Document scoredDoc = cloneDocument(doc);
            scoredDoc.setScore(similarity);
            results.add(scoredDoc);
        }

        // 按相似度降序排序，取 topK
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        return results.stream()
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 关键词搜索（简单的字符串匹配，按命中次数排序）
     */
    public List<Document> keywordSearch(String collectionName, String query, int topK) {
        ConcurrentHashMap<String, Document> col = collections.get(collectionName);
        if (col == null || col.isEmpty() || query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String[] keywords = query.trim().toLowerCase().split("\\s+");
        List<Document> results = new ArrayList<>();

        for (Document doc : col.values()) {
            String text = (doc.getTitle() + " " + doc.getContent()).toLowerCase();
            int hitCount = 0;
            for (String kw : keywords) {
                if (text.contains(kw)) hitCount++;
            }
            if (hitCount > 0) {
                Document scoredDoc = cloneDocument(doc);
                scoredDoc.setScore(hitCount);
                results.add(scoredDoc);
            }
        }

        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        return results.stream()
                .limit(topK)
                .collect(Collectors.toList());
    }

    public List<Document> getAllDocuments(String collectionName) {
        ConcurrentHashMap<String, Document> col = collections.get(collectionName);
        if (col == null) return new ArrayList<>();
        return new ArrayList<>(col.values());
    }

    // ========== 工具方法 ==========

    /**
     * 余弦相似度计算
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) return 0.0;

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private Document cloneDocument(Document doc) {
        return Document.builder()
                .id(doc.getId())
                .content(doc.getContent())
                .title(doc.getTitle())
                .source(doc.getSource())
                .category(doc.getCategory())
                .agentType(doc.getAgentType())
                .tags(doc.getTags() != null ? new ArrayList<>(doc.getTags()) : null)
                .embedding(doc.getEmbedding()) // 引用即可，不修改
                .score(doc.getScore())
                .payload(doc.getPayload())
                .hardwareInterfaces(doc.getHardwareInterfaces())
                .sensors(doc.getSensors())
                .actuators(doc.getActuators())
                .applicationDomains(doc.getApplicationDomains())
                .safetyLevels(doc.getSafetyLevels())
                .schedulingPolicies(doc.getSchedulingPolicies())
                .build();
    }

    // ========== 持久化 ==========

    private void saveToDisk() {
        try {
            Path path = Paths.get(STORAGE_FILE);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            // 转换为可序列化结构：Map<collectionName, List<Document>>
            Map<String, List<Document>> serializable = new HashMap<>();
            for (Map.Entry<String, ConcurrentHashMap<String, Document>> entry : collections.entrySet()) {
                serializable.put(entry.getKey(), new ArrayList<>(entry.getValue().values()));
            }
            String json = objectMapper.writeValueAsString(serializable);
            Files.writeString(path, json);
        } catch (IOException e) {
            log.error("Failed to persist local vector store: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        try {
            Path path = Paths.get(STORAGE_FILE);
            if (!Files.exists(path)) {
                log.info("No local vector store file found, starting fresh");
                return;
            }
            String json = Files.readString(path);
            Map<String, List<Document>> data = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(
                            HashMap.class, String.class,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Document.class)
                    ));

            for (Map.Entry<String, List<Document>> entry : data.entrySet()) {
                ConcurrentHashMap<String, Document> col = new ConcurrentHashMap<>();
                for (Document doc : entry.getValue()) {
                    col.put(doc.getId(), doc);
                }
                collections.put(entry.getKey(), col);
            }
            log.info("Loaded local vector store from disk: {} collections", collections.size());
            for (String name : collections.keySet()) {
                log.info("  - {}: {} documents", name, collections.get(name).size());
            }
        } catch (IOException e) {
            log.error("Failed to load local vector store: {}", e.getMessage());
        }
    }
}
