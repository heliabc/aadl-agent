package com.example.aadlagent.memory;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 决策记录（会话记忆的重要节点）
 *
 * 记录会话中的关键决策和修正，用于：
 * 1. 追踪任务进展
 * 2. 回溯决策原因
 * 3. 生成会话摘要时提取关键信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DecisionRecord {

    /** 决策唯一ID */
    private String decisionId;

    /** 决策类型 */
    private DecisionType type;

    /** 决策描述（一句话概括） */
    private String description;

    /** 决策原因 / 依据 */
    private String reason;

    /** 相关的 Agent 名称 */
    private String agentName;

    /** 决策时间 */
    private LocalDateTime timestamp;

    /** 影响范围（如：修改了哪些组件、修复了哪些错误） */
    private String impact;

    /**
     * 决策类型枚举
     */
    public enum DecisionType {
        /** 用户输入了新需求 */
        USER_INPUT,
        /** 需求分析完成 */
        REQUIREMENT_ANALYZED,
        /** 架构设计完成 */
        ARCHITECTURE_DESIGNED,
        /** 模块分析完成 */
        MODULES_ANALYZED,
        /** AADL 生成完成 */
        AADL_GENERATED,
        /** 修复了错误 */
        ERROR_FIXED,
        /** 用户确认了某个结果 */
        USER_CONFIRMED,
        /** 用户修改了某个部分 */
        USER_MODIFIED,
        /** 质量检查不通过，触发重试 */
        QUALITY_RETRY,
        /** 质量检查不通过，触发回退 */
        QUALITY_ROLLBACK,
        /** 其他重要决策 */
        OTHER
    }
}
