package com.example.aadlagent.agent.architecture;

import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.agent.ReActAgent;
import com.example.aadlagent.agent.tool.SearchKnowledgeTool;
import com.example.aadlagent.agent.tool.Tool;
import com.example.aadlagent.agent.tool.ValidateArchitectureTool;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.model.AadlArchitectureModel;
import com.example.aadlagent.util.JsonRepairUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class AadlArchitectureAgent extends ReActAgent {

    private static final String AGENT_NAME = "AadlArchitectureAgent";

    private final AadlArchitecturePrompt prompt;
    private final SearchKnowledgeTool searchKnowledgeTool;
    private final ValidateArchitectureTool validateArchitectureTool;

    @Value("${agent.architecture.temperature:0.1}")
    private double temperature;

    @Value("${agent.architecture.max-tokens:8192}")
    private int maxTokens;

    public AadlArchitectureAgent(ModelService modelService,
                                 SearchKnowledgeTool searchKnowledgeTool,
                                 ValidateArchitectureTool validateArchitectureTool) {
        super(modelService);
        this.searchKnowledgeTool = searchKnowledgeTool;
        this.validateArchitectureTool = validateArchitectureTool;
        this.objectMapper.disable(SerializationFeature.WRITE_NULL_MAP_VALUES);
        this.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.objectMapper.enable(JsonParser.Feature.ALLOW_COMMENTS);
        this.objectMapper.enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES);
        this.objectMapper.enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
        this.prompt = new AadlArchitecturePrompt();
    }

    @Override
    protected String getSystemPrompt() {
        return """
你是一位精通 AADL（Architecture Analysis & Design Language）的系统架构设计师。
你的任务是根据需求列表，设计出合理的 AADL 架构模型（树状层级结构）。

## 工作方式（ReAct 模式）

1. **Thought（思考）**：分析需求，确定系统的组件组成和层级关系
2. **Action（行动）**：如果不确定 AADL 组件规则或需要参考示例，调用 search_knowledge 工具查询
3. **Observation（观察）**：查看工具返回的知识
4. 每次修改架构后，必须调用 validate_architecture 工具验证架构树的层级包含关系是否符合 AADL 规范
5. 重复以上步骤，直到架构验证通过且你有足够信息完成架构设计
6. **Final Answer**：输出完整的架构模型 JSON

## 可用工具

- **search_knowledge**: 检索 AADL 领域知识，包括组件类型、层级规则、设计示例等。不确定 AADL 架构规则时调用。
- **validate_architecture**: 验证架构树的层级包含关系是否符合 AADL 规范。每次修改架构后必须调用此工具进行验证，确保组件之间的包含关系合法。

## 输出要求

输出必须是一个合法的 JSON 对象，表示架构树，格式如下：
```json
{
  "name": "SystemName",
  "type": "system",
  "subcomponents": [
    {
      "name": "DeviceName",
      "type": "device",
      "features": [...]
    },
    ...
  ]
}
```

AADL 组件类型包括：system, process, thread, processor, device, memory, bus, data, subprogram。
层级规则：system 可包含 process/processor/device/memory/bus；process 可包含 thread/subprogram 等。

最终答案请用 ```json 代码块包裹完整的架构模型 JSON。
""";
    }

    @Override
    protected String buildUserMessage(AgentInput input) {
        String requirementsJson = input.getContent();
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下需求列表，设计 AADL 架构模型：\n\n");
        sb.append("需求列表：\n").append(requirementsJson).append("\n\n");
        sb.append("如果需要查询 AADL 组件类型、层级规则或设计示例，请调用 search_knowledge 工具。\n");
        sb.append("完成设计后，输出完整的架构模型 JSON（用 ```json 代码块包裹）。");
        return sb.toString();
    }

    @Override
    protected List<Tool> getTools() {
        return Arrays.asList(searchKnowledgeTool, validateArchitectureTool);
    }

    @Override
    protected AgentOutput buildFinalOutput(AgentInput input, String finalContent, long totalTimeMs) {
        try {
            AadlArchitectureModel architecture = parseArchitecture(finalContent);

            if (architecture != null && architecture.getName() != null) {
                String outputJson = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(architecture);

                log.info("========================================");
                log.info("AadlArchitectureAgent 执行成功!");
                log.info("根节点名称: {}", architecture.getName());
                log.info("子组件数量: {}", architecture.getSubcomponents() != null
                        ? architecture.getSubcomponents().size() : 0);
                log.info("总耗时: {}ms", totalTimeMs);
                log.info("========================================");

                return AgentOutput.success(input.getSessionId(), outputJson, totalTimeMs);
            }

            log.error("解析出的架构模型为空或名称缺失");
            return AgentOutput.failure(input.getSessionId(), "架构模型解析失败：名称为空");

        } catch (Exception e) {
            log.error("架构模型解析失败: {}", e.getMessage(), e);
            return AgentOutput.failure(input.getSessionId(), "架构模型解析失败: " + e.getMessage());
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
    protected String getKnowledgeDomain() {
        return "architecture";
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }

    // ==================== 原有辅助方法（保留） ====================

    private AadlArchitectureModel parseArchitecture(String response) throws Exception {
        String jsonContent = JsonRepairUtil.extractAndFixJson(response);

        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            throw new IllegalArgumentException("无法从响应中提取JSON内容");
        }

        AadlArchitectureModel architecture = objectMapper.readValue(jsonContent, AadlArchitectureModel.class);

        if (architecture != null && architecture.getName() == null) {
            com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(jsonContent);
            if (rootNode.has("root")) {
                com.fasterxml.jackson.databind.JsonNode inner = rootNode.get("root");
                architecture = objectMapper.treeToValue(inner, AadlArchitectureModel.class);
            }
        }

        return architecture;
    }
}
