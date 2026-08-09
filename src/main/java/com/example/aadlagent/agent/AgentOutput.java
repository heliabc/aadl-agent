
package com.example.aadlagent.agent;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentOutput {

    private String sessionId;

    private String content;

    /** 原始内容（自动修复前的版本，用于对比） */
    private String originalContent;

    private boolean success;

    private String errorMessage;

    private long executionTime;

    private boolean cancelled;

    public static AgentOutput success(String sessionId, String content, long executionTime) {
        return AgentOutput.builder()
                .sessionId(sessionId)
                .content(content)
                .success(true)
                .executionTime(executionTime)
                .build();
    }

    public static AgentOutput failure(String sessionId, String errorMessage) {
        return AgentOutput.builder()
                .sessionId(sessionId)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    public static AgentOutput cancelled(String sessionId) {
        return AgentOutput.builder()
                .sessionId(sessionId)
                .success(false)
                .errorMessage("任务已被取消")
                .cancelled(true)
                .build();
    }
}
