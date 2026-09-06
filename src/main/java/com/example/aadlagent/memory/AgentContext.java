package com.example.aadlagent.memory;

import com.example.aadlagent.model.AadlArchitectureModel;
import com.example.aadlagent.model.ModuleAnalysisResult;
import com.example.aadlagent.model.Requirement;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作记忆（Working Memory）
 *
 * 在 AgentChain 执行过程中流动的结构化上下文，
 * 避免各 Agent 之间通过字符串序列化/反序列化传递数据，
 * 减少解析错误，提升效率。
 *
 * 生命周期：随任务创建，任务结束后释放。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentContext {

    /** 会话ID */
    private String sessionId;

    /** 原始需求文档 */
    private String rawRequirementDoc;

    /** 需求分析结果（需求列表） */
    private List<Requirement> requirements;

    /** 架构树 */
    private AadlArchitectureModel architecture;

    /** 模块分析结果 */
    private ModuleAnalysisResult moduleAnalysis;

    /** 生成的 AADL 代码 */
    private String aadlCode;

    /** 修复前的原始 AADL 代码（用于对比） */
    private String originalAadlCode;

    /** 当前执行到第几个 Agent */
    private int currentAgentIndex;

    /** 当前 Agent 名称 */
    private String currentAgentName;

    /** 重试次数（当前 Agent） */
    private int retryCount;

    /** 上一次质量检查失败的原因（重试时传给 Agent，帮助模型知道哪里出了问题） */
    private String lastQualityIssue;

    /** 上一次质量检查失败的具体项列表 */
    @Builder.Default
    private List<String> lastQualityFailedChecks = new ArrayList<>();

    /** 扩展字段，用于各 Agent 存放自定义数据 */
    @Builder.Default
    private Map<String, Object> extra = new HashMap<>();

    /**
     * 获取扩展字段的值（带类型转换）
     */
    @SuppressWarnings("unchecked")
    public <T> T getExtra(String key, Class<T> type) {
        Object val = extra.get(key);
        if (val == null) {
            return null;
        }
        if (type.isInstance(val)) {
            return (T) val;
        }
        return null;
    }

    /**
     * 设置扩展字段
     */
    public void putExtra(String key, Object value) {
        if (extra == null) {
            extra = new HashMap<>();
        }
        extra.put(key, value);
    }

    /**
     * 需求数量
     */
    public int getRequirementCount() {
        return requirements != null ? requirements.size() : 0;
    }

    /**
     * 架构组件数量（递归统计）
     */
    public int getArchitectureComponentCount() {
        if (architecture == null) return 0;
        return countComponents(architecture);
    }

    private int countComponents(AadlArchitectureModel node) {
        if (node == null) return 0;
        int count = 1;
        if (node.getSubcomponents() != null) {
            for (AadlArchitectureModel child : node.getSubcomponents()) {
                count += countComponents(child);
            }
        }
        return count;
    }
}
