package com.example.aadlplugin.agent.architecture;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

public class AadlArchitecturePrompt {

    private Map<String, Object> rulesConfig;

    public AadlArchitecturePrompt() {
        loadRules();
    }

    @SuppressWarnings("unchecked")
    private void loadRules() {
        try {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("architecture-rules.yml")) {
                if (is == null) {
                    throw new RuntimeException("architecture-rules.yml not found in classpath");
                }
                Yaml yaml = new Yaml();
                this.rulesConfig = yaml.load(is);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load architecture rules file", e);
        }
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String requirementsJson) {
        return buildPrompt(requirementsJson, null);
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String requirementsJson, String ragContext) {
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

        prompt.append("【AADL组件类型定义】\n");
        prompt.append(rulesConfig.get("component_types"));
        prompt.append("\n\n");

        prompt.append("【输出格式要求】\n");
        prompt.append(rulesConfig.get("output_format"));
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
        prompt.append(requirementsJson);
        prompt.append("\n\n");

        prompt.append(rulesConfig.get("output_instruction"));

        return prompt.toString();
    }
}