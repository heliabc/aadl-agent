package com.example.aadlagent.client;

import com.example.aadlagent.client.dto.ChatMessage;
import com.example.aadlagent.client.dto.ChatResponse;

import java.util.List;
import java.util.Map;

public interface LlmClient {

    String chat(String prompt, Double temperature, Integer maxTokens);

    /**
     * 使用指定模型名称进行对话
     * @param modelName 模型名称，为null或空时使用默认模型
     */
    String chat(String prompt, Double temperature, Integer maxTokens, String modelName);

    /**
     * 多轮对话 + 工具调用（ReAct 模式核心方法）
     *
     * @param messages      对话历史消息列表（包含 system/user/assistant/tool 角色）
     * @param tools         可用工具定义列表，每个工具为 Map（符合 OpenAI/Ollama function calling 格式）
     *                      格式示例：
     *                      {
     *                        "type": "function",
     *                        "function": {
     *                          "name": "tool_name",
     *                          "description": "tool description",
     *                          "parameters": { ... }
     *                        }
     *                      }
     * @param temperature   温度
     * @param maxTokens     最大 token 数
     * @param modelName     模型名称，为null或空时使用默认模型
     * @return ChatResponse  包含模型回复，可能有 tool_calls 或直接 content
     */
    ChatResponse chatWithTools(List<ChatMessage> messages, List<Map<String, Object>> tools,
                               Double temperature, Integer maxTokens, String modelName);

    float[] embed(String text);

    boolean isAvailable();

    String getModelName();

    /**
     * 检查指定模型是否可用
     */
    boolean checkModel(String modelName);

    /**
     * 测试API连接是否真正可用（发送一个简单的测试请求）
     */
    default boolean testConnection() {
        return isAvailable();
    }

    /**
     * 是否支持工具调用（function calling）
     * 默认支持，各客户端可根据自身能力覆写
     */
    default boolean supportsToolCalling() {
        return true;
    }
}
