package com.example.aadlplugin.rag;

import com.example.aadlplugin.rag.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class KnowledgeBaseManager {
    
    private static final Logger log = Logger.getLogger(KnowledgeBaseManager.class.getName());

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

    public void init() {
        performStartupSelfCheck();
        loadAllKnowledgeBases();
        executorService.submit(this::startFileWatcher);
    }

    private void performStartupSelfCheck() {
        log.info("=== Starting Qdrant startup self-check ===");
        
        int maxRetries = 60;
        int delayMs = 1000;
        
        for (String collection : AGENT_TYPES) {
            log.info(String.format("Checking collection: %s", collection));
            
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    List<String> existingCollections = qdrantVectorStore.listCollections();
                    if (existingCollections.contains(collection)) {
                        log.info(String.format("Collection '%s' exists - OK", collection));
                        break;
                    }
                    
                    log.warning(String.format("Collection '%s' not found, attempting creation... (attempt %d/%d)", collection, attempt, maxRetries));
                    qdrantVectorStore.search(collection, new float[384], 1);
                    
                } catch (Exception e) {
                    log.warning(String.format("Failed to check/create collection '%s': %s (attempt %d/%d)", collection, e.getMessage(), attempt, maxRetries));
                }
                
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Startup self-check interrupted", ie);
                    }
                }
            }
        }
        
        log.info("=== Qdrant startup self-check completed ===");
        List<String> finalCollections = qdrantVectorStore.listCollections();
        log.info(String.format("All collections verified: %s", finalCollections));
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
            log.severe(String.format("Failed to load knowledge bases: %s", e.getMessage()));
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
            log.info(String.format("Knowledge base not found, creating empty: %s", agentType));
            saveKnowledgeBase(agentType);
            return;
        }

        try {
            long modifiedTime = Files.getLastModifiedTime(kbPath).toMillis();
            boolean fileChanged = modifiedTime != lastModifiedTimes.getOrDefault(agentType, 0L);
            
            if (!fileChanged && knowledgeBases.containsKey(agentType)) {
                log.info(String.format("Knowledge base %s unchanged, but re-inserting to Qdrant on startup", agentType));
                upsertExistingKnowledgeBase(agentType);
                return;
            }

            String json = Files.readString(kbPath);
            
            KnowledgeBase kb = convertLegacyFormat(json, agentType);
            log.info(String.format("Loaded KB for %s via legacy format: %d basics, %d examples, %d corrections", 
                    agentType,
                    kb.getBasics() != null ? kb.getBasics().size() : 0,
                    kb.getExamples() != null ? kb.getExamples().size() : 0,
                    kb.getErrorCorrections() != null ? kb.getErrorCorrections().size() : 0));
            
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

            boolean qdrantAvailable = qdrantVectorStore.isAvailable();
            log.info(String.format("Loading knowledge base %s: qdrantAvailable=%s, basics=%d, examples=%d, corrections=%d", 
                    agentType, qdrantAvailable, 
                    kb.getBasics() != null ? kb.getBasics().size() : 0,
                    kb.getExamples() != null ? kb.getExamples().size() : 0,
                    kb.getErrorCorrections() != null ? kb.getErrorCorrections().size() : 0));
            
            List<Document> docsToUpsert = new ArrayList<>();
            int generatedEmbeddings = 0;
            int skippedEmbeddings = 0;
            
            for (BasicKnowledge basic : kb.getBasics()) {
                if (basic.getEmbedding() == null || basic.getEmbedding().length == 0) {
                    try {
                        float[] embedding = embeddingService.embed(basic.getContent());
                        if (embedding != null && embedding.length > 0) {
                            basic.setEmbedding(embedding);
                            generatedEmbeddings++;
                        } else {
                            skippedEmbeddings++;
                            log.warning(String.format("Embedding generation returned null or empty for basic: %s", basic.getId()));
                        }
                    } catch (Exception e) {
                        skippedEmbeddings++;
                        log.warning(String.format("Failed to generate embedding for basic %s: %s", basic.getId(), e.getMessage()));
                    }
                }
                if (qdrantAvailable && basic.getEmbedding() != null && basic.getEmbedding().length > 0) {
                    docsToUpsert.add(toDocument(basic, agentType, "basic"));
                } else if (!qdrantAvailable && basic.getEmbedding() != null) {
                    log.fine(String.format("Qdrant not available, skipping upsert for basic: %s", basic.getId()));
                } else if (qdrantAvailable && (basic.getEmbedding() == null || basic.getEmbedding().length == 0)) {
                    log.warning(String.format("No valid embedding for basic, skipping upsert: %s", basic.getId()));
                }
            }
            
            for (ExampleKnowledge example : kb.getExamples()) {
                String semanticContent = (example.getScenario() != null ? example.getScenario() : "") + "\n" + 
                        (example.getInput() != null ? example.getInput() : "");
                if (example.getEmbedding() == null || example.getEmbedding().length == 0) {
                    try {
                        float[] embedding = embeddingService.embed(semanticContent);
                        if (embedding != null && embedding.length > 0) {
                            example.setEmbedding(embedding);
                            generatedEmbeddings++;
                        } else {
                            skippedEmbeddings++;
                            log.warning(String.format("Embedding generation returned null or empty for example: %s", example.getId()));
                        }
                    } catch (Exception e) {
                        skippedEmbeddings++;
                        log.warning(String.format("Failed to generate embedding for example %s: %s", example.getId(), e.getMessage()));
                    }
                }
                if (qdrantAvailable && example.getEmbedding() != null && example.getEmbedding().length > 0) {
                    docsToUpsert.add(toDocument(example, agentType, "example"));
                } else if (qdrantAvailable && (example.getEmbedding() == null || example.getEmbedding().length == 0)) {
                    log.warning(String.format("No valid embedding for example, skipping upsert: %s", example.getId()));
                }
            }
            
            for (ErrorCorrection ec : kb.getErrorCorrections()) {
                String semanticContent = (ec.getErrorType() != null ? ec.getErrorType() : "") + "\n" + 
                        (ec.getErrorDescription() != null ? ec.getErrorDescription() : "") + "\n" + 
                        (ec.getCorrectionExplanation() != null ? ec.getCorrectionExplanation() : "");
                if (ec.getEmbedding() == null || ec.getEmbedding().length == 0) {
                    try {
                        float[] embedding = embeddingService.embed(semanticContent);
                        if (embedding != null && embedding.length > 0) {
                            ec.setEmbedding(embedding);
                            generatedEmbeddings++;
                        } else {
                            skippedEmbeddings++;
                            log.warning(String.format("Embedding generation returned null or empty for error correction: %s", ec.getId()));
                        }
                    } catch (Exception e) {
                        skippedEmbeddings++;
                        log.warning(String.format("Failed to generate embedding for error correction %s: %s", ec.getId(), e.getMessage()));
                    }
                }
                if (qdrantAvailable && ec.getEmbedding() != null && ec.getEmbedding().length > 0) {
                    docsToUpsert.add(toDocument(ec, agentType, "error_correction"));
                } else if (qdrantAvailable && (ec.getEmbedding() == null || ec.getEmbedding().length == 0)) {
                    log.warning(String.format("No valid embedding for error correction, skipping upsert: %s", ec.getId()));
                }
            }

            knowledgeBases.put(agentType, kb);
            lastModifiedTimes.put(agentType, modifiedTime);
            
            log.info(String.format("Knowledge base %s: %d docs to upsert, %d embeddings generated, %d skipped", 
                    agentType, docsToUpsert.size(), generatedEmbeddings, skippedEmbeddings));
            
            if (!docsToUpsert.isEmpty()) {
                log.info(String.format("Calling upsertBatch for %s with %d documents", agentType, docsToUpsert.size()));
                qdrantVectorStore.upsertBatch(agentType, docsToUpsert);
                log.info(String.format("upsertBatch completed for %s", agentType));
            } else if (qdrantAvailable) {
                log.warning(String.format("No documents to upsert for %s - check if embeddings are being generated", agentType));
            } else {
                log.warning(String.format("Qdrant not available, skipping upsert for %s", agentType));
            }
            
            log.info(String.format("Loaded knowledge base %s: %d basics, %d examples, %d corrections (qdrant: %s, upserted: %d)", 
                    agentType, kb.getBasics().size(), kb.getExamples().size(), 
                    kb.getErrorCorrections().size(), qdrantAvailable, docsToUpsert.size()));
        } catch (IOException e) {
            log.severe(String.format("Failed to load knowledge base %s: %s", agentType, e.getMessage()));
            knowledgeBases.put(agentType, createEmptyKnowledgeBase(agentType));
        }
    }

    private KnowledgeBase convertLegacyFormat(String json, String agentType) {
        try {
            com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(json);
            
            log.info(String.format("Legacy format parsing for %s: rootNode has basics=%s, examples=%s, errorCorrections=%s", 
                    agentType,
                    rootNode.has("basics"),
                    rootNode.has("examples"),
                    rootNode.has("errorCorrections")));
            
            KnowledgeBase kb = createEmptyKnowledgeBase(agentType);
            LocalDateTime now = LocalDateTime.now();
            
            com.fasterxml.jackson.databind.JsonNode basicsNode = rootNode.get("basics");
            log.info(String.format("Legacy format parsing for %s: basicsNode=%s, isArray=%s, size=%d", 
                    agentType,
                    basicsNode != null,
                    basicsNode != null && basicsNode.isArray(),
                    basicsNode != null && basicsNode.isArray() ? basicsNode.size() : 0));
            
            if (basicsNode != null && basicsNode.isArray()) {
                int index = 1;
                for (com.fasterxml.jackson.databind.JsonNode node : basicsNode) {
                    String category = node.has("category") ? node.get("category").asText() : null;
                    String content = node.has("content") ? node.get("content").asText() : null;
                    
                    kb.getBasics().add(BasicKnowledge.builder()
                            .id("BASIC-" + agentType + "-" + String.format("%03d", index++))
                            .title(category != null ? category : "基本规则")
                            .content(content)
                            .section(category)
                            .tags(new ArrayList<>())
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                }
            }
            
            com.fasterxml.jackson.databind.JsonNode examplesNode = rootNode.get("examples");
            if (examplesNode != null && examplesNode.isArray()) {
                int index = 1;
                for (com.fasterxml.jackson.databind.JsonNode node : examplesNode) {
                    String scenario = node.has("scenario") ? node.get("scenario").asText() : null;
                    String input = node.has("input") ? node.get("input").asText() : null;
                    String goldenOutput = node.has("goldenOutput") ? node.get("goldenOutput").asText() : null;
                    String explanation = node.has("explanation") ? node.get("explanation").asText() : null;
                    
                    kb.getExamples().add(ExampleKnowledge.builder()
                            .id("EXAMPLE-" + agentType + "-" + String.format("%03d", index++))
                            .title(scenario != null ? scenario.substring(0, Math.min(scenario.length(), 30)) : "示例")
                            .scenario(scenario)
                            .input(input)
                            .output(goldenOutput)
                            .explanation(explanation)
                            .tags(new ArrayList<>())
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                }
            }
            
            com.fasterxml.jackson.databind.JsonNode errorCorrectionsNode = rootNode.get("errorCorrections");
            if (errorCorrectionsNode != null && errorCorrectionsNode.isArray()) {
                int index = 1;
                for (com.fasterxml.jackson.databind.JsonNode node : errorCorrectionsNode) {
                    String errorType = node.has("errorType") ? node.get("errorType").asText() : null;
                    String badBehavior = node.has("badBehavior") ? node.get("badBehavior").asText() : null;
                    String correctionRule = node.has("correctionRule") ? node.get("correctionRule").asText() : null;
                    
                    kb.getErrorCorrections().add(ErrorCorrection.builder()
                            .id("EC-" + agentType + "-" + String.format("%03d", index++))
                            .title(errorType != null ? errorType : "错误修正")
                            .errorType(errorType)
                            .errorContent(badBehavior)
                            .errorDescription(badBehavior)
                            .correctContent(correctionRule)
                            .correctionExplanation(correctionRule)
                            .tags(new ArrayList<>())
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                }
            }
            
            if (kb.getBasics().isEmpty() && kb.getExamples().isEmpty() && kb.getErrorCorrections().isEmpty()) {
                return createEmptyKnowledgeBase(agentType);
            }
            
            return kb;
        } catch (Exception e) {
            log.warning(String.format("Failed to convert legacy format: %s", e.getMessage()));
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
            log.fine(String.format("Saved knowledge base: %s (%d entries)", agentType, kb.getTotalEntries()));
        } catch (IOException e) {
            log.severe(String.format("Failed to save knowledge base %s: %s", agentType, e.getMessage()));
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
                            log.info(String.format("Detected change in knowledge base: %s", agentType));
                            loadKnowledgeBase(agentType);
                        }
                    }
                }
                key.reset();
            }
        } catch (Exception e) {
            log.severe(String.format("File watcher stopped: %s", e.getMessage()));
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
                log.warning("Failed to generate embedding for basic knowledge");
            }
        }
        
        kb.getBasics().add(basic);
        knowledgeBases.put(agentType, kb);
        saveKnowledgeBase(agentType);
        
        if (qdrantVectorStore.isAvailable() && basic.getEmbedding() != null) {
            qdrantVectorStore.upsert(agentType, toDocument(basic, agentType, "basic"));
        }
        
        log.info(String.format("Added basic knowledge to %s: %s", agentType, basic.getId()));
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
        
        String semanticContent = (example.getScenario() != null ? example.getScenario() : "") + "\n" + 
                (example.getInput() != null ? example.getInput() : "");
        if (example.getEmbedding() == null) {
            try {
                example.setEmbedding(embeddingService.embed(semanticContent));
            } catch (Exception e) {
                log.warning("Failed to generate embedding for example");
            }
        }
        
        kb.getExamples().add(example);
        knowledgeBases.put(agentType, kb);
        saveKnowledgeBase(agentType);
        
        if (qdrantVectorStore.isAvailable() && example.getEmbedding() != null) {
            qdrantVectorStore.upsert(agentType, toDocument(example, agentType, "example"));
        }
        
        log.info(String.format("Added example to %s: %s", agentType, example.getId()));
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
        
        String semanticContent = (ec.getErrorType() != null ? ec.getErrorType() : "") + "\n" + 
                (ec.getErrorDescription() != null ? ec.getErrorDescription() : "") + "\n" + 
                (ec.getCorrectionExplanation() != null ? ec.getCorrectionExplanation() : "");
        if (ec.getEmbedding() == null) {
            try {
                ec.setEmbedding(embeddingService.embed(semanticContent));
            } catch (Exception e) {
                log.warning("Failed to generate embedding for error correction");
            }
        }
        
        kb.getErrorCorrections().add(ec);
        knowledgeBases.put(agentType, kb);
        saveKnowledgeBase(agentType);
        
        if (qdrantVectorStore.isAvailable() && ec.getEmbedding() != null) {
            qdrantVectorStore.upsert(agentType, toDocument(ec, agentType, "error_correction"));
        }
        
        log.info(String.format("Added error correction to %s: %s", agentType, ec.getId()));
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
                        log.warning("Failed to regenerate embedding for basic knowledge");
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
                
                log.info(String.format("Updated basic knowledge in %s: %s", agentType, basicId));
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
                
                String semanticContent = (example.getScenario() != null ? example.getScenario() : "") + "\n" + 
                        (example.getInput() != null ? example.getInput() : "");
                try {
                    example.setEmbedding(embeddingService.embed(semanticContent));
                } catch (Exception e) {
                    log.warning("Failed to regenerate embedding for example");
                }
                example.setUpdatedAt(LocalDateTime.now());
                
                knowledgeBases.put(agentType, kb);
                saveKnowledgeBase(agentType);
                
                if (qdrantVectorStore.isAvailable() && example.getEmbedding() != null) {
                    qdrantVectorStore.upsert(agentType, toDocument(example, agentType, "example"));
                }
                
                log.info(String.format("Updated example in %s: %s", agentType, exampleId));
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
                
                String semanticContent = (ec.getErrorType() != null ? ec.getErrorType() : "") + "\n" + 
                        (ec.getErrorDescription() != null ? ec.getErrorDescription() : "") + "\n" + 
                        (ec.getCorrectionExplanation() != null ? ec.getCorrectionExplanation() : "");
                try {
                    ec.setEmbedding(embeddingService.embed(semanticContent));
                } catch (Exception e) {
                    log.warning("Failed to regenerate embedding for error correction");
                }
                ec.setUpdatedAt(LocalDateTime.now());
                
                knowledgeBases.put(agentType, kb);
                saveKnowledgeBase(agentType);
                
                if (qdrantVectorStore.isAvailable() && ec.getEmbedding() != null) {
                    qdrantVectorStore.upsert(agentType, toDocument(ec, agentType, "error_correction"));
                }
                
                log.info(String.format("Updated error correction in %s: %s", agentType, ecId));
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
            
            log.info(String.format("Deleted basic knowledge from %s: %s", agentType, basicId));
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
            
            log.info(String.format("Deleted example from %s: %s", agentType, exampleId));
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
            
            log.info(String.format("Deleted error correction from %s: %s", agentType, ecId));
        }
        return removed;
    }

    public void reloadKnowledgeBase(String agentType) {
        loadKnowledgeBase(agentType);
        log.info(String.format("Reloaded knowledge base: %s", agentType));
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
                .payload(basic.getContent())
                .title(basic.getTitle())
                .source(agentType)
                .category(type)
                .tags(basic.getTags())
                .embedding(basic.getEmbedding())
                .build();
    }

    private Document toDocument(ExampleKnowledge example, String agentType, String type) {
        String semanticText = (example.getScenario() != null ? example.getScenario() : "") + "\n" + 
                (example.getInput() != null ? example.getInput() : "") + 
                (example.getExplanation() != null ? "\n解释: " + example.getExplanation() : "");
        
        String payload = (example.getScenario() != null ? "场景: " + example.getScenario() + "\n" : "") + 
                (example.getInput() != null ? "输入: " + example.getInput() + "\n" : "") + 
                (example.getOutput() != null ? "输出: " + example.getOutput() + "\n" : "") + 
                (example.getExplanation() != null ? "解释: " + example.getExplanation() : "");

        String fullText = semanticText + (example.getOutput() != null ? "\n" + example.getOutput() : "");
        
        return Document.builder()
                .id(example.getId())
                .content(semanticText)
                .payload(payload)
                .title(example.getTitle())
                .source(agentType)
                .category(type)
                .tags(example.getTags())
                .embedding(example.getEmbedding())
                .hardwareInterfaces(extractHardwareInterfaces(fullText))
                .sensors(extractSensors(fullText))
                .actuators(extractActuators(fullText))
                .applicationDomains(extractApplicationDomains(fullText, example.getTags()))
                .safetyLevels(extractSafetyLevels(fullText))
                .schedulingPolicies(extractSchedulingPolicies(fullText))
                .build();
    }

    private Document toDocument(ErrorCorrection ec, String agentType, String type) {
        String semanticText = (ec.getErrorType() != null ? "错误类型: " + ec.getErrorType() : "") + "\n" + 
                (ec.getErrorDescription() != null ? "错误描述: " + ec.getErrorDescription() : "") + 
                (ec.getCorrectionExplanation() != null ? "修复说明: " + ec.getCorrectionExplanation() : "");
        
        String payload = (ec.getErrorType() != null ? "错误类型: " + ec.getErrorType() + "\n" : "") + 
                (ec.getErrorContent() != null ? "错误内容: " + ec.getErrorContent() + "\n" : "") + 
                (ec.getErrorDescription() != null ? "错误描述: " + ec.getErrorDescription() + "\n" : "") + 
                (ec.getCorrectContent() != null ? "正确内容: " + ec.getCorrectContent() + "\n" : "") + 
                (ec.getCorrectionExplanation() != null ? "修复说明: " + ec.getCorrectionExplanation() + "\n" : "") + 
                (ec.getSuggestion() != null ? "建议: " + ec.getSuggestion() : "");

        String fullText = semanticText + (ec.getCorrectContent() != null ? "\n" + ec.getCorrectContent() : "");

        return Document.builder()
                .id(ec.getId())
                .content(semanticText)
                .payload(payload)
                .title(ec.getTitle())
                .source(agentType)
                .category(type)
                .tags(ec.getTags())
                .embedding(ec.getEmbedding())
                .hardwareInterfaces(extractHardwareInterfaces(fullText))
                .sensors(extractSensors(fullText))
                .actuators(extractActuators(fullText))
                .applicationDomains(extractApplicationDomains(fullText, ec.getTags()))
                .safetyLevels(extractSafetyLevels(fullText))
                .schedulingPolicies(extractSchedulingPolicies(fullText))
                .build();
    }

    private List<String> extractHardwareInterfaces(String text) {
        if (text == null) return new ArrayList<>();
        String lower = text.toLowerCase();
        List<String> interfaces = new ArrayList<>();
        
        if (lower.contains("spi")) interfaces.add("SPI");
        if (lower.contains("i2c") || lower.contains("i²c")) interfaces.add("I2C");
        if (lower.contains("uart")) interfaces.add("UART");
        if (lower.contains("can")) interfaces.add("CAN");
        if (lower.contains("ethernet")) interfaces.add("ETHERNET");
        if (lower.contains("usb")) interfaces.add("USB");
        if (lower.contains("gpio")) interfaces.add("GPIO");
        if (lower.contains("adc")) interfaces.add("ADC");
        if (lower.contains("dac")) interfaces.add("DAC");
        
        return interfaces;
    }

    private List<String> extractSensors(String text) {
        if (text == null) return new ArrayList<>();
        String lower = text.toLowerCase();
        List<String> sensors = new ArrayList<>();
        
        if (lower.contains("gyroscope") || lower.contains("陀螺仪")) sensors.add("GYROSCOPE");
        if (lower.contains("accelerometer") || lower.contains("加速度计")) sensors.add("ACCELEROMETER");
        if (lower.contains("temperature") || lower.contains("温度")) sensors.add("TEMPERATURE");
        if (lower.contains("pressure") || lower.contains("压力")) sensors.add("PRESSURE");
        if (lower.contains("gps")) sensors.add("GPS");
        if (lower.contains("imu")) sensors.add("IMU");
        if (lower.contains("sensor") || lower.contains("传感器")) sensors.add("SENSOR");
        
        return sensors;
    }

    private List<String> extractActuators(String text) {
        if (text == null) return new ArrayList<>();
        String lower = text.toLowerCase();
        List<String> actuators = new ArrayList<>();
        
        if (lower.contains("motor") || lower.contains("电机")) actuators.add("MOTOR");
        if (lower.contains("valve") || lower.contains("阀门")) actuators.add("VALVE");
        if (lower.contains("led") || lower.contains("指示灯")) actuators.add("LED");
        if (lower.contains("actuator") || lower.contains("执行器")) actuators.add("ACTUATOR");
        
        return actuators;
    }

    private List<String> extractApplicationDomains(String text, List<String> tags) {
        if (text == null) text = "";
        String lower = text.toLowerCase();
        List<String> domains = new ArrayList<>();
        
        if (tags != null) {
            for (String tag : tags) {
                String tagLower = tag.toLowerCase();
                if (tagLower.contains("航空") || tagLower.contains("aviation")) domains.add("AVIATION");
                if (tagLower.contains("航天") || tagLower.contains("space")) domains.add("SPACE");
                if (tagLower.contains("汽车") || tagLower.contains("automotive")) domains.add("AUTOMOTIVE");
                if (tagLower.contains("医疗") || tagLower.contains("medical")) domains.add("MEDICAL");
                if (tagLower.contains("工业") || tagLower.contains("industrial")) domains.add("INDUSTRIAL");
                if (tagLower.contains("消费") || tagLower.contains("consumer")) domains.add("CONSUMER");
                if (tagLower.contains("能源") || tagLower.contains("energy")) domains.add("ENERGY");
                if (tagLower.contains("通信") || tagLower.contains("telecom")) domains.add("TELECOM");
            }
        }
        
        if (lower.contains("航空") || lower.contains("aviation") || lower.contains("flight")) domains.add("AVIATION");
        if (lower.contains("航天") || lower.contains("space") || lower.contains("satellite")) domains.add("SPACE");
        if (lower.contains("汽车") || lower.contains("automotive") || lower.contains("vehicle")) domains.add("AUTOMOTIVE");
        if (lower.contains("医疗") || lower.contains("medical") || lower.contains("hospital")) domains.add("MEDICAL");
        if (lower.contains("工业") || lower.contains("industrial") || lower.contains("factory")) domains.add("INDUSTRIAL");
        if (lower.contains("消费") || lower.contains("consumer") || lower.contains("smart")) domains.add("CONSUMER");
        if (lower.contains("能源") || lower.contains("energy") || lower.contains("power")) domains.add("ENERGY");
        if (lower.contains("通信") || lower.contains("telecom") || lower.contains("5g")) domains.add("TELECOM");
        
        return domains.stream().distinct().toList();
    }

    private List<String> extractSafetyLevels(String text) {
        if (text == null) return new ArrayList<>();
        String lower = text.toLowerCase();
        List<String> levels = new ArrayList<>();
        
        if (lower.contains("asil-a")) levels.add("ASIL_A");
        if (lower.contains("asil-b")) levels.add("ASIL_B");
        if (lower.contains("asil-c")) levels.add("ASIL_C");
        if (lower.contains("asil-d")) levels.add("ASIL_D");
        
        return levels;
    }

    private List<String> extractSchedulingPolicies(String text) {
        if (text == null) return new ArrayList<>();
        String lower = text.toLowerCase();
        List<String> policies = new ArrayList<>();
        
        if (lower.contains("fifo") || lower.contains("先进先出")) policies.add("FIFO");
        if (lower.contains("priority") || lower.contains("优先级")) policies.add("PRIORITY");
        if (lower.contains("round-robin") || lower.contains("轮转")) policies.add("ROUND_ROBIN");
        if (lower.contains("rate-monotonic") || lower.contains("速率单调")) policies.add("RATE_MONOTONIC");
        if (lower.contains("edf") || lower.contains("最早截止期")) policies.add("EDF");
        
        return policies;
    }

    private void upsertExistingKnowledgeBase(String agentType) {
        KnowledgeBase kb = knowledgeBases.get(agentType);
        if (kb == null) {
            log.warning(String.format("Knowledge base %s not found in memory", agentType));
            return;
        }
        
        if (!qdrantVectorStore.isAvailable()) {
            log.warning(String.format("Qdrant not available, skipping upsert for %s", agentType));
            return;
        }
        
        log.info(String.format("Re-inserting existing knowledge base %s to Qdrant", agentType));
        
        List<Document> docsToUpsert = new ArrayList<>();
        int generatedEmbeddings = 0;
        int skippedEmbeddings = 0;
        
        for (BasicKnowledge basic : kb.getBasics()) {
            if (basic.getEmbedding() == null || basic.getEmbedding().length == 0) {
                try {
                    float[] embedding = embeddingService.embed(basic.getContent());
                    if (embedding != null && embedding.length > 0) {
                        basic.setEmbedding(embedding);
                        generatedEmbeddings++;
                    } else {
                        skippedEmbeddings++;
                        log.warning(String.format("Embedding generation returned null or empty for basic: %s", basic.getId()));
                    }
                } catch (Exception e) {
                    skippedEmbeddings++;
                    log.warning(String.format("Failed to generate embedding for basic %s: %s", basic.getId(), e.getMessage()));
                }
            }
            if (basic.getEmbedding() != null && basic.getEmbedding().length > 0) {
                docsToUpsert.add(toDocument(basic, agentType, "basic"));
            } else {
                log.warning(String.format("No valid embedding for basic, skipping upsert: %s", basic.getId()));
            }
        }
        
        for (ExampleKnowledge example : kb.getExamples()) {
            String semanticContent = (example.getScenario() != null ? example.getScenario() : "") + "\n" + 
                    (example.getInput() != null ? example.getInput() : "");
            if (example.getEmbedding() == null || example.getEmbedding().length == 0) {
                try {
                    float[] embedding = embeddingService.embed(semanticContent);
                    if (embedding != null && embedding.length > 0) {
                        example.setEmbedding(embedding);
                        generatedEmbeddings++;
                    } else {
                        skippedEmbeddings++;
                        log.warning(String.format("Embedding generation returned null or empty for example: %s", example.getId()));
                    }
                } catch (Exception e) {
                    skippedEmbeddings++;
                    log.warning(String.format("Failed to generate embedding for example %s: %s", example.getId(), e.getMessage()));
                }
            }
            if (example.getEmbedding() != null && example.getEmbedding().length > 0) {
                docsToUpsert.add(toDocument(example, agentType, "example"));
            } else {
                log.warning(String.format("No valid embedding for example, skipping upsert: %s", example.getId()));
            }
        }
        
        for (ErrorCorrection ec : kb.getErrorCorrections()) {
            String semanticContent = (ec.getErrorType() != null ? ec.getErrorType() : "") + "\n" + 
                    (ec.getErrorDescription() != null ? ec.getErrorDescription() : "") + "\n" + 
                    (ec.getCorrectionExplanation() != null ? ec.getCorrectionExplanation() : "");
            if (ec.getEmbedding() == null || ec.getEmbedding().length == 0) {
                try {
                    float[] embedding = embeddingService.embed(semanticContent);
                    if (embedding != null && embedding.length > 0) {
                        ec.setEmbedding(embedding);
                        generatedEmbeddings++;
                    } else {
                        skippedEmbeddings++;
                        log.warning(String.format("Embedding generation returned null or empty for error correction: %s", ec.getId()));
                    }
                } catch (Exception e) {
                    skippedEmbeddings++;
                    log.warning(String.format("Failed to generate embedding for error correction %s: %s", ec.getId(), e.getMessage()));
                }
            }
            if (ec.getEmbedding() != null && ec.getEmbedding().length > 0) {
                docsToUpsert.add(toDocument(ec, agentType, "error_correction"));
            } else {
                log.warning(String.format("No valid embedding for error correction, skipping upsert: %s", ec.getId()));
            }
        }
        
        log.info(String.format("Existing KB %s: %d docs to upsert, %d embeddings generated, %d skipped", 
                agentType, docsToUpsert.size(), generatedEmbeddings, skippedEmbeddings));
        
        if (!docsToUpsert.isEmpty()) {
            log.info(String.format("Calling upsertBatch for %s with %d documents", agentType, docsToUpsert.size()));
            qdrantVectorStore.upsertBatch(agentType, docsToUpsert);
            log.info(String.format("Re-inserted %d documents to Qdrant for %s", docsToUpsert.size(), agentType));
        } else {
            log.warning(String.format("No documents to upsert for %s - check if embeddings are being generated", agentType));
        }
    }
}