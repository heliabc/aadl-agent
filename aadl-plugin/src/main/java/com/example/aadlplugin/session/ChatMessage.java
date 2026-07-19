package com.example.aadlplugin.session;

import java.time.LocalDateTime;

public class ChatMessage {

    private String role;
    private String content;
    private String agentType;
    private LocalDateTime timestamp;

    public ChatMessage() {
    }

    public ChatMessage(String role, String content, String agentType, LocalDateTime timestamp) {
        this.role = role;
        this.content = content;
        this.agentType = agentType;
        this.timestamp = timestamp;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, LocalDateTime.now());
    }

    public static ChatMessage assistant(String content, String agentType) {
        return new ChatMessage("assistant", content, agentType, LocalDateTime.now());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, LocalDateTime.now());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String role;
        private String content;
        private String agentType;
        private LocalDateTime timestamp;

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder timestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public ChatMessage build() {
            return new ChatMessage(role, content, agentType, timestamp);
        }
    }
}
