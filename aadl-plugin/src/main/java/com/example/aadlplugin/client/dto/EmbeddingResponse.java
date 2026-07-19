package com.example.aadlplugin.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingResponse {

    private String model;
    private List<List<Float>> embeddings;

    @JsonProperty("prompt_eval_count")
    private Integer promptEvalCount;

    @JsonProperty("total_eval_count")
    private Integer totalEvalCount;

    public EmbeddingResponse() {
    }

    public EmbeddingResponse(String model, List<List<Float>> embeddings, Integer promptEvalCount, Integer totalEvalCount) {
        this.model = model;
        this.embeddings = embeddings;
        this.promptEvalCount = promptEvalCount;
        this.totalEvalCount = totalEvalCount;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<List<Float>> getEmbeddings() {
        return embeddings;
    }

    public void setEmbeddings(List<List<Float>> embeddings) {
        this.embeddings = embeddings;
    }

    public Integer getPromptEvalCount() {
        return promptEvalCount;
    }

    public void setPromptEvalCount(Integer promptEvalCount) {
        this.promptEvalCount = promptEvalCount;
    }

    public Integer getTotalEvalCount() {
        return totalEvalCount;
    }

    public void setTotalEvalCount(Integer totalEvalCount) {
        this.totalEvalCount = totalEvalCount;
    }

    public String getUsage() {
        StringBuilder sb = new StringBuilder();
        if (promptEvalCount != null) {
            sb.append("prompt_eval_count=").append(promptEvalCount);
        }
        if (totalEvalCount != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("total_eval_count=").append(totalEvalCount);
        }
        return sb.length() > 0 ? sb.toString() : "N/A";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private List<List<Float>> embeddings;
        private Integer promptEvalCount;
        private Integer totalEvalCount;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder embeddings(List<List<Float>> embeddings) {
            this.embeddings = embeddings;
            return this;
        }

        public Builder promptEvalCount(Integer promptEvalCount) {
            this.promptEvalCount = promptEvalCount;
            return this;
        }

        public Builder totalEvalCount(Integer totalEvalCount) {
            this.totalEvalCount = totalEvalCount;
            return this;
        }

        public EmbeddingResponse build() {
            return new EmbeddingResponse(model, embeddings, promptEvalCount, totalEvalCount);
        }
    }
}
