package com.example.aadlagent.agent.requirement;

import org.springframework.core.io.ClassPathResource;
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
            ClassPathResource resource = new ClassPathResource("requirement-rules.yml");
            try (InputStream is = resource.getInputStream()) {
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

    @SuppressWarnings("unchecked")
    public String buildPromptWithGlobalContext(String content, String sectionId) {
        StringBuilder prompt = new StringBuilder();

        prompt.append(rulesConfig.get("system_prompt"));
        prompt.append("\n\n");

        prompt.append("【分块处理模式】\n");
        prompt.append("你正在处理文档的一个分块。输入内容的顶部已注入全局上下文卡片，请仔细阅读并参考。\n");
        prompt.append("当前分块ID: ").append(sectionId).append("\n\n");

        prompt.append("【强制要求】\n");
        prompt.append("1. 每个提取的需求条目必须显式标注 global_ref 字段，列出该需求受哪些全局锚点影响\n");
        prompt.append("2. global_ref 字段是字符串数组，每个元素是全局上下文卡片中的锚点ID（如 CONST-001, PARAM-002）\n");
        prompt.append("3. 对于\"参见X章\"类引用，原样照抄原文，禁止自行解释\n");
        prompt.append("4. 如果需求不受任何全局锚点影响，global_ref 设为空数组 []\n\n");

        prompt.append("【输出格式】\n");
        prompt.append("[{\n");
        prompt.append("  \"requirementId\": \"\",\n");
        prompt.append("  \"title\": \"需求标题\",\n");
        prompt.append("  \"description\": \"需求详细描述\",\n");
        prompt.append("  \"priority\": \"高/中/低\",\n");
        prompt.append("  \"acceptanceCriteria\": [\"验收标准1\", \"验收标准2\"],\n");
        prompt.append("  \"dependencies\": [\"依赖项1\"],\n");
        prompt.append("  \"globalRef\": [\"CONST-001\", \"PARAM-003\"]\n");
        prompt.append("}]\n\n");

        prompt.append("【格式示例】\n");
        prompt.append("[{\n");
        prompt.append("  \"requirementId\": \"\",\n");
        prompt.append("  \"title\": \"数据采集频率要求\",\n");
        prompt.append("  \"description\": \"系统必须以100Hz的频率采集传感器数据\",\n");
        prompt.append("  \"priority\": \"高\",\n");
        prompt.append("  \"acceptanceCriteria\": [\"采样频率达到100Hz\", \"数据精度达到0.1%\"],\n");
        prompt.append("  \"dependencies\": [\"章节: SEC-001\"],\n");
        prompt.append("  \"globalRef\": [\"CONST-001\", \"PARAM-002\"]\n");
        prompt.append("}]\n\n");

        prompt.append("【严格规则】\n");
        prompt.append("1. 只提取当前分块中的需求，不要处理其他分块的内容\n");
        prompt.append("2. 保持需求的原始语义，不要添加、删除或修改含义\n");
        prompt.append("3. 必须输出有效的JSON数组格式\n");
        prompt.append("4. global_ref 字段必须正确引用全局上下文卡片中的锚点ID\n");
        prompt.append("5. 不输出任何解释性文字，只输出JSON\n\n");

        prompt.append("【输入内容】\n");
        prompt.append(content);
        prompt.append("\n\n");

        prompt.append("【输出指令】\n");
        prompt.append("请提取当前分块中的需求条目，严格按照上述格式输出JSON。");

        return prompt.toString();
    }
}