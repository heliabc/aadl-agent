package com.example.aadlagent.agent.architecture;

import org.springframework.core.io.ClassPathResource;
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
            ClassPathResource resource = new ClassPathResource("architecture-rules.yml");
            try (InputStream is = resource.getInputStream()) {
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

        // 1. 角色定义
        prompt.append(rulesConfig.get("system_prompt"));
        prompt.append("\n\n");

        // 2. 核心规则（首因效应：放在最前面，模型最先读到）
        Map<String, Object> global = (Map<String, Object>) rulesConfig.get("global");
        if (global != null && global.get("core_rules") != null) {
            prompt.append(global.get("core_rules"));
            prompt.append("\n\n");
        }

        // 3. 任务目标
        prompt.append(rulesConfig.get("task_description"));
        prompt.append("\n\n");

        // 4. RAG 参考知识
        if (ragContext != null && !ragContext.trim().isEmpty()) {
            prompt.append("【参考知识】\n");
            prompt.append(ragContext);
            prompt.append("\n\n");
        }

        // 5. 组件类型定义
        prompt.append("【AADL组件类型定义】\n");
        prompt.append(rulesConfig.get("component_types"));
        prompt.append("\n\n");

        // 6. 输出格式要求
        prompt.append("【输出格式要求】\n");
        prompt.append(rulesConfig.get("output_format"));
        prompt.append("\n\n");

        // 7. 严格规则
        prompt.append("【严格规则】\n");
        prompt.append(rulesConfig.get("strict_rules"));
        prompt.append("\n\n");

        // 8. 示例
        Map<String, Object> example = (Map<String, Object>) rulesConfig.get("example");
        prompt.append("【").append(example.get("title")).append("】\n");
        prompt.append(example.get("description")).append("\n");
        prompt.append("示例输入：\n");
        prompt.append(example.get("input"));
        prompt.append("\n\n示例输出：\n");
        prompt.append(example.get("output"));
        prompt.append("\n\n");

        // 9. 输入清单
        prompt.append(rulesConfig.get("input_section"));
        prompt.append("\n\n");
        prompt.append(rulesConfig.get("input_label"));
        prompt.append("\n");
        prompt.append(requirementsJson);
        prompt.append("\n\n");

        // 10. 自检清单（近因效应：紧贴 output_instruction）
        Map<String, Object> checklist = (Map<String, Object>) rulesConfig.get("checklist");
        if (checklist != null && checklist.get("content") != null) {
            prompt.append(checklist.get("content"));
            prompt.append("\n\n");
        }

        // 11. 输出指令
        prompt.append(rulesConfig.get("output_instruction"));

        return prompt.toString();
    }
}