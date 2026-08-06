package com.example.aadlagent.util;

import java.nio.file.*;
import java.util.*;

/**
 * 独立测试入口：直接调用 AadlReferenceValidator 验证并修复 AADL 代码
 * 用法：java com.example.aadlagent.util.ValidatorTestRunner <aadl文件路径>
 *
 * 不依赖 Spring，仅依赖 AadlReferenceValidator + AadlInputParser 两个工具类
 */
public class ValidatorTestRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java ValidatorTestRunner <aadl文件路径>");
            System.err.println("  或: java ValidatorTestRunner <aadl文件路径> <输出文件路径>");
            System.exit(1);
        }

        String inputFile = args[0];
        String outputFile = args.length > 1 ? args[1] : inputFile.replace(".aadl", "_fixed.aadl");

        String aadlCode = new String(Files.readAllBytes(Paths.get(inputFile)));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║      AADL 自动修复综合测试（真实 AadlReferenceValidator）        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("输入文件: " + inputFile);
        System.out.println("输出文件: " + outputFile);
        System.out.println("代码行数: " + aadlCode.split("\n").length);
        System.out.println();

        // 使用空的 ParseResult（真值表为空，仅做结构/语法层面的检测与修复）
        AadlInputParser.ParseResult parseResult = new AadlInputParser.ParseResult();
        parseResult.manifestText = "";

        AadlReferenceValidator validator = new AadlReferenceValidator();
        AadlReferenceValidator.ValidationResult result = validator.validate(aadlCode, parseResult);

        // 输出统计
        System.out.println("═══════════════════ 检测结果 ═══════════════════");
        System.out.printf("  错误数: %d%n", result.errors.size());
        System.out.printf("  警告数: %d%n", result.warnings.size());
        System.out.printf("  修复数: %d%n", result.fixes.size());
        System.out.println();

        if (!result.errors.isEmpty()) {
            System.out.println("═══════════════════ 错误明细 ═══════════════════");
            int i = 1;
            for (String e : result.errors) {
                System.out.printf("  E%02d. %s%n", i++, e);
            }
            System.out.println();
        }

        if (!result.warnings.isEmpty()) {
            System.out.println("═══════════════════ 警告明细 ═══════════════════");
            int i = 1;
            for (String w : result.warnings) {
                System.out.printf("  W%02d. %s%n", i++, w);
            }
            System.out.println();
        }

        if (!result.fixes.isEmpty()) {
            System.out.println("═══════════════════ 修复明细 ═══════════════════");
            int i = 1;
            for (String f : result.fixes) {
                System.out.printf("  F%02d. %s%n", i++, f);
            }
            System.out.println();
        }

        // 保存修复后的代码
        if (result.fixedContent != null && !result.fixedContent.equals(aadlCode)) {
            Files.write(Paths.get(outputFile), result.fixedContent.getBytes());
            System.out.println("修复后的代码已保存到: " + outputFile);
        } else {
            System.out.println("代码无需修复（或修复内容为空）");
        }
    }
}
