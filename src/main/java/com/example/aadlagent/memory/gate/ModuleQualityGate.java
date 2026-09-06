package com.example.aadlagent.memory.gate;

import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.memory.AgentContext;
import com.example.aadlagent.memory.QualityGate;
import com.example.aadlagent.memory.QualityGateResult;
import com.example.aadlagent.model.ModuleAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ModuleAnalysisAgent 质量检查门
 *
 * 检查点：
 * 1. 输出是否能正确解析
 * 2. 模块数量是否合理
 * 3. 模块是否有名称和层次结构
 */
@Slf4j
@Component
public class ModuleQualityGate implements QualityGate {

    private static final String AGENT_NAME = "ModuleAnalysisAgent";
    private static final int MIN_MODULES = 2;
    private static final int MAX_MODULES = 100;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getTargetAgentName() {
        return AGENT_NAME;
    }

    @Override
    public QualityGateResult check(AgentContext context, AgentOutput output) {
        QualityGateResult result = QualityGateResult.pass();

        if (output == null || !output.isSuccess()) {
            return QualityGateResult.retry("模块分析失败，输出为空或不成功")
                    .addFailedCheck("输出有效性");
        }

        String content = output.getContent();
        if (content == null || content.trim().isEmpty()) {
            return QualityGateResult.retry("模块分析输出为空")
                    .addFailedCheck("输出非空");
        }

        try {
            ModuleAnalysisResult analysis = objectMapper.readValue(content, ModuleAnalysisResult.class);

            if (analysis == null || analysis.getModules() == null || analysis.getModules().isEmpty()) {
                return QualityGateResult.retry("模块分析结果为空")
                        .addFailedCheck("模块列表非空");
            }
            result.addPassedCheck("模块解析成功");

            int moduleCount = analysis.getModules().size();
            if (moduleCount < MIN_MODULES) {
                return QualityGateResult.retry("模块数量过少（" + moduleCount + " 个），可能解析不完整")
                        .addFailedCheck("模块数量合理性");
            }
            if (moduleCount > MAX_MODULES) {
                return QualityGateResult.retry("模块数量过多（" + moduleCount + " 个），可能存在异常")
                        .addFailedCheck("模块数量合理性");
            }
            result.addPassedCheck("模块数量合理（" + moduleCount + " 个）");

            // 检查模块名称有效性
            int validModules = 0;
            for (var module : analysis.getModules()) {
                if (module.getModuleName() != null && !module.getModuleName().trim().isEmpty()) {
                    validModules++;
                }
            }
            if (validModules < moduleCount * 0.7) {
                return QualityGateResult.retry("模块名称有效率过低")
                        .addFailedCheck("模块名称有效性");
            }
            result.addPassedCheck("模块名称有效率 " + String.format("%.0f%%", (double) validModules / moduleCount * 100));

        } catch (Exception e) {
            log.warn("解析模块分析输出失败: {}", e.getMessage());
            return QualityGateResult.retry("无法解析模块分析输出: " + e.getMessage())
                    .addFailedCheck("输出可解析性");
        }

        return result;
    }
}
