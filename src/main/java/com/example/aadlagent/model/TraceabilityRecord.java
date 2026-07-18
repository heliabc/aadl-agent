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

    public static final String TRACE_LEVEL_REQ_TO_ORIGINAL = "REQ_TO_ORIGINAL";
    public static final String TRACE_LEVEL_AADL_TO_REQ = "AADL_TO_REQ";

    private String id;

    private String originalRequirement;

    private String requirementId;

    private String requirementTitle;

    private String requirementDescription;

    private String aadlComponent;

    private String aadlCode;

    private String traceLevel;

    private String source;
}