package com.example.aadlagent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModuleAnalysisResult {

    @JsonProperty("modules")
    private List<Module> modules;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Module {
        @JsonProperty("module_name")
        private String moduleName;

        @JsonProperty("component_hierarchy")
        private List<String> componentHierarchy;

        @JsonProperty("sub_components")
        private List<String> subComponents;

        @JsonProperty("function_description")
        private String functionDescription;

        @JsonProperty("related_components")
        private List<String> relatedComponents;
    }
}