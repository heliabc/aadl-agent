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

        log.info("Starting RAG search for query: {} (agentType: {})", query, agentType);

        String rewrittenQuery = queryRewriter.rewrite(query);

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

        return SearchResult.builder()
                .documents(rerankedResults)
                .rewrittenQuery(rewrittenQuery)
                .searchTime(searchTime)
                .build();
    }

    private List<Document> searchWithQdrant(String query, String agentType) {
        try {
            float[] queryEmbedding = embeddingService.embed(query);
            if (queryEmbedding != null) {
                List<Document> vectorResults = qdrantVectorStore.search(agentType, queryEmbedding, ragConfig.getTopK());
                List<Document> keywordResults = qdrantVectorStore.keywordSearch(agentType, query, ragConfig.getTopK());
                return rrfFusion.fuse(List.of(vectorResults, keywordResults));
            } else {
                log.warn("Failed to generate embedding for query, falling back to keyword search");
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