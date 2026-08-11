package com.example.aadlagent.ablation;

import com.example.aadlagent.agent.aadl.AadlFixerAgent;
import com.example.aadlagent.agent.aadl.AadlGeneratorAgent;
import com.example.aadlagent.agent.architecture.AadlArchitectureAgent;
import com.example.aadlagent.agent.module.ModuleAnalysisAgent;
import com.example.aadlagent.agent.requirement.RequirementAgent;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.rag.RagService;
import com.example.aadlagent.util.AadlReferenceValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 端到端消融实验运行器
 *
 * 完整流水线：
 *   requirementAgent → architectureAgent → moduleAgent → generatorAgent → staticAnalysis → fixerAgent
 *   (RAG+Prompt)        (RAG+Prompt)        (RAG+Prompt)     (RAG+Prompt)     (静态分析)    (RAG+Prompt)
 *
 * 输入：需求文档文本
 * 输出：修复后的 AADL 代码
 *
 * 消融方式：
 * - Agent 消融：跳过该 Agent，输入直接透传到下一步（格式可能不匹配，看下游能处理多少）
 * - RAG 消融：所有 Agent 都不传 ragContext
 * - Prompt 消融：所有 Agent 都用极简 prompt（在各 Agent 内部判断）
 */
@Slf4j
@Component
public class EndToEndAblationRunner {

    private final RequirementAgent requirementAgent;
    private final AadlArchitectureAgent architectureAgent;
    private final ModuleAnalysisAgent moduleAgent;
    private final AadlGeneratorAgent generatorAgent;
    private final AadlFixerAgent fixerAgent;
    private final RagService ragService;
    private final AadlReferenceValidator validator;

    private String outputDir = "output/ablation-e2e";

