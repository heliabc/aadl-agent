package com.example.aadlagent.memory;

import com.example.aadlagent.rag.EmbeddingService;
import com.example.aadlagent.rag.QdrantVectorStore;
import com.example.aadlagent.rag.RagService;
import com.example.aadlagent.rag.model.Document;
import com.example.aadlagent.rag.model.SearchResult;
import com.example.aadlagent.util.AadlReferenceValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 长期记忆服务 - 成功案例库
 *
 * 存储和管理历史成功生成的案例，用于：
 * 1. 新需求进来时，召回相似案例作为参考
 * 2. 积累领域知识
 *
 * 存储方式：JSON 文件（knowledge/success_cases.json）保存源数据，
 *           Qdrant 集合（success_case）保存向量索引，检索统一走 RAG
 */
@Slf4j
@Service
public class LongTermMemoryService {

    @Value("${memory.long-term.storage-path:knowledge/success_cases.json}")
    private String storagePath;

    /** Qdrant 集合名（与 success_cases.json 同名，成功案例统一走向量检索） */
    private static final String COLLECTION = "success_cases";

    /** 内存中的案例索引 */
    private final Map<String, SuccessCase> caseIndex = new LinkedHashMap<>();

    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final QdrantVectorStore qdrantVectorStore;
    private final RagService ragService;

    public LongTermMemoryService(EmbeddingService embeddingService,
                                 QdrantVectorStore qdrantVectorStore,
                                 RagService ragService) {
        this.embeddingService = embeddingService;
        this.qdrantVectorStore = qdrantVectorStore;
        this.ragService = ragService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        loadCases();
        syncCasesToQdrant();
        log.info("Long-term memory initialized: {} success cases loaded", caseIndex.size());
    }

    /**
     * 从文件加载所有成功案例
     */
    private synchronized void loadCases() {
        try {
            Path path = Paths.get(storagePath);
            if (!Files.exists(path)) {
                log.info("Success cases file not found, starting with empty library: {}", storagePath);
                return;
            }

            String json = Files.readString(path);
            SuccessCase[] cases = objectMapper.readValue(json, SuccessCase[].class);
            for (SuccessCase c : cases) {
                if (c.getCaseId() != null) {
                    caseIndex.put(c.getCaseId(), c);
                }
            }
            log.info("Loaded {} success cases from {}", caseIndex.size(), storagePath);
        } catch (Exception e) {
            log.error("Failed to load success cases: {}", e.getMessage());
        }
    }

