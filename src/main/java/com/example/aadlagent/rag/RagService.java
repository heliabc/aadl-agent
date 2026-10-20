package com.example.aadlagent.rag;

import com.example.aadlagent.rag.model.Document;
import com.example.aadlagent.rag.model.SearchResult;
import com.example.aadlagent.config.RagConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class RagService {

    private final QueryRewriter queryRewriter;
    private final QdrantVectorStore qdrantVectorStore;
    private final VectorSearchService vectorSearchService;
    private final RrfFusion rrfFusion;
    private final Reranker reranker;
    private final RagConfig ragConfig;
    private final KnowledgeBaseManager knowledgeBaseManager;
    private final EmbeddingService embeddingService;

    public RagService(QueryRewriter queryRewriter,
                      QdrantVectorStore qdrantVectorStore,
                      VectorSearchService vectorSearchService,
                      RrfFusion rrfFusion,
                      Reranker reranker,
                      RagConfig ragConfig,
                      KnowledgeBaseManager knowledgeBaseManager,
                      EmbeddingService embeddingService) {
        this.queryRewriter = queryRewriter;
        this.qdrantVectorStore = qdrantVectorStore;
        this.vectorSearchService = vectorSearchService;
        this.rrfFusion = rrfFusion;
        this.reranker = reranker;
        this.ragConfig = ragConfig;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.embeddingService = embeddingService;
    }

    public SearchResult search(String query, String agentType) {
        long startTime = System.currentTimeMillis();

        if (!ragConfig.isEnabled()) {
            log.info("RAG is disabled (rag.enabled=false), returning empty result for agentType={}", agentType);
            return SearchResult.builder()
                    .documents(new ArrayList<>())
                    .rewrittenQuery(query)
                    .searchTime(0L)
                    .build();
        }

        log.info("Starting RAG search for query (len={}, agentType={}): {}", 
                query != null ? query.length() : -1, agentType, 
                query != null && query.length() > 100 ? query.substring(0, 100) + "..." : query);

        String rewrittenQuery = queryRewriter.rewrite(query, agentType);
        
        log.info("Rewritten query (len={}): {}", 
                rewrittenQuery != null ? rewrittenQuery.length() : -1, 
                rewrittenQuery != null && rewrittenQuery.length() > 100 ? rewrittenQuery.substring(0, 100) + "..." : rewrittenQuery);

        List<Document> results;

        if (qdrantVectorStore.isAvailable()) {
            results = searchWithQdrant(rewrittenQuery, agentType);
            if (results == null || results.isEmpty()) {
                log.warn("Qdrant search returned empty results, falling back to memory search");
                results = searchInMemory(rewrittenQuery, agentType);
            }
        } else {
            log.info("Qdrant not available, using memory search");
            results = searchInMemory(rewrittenQuery, agentType);
        }

        List<Document> rerankedResults = reranker.rerank(rewrittenQuery, results);

        long searchTime = System.currentTimeMillis() - startTime;

        log.info("RAG search completed in {}ms (agentType: {}, qdrant: {})", searchTime, agentType, qdrantVectorStore.isAvailable());
        logRetrievedDocuments(agentType, rewrittenQuery, rerankedResults);

        return SearchResult.builder()
                .documents(rerankedResults)
                .rewrittenQuery(rewrittenQuery)
                .searchTime(searchTime)
                .build();
    }

    /**
     * 将检索到的文档完整保存到 txt 文件，方便调试 RAG 召回质量。
     * 文件路径：./logs/rag/rag_result_{agentType}_{时间戳}.txt
     */
    private void logRetrievedDocuments(String agentType, String query, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            log.info("=== RAG 检索结果 [{}] ===  0 条（空）", agentType);
            return;
        }

        // 构造文件路径
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new java.util.Date());
        String dirPath = "./logs/rag";
        String fileName = "rag_result_" + agentType + "_" + timestamp + ".txt";
        java.nio.file.Path dir = java.nio.file.Paths.get(dirPath);
        java.nio.file.Path filePath = dir.resolve(fileName);

        try {
            // 确保目录存在
            if (!java.nio.file.Files.exists(dir)) {
                java.nio.file.Files.createDirectories(dir);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("============================================================\n");
            sb.append("RAG 检索结果\n");
            sb.append("============================================================\n");
            sb.append("Agent类型: ").append(agentType).append("\n");
            sb.append("检索查询: ").append(query != null ? query : "(空)").append("\n");
            sb.append("文档数量: ").append(docs.size()).append("\n");
            sb.append("时间: ").append(timestamp).append("\n");
            sb.append("============================================================\n\n");

            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                String scoreStr = String.format("%.4f", doc.getScore());

                sb.append("────────────────────────────────────────────────────────────\n");
                sb.append(String.format("[%d/%d] 标题: %s\n", i + 1, docs.size(),
                        doc.getTitle() != null ? doc.getTitle() : "(无标题)"));
                sb.append(String.format("类别: %s | 分数: %s\n",
                        doc.getCategory() != null ? doc.getCategory() : "?",
                        scoreStr));
                if (doc.getTags() != null && !doc.getTags().isEmpty()) {
                    sb.append("标签: ").append(String.join(", ", doc.getTags())).append("\n");
                }
                sb.append("────────────────────────────────────────────────────────────\n");
                sb.append(doc.getContent() != null ? doc.getContent() : "(无内容)");
                sb.append("\n\n");
            }

            java.nio.file.Files.writeString(filePath, sb.toString());
            log.info("=== RAG 检索结果 [{}] === 共 {} 条，已保存到: {}", agentType, docs.size(), filePath.toAbsolutePath());

        } catch (Exception e) {
            // 写文件失败别影响主流程，降级为日志打印标题列表
            log.warn("保存 RAG 结果到文件失败，降级为日志打印: {}", e.getMessage());
            log.info("=== RAG 检索结果 [{}] === 共 {} 条", agentType, docs.size());
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                log.info("  [{}/{}] {}", i + 1, docs.size(),
                        doc.getTitle() != null ? doc.getTitle() : "(无标题)");
            }
        }
    }

    private List<Document> searchWithQdrant(String query, String agentType) {
        try {
            log.debug("Generating embedding for query (len={}, agentType={})", 
                    query != null ? query.length() : -1, agentType);
            
            float[] queryEmbedding = embeddingService.embed(query);
            
            log.info("Embedding generation result: null={}, length={}", 
                    queryEmbedding == null, 
                    queryEmbedding != null ? queryEmbedding.length : -1);
            
            if (queryEmbedding != null && queryEmbedding.length > 0) {
                log.debug("Generated query embedding with dimension: {}", queryEmbedding.length);
                List<Document> vectorResults = qdrantVectorStore.search(agentType, queryEmbedding, ragConfig.getTopK());
                List<Document> keywordResults = qdrantVectorStore.keywordSearch(agentType, query, ragConfig.getTopK());
                return rrfFusion.fuse(List.of(vectorResults, keywordResults));
            } else {
                log.warn("Failed to generate valid embedding for query (null or empty), falling back to keyword search");
                return qdrantVectorStore.keywordSearch(agentType, query, ragConfig.getTopK());
            }
        } catch (Exception e) {
            log.error("Qdrant search failed: {}, falling back to memory search", e.getMessage());
            return null;
        }
    }

    private List<Document> searchInMemory(String query, String agentType) {
        try {
            List<Document> agentDocuments = knowledgeBaseManager.toDocuments(agentType);
            return vectorSearchService.hybridSearch(query, agentDocuments, ragConfig.getTopK());
        } catch (Exception e) {
            log.error("Memory search failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public SearchResult search(String query) {
        return search(query, "requirement");
    }

    public String getEnhancedContext(String query, String agentType) {
        SearchResult result = search(query, agentType);

        if (result.getDocuments() == null || result.getDocuments().isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        
        List<Document> basics = new ArrayList<>();
        List<Document> examples = new ArrayList<>();
        List<Document> errorCorrections = new ArrayList<>();

        for (Document doc : result.getDocuments()) {
            String category = doc.getCategory();
            if ("basic".equals(category)) {
                basics.add(doc);
            } else if ("example".equals(category)) {
                examples.add(doc);
            } else if ("error_correction".equals(category)) {
                errorCorrections.add(doc);
            } else {
                basics.add(doc);
            }
        }

        if (!basics.isEmpty()) {
            context.append("【基础知识规范】\n");
            context.append("以下是相关的基础知识和规范，请在处理时遵循：\n");
            for (int i = 0; i < basics.size(); i++) {
                Document doc = basics.get(i);
                context.append(String.format("%d. %s\n", i + 1, doc.getContent()));
            }
            context.append("\n");
        }

        if (!examples.isEmpty()) {
            context.append("【正确示例参考】\n");
            context.append("以下是相关的正确示例，请参考其处理方式和输出格式：\n");
            for (int i = 0; i < examples.size(); i++) {
                Document doc = examples.get(i);
                context.append(String.format("%d. %s\n", i + 1, doc.getContent()));
            }
            context.append("\n");
        }

        if (!errorCorrections.isEmpty()) {
            context.append("【常见错误及修复】\n");
            context.append("以下是相关的错误案例和正确修复方式，请避免重复相同错误：\n");
            for (int i = 0; i < errorCorrections.size(); i++) {
                Document doc = errorCorrections.get(i);
                context.append(String.format("%d. %s\n", i + 1, doc.getContent()));
            }
            context.append("\n");
        }

        return context.toString();
    }

    public String getEnhancedContext(String query) {
        return getEnhancedContext(query, "requirement");
    }

    public boolean isQdrantAvailable() {
        return qdrantVectorStore.isAvailable();
    }

}