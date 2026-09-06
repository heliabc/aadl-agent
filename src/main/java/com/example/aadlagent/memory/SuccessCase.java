package com.example.aadlagent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 成功案例（长期记忆）
 *
 * 记录每次成功生成的完整案例，包括：
 * - 输入需求
 * - 架构设计
 * - 最终 AADL 代码
 * - 领域标签
 *
 * 用于：
 * 1. 新需求进来时，召回相似领域的历史案例作为参考
 * 2. 积累领域知识，提升生成质量
 * 3. 作为 SFT 微调的数据来源
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuccessCase {

    /** 案例唯一ID */
    private String caseId;

    /** 案例标题（一句话概括） */
    private String title;

    /** 原始需求文档 */
    private String requirementDoc;

    /** 需求摘要（前200字，用于快速浏览和检索） */
    private String requirementSummary;

    /** 最终的 AADL 代码 */
    private String aadlCode;

    /** AADL 代码长度 */
    private int aadlCodeLength;

    /** 组件数量 */
    private int componentCount;

    /** 连接数量 */
    private int connectionCount;

    /** 领域标签（如：航空航天、汽车电子、工业控制、医疗设备等） */
    private List<String> domainTags;

    /** 技术标签（如：实时系统、分布式、安全关键、容错等） */
    private List<String> techTags;

    /** 质量评分（0-100，基于验证错误数量、人工反馈等） */
    private int qualityScore;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 向量嵌入（用于相似度检索） */
    private float[] embedding;

    /** 备注/说明 */
    private String notes;
}
