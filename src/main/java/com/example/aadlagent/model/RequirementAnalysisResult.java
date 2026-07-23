package com.example.aadlagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RequirementAnalysisResult {

    private String rawInput;

    private Stage0Result stage0;

    private Stage1Result stage1;

    private Stage2Result stage2;

    private Stage3Result stage3;

    private long totalExecutionTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Stage0Result {
        private List<GlobalAnchor> anchors;
        private String contextCard;
        private long executionTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Stage1Result {
        private List<DocumentChunk> chunks;
        private long executionTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Stage2Result {
        private List<List<Requirement>> chunkResults;
        private long executionTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Stage3Result {
        private List<Requirement> mergedRequirements;
        private List<Conflict> conflicts;
        private long executionTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DocumentChunk {
        private int chunkId;
        private String content;
        private String sectionId;
        private String sectionTitle;
        private int startLine;
        private int endLine;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Conflict {
        private String conflictId;
        private String anchorId;
        private String description;
        private List<String> conflictingRequirementIds;
        private List<String> conflictingValues;
    }
}
