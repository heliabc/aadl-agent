package com.example.aadlagent.agent.module;

import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.agent.ReActAgent;
import com.example.aadlagent.agent.tool.SearchKnowledgeTool;
import com.example.aadlagent.agent.tool.Tool;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.model.ModuleAnalysisResult;
import com.example.aadlagent.util.JsonRepairUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class ModuleAnalysisAgent extends ReActAgent {

    private static final String AGENT_NAME = "ModuleAnalysisAgent";

    private final ModuleAnalysisPrompt prompt;
    private final SearchKnowledgeTool searchKnowledgeTool;

    @Value("${agent.module.temperature:0.1}")
    private double temperature;

    @Value("${agent.module.max-tokens:8192}")
    private int maxTokens;

    public ModuleAnalysisAgent(ModelService modelService,
                               SearchKnowledgeTool searchKnowledgeTool) {
        super(modelService);
        this.objectMapper = JsonMapper.builder()
                .disable(SerializationFeature.WRITE_NULL_MAP_VALUES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonParser.Feature.ALLOW_COMMENTS)
                .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTE)
                .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
                .build();
        this.prompt = new ModuleAnalysisPrompt();
        this.searchKnowledgeTool = searchKnowledgeTool;
    }

    @Override
    protected String getSystemPrompt() {
        return """
你是一名专业的嵌入式系统架构分析师（模块分析专家），擅长根据需求文档和 AADL 架构模型分析功能模块，并为下游 AADL 代码生成提供端口声明和连接关系的精确约束。
你的核心职责是：识别功能模块，分析其组件层次和子组件，并基于需求中的数据流向推断每个模块需要的 AADL 端口（in/out data port）和模块间的连接关系。

## 工作方式（ReAct 模式）

1. **Thought（思考）**：分析需求列表和 AADL 架构树，确定系统中的功能模块划分、组件层次、端口需求和连接关系
2. **Action（行动）**：如果不确定 AADL 模块设计规则、组件类型定义或需要参考示例，调用 search_knowledge 工具查询
3. **Observation（观察）**：查看工具返回的知识
4. 重复以上步骤，直到你有足够信息完成模块分析
5. **Final Answer**：输出完整的模块分析结果 JSON

## 可用工具

- **search_knowledge**: 检索 AADL 领域知识，包括模块设计规则、组件类型定义、端口声明规范、连接关系示例等。不确定 AADL 模块分析规则时调用。

## AADL 组件类型要点

架构树中所有节点的 type 只能是以下 14 种 AADL 组件之一：
- 软件层：data, subprogram, subprogram group, thread, thread group, process
- 硬件层：processor, virtual processor, memory, device, bus, virtual bus
- 复合与抽象：system, abstract

**模块分析核心对象是 thread 组件**——每个 thread 对应一个功能模块。
data 组件是纯类型分类器，没有端口，不能作为功能模块。
device 是软硬件交汇点，不是软件功能模块，但是 thread 模块的重要关联组件。

## 输出要求

输出必须是一个合法的 JSON 对象，包含一个 "modules" 数组。每个模块对象必须包含以下字段：
- module_name: 模块名称
- satisfied_requirements: 该模块满足的需求ID数组
- component_hierarchy: 组件层次数组（从架构树顶层到该模块的节点名称路径）
- sub_components: 该模块内部包含的子组件名称数组
- function_description: 模块的具体功能说明
- related_components: 与该模块有关联的其他组件名称数组
- port_requirements: 该模块需要的 AADL 端口声明数组，每个包含 port_name, direction, data_type, reason
- connection_hints: 模块间连接关系提示数组，每个包含 source, target, data_type

最终答案请用 ```json 代码块包裹完整的模块分析结果 JSON。
""";
    }

    @Override
    protected String buildUserMessage(AgentInput input) {
        String requirementsJson = input.getContent();
        String architectureJson = input.getMetadata();

        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下需求列表和 AADL 架构树，进行模块分析：\n\n");
        sb.append("【需求列表】\n").append(requirementsJson).append("\n\n");
        sb.append("【AADL 架构树】\n").append(architectureJson).append("\n\n");
        sb.append("如果需要查询 AADL 模块设计规则、组件类型定义、端口声明规范或连接关系示例，请调用 search_knowledge 工具。\n");
        sb.append("完成分析后，输出完整的模块分析结果 JSON（用 ```json 代码块包裹）。");
        return sb.toString();
    }

    @Override
    protected List<Tool> getTools() {
        return Collections.singletonList(searchKnowledgeTool);
    }

    @Override
    protected AgentOutput buildFinalOutput(AgentInput input, String finalContent, long totalTimeMs) {
        try {
            ModuleAnalysisResult result = parseModuleAnalysis(finalContent);

            if (result != null && result.getModules() != null && !result.getModules().isEmpty()) {
                String outputJson = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result);

                log.info("========================================");
                log.info("ModuleAnalysisAgent 执行成功!");
                log.info("分析模块数量: {} 个", result.getModules().size());
                log.info("总耗时: {}ms", totalTimeMs);
                log.info("========================================");

                for (int i = 0; i < result.getModules().size(); i++) {
                    ModuleAnalysisResult.Module module = result.getModules().get(i);
                    log.info("  {}: {} (层次: {})", i + 1, module.getModuleName(),
                            String.join(" → ", module.getComponentHierarchy()));
                }

                return AgentOutput.success(input.getSessionId(), outputJson, totalTimeMs);
            }

            log.error("解析出的模块列表为空");
            return AgentOutput.failure(input.getSessionId(), "模块分析失败：模块列表为空");

        } catch (Exception e) {
            log.error("模块分析结果解析失败: {}", e.getMessage(), e);
            return AgentOutput.failure(input.getSessionId(), "模块分析结果解析失败: " + e.getMessage());
        }
    }

    @Override
    protected double getTemperature() {
        return temperature;
    }

    @Override
    protected int getMaxTokens() {
        return maxTokens;
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }

    // ==================== 原有辅助方法（保留） ====================

    private ModuleAnalysisResult parseModuleAnalysis(String response) throws Exception {
        String jsonContent = JsonRepairUtil.extractAndFixJson(response);

        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            throw new IllegalArgumentException("无法从响应中提取JSON内容");
        }

        return objectMapper.readValue(jsonContent, ModuleAnalysisResult.class);
    }
}
