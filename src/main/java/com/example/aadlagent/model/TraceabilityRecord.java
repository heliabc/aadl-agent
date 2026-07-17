package com.example.aadlagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraceabilityRecord {

    private String id;

    private String originalRequirement;

    private String requirementId;

    private String requirementTitle;

    private String requirementDescription;

    private String aadlComponent;

    private String aadlCode;

    private String traceType;

    private String source;
}