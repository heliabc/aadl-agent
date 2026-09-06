
package com.example.aadlagent.agent;

import com.example.aadlagent.memory.AgentContext;
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

    /**
     * 更新后的工作记忆上下文
     * Agent 执行完成后，可以将自己产出的结构化数据写回 context
     */
    private AgentContext context;

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
