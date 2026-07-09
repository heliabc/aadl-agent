package com.example.aadlagent.rag;

import com.example.aadlagent.rag.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KnowledgeBaseManager {

    private static final String KNOWLEDGE_ROOT = "./knowledge";
    private static final String[] AGENT_TYPES = {"requirement", "architecture", "module", "aadl"};

    private final EmbeddingService embeddingService;
    private final QdrantVectorStore qdrantVectorStore;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, KnowledgeBase> knowledgeBases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastModifiedTimes = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public KnowledgeBaseManager(EmbeddingService embeddingService, QdrantVectorStore qdrantVectorStore) {
        this.embeddingService = embeddingService;
        this.qdrantVectorStore = qdrantVectorStore;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        executorService.submit(this::loadAllKnowledgeBases);
        executorService.submit(this::startFileWatcher);
    }

    private void loadAllKnowledgeBases() {
        try {
            Path rootPath = Paths.get(KNOWLEDGE_ROOT);
            if (!Files.exists(rootPath)) {
                Files.createDirectories(rootPath);
            }

            for (String agentType : AGENT_TYPES) {
                loadKnowledgeBase(agentType);
            }
            log.info("All knowledge bases loaded");
        } catch (Exception e) {
            log.error("Failed to load knowledge bases: {}", e.getMessage());
        }
    }

    private void loadKnowledgeBase(String agentType) {
        Path kbPath = Paths.get(KNOWLEDGE_ROOT, agentType + ".json");
        
        if (!Files.exists(kbPath)) {
            KnowledgeBase kb = KnowledgeBase.builder()
                    .agentType(agentType)
                    .basics(new ArrayList<>())
                    .examples(new ArrayList<>())
                    .errorCorrections(new ArrayList<>())
                    .lastModified(LocalDateTime.now())
                    .build();
            knowledgeBases.put(agentType, kb);
            lastModifiedTimes.put(agentType, 0L);
            log.info("Knowledge base not found, creating empty: {}", agentType);
            saveKnowledgeBase(agentType);
            return;
        }

        try {
            long modifiedTime = Files.getLastModifiedTime(kbPath).toMillis();
            if (modifiedTime == lastModifiedTimes.getOrDefault(agentType, 0L)) {
                return;
            }

            String json = Files.readString(kbPath);
            
            KnowledgeBase kb;
            try {
                kb = objectMapper.readValue(json, KnowledgeBase.class);
            } catch (Exception e) {
                kb = convertLegacyFormat(json, agentType);
            }
            
            if (kb == null) {
                kb = KnowledgeBase.builder()
                        .agentType(agentType)
                        .basics(new ArrayList<>())
                        .examples(new ArrayList<>())
                        .errorCorrections(new ArrayList<>())
                        .lastModified(LocalDateTime.now())
                        .build();
            }
            
            kb.setAgentType(agentType);

            boolean embeddingAvailable = embeddingService.isAvailable();
            boolean qdrantAvailable = qdrantVectorStore.isAvailable();
            
            List<Document> docsToUpsert = new ArrayList<>();
            
            for (BasicKnowledge basic : kb.getBasics()) {
                if (basic.getEmbedding() == null && embeddingAvailable) {
                    try {
                        basic.setEmbedding(embeddingService.embed(basic.getContent()));
                    } catch (Exception e) {
                        log.warn("Failed to generate embedding for basic: {}", basic.getId());
                    }
                }
                if (qdrantAvailable && basic.getEmbedding() != null) {
                    docsToUpsert.add(toDocument(basic, agentType, "basic"));
                }
            }
            
            for (ExampleKnowledge example : kb.getExamples()) {
                String content = example.getScenario() + "\n" + example.getInput() + "\n" + example.getOutput() + "\n" + example.getExplanation();
                if (example.getEmbedding() == null && embeddingAvailable) {
                    try {
                        example.setEmbedding(embeddingService.embed(content));
                    } catch (Exception e) {
                        log.warn("Failed to generate embedding for example: {}", example.getId());
                    }
                }
                if (qdrantAvailable && example.getEmbedding() != null) {
                    docsToUpsert.add(toDocument(example, agentType, "example"));
                }
            }
            
            for (ErrorCorrection ec : kb.getErrorCorrections()) {
                String content = ec.getErrorContent() + "\n" + ec.getCorrectContent() + "\n" + ec.getCorrectionExplanation();
                if (ec.getEmbedding() == null && embeddingAvailable) {
                    try {
                        ec.setEmbedding(embeddingService.embed(content));
                    } catch (Exception e) {
                        log.warn("Failed to generate embedding for error correction: {}", ec.getId());
                    }
                }
                if (qdrantAvailable && ec.getEmbedding() != null) {
                    docsToUpsert.add(toDocument(ec, agentType, "error_correction"));
                }
            }

            knowledgeBases.put(agentType, kb);
            lastModifiedTimes.put(agentType, modifiedTime);
            
            if (!docsToUpsert.isEmpty()) {
                qdrantVectorStore.upsertBatch(agentType, docsToUpsert);
            }
            
            log.info("Loaded knowledge base {}: {} basics, {} examples, {} corrections (qdrant: {})", 
                    agentType, kb.getBasics().size(), kb.getExamples().size(), 
                    kb.getErrorCorrections().size(), qdrantAvailable);
        } catch (IOException e) {
            log.error("Failed to load knowledge base {}: {}", agentType, e.getMessage());
            knowledgeBases.put(agentType, createEmptyKnowledgeBase(agentType));
        }
    }

    private KnowledgeBase convertLegacyFormat(String json, String agentType) {
        try {
            List<KnowledgeEntry> entries = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<KnowledgeEntry>>() {});
            if (entries == null || entries.isEmpty()) {
                return createEmptyKnowledgeBase(agentType);
            }
            
            KnowledgeBase kb = createEmptyKnowledgeBase(agentType);
            
            for (KnowledgeEntry entry : entries) {
                String category = entry.getCategory();
                if (category != null && category.contains("语法")) {
                    kb.getBasics().add(BasicKnowledge.builder()
                            .id(entry.getId())
                            .title(entry.getTitle())
                            .content(entry.getContent())
                            .section(category)
                            .tags(entry.getTags())
                            .createdAt(entry.getCreatedAt())
                            .updatedAt(entry.getUpdatedAt())
                            .embedding(entry.getEmbedding())
                            .build());
                } else if (category != null && category.contains("示例")) {
                    kb.getExamples().add(ExampleKnowledge.builder()
                            .id(entry.getId())
                            .title(entry.getTitle())
                            .scenario(entry.getContent())
                            .tags(entry.getTags())
                            .createdAt(entry.getCreatedAt())
                            .updatedAt(entry.getUpdatedAt())
                            .embedding(entry.getEmbedding())
                            .build());
                } else if (category != null && category.contains("错误")) {
                    kb.getErrorCorrections().add(ErrorCorrection.builder()
                            .id(entry.getId())
                            .title(entry.getTitle())
                            .errorContent(entry.getContent())
                            .tags(entry.getTags())
                            .createdAt(entry.getCreatedAt())
                            .updatedAt(entry.getUpdatedAt())
                            .embedding(entry.getEmbedding())
                            .build());
                } else {
                    kb.getBasics().add(BasicKnowledge.builder()
                            .id(entry.getId())
                            .title(entry.getTitle())
                            .content(entry.getContent())
                            .section(category)
                            .tags(entry.getTags())
                            .createdAt(entry.getCreatedAt())
                            .updatedAt(entry.getUpdatedAt())
                            .embedding(entry.getEmbedding())
                            .build());
                }
            }
            return kb;
        } catch (Exception e) {
            log.warn("Failed to convert legacy format: {}", e.getMessage());
            return createEmptyKnowledgeBase(agentType);
        }
    }

    private KnowledgeBase createEmptyKnowledgeBase(String agentType) {
        return KnowledgeBase.builder()
                .agentType(agentType)
                .basics(new ArrayList<>())
                .examples(new ArrayList<>())
                .errorCorrections(new ArrayList<>())
                .lastModified(LocalDateTime.now())
                .build();
    }

    private void saveKnowledgeBase(String agentType) {
        try {
            Path kbPath = Paths.get(KNOWLEDGE_ROOT, agentType + ".json");
            KnowledgeBase kb = knowledgeBases.getOrDefault(agentType, createEmptyKnowledgeBase(agentType));
            kb.setLastModified(LocalDateTime.now());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(kb);
            Files.writeString(kbPath, json);
            lastModifiedTimes.put(agentType, System.currentTimeMillis());
            log.debug("Saved knowledge base: {} ({} entries)", agentType, kb.getTotalEntries());
        } catch (IOException e) {
            log.error("Failed to save knowledge base {}: {}", agentType, e.getMessage());
        }
    }

    private void startFileWatcher() {
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            Path rootPath = Paths.get(KNOWLEDGE_ROOT);
            
            if (Files.exists(rootPath)) {
                rootPath.register(watchService, 
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
            }

            log.info("Started file watcher for knowledge directory");

            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    Path fileName = (Path) event.context();
                    
                    if (kind == StandardWatchEventKinds.ENTRY_MODIFY || 
                        kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        String name = fileName.toString();
                        if (name.endsWith(".json")) {
                            String agentType = name.substring(0, name.lastIndexOf('.'));
                            log.info("Detected change in knowledge base: {}", agentType);
                            loadKnowledgeBase(agentType);
                        }
                    }
                }
                key.reset();
            }
        } catch (Exception e) {
            log.error("File watcher stopped: {}", e.getMessage());
        }
    }

    public KnowledgeBase getKnowledgeBase(String agentType) {
        loadKnowledgeBase(agentType);
        return knowledgeBases.getOrDefault(agentType, createEmptyKnowledgeBase(agentType));
    }

    public List<BasicKnowledge> getBasics(String agentType) {
        return getKnowledgeBase(agentType).getBasics();
    }

    public List<ExampleKnowledge> getExamples(String agentType) {
        return getKnowledgeBase(agentType).getExamples();
    }

    public List<ErrorCorrection> getErrorCorrections(String agentType) {
        return getKnowledgeBase(agentType).getErrorCorrections();
    }

    public BasicKnowledge getBasic(String agentType, String basicId) {
        return getBasics(agentType).stream()
                .filter(b -> b.getId().equals(basicId))
                .findFirst()
                .orElse(null);
    }

    public ExampleKnowledge getExample(String agentType, String exampleId) {
        return getExamples(agentType).stream()
                .filter(e -> e.getId().equals(exampleId))
                .findFirst()
                .orElse(null);
    }

    public ErrorCorrection getErrorCorrection(String agentType, String ecId) {
        return getErrorCorrections(agentType).stream()
                .filter(e -> e.getId().equals(ecId))
                .findFirst()
                .orElse(null);
    }

    public BasicKnowledge addBasic(String agentType, BasicKnowledge basic) {
        KnowledgeBase kb = getKnowledgeBase(agentType);
        
        if (basic.getId() == null || basic.getId().isEmpty()) {
            basic.setId("BASIC-" + UUID.randomUUID().toString().substring(0, 8));
        }
        
        LocalDateTime now = LocalDateTime.now();
        basic.setCreatedAt(now);
        basic.setUpdatedAt(now);
        
        if (basic.getEmbedding() == null) {
            try {
                basic.setEmbedding(embeddingService.embed(basic.getContent()));
            } catch (Exception e) {
                log.warn("Failed to generate embedding for basic knowledge");
            }
        }
        
        kb.getBasics().add(basic);
        knowledgeBases.put(agentType, kb);
        saveKnowledgeBase(agentType);
        
        if (qdrantVectorStore.isAvailable() && basic.getEmbedding() != null) {
            qdrantVectorStore.upsert(agentType, toDocument(basic, agentType, "basic"));
        }
        
        log.info("Added basic knowledge to {}: {}", agentType, basic.getId());
        return basic;
    }

    public ExampleKnowledge addExample(String agentType, ExampleKnowledge example) {
        KnowledgeBase kb = getKnowledgeBase(agentType);
        
        if (example.getId() == null || example.getId().isEmpty()) {
            example.setId("EXAMPLE-" + UUID.randomUUID().toString().substring(0, 8));
        }
        
        LocalDateTime now = LocalDateTime.now();
        example.setCreatedAt(now);
        example.setUpdatedAt(now);
        
        String content = example.getScenario() + "\n" + example.getInput() + "\n" + example.getOutput() + "\n" + (example.getExplanation() != null ? example.getExplanation() : "");
        if (example.getEmbedding() == null) {
            try {
                example.setEmbedding(embeddingService.embed(content));
            } catch (Exception e) {
                log.warn("Failed to generate embedding for example");
            }
        }
        
        kb.getExamples().add(example);
        knowledgeBases.put(agentType, kb);
        saveKnowledgeBase(agentType);
        
        if (qdrantVectorStore.isAvailable() && example.getEmbedding() != null) {
            qdrantVectorStore.upsert(agentType, toDocument(example, agentType, "example"));
        }
        
        log.info("Added example to {}: {}", agentType, example.getId());
        return example;
    }

    public ErrorCorrection addErrorCorrection(String agentType, ErrorCorrection ec) {
        KnowledgeBase kb = getKnowledgeBase(agentType);
        
        if (ec.getId() == null || ec.getId().isEmpty()) {
            ec.setId("EC-" + UUID.randomUUID().toString().substring(0, 8));
        }
        
        LocalDateTime now = LocalDateTime.now();
        ec.setCreatedAt(now);
        ec.setUpdatedAt(now);
        
        String content = ec.getErrorContent() + "\n" + ec.getCorrectContent() + "\n" + (ec.getCorrectionExplanation() != null ? ec.getCorrectionExplanation() : "");
        if (ec.getEmbedding() == null) {
            try {
                ec.setEmbedding(embeddingService.embed(content));
            } catch (Exception e) {
                log.warn("Failed to generate embedding for error correction");
            }
        }
        
        kb.getErrorCorrections().add(ec);
        knowledgeBases.put(agentType, kb);
        saveKnowledgeBase(agentType);
        
        if (qdrantVectorStore.isAvailable() && ec.getEmbedding() != null) {
            qdrantVectorStore.upsert(agentType, toDocument(ec, agentType, "error_correction"));
        }
        
        log.info("Added error correction to {}: {}", agentType, ec.getId());
        return ec;
    }

    public boolean updateBasic(String agentType, String basicId, BasicKnowledge updated) {
        KnowledgeBase kb = getKnowledgeBase(agentType);
        for (BasicKnowledge basic : kb.getBasics()) {
            if (basic.getId().equals(basicId)) {
                if (updated.getTitle() != null) basic.setTitle(updated.getTitle());
                if (updated.getContent() != null) {
                    basic.setContent(updated.getContent());
                    try {
                        basic.setEmbedding(embeddingService.embed(updated.getContent()));
                    } catch (Exception e) {
                        log.warn("Failed to regenerate embedding for basic knowledge");
                    }
                }
                if (updated.getSection() != null) basic.setSection(updated.getSection());
                if (updated.getTags() != null) basic.setTags(updated.getTags());
                basic.setUpdatedAt(LocalDateTime.now());
                
                knowledgeBases.put(agentType, kb);
                saveKnowledgeBase(agentType);
                
                if (qdrantVectorStore.isAvailable() && basic.getEmbedding() != null) {
                    qdrantVectorStore.upsert(agentType, toDocument(basic, agentType, "basic"));
                }
                
                log.info("Updated basic knowledge in {}: {}", agentType, basicId);
                return true;
            }
        }
        return false;
    }

    public boolean updateExample(String agentType, String exampleId, ExampleKnowledge updated) {
        KnowledgeBase kb = getKnowledgeBase(agentType);
        for (ExampleKnowledge example : kb.getExamples()) {
            if (example.getId().equals(exampleId)) {
                if (updated.getTitle() != null) example.setTitle(updated.getTitle());
                if (updated.getScenario() != null) example.setScenario(updated.getScenario());
                if (updated.getInput() != null) example.setInput(updated.getInput());
                if (updated.getOutput() != null) example.setOutput(updated.getOutput());
                if (updated.getExplanation() != null) example.setExplanation(updated.getExplanation());
                if (updated.getTags() != null) example.setTags(updated.getTags());
                
                String content = example.getScenario() + "\n" + example.getInput() + "\n" + example.getOutput() + "\n" + (example.getExplanation() != null ? example.getExplanation() : "");
                try {
                    example.setEmbedding(embeddingService.embed(content));
                } catch (Exception e) {
                    log.warn("Failed to regenerate embedding for example");
                }
                example.setUpdatedAt(LocalDateTime.now());
                
                knowledgeBases.put(agentType, kb);
                saveKnowledgeBase(agentType);
                
                if (qdrantVectorStore.isAvailable() && example.getEmbedding() != null) {
                    qdrantVectorStore.upsert(agentType, toDocument(example, agentType, "example"));
                }
                
                log.info("Updated example in {}: {}", agentType, exampleId);
                return true;
            }
        }
        return false;
    }

    public boolean updateErrorCorrection(String agentType, String ecId, ErrorCorrection updated) {
        KnowledgeBase kb = getKnowledgeBase(agentType);
        for (ErrorCorrection ec : kb.getErrorCorrections()) {
            if (ec.getId().equals(ecId)) {
                if (updated.getTitle() != null) ec.setTitle(updated.getTitle());
                if (updated.getErrorType() != null) ec.setErrorType(updated.getErrorType());
                if (updated.getErrorContent() != null) ec.setErrorContent(updated.getErrorContent());
                if (updated.getErrorDescription() != null) ec.setErrorDescription(updated.getErrorDescription());
                if (updated.getCorrectContent() != null) ec.setCorrectContent(updated.getCorrectContent());
                if (updated.getCorrectionExplanation() != null) ec.setCorrectionExplanation(updated.getCorrectionExplanation());
                if (updated.getSuggestion() != null) ec.setSuggestion(updated.getSuggestion());
                if (updated.getTags() != null) ec.setTags(updated.getTags());
                
                String content = ec.getErrorContent() + "\n" + ec.getCorrectContent() + "\n" + (ec.getCorrectionExplanation() != null ? ec.getCorrectionExplanation() : "");
                try {
                    ec.setEmbedding(embeddingService.embed(content));
                } catch (Exception e) {
                    log.warn("Failed to regenerate embedding for error correction");
                }
                ec.setUpdatedAt(LocalDateTime.now());
                
                knowledgeBases.put(agentType, kb);
                saveKnowledgeBase(agentType);
                
                if (qdrantVectorStore.isAvailable() && ec.getEmbedding() != null) {
                    qdrantVectorStore.upsert(agentType, toDocument(ec, agentType, "error_correction"));
                }
                
                log.info("Updated error correction in {}: {}", agentType, ecId);
                return true;
            }
        }
        return false;
    }

    public boolean deleteBasic(String agentType, String basicId) {
        KnowledgeBase kb = getKnowledgeBase(agentType);
        boolean removed = kb.getBasics().removeIf(b -> b.getId().equals(basicId));
        
        if (removed) {
            knowledgeBases.put(agentType, kb);
            saveKnowledgeBase(agentType);
            
            if (qdrantVectorStore.isAvailable()) {
                qdrantVectorStore.delete(agentType, basicId);
            }
            
            log.info("Deleted basic knowledge from {}: {}", agentType, basicId);
        }
        return removed;
    }

    public boolean deleteExample(String agentType, String exampleId) {
        KnowledgeBase kb = getKnowledgeBase(agentType);
        boolean removed = kb.getExamples().removeIf(e -> e.getId().equals(exampleId));
        
        if (removed) {
            knowledgeBases.put(agentType, kb);
            saveKnowledgeBase(agentType);
            
            if (qdrantVectorStore.isAvailable()) {
                qdrantVectorStore.delete(agentType, exampleId);
            }
            
            log.info("Deleted example from {}: {}", agentType, exampleId);
        }
        return removed;
    }

    public boolean deleteErrorCorrection(String agentType, String ecId) {
        KnowledgeBase kb = getKnowledgeBase(agentType);
        boolean removed = kb.getErrorCorrections().removeIf(e -> e.getId().equals(ecId));
        
        if (removed) {
            knowledgeBases.put(agentType, kb);
            saveKnowledgeBase(agentType);
            
            if (qdrantVectorStore.isAvailable()) {
                qdrantVectorStore.delete(agentType, ecId);
            }
            
            log.info("Deleted error correction from {}: {}", agentType, ecId);
        }
        return removed;
    }

    public void reloadKnowledgeBase(String agentType) {
        loadKnowledgeBase(agentType);
        log.info("Reloaded knowledge base: {}", agentType);
    }

    public List<String> listKnowledgeBases() {
        return Arrays.asList(AGENT_TYPES);
    }

    public int getTotalEntryCount(String agentType) {
        return getKnowledgeBase(agentType).getTotalEntries();
    }

    public boolean isQdrantAvailable() {
        return qdrantVectorStore.isAvailable();
    }

    public List<Document> toDocuments(String agentType) {
        KnowledgeBase kb = getKnowledgeBase(agentType);
        List<Document> documents = new ArrayList<>();
        
        for (BasicKnowledge basic : kb.getBasics()) {
            documents.add(toDocument(basic, agentType, "basic"));
        }
        for (ExampleKnowledge example : kb.getExamples()) {
            documents.add(toDocument(example, agentType, "example"));
        }
        for (ErrorCorrection ec : kb.getErrorCorrections()) {
            documents.add(toDocument(ec, agentType, "error_correction"));
        }
        
        return documents;
    }

    private Document toDocument(BasicKnowledge basic, String agentType, String type) {
        return Document.builder()
                .id(basic.getId())
                .content(basic.getContent())
                .title(basic.getTitle())
                .source(agentType)
                .category(type)
                .tags(basic.getTags())
                .embedding(basic.getEmbedding())
                .build();
    }

    private Document toDocument(ExampleKnowledge example, String agentType, String type) {
        String content = example.getScenario() + "\n输入: " + example.getInput() + "\n输出: " + example.getOutput() + 
                (example.getExplanation() != null ? "\n解释: " + example.getExplanation() : "");
        return Document.builder()
                .id(example.getId())
                .content(content)
                .title(example.getTitle())
                .source(agentType)
                .category(type)
                .tags(example.getTags())
                .embedding(example.getEmbedding())
                .build();
    }

    private Document toDocument(ErrorCorrection ec, String agentType, String type) {
        String content = "错误类型: " + ec.getErrorType() + "\n错误内容: " + ec.getErrorContent() + 
                "\n错误描述: " + ec.getErrorDescription() + "\n正确内容: " + ec.getCorrectContent() + 
                "\n修复说明: " + ec.getCorrectionExplanation() + (ec.getSuggestion() != null ? "\n建议: " + ec.getSuggestion() : "");
        return Document.builder()
                .id(ec.getId())
                .content(content)
                .title(ec.getTitle())
                .source(agentType)
                .category(type)
                .tags(ec.getTags())
                .embedding(ec.getEmbedding())
                .build();
    }
}