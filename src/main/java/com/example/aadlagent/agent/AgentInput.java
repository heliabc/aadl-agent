
package com.example.aadlagent.agent;

import com.example.aadlagent.client.ModelType;
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
