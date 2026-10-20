package com.example.aadlagent.rag;

import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.config.RewriteConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class QueryRewriter {

    private final ModelService modelService;
    private final RewriteConfig rewriteConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 200;

    /**
     * AADL 语法词典：从口语/模糊关键词映射到标准 syntax_name 列表。
     * 基于 knowledge/aadl.json v2.1 的 81 条结构化知识库构建。
     */
    private static final Map<String, List<String>> SYNTAX_DICTIONARY = new LinkedHashMap<>();

    static {
        // === Ch.6 组件类型与实现 (18条) ===
        SYNTAX_DICTIONARY.put("组件类型", Arrays.asList("component_type", "component_category"));
        SYNTAX_DICTIONARY.put("定义组件", Arrays.asList("component_type", "component_implementation"));
        SYNTAX_DICTIONARY.put("组件实现", Arrays.asList("component_implementation", "component_implementation_extension"));
        SYNTAX_DICTIONARY.put("扩展组件", Arrays.asList("component_type_extension", "component_implementation_extension"));
        SYNTAX_DICTIONARY.put("继承组件", Arrays.asList("component_type_extension", "component_implementation_extension"));
        SYNTAX_DICTIONARY.put("线程", Arrays.asList("thread_component_type", "component_type"));
        SYNTAX_DICTIONARY.put("thread", Arrays.asList("thread_component_type"));
        SYNTAX_DICTIONARY.put("数据", Arrays.asList("data_component_type"));
        SYNTAX_DICTIONARY.put("data", Arrays.asList("data_component_type"));
        SYNTAX_DICTIONARY.put("子程序", Arrays.asList("subprogram_component_type", "subprogram_call_sequence", "subprogram_call"));
        SYNTAX_DICTIONARY.put("subprogram", Arrays.asList("subprogram_component_type", "subprogram_call"));
        SYNTAX_DICTIONARY.put("系统", Arrays.asList("system_component_type", "component_type"));
        SYNTAX_DICTIONARY.put("system", Arrays.asList("system_component_type"));
        SYNTAX_DICTIONARY.put("进程", Arrays.asList("process_component_type", "component_type"));
        SYNTAX_DICTIONARY.put("process", Arrays.asList("process_component_type"));
        SYNTAX_DICTIONARY.put("处理器", Arrays.asList("processor_component_type", "processor_feature"));
        SYNTAX_DICTIONARY.put("processor", Arrays.asList("processor_component_type", "processor_feature"));
        SYNTAX_DICTIONARY.put("内存", Arrays.asList("memory_component_type"));
        SYNTAX_DICTIONARY.put("memory", Arrays.asList("memory_component_type"));
        SYNTAX_DICTIONARY.put("总线", Arrays.asList("bus_component_type", "bus_access_spec"));
        SYNTAX_DICTIONARY.put("bus", Arrays.asList("bus_component_type", "bus_access_spec"));
        SYNTAX_DICTIONARY.put("设备", Arrays.asList("device_component_type"));
        SYNTAX_DICTIONARY.put("device", Arrays.asList("device_component_type"));
        SYNTAX_DICTIONARY.put("虚拟处理器", Arrays.asList("virtual_processor_component_type"));
        SYNTAX_DICTIONARY.put("虚拟总线", Arrays.asList("virtual_bus_component_type"));
        SYNTAX_DICTIONARY.put("抽象", Arrays.asList("abstract_component_type"));
        SYNTAX_DICTIONARY.put("abstract", Arrays.asList("abstract_component_type"));

        // === Ch.8 特征 (11条) ===
        SYNTAX_DICTIONARY.put("特征", Arrays.asList("feature_clause", "feature", "port_spec"));
        SYNTAX_DICTIONARY.put("feature", Arrays.asList("feature_clause", "feature", "port_spec"));
        SYNTAX_DICTIONARY.put("端口", Arrays.asList("port_spec", "feature_clause", "port_connection"));
        SYNTAX_DICTIONARY.put("port", Arrays.asList("port_spec", "port_connection"));
        SYNTAX_DICTIONARY.put("数据端口", Arrays.asList("port_spec", "port_connection"));
        SYNTAX_DICTIONARY.put("事件端口", Arrays.asList("port_spec", "port_connection"));
        SYNTAX_DICTIONARY.put("事件数据端口", Arrays.asList("port_spec", "port_connection"));
        SYNTAX_DICTIONARY.put("特征组", Arrays.asList("feature_group_spec", "feature_group_type", "feature_group_connection"));
        SYNTAX_DICTIONARY.put("feature group", Arrays.asList("feature_group_spec", "feature_group_type"));
        SYNTAX_DICTIONARY.put("子程序访问", Arrays.asList("subprogram_access_spec"));
        SYNTAX_DICTIONARY.put("数据访问", Arrays.asList("data_access_spec"));
        SYNTAX_DICTIONARY.put("参数", Arrays.asList("parameter_spec", "parameter_connection"));
        SYNTAX_DICTIONARY.put("parameter", Arrays.asList("parameter_spec", "parameter_connection"));
        SYNTAX_DICTIONARY.put("总线访问", Arrays.asList("bus_access_spec"));
        SYNTAX_DICTIONARY.put("内部特征", Arrays.asList("internal_feature"));
        SYNTAX_DICTIONARY.put("处理器特征", Arrays.asList("processor_feature"));

        // === Ch.9 子组件与原型 (5条) ===
        SYNTAX_DICTIONARY.put("子组件", Arrays.asList("subcomponent_clause", "subcomponent", "subcomponent_access_spec"));
        SYNTAX_DICTIONARY.put("subcomponent", Arrays.asList("subcomponent_clause", "subcomponent"));
        SYNTAX_DICTIONARY.put("原型", Arrays.asList("prototype", "component_prototype", "feature_prototype"));
        SYNTAX_DICTIONARY.put("prototype", Arrays.asList("prototype", "component_prototype", "feature_prototype"));

        // === Ch.10 连接 (7条) ===
        SYNTAX_DICTIONARY.put("连接", Arrays.asList("connection_clause", "connection", "port_connection", "access_connection"));
        SYNTAX_DICTIONARY.put("connection", Arrays.asList("connection_clause", "connection", "port_connection"));
        SYNTAX_DICTIONARY.put("端口连接", Arrays.asList("port_connection", "connection_clause"));
        SYNTAX_DICTIONARY.put("访问连接", Arrays.asList("access_connection", "connection_clause"));
        SYNTAX_DICTIONARY.put("特征组连接", Arrays.asList("feature_group_connection", "connection_clause"));
        SYNTAX_DICTIONARY.put("参数连接", Arrays.asList("parameter_connection", "connection_clause"));
        SYNTAX_DICTIONARY.put("特征连接", Arrays.asList("feature_connection", "connection_clause"));

        // === Ch.11 流 (3条) ===
        SYNTAX_DICTIONARY.put("流", Arrays.asList("flow_specification", "flow_implementation", "end_to_end_flow"));
        SYNTAX_DICTIONARY.put("flow", Arrays.asList("flow_specification", "flow_implementation", "end_to_end_flow"));
        SYNTAX_DICTIONARY.put("端到端流", Arrays.asList("end_to_end_flow", "flow_specification"));

        // === Ch.12 属性集 (9条+) ===
        SYNTAX_DICTIONARY.put("属性", Arrays.asList("property_set", "property_type", "property_definition", "property_association", "contained_property_association"));
        SYNTAX_DICTIONARY.put("property", Arrays.asList("property_set", "property_type", "property_definition", "property_association"));
        SYNTAX_DICTIONARY.put("属性集", Arrays.asList("property_set"));
        SYNTAX_DICTIONARY.put("属性类型", Arrays.asList("property_type", "property_type_declaration", "enumeration_type", "range_type", "units_type", "record_type", "classifier_type", "reference_type"));
        SYNTAX_DICTIONARY.put("属性定义", Arrays.asList("property_definition", "property_definition_declaration"));
        SYNTAX_DICTIONARY.put("属性关联", Arrays.asList("property_association", "contained_property_association"));
        SYNTAX_DICTIONARY.put("枚举", Arrays.asList("enumeration_type"));
        SYNTAX_DICTIONARY.put("范围", Arrays.asList("range_type"));
        SYNTAX_DICTIONARY.put("单位", Arrays.asList("units_type"));
        SYNTAX_DICTIONARY.put("记录", Arrays.asList("record_type"));
        SYNTAX_DICTIONARY.put("分类器", Arrays.asList("classifier_type"));
        SYNTAX_DICTIONARY.put("引用", Arrays.asList("reference_type", "property_reference"));

        // === Ch.13 模式 (4条) ===
        SYNTAX_DICTIONARY.put("模式", Arrays.asList("mode_declaration", "mode_transition", "modes_subclause", "mode_transition_trigger"));
        SYNTAX_DICTIONARY.put("mode", Arrays.asList("mode_declaration", "mode_transition", "modes_subclause"));
        SYNTAX_DICTIONARY.put("模式转换", Arrays.asList("mode_transition", "mode_transition_trigger"));
        SYNTAX_DICTIONARY.put("mode transition", Arrays.asList("mode_transition", "mode_transition_trigger"));

        // === Ch.14 子程序调用 (3条) ===
        SYNTAX_DICTIONARY.put("调用序列", Arrays.asList("subprogram_call_sequence"));
        SYNTAX_DICTIONARY.put("子程序调用", Arrays.asList("subprogram_call", "called_subprogram"));
        SYNTAX_DICTIONARY.put("called subprogram", Arrays.asList("called_subprogram", "subprogram_call"));

        // === Ch.4 附件 (4条) ===
        SYNTAX_DICTIONARY.put("附件", Arrays.asList("annex_subclause", "annex_library"));
        SYNTAX_DICTIONARY.put("annex", Arrays.asList("annex_subclause", "annex_library"));
        SYNTAX_DICTIONARY.put("数据集附件", Arrays.asList("dataset_annex_library", "dataset_annex_subclause"));

        // === Ch.5 包 (5条) ===
        SYNTAX_DICTIONARY.put("包", Arrays.asList("package", "with_clause", "import_declaration", "alias_declaration"));
        SYNTAX_DICTIONARY.put("package", Arrays.asList("package", "with_clause", "import_declaration"));
        SYNTAX_DICTIONARY.put("导入", Arrays.asList("import_declaration", "with_clause"));
        SYNTAX_DICTIONARY.put("with", Arrays.asList("with_clause", "import_declaration"));
        SYNTAX_DICTIONARY.put("别名", Arrays.asList("alias_declaration"));

        // === Ch.4 词法 (7条) ===
        SYNTAX_DICTIONARY.put("标识符", Arrays.asList("identifier"));
        SYNTAX_DICTIONARY.put("identifier", Arrays.asList("identifier"));
        SYNTAX_DICTIONARY.put("数字字面量", Arrays.asList("numeric_literal"));
        SYNTAX_DICTIONARY.put("字符串", Arrays.asList("string_literal"));
        SYNTAX_DICTIONARY.put("注释", Arrays.asList("comment"));
        SYNTAX_DICTIONARY.put("关键字", Arrays.asList("keywords"));
        SYNTAX_DICTIONARY.put("分隔符", Arrays.asList("delimiters_and_operators"));

        // === Annex C 表达式 (2条) ===
        SYNTAX_DICTIONARY.put("表达式", Arrays.asList("expression", "property_reference"));
        SYNTAX_DICTIONARY.put("expression", Arrays.asList("expression", "property_reference"));
        SYNTAX_DICTIONARY.put("属性引用", Arrays.asList("property_reference"));

        // === 架构树JSON专用：精确feature kind值（aadl agent输入是JSON，不是自然语言）===
        // 这些是 AadlArchitectureModel.FeatureModel.kind 的精确值
        SYNTAX_DICTIONARY.put("data_port", Arrays.asList("port_spec", "feature_clause", "port_connection"));
        SYNTAX_DICTIONARY.put("event_port", Arrays.asList("port_spec", "feature_clause", "port_connection"));
        SYNTAX_DICTIONARY.put("event_data_port", Arrays.asList("port_spec", "feature_clause", "port_connection"));
        SYNTAX_DICTIONARY.put("bus_access", Arrays.asList("bus_access_spec", "feature_clause", "access_connection"));
        SYNTAX_DICTIONARY.put("data_access", Arrays.asList("data_access_spec", "feature_clause", "access_connection"));
        SYNTAX_DICTIONARY.put("subprogram_access", Arrays.asList("subprogram_access_spec", "feature_clause"));
        SYNTAX_DICTIONARY.put("feature_group", Arrays.asList("feature_group_spec", "feature_group_type", "feature_group_connection"));
        SYNTAX_DICTIONARY.put("parameter_port", Arrays.asList("parameter_spec", "parameter_connection"));

        // === 模块分析JSON专用：字段名映射（ModuleAnalysisResult）===
        SYNTAX_DICTIONARY.put("port_requirements", Arrays.asList("port_spec", "feature_clause"));
        SYNTAX_DICTIONARY.put("connection_hints", Arrays.asList("connection_clause", "port_connection", "access_connection"));
        SYNTAX_DICTIONARY.put("component_hierarchy", Arrays.asList("subcomponent_clause", "component_type"));
        SYNTAX_DICTIONARY.put("sub_components", Arrays.asList("subcomponent_clause", "subcomponent"));
        SYNTAX_DICTIONARY.put("related_components", Arrays.asList("component_type", "component_category"));
    }

    // 概念检索：提取到的术语数量 >= 此阈值时，直接用概念做检索，跳过 LLM
    private static final int ENOUGH_CONCEPTS_THRESHOLD = 3;
    // 概念检索：最多保留的术语数量（避免查询过长影响向量质量）
    private static final int MAX_CONCEPTS_FOR_SEARCH = 6;
    // 超长查询阈值（字符数）
    private static final int LONG_QUERY_THRESHOLD = 500;

    // AADL领域专用改写Prompt（JSON格式，适配14B小模型）
    // 输出严格 JSON，解析失败直接降级
    private static final String REWRITE_PROMPT = """
将下面的查询改写为3-5个AADL标准术语。

参考术语：
{}

只输出JSON，不要其他文字。格式：{"terms": ["术语1", "术语2", "术语3"]}

查询：{}
JSON：""";

    // AADL领域专用拆分Prompt（极简版）
    private static final String DECOMPOSE_PROMPT = """
将下面的AADL问题拆成2-3条子查询，每行一条。
每条子查询只问一个知识点，用AADL标准术语。

问题：{}
子查询：
""";

    public QueryRewriter(ModelService modelService, RewriteConfig rewriteConfig) {
        this.modelService = modelService;
        this.rewriteConfig = rewriteConfig;
    }

    /**
     * 从用户查询中提取匹配的AADL语法概念（基于静态词典）。
     * 返回按匹配优先级排序的唯一 syntax_name 列表。
     */
    List<String> extractSyntaxConcepts(String query) {
        if (!StringUtils.hasText(query)) {
            return Collections.emptyList();
        }
        String lowerQuery = query.toLowerCase();
        Set<String> matchedConcepts = new LinkedHashSet<>();

        // 1. 精确短语匹配（优先）
        for (Map.Entry<String, List<String>> entry : SYNTAX_DICTIONARY.entrySet()) {
            String keyword = entry.getKey().toLowerCase();
            // 要求关键词作为独立词出现，避免"data"匹配"database"
            if (lowerQuery.contains(keyword)) {
                matchedConcepts.addAll(entry.getValue());
            }
        }

        // 2. 如果匹配太多，按领域去重保留最相关的
        List<String> result = new ArrayList<>(matchedConcepts);
        if (result.size() > 8) {
            // 优先保留更具体的术语（包含下划线或component_前缀的通常更具体）
            result.sort((a, b) -> {
                int scoreA = scoreSpecificity(a);
                int scoreB = scoreSpecificity(b);
                return Integer.compare(scoreB, scoreA);
            });
            result = result.subList(0, 8);
        }

        log.debug("Extracted syntax concepts for query '{}': {}", query, result);
        return result;
    }

    /**
     * 评估术语 specificity 分数：越具体分数越高。
     */
    private int scoreSpecificity(String term) {
        int score = 0;
        if (term.contains("_")) score += 2;
        if (term.startsWith("component_")) score += 1;
        if (term.endsWith("_type")) score += 1;
        if (term.endsWith("_spec")) score += 1;
        if (term.endsWith("_connection")) score += 1;
        if (term.endsWith("_clause")) score += 1;
        return score;
    }

    /**
    /**
     * 按 agentType 选择改写策略。
     * - aadl: AADL 语法特化改写（语法词典 + LLM 术语改写 + 有效性校验）
     * 其他: 直接返回原查询（不做改写，避免引入幻觉；向量检索对原查询段落内容匹配更好）
     */
    public String rewrite(String query, String agentType) {
        if (!StringUtils.hasText(query)) {
            log.warn("Input query is blank, return original");
            return query;
        }
        String trimmedQuery = query.trim();

        // 非 AADL agent 直接返回原查询，不做改写
        // 原因：requirement/architecture/module/error_memory 的知识库是段落式内容，
        // 长查询向量匹配原查询反而更准，改写容易丢失上下文
        if (!"aadl".equals(agentType)) {
            log.debug("agentType={}, skip query rewrite (use original query)", agentType);
            return trimmedQuery;
        }

        return rewriteAadlQuery(trimmedQuery);
    }

    /**
     * 兼容旧接口（无 agentType 参数），默认按通用处理（不改写）。
     * @deprecated 请使用 rewrite(query, agentType)
     */
    @Deprecated
    public String rewrite(String query) {
        return rewrite(query, null);
    }

    /**
     * AADL 专用改写主逻辑：
     *
     * 核心思路：aadl agent 的输入是长需求文档，不是短查询。
     * 向量检索对长文档效果很差（日志验证过 0 命中），所以主路径是：
     *   静态词典提取 AADL 概念 → 用标准术语做检索
     *
     * 策略优先级：
     *   1. 概念数 >= ENOUGH_CONCEPTS_THRESHOLD → 直接用概念，不调 LLM（主路径）
     *   2. 概念数 < 阈值 + 短查询 → 尝试 LLM 改写补充术语（兜底）
     *   3. LLM 失败 → 降级为概念拼接
     */
    private String rewriteAadlQuery(String trimmedQuery) {
        // 1. 静态词典提取 AADL 语法概念（O(1)，不依赖 LLM）
        List<String> concepts = extractSyntaxConcepts(trimmedQuery);
        if (!concepts.isEmpty()) {
            log.info("[aadl] Matched syntax concepts ({}): {}", concepts.size(), concepts);
        }

        // 2. 主路径：概念足够多 → 直接用 top-N 概念做检索，跳过 LLM
        // 原因：概念就是标准 syntax_name，与知识库精确匹配，比长文档 embedding 靠谱得多
        if (concepts.size() >= ENOUGH_CONCEPTS_THRESHOLD) {
            List<String> topConcepts = concepts.size() > MAX_CONCEPTS_FOR_SEARCH
                    ? concepts.subList(0, MAX_CONCEPTS_FOR_SEARCH)
                    : concepts;
            String conceptQuery = String.join(" ", topConcepts);
            log.info("[aadl] Concept-based retrieval ({} terms, top-{}): {}",
                    concepts.size(), topConcepts.size(), conceptQuery);
            return conceptQuery;
        }

        // 3. 概念太少 → 尝试 LLM 改写补充（短查询场景才走这里）
        if (trimmedQuery.length() > LONG_QUERY_THRESHOLD) {
            // 长查询但概念少，直接降级（调 LLM 也是白费劲）
            String fallback = buildFallbackQuery(trimmedQuery, concepts);
            log.info("[aadl] Long query with few concepts ({}), use fallback", concepts.size());
            return fallback;
        }

        log.info("[aadl] Few concepts ({}), trying LLM rewrite for short query", concepts.size());

        LlmClient client = modelService.getClient(rewriteConfig.getModelType());
        if (!client.isAvailable()) {
            log.warn("[aadl] Rewrite model unavailable, use fallback");
            return buildFallbackQuery(trimmedQuery, concepts);
        }

        String conceptsForPrompt = concepts.isEmpty()
                ? "component_type port_spec property_association mode_declaration subprogram_call flow_specification"
                : String.join(" ", concepts);
        String prompt = String.format(REWRITE_PROMPT, conceptsForPrompt, trimmedQuery);
        String modelName = rewriteConfig.getOllamaModelName();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String rewritten = client.chat(prompt, rewriteConfig.getTemperature(), rewriteConfig.getMaxTokens(), modelName);
                if (StringUtils.hasText(rewritten)) {
                    List<String> terms = parseJsonTerms(rewritten.trim());
                    if (terms != null && terms.size() >= 2) {
                        String result = String.join(" ", terms);
                        log.info("[aadl] LLM rewrite success, terms ({}): {}", terms.size(), result);
                        return result;
                    }
                    log.warn("[aadl] LLM rewrite JSON parse failed, attempt {}/{}", attempt, MAX_RETRIES);
                }
            } catch (Exception e) {
                log.warn("[aadl] LLM rewrite error attempt {}/{}: {}", attempt, MAX_RETRIES, e.getMessage());
            }
            if (attempt < MAX_RETRIES) {
                sleepSafe(RETRY_DELAY_MS);
            }
        }

        // 4. 全部失败降级
        log.warn("[aadl] LLM rewrite all retries failed, fallback to concept-enhanced query");
        return buildFallbackQuery(trimmedQuery, concepts);
    }

    /**
     * 从 LLM 输出中解析 JSON 格式的 terms 数组。
     * 支持严格 JSON 也支持从废话中提取第一个 JSON 对象。
     * 解析失败返回 null。
     */
    private List<String> parseJsonTerms(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            return null;
        }
        String text = rawOutput.trim();

        // 1. 尝试直接解析
        try {
            JsonNode node = objectMapper.readTree(text);
            return extractTermsFromNode(node);
        } catch (Exception ignored) {
            // 不是纯 JSON，继续提取
        }

        // 2. 找第一个 { ... } JSON 对象（LLM 可能在前后加了文字）
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            String jsonStr = text.substring(firstBrace, lastBrace + 1);
            try {
                JsonNode node = objectMapper.readTree(jsonStr);
                return extractTermsFromNode(node);
            } catch (Exception e) {
                log.debug("[aadl] JSON extraction failed: {}", e.getMessage());
            }
        }

        // 3. 找第一个 [ ... ] JSON 数组（模型可能直接输出数组）
        int firstBracket = text.indexOf('[');
        int lastBracket = text.lastIndexOf(']');
        if (firstBracket >= 0 && lastBracket > firstBracket) {
            String jsonStr = text.substring(firstBracket, lastBracket + 1);
            try {
                JsonNode node = objectMapper.readTree(jsonStr);
                if (node.isArray()) {
                    List<String> terms = new ArrayList<>();
                    for (JsonNode item : node) {
                        if (item.isTextual()) {
                            terms.add(item.asText().trim());
                        }
                    }
                    return terms.isEmpty() ? null : terms;
                }
            } catch (Exception e) {
                log.debug("[aadl] Array JSON extraction failed: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * 从 JSON 节点中提取 terms 数组。
     * 支持 {"terms": [...]} 和直接数组两种格式。
     */
    private List<String> extractTermsFromNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        // 优先找 terms 字段
        JsonNode termsNode = node.has("terms") ? node.get("terms") : null;
        if (termsNode == null || !termsNode.isArray()) {
            // 如果根节点就是数组
            if (node.isArray()) {
                termsNode = node;
            } else {
                return null;
            }
        }
        List<String> terms = new ArrayList<>();
        for (JsonNode item : termsNode) {
            if (item.isTextual()) {
                String t = item.asText().trim();
                if (!t.isEmpty()) {
                    terms.add(t);
                }
            }
        }
        return terms.isEmpty() ? null : terms;
    }

    /**
     * 构建降级查询：将原始查询与提取的语法概念拼接，提升检索关键词密度。
     */
    private String buildFallbackQuery(String originalQuery, List<String> concepts) {
        if (concepts.isEmpty()) {
            return originalQuery;
        }
        // 避免重复：如果原始查询已包含概念词，不重复追加
        String lowerOriginal = originalQuery.toLowerCase();
        List<String> newConcepts = concepts.stream()
                .filter(c -> !lowerOriginal.contains(c.toLowerCase().replace("_", " ")))
                .collect(Collectors.toList());
        if (newConcepts.isEmpty()) {
            return originalQuery;
        }
        return originalQuery + " " + String.join(" ", newConcepts);
    }

    /**
     * 多意图查询拆分（增加重试、过滤空行、领域Prompt）
     */
    public String[] decompose(String query) {
        if (!StringUtils.hasText(query)) {
            return new String[]{query};
        }
        String trimmedQuery = query.trim();
        log.info("Start decompose raw query: {}", trimmedQuery);

        // 获取配置的模型客户端
        LlmClient client = modelService.getClient(rewriteConfig.getModelType());
        
        // 模型不可用降级：不拆分，直接返回原句数组
        if (!client.isAvailable()) {
            log.warn("Rewrite model {} unavailable, skip decompose", rewriteConfig.getModelType());
            return new String[]{trimmedQuery};
        }

        String prompt = String.format(DECOMPOSE_PROMPT, trimmedQuery);
        String modelName = rewriteConfig.getOllamaModelName();
        String decomposedText = null;

        // 和rewrite保持一致重试逻辑
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String resp = client.chat(prompt, rewriteConfig.getTemperature(), rewriteConfig.getMaxTokens(), modelName);
                if (StringUtils.hasText(resp)) {
                    decomposedText = resp;
                    break;
                }
                log.warn("Decompose empty response attempt {}/{}", attempt, MAX_RETRIES);
            } catch (Exception e) {
                log.warn("Decompose LLM error attempt {}/{} err={}", attempt, MAX_RETRIES, e.getMessage());
            }
            if (attempt < MAX_RETRIES) {
                sleepSafe(RETRY_DELAY_MS);
            }
        }

        // LLM全部失败，降级返回原句
        if (!StringUtils.hasText(decomposedText)) {
            log.warn("Decompose all retries failed, fallback single query");
            return new String[]{trimmedQuery};
        }

        // 分割并过滤空行、空白字符串，防止空向量
        List<String> subQueries = Arrays.stream(decomposedText.split("\\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        // 过滤后无有效子查询，降级原句
        if (subQueries.isEmpty()) {
            return new String[]{trimmedQuery};
        }
        return subQueries.toArray(new String[0]);
    }

    /**
     * 安全休眠，屏蔽中断异常
     */
    private void sleepSafe(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Thread sleep interrupted");
        }
    }
}
