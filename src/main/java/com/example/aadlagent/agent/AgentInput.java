
package com.example.aadlagent.agent;

import com.example.aadlagent.client.ModelType;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

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
}
