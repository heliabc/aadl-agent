package com.example.aadlplugin.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AadlArchitectureModel {

    private String name;
    private String type;
    private List<AadlArchitectureModel> children;

    public AadlArchitectureModel() {
    }

    public AadlArchitectureModel(String name, String type, List<AadlArchitectureModel> children) {
        this.name = name;
        this.type = type;
        this.children = children;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<AadlArchitectureModel> getChildren() {
        return children;
    }

    public void setChildren(List<AadlArchitectureModel> children) {
        this.children = children;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String type;
        private List<AadlArchitectureModel> children;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder children(List<AadlArchitectureModel> children) {
            this.children = children;
            return this;
        }

        public AadlArchitectureModel build() {
            if (children == null) children = new ArrayList<>();
            return new AadlArchitectureModel(name, type, children);
        }
    }
}
