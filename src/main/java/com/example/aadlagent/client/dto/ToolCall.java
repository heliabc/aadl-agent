package com.example.aadlagent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * 工具调用 DTO
 * 对应 Ollama / OpenAI 格式中的 tool_calls 项
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolCall {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具调用 ID */
    private String id;

    /** 工具类型，目前固定为 "function" */
    private String type;

    /** 函数调用详情 */
    private FunctionCall function;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {
        /** 函数名称 */
        private String name;

        /**
         * 函数参数（JSON 字符串）
         * 兼容两种格式：
         * - OpenAI 标准格式：arguments 是 JSON 字符串
         * - 部分模型/Ollama 返回格式：arguments 是 JSON 对象
         */
        private String arguments;

        /**
         * 自定义 setter：同时兼容 String 和 Object（Map/JsonNode）两种格式
         */
        @JsonProperty("arguments")
        public void setArguments(Object argumentsObj) {
            if (argumentsObj == null) {
                this.arguments = "{}";
            } else if (argumentsObj instanceof String) {
                this.arguments = (String) argumentsObj;
            } else {
                try {
                    this.arguments = MAPPER.writeValueAsString(argumentsObj);
                } catch (JsonProcessingException e) {
                    this.arguments = "{}";
                }
            }
        }

        @Builder
        public FunctionCall(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }
}
