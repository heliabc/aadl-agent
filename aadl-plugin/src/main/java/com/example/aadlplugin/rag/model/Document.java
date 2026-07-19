package com.example.aadlplugin.rag.model;

import java.util.ArrayList;
import java.util.List;

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

    public Document() {
    }

    public Document(String id, String content, String title, String source, String category, String agentType,
                    List<String> tags, float[] embedding, double score, String payload,
                    List<String> hardwareInterfaces, List<String> sensors, List<String> actuators,
                    List<String> applicationDomains, List<String> safetyLevels, List<String> schedulingPolicies) {
        this.id = id;
        this.content = content;
        this.title = title;
        this.source = source;
        this.category = category;
        this.agentType = agentType;
        this.tags = tags;
        this.embedding = embedding;
        this.score = score;
        this.payload = payload;
        this.hardwareInterfaces = hardwareInterfaces;
        this.sensors = sensors;
        this.actuators = actuators;
        this.applicationDomains = applicationDomains;
        this.safetyLevels = safetyLevels;
        this.schedulingPolicies = schedulingPolicies;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public List<String> getHardwareInterfaces() {
        return hardwareInterfaces;
    }

    public void setHardwareInterfaces(List<String> hardwareInterfaces) {
        this.hardwareInterfaces = hardwareInterfaces;
    }

    public List<String> getSensors() {
        return sensors;
    }

    public void setSensors(List<String> sensors) {
        this.sensors = sensors;
    }

    public List<String> getActuators() {
        return actuators;
    }

    public void setActuators(List<String> actuators) {
        this.actuators = actuators;
    }

    public List<String> getApplicationDomains() {
        return applicationDomains;
    }

    public void setApplicationDomains(List<String> applicationDomains) {
        this.applicationDomains = applicationDomains;
    }

    public List<String> getSafetyLevels() {
        return safetyLevels;
    }

    public void setSafetyLevels(List<String> safetyLevels) {
        this.safetyLevels = safetyLevels;
    }

    public List<String> getSchedulingPolicies() {
        return schedulingPolicies;
    }

    public void setSchedulingPolicies(List<String> schedulingPolicies) {
        this.schedulingPolicies = schedulingPolicies;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
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

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder embedding(float[] embedding) {
            this.embedding = embedding;
            return this;
        }

        public Builder score(double score) {
            this.score = score;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder hardwareInterfaces(List<String> hardwareInterfaces) {
            this.hardwareInterfaces = hardwareInterfaces;
            return this;
        }

        public Builder sensors(List<String> sensors) {
            this.sensors = sensors;
            return this;
        }

        public Builder actuators(List<String> actuators) {
            this.actuators = actuators;
            return this;
        }

        public Builder applicationDomains(List<String> applicationDomains) {
            this.applicationDomains = applicationDomains;
            return this;
        }

        public Builder safetyLevels(List<String> safetyLevels) {
            this.safetyLevels = safetyLevels;
            return this;
        }

        public Builder schedulingPolicies(List<String> schedulingPolicies) {
            this.schedulingPolicies = schedulingPolicies;
            return this;
        }

        public Document build() {
            if (tags == null) tags = new ArrayList<>();
            if (hardwareInterfaces == null) hardwareInterfaces = new ArrayList<>();
            if (sensors == null) sensors = new ArrayList<>();
            if (actuators == null) actuators = new ArrayList<>();
            if (applicationDomains == null) applicationDomains = new ArrayList<>();
            if (safetyLevels == null) safetyLevels = new ArrayList<>();
            if (schedulingPolicies == null) schedulingPolicies = new ArrayList<>();
            return new Document(id, content, title, source, category, agentType,
                    tags, embedding, score, payload, hardwareInterfaces, sensors,
                    actuators, applicationDomains, safetyLevels, schedulingPolicies);
        }
    }
}
