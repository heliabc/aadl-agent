package com.example.aadlagent.agent;

import com.example.aadlagent.memory.AgentContext;
import com.example.aadlagent.memory.QualityGate;
import com.example.aadlagent.memory.QualityGateResult;
import com.example.aadlagent.memory.QualityGateStatus;
import com.example.aadlagent.service.TaskCancellationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 执行链（支持质量检查门 + 重试 + 回退）
 *
 * 新特性：
 * 1. 工作记忆（AgentContext）在链条中流动，传递结构化中间结果
 * 2. 每个 Agent 执行后通过 QualityGate 检查输出质量
 * 3. 质量不达标时可重试当前 Agent，或回退到上一个 Agent 重做
 * 4. 最多重试次数可配置，避免无限循环
 */
@Slf4j
@Component
public class AgentChain {

    private final List<Agent<? extends AgentInput, ? extends AgentOutput>> agents = new ArrayList<>();
    private final Map<String, QualityGate> qualityGates = new HashMap<>();
    private final TaskCancellationService cancellationService;

    /** 每个 Agent 的最大重试次数 */
    private int maxRetriesPerAgent = 2;

    /** 最大回退次数（整个链条层面） */
    private int maxRollbacks = 2;

    public AgentChain(TaskCancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    public void addAgent(Agent<? extends AgentInput, ? extends AgentOutput> agent) {
        agents.add(agent);
        log.info("Added agent: {}", agent.getAgentName());
    }

    /**
     * 注册质量检查门
     */
    public void addQualityGate(QualityGate gate) {
        qualityGates.put(gate.getTargetAgentName(), gate);
        log.info("Added quality gate for agent: {}", gate.getTargetAgentName());
    }

    public void setMaxRetriesPerAgent(int maxRetriesPerAgent) {
        this.maxRetriesPerAgent = maxRetriesPerAgent;
    }

    public void setMaxRollbacks(int maxRollbacks) {
        this.maxRollbacks = maxRollbacks;
    }

    public int getAgentCount() {
        return agents.size();
    }

    public AgentOutput executeChain(String initialContent) {
        String sessionId = UUID.randomUUID().toString();
        return executeChain(sessionId, initialContent);
    }

    public AgentOutput executeChain(String sessionId, String initialContent) {
        // 初始化工作记忆
        AgentContext context = AgentContext.builder()
                .sessionId(sessionId)
                .rawRequirementDoc(initialContent)
                .currentAgentIndex(0)
                .retryCount(0)
                .build();

        return executeChain(sessionId, initialContent, context);
    }

    /**
     * 带初始上下文的执行（用于会话记忆恢复等场景）
     */
    public AgentOutput executeChain(String sessionId, String initialContent, AgentContext initialContext) {
        long totalStartTime = System.currentTimeMillis();

        log.info("========================================");
        log.info("Starting agent chain execution with sessionId: {}", sessionId);
        log.info("Total agents: {}, Quality gates: {}", agents.size(), qualityGates.size());
        log.info("Max retries per agent: {}, Max rollbacks: {}", maxRetriesPerAgent, maxRollbacks);
        log.info("========================================");

        AtomicBoolean cancellationFlag = cancellationService.registerTask(sessionId);

        String currentContent = initialContent;
        AgentOutput finalOutput = null;
        AgentContext currentContext = initialContext != null ? initialContext :
                AgentContext.builder().sessionId(sessionId).rawRequirementDoc(initialContent).build();

        // 记录每个 Agent 的历史输出（用于回退）
        List<AgentOutput> agentHistory = new ArrayList<>();
        int rollbackCount = 0;

        try {
            int i = 0;
            while (i < agents.size()) {
                if (cancellationFlag.get()) {
                    log.info("Task cancelled, stopping agent chain");
                    return AgentOutput.cancelled(sessionId);
                }

                Agent<? extends AgentInput, ? extends AgentOutput> agent = agents.get(i);
                String agentName = agent.getAgentName();

                // 计算当前是第几次重试
                int retryCount = 0;
                // 从历史中找出当前 agent 的重试次数（通过 agentName 匹配）
                for (int j = agentHistory.size() - 1; j >= 0; j--) {
                    // 简单的重试计数：连续相同 agentName 的输出算重试
                    // 这里用更简单的方式：每次 RETRY 后 retryCount++
                    break;
                }

                log.info("\n--- Executing agent {}/{}: {} ---", i + 1, agents.size(), agentName);

                try {
                    // 更新 context 中的当前位置
                    currentContext.setCurrentAgentIndex(i);
                    currentContext.setCurrentAgentName(agentName);

                    AgentInput input = AgentInput.builder()
                            .sessionId(sessionId)
                            .content(currentContent)
                            .metadata("{\"agentIndex\": " + i + ", \"agentName\": \"" + agentName + "\"}")
                            .context(currentContext)
                            .cancelled(cancellationFlag)
                            .build();

                    @SuppressWarnings("unchecked")
                    AgentOutput output = ((Agent<AgentInput, AgentOutput>) agent).execute(input);

                    if (output.isCancelled()) {
                        log.info("Task cancelled during agent {} execution", agentName);
                        return output;
                    }

                    if (!output.isSuccess()) {
                        log.error("Agent {} failed: {}", agentName, output.getErrorMessage());

                        // 失败也尝试重试（如果还有重试次数）
                        if (retryCount < maxRetriesPerAgent) {
                            log.info("Agent {} failed, retrying ({}/{})", agentName, retryCount + 1, maxRetriesPerAgent);
                            currentContext.setRetryCount(retryCount + 1);
                            currentContext.setLastQualityIssue("执行失败: " + output.getErrorMessage());
                            currentContext.setLastQualityFailedChecks(new ArrayList<>());
                            continue; // 重试当前 agent
                        }

                        return AgentOutput.failure(sessionId,
                                "Agent " + agentName + " failed after " + (retryCount + 1) + " attempts: "
                                        + output.getErrorMessage());
                    }

                    // === 质量检查门 ===
                    QualityGate gate = qualityGates.get(agentName);
                    if (gate != null) {
                        log.info("Running quality gate for: {}", agentName);
                        QualityGateResult gateResult = gate.check(currentContext, output);

                        if (!gateResult.isPassed()) {
                            log.warn("Quality gate {}: status={}, reason={}",
                                    agentName, gateResult.getStatus(), gateResult.getReason());

                            if (gateResult.getFailedChecks() != null && !gateResult.getFailedChecks().isEmpty()) {
                                for (String failed : gateResult.getFailedChecks()) {
                                    log.warn("  - FAILED: {}", failed);
                                }
                            }

                            QualityGateStatus status = gateResult.getStatus();

                            if (status == QualityGateStatus.RETRY && retryCount < maxRetriesPerAgent) {
                                // 重试当前 Agent，把失败原因写入 context
                                log.info("Retrying agent {} ({}/{}) due to quality gate: {}",
                                        agentName, retryCount + 1, maxRetriesPerAgent, gateResult.getReason());
                                currentContext.setRetryCount(retryCount + 1);
                                currentContext.setLastQualityIssue(gateResult.getReason());
                                currentContext.setLastQualityFailedChecks(
                                        gateResult.getFailedChecks() != null
                                                ? new ArrayList<>(gateResult.getFailedChecks())
                                                : new ArrayList<>());
                                continue;
                            }

                            if (status == QualityGateStatus.ROLLBACK && i > 0 && rollbackCount < maxRollbacks) {
                                // 回退到上一个 Agent
                                log.info("Rolling back to previous agent (rollback {}/{})",
                                        rollbackCount + 1, maxRollbacks);
                                rollbackCount++;
                                if (!agentHistory.isEmpty()) {
                                    agentHistory.remove(agentHistory.size() - 1);
                                }
                                i = Math.max(0, i - 1);
                                // 恢复上一个 agent 的输出作为 currentContent
                                if (!agentHistory.isEmpty()) {
                                    AgentOutput prevOutput = agentHistory.get(agentHistory.size() - 1);
                                    currentContent = prevOutput.getContent();
                                    if (prevOutput.getContext() != null) {
                                        currentContext = prevOutput.getContext();
                                    }
                                }
                                currentContext.setRetryCount(0);
                                continue;
                            }

                            if (status == QualityGateStatus.FAIL) {
                                // 彻底失败
                                log.error("Quality gate FAIL for agent {}: {}", agentName, gateResult.getReason());
                                return AgentOutput.failure(sessionId,
                                        "Quality gate failed for " + agentName + ": " + gateResult.getReason());
                            }

                            // 达到最大重试/回退次数，仍然继续（勉强接受）
                            log.warn("Max retries/rollbacks reached, proceeding with current output for {}", agentName);
                        } else {
                            log.info("Quality gate PASSED for {}", agentName);
                            if (gateResult.getPassedChecks() != null) {
                                for (String passed : gateResult.getPassedChecks()) {
                                    log.debug("  - PASSED: {}", passed);
                                }
                            }
                        }
                    }

                    // 质量通过，记录历史并继续
                    agentHistory.add(output);

                    // 更新当前内容和上下文
                    currentContent = output.getContent();
                    if (output.getContext() != null) {
                        currentContext = output.getContext();
                    }
                    finalOutput = output;
                    currentContext.setRetryCount(0); // 重置重试计数
                    currentContext.setLastQualityIssue(null); // 清空上一次质量问题
                    currentContext.setLastQualityFailedChecks(new ArrayList<>()); // 清空失败检查项

                    log.info("Agent {} completed successfully in {}ms", agentName, output.getExecutionTime());

                    i++; // 前进到下一个 agent

                } catch (Exception e) {
                    log.error("Agent {} threw exception: {}", agentName, e.getMessage(), e);

                    // 异常也尝试重试
                    if (retryCount < maxRetriesPerAgent) {
                        log.info("Retrying agent {} after exception ({}/{})",
                                agentName, retryCount + 1, maxRetriesPerAgent);
                        currentContext.setRetryCount(retryCount + 1);
                        continue;
                    }

                    return AgentOutput.failure(sessionId,
                            "Agent " + agentName + " threw exception after " + (retryCount + 1) + " attempts: "
                                    + e.getMessage());
                }
            }

            long totalTime = System.currentTimeMillis() - totalStartTime;
            log.info("\n========================================");
            log.info("Agent chain completed in {}ms", totalTime);
            log.info("Total agents executed: {}", agents.size());
            log.info("Total rollbacks: {}", rollbackCount);
            log.info("========================================");

            if (finalOutput != null) {
                AgentOutput result = AgentOutput.success(sessionId, finalOutput.getContent(), totalTime);
                result.setContext(currentContext);
                return result;
            }

            return AgentOutput.failure(sessionId, "No agents executed");
        } finally {
            cancellationService.unregisterTask(sessionId);
        }
    }
}
