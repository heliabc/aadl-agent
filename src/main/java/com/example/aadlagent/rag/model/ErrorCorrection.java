package com.example.aadlagent.rag.model;

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
public class ErrorCorrection {

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
}