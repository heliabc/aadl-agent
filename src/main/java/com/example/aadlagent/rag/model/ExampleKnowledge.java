package com.example.aadlagent.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExampleKnowledge {

    private String id;
    private String title;
    private String scenario;
    private String input;
    
    @JsonProperty("goldenOutput")
    private String output;
    
    private String explanation;
    private List<String> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private float[] embedding;
}