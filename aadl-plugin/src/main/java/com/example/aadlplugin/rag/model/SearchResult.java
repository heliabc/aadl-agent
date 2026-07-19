package com.example.aadlplugin.rag.model;

import java.util.List;

public class SearchResult {

    private List<Document> documents;
    private String rewrittenQuery;
    private long searchTime;

    public SearchResult() {
    }

    public SearchResult(List<Document> documents, String rewrittenQuery, long searchTime) {
        this.documents = documents;
        this.rewrittenQuery = rewrittenQuery;
        this.searchTime = searchTime;
    }

    public List<Document> getDocuments() {
        return documents;
    }

    public void setDocuments(List<Document> documents) {
        this.documents = documents;
    }

    public String getRewrittenQuery() {
        return rewrittenQuery;
    }

    public void setRewrittenQuery(String rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    public long getSearchTime() {
        return searchTime;
    }

    public void setSearchTime(long searchTime) {
        this.searchTime = searchTime;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<Document> documents;
        private String rewrittenQuery;
        private long searchTime;

        public Builder documents(List<Document> documents) {
            this.documents = documents;
            return this;
        }

        public Builder rewrittenQuery(String rewrittenQuery) {
            this.rewrittenQuery = rewrittenQuery;
            return this;
        }

        public Builder searchTime(long searchTime) {
            this.searchTime = searchTime;
            return this;
        }

        public SearchResult build() {
            return new SearchResult(documents, rewrittenQuery, searchTime);
        }
    }
}
