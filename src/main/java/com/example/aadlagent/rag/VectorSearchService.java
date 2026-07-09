
package com.example.aadlagent.rag;

import com.example.aadlagent.rag.model.Document;
import com.example.aadlagent.config.RagConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class VectorSearchService {

    private final EmbeddingService embeddingService;
    private final RagConfig ragConfig;
    private final Map<String, Document> documentStore = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String KNOWLEDGE_DIR = "./knowledge";
    private static final String STORE_FILE = KNOWLEDGE_DIR + "/store.json";

    private volatile boolean initialized = false;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public VectorSearchService(EmbeddingService embeddingService, RagConfig ragConfig) {
        this.embeddingService = embeddingService;
        this.ragConfig = ragConfig;
    }

    @PostConstruct
    public void init() {
        executorService.submit(this::loadDocumentsAsync);
    }

    private void loadDocumentsAsync() {
        try {
            loadDocuments();
        } catch (Exception e) {
            log.error("Failed to load documents asynchronously: {}", e.getMessage());
        }
    }

    private void loadDocuments() {
        Path storePath = Paths.get(STORE_FILE);
        if (Files.exists(storePath)) {
            try {
                String json = Files.readString(storePath);
                List<Document> documents = objectMapper.readValue(json, new TypeReference<List<Document>>() {});
                for (Document doc : documents) {
                    documentStore.put(doc.getId(), doc);
                }
                log.info("Loaded {} documents from store", documentStore.size());
            } catch (IOException e) {
                log.error("Failed to load documents from store: {}", e.getMessage());
                loadKnowledgeFiles();
            }
        } else {
            log.info("No store file found, loading from knowledge directory");
            loadKnowledgeFiles();
        }
        initialized = true;
    }

    private void loadKnowledgeFiles() {
        Path knowledgePath = Paths.get(KNOWLEDGE_DIR);
        if (!Files.exists(knowledgePath)) {
            log.warn("Knowledge directory not found: {}", KNOWLEDGE_DIR);
            return;
        }

        boolean embeddingAvailable = embeddingService.isAvailable();
        if (!embeddingAvailable) {
            log.warn("Embedding service is not available, skipping embedding generation. Documents will be loaded without vectors.");
        }

        try {
            Files.walk(knowledgePath)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path);
                            String fileName = path.getFileName().toString();
                            String title = fileName.substring(0, fileName.lastIndexOf('.'));
                            
                            List<String> chunks = TextChunker.chunk(content, 500, 100);
                            for (int i = 0; i < chunks.size(); i++) {
                                String id = "KNOW-" + fileName.hashCode() + "-" + i;
                                float[] embedding = null;
                                
                                if (embeddingAvailable) {
                                    embedding = embeddingService.embed(chunks.get(i));
                                }
                                
                                Document doc = Document.builder()
                                        .id(id)
                                        .content(chunks.get(i))
                                        .title(title + " (chunk " + (i + 1) + ")")
                                        .source(fileName)
                                        .embedding(embedding)
                                        .build();
                                
                                documentStore.put(id, doc);
                            }
                            log.info("Loaded knowledge file: {} ({} chunks)", fileName, chunks.size());
                        } catch (IOException e) {
                            log.error("Failed to load knowledge file: {}", path, e);
                        }
                    });
            
            saveDocuments();
            log.info("Loaded {} total knowledge chunks", documentStore.size());
        } catch (IOException e) {
            log.error("Failed to walk knowledge directory: {}", e.getMessage());
        }
    }

    private void saveDocuments() {
        try {
            Path knowledgePath = Paths.get(KNOWLEDGE_DIR);
            if (!Files.exists(knowledgePath)) {
                Files.createDirectories(knowledgePath);
            }
            
            List<Document> documents = new ArrayList<>(documentStore.values());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(documents);
            Files.writeString(Paths.get(STORE_FILE), json);
            log.debug("Saved {} documents to store", documents.size());
        } catch (IOException e) {
            log.error("Failed to save documents to store: {}", e.getMessage());
        }
    }

    public void addDocument(Document document) {
        if (document.getEmbedding() == null) {
            float[] embedding = embeddingService.embed(document.getContent());
            document.setEmbedding(embedding);
        }
        documentStore.put(document.getId(), document);
        saveDocuments();
        log.info("Added document: {}", document.getId());
    }

    public void removeDocument(String id) {
        documentStore.remove(id);
        saveDocuments();
        log.info("Removed document: {}", id);
    }

    public List<Document> search(String query, int topK) {
        return search(query, new ArrayList<>(documentStore.values()), topK);
    }

    public List<Document> search(String query, List<Document> documents, int topK) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        log.info("Searching for: {}", query);

        float[] queryEmbedding = embeddingService.embed(query);
        if (queryEmbedding == null) {
            log.warn("Failed to generate query embedding");
            return Collections.emptyList();
        }

        List<Map.Entry<String, Double>> scores = new ArrayList<>();

        for (Document doc : documents) {
            if (doc.getEmbedding() == null) {
                continue;
            }
            double similarity = cosineSimilarity(queryEmbedding, doc.getEmbedding());
            scores.add(new AbstractMap.SimpleEntry<>(doc.getId(), similarity));
        }

        scores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int limit = Math.min(topK, scores.size());
        List<Document> results = new ArrayList<>();

        for (int i = 0; i < limit; i++) {
            String docId = scores.get(i).getKey();
            Document doc = documents.stream().filter(d -> d.getId().equals(docId)).findFirst().orElse(null);
            if (doc != null) {
                doc.setScore(scores.get(i).getValue());
                results.add(doc);
            }
        }

        log.info("Search completed, found {} results", results.size());

        return results;
    }

    public List<Document> hybridSearch(String query, int topK) {
        return hybridSearch(query, new ArrayList<>(documentStore.values()), topK);
    }

    public List<Document> hybridSearch(String query, List<Document> documents, int topK) {
        List<Document> vectorResults = search(query, documents, topK);

        List<Document> keywordResults = keywordSearch(query, documents, topK);

        List<List<Document>> allResults = Arrays.asList(vectorResults, keywordResults);

        RrfFusion rrfFusion = new RrfFusion(ragConfig);
        return rrfFusion.fuse(allResults);
    }

    private List<Document> keywordSearch(String query, int topK) {
        return keywordSearch(query, new ArrayList<>(documentStore.values()), topK);
    }

    private List<Document> keywordSearch(String query, List<Document> documents, int topK) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<Map.Entry<String, Double>> scores = new ArrayList<>();

        String[] keywords = query.toLowerCase().split("\\s+");

        for (Document doc : documents) {
            String content = doc.getContent().toLowerCase();
            int matchCount = 0;

            for (String keyword : keywords) {
                if (content.contains(keyword)) {
                    matchCount++;
                }
            }

            if (matchCount > 0) {
                double score = (double) matchCount / keywords.length;
                scores.add(new AbstractMap.SimpleEntry<>(doc.getId(), score));
            }
        }

        scores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int limit = Math.min(topK, scores.size());
        List<Document> results = new ArrayList<>();

        for (int i = 0; i < limit; i++) {
            String docId = scores.get(i).getKey();
            Document doc = documents.stream().filter(d -> d.getId().equals(docId)).findFirst().orElse(null);
            if (doc != null) {
                doc.setScore(scores.get(i).getValue());
                results.add(doc);
            }
        }

        return results;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public int getDocumentCount() {
        return documentStore.size();
    }

    public List<Document> getAllDocuments() {
        return new ArrayList<>(documentStore.values());
    }

    public void reload() {
        documentStore.clear();
        initialized = false;
        loadDocuments();
    }
}
