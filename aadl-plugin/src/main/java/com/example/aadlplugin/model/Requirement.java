package com.example.aadlplugin.model;

import java.util.ArrayList;
import java.util.List;

public class Requirement {

    private String requirementId;
    private String title;
    private String description;
    private String priority;
    private List<String> acceptanceCriteria;
    private List<String> dependencies;

    public Requirement() {
    }

    public Requirement(String requirementId, String title, String description, String priority,
                       List<String> acceptanceCriteria, List<String> dependencies) {
        this.requirementId = requirementId;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.acceptanceCriteria = acceptanceCriteria;
        this.dependencies = dependencies;
    }

    public String getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(String requirementId) {
        this.requirementId = requirementId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public List<String> getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public void setAcceptanceCriteria(List<String> acceptanceCriteria) {
        this.acceptanceCriteria = acceptanceCriteria;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String requirementId;
        private String title;
        private String description;
        private String priority;
        private List<String> acceptanceCriteria;
        private List<String> dependencies;

        public Builder requirementId(String requirementId) {
            this.requirementId = requirementId;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder priority(String priority) {
            this.priority = priority;
            return this;
        }

        public Builder acceptanceCriteria(List<String> acceptanceCriteria) {
            this.acceptanceCriteria = acceptanceCriteria;
            return this;
        }

        public Builder dependencies(List<String> dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public Requirement build() {
            if (acceptanceCriteria == null) acceptanceCriteria = new ArrayList<>();
            if (dependencies == null) dependencies = new ArrayList<>();
            return new Requirement(requirementId, title, description, priority, acceptanceCriteria, dependencies);
        }
    }
}
