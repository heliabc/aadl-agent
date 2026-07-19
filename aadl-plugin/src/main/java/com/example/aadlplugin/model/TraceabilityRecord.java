package com.example.aadlplugin.model;

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

    public TraceabilityRecord() {
    }

    public TraceabilityRecord(String id, String originalRequirement, String requirementId, String requirementTitle,
                              String requirementDescription, String aadlComponent, String aadlCode,
                              String traceLevel, String source) {
        this.id = id;
        this.originalRequirement = originalRequirement;
        this.requirementId = requirementId;
        this.requirementTitle = requirementTitle;
        this.requirementDescription = requirementDescription;
        this.aadlComponent = aadlComponent;
        this.aadlCode = aadlCode;
        this.traceLevel = traceLevel;
        this.source = source;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOriginalRequirement() {
        return originalRequirement;
    }

    public void setOriginalRequirement(String originalRequirement) {
        this.originalRequirement = originalRequirement;
    }

    public String getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(String requirementId) {
        this.requirementId = requirementId;
    }

    public String getRequirementTitle() {
        return requirementTitle;
    }

    public void setRequirementTitle(String requirementTitle) {
        this.requirementTitle = requirementTitle;
    }

    public String getRequirementDescription() {
        return requirementDescription;
    }

    public void setRequirementDescription(String requirementDescription) {
        this.requirementDescription = requirementDescription;
    }

    public String getAadlComponent() {
        return aadlComponent;
    }

    public void setAadlComponent(String aadlComponent) {
        this.aadlComponent = aadlComponent;
    }

    public String getAadlCode() {
        return aadlCode;
    }

    public void setAadlCode(String aadlCode) {
        this.aadlCode = aadlCode;
    }

    public String getTraceLevel() {
        return traceLevel;
    }

    public void setTraceLevel(String traceLevel) {
        this.traceLevel = traceLevel;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String originalRequirement;
        private String requirementId;
        private String requirementTitle;
        private String requirementDescription;
        private String aadlComponent;
        private String aadlCode;
        private String traceLevel;
        private String source;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder originalRequirement(String originalRequirement) {
            this.originalRequirement = originalRequirement;
            return this;
        }

        public Builder requirementId(String requirementId) {
            this.requirementId = requirementId;
            return this;
        }

        public Builder requirementTitle(String requirementTitle) {
            this.requirementTitle = requirementTitle;
            return this;
        }

        public Builder requirementDescription(String requirementDescription) {
            this.requirementDescription = requirementDescription;
            return this;
        }

        public Builder aadlComponent(String aadlComponent) {
            this.aadlComponent = aadlComponent;
            return this;
        }

        public Builder aadlCode(String aadlCode) {
            this.aadlCode = aadlCode;
            return this;
        }

        public Builder traceLevel(String traceLevel) {
            this.traceLevel = traceLevel;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public TraceabilityRecord build() {
            return new TraceabilityRecord(id, originalRequirement, requirementId, requirementTitle,
                    requirementDescription, aadlComponent, aadlCode, traceLevel, source);
        }
    }
}
