package com.example.aadlagent.agent.aadl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class AadlFixerPrompt {

    private Map<String, Object> rulesConfig;

    public AadlFixerPrompt() {
        loadRules();
    }

    @SuppressWarnings("unchecked")
    private void loadRules() {
        try {
            ClassPathResource resource = new ClassPathResource("aadl-fixer-rules.yml");
            try (InputStream is = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                this.rulesConfig = yaml.load(is);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load AADL fixer rules file", e);
        }
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String aadlContent, String errors) {
        return buildPrompt(aadlContent, errors, null);
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String aadlContent, String errors, String ragContext) {
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

        // 5. 待修复的 AADL 代码
        prompt.append("【待修复的 AADL 代码】\n");
        prompt.append("```aadl\n");
        prompt.append(aadlContent);
        prompt.append("\n```\n\n");

        // 6. 错误信息 / 用户修复指令
        if (errors != null && !errors.trim().isEmpty()) {
            prompt.append("【用户输入（错误信息或修复指令）】\n");
            prompt.append(errors);
            prompt.append("\n\n");
        } else {
            prompt.append("【用户修正意图】\n");
            prompt.append("用户要求对代码进行修正或改进，请根据代码内容分析需要改进的地方。\n\n");
        }

        // 7. 修复指南
        Map<String, Object> fixGuidelines = (Map<String, Object>) rulesConfig.get("fix_guidelines");
        prompt.append("【修复指南】\n");
        prompt.append(fixGuidelines.get("content"));
        prompt.append("\n\n");

        // 8. 修复规则（按错误关键词动态注入，减少小模型的 prompt 负担）
        Object staticFixesObj = rulesConfig.get("static_analysis_fixes");
        if (staticFixesObj instanceof List) {
            List<Map<String, Object>> staticFixes = (List<Map<String, Object>>) staticFixesObj;
            String errorsForMatching = errors != null ? errors : "";

            List<Map<String, Object>> injectedRules = new ArrayList<>();
            for (Map<String, Object> rule : staticFixes) {
                if (shouldInjectFixRule(rule, errorsForMatching)) {
                    injectedRules.add(rule);
                }
            }

            // 回退策略：如果没有匹配到任何规则，注入全部
            if (injectedRules.isEmpty() && !staticFixes.isEmpty()) {
                injectedRules = staticFixes;
                log.info("修复规则动态过滤：无关键词匹配，回退注入全部 {} 条", staticFixes.size());
            } else {
                log.info("修复规则动态过滤：注入 {} 条，跳过 {} 条（共 {} 条）",
                        injectedRules.size(), staticFixes.size() - injectedRules.size(), staticFixes.size());
            }

            prompt.append("【修复规则】\n");
            for (Map<String, Object> rule : injectedRules) {
                prompt.append("--- ").append(rule.get("id")).append(": ").append(rule.get("title")).append(" ---\n");
                prompt.append("错误特征: ").append(rule.get("error_pattern")).append("\n");
                prompt.append("修复策略: ").append(rule.get("fix_strategy")).append("\n\n");
            }
        }

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
     * 决定单条修复规则是否注入 prompt。
     *
     * 判断逻辑：
     * 1. 没有 trigger 配置 → 默认注入
     * 2. trigger.anyKeywords：错误文本中包含任一关键词 → 注入
     * 3. 有 trigger 但关键词都不匹配 → 跳过
     *
     * @param rule           规则配置
     * @param errorsText     错误信息文本
     * @return true=注入, false=跳过
     */
    @SuppressWarnings("unchecked")
    private boolean shouldInjectFixRule(Map<String, Object> rule, String errorsText) {
        Object trigger = rule.get("trigger");
        if (!(trigger instanceof Map)) {
            return true; // 没有 trigger 配置，默认注入
        }
        Map<String, Object> triggerMap = (Map<String, Object>) trigger;
        Object anyKeywords = triggerMap.get("anyKeywords");
        if (!(anyKeywords instanceof List)) {
            return true; // 没有 anyKeywords 配置，默认注入
        }
        List<String> keywords = (List<String>) anyKeywords;
        String errorsLower = errorsText.toLowerCase();
        for (String keyword : keywords) {
            if (errorsLower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建极简 prompt（消融 prompt 模块时使用）
     * 只包含最基本的角色定义、代码和修复指令，不包含任何规则/示例/指南等知识
     */
    public String buildMinimalPrompt(String aadlContent, String errors) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个 AADL 代码修复助手。请修复下面的 AADL 代码中的问题。\n\n");

        prompt.append("AADL 代码：\n");
        prompt.append("```aadl\n");
        prompt.append(aadlContent);
        prompt.append("\n```\n\n");

        if (errors != null && !errors.trim().isEmpty()) {
            prompt.append("需要修复的问题：\n");
            prompt.append(errors);
            prompt.append("\n\n");
        }

        prompt.append("请直接输出修复后的完整 AADL 代码，用 ```aadl 和 ``` 包裹。");

        return prompt.toString();
    }
}
