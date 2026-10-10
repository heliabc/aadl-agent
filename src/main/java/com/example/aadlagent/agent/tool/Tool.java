package com.example.aadlagent.agent.tool;

import java.util.Map;

/**
 * 工具接口 — ReAct 模式中 LLM 可调用的工具
 *
 * 每个工具对应一个具体能力（如验证 AADL 代码、检索知识库等）。
 * 工具的名称、描述和参数 schema 会以 function calling 格式传给 LLM，
 * LLM 决定调用哪个工具并传入参数，工具执行后返回观察结果。
 */
public interface Tool {

    /**
     * 工具名称（唯一标识，LLM 通过此名称调用）
     */
    String getName();

    /**
     * 工具描述（告诉 LLM 这个工具的用途，越精准越好）
     */
    String getDescription();

    /**
     * 工具参数的 JSON Schema（符合 OpenAI/Ollama function calling 格式）
     * 格式示例：
     * {
     *   "type": "object",
     *   "properties": {
     *     "param1": {"type": "string", "description": "..."},
     *     "param2": {"type": "integer", "description": "..."}
     *   },
     *   "required": ["param1"]
     * }
     */
    Map<String, Object> getParameters();

    /**
     * 执行工具
     *
     * @param arguments 工具参数（LLM 传入的参数，已解析为 Map）
     * @return 工具执行结果（字符串形式的观察结果）
     */
    String execute(Map<String, Object> arguments);

    /**
     * 转换为 LLM function calling 格式的工具定义
     */
    default Map<String, Object> toToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", getName(),
                        "description", getDescription(),
                        "parameters", getParameters()
                )
        );
    }
}
