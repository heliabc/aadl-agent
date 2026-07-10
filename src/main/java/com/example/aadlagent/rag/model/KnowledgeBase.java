package com.example.aadlagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {

    private String agentType;
    
    @Builder.Default
    private List<BasicKnowledge> basics = new ArrayList<>();
    
    @Builder.Default
    private List<ExampleKnowledge> examples = new ArrayList<>();
    
    @Builder.Default
    private List<ErrorCorrection> errorCorrections = new ArrayList<>();
    
    private LocalDateTime lastModified;

    public int getTotalEntries() {
        return basics.size() + examples.size() + errorCorrections.size();
    }
}