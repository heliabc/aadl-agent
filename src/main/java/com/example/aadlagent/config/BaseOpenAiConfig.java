package com.example.aadlagent.config;

import lombok.Data;

/**
 * OpenAI 兼容 API 的通用配置基类。
 * DeepSeek、豆包（Doubao）、ChatGPT 等兼容 OpenAI 格式的 API 均可继承此类。
 */
@Data
public class BaseOpenAiConfig {

    /** API Key，为空则该模型不可用 */
    private String apiKey = "";

    /** API 基础地址（不含 /chat/completions 后缀） */
    private String baseUrl = "";

    /** 对话模型名称 */
    private String chatModel = "";

    /** 请求超时时间（毫秒） */
    private int timeout = 600000;
}
