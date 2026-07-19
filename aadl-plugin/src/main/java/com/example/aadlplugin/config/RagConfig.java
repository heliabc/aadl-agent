package com.example.aadlplugin.config;

public class RagConfig {

    private int topK = 5;

    private int rerankTopK = 3;

    private int rrfK = 60;

    private boolean rewriteQuery = true;

    private boolean useRrf = true;

    private int chunkSize = 500;

    private int chunkOverlap = 50;

    public RagConfig() {
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getRerankTopK() {
        return rerankTopK;
    }

    public void setRerankTopK(int rerankTopK) {
        this.rerankTopK = rerankTopK;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public boolean isRewriteQuery() {
        return rewriteQuery;
    }

    public void setRewriteQuery(boolean rewriteQuery) {
        this.rewriteQuery = rewriteQuery;
    }

    public boolean isUseRrf() {
        return useRrf;
    }

    public void setUseRrf(boolean useRrf) {
        this.useRrf = useRrf;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }
}
