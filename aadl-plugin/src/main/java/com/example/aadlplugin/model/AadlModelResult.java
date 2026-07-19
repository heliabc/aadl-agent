package com.example.aadlplugin.model;

public class AadlModelResult {

    private String aadlContent;
    private Integer componentCount;
    private Integer connectionCount;

    public AadlModelResult() {
    }

    public AadlModelResult(String aadlContent, Integer componentCount, Integer connectionCount) {
        this.aadlContent = aadlContent;
        this.componentCount = componentCount;
        this.connectionCount = connectionCount;
    }

    public String getAadlContent() {
        return aadlContent;
    }

    public void setAadlContent(String aadlContent) {
        this.aadlContent = aadlContent;
    }

    public Integer getComponentCount() {
        return componentCount;
    }

    public void setComponentCount(Integer componentCount) {
        this.componentCount = componentCount;
    }

    public Integer getConnectionCount() {
        return connectionCount;
    }

    public void setConnectionCount(Integer connectionCount) {
        this.connectionCount = connectionCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String aadlContent;
        private Integer componentCount;
        private Integer connectionCount;

        public Builder aadlContent(String aadlContent) {
            this.aadlContent = aadlContent;
            return this;
        }

        public Builder componentCount(Integer componentCount) {
            this.componentCount = componentCount;
            return this;
        }

        public Builder connectionCount(Integer connectionCount) {
            this.connectionCount = connectionCount;
            return this;
        }

        public AadlModelResult build() {
            return new AadlModelResult(aadlContent, componentCount, connectionCount);
        }
    }
}
