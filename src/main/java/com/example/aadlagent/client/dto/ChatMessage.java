package com.example.aadlagent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessage {

    private String role;

    private String content;

    /**
     * 工具调用列表（模型返回时使用）
     * 仅当 role="assistant" 且模型决定调用工具时才有值
     */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    /**
     * 工具调用 ID（tool role 消息使用）
     * 用于关联工具调用结果与对应的 tool_call
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;
}
