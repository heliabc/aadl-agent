package com.example.aadlagent.agent.aadl;

import org.springframework.core.io.ClassPathResource;
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

        // 6. 错误信息 / 用户修正意图
        if (errors != null && !errors.trim().isEmpty()) {
            prompt.append("【检测到的错误信息】\n");
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

        // 8. 常见错误修复示例
        List<Map<String, Object>> commonFixes = (List<Map<String, Object>>) rulesConfig.get("common_fixes");
        prompt.append("【常见错误修复示例】\n");
        for (Map<String, Object> fix : commonFixes) {
            prompt.append("--- ").append(fix.get("title")).append(" ---\n");
            prompt.append("错误类型: ").append(fix.get("error_type")).append("\n");
            prompt.append("错误示例: ").append(fix.get("error_example")).append("\n");
            prompt.append("修复方案: ").append(fix.get("fix_solution")).append("\n\n");
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
}