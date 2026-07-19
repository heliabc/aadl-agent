package com.example.aadlplugin.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public class ModuleAnalysisResult {

    @JsonProperty("modules")
    private List<Module> modules;

    public ModuleAnalysisResult() {
    }

    public ModuleAnalysisResult(List<Module> modules) {
        this.modules = modules;
    }

    public List<Module> getModules() {
        return modules;
    }

    public void setModules(List<Module> modules) {
        this.modules = modules;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<Module> modules;

        public Builder modules(List<Module> modules) {
            this.modules = modules;
            return this;
        }

        public ModuleAnalysisResult build() {
            return new ModuleAnalysisResult(modules);
        }
    }

    public static class Module {
        @JsonProperty("module_name")
        private String moduleName;

        @JsonProperty("satisfied_requirements")
        private List<String> satisfiedRequirements;

        @JsonProperty("component_hierarchy")
        private List<String> componentHierarchy;

        @JsonProperty("sub_components")
        private List<String> subComponents;

        @JsonProperty("function_description")
        private String functionDescription;

        @JsonProperty("related_components")
        private List<String> relatedComponents;

        public Module() {
        }

        public Module(String moduleName, List<String> satisfiedRequirements, List<String> componentHierarchy,
                      List<String> subComponents, String functionDescription, List<String> relatedComponents) {
            this.moduleName = moduleName;
            this.satisfiedRequirements = satisfiedRequirements;
            this.componentHierarchy = componentHierarchy;
            this.subComponents = subComponents;
            this.functionDescription = functionDescription;
            this.relatedComponents = relatedComponents;
        }

        public String getModuleName() {
            return moduleName;
        }

        public void setModuleName(String moduleName) {
            this.moduleName = moduleName;
        }

        public List<String> getSatisfiedRequirements() {
            return satisfiedRequirements;
        }

        public void setSatisfiedRequirements(List<String> satisfiedRequirements) {
            this.satisfiedRequirements = satisfiedRequirements;
        }

        public List<String> getComponentHierarchy() {
            return componentHierarchy;
        }

        public void setComponentHierarchy(List<String> componentHierarchy) {
            this.componentHierarchy = componentHierarchy;
        }

        public List<String> getSubComponents() {
            return subComponents;
        }

        public void setSubComponents(List<String> subComponents) {
            this.subComponents = subComponents;
        }

        public String getFunctionDescription() {
            return functionDescription;
        }

        public void setFunctionDescription(String functionDescription) {
            this.functionDescription = functionDescription;
        }

        public List<String> getRelatedComponents() {
            return relatedComponents;
        }

        public void setRelatedComponents(List<String> relatedComponents) {
            this.relatedComponents = relatedComponents;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String moduleName;
            private List<String> satisfiedRequirements;
            private List<String> componentHierarchy;
            private List<String> subComponents;
            private String functionDescription;
            private List<String> relatedComponents;

            public Builder moduleName(String moduleName) {
                this.moduleName = moduleName;
                return this;
            }

            public Builder satisfiedRequirements(List<String> satisfiedRequirements) {
                this.satisfiedRequirements = satisfiedRequirements;
                return this;
            }

            public Builder componentHierarchy(List<String> componentHierarchy) {
                this.componentHierarchy = componentHierarchy;
                return this;
            }

            public Builder subComponents(List<String> subComponents) {
                this.subComponents = subComponents;
                return this;
            }

            public Builder functionDescription(String functionDescription) {
                this.functionDescription = functionDescription;
                return this;
            }

            public Builder relatedComponents(List<String> relatedComponents) {
                this.relatedComponents = relatedComponents;
                return this;
            }

            public Module build() {
                if (satisfiedRequirements == null) satisfiedRequirements = new ArrayList<>();
                if (componentHierarchy == null) componentHierarchy = new ArrayList<>();
                if (subComponents == null) subComponents = new ArrayList<>();
                if (relatedComponents == null) relatedComponents = new ArrayList<>();
                return new Module(moduleName, satisfiedRequirements, componentHierarchy,
                        subComponents, functionDescription, relatedComponents);
            }
        }
    }
}
