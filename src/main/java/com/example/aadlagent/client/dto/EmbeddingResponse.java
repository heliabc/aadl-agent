package com.example.aadlagent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingResponse {

    private String model;

    private List<List<Float>> embeddings;

    @JsonProperty("prompt_eval_count")
    private Integer promptEvalCount;

    @JsonProperty("total_eval_count")
    private Integer totalEvalCount;

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
}