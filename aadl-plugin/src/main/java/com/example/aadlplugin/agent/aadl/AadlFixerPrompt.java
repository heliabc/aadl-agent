package com.example.aadlplugin.agent.aadl;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class AadlFixerPrompt {

    private Map<String, Object> rulesConfig;

    public AadlFixerPrompt() {
        loadRules();
    }

    @SuppressWarnings("unchecked")
    private void loadRules() {
        try {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("aadl-fixer-rules.yml")) {
                if (is == null) {
                    throw new RuntimeException("aadl-fixer-rules.yml not found in classpath");
                }
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

        prompt.append(rulesConfig.get("system_prompt"));
        prompt.append("\n\n");

        if (ragContext != null && !ragContext.trim().isEmpty()) {
            prompt.append("【参考知识】\n");
            prompt.append(ragContext);
            prompt.append("\n\n");
        }

        prompt.append(rulesConfig.get("task_description"));
        prompt.append("\n\n");

        prompt.append("【待修复的 AADL 代码】\n");
        prompt.append("```aadl\n");
        prompt.append(aadlContent);
        prompt.append("\n```\n\n");

        prompt.append("【检测到的错误列表】\n");
        prompt.append(errors);
        prompt.append("\n\n");

        Map<String, Object> fixGuidelines = (Map<String, Object>) rulesConfig.get("fix_guidelines");
        prompt.append("【修复指南】\n");
        prompt.append(fixGuidelines.get("content"));
        prompt.append("\n\n");

        List<Map<String, Object>> commonFixes = (List<Map<String, Object>>) rulesConfig.get("common_fixes");
        prompt.append("【常见错误修复示例】\n");
        for (Map<String, Object> fix : commonFixes) {
            prompt.append("--- ").append(fix.get("title")).append(" ---\n");
            prompt.append("错误类型: ").append(fix.get("error_type")).append("\n");
            prompt.append("错误示例: ").append(fix.get("error_example")).append("\n");
            prompt.append("修复方案: ").append(fix.get("fix_solution")).append("\n\n");
        }

        prompt.append(rulesConfig.get("output_instruction"));

        return prompt.toString();
    }
}