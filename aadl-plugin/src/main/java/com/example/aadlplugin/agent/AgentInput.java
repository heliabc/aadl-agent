package com.example.aadlplugin.agent;

import com.example.aadlplugin.client.ModelType;

public class AgentInput {

    private String sessionId;
    private String content;
    private String metadata;
    private String ragContext;
    private ModelType modelType;

    public AgentInput() {
    }

    public AgentInput(String sessionId, String content, String metadata, String ragContext, ModelType modelType) {
        this.sessionId = sessionId;
        this.content = content;
        this.metadata = metadata;
        this.ragContext = ragContext;
        this.modelType = modelType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getRagContext() {
        return ragContext;
    }

    public void setRagContext(String ragContext) {
        this.ragContext = ragContext;
    }

    public ModelType getModelType() {
        return modelType;
    }

    public void setModelType(ModelType modelType) {
        this.modelType = modelType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private String content;
        private String metadata;
        private String ragContext;
        private ModelType modelType;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder metadata(String metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder ragContext(String ragContext) {
            this.ragContext = ragContext;
            return this;
        }

        public Builder modelType(ModelType modelType) {
            this.modelType = modelType;
            return this;
        }

        public AgentInput build() {
            return new AgentInput(sessionId, content, metadata, ragContext, modelType);
        }
    }
}
