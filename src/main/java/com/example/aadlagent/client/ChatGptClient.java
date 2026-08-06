package com.example.aadlagent.client;

import com.example.aadlagent.config.ChatGptConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OpenAI ChatGPT API 客户端。
 */
@Slf4j
@Component
public class ChatGptClient extends AbstractOpenAiCompatibleClient {

    public ChatGptClient(ChatGptConfig config) {
        super(config);
    }

    @Override
    protected String getClientName() {
        return "ChatGPT";
    }
}
