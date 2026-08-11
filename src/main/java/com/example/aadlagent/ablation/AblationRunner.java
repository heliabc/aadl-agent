package com.example.aadlagent.ablation;

import com.example.aadlagent.agent.aadl.AadlFixerAgent;
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
 * 消融实验运行器
 *
 * 修复流水线模块（每个都是独立模块，可独立消融）：
 * 1. staticAnalysis - 静态语法检测
 * 2. errorParser    - 错误解析 Agent
 * 3. rag            - RAG 检索增强
 * 4. prompt         - Prompt 工程
 * 5. fixer          - Fixer Agent / LLM
 *
 * 消融方式：模块输入直接透传（或传空），不执行该模块逻辑
 */
@Slf4j
@Component
public class AblationRunner {

    private final AadlFixerAgent fixerAgent;
    private final RagService ragService;
    private final AadlReferenceValidator validator;

    private String outputDir = "output/ablation";

    public AblationRunner(AadlFixerAgent fixerAgent, RagService ragService,
                          AadlReferenceValidator validator) {
        this.fixerAgent = fixerAgent;
        this.ragService = ragService;
        this.validator = validator;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * 运行多组消融实验
     */
    public List<List<AblationResult>> run(AblationConfig[] configs, List<AblationCase> cases,
                                           ModelType modelType) {
        List<List<AblationResult>> allResults = new ArrayList<>();

        log.info("========================================");
        log.info("消融实验开始");
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

    private AblationResult runOne(AblationCase tc, AblationConfig cfg, ModelType modelType) {
        AblationResult r = new AblationResult();
        r.setCaseId(tc.getId());
        r.setCaseName(tc.getName());
        r.setSetupLabel(cfg.getLabel());

        long start = System.currentTimeMillis();
        try {
            // 初始错误数
            AadlReferenceValidator.ValidationResult initV = validator.validateSyntax(tc.getBuggyCode());
            r.setInitialErrors(initV.errors.size());

            // 准备 RAG（只有 rag 模块启用时才检索）
            String ragCtx = null;
            if (cfg.rag && ragService != null) {
                try {
                    String query = tc.getErrorsText() + "\n" + tc.getBuggyCode();
                    ragCtx = ragService.getEnhancedContext(query, "aadl");
                } catch (Exception e) {
                    log.warn("    RAG 检索失败: {}", e.getMessage());
                }
            }

            // 调用修复
            String fixed = fixerAgent.fixForAblation(
                    tc.getBuggyCode(), tc.getErrorsText(), ragCtx, modelType, cfg);

            r.setFixedCode(fixed);

            // 最终错误数
            String clean = sanitizeForValidation(fixed);
            AadlReferenceValidator.ValidationResult finalV = validator.validateSyntax(clean);
            r.setFinalErrors(finalV.errors.size());
            r.setRemainingErrors(new ArrayList<>(finalV.errors));
            r.setSuccess(finalV.errors.isEmpty());

        } catch (Exception e) {
            log.error("    失败: {}", e.getMessage());
            r.setFinalErrors(r.getInitialErrors());
            r.setFixedCode(tc.getBuggyCode());
        }

        r.setTimeMs(System.currentTimeMillis() - start);
        return r;
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

    private void saveResults(AblationConfig[] configs, List<AblationCase> cases,
                              List<List<AblationResult>> allResults) throws Exception {
        Files.createDirectories(Paths.get(outputDir));
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        // 1. 汇总报告
        String report = outputDir + "/report_" + ts + ".txt";
        try (PrintWriter pw = new PrintWriter(new FileWriter(report))) {
            pw.println("============================================================");
            pw.println("           AADL 修复系统 - 消融实验报告");
            pw.println("============================================================");
            pw.println("时间: " + new Date());
            pw.println("用例数: " + cases.size());
            pw.println("实验组数: " + configs.length);
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
            pw.println();

            // 按难度
            pw.println("【按难度分组成功率】");
            pw.println();
            List<String> diffs = Arrays.asList("easy", "medium", "hard");
            pw.printf("%-22s", "实验组");
            for (String d : diffs) pw.printf(" %8s", d);
            pw.println();
            pw.println("-".repeat(22 + diffs.size() * 9));

            for (int si = 0; si < configs.length; si++) {
                pw.printf("%-22s", configs[si].getLabel());
                List<AblationResult> rs = allResults.get(si);
                for (String d : diffs) {
                    List<AblationResult> dr = new ArrayList<>();
                    for (int ci = 0; ci < cases.size(); ci++) {
                        if (d.equals(cases.get(ci).getDifficulty())) dr.add(rs.get(ci));
                    }
                    if (!dr.isEmpty()) {
                        long s = dr.stream().filter(AblationResult::isSuccess).count();
                        pw.printf(" %7.1f%%", (double) s / dr.size() * 100);
                    } else {
                        pw.printf(" %8s", "-");
                    }
                }
                pw.println();
            }
        }
        log.info("报告已保存: {}", report);

        // 2. CSV
        String csv = outputDir + "/data_" + ts + ".csv";
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
                String f = codeDir + "/" + r.getCaseId() + "_fixed.aadl";
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
