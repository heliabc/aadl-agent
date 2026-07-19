package com.example.aadlplugin.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatResponse {

    private String model;
    private ChatMessage message;
    private boolean done;

    @JsonProperty("done_reason")
    private String doneReason;

    private Usage usage;

    public ChatResponse() {
    }

    public ChatResponse(String model, ChatMessage message, boolean done, String doneReason, Usage usage) {
        this.model = model;
        this.message = message;
        this.done = done;
        this.doneReason = doneReason;
        this.usage = usage;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public ChatMessage getMessage() {
        return message;
    }

    public void setMessage(ChatMessage message) {
        this.message = message;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public String getDoneReason() {
        return doneReason;
    }

    public void setDoneReason(String doneReason) {
        this.doneReason = doneReason;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private ChatMessage message;
        private boolean done;
        private String doneReason;
        private Usage usage;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder message(ChatMessage message) {
            this.message = message;
            return this;
        }

        public Builder done(boolean done) {
            this.done = done;
            return this;
        }

        public Builder doneReason(String doneReason) {
            this.doneReason = doneReason;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public ChatResponse build() {
            return new ChatResponse(model, message, done, doneReason, usage);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("prompt_eval_count")
        private Integer promptEvalCount;

        @JsonProperty("eval_count")
        private Integer evalCount;

        @JsonProperty("total_tokens")
        private Integer totalTokens;

        public Usage() {
        }

        public Usage(Integer promptEvalCount, Integer evalCount, Integer totalTokens) {
            this.promptEvalCount = promptEvalCount;
            this.evalCount = evalCount;
            this.totalTokens = totalTokens;
        }

        public Integer getPromptEvalCount() {
            return promptEvalCount;
        }

        public void setPromptEvalCount(Integer promptEvalCount) {
            this.promptEvalCount = promptEvalCount;
        }

        public Integer getEvalCount() {
            return evalCount;
        }

        public void setEvalCount(Integer evalCount) {
            this.evalCount = evalCount;
        }

        public Integer getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(Integer totalTokens) {
            this.totalTokens = totalTokens;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Integer promptEvalCount;
            private Integer evalCount;
            private Integer totalTokens;

            public Builder promptEvalCount(Integer promptEvalCount) {
                this.promptEvalCount = promptEvalCount;
                return this;
            }

            public Builder evalCount(Integer evalCount) {
                this.evalCount = evalCount;
                return this;
            }

            public Builder totalTokens(Integer totalTokens) {
                this.totalTokens = totalTokens;
                return this;
            }

            public Usage build() {
                return new Usage(promptEvalCount, evalCount, totalTokens);
            }
        }
    }
}
