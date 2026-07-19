package com.example.aadlplugin.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ErrorCorrection {

    private String id;
    private String title;
    private String errorType;

    @JsonProperty("badBehavior")
    private String errorContent;

    private String errorDescription;

    @JsonProperty("correctionRule")
    private String correctContent;

    private String correctionExplanation;
    private String suggestion;
    private List<String> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private float[] embedding;

    public ErrorCorrection() {
    }

    public ErrorCorrection(String id, String title, String errorType, String errorContent, String errorDescription,
                          String correctContent, String correctionExplanation, String suggestion,
                          List<String> tags, LocalDateTime createdAt, LocalDateTime updatedAt, float[] embedding) {
        this.id = id;
        this.title = title;
        this.errorType = errorType;
        this.errorContent = errorContent;
        this.errorDescription = errorDescription;
        this.correctContent = correctContent;
        this.correctionExplanation = correctionExplanation;
        this.suggestion = suggestion;
        this.tags = tags;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.embedding = embedding;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getErrorContent() {
        return errorContent;
    }

    public void setErrorContent(String errorContent) {
        this.errorContent = errorContent;
    }

    public String getErrorDescription() {
        return errorDescription;
    }

    public void setErrorDescription(String errorDescription) {
        this.errorDescription = errorDescription;
    }

    public String getCorrectContent() {
        return correctContent;
    }

    public void setCorrectContent(String correctContent) {
        this.correctContent = correctContent;
    }

    public String getCorrectionExplanation() {
        return correctionExplanation;
    }

    public void setCorrectionExplanation(String correctionExplanation) {
        this.correctionExplanation = correctionExplanation;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String title;
        private String errorType;
        private String errorContent;
        private String errorDescription;
        private String correctContent;
        private String correctionExplanation;
        private String suggestion;
        private List<String> tags;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private float[] embedding;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder errorType(String errorType) {
            this.errorType = errorType;
            return this;
        }

        public Builder errorContent(String errorContent) {
            this.errorContent = errorContent;
            return this;
        }

        public Builder errorDescription(String errorDescription) {
            this.errorDescription = errorDescription;
            return this;
        }

        public Builder correctContent(String correctContent) {
            this.correctContent = correctContent;
            return this;
        }

        public Builder correctionExplanation(String correctionExplanation) {
            this.correctionExplanation = correctionExplanation;
            return this;
        }

        public Builder suggestion(String suggestion) {
            this.suggestion = suggestion;
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Builder embedding(float[] embedding) {
            this.embedding = embedding;
            return this;
        }

        public ErrorCorrection build() {
            if (tags == null) tags = new ArrayList<>();
            return new ErrorCorrection(id, title, errorType, errorContent, errorDescription, correctContent,
                    correctionExplanation, suggestion, tags, createdAt, updatedAt, embedding);
        }
    }
}
