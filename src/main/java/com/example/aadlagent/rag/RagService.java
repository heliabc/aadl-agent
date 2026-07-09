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
            float[] queryEmbedding = embeddingService.embed(rewrittenQuery);
            if (queryEmbedding != null) {
                List<Document> vectorResults = qdrantVectorStore.search(agentType, queryEmbedding, ragConfig.getTopK());
                List<Document> keywordResults = qdrantVectorStore.keywordSearch(agentType, rewrittenQuery, ragConfig.getTopK());
                results = rrfFusion.fuse(List.of(vectorResults, keywordResults));
            } else {
                results = qdrantVectorStore.keywordSearch(agentType, rewrittenQuery, ragConfig.getTopK());
            }
        } else {
            List<Document> agentDocuments = knowledgeBaseManager.toDocuments(agentType);
            results = vectorSearchService.hybridSearch(rewrittenQuery, agentDocuments, ragConfig.getTopK());
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

    public SearchResult search(String query) {
        return search(query, "requirement");
    }

    public String getEnhancedContext(String query, String agentType) {
        SearchResult result = search(query, agentType);

        if (result.getDocuments() == null || result.getDocuments().isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("参考知识：\n");

        for (int i = 0; i < result.getDocuments().size(); i++) {
            Document doc = result.getDocuments().get(i);
            context.append(String.format("%d. %s\n", i + 1, doc.getContent()));
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