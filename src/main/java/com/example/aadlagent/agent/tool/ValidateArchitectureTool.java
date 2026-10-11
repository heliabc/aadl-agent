package com.example.aadlagent.agent.tool;

import com.example.aadlagent.model.AadlArchitectureModel;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AADL 架构层级包含关系验证工具
 *
 * 对给定的 AADL 架构模型 JSON 执行层级包含关系验证，
 * 检查每个父组件的类型是否允许包含其子组件的类型，
 * 确保架构树符合 AADL 规范的 containment 规则。
 *
 * 用途：在 ReAct 修复循环中，每次 LLM 修改架构后调用此工具验证，
 * 让 LLM 知道哪些层级包含关系不合法，实现"修改-验证-再修改"的闭环。
 */
@Slf4j
@Component
public class ValidateArchitectureTool implements Tool {

    private static final String TOOL_NAME = "validate_architecture";

    private final ObjectMapper objectMapper;

    /**
     * AADL 组件包含规则：父组件类型 -> 允许的子组件类型集合
     * 所有类型均使用小写存储，比较时不区分大小写。
     */
    private static final Map<String, Set<String>> CONTAINMENT_RULES = new HashMap<>();

    static {
        CONTAINMENT_RULES.put("system", Set.of(
                "system", "process", "processor", "device", "memory", "bus", "data"
        ));
        CONTAINMENT_RULES.put("process", Set.of(
                "thread", "subprogram", "data", "subprogram_group", "process"
        ));
        CONTAINMENT_RULES.put("thread", Set.of(
                "subprogram", "data"
        ));
        CONTAINMENT_RULES.put("processor", Set.of(
                "memory", "bus"
        ));
        CONTAINMENT_RULES.put("device", Set.of(
                "data"
        ));
        CONTAINMENT_RULES.put("bus", Set.of(
                "bus", "virtual_bus"
        ));
        // memory、data、subprogram 不包含子组件
        CONTAINMENT_RULES.put("memory", Set.of());
        CONTAINMENT_RULES.put("data", Set.of());
        CONTAINMENT_RULES.put("subprogram", Set.of());
    }

    public ValidateArchitectureTool() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "验证 AADL 架构树的层级包含关系是否符合 AADL 规范，检查组件类型是否允许包含子组件类型。每次修改架构后应该调用此工具验证。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> archParam = new LinkedHashMap<>();
        archParam.put("type", "string");
        archParam.put("description", "架构模型的 JSON 字符串（符合 AadlArchitectureModel 格式）");
        props.put("architecture_json", archParam);

        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("architecture_json")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String architectureJson = (String) arguments.get("architecture_json");
        if (architectureJson == null || architectureJson.trim().isEmpty()) {
            return "错误：architecture_json 参数不能为空";
        }

        try {
            AadlArchitectureModel model = objectMapper.readValue(
                    architectureJson, AadlArchitectureModel.class);

            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            // 递归验证架构树
            validateNode(model, null, errors, warnings);

            StringBuilder sb = new StringBuilder();
            sb.append("=== AADL 架构层级验证结果 ===\n");

            // 错误
            if (!errors.isEmpty()) {
                sb.append("\n【错误】(").append(errors.size()).append(" 项):\n");
                for (int i = 0; i < errors.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(errors.get(i)).append("\n");
                }
            } else {
                sb.append("\n【错误】: 无\n");
            }

            // 警告
            if (!warnings.isEmpty()) {
                sb.append("\n【警告】(").append(warnings.size()).append(" 项):\n");
                for (int i = 0; i < warnings.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(warnings.get(i)).append("\n");
                }
            } else {
                sb.append("【警告】: 无\n");
            }

            // 汇总
            sb.append("\n【汇总】: ").append(errors.size()).append(" 错误, ")
                    .append(warnings.size()).append(" 警告\n");

            if (errors.isEmpty()) {
                sb.append("【结论】: 架构层级包含关系验证通过 ✓\n");
            } else {
                sb.append("【结论】: 架构层级包含关系验证未通过，请修复上述错误\n");
            }

            log.info("ValidateArchitectureTool: {} 错误, {} 警告", errors.size(), warnings.size());

            return sb.toString();

        } catch (Exception e) {
            log.error("ValidateArchitectureTool execution error: {}", e.getMessage(), e);
            return "验证工具执行异常: " + e.getMessage();
        }
    }

    /**
     * 递归验证单个节点及其子组件的包含关系
     *
     * @param node     当前节点
     * @param parent   父节点（根节点时为 null）
     * @param errors   错误列表（收集用）
     * @param warnings 警告列表（收集用）
     */
    private void validateNode(AadlArchitectureModel node, AadlArchitectureModel parent,
                              List<String> errors, List<String> warnings) {
        if (node == null) {
            return;
        }

        String nodeName = node.getName() != null ? node.getName() : "(未命名)";
        String nodeType = node.getType() != null ? node.getType().toLowerCase() : null;

        // 检查类型是否为空
        if (nodeType == null || nodeType.isEmpty()) {
            errors.add("组件 '" + nodeName + "' 的类型为空");
            return;
        }

        // 检查类型是否为已知的 AADL 组件类型
        if (!CONTAINMENT_RULES.containsKey(nodeType)) {
            warnings.add("组件 '" + nodeName + "' 的类型 '" + node.getType()
                    + "' 不是标准 AADL 组件类型，跳过包含关系检查");
        }

        // 验证父子包含关系（根节点跳过）
        if (parent != null) {
            String parentName = parent.getName() != null ? parent.getName() : "(未命名)";
            String parentType = parent.getType() != null ? parent.getType().toLowerCase() : null;

            if (parentType != null && CONTAINMENT_RULES.containsKey(parentType)) {
                Set<String> allowedChildren = CONTAINMENT_RULES.get(parentType);
                if (!allowedChildren.contains(nodeType)) {
                    String allowedStr = String.join(", ", allowedChildren);
                    if (allowedStr.isEmpty()) {
                        allowedStr = "无（该类型不允许包含子组件）";
                    }
                    errors.add("父组件 '" + parentName + "' (类型: " + parent.getType()
                            + ") 不允许包含子组件 '" + nodeName + "' (类型: " + node.getType()
                            + ")。允许的子组件类型: " + allowedStr);
                }
            }
        }

        // 递归验证子组件
        List<AadlArchitectureModel> subcomponents = node.getSubcomponents();
        if (subcomponents != null && !subcomponents.isEmpty()) {
            // 如果当前组件类型不允许包含任何子组件，但存在子组件，添加错误
            if (nodeType != null && CONTAINMENT_RULES.containsKey(nodeType)
                    && CONTAINMENT_RULES.get(nodeType).isEmpty()) {
                errors.add("组件 '" + nodeName + "' (类型: " + node.getType()
                        + ") 不允许包含子组件，但实际包含 " + subcomponents.size() + " 个子组件");
            }

            for (AadlArchitectureModel child : subcomponents) {
                validateNode(child, node, errors, warnings);
            }
        }
    }
}
