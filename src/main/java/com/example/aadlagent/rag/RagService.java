
package com.example.aadlagent.rag;

import com.example.aadlagent.rag.model.Document;
import com.example.aadlagent.rag.model.SearchResult;
import com.example.aadlagent.config.RagConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class RagService {

    private final QueryRewriter queryRewriter;
    private final VectorSearchService vectorSearchService;
    private final RrfFusion rrfFusion;
    private final Reranker reranker;
    private final RagConfig ragConfig;

    public RagService(QueryRewriter queryRewriter,
                      VectorSearchService vectorSearchService,
                      RrfFusion rrfFusion,
                      Reranker reranker,
                      RagConfig ragConfig) {
        this.queryRewriter = queryRewriter;
        this.vectorSearchService = vectorSearchService;
        this.rrfFusion = rrfFusion;
        this.reranker = reranker;
        this.ragConfig = ragConfig;
    }

    public SearchResult search(String query) {
        long startTime = System.currentTimeMillis();

        log.info("Starting RAG search for query: {}", query);

        String rewrittenQuery = queryRewriter.rewrite(query);

        List<Document> results = vectorSearchService.hybridSearch(rewrittenQuery, ragConfig.getTopK());

        List<Document> rerankedResults = reranker.rerank(rewrittenQuery, results);

        long searchTime = System.currentTimeMillis() - startTime;

        log.info("RAG search completed in {}ms", searchTime);

        return SearchResult.builder()
                .documents(rerankedResults)
                .rewrittenQuery(rewrittenQuery)
                .searchTime(searchTime)
                .build();
    }

    public String getEnhancedContext(String query) {
        SearchResult result = search(query);

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
}
