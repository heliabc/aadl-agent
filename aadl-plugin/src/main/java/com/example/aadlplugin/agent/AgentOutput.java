package com.example.aadlplugin.agent;

public class AgentOutput {

    private String sessionId;
    private String content;
    private boolean success;
    private String errorMessage;
    private long executionTime;

    public AgentOutput() {
    }

    public AgentOutput(String sessionId, String content, boolean success, String errorMessage, long executionTime) {
        this.sessionId = sessionId;
        this.content = content;
        this.success = success;
        this.errorMessage = errorMessage;
        this.executionTime = executionTime;
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

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getExecutionTime() {
        return executionTime;
    }

    public void setExecutionTime(long executionTime) {
        this.executionTime = executionTime;
    }

    public static AgentOutput success(String sessionId, String content, long executionTime) {
        return new AgentOutput(sessionId, content, true, null, executionTime);
    }

    public static AgentOutput failure(String sessionId, String errorMessage) {
        return new AgentOutput(sessionId, null, false, errorMessage, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private String content;
        private boolean success;
        private String errorMessage;
        private long executionTime;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder executionTime(long executionTime) {
            this.executionTime = executionTime;
            return this;
        }

        public AgentOutput build() {
            return new AgentOutput(sessionId, content, success, errorMessage, executionTime);
        }
    }
}
