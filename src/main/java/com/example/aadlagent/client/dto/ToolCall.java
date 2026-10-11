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
 * 
 * arguments 字段使用 Object 类型，兼容两种格式：
 * - 反序列化（解析响应）：String 或对象都能接收
 * - 序列化（发送请求）：按原始格式输出，保证和模型返回格式一致
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
         * 函数参数，Object 类型以兼容多种格式：
         * - String：OpenAI 标准格式（JSON 字符串）
         * - Map / JsonNode：对象格式（Ollama/qwen3 等部分模型返回）
         * 
         * 序列化时保持原始格式输出，确保回传给模型时格式一致
         */
        private Object arguments;

        /**
         * 获取 arguments 的 JSON 字符串形式（用于业务代码解析参数）
         */
        public String getArgumentsAsString() {
            if (arguments == null) {
                return "{}";
            }
            if (arguments instanceof String) {
                return (String) arguments;
            }
            try {
                return MAPPER.writeValueAsString(arguments);
            } catch (JsonProcessingException e) {
                return "{}";
            }
        }

        @Builder
        public FunctionCall(String name, Object arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }
}
