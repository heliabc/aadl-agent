package com.example.aadlplugin.agent.requirement;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

public class RequirementPrompt {

    private Map<String, Object> rulesConfig;

    public RequirementPrompt() {
        loadRules();
    }

    @SuppressWarnings("unchecked")
    private void loadRules() {
        try {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("requirement-rules.yml")) {
                if (is == null) {
                    throw new RuntimeException("requirement-rules.yml not found in classpath");
                }
                Yaml yaml = new Yaml();
                this.rulesConfig = yaml.load(is);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load requirement rules file", e);
        }
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String requirementDoc) {
        return buildPrompt(requirementDoc, null);
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String requirementDoc, String ragContext) {
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

        prompt.append("【输出格式】\n");
        prompt.append(rulesConfig.get("output_format"));
        prompt.append("\n\n");

        prompt.append("【格式示例】\n");
        prompt.append(rulesConfig.get("format_example"));
        prompt.append("\n\n");

        prompt.append("【严格规则】\n");
        prompt.append(rulesConfig.get("strict_rules"));
        prompt.append("\n\n");

        Map<String, Object> example = (Map<String, Object>) rulesConfig.get("example");
        prompt.append("【").append(example.get("title")).append("】\n");
        prompt.append(example.get("description")).append("\n");
        prompt.append("示例输入：\n");
        prompt.append(example.get("input"));
        prompt.append("\n\n示例输出：\n");
        prompt.append(example.get("output"));
        prompt.append("\n\n");

        prompt.append(rulesConfig.get("input_section"));
        prompt.append("\n\n");

        prompt.append(rulesConfig.get("input_label"));
        prompt.append("\n");
        prompt.append(requirementDoc);
        prompt.append("\n\n");

        prompt.append(rulesConfig.get("output_instruction"));

        return prompt.toString();
    }
}