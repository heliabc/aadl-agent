package com.example.aadlagent.ablation;

/**
 * 消融实验配置
 *
 * 完整流水线：
 *   requirementAgent → architectureAgent → moduleAgent → generatorAgent → staticAnalysis → fixerAgent
 *   (RAG+Prompt)        (RAG+Prompt)        (RAG+Prompt)     (RAG+Prompt)     (静态分析)     (RAG+Prompt)
 *
 * 消融维度：
 * 1. 去掉某个 Agent（该步跳过，输入直接透传到下一步）
 * 2. 去掉 RAG（所有 Agent 都不用 RAG）
 * 3. 去掉 Prompt（所有 Agent 都用极简 prompt）
 *
 * 也可以组合消融（如：去掉 moduleAgent + 去掉 RAG）
 */
public class AblationConfig {

    public String name = "baseline";

    // ===== Agent 开关（true=启用，false=消融/跳过） =====
    public boolean requirementAgent = true;
    public boolean architectureAgent = true;
    public boolean moduleAgent = true;
    public boolean generatorAgent = true;
    public boolean staticAnalysis = true;   // 静态语法分析（生成后+修复前）
    public boolean fixerAgent = true;

    // ===== RAG / Prompt 全局开关 =====
    public boolean rag = true;      // 所有 Agent 是否使用 RAG
    public boolean prompt = true;   // 所有 Agent 是否使用完整 Prompt（false=极简）

    public AblationConfig() {}

    public AblationConfig(String name) {
        this.name = name;
    }

    /** 基线（全部启用） */
    public static AblationConfig baseline() {
        return new AblationConfig("baseline");
    }

    /** 消融某个 Agent */
    public static AblationConfig ablateAgent(String agent) {
        AblationConfig c = new AblationConfig("no-" + agent);
        switch (agent.toLowerCase()) {
            case "requirement", "req", "需求" -> c.requirementAgent = false;
            case "architecture", "arch", "架构" -> c.architectureAgent = false;
            case "module", "模块" -> c.moduleAgent = false;
            case "generator", "gen", "生成" -> c.generatorAgent = false;
            case "static", "staticanalysis", "静态分析" -> c.staticAnalysis = false;
            case "fixer", "修复" -> c.fixerAgent = false;
            default -> throw new IllegalArgumentException("未知 Agent: " + agent);
        }
        return c;
    }

    /** 消融 RAG */
    public static AblationConfig ablateRag() {
        AblationConfig c = new AblationConfig("no-rag");
        c.rag = false;
        return c;
    }

    /** 消融 Prompt */
    public static AblationConfig ablatePrompt() {
        AblationConfig c = new AblationConfig("no-prompt");
        c.prompt = false;
        return c;
    }

    /** 组合消融 */
    public static AblationConfig ablate(String... items) {
        AblationConfig c = new AblationConfig();
        StringBuilder name = new StringBuilder("ablated");
        for (String item : items) {
            name.append("_no_").append(item);
            switch (item.toLowerCase()) {
                case "requirement", "req" -> c.requirementAgent = false;
                case "architecture", "arch" -> c.architectureAgent = false;
                case "module" -> c.moduleAgent = false;
                case "generator", "gen" -> c.generatorAgent = false;
                case "static", "staticanalysis" -> c.staticAnalysis = false;
                case "fixer" -> c.fixerAgent = false;
                case "rag" -> c.rag = false;
                case "prompt" -> c.prompt = false;
                default -> throw new IllegalArgumentException("未知消融项: " + item);
            }
        }
        c.name = name.toString();
        return c;
    }

    /** 标准 Agent 消融实验组（从完整流水线中去掉一个 Agent） */
    public static AblationConfig[] standardAgentAblations() {
        return new AblationConfig[] {
                baseline(),
                ablateAgent("requirement"),
                ablateAgent("architecture"),
                ablateAgent("module"),
                ablateAgent("generator"),
                ablateAgent("static"),
                ablateAgent("fixer"),
        };
    }

    /** 标准 RAG / Prompt 消融实验组 */
    public static AblationConfig[] standardRagPromptAblations() {
        return new AblationConfig[] {
                baseline(),
                ablateRag(),
                ablatePrompt(),
        };
    }

    /** 全部消融实验组（Agent + RAG/Prompt） */
    public static AblationConfig[] allStandardAblations() {
        AblationConfig[] agentAbl = standardAgentAblations();
        AblationConfig[] rpAbl = standardRagPromptAblations();
        // 合并，去掉重复的 baseline
        AblationConfig[] all = new AblationConfig[agentAbl.length + rpAbl.length - 1];
        System.arraycopy(agentAbl, 0, all, 0, agentAbl.length);
        all[agentAbl.length] = rpAbl[1]; // no-rag
        all[agentAbl.length + 1] = rpAbl[2]; // no-prompt
        return all;
    }

    public String getLabel() {
        return name;
    }
}
