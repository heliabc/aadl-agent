
package com.example.aadlplugin.agent;

public interface Agent<I extends AgentInput, O extends AgentOutput> {

    O execute(I input);

    String getAgentName();
}
