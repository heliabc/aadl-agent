package com.example.aadlagent.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AadlArchitectureModel {

    private String name;

    private String type;

    /**
     * 子组件列表（独立构件，有自己的 name 和 type）。
     * 反序列化时同时兼容旧字段名 "children"。
     */
    @JsonAlias("children")
    private List<AadlArchitectureModel> subcomponents;

    /**
     * 特征列表（组件内部的端口/访问点声明，不是独立组件）。
     * 每个 feature 包含 name、kind、direction、data_type 等属性。
     */
    private List<FeatureModel> features;

    /**
     * 特征（feature）模型。
     * 表示组件内部的端口、总线访问等声明，不是独立组件。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FeatureModel {
        /** 特征名称，如 "sensorDataIn"、"canBusAccess" */
        private String name;

        /** 特征类型：data_port / bus_access / event_port / event_data_port */
        private String kind;

        /** 方向：in / out / inout */
        private String direction;

        /** 数据类型（仅 data_port 类需要，bus_access 不需要） */
        private String data_type;
    }
}
