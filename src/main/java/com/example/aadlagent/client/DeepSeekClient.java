package com.example.aadlagent.client;

import com.example.aadlagent.config.DeepSeekConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeepSeekClient extends AbstractOpenAiCompatibleClient {

    public DeepSeekClient(DeepSeekConfig config) {
        super(config);
    }

    @Override
    protected String getClientName() {
        return "DeepSeek";
    }
}
