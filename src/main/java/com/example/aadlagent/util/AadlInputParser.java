package com.example.aadlagent.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AADL 输入解析器（硬编码，不依赖大模型）。
 *
 * 将架构树 JSON 和模块分析 JSON 解析为结构化的文本清单，
 * 直接注入 LLM 提示词，替代原始 JSON，使 LLM 无需自行理解 JSON 结构。
 *
 * 解析后的 {@link ParseResult} 同时暴露：
 * - manifestText：文本清单，注入提示词
 * - archComponents：组件真值表，供 {@link AadlReferenceValidator} 交叉验证复用
 * - modules：模块约束列表，供验证器检查连接关系复用
 */
@Slf4j
public class AadlInputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ========================= 公共数据结构 =========================

    /** 架构树节点（同时作为验证器的真值组件） */
    public static class ArchNode {
        public String name;
        public String type;
        public String parent;
        public int depth;
        public List<ArchNode> children = new ArrayList<>();
    }

    /** 模块分析信息 */
    public static class ModuleInfo {
        public String name;
        public List<String> hierarchy = new ArrayList<>();
        public List<String> subComponents = new ArrayList<>();
        public String functionDescription;
        public List<String> relatedComponents = new ArrayList<>();
        public List<String> satisfiedRequirements = new ArrayList<>();
    }

    /** 解析结果：文本清单 + 结构化数据 */
    public static class ParseResult {
        /** 文本清单，注入 LLM 提示词 */
        public String manifestText;
        /** 组件真值表（name → 节点），供验证器复用 */
        public Map<String, ArchNode> archComponents = new LinkedHashMap<>();
        /** 模块约束列表，供验证器复用 */
        public List<ModuleInfo> modules = new ArrayList<>();
    }

    // ========================= 公共入口 =========================

    /**
     * 解析架构树和模块分析 JSON，生成结构化的组件清单和约束文本。
     *
     * @param architectureJson 架构树 JSON
     * @param modulesJson      模块分析 JSON
     * @return ParseResult，包含文本清单和结构化数据
     */
    public ParseResult parse(String architectureJson, String modulesJson) {
        ParseResult result = new ParseResult();
        StringBuilder manifest = new StringBuilder();

        // 1. 解析架构树
        List<ArchNode> archNodes = new ArrayList<>();
        ArchNode root = null;
        if (architectureJson != null && !architectureJson.trim().isEmpty()) {
            try {
                JsonNode rootNode = MAPPER.readTree(architectureJson);
                root = parseArchNode(rootNode, null, 0, archNodes, result.archComponents);
                log.info("架构树解析完成：{} 个组件", archNodes.size());
            } catch (Exception e) {
                log.warn("解析架构树失败: {}", e.getMessage());
            }
        }

        // 2. 解析模块分析
        if (modulesJson != null && !modulesJson.trim().isEmpty()) {
            try {
                JsonNode rootNode = MAPPER.readTree(modulesJson);
                JsonNode modulesNode = rootNode.get("modules");
                if (modulesNode != null && modulesNode.isArray()) {
                    for (JsonNode m : modulesNode) {
                        ModuleInfo info = new ModuleInfo();
                        info.name = getText(m, "module_name");
                        info.hierarchy = toStringList(m.get("component_hierarchy"));
                        info.subComponents = toStringList(m.get("sub_components"));
                        info.functionDescription = getText(m, "function_description");
                        info.relatedComponents = toStringList(m.get("related_components"));
                        info.satisfiedRequirements = toStringList(m.get("satisfied_requirements"));
                        result.modules.add(info);
                    }
                }
                log.info("模块分析解析完成：{} 个模块", result.modules.size());
            } catch (Exception e) {
                log.warn("解析模块分析失败: {}", e.getMessage());
            }
        }

        // 3. 生成组件清单
        buildComponentList(manifest, archNodes);

        // 4. 生成类型约束
        buildTypeConstraints(manifest, archNodes);

        // 5. 生成层级关系
        buildHierarchy(manifest, root);

        // 6. 生成模块约束
        buildModuleConstraints(manifest, result.modules);

        result.manifestText = manifest.toString();
        return result;
    }

    // ========================= 组件清单 =========================

    private void buildComponentList(StringBuilder sb, List<ArchNode> nodes) {
        sb.append("═══════════════════════════════════════════════════════════\n");
        sb.append("【组件清单】以下为全部组件，AADL 中不得出现清单之外的组件，也不得遗漏任何组件。\n");
        sb.append("═══════════════════════════════════════════════════════════\n");
        sb.append(String.format("  %-30s %-15s %s%n", "组件名", "AADL类型", "逻辑父节点"));
        sb.append("  ─────────────────────────────────────────────────────────\n");

        for (ArchNode node : nodes) {
            String parent = node.parent != null ? node.parent : "(根)";
            sb.append(String.format("  %-30s %-15s %s%n", node.name, node.type, parent));
        }
        sb.append("  ─────────────────────────────────────────────────────────\n");
        sb.append(String.format("  共 %d 个组件%n%n", nodes.size()));
    }

    // ========================= 类型约束 =========================

    private void buildTypeConstraints(StringBuilder sb, List<ArchNode> nodes) {
        sb.append("【类型约束】每个组件的 AADL 类型已固定，必须严格使用以下类型关键字，不得更改：\n");

        int col = 0;
        for (ArchNode node : nodes) {
            if (col == 0) {
                sb.append("  ");
            }
            sb.append(node.name).append(" -> ").append(node.type);
            col++;
            if (col < 4 && col < nodes.size()) {
                sb.append(", ");
            } else {
                sb.append("\n");
                col = 0;
            }
        }
        sb.append("\n");
    }

    // ========================= 层级关系 =========================

    private void buildHierarchy(StringBuilder sb, ArchNode root) {
        sb.append("【层级关系】来自架构树，用于确定 subcomponents 嵌套结构：\n\n");

        if (root != null) {
            printTreeNode(sb, root, "", true);
        }

        sb.append("\n");
    }

    private void printTreeNode(StringBuilder sb, ArchNode node, String prefix, boolean isLast) {
        sb.append(prefix);
        sb.append(isLast ? "+-- " : "|-- ");
        sb.append(node.name).append(" (").append(node.type).append(")\n");

        for (int i = 0; i < node.children.size(); i++) {
            String childPrefix = prefix + (isLast ? "    " : "|   ");
            printTreeNode(sb, node.children.get(i), childPrefix, i == node.children.size() - 1);
        }
    }

    // ========================= 模块约束 =========================

    private void buildModuleConstraints(StringBuilder sb, List<ModuleInfo> modules) {
        if (modules.isEmpty()) {
            return;
        }

        sb.append("═══════════════════════════════════════════════════════════\n");
        sb.append("【模块约束】从模块分析中提取，必须映射到 AADL 的 properties 和 connections。\n");
        sb.append("═══════════════════════════════════════════════════════════\n\n");

        for (int i = 0; i < modules.size(); i++) {
            ModuleInfo m = modules.get(i);
            sb.append(String.format("--- 模块 %d: %s ---%n", i + 1, m.name));

            // 对应组件
            String componentName = !m.hierarchy.isEmpty()
                    ? m.hierarchy.get(m.hierarchy.size() - 1)
                    : "(未知)";
            sb.append("对应组件: ").append(componentName).append("\n");

            // 层级路径
            if (!m.hierarchy.isEmpty()) {
                sb.append("层级路径: ").append(String.join(" -> ", m.hierarchy)).append("\n");
            }

            // 需求
            if (!m.satisfiedRequirements.isEmpty()) {
                sb.append("满足需求: ").append(String.join(", ", m.satisfiedRequirements)).append("\n");
            }

            // 功能描述
            if (m.functionDescription != null && !m.functionDescription.isEmpty()) {
                sb.append("功能描述: ").append(m.functionDescription).append("\n");
            }

            // 子组件
            if (!m.subComponents.isEmpty()) {
                sb.append("子组件: ").append(String.join(", ", m.subComponents)).append("\n");
            }

            // 关联组件 -> 连接约束
            if (!m.relatedComponents.isEmpty()) {
                sb.append("关联组件: ").append(String.join(", ", m.relatedComponents)).append("\n");
                sb.append("-> 必须在 connections 中声明 ").append(componentName)
                        .append(" 与以上关联组件的连接\n");
            }

            sb.append("\n");
        }
    }

    // ========================= 辅助方法 =========================

    private ArchNode parseArchNode(JsonNode node, String parentName, int depth,
                                   List<ArchNode> registry,
                                   Map<String, ArchNode> componentMap) {
        if (node == null || !node.has("name") || !node.has("type")) {
            return null;
        }

        ArchNode archNode = new ArchNode();
        archNode.name = node.get("name").asText();
        archNode.type = node.get("type").asText();
        archNode.parent = parentName;
        archNode.depth = depth;
        registry.add(archNode);

        // 同时注册到真值表（同名组件以第一个为准）
        if (!componentMap.containsKey(archNode.name)) {
            componentMap.put(archNode.name, archNode);
        }

        if (node.has("children") && node.get("children").isArray()) {
            for (JsonNode child : node.get("children")) {
                ArchNode childNode = parseArchNode(child, archNode.name, depth + 1, registry, componentMap);
                if (childNode != null) {
                    archNode.children.add(childNode);
                }
            }
        }

        return archNode;
    }

    private String getText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText();
    }

    private List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode element : node) {
            if (!element.isNull()) {
                result.add(element.asText());
            }
        }
        return result;
    }
}
