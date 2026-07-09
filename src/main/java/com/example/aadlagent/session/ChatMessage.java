package com.example.aadlagent.session;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    private String role;

    private String content;

    private String agentType;

    private LocalDateTime timestamp;

    public static ChatMessage user(String content) {
        return ChatMessage.builder()
                .role("user")
                .content(content)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ChatMessage assistant(String content, String agentType) {
        return ChatMessage.builder()
                .role("assistant")
                .content(content)
                .agentType(agentType)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ChatMessage system(String content) {
        return ChatMessage.builder()
                .role("system")
                .content(content)
                .timestamp(LocalDateTime.now())
                .build();
    }
}