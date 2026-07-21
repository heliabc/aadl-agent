package com.example.aadlagent.agent.aadl;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class AadlErrorParserPrompt {

    private Map<String, Object> rulesConfig;

    public AadlErrorParserPrompt() {
        loadRules();
    }

    @SuppressWarnings("unchecked")
    private void loadRules() {
        try {
            ClassPathResource resource = new ClassPathResource("aadl-error-parser-rules.yml");
            try (InputStream is = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                this.rulesConfig = yaml.load(is);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load AADL error parser rules file", e);
        }
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String aadlContent, String rawErrors) {
        return buildPrompt(aadlContent, rawErrors, null);
    }

    @SuppressWarnings("unchecked")
    public String buildPrompt(String aadlContent, String rawErrors, String ragContext) {
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

        prompt.append("【AADL代码】\n");
        prompt.append("```aadl\n");
        prompt.append(aadlContent);
        prompt.append("\n```\n\n");

        prompt.append("【原始错误信息】\n");
        prompt.append(rawErrors);
        prompt.append("\n\n");

        Map<String, Object> errorSchema = (Map<String, Object>) rulesConfig.get("error_schema");
        prompt.append("【错误输出格式】\n");
        prompt.append(errorSchema.get("description"));
        prompt.append("\n\n");
        prompt.append("输出JSON格式示例:\n");
        prompt.append(errorSchema.get("example"));
        prompt.append("\n\n");

        List<Map<String, Object>> errorTypes = (List<Map<String, Object>>) rulesConfig.get("error_types");
        prompt.append("【常见错误类型】\n");
        for (Map<String, Object> type : errorTypes) {
            prompt.append("--- ").append(type.get("type")).append(" ---\n");
            prompt.append("描述: ").append(type.get("description")).append("\n");
            prompt.append("示例: ").append(type.get("example")).append("\n\n");
        }

        prompt.append(rulesConfig.get("output_instruction"));

        return prompt.toString();
    }
}
