package com.example.aadlagent.memory.gate;

import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.memory.AgentContext;
import com.example.aadlagent.memory.QualityGate;
import com.example.aadlagent.memory.QualityGateResult;
import com.example.aadlagent.model.AadlArchitectureModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AadlArchitectureAgent 质量检查门
 *
 * 检查点：
 * 1. 输出是否能正确解析为架构模型
 * 2. 根节点名称是否存在
 * 3. 子组件数量是否合理
 * 4. 类型是否有效（system/process/thread 等）
 */
@Slf4j
@Component
public class ArchitectureQualityGate implements QualityGate {

    private static final String AGENT_NAME = "AadlArchitectureAgent";
    private static final int MIN_COMPONENTS = 2;
    private static final int MAX_COMPONENTS = 100;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getTargetAgentName() {
        return AGENT_NAME;
    }

    @Override
    public QualityGateResult check(AgentContext context, AgentOutput output) {
        QualityGateResult result = QualityGateResult.pass();

        if (output == null || !output.isSuccess()) {
            return QualityGateResult.retry("架构生成失败，输出为空或不成功")
                    .addFailedCheck("输出有效性");
        }

        String content = output.getContent();
        if (content == null || content.trim().isEmpty()) {
            return QualityGateResult.retry("架构生成输出为空")
                    .addFailedCheck("输出非空");
        }

        try {
            AadlArchitectureModel architecture = parseArchitecture(content);

            if (architecture == null) {
                return QualityGateResult.retry("架构模型解析结果为空")
                        .addFailedCheck("架构非空");
            }
            result.addPassedCheck("架构解析成功");

            // 根节点名称检查
            if (architecture.getName() == null || architecture.getName().trim().isEmpty()) {
                return QualityGateResult.retry("架构根节点名称为空")
                        .addFailedCheck("根节点名称");
            }
            result.addPassedCheck("根节点名称: " + architecture.getName());

            // 根节点类型检查
            if (architecture.getType() == null || architecture.getType().trim().isEmpty()) {
                return QualityGateResult.retry("架构根节点类型为空")
                        .addFailedCheck("根节点类型");
            }
            result.addPassedCheck("根节点类型: " + architecture.getType());

            // 组件数量统计
            int componentCount = countComponents(architecture);
            if (componentCount < MIN_COMPONENTS) {
                return QualityGateResult.retry("架构组件过少（" + componentCount + " 个），可能解析不完整")
                        .addFailedCheck("组件数量合理性");
            }
            if (componentCount > MAX_COMPONENTS) {
                return QualityGateResult.retry("架构组件过多（" + componentCount + " 个），可能存在异常")
                        .addFailedCheck("组件数量合理性");
            }
            result.addPassedCheck("组件数量合理（" + componentCount + " 个）");

            // 子组件信息（仅记录，不做强制检查——叶子节点子组件为空是正常的）
            int subCount = architecture.getSubcomponents() != null ? architecture.getSubcomponents().size() : 0;
            result.addPassedCheck("根节点子组件数: " + subCount);

        } catch (Exception e) {
            log.warn("解析架构输出失败: {}", e.getMessage());
            return QualityGateResult.retry("无法解析架构输出: " + e.getMessage())
                    .addFailedCheck("输出可解析性");
        }

        return result;
    }

    private AadlArchitectureModel parseArchitecture(String content) throws Exception {
        try {
            AadlArchitectureModel model = objectMapper.readValue(content, AadlArchitectureModel.class);
            if (model != null && model.getName() != null) {
                return model;
            }
            // 尝试提取 root 节点
            var node = objectMapper.readTree(content);
            if (node.has("root")) {
                return objectMapper.treeToValue(node.get("root"), AadlArchitectureModel.class);
            }
            return model;
        } catch (Exception e) {
            throw e;
        }
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
