package com.example.aadlplugin.rag.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class KnowledgeBase {

    private String agentType;
    private List<BasicKnowledge> basics;
    private List<ExampleKnowledge> examples;
    private List<ErrorCorrection> errorCorrections;
    private LocalDateTime lastModified;

    public KnowledgeBase() {
        this.basics = new ArrayList<>();
        this.examples = new ArrayList<>();
        this.errorCorrections = new ArrayList<>();
    }

    public KnowledgeBase(String agentType, List<BasicKnowledge> basics, List<ExampleKnowledge> examples,
                         List<ErrorCorrection> errorCorrections, LocalDateTime lastModified) {
        this.agentType = agentType;
        this.basics = basics != null ? basics : new ArrayList<>();
        this.examples = examples != null ? examples : new ArrayList<>();
        this.errorCorrections = errorCorrections != null ? errorCorrections : new ArrayList<>();
        this.lastModified = lastModified;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public List<BasicKnowledge> getBasics() {
        return basics;
    }

    public void setBasics(List<BasicKnowledge> basics) {
        this.basics = basics != null ? basics : new ArrayList<>();
    }

    public List<ExampleKnowledge> getExamples() {
        return examples;
    }

    public void setExamples(List<ExampleKnowledge> examples) {
        this.examples = examples != null ? examples : new ArrayList<>();
    }

    public List<ErrorCorrection> getErrorCorrections() {
        return errorCorrections;
    }

    public void setErrorCorrections(List<ErrorCorrection> errorCorrections) {
        this.errorCorrections = errorCorrections != null ? errorCorrections : new ArrayList<>();
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }

    public int getTotalEntries() {
        return basics.size() + examples.size() + errorCorrections.size();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String agentType;
        private List<BasicKnowledge> basics = new ArrayList<>();
        private List<ExampleKnowledge> examples = new ArrayList<>();
        private List<ErrorCorrection> errorCorrections = new ArrayList<>();
        private LocalDateTime lastModified;

        public Builder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder basics(List<BasicKnowledge> basics) {
            this.basics = basics != null ? basics : new ArrayList<>();
            return this;
        }

        public Builder examples(List<ExampleKnowledge> examples) {
            this.examples = examples != null ? examples : new ArrayList<>();
            return this;
        }

        public Builder errorCorrections(List<ErrorCorrection> errorCorrections) {
            this.errorCorrections = errorCorrections != null ? errorCorrections : new ArrayList<>();
            return this;
        }

        public Builder lastModified(LocalDateTime lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        public KnowledgeBase build() {
            return new KnowledgeBase(agentType, basics, examples, errorCorrections, lastModified);
        }
    }
}
