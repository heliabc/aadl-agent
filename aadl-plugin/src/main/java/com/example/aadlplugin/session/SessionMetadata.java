package com.example.aadlplugin.session;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class SessionMetadata {
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private String sessionId;
    private String name;
    private String requirementSummary;
    private String modelType;
    private LocalDateTime createdAt;
    private LocalDateTime lastModified;
    private int requirementCount;
    private boolean hasAadlGenerated;
    
    public SessionMetadata() {
        this.createdAt = LocalDateTime.now();
        this.lastModified = LocalDateTime.now();
    }
    
    public SessionMetadata(String sessionId, String name, String requirementSummary) {
        this.sessionId = sessionId;
        this.name = name;
        this.requirementSummary = requirementSummary;
        this.createdAt = LocalDateTime.now();
        this.lastModified = LocalDateTime.now();
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
        this.lastModified = LocalDateTime.now();
    }
    
    public String getRequirementSummary() {
        return requirementSummary;
    }
    
    public void setRequirementSummary(String requirementSummary) {
        this.requirementSummary = requirementSummary;
        this.lastModified = LocalDateTime.now();
    }
    
    public String getModelType() {
        return modelType;
    }
    
    public void setModelType(String modelType) {
        this.modelType = modelType;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getLastModified() {
        return lastModified;
    }
    
    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }
    
    public int getRequirementCount() {
        return requirementCount;
    }
    
    public void setRequirementCount(int requirementCount) {
        this.requirementCount = requirementCount;
    }
    
    public boolean isHasAadlGenerated() {
        return hasAadlGenerated;
    }
    
    public void setHasAadlGenerated(boolean hasAadlGenerated) {
        this.hasAadlGenerated = hasAadlGenerated;
    }
    
    public String getCreatedAtFormatted() {
        return createdAt.format(FORMATTER);
    }
    
    public String getLastModifiedFormatted() {
        return lastModified.format(FORMATTER);
    }
    
    @Override
    public String toString() {
        return String.format("%s - %s (%s)", name, getCreatedAtFormatted(), modelType);
    }
}
