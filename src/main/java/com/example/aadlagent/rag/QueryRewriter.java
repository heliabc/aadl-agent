package com.example.aadlagent.rag;

import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.config.RewriteConfig;
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
    }

    // AADL领域专用改写Prompt（结构化版本，注入语法概念）
    private static final String REWRITE_PROMPT = """
你是AADL（Architecture Analysis and Design Language）核心语法查询重构专家。
你的任务是将用户的自然语言查询改写为适合向量检索的AADL标准术语查询。

【AADL知识库结构】
知识库包含81条结构化语法条目，每条有：syntax_name、bnf_syntax、语义说明、正例/反例、规则。
核心章节：Ch.4词法、Ch.5包、Ch.6组件、Ch.7执行平台、Ch.8特征、Ch.9子组件/原型、Ch.10连接、Ch.11流、Ch.12属性、Ch.13模式、Ch.14子程序调用、附件、表达式。

【用户查询涉及的核心语法概念】
{}

【改写规则】
1. 必须使用上述列出的标准 syntax_name 作为检索关键词，不得替换为口语化表达。
2. 如果查询涉及组件定义，优先使用 component_type / component_implementation / component_type_extension。
3. 如果查询涉及端口/特征，优先使用 port_spec / feature_clause / data_access_spec / subprogram_access_spec / bus_access_spec / parameter_spec。
4. 如果查询涉及连接，优先使用 connection / port_connection / access_connection / feature_group_connection / parameter_connection。
5. 如果查询涉及属性，优先使用 property_association / property_definition / property_type / property_set。
6. 如果查询涉及模式，优先使用 mode_declaration / mode_transition / modes_subclause。
7. 如果查询涉及子组件，优先使用 subcomponent / subcomponent_clause。
8. 如果查询涉及子程序调用，优先使用 subprogram_call / subprogram_call_sequence / called_subprogram。
9. 如果查询涉及流，优先使用 flow_specification / flow_implementation / end_to_end_flow。
10. 保留原始查询中的具体约束条件（如"不能嵌套"、"必须绑定"、"方向错误"等）。
11. 同义词扩展仅在同一语法领域内进行，禁止跨领域混淆（如不要把"端口"扩展成"属性"）。
12. 输出必须是一条紧凑的检索语句，包含2~5个核心术语，适合向量相似度匹配。

【禁止】
- 禁止输出解释、序号、换行、Markdown格式。
- 禁止 invent 不存在的AADL语法术语。
- 禁止将硬件概念（processor/bus/memory）与软件概念（thread/process/data/subprogram）混为一谈。

用户原始查询：{}
仅输出改写后的单行查询：
""";

    // AADL领域专用拆分Prompt（结构化版本）
    private static final String DECOMPOSE_PROMPT = """
你是AADL核心语法查询拆分专家。将用户复杂提问拆分为2~4条独立检索子查询，每条只包含单一AADL语法意图。

【AADL知识库核心术语】
组件：component_type, component_implementation, component_type_extension, thread, data, subprogram, system, process, processor, memory, bus, device, abstract
特征：feature_clause, port_spec, feature_group_type, data_access_spec, subprogram_access_spec, bus_access_spec, parameter_spec
连接：connection, port_connection, access_connection, feature_group_connection, parameter_connection
子组件：subcomponent, subcomponent_clause, prototype
属性：property_set, property_type, property_definition, property_association
模式：mode_declaration, mode_transition, modes_subclause
流：flow_specification, flow_implementation, end_to_end_flow
子程序调用：subprogram_call_sequence, subprogram_call, called_subprogram
附件：annex_subclause, annex_library

【拆分规则】
1. 每条子查询必须包含1~2个上述标准术语。
2. 如果查询涉及多个语法领域（如同时问端口和连接），按领域拆分。
3. 如果查询涉及错误/约束检查，单独拆出一条包含"规则"或"合法性"关键词的子查询。
4. 仅每行输出一条子查询，禁止空行、注释、序号、解释文本。

原始查询：{}
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
     * 将提取的语法概念格式化为 Prompt 注入文本。
     */
    private String formatSyntaxConceptsForPrompt(List<String> concepts) {
        if (concepts.isEmpty()) {
            return "（未匹配到明确语法概念，请根据查询内容推断最相关的AADL术语）";
        }
        return concepts.stream()
                .map(c -> "- " + c)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 查询优化改写（带完整重试降级 + AADL语法概念注入）
     */
    public String rewrite(String query) {
        if (!StringUtils.hasText(query)) {
            log.warn("Input query is blank, return original");
            return query;
        }
        String trimmedQuery = query.trim();
        log.info("Start rewrite raw query: {}", trimmedQuery);

        // 1. 基于静态词典提取AADL语法概念（不依赖LLM，总是可用）
        List<String> concepts = extractSyntaxConcepts(trimmedQuery);
        String conceptsText = formatSyntaxConceptsForPrompt(concepts);
        if (!concepts.isEmpty()) {
            log.info("Matched AADL syntax concepts: {}", concepts);
        }

        // 获取配置的模型客户端
        LlmClient client = modelService.getClient(rewriteConfig.getModelType());
        
        // 模型不可用直接降级：但仍返回注入概念的查询（比原查询更有检索价值）
        if (!client.isAvailable()) {
            log.warn("Rewrite model {} unavailable, skip LLM rewrite", rewriteConfig.getModelType());
            // 降级策略：将提取到的语法概念拼接到原查询末尾，增强关键词密度
            String fallbackQuery = buildFallbackQuery(trimmedQuery, concepts);
            log.info("Fallback query with syntax concepts: {}", fallbackQuery);
            return fallbackQuery;
        }

        // 2. 构造注入语法概念的Prompt
        String prompt = String.format(REWRITE_PROMPT, conceptsText, trimmedQuery);
        String modelName = rewriteConfig.getOllamaModelName();
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String rewritten = client.chat(prompt, rewriteConfig.getTemperature(), rewriteConfig.getMaxTokens(), modelName);
                if (StringUtils.hasText(rewritten)) {
                    String result = rewritten.trim();
                    // 后处理：去除可能的引号、序号前缀
                    result = sanitizeRewrittenQuery(result);
                    log.info("Rewrite success, attempt {}, result: {}", attempt, result);
                    return result;
                }
                log.warn("Rewrite empty response, attempt {}/{}", attempt, MAX_RETRIES);
            } catch (Exception e) {
                log.warn("Rewrite LLM call error attempt {}/{} err={}", attempt, MAX_RETRIES, e.getMessage());
            }
            // 重试休眠
            if (attempt < MAX_RETRIES) {
                sleepSafe(RETRY_DELAY_MS);
            }
        }
        
        // 3. 全部重试失败，降级为概念增强查询
        log.warn("Rewrite all {} retries failed, fallback to concept-enhanced query", MAX_RETRIES);
        return buildFallbackQuery(trimmedQuery, concepts);
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
     * 清洗LLM改写结果：去除常见噪声。
     */
    private String sanitizeRewrittenQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        String result = query.trim();
        // 去除首尾引号
        if ((result.startsWith("\"") && result.endsWith("\"")) ||
            (result.startsWith("'") && result.endsWith("'"))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        // 去除 "1."、"- " 等序号前缀
        result = result.replaceFirst("^[\\d\\.\\-\\s]+", "").trim();
        return result;
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
