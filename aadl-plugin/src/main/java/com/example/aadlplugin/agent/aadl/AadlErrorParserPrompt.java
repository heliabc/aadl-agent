package com.example.aadlplugin.agent.aadl;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class AadlErrorParserPrompt {

    private static final String RULES_FILE = "/aadl-error-parser-rules.yml";

    private Map<String, Object> rules;

    public AadlErrorParserPrompt() {
        loadRules();
    }

    private void loadRules() {
        try (InputStream is = getClass().getResourceAsStream(RULES_FILE)) {
            if (is != null) {
                Yaml yaml = new Yaml();
                rules = yaml.load(is);
            } else {
                System.err.println("警告: 无法加载规则文件 " + RULES_FILE);
                rules = Map.of();
            }
        } catch (Exception e) {
            System.err.println("加载规则文件失败: " + e.getMessage());
            rules = Map.of();
        }
    }

    public String buildPrompt(String aadlContent, String rawErrors, String ragContext) {
        StringBuilder prompt = new StringBuilder();

        String systemPrompt = (String) rules.getOrDefault("system_prompt", 
            "你是一个专业的AADL错误解析器。请分析原始错误信息并输出结构化的JSON格式。");
        prompt.append(systemPrompt).append("\n\n");

        String taskDescription = (String) rules.getOrDefault("task_description", 
            "请分析以下AADL代码和原始错误信息，输出结构化的错误列表。");
        prompt.append(taskDescription).append("\n\n");

        Map<String, Object> errorSchema = (Map<String, Object>) rules.get("error_schema");
        if (errorSchema != null) {
            prompt.append("【错误输出格式要求】\n");
            prompt.append("请输出JSON格式，包含以下字段：\n");
            List<Map<String, String>> fields = (List<Map<String, String>>) errorSchema.get("fields");
            if (fields != null) {
                for (Map<String, String> field : fields) {
                    prompt.append("- ").append(field.get("name"))
                          .append(" (").append(field.get("type")).append("): ")
                          .append(field.get("description")).append("\n");
                }
            }
        }

        List<Map<String, String>> errorTypes = (List<Map<String, String>>) rules.get("error_types");
        if (errorTypes != null) {
            prompt.append("\n【错误类型参考】\n");
            for (Map<String, String> type : errorTypes) {
                prompt.append("- ").append(type.get("code"))
                      .append(": ").append(type.get("description")).append("\n");
            }
        }

        String outputInstruction = (String) rules.getOrDefault("output_instruction", 
            "请只输出JSON格式的错误列表，不要包含其他解释。");
        prompt.append("\n").append(outputInstruction).append("\n\n");

        if (ragContext != null && !ragContext.isEmpty()) {
            prompt.append("【参考知识】\n");
            prompt.append(ragContext).append("\n\n");
        }

        prompt.append("【AADL代码】\n");
        prompt.append("```aadl\n");
        prompt.append(aadlContent).append("\n");
        prompt.append("```\n\n");

        prompt.append("【原始错误信息】\n");
        prompt.append(rawErrors).append("\n");

        return prompt.toString();
    }
}
