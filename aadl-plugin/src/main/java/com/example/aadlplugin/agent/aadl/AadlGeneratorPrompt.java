package com.example.aadlplugin.agent.aadl;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class AadlGeneratorPrompt {

    private Map<String, Object> rulesConfig;

    public AadlGeneratorPrompt() {
        loadRules();
    }

    @SuppressWarnings("unchecked")
    private void loadRules() {
        try {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("aadl-rules.yml")) {
                if (is == null) {
                    throw new RuntimeException("aadl-rules.yml not found in classpath");
                }
                Yaml yaml = new Yaml();
                this.rulesConfig = yaml.load(is);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load AADL rules file", e);
        }
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String architectureJson, String modulesJson) {
        return buildPrompt(architectureJson, modulesJson, null);
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String architectureJson, String modulesJson, String ragContext) {
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

        Map<String, Object> global = (Map<String, Object>) rulesConfig.get("global");
        prompt.append("【全局规则】\n");
        prompt.append(global.get("alwaysRules"));
        prompt.append("\n\n");

        prompt.append("【组件模板规则】\n");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) rulesConfig.get("rules");
        for (Map<String, Object> rule : rules) {
            prompt.append("--- ").append(rule.get("title")).append(" ---\n");
            prompt.append(rule.get("content"));
            prompt.append("\n\n");
        }

        Map<String, Object> order = (Map<String, Object>) rulesConfig.get("order");
        prompt.append("【生成顺序】\n");
        prompt.append(order.get("content"));
        prompt.append("\n\n");

        Map<String, Object> forbidden = (Map<String, Object>) rulesConfig.get("forbidden");
        prompt.append("【禁止规则】\n");
        prompt.append(forbidden.get("content"));
        prompt.append("\n\n");

        Map<String, Object> example = (Map<String, Object>) rulesConfig.get("example");
        prompt.append("【").append(example.get("title")).append("】\n");
        prompt.append(example.get("description")).append("\n");
        prompt.append("```\n");
        prompt.append(example.get("content"));
        prompt.append("\n```\n\n");

        prompt.append(rulesConfig.get("input_section"));
        prompt.append("\n\n");

        prompt.append(rulesConfig.get("architecture_label"));
        prompt.append("\n");
        prompt.append(architectureJson);
        prompt.append("\n\n");

        prompt.append(rulesConfig.get("modules_label"));
        prompt.append("\n");
        prompt.append(modulesJson);
        prompt.append("\n\n");

        prompt.append(rulesConfig.get("output_instruction"));

        return prompt.toString();
    }
}