package com.example.aadlplugin.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class AgentChain {

    private static final Logger log = Logger.getLogger(AgentChain.class.getName());

    private final List<Agent<? extends AgentInput, ? extends AgentOutput>> agents = new ArrayList<>();

    public void addAgent(Agent<? extends AgentInput, ? extends AgentOutput> agent) {
        agents.add(agent);
        log.info("Added agent: " + agent.getAgentName());
    }

    public AgentOutput executeChain(String initialContent) {
        String sessionId = UUID.randomUUID().toString();
        long totalStartTime = System.currentTimeMillis();

        log.info("Starting agent chain execution with sessionId: " + sessionId);

        String currentContent = initialContent;
        AgentOutput finalOutput = null;

        for (int i = 0; i < agents.size(); i++) {
            Agent<? extends AgentInput, ? extends AgentOutput> agent = agents.get(i);
            log.info("Executing agent " + (i + 1) + "/" + agents.size() + ": " + agent.getAgentName());

            try {
                AgentInput input = AgentInput.builder()
                        .sessionId(sessionId)
                        .content(currentContent)
                        .metadata("{\"agentIndex\": " + i + ", \"agentName\": \"" + agent.getAgentName() + "\"}")
                        .build();

                @SuppressWarnings("unchecked")
                AgentOutput output = ((Agent<AgentInput, AgentOutput>) agent).execute(input);

                if (!output.isSuccess()) {
                    log.severe("Agent " + agent.getAgentName() + " failed: " + output.getErrorMessage());
                    return AgentOutput.failure(sessionId, "Agent " + agent.getAgentName() + " failed: " + output.getErrorMessage());
                }

                currentContent = output.getContent();
                finalOutput = output;

                log.info("Agent " + agent.getAgentName() + " completed successfully in " + output.getExecutionTime() + "ms");

            } catch (Exception e) {
                log.severe("Agent " + agent.getAgentName() + " threw exception: " + e.getMessage());
                e.printStackTrace();
                return AgentOutput.failure(sessionId, "Agent " + agent.getAgentName() + " threw exception: " + e.getMessage());
            }
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;
        log.info("Agent chain completed in " + totalTime + "ms");

        if (finalOutput != null) {
            return AgentOutput.success(sessionId, finalOutput.getContent(), totalTime);
        }

        return AgentOutput.failure(sessionId, "No agents executed");
    }

    public int getAgentCount() {
        return agents.size();
    }
}