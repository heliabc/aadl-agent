package com.example.aadlagent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatResponse {

    private String model;

    private ChatMessage message;

    private boolean done;

    @JsonProperty("done_reason")
    private String doneReason;

    private Usage usage;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("prompt_eval_count")
        private Integer promptEvalCount;

        @JsonProperty("eval_count")
        private Integer evalCount;

        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}