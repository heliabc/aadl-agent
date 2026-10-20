package com.example.aadlagent.rag;

import com.example.aadlagent.model.AadlArchitectureModel;
import com.example.aadlagent.model.ModuleAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AADL 生成专用查询构造器。
 *
 * 核心思路：aadl agent 的输入不是自然语言查询，而是结构化的
 * 架构树 JSON + 模块分析 JSON。我们知道每个字段的精确含义，
 * 所以直接解析 JSON 结构，映射到 AADL 标准语法概念，
 * 比把 JSON 当字符串做关键词匹配精确得多。
 *
 * 查询构造分三层：
 *   1. 结构层（高权重）：组件类型、feature kind、连接类型
 *   2. 关系层（中权重）：层级深度、子组件数量、连接模式
 *   3. 描述层（低权重）：功能描述中的语义关键词
 */
@Slf4j
@Component
public class AadlQueryBuilder {

    private final ObjectMapper objectMapper;

    public AadlQueryBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 根据架构树 JSON 和模块分析 JSON 构建检索查询。
     * 返回的是空格分隔的 AADL 标准术语，直接可用于 RAG 检索。
     */
    public String buildQuery(String architectureJson, String modulesJson) {
        Set<String> concepts = new LinkedHashSet<>();

        // 1. 解析架构树，提取结构层概念
        AadlArchitectureModel arch = tryParseArchitecture(architectureJson);
        if (arch != null) {
            concepts.addAll(extractFromArchitecture(arch));
            log.info("[AadlQueryBuilder] 架构树提取概念 ({}): {}", concepts.size(), concepts);
        } else {
            log.warn("[AadlQueryBuilder] 架构树 JSON 解析失败，降级为字符串匹配");
        }

        // 2. 解析模块分析，提取关系层+描述层概念
        ModuleAnalysisResult modules = tryParseModules(modulesJson);
        if (modules != null) {
            List<String> moduleConcepts = extractFromModules(modules);
            for (String c : moduleConcepts) {
                concepts.add(c);
            }
            log.info("[AadlQueryBuilder] 模块分析新增概念，总计 {}: {}", concepts.size(), concepts);
        }

        // 3. 如果概念太少，补一些通用核心概念保底
        if (concepts.size() < 3) {
            concepts.add("component_type");
            concepts.add("port_spec");
            concepts.add("feature_clause");
            log.info("[AadlQueryBuilder] 概念不足，补充保底概念");
        }

        // 4. 按特异性排序，取 top-N
        List<String> result = concepts.stream()
                .sorted(Comparator.comparingInt(this::specificityScore).reversed())
                .limit(8)  // 比普通查询多2个，因为结构信息更可靠
                .collect(Collectors.toList());

        String query = String.join(" ", result);
        log.info("[AadlQueryBuilder] 最终查询 ({} terms): {}", result.size(), query);
        return query;
    }

    // ========== 架构树解析 ==========

    private List<String> extractFromArchitecture(AadlArchitectureModel arch) {
        Set<String> concepts = new LinkedHashSet<>();

        // 根组件类型
        if (arch.getType() != null) {
            concepts.addAll(mapComponentType(arch.getType()));
        }

        // 递归遍历所有子组件
        if (arch.getSubcomponents() != null) {
            collectComponentTypes(arch.getSubcomponents(), concepts);
        }

        // 根组件的 features
        if (arch.getFeatures() != null) {
            collectFeatureKinds(arch.getFeatures(), concepts);
        }

        // 有子组件 → 有 subcomponent_clause + component_implementation
        if (arch.getSubcomponents() != null && !arch.getSubcomponents().isEmpty()) {
            concepts.add("subcomponent_clause");
            concepts.add("component_implementation");
        }

        // 有多种 feature kind → 有 feature_clause
        long featureCount = arch.getFeatures() != null ? arch.getFeatures().size() : 0;
        if (featureCount > 0) {
            concepts.add("feature_clause");
        }

        // 层级深度 > 1 → 涉及子组件和连接
        int depth = maxDepth(arch);
        if (depth >= 2) {
            concepts.add("connection_clause");
            concepts.add("port_connection");
        }

        return new ArrayList<>(concepts);
    }

    private void collectComponentTypes(List<AadlArchitectureModel> components, Set<String> concepts) {
        if (components == null) return;
        for (AadlArchitectureModel comp : components) {
            if (comp.getType() != null) {
                concepts.addAll(mapComponentType(comp.getType()));
            }
            if (comp.getFeatures() != null) {
                collectFeatureKinds(comp.getFeatures(), concepts);
            }
            if (comp.getSubcomponents() != null) {
                collectComponentTypes(comp.getSubcomponents(), concepts);
            }
        }
    }

    private void collectFeatureKinds(List<AadlArchitectureModel.FeatureModel> features, Set<String> concepts) {
        if (features == null) return;
        for (AadlArchitectureModel.FeatureModel f : features) {
            if (f.getKind() != null) {
                concepts.addAll(mapFeatureKind(f.getKind()));
            }
        }
    }

    private int maxDepth(AadlArchitectureModel node) {
        if (node.getSubcomponents() == null || node.getSubcomponents().isEmpty()) return 1;
        int maxChildDepth = 0;
        for (AadlArchitectureModel child : node.getSubcomponents()) {
            maxChildDepth = Math.max(maxChildDepth, maxDepth(child));
        }
        return 1 + maxChildDepth;
    }

    // ========== 模块分析解析 ==========

    private List<String> extractFromModules(ModuleAnalysisResult modules) {
        Set<String> concepts = new LinkedHashSet<>();

        if (modules.getModules() == null || modules.getModules().isEmpty()) {
            return new ArrayList<>(concepts);
        }

        boolean hasConnections = false;
        boolean hasPorts = false;
        boolean hasHierarchy = false;
        Set<String> functionKeywords = new HashSet<>();

        for (ModuleAnalysisResult.Module m : modules.getModules()) {
            // 连接提示 → 连接相关概念
            if (m.getConnectionHints() != null && !m.getConnectionHints().isEmpty()) {
                hasConnections = true;
            }
            // 端口需求 → 端口相关概念
            if (m.getPortRequirements() != null && !m.getPortRequirements().isEmpty()) {
                hasPorts = true;
            }
            // 组件层级 → 子组件概念
            if (m.getComponentHierarchy() != null && m.getComponentHierarchy().size() > 1) {
                hasHierarchy = true;
            }
            // 功能描述 → 语义关键词提取（流、属性、模式等）
            if (m.getFunctionDescription() != null) {
                extractSemanticKeywords(m.getFunctionDescription(), functionKeywords);
            }
            // 满足的需求 → 同样提取语义关键词
            if (m.getSatisfiedRequirements() != null) {
                for (String req : m.getSatisfiedRequirements()) {
                    extractSemanticKeywords(req, functionKeywords);
                }
            }
        }

        if (hasConnections) {
            concepts.add("connection_clause");
            concepts.add("port_connection");
            concepts.add("access_connection");
        }
        if (hasPorts) {
            concepts.add("port_spec");
            concepts.add("feature_clause");
        }
        if (hasHierarchy) {
            concepts.add("subcomponent_clause");
            concepts.add("component_implementation");
        }

        // 语义关键词映射
        concepts.addAll(functionKeywords);

        return new ArrayList<>(concepts);
    }

    /**
     * 从功能描述/需求描述中提取 AADL 语义关键词。
     * 这些是自然语言描述中隐含的高级概念（流、属性、模式、错误模型等）
     */
    private void extractSemanticKeywords(String text, Set<String> keywords) {
        if (text == null || text.isEmpty()) return;
        String lower = text.toLowerCase();

        // 流相关
        if (lower.contains("流") || lower.contains("flow") || lower.contains("数据流") || lower.contains("通路")) {
            keywords.add("flow_specification");
            keywords.add("flow_implementation");
        }
        if (lower.contains("端到端") || lower.contains("end_to_end") || lower.contains("end to end")) {
            keywords.add("end_to_end_flow");
        }

        // 属性/约束相关
        if (lower.contains("时延") || lower.contains("延迟") || lower.contains("latency") || lower.contains("delay")) {
            keywords.add("property_association");
            keywords.add("property_type");
        }
        if (lower.contains("功耗") || lower.contains("power") || lower.contains("重量") || lower.contains("weight")) {
            keywords.add("property_association");
            keywords.add("units_type");
        }
        if (lower.contains("约束") || lower.contains("constraint") || lower.contains("属性")) {
            keywords.add("property_association");
        }

        // 模式相关
        if (lower.contains("模式") || lower.contains("mode") || lower.contains("状态切换") || lower.contains("故障状态")) {
            keywords.add("mode_declaration");
            keywords.add("mode_transition");
        }

        // 错误模型/EMV2
        if (lower.contains("故障") || lower.contains("错误") || lower.contains("error") || lower.contains("fault") || lower.contains("失效")) {
            keywords.add("annex_subclause");
        }
        if (lower.contains("emv2") || lower.contains("error model") || lower.contains("错误模型")) {
            keywords.add("annex_subclause");
        }

        // 子程序调用
        if (lower.contains("调用") || lower.contains("call") || lower.contains("子程序")) {
            keywords.add("subprogram_call_sequence");
            keywords.add("subprogram_call");
        }

        // 原型
        if (lower.contains("原型") || lower.contains("prototype")) {
            keywords.add("prototype");
            keywords.add("component_prototype");
        }
    }

