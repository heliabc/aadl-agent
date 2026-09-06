package com.example.aadlagent.memory;

import com.example.aadlagent.agent.AgentOutput;

/**
 * 质量检查门（Quality Gate）接口
 *
 * 每个 Agent 执行完后，通过对应的 QualityGate 检查输出质量。
 * 根据检查结果决定：继续执行、重试当前、回退上一步、或终止失败。
 *
 * @param <I> 输入类型（AgentContext）
 * @param <O> 输出类型（AgentOutput）
 */
public interface QualityGate {

    /**
     * 获取该质量门对应的 Agent 名称
     */
    String getTargetAgentName();

    /**
     * 执行质量检查
     *
     * @param context 工作记忆上下文（执行前的状态）
     * @param output  当前 Agent 的输出
     * @return 质量检查结果（PASS / RETRY / ROLLBACK / FAIL）
     */
    QualityGateResult check(AgentContext context, AgentOutput output);
}
