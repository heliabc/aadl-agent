package com.example.aadlagent.agent.aadl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class AadlGeneratorPrompt {

    private Map<String, Object> rulesConfig;

    /** 同义词组列表：每组包含规范词及其所有同义词，匹配时自动展开 */
    private final List<Set<String>> synonymGroups = new ArrayList<>();

    public AadlGeneratorPrompt() {
        loadRules();
    }

    @SuppressWarnings("unchecked")
    private void loadRules() {
        try {
            ClassPathResource resource = new ClassPathResource("aadl-rules.yml");
            try (InputStream is = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                this.rulesConfig = yaml.load(is);
            }
            loadSynonyms();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load AADL rules file", e);
        }
    }

    /**
     * 从 YAML 的 synonyms 段加载同义词组。
     *
     * YAML 格式：
     *   synonyms:
     *     规范词: ["同义词1", "同义词2", ...]
     *
     * 加载后每组 = {规范词} ∪ {同义词列表}，存入 synonymGroups。
     * 匹配时若规则关键词命中某组任一成员，该组全部成员自动参与匹配。
     */
    @SuppressWarnings("unchecked")
    private void loadSynonyms() {
        synonymGroups.clear();
        Object synonymsObj = rulesConfig.get("synonyms");
        if (!(synonymsObj instanceof Map)) {
            log.info("aadl-rules.yml 未配置 synonyms 段，跳过同义词加载");
            return;
        }
        Map<String, Object> raw = (Map<String, Object>) synonymsObj;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String canonical = entry.getKey();
            Set<String> group = new HashSet<>();
            group.add(canonical);
            if (entry.getValue() instanceof List) {
                group.addAll((List<String>) entry.getValue());
            }
            synonymGroups.add(group);
        }
        log.info("同义词表加载完成：{} 组", synonymGroups.size());
    }

    /**
     * 用同义词表展开规则的关键词集合。
     *
     * 对每个原始关键词，检查它是否出现在某个同义词组中（不区分大小写）。
     * 若命中，将该组全部成员加入展开集合。
     *
     * @param keywords 规则配置的原始关键词列表
     * @return 展开后的关键词集合（含原始关键词 + 命中同义词组的全部成员）
     */
    private Set<String> expandKeywords(List<String> keywords) {
        Set<String> expanded = new HashSet<>(keywords);
        for (String keyword : keywords) {
            String lowerKeyword = keyword.toLowerCase();
            for (Set<String> group : synonymGroups) {
                for (String term : group) {
                    if (term.toLowerCase().equals(lowerKeyword)) {
                        expanded.addAll(group);
                        break;
                    }
                }
            }
        }
        return expanded;
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String parsedManifest, String ragContext) {
        StringBuilder prompt = new StringBuilder();

        // 1. 角色定义
        prompt.append(rulesConfig.get("system_prompt"));
        prompt.append("\n\n");

        // 2. 核心红线规则（首因效应：放在最前面，模型最先读到）
        Map<String, Object> global = (Map<String, Object>) rulesConfig.get("global");
        prompt.append(global.get("alwaysRules"));
        prompt.append("\n\n");

        // 3. 任务目标
        prompt.append(rulesConfig.get("task_description"));
        prompt.append("\n\n");

        // 4. RAG 参考知识
        if (ragContext != null && !ragContext.trim().isEmpty()) {
            prompt.append("【参考知识】\n");
            prompt.append(ragContext);
            prompt.append("\n\n");
        }

        // 5. 组件模板规则（按条件动态注入）
        String combinedInput = parsedManifest != null ? parsedManifest : "";
        String combinedLower = combinedInput.toLowerCase();

        prompt.append("【组件模板规则】\n");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) rulesConfig.get("rules");
        int injectedCount = 0;
        int skippedCount = 0;
        for (Map<String, Object> rule : rules) {
            String ruleId = (String) rule.get("id");
            String decision = decideRuleInjection(rule, combinedInput, combinedLower);

            if ("skip".equals(decision)) {
                skippedCount++;
                log.debug("规则过滤：跳过 [{}] {}", ruleId, rule.get("title"));
                continue;
            }

            prompt.append("--- ").append(rule.get("title")).append(" ---\n");
            prompt.append(rule.get("content"));
            prompt.append("\n\n");
            injectedCount++;
        }
        log.info("规则过滤完成：注入 {} 条，跳过 {} 条（共 {} 条）", injectedCount, skippedCount, rules.size());

        // 6. 生成顺序
        Map<String, Object> order = (Map<String, Object>) rulesConfig.get("order");
        prompt.append("【生成顺序】\n");
        prompt.append(order.get("content"));
        prompt.append("\n\n");

        // 7. 示例
        Map<String, Object> example = (Map<String, Object>) rulesConfig.get("example");
        prompt.append("【").append(example.get("title")).append("】\n");
        prompt.append(example.get("description")).append("\n");
        prompt.append("--- 示例开始 ---\n");
        prompt.append(example.get("content"));
        prompt.append("\n--- 示例结束 ---\n\n");

        // 8. 输入清单
        prompt.append(rulesConfig.get("input_section"));
        prompt.append("\n\n");
        prompt.append(parsedManifest);
        prompt.append("\n\n");

        // 9. 自检清单（近因效应：紧贴 output_instruction，模型最后读到）
        Map<String, Object> checklist = (Map<String, Object>) rulesConfig.get("checklist");
        if (checklist != null && checklist.get("content") != null) {
            prompt.append(checklist.get("content"));
            prompt.append("\n\n");
        }

        // 10. 输出指令
        prompt.append(rulesConfig.get("output_instruction"));

        return prompt.toString();
    }

    /**
     * 极简 Prompt（消融实验用）
     */
    public String buildMinimalPrompt(String parsedManifest) {
        return "你是一个AADL建模专家。请根据以下架构和模块分析信息，生成AADL代码。\n\n" +
                "输入信息：\n" + parsedManifest + "\n\n" +
                "请直接输出AADL代码，不要解释。";
    }

    /**
     * 决定单条规则是否注入 prompt。
     *
     * 判断优先级：
     * 1. skipIfKeywords：输入中包含任一排除词 → 直接跳过
     * 2. always: true → 始终注入
     * 3. trigger.anyKeywords：输入中包含任一关键词 → 注入
     * 4. 没有 always 也没有 trigger → 默认注入（保守策略）
     *
     * @param rule          规则配置
     * @param combinedInput 架构树+模块分析的原始拼接文本
     * @param combinedLower 拼接文本的小写版本（用于不区分大小写匹配）
     * @return "inject" 或 "skip"
     */
    @SuppressWarnings("unchecked")
    private String decideRuleInjection(Map<String, Object> rule, String combinedInput, String combinedLower) {
        // 1. 检查 skipIfKeywords（排除词优先级最高）
        Object skipIfKeywords = rule.get("skipIfKeywords");
        if (skipIfKeywords instanceof List) {
            List<String> skipWords = (List<String>) skipIfKeywords;
            for (String word : skipWords) {
                if (combinedInput.contains(word)) {
                    return "skip";
                }
            }
        }

        // 2. always: true 始终注入
        Object always = rule.get("always");
        if (Boolean.TRUE.equals(always)) {
            return "inject";
        }

        // 3. trigger.anyKeywords：任一关键词匹配则注入（经同义词表展开后匹配）
        Object trigger = rule.get("trigger");
        if (trigger instanceof Map) {
            Map<String, Object> triggerMap = (Map<String, Object>) trigger;
            Object anyKeywords = triggerMap.get("anyKeywords");
            if (anyKeywords instanceof List) {
                List<String> keywords = (List<String>) anyKeywords;
                // 同义词展开：若关键词命中同义词组，自动加入该组全部成员
                Set<String> expandedKeywords = expandKeywords(keywords);
                if (expandedKeywords.size() > keywords.size()) {
                    log.debug("规则 [{}] 关键词展开：{} -> {} 个", rule.get("id"), keywords.size(), expandedKeywords.size());
                }
                for (String keyword : expandedKeywords) {
                    // 不区分大小写匹配
                    if (combinedLower.contains(keyword.toLowerCase())) {
                        return "inject";
                    }
                }
                // 有 trigger 配置但关键词都不匹配 → 跳过
                return "skip";
            }
        }

        // 4. 既没有 always 也没有 trigger → 默认注入
        return "inject";
    }
}