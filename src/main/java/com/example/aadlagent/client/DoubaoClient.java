package com.example.aadlagent.client;

import com.example.aadlagent.config.DoubaoConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 豆包（字节跳动火山引擎）API 客户端。
 * 豆包 API 兼容 OpenAI /chat/completions 格式。
 */
@Slf4j
@Component
public class DoubaoClient extends AbstractOpenAiCompatibleClient {

    public DoubaoClient(DoubaoConfig config) {
        super(config);
    }

    @Override
    protected String getClientName() {
        return "Doubao";
    }
}
