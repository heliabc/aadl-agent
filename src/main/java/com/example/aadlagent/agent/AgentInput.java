
package com.example.aadlagent.agent;

import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.memory.AgentContext;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.concurrent.atomic.AtomicBoolean;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentInput {

    private String sessionId;

    private String content;

    private String metadata;

    private String ragContext;

    private ModelType modelType;

    private boolean minimalPrompt;  // 消融实验用：是否使用极简 prompt

    /**
     * 工作记忆上下文（结构化中间结果）
     * 各 Agent 可以从中读取上游的结构化数据，避免重复解析
     */
    private AgentContext context;

    private transient AtomicBoolean cancelled;

    public boolean isCancelled() {
        return cancelled != null && cancelled.get();
    }

    public void setCancelled(boolean cancelled) {
        if (this.cancelled == null) {
            this.cancelled = new AtomicBoolean();
        }
        this.cancelled.set(cancelled);
    }
}
