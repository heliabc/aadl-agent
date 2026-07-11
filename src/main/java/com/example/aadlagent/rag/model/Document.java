package com.example.aadlagent.rag.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    private String id;

    private String content;

    private String title;

    private String source;

    private String category;

    private String agentType;

    private List<String> tags;

    private float[] embedding;

    private double score;

    private String payload;

    private List<String> hardwareInterfaces;

    private List<String> sensors;

    private List<String> actuators;

    private List<String> applicationDomains;

    private List<String> safetyLevels;

    private List<String> schedulingPolicies;
}