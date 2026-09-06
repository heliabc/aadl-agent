package com.example.aadlagent.memory.gate;

import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.memory.AgentContext;
import com.example.aadlagent.memory.QualityGate;
import com.example.aadlagent.memory.QualityGateResult;
import com.example.aadlagent.model.Requirement;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RequirementAgent 质量检查门
 *
 * 检查点：
 * 1. 输出是否为空
 * 2. 能否正确解析为需求列表
 * 3. 需求数量是否合理（太少可能失败，太多可能噪声）
 */
@Slf4j
@Component
public class RequirementQualityGate implements QualityGate {

    private static final String AGENT_NAME = "RequirementAgent";
    private static final int MIN_REQUIREMENTS = 2;
    private static final int MAX_REQUIREMENTS = 200;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getTargetAgentName() {
        return AGENT_NAME;
    }

    @Override
    public QualityGateResult check(AgentContext context, AgentOutput output) {
        QualityGateResult result = QualityGateResult.pass();

        if (output == null || !output.isSuccess()) {
            return QualityGateResult.retry("需求分析失败，输出为空或不成功")
                    .addFailedCheck("输出有效性");
        }

        String content = output.getContent();
        if (content == null || content.trim().isEmpty()) {
            return QualityGateResult.retry("需求分析输出为空")
                    .addFailedCheck("输出非空");
        }

        // 尝试解析需求列表
        try {
            // 兼容两种格式：直接的 List<Requirement>，或 RequirementAnalysisResult 包装
            List<Requirement> requirements = tryParseRequirements(content);

            if (requirements == null || requirements.isEmpty()) {
                return QualityGateResult.retry("解析出的需求列表为空")
                        .addFailedCheck("需求列表非空");
            }

            int count = requirements.size();
            result.addPassedCheck("需求解析成功，共 " + count + " 条");

            // 数量合理性检查
            if (count < MIN_REQUIREMENTS) {
                return QualityGateResult.retry("需求数量过少（" + count + " 条），可能解析不完整")
                        .addFailedCheck("需求数量合理性");
            }
            result.addPassedCheck("需求数量合理（" + count + " 条）");

            // 检查需求质量：是否有标题和描述
            int validCount = 0;
            for (Requirement req : requirements) {
                if (req.getTitle() != null && !req.getTitle().trim().isEmpty()
                        && req.getDescription() != null && !req.getDescription().trim().isEmpty()) {
                    validCount++;
                }
            }
            double validRatio = (double) validCount / count;
            if (validRatio < 0.5) {
                return QualityGateResult.retry("需求质量过低，有效需求占比仅 " + String.format("%.0f%%", validRatio * 100))
                        .addFailedCheck("需求质量");
            }
            result.addPassedCheck("需求质量合格（有效率 " + String.format("%.0f%%", validRatio * 100) + "）");

        } catch (Exception e) {
            log.warn("解析需求输出失败: {}", e.getMessage());
            return QualityGateResult.retry("无法解析需求输出: " + e.getMessage())
                    .addFailedCheck("输出可解析性");
        }

        return result;
    }

    /**
     * 尝试从 JSON 中解析需求列表
     */
    private List<Requirement> tryParseRequirements(String content) throws Exception {
        // 尝试直接解析为列表
        try {
            return objectMapper.readValue(content, new TypeReference<List<Requirement>>() {});
        } catch (Exception e) {
            // 尝试从 RequirementAnalysisResult 中提取
            try {
                var node = objectMapper.readTree(content);
                if (node.has("stage3")) {
                    var stage3 = node.get("stage3");
                    if (stage3.has("mergedRequirements")) {
                        return objectMapper.readValue(
                                stage3.get("mergedRequirements").toString(),
                                new TypeReference<List<Requirement>>() {});
                    }
                }
            } catch (Exception ex) {
                // 继续尝试其他方式
            }
        }
        throw new IllegalArgumentException("无法解析需求格式");
    }
}
