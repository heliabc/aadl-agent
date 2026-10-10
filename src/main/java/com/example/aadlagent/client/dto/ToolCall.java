package com.example.aadlagent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    /** 工具调用 ID */
    private String id;

    /** 工具类型，目前固定为 "function" */
    private String type;

    /** 函数调用详情 */
    private FunctionCall function;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {
        /** 函数名称 */
        private String name;

        /** 函数参数（JSON 字符串） */
        private String arguments;
    }
}
