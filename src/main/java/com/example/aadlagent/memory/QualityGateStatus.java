package com.example.aadlagent.memory;

/**
 * 质量检查结果
 *
 * 用于 AgentChain 中的质量门（Quality Gate）判断：
 * - PASS：通过，继续执行下一个 Agent
 * - RETRY：不通过，重试当前 Agent
 * - ROLLBACK：不通过，回退到上一个 Agent 重做
 * - FAIL：严重失败，终止整个链条
 */
public enum QualityGateStatus {
    /** 通过，继续执行 */
    PASS,
    /** 重试当前 Agent */
    RETRY,
    /** 回退到上一个 Agent */
    ROLLBACK,
    /** 彻底失败，终止链条 */
    FAIL
}