    public EndToEndAblationRunner(RequirementAgent requirementAgent,
                                   AadlArchitectureAgent architectureAgent,
                                   ModuleAnalysisAgent moduleAgent,
                                   AadlGeneratorAgent generatorAgent,
                                   AadlFixerAgent fixerAgent,
                                   RagService ragService,
                                   AadlReferenceValidator validator) {
        this.requirementAgent = requirementAgent;
        this.architectureAgent = architectureAgent;
        this.moduleAgent = moduleAgent;
        this.generatorAgent = generatorAgent;
        this.fixerAgent = fixerAgent;
        this.ragService = ragService;
        this.validator = validator;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * 运行端到端消融实验
     */
    public List<List<AblationResult>> run(AblationConfig[] configs, List<AblationCase> cases,
                                           ModelType modelType) {
        List<List<AblationResult>> allResults = new ArrayList<>();

        log.info("========================================");
        log.info("端到端消融实验开始");
        log.info("实验组数: {}, 测试用例数: {}, 模型: {}", configs.length, cases.size(), modelType);
        log.info("========================================");

        for (int si = 0; si < configs.length; si++) {
            AblationConfig cfg = configs[si];
            log.info("\n--- [{}/{}] {} ---", si + 1, configs.length, cfg.getLabel());

            List<AblationResult> results = new ArrayList<>();
            for (int ci = 0; ci < cases.size(); ci++) {
                AblationCase tc = cases.get(ci);
                log.info("  [{}/{}] {} - {}...", ci + 1, cases.size(), tc.getId(), tc.getName());

                AblationResult result = runOne(tc, cfg, modelType);
                results.add(result);

                log.info("    错误 {}→{} ({})", result.getInitialErrors(), result.getFinalErrors(),
                        result.isSuccess() ? "✓" : "✗");
            }
            allResults.add(results);
        }

        try {
            saveResults(configs, cases, allResults);
        } catch (Exception e) {
            log.error("保存结果失败", e);
        }

        return allResults;
    }

    /**
     * 运行单个用例
     */
    public AblationResult runOne(AblationCase tc, AblationConfig cfg, ModelType modelType) {
        AblationResult r = new AblationResult();
        r.setCaseId(tc.getId());
        r.setCaseName(tc.getName());
        r.setSetupLabel(cfg.getLabel());

        long start = System.currentTimeMillis();
        try {
            String requirementDoc = tc.getRequirementDoc() != null
                    ? tc.getRequirementDoc()
                    : tc.getBuggyCode(); // 如果没有需求文档，用 buggy code 凑

            // ===== 模块1：需求Agent =====
            // 输入：需求文档
            // 输出：需求 JSON
            // 消融：需求文档直接当 JSON 透传
            String requirementsJson;
            if (cfg.requirementAgent) {
                String rag = cfg.rag ? getRag(requirementDoc, "requirement") : null;
                requirementsJson = callAgent(requirementAgent, requirementDoc, null, rag, modelType, !cfg.prompt);
            } else {
                requirementsJson = requirementDoc; // 透传
                log.debug("  [消融] requirementAgent 跳过，需求文档直接透传");
            }
            if (requirementsJson == null) requirementsJson = "";

            // ===== 模块2：架构Agent =====
            // 输入：需求 JSON
            // 输出：架构 JSON
            // 消融：需求 JSON 当架构 JSON 透传
            String architectureJson;
            if (cfg.architectureAgent) {
                String rag = cfg.rag ? getRag(requirementsJson, "architecture") : null;
                architectureJson = callAgent(architectureAgent, requirementsJson, null, rag, modelType, !cfg.prompt);
            } else {
                architectureJson = requirementsJson; // 透传
                log.debug("  [消融] architectureAgent 跳过，需求 JSON 直接透传");
            }
            if (architectureJson == null) architectureJson = "";

            // ===== 模块3：模块Agent =====
            // 输入：需求 JSON (content) + 架构 JSON (metadata)
            // 输出：模块 JSON
            // 消融：架构 JSON 当模块 JSON 透传
            String modulesJson;
            if (cfg.moduleAgent) {
                String rag = cfg.rag ? getRag(requirementsJson + "\n" + architectureJson, "module") : null;
                modulesJson = callAgent(moduleAgent, requirementsJson, architectureJson, rag, modelType, !cfg.prompt);
            } else {
                modulesJson = architectureJson; // 透传
                log.debug("  [消融] moduleAgent 跳过，架构 JSON 直接透传");
            }
            if (modulesJson == null) modulesJson = "";

            // ===== 模块4：生成Agent =====
            // 输入：架构 JSON (content) + 模块 JSON (metadata)
            // 输出：AADL 代码
            // 消融：输出一个最简 AADL 骨架
            String aadlCode;
            if (cfg.generatorAgent) {
                String rag = cfg.rag ? getRag(architectureJson + "\n" + modulesJson, "aadl") : null;
                aadlCode = callAgent(generatorAgent, architectureJson, modulesJson, rag, modelType, !cfg.prompt);
            } else {
                aadlCode = buildMinimalAadl(tc.getName());
                log.debug("  [消融] generatorAgent 跳过，使用最简 AADL 骨架");
            }
            if (aadlCode == null) aadlCode = "";

            // ===== 模块5：静态语法分析 =====
            // （在生成后和修复前各做一次，这里生成后做一次）
            if (cfg.staticAnalysis) {
                AadlReferenceValidator.ValidationResult vr = validator.validateSyntax(aadlCode);
                if (vr.fixedContent != null) {
                    aadlCode = vr.fixedContent;
                }
                log.debug("  静态分析后，剩余错误: {}", vr.errors.size());
            }

            // 记录初始错误数（生成后/静态分析后）
            AadlReferenceValidator.ValidationResult initV = validator.validateSyntax(aadlCode);
            r.setInitialErrors(initV.errors.size());

            // ===== 模块6：修复Agent =====
            // 消融 fixerAgent：直接返回生成后的代码
            String fixedCode;
            if (!cfg.fixerAgent) {
                fixedCode = aadlCode;
                log.debug("  [消融] fixerAgent 跳过，直接返回生成代码");
            } else {
                String ragCtx = cfg.rag
                        ? getRag("修复以下AADL代码\n错误列表见下一步\n" + aadlCode, "aadl")
                        : null;
                // 用 fixForAblation 方法（已支持 rag/prompt/staticAnalysis 开关）
                // 注意：staticAnalysis 在上面已经做过了，fixer 内部再做一次也没关系
                // 为了准确，这里直接调 fixForAblation，它内部会自己处理 staticAnalysis 开关
                fixedCode = fixerAgent.fixForAblation(aadlCode, null, ragCtx, modelType, cfg);
            }

            r.setFixedCode(fixedCode);

            // 最终错误数
            String clean = sanitizeForValidation(fixedCode);
            AadlReferenceValidator.ValidationResult finalV = validator.validateSyntax(clean);
            r.setFinalErrors(finalV.errors.size());
            r.setRemainingErrors(new ArrayList<>(finalV.errors));
            r.setSuccess(finalV.errors.isEmpty());

        } catch (Exception e) {
            log.error("    失败: {}", e.getMessage(), e);
            r.setFinalErrors(r.getInitialErrors() > 0 ? r.getInitialErrors() : 999);
            r.setFixedCode(tc.getBuggyCode() != null ? tc.getBuggyCode() : "");
        }

        r.setTimeMs(System.currentTimeMillis() - start);
        return r;
    }

    // ==================== 辅助方法 ====================

    private String getRag(String query, String type) {
        if (ragService == null) return null;
        try {
            return ragService.getEnhancedContext(query, type);
        } catch (Exception e) {
            log.warn("    RAG({}) 检索失败: {}", type, e.getMessage());
            return null;
        }
    }

    private String callAgent(com.example.aadlagent.agent.Agent<
            com.example.aadlagent.agent.AgentInput,
            com.example.aadlagent.agent.AgentOutput> agent,
                             String content, String metadata, String ragContext,
                             ModelType modelType, boolean minimalPrompt) {
        try {
            com.example.aadlagent.agent.AgentInput input =
                    com.example.aadlagent.agent.AgentInput.builder()
                            .sessionId("ablation-" + System.currentTimeMillis())
                            .content(content)
                            .metadata(metadata)
                            .ragContext(ragContext)
                            .modelType(modelType)
                            .minimalPrompt(minimalPrompt)
                            .build();
            com.example.aadlagent.agent.AgentOutput output = agent.execute(input);
            if (output.isSuccess()) {
                return output.getContent();
            } else {
                log.warn("    {} 执行失败: {}", agent.getAgentName(), output.getErrorMessage());
                return content; // 失败时透传输入
            }
        } catch (Exception e) {
            log.warn("    {} 异常: {}", agent.getAgentName(), e.getMessage());
            return content;
        }
    }

    private String buildMinimalAadl(String name) {
        String safeName = name.replaceAll("[^a-zA-Z0-9]", "_");
        if (safeName.isEmpty() || Character.isDigit(safeName.charAt(0))) {
            safeName = "System_" + safeName;
        }
        return "package " + safeName + "_Pkg;\n" +
                "public system " + safeName + "\n" +
                "end " + safeName + ";\n" +
                "\n" +
                "system implementation " + safeName + ".impl\n" +
                "end " + safeName + ".impl;\n" +
                "end " + safeName + "_Pkg;\n";
    }

    private String sanitizeForValidation(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : code.split("\n")) {
            if (line.matches("\\s*--\\s*\\[(错误|警告|修复|ERROR|WARNING)\\].*")) continue;
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== 结果保存 ====================

    private void saveResults(AblationConfig[] configs, List<AblationCase> cases,
                              List<List<AblationResult>> allResults) throws Exception {
        Files.createDirectories(Paths.get(outputDir));
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        // 1. 汇总报告
        String report = outputDir + "/e2e_report_" + ts + ".txt";
        try (PrintWriter pw = new PrintWriter(new FileWriter(report))) {
            pw.println("============================================================");
            pw.println("       AADL 端到端生成修复系统 - 消融实验报告");
            pw.println("============================================================");
            pw.println("时间: " + new Date());
            pw.println("用例数: " + cases.size());
            pw.println("实验组数: " + configs.length);
            pw.println();
            pw.println("流水线: requirementAgent → architectureAgent → moduleAgent");
            pw.println("        → generatorAgent → staticAnalysis → fixerAgent");
            pw.println();

            // 总览
            pw.println("【总览对比】");
            pw.println();
            pw.printf("%-22s %6s %6s %8s %8s %10s%n",
                    "实验组", "成功", "总数", "成功率", "修复率", "耗时ms");
            pw.println("-".repeat(65));
            for (int i = 0; i < configs.length; i++) {
                List<AblationResult> rs = allResults.get(i);
                long succ = rs.stream().filter(AblationResult::isSuccess).count();
                double avgFix = rs.stream().mapToDouble(AblationResult::getFixRate).average().orElse(0);
                double avgT = rs.stream().mapToLong(AblationResult::getTimeMs).average().orElse(0);
                pw.printf("%-22s %6d %6d %7.1f%% %7.1f%% %10.0f%n",
                        configs[i].getLabel(), succ, rs.size(),
                        (double) succ / rs.size() * 100, avgFix * 100, avgT);
            }
            pw.println();

            // 各用例对比
            pw.println("【各用例详细对比】");
            pw.println();
            pw.printf("%-6s %-18s", "ID", "用例");
            for (AblationConfig c : configs) pw.printf(" %-12s", truncate(c.getLabel(), 12));
            pw.println();
            pw.println("-".repeat(24 + configs.length * 13));

            for (int ci = 0; ci < cases.size(); ci++) {
                AblationCase tc = cases.get(ci);
                pw.printf("%-6s %-18s", tc.getId(), truncate(tc.getName(), 18));
                for (int si = 0; si < configs.length; si++) {
                    AblationResult r = allResults.get(si).get(ci);
                    String s = r.isSuccess()
                            ? String.format("✓%d→%d", r.getInitialErrors(), r.getFinalErrors())
                            : String.format("✗%d→%d", r.getInitialErrors(), r.getFinalErrors());
                    pw.printf(" %-12s", s);
                }
                pw.println();
            }
        }
        log.info("报告已保存: {}", report);

        // 2. CSV
        String csv = outputDir + "/e2e_data_" + ts + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(csv))) {
            pw.println("CaseID,CaseName,Category,Difficulty,Setup,InitialErrors,FinalErrors,FixRate,Success,TimeMs");
            for (int si = 0; si < configs.length; si++) {
                for (int ci = 0; ci < cases.size(); ci++) {
                    AblationCase tc = cases.get(ci);
                    AblationResult r = allResults.get(si).get(ci);
                    pw.printf("%s,%s,%s,%s,%s,%d,%d,%.4f,%s,%d%n",
                            tc.getId(), esc(tc.getName()), tc.getCategory(), tc.getDifficulty(),
                            configs[si].getLabel(),
                            r.getInitialErrors(), r.getFinalErrors(), r.getFixRate(),
                            r.isSuccess() ? "YES" : "NO", r.getTimeMs());
                }
            }
        }
        log.info("CSV 已保存: {}", csv);

        // 3. 修复后代码
        for (int si = 0; si < configs.length; si++) {
            String codeDir = outputDir + "/" + configs[si].getLabel() + "_" + ts;
            Files.createDirectories(Paths.get(codeDir));
            for (int ci = 0; ci < cases.size(); ci++) {
                AblationResult r = allResults.get(si).get(ci);
                String f = codeDir + "/" + r.getCaseId() + ".aadl";
                try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
                    pw.print(r.getFixedCode());
                }
            }
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String esc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