    // ========== 映射表 ==========

    /**
     * 架构树中组件 type 字段 → AADL 语法概念
     */
    private List<String> mapComponentType(String type) {
        if (type == null) return Collections.emptyList();
        return switch (type.toLowerCase()) {
            case "system" -> Arrays.asList("system_component_type", "component_type");
            case "thread" -> Arrays.asList("thread_component_type", "component_type");
            case "process" -> Arrays.asList("process_component_type", "component_type");
            case "data" -> Arrays.asList("data_component_type");
            case "processor" -> Arrays.asList("processor_component_type", "processor_feature");
            case "memory" -> Arrays.asList("memory_component_type");
            case "bus" -> Arrays.asList("bus_component_type");
            case "device" -> Arrays.asList("device_component_type");
            case "subprogram" -> Arrays.asList("subprogram_component_type");
            case "abstract" -> Arrays.asList("abstract_component_type");
            case "virtual_bus", "virtual bus" -> Arrays.asList("virtual_bus_component_type");
            case "virtual_processor", "virtual processor" -> Arrays.asList("virtual_processor_component_type");
            default -> Arrays.asList("component_type", "component_category");
        };
    }

    /**
     * Feature kind 字段 → AADL 语法概念
     */
    private List<String> mapFeatureKind(String kind) {
        if (kind == null) return Collections.emptyList();
        return switch (kind.toLowerCase()) {
            case "data_port", "data port" -> Arrays.asList("port_spec", "feature_clause", "port_connection");
            case "event_port", "event port" -> Arrays.asList("port_spec", "feature_clause", "port_connection");
            case "event_data_port", "event data port" -> Arrays.asList("port_spec", "feature_clause", "port_connection");
            case "bus_access", "bus access" -> Arrays.asList("bus_access_spec", "feature_clause", "access_connection");
            case "data_access", "data access" -> Arrays.asList("data_access_spec", "feature_clause", "access_connection");
            case "subprogram_access", "subprogram access" -> Arrays.asList("subprogram_access_spec", "feature_clause");
            case "feature_group", "feature group" -> Arrays.asList("feature_group_spec", "feature_group_type", "feature_group_connection");
            case "parameter", "parameter_port" -> Arrays.asList("parameter_spec", "parameter_connection");
            default -> Arrays.asList("feature_clause");
        };
    }

    /**
     * 特异性评分：术语越具体，分数越高，排越靠前。
     */
    private int specificityScore(String term) {
        int score = 0;
        // 长度越长越具体
        score += term.length();
        // 下划线越多说明组合层次越深
        score += countChar(term, '_') * 3;
        // 高特异性后缀
        if (term.endsWith("_spec")) score += 5;
        if (term.endsWith("_connection")) score += 5;
        if (term.endsWith("_implementation")) score += 4;
        if (term.contains("access_")) score += 4;
        if (term.contains("bus_access")) score += 2;
        if (term.contains("feature_group")) score += 3;
        if (term.endsWith("_clause")) score += 3;
        // 组件类类型（system_component_type 比 component_type 更具体）
        if (term.startsWith("system_") || term.startsWith("thread_") ||
            term.startsWith("process_") || term.startsWith("processor_") ||
            term.startsWith("memory_") || term.startsWith("device_") ||
            term.startsWith("virtual_") || term.startsWith("data_")) {
            if (term.contains("_component_")) score += 4;
        }
        // 流、属性、模式等高阶概念
        if (term.startsWith("flow_")) score += 3;
        if (term.startsWith("mode_")) score += 3;
        if (term.startsWith("property_")) score += 2;
        if (term.equals("annex_subclause")) score += 4;
        if (term.equals("end_to_end_flow")) score += 5;
        return score;
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    // ========== 解析辅助 ==========

    private AadlArchitectureModel tryParseArchitecture(String json) {
        try {
            return objectMapper.readValue(json, AadlArchitectureModel.class);
        } catch (Exception e) {
            log.warn("[AadlQueryBuilder] 架构树JSON解析失败: {}", e.getMessage());
            return null;
        }
    }

    private ModuleAnalysisResult tryParseModules(String json) {
        try {
            return objectMapper.readValue(json, ModuleAnalysisResult.class);
        } catch (Exception e) {
            log.warn("[AadlQueryBuilder] 模块分析JSON解析失败: {}", e.getMessage());
            return null;
        }
    }
}