    /**
     * 保存所有案例到文件
     */
    private synchronized void saveCases() {
        try {
            Path path = Paths.get(storagePath);
            // 确保父目录存在
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }

            List<SuccessCase> cases = new ArrayList<>(caseIndex.values());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cases);
            Files.writeString(path, json);
            log.debug("Saved {} success cases to {}", cases.size(), storagePath);
        } catch (Exception e) {
            log.error("Failed to save success cases: {}", e.getMessage());
        }
    }

    /**
     * 添加成功案例
     *
     * @param requirementDoc 需求文档
     * @param aadlCode       生成的 AADL 代码
     * @param validator      验证器（用于计算质量分数）
     * @return 新案例的 ID
     */
    public String addSuccessCase(String requirementDoc, String aadlCode, AadlReferenceValidator validator) {
        if (requirementDoc == null || aadlCode == null ||
                requirementDoc.trim().isEmpty() || aadlCode.trim().isEmpty()) {
            return null;
        }

        // 生成案例ID
        String caseId = "CASE-" + UUID.randomUUID().toString().substring(0, 8);

        // 生成需求摘要（前200字）
        String summary = requirementDoc.length() > 200
                ? requirementDoc.substring(0, 200) + "..."
                : requirementDoc;

        // 计算质量分数（基于验证错误数量）
        int qualityScore = 100;
        int componentCount = 0;
        int connectionCount = 0;

        if (validator != null) {
            try {
                AadlReferenceValidator.ValidationResult result = validator.validateSyntax(aadlCode);
                int errorCount = result.errors != null ? result.errors.size() : 0;
                // 每1个错误扣2分，最低0分
                qualityScore = Math.max(0, 100 - errorCount * 2);

                // 统计组件和连接数量
                componentCount = countComponents(aadlCode);
                connectionCount = countConnections(aadlCode);
            } catch (Exception e) {
                log.warn("Failed to calculate quality score: {}", e.getMessage());
            }
        }

        // 自动提取标签
        List<String> domainTags = extractDomainTags(requirementDoc);
        List<String> techTags = extractTechTags(requirementDoc, aadlCode);

        // 生成标题
        String title = generateTitle(requirementDoc, componentCount);

        SuccessCase newCase = SuccessCase.builder()
                .caseId(caseId)
                .title(title)
                .requirementDoc(requirementDoc)
                .requirementSummary(summary)
                .aadlCode(aadlCode)
                .aadlCodeLength(aadlCode.length())
                .componentCount(componentCount)
                .connectionCount(connectionCount)
                .domainTags(domainTags)
                .techTags(techTags)
                .qualityScore(qualityScore)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        caseIndex.put(caseId, newCase);
        upsertCase(newCase);
        saveCases();

        log.info("Added success case: {} ({} components, quality={})",
                title, componentCount, qualityScore);

        return caseId;
    }

    /**
     * 根据需求文档召回相似的成功案例
     *
     * 检索路径与知识库统一：优先走 RAG（Qdrant 向量召回 + 关键词 + RRF 融合 + 重排），
     * RAG 不可用或未召回时降级为关键词匹配。
     *
     * @param query      查询文本（需求文档或描述）
     * @param maxResults 最多返回多少个
     * @return 相似案例列表（按相似度降序）
     */
    public List<SuccessCase> findSimilarCases(String query, int maxResults) {
        if (query == null || query.trim().isEmpty() || caseIndex.isEmpty()) {
            return Collections.emptyList();
        }

        // 优先走 RAG：检索 success_case 集合
        try {
            SearchResult result = ragService.search(query, COLLECTION);
            List<Document> docs = result != null ? result.getDocuments() : null;
            if (docs != null && !docs.isEmpty()) {
                List<SuccessCase> cases = new ArrayList<>();
                for (Document doc : docs) {
                    if (!"success_case".equals(doc.getCategory())) {
                        continue;
                    }
                    // RAG 只负责排序，结构化字段仍从内存索引取
                    SuccessCase c = caseIndex.get(doc.getId());
                    if (c != null && !cases.contains(c)) {
                        cases.add(c);
                        if (cases.size() >= maxResults) {
                            break;
                        }
                    }
                }
                if (!cases.isEmpty()) {
                    log.debug("RAG retrieved {} similar success cases", cases.size());
                    return cases;
                }
            }
        } catch (Exception e) {
            log.warn("RAG retrieval for success cases failed, falling back to keyword scoring: {}", e.getMessage());
        }

        // 降级：RAG 不可用（Qdrant/Embedding 异常）时用关键词匹配
        return keywordFindSimilarCases(query, maxResults);
    }

    /**
     * 关键词匹配召回（RAG 降级兜底）
     */
    private List<SuccessCase> keywordFindSimilarCases(String query, int maxResults) {
        // 从查询中提取关键词
        Set<String> queryKeywords = extractKeywords(query);

        // 计算每个案例的匹配分数
        List<Map.Entry<SuccessCase, Integer>> scored = new ArrayList<>();
        for (SuccessCase c : caseIndex.values()) {
            int score = calculateMatchScore(c, queryKeywords);
            if (score > 0) {
                scored.add(new AbstractMap.SimpleEntry<>(c, score));
            }
        }

        // 按分数降序排序
        scored.sort((a, b) -> b.getValue() - a.getValue());

        // 取前 N 个
        return scored.stream()
                .limit(maxResults)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有成功案例
     */
    public List<SuccessCase> getAllCases() {
        return new ArrayList<>(caseIndex.values());
    }

    /**
     * 获取成功案例数量
     */
    public int getCaseCount() {
        return caseIndex.size();
    }

    /**
     * 删除指定案例
     */
    public boolean removeCase(String caseId) {
        if (caseIndex.remove(caseId) != null) {
            if (qdrantVectorStore.isAvailable()) {
                qdrantVectorStore.delete(COLLECTION, caseId);
            }
            saveCases();
            log.info("Removed success case: {}", caseId);
            return true;
        }
        return false;
    }

    // ==================== Qdrant 同步 ====================

    /**
     * 生成向量并写入 Qdrant（与知识库统一走向量检索）
     */
    private boolean upsertCase(SuccessCase c) {
        if (c == null || c.getCaseId() == null || !qdrantVectorStore.isAvailable()) {
            return false;
        }
        try {
            if (c.getEmbedding() == null || c.getEmbedding().length == 0) {
                float[] embedding = embeddingService.embed(buildSemanticText(c));
                if (embedding == null || embedding.length == 0) {
                    log.warn("Failed to generate embedding for success case {}", c.getCaseId());
                    return false;
                }
                c.setEmbedding(embedding);
            }
            qdrantVectorStore.upsert(COLLECTION, toDocument(c));
            return true;
        } catch (Exception e) {
            log.warn("Failed to upsert success case {}: {}", c.getCaseId(), e.getMessage());
            return false;
        }
    }

    /**
     * 启动时把内存中的案例全量同步到 Qdrant（幂等覆盖 + 孤儿清理）
     * 保证 JSON 文件是唯一真值来源。
     */
    private void syncCasesToQdrant() {
        if (!qdrantVectorStore.isAvailable()) {
            log.info("Qdrant not available, success cases kept in memory only");
            return;
        }
        // syncOnStartup=false 且集合已有数据时跳过启动同步
        if (!qdrantConfig.isSyncOnStartup()) {
            long existingCount = qdrantVectorStore.count(COLLECTION);
            if (existingCount > 0) {
                log.info("syncOnStartup=false and collection '{}' has {} points, skipping startup sync (reuse existing data)", COLLECTION, existingCount);
                return;
            }
            log.info("syncOnStartup=false but collection '{}' is empty, performing initial load", COLLECTION);
        }
        if (caseIndex.isEmpty()) {
            // 没有案例，但集合里可能有旧数据，做一次清理
            try {
                List<com.example.aadlagent.rag.model.Document> existing = qdrantVectorStore.getAllDocuments(COLLECTION);
                if (!existing.isEmpty()) {
                    java.util.List<String> ids = new java.util.ArrayList<>();
                    for (com.example.aadlagent.rag.model.Document d : existing) {
                        ids.add(d.getId());
                    }
                    log.info("No success cases in JSON, cleaning {} orphan points from '{}'", ids.size(), COLLECTION);
                    qdrantVectorStore.deleteBatch(COLLECTION, ids);
                }
            } catch (Exception e) {
                log.warn("Failed to clean orphan points for {}: {}", COLLECTION, e.getMessage());
            }
            return;
        }

        // 为所有缺失 embedding 的案例生成向量
        List<com.example.aadlagent.rag.model.Document> docs = new ArrayList<>();
        for (SuccessCase c : caseIndex.values()) {
            if (c.getEmbedding() == null || c.getEmbedding().length == 0) {
                try {
                    float[] emb = embeddingService.embed(buildSemanticText(c));
                    if (emb != null && emb.length > 0) {
                        c.setEmbedding(emb);
                    }
                } catch (Exception e) {
                    log.warn("Failed to generate embedding for success case {}: {}", c.getCaseId(), e.getMessage());
                }
            }
            if (c.getEmbedding() != null && c.getEmbedding().length > 0) {
                docs.add(toDocument(c));
            }
        }

        if (docs.isEmpty()) {
            log.warn("No valid embeddings for success cases, skipping Qdrant sync");
            return;
        }

        // 全量 upsert（幂等覆盖）
        qdrantVectorStore.upsertBatch(COLLECTION, docs);
        log.info("Synced {} success cases to Qdrant collection '{}'", docs.size(), COLLECTION);

        // 孤儿点清理
        try {
            java.util.List<com.example.aadlagent.rag.model.Document> existing = qdrantVectorStore.getAllDocuments(COLLECTION);
            java.util.Set<String> expectedIds = docs.stream()
                    .map(com.example.aadlagent.rag.model.Document::getId)
                    .collect(java.util.stream.Collectors.toSet());
            java.util.List<String> orphanIds = new java.util.ArrayList<>();
            for (com.example.aadlagent.rag.model.Document d : existing) {
                if (!expectedIds.contains(d.getId())) {
                    orphanIds.add(d.getId());
                }
            }
            if (!orphanIds.isEmpty()) {
                log.info("Found {} orphan success cases in Qdrant, cleaning up...", orphanIds.size());
                qdrantVectorStore.deleteBatch(COLLECTION, orphanIds);
                log.info("Cleaned up {} orphan success cases", orphanIds.size());
            }
        } catch (Exception e) {
            log.warn("Failed to clean orphan success cases: {}", e.getMessage());
        }
    }

    /**
     * 生成向量用的语义文本（以需求侧为主，检索 query 也是需求）
     */
    private String buildSemanticText(SuccessCase c) {
        StringBuilder sb = new StringBuilder();
        if (c.getTitle() != null) {
            sb.append(c.getTitle()).append("\n");
        }
        if (c.getRequirementSummary() != null) {
            sb.append(c.getRequirementSummary());
        } else if (c.getRequirementDoc() != null) {
            sb.append(c.getRequirementDoc());
        }
        return sb.toString().trim();
    }

    private Document toDocument(SuccessCase c) {
        List<String> tags = new ArrayList<>();
        if (c.getDomainTags() != null) {
            tags.addAll(c.getDomainTags());
        }
        if (c.getTechTags() != null) {
            tags.addAll(c.getTechTags());
        }

        StringBuilder payload = new StringBuilder();
        if (c.getRequirementSummary() != null) {
            payload.append("需求摘要: ").append(c.getRequirementSummary()).append("\n");
        }
        if (!tags.isEmpty()) {
            payload.append("标签: ").append(String.join(", ", tags)).append("\n");
        }
        payload.append("质量分: ").append(c.getQualityScore())
               .append("，组件数: ").append(c.getComponentCount())
               .append("，连接数: ").append(c.getConnectionCount());

        return Document.builder()
                .id(c.getCaseId())
                .content(buildSemanticText(c))
                .payload(payload.toString())
                .title(c.getTitle())
                .source(COLLECTION)
                .category("success_case")
                .tags(tags)
                .embedding(c.getEmbedding())
                .build();
    }

    // ==================== 辅助方法 ====================

    /**
     * 从查询中提取关键词
     */
    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        if (text == null) return keywords;

        String lower = text.toLowerCase();

        // 领域关键词
        String[] domainKeywords = {
                "飞控", "航空", "航天", "无人机", "卫星", "火箭",
                "汽车", "自动驾驶", "车载", "动力", "制动",
                "工业", "控制", "机器人", "自动化",
                "医疗", "设备", "监护",
                "通信", "网络", "协议",
                "实时", "嵌入式", "分布式", "容错", "安全"
        };

        for (String kw : domainKeywords) {
            if (lower.contains(kw)) {
                keywords.add(kw);
            }
        }

        // 技术关键词（从 AADL 组件类型角度）
        String[] techKeywords = {
                "thread", "process", "processor", "device", "memory",
                "system", "bus", "data", "connection", "port",
                "线程", "进程", "处理器", "设备", "内存",
                "系统", "总线", "数据", "连接", "端口"
        };

        for (String kw : techKeywords) {
            if (lower.contains(kw)) {
                keywords.add(kw);
            }
        }

        return keywords;
    }

    /**
     * 计算案例与查询的匹配分数
     */
    private int calculateMatchScore(SuccessCase c, Set<String> queryKeywords) {
        int score = 0;

        // 匹配领域标签
        if (c.getDomainTags() != null) {
            for (String tag : c.getDomainTags()) {
                if (queryKeywords.contains(tag.toLowerCase())) {
                    score += 10;
                }
            }
        }

        // 匹配技术标签
        if (c.getTechTags() != null) {
            for (String tag : c.getTechTags()) {
                if (queryKeywords.contains(tag.toLowerCase())) {
                    score += 5;
                }
            }
        }

        // 标题和摘要中的关键词匹配
        String text = (c.getTitle() != null ? c.getTitle().toLowerCase() : "")
                + " " + (c.getRequirementSummary() != null ? c.getRequirementSummary().toLowerCase() : "");
        for (String kw : queryKeywords) {
            if (text.contains(kw)) {
                score += 3;
            }
        }

        // 质量分加成（质量高的案例排名靠前）
        score += c.getQualityScore() / 20; // 0-5 分的加成

        return score;
    }

    /**
     * 从需求文档中提取领域标签
     */
    private List<String> extractDomainTags(String requirementDoc) {
        List<String> tags = new ArrayList<>();
        if (requirementDoc == null) return tags;

        String lower = requirementDoc.toLowerCase();

        if (lower.contains("飞控") || lower.contains("航空") || lower.contains("航天")
                || lower.contains("无人机") || lower.contains("卫星")) {
            tags.add("航空航天");
        }
        if (lower.contains("汽车") || lower.contains("自动驾驶") || lower.contains("车载")
                || lower.contains("制动") || lower.contains("动力")) {
            tags.add("汽车电子");
        }
        if (lower.contains("工业") || lower.contains("机器人") || lower.contains("自动化")) {
            tags.add("工业控制");
        }
        if (lower.contains("医疗") || lower.contains("监护") || lower.contains("诊断")) {
            tags.add("医疗设备");
        }
        if (lower.contains("通信") || lower.contains("网络") || lower.contains("协议")) {
            tags.add("通信系统");
        }

        return tags;
    }

    /**
     * 从需求和代码中提取技术标签
     */
    private List<String> extractTechTags(String requirementDoc, String aadlCode) {
        List<String> tags = new ArrayList<>();
        String combined = (requirementDoc + " " + aadlCode).toLowerCase();

        if (combined.contains("实时") || combined.contains("real-time") || combined.contains("deadline")) {
            tags.add("实时系统");
        }
        if (combined.contains("分布式") || combined.contains("distributed")) {
            tags.add("分布式");
        }
        if (combined.contains("容错") || combined.contains("fault") || combined.contains("redundant")) {
            tags.add("容错");
        }
        if (combined.contains("安全") || combined.contains("safety") || combined.contains("security")) {
            tags.add("安全关键");
        }
        if (combined.contains("并发") || combined.contains("concurrent") || combined.contains("thread")) {
            tags.add("多线程");
        }

        return tags;
    }

    /**
     * 生成案例标题
     */
    private String generateTitle(String requirementDoc, int componentCount) {
        // 取需求文档的前 30 个字符作为标题基础
        String base = requirementDoc.trim().replaceAll("\\s+", " ");
        if (base.length() > 30) {
            base = base.substring(0, 30) + "...";
        }
        return base + " (" + componentCount + "组件)";
    }

    /**
     * 统计 AADL 代码中的组件数量
     */
    private int countComponents(String aadlCode) {
        int count = 0;
        String[] patterns = {"thread ", "process ", "system ", "processor ",
                "device ", "memory ", "bus ", "data "};
        for (String pattern : patterns) {
            int idx = 0;
            while ((idx = aadlCode.indexOf(pattern, idx)) != -1) {
                // 排除 implementation 行
                int lineEnd = aadlCode.indexOf('\n', idx);
                String line = lineEnd > 0 ? aadlCode.substring(idx, lineEnd) : aadlCode.substring(idx);
                if (!line.contains("implementation")) {
                    count++;
                }
                idx += pattern.length();
            }
        }
        return count;
    }

    /**
     * 统计连接数量
     */
    private int countConnections(String aadlCode) {
        int count = 0;
        int idx = 0;
        while ((idx = aadlCode.indexOf("connections", idx)) != -1) {
            count++;
            idx += "connections".length();
        }
        return count;
    }
}
