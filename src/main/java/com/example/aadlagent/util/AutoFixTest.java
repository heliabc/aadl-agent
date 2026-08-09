package com.example.aadlagent.util;

import java.nio.file.*;
import java.util.*;

/**
 * 自动修复功能单元测试（plugin 版本）
 *
 * 用法：java com.example.aadlplugin.util.AutoFixTest [测试文件路径]
 */
public class AutoFixTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String inputFile = args.length > 0
                ? args[0]
                : "output/aadl/test_auto_fix_comprehensive.aadl";

        String aadlCode = new String(Files.readAllBytes(Paths.get(inputFile)));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║           AADL 自动修复功能 - 单元测试                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("测试文件: " + inputFile);
        System.out.println("代码行数: " + aadlCode.split("\n").length);
        System.out.println();

        AadlInputParser.ParseResult parseResult = new AadlInputParser.ParseResult();
        AadlReferenceValidator validator = new AadlReferenceValidator();
        AadlReferenceValidator.ValidationResult result = validator.validate(aadlCode, parseResult);

        String fixed = result.fixedContent;

        System.out.println("──────────────────────────────────────────────────────────");
        System.out.println("  检测结果统计");
        System.out.println("──────────────────────────────────────────────────────────");
        System.out.printf("  错误数: %d%n", result.errors.size());
        System.out.printf("  警告数: %d%n", result.warnings.size());
        System.out.printf("  修复数: %d%n", result.fixes.size());
        System.out.println();

        // ---- 测试1：畸形 end 语句修复 ----
        test("01_畸形end语句_尾逗号应被移除",
                !fixed.contains("end Top_TestFix,"),
                "修复后的代码中不应包含 'end Top_TestFix,'",
                "实际: " + (fixed.contains("end Top_TestFix,") ? "仍存在尾逗号" : "已修复"));

        test("01b_畸形end语句_应保留正确end",
                fixed.contains("end Top_TestFix;"),
                "修复后应包含 'end Top_TestFix;'",
                "实际: " + (fixed.contains("end Top_TestFix;") ? "正确" : "丢失了end语句"));

        // ---- 测试2：data组件features块删除 ----
        test("02_data组件features块应被删除",
                !dataHasFeaturesBlock(fixed, "SensorData"),
                "SensorData 的 features 块应被删除",
                "检查修复后的 data SensorData 声明");

        test("02b_data组件声明应保留",
                fixed.contains("data SensorData") && fixed.contains("end SensorData;"),
                "data 组件声明本身应保留",
                "data SensorData 声明应存在");

        // ---- 测试3：thread的bus access只检测不修复 ----
        test("03_thread_bus_access_仍存在_不自动修复",
                fixed.contains("requires bus access CanBus"),
                "thread 的 bus access 不应被自动修改（只检测不修复）",
                "实际: " + (fixed.contains("requires bus access CanBus") ? "保留（正确）" : "被意外修改"));

        // ---- 测试4：thread impl的connections块删除 ----
        test("04_thread_impl_connections块应被删除",
                !threadImplHasConnections(fixed, "SensorThread"),
                "SensorThread.impl 中的 connections 块应被删除",
                "检查 thread implementation SensorThread.impl 内部");

        // ---- 测试5：features从impl移到type ----
        test("05_features应从impl移到type",
                processTypeHasFeature(fixed, "MainProcess", "sensor_in"),
                "MainProcess 类型声明中应包含 sensor_in feature",
                "检查 process MainProcess 的 features 块");

        test("05b_impl中不应再有features块",
                !processImplHasFeaturesBlock(fixed, "MainProcess"),
                "MainProcess.impl 中不应再包含 features 块",
                "检查 process implementation MainProcess.impl");

        // ---- 测试6：requires data port → in / provides → out ----
        test("06_requires_data_port_改为_in",
                fixed.contains("in data port SensorData"),
                "'requires data port' 应被改为 'in data port'",
                "检查 SerialDevice 的 tx_data feature");

        test("06b_provides_data_port_改为_out",
                fixed.contains("out data port CommandData"),
                "'provides data port' 应被改为 'out data port'",
                "检查 SerialDevice 的 rx_data feature");

        // ---- 测试7：连接操作符修复 ----
        test("07_port连接操作符_<→改为->",
                portConnUsesSingleArrow(fixed, "Conn1"),
                "port 连接 Conn1 的操作符应从 <-> 改为 ->",
                "检查 Conn1 连接行");

        // ---- 测试8：命名冲突修复 ----
        test("08_命名冲突_实例名不应与类型名相同",
                !processorImplHasSameNameInstance(fixed, "MainProcessor"),
                "MainProcessor.impl 中实例名不应再等于类型名",
                "实例名应被重命名（加 _inst 等后缀）");

        // ---- 测试9：reference括号格式修复 ----
        test("09_reference括号_应添加外层列表括号",
                fixed.contains("(reference (can_bus1))"),
                "reference (can_bus1) 应被改为 (reference (can_bus1))",
                "检查 RAM.impl 的 Allowed_Connection_Binding 属性");

        // ---- 测试10：块顺序修复 ----
        test("10_块顺序_subcomponents应在properties之前",
                checkBlockOrder(fixed, "CanBus"),
                "CanBus.impl 中块顺序应为 subcomponents → connections → properties",
                "检查 bus implementation CanBus.impl 的块顺序");

        // ---- 测试11：未声明组件自动补全 ----
        test("11_未声明组件_应被自动补全",
                fixed.contains("device DisplayDevice"),
                "DisplayDevice 组件声明应被自动补全",
                "检查是否有 device DisplayDevice 的类型声明");

        test("11b_应补全impl声明",
                fixed.contains("device implementation DisplayDevice.impl"),
                "DisplayDevice.impl 应被自动补全",
                "检查是否有 device implementation DisplayDevice.impl");

        // ---- 测试12：annex块不应被修改 ----
        test("12_annex块内容应完整保留",
                fixed.contains("error behavior ThreeErrorStates")
                        && fixed.contains("BadValueEvent  : error event")
                        && fixed.contains("end behavior;"),
                "EMV2 annex 块内容应原样保留，不应被解析或修改",
                "检查 annex EMV2 {** ... **} 内容");

        test("12b_annex内的end_behavior不应被误删",
                fixed.contains("end behavior;"),
                "annex 内的 'end behavior;' 不应被当作畸形 end 语句处理",
                "检查 EMV2 内部 end 语句完整性");

        // ---- 测试13：修复后仍有正确的package结构 ----
        // 注意：修复后代码可能包含行内注释，所以检查关键内容而非严格的首尾匹配
        boolean hasPackageDecl = fixed.contains("package TestFix_Arch");
        boolean hasPackageEnd = fixed.contains("end TestFix_Arch;");
        test("13_packages结构完整",
                hasPackageDecl && hasPackageEnd,
                "修复后代码应包含 package 声明和 end package 声明",
                "hasPackageDecl=" + hasPackageDecl + ", hasPackageEnd=" + hasPackageEnd);

        // ---- 测试14：修复数统计合理性 ----
        test("14_修复数量应大于0",
                result.fixes.size() > 0,
                "应检测到并执行了多个修复",
                "实际修复数: " + result.fixes.size());

        test("14b_修复数量应合理（>=5个）",
                result.fixes.size() >= 5,
                "至少应有5个以上的自动修复动作",
                "实际修复数: " + result.fixes.size());

        // ==================== 输出明细 ====================
        System.out.println();
        System.out.println("──────────────────────────────────────────────────────────");
        System.out.println("  修复明细");
        System.out.println("──────────────────────────────────────────────────────────");
        int i = 1;
        for (String f : result.fixes) {
            System.out.printf("  F%02d. %s%n", i++, f);
        }

        if (!result.errors.isEmpty()) {
            System.out.println();
            System.out.println("──────────────────────────────────────────────────────────");
            System.out.println("  剩余错误（未自动修复的）");
            System.out.println("──────────────────────────────────────────────────────────");
            i = 1;
            for (String e : result.errors) {
                System.out.printf("  E%02d. %s%n", i++, e);
            }
        }

        if (!result.warnings.isEmpty()) {
            System.out.println();
            System.out.println("──────────────────────────────────────────────────────────");
            System.out.println("  警告");
            System.out.println("──────────────────────────────────────────────────────────");
            i = 1;
            for (String w : result.warnings) {
                System.out.printf("  W%02d. %s%n", i++, w);
            }
        }

        // ==================== 总结 ====================
        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════");
        System.out.printf("  测试结果: %d 通过, %d 失败, 共 %d%n",
                passed, failed, passed + failed);
        System.out.println("══════════════════════════════════════════════════════════");

        // 保存修复结果
        String outputFile = inputFile.replace(".aadl", "_fixed.aadl");
        Files.write(Paths.get(outputFile), fixed.getBytes());
        System.out.println();
        System.out.println("修复后代码已保存到: " + outputFile);

        System.exit(failed > 0 ? 1 : 0);
    }

    // ==================== 断言辅助方法 ====================

    private static void test(String name, boolean condition, String expected, String actual) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name);
            System.out.println("    期望: " + expected);
            System.out.println("    实际: " + actual);
        }
    }

    // ==================== 专用检查辅助方法 ====================

    private static boolean dataHasFeaturesBlock(String content, String dataName) {
        String[] lines = content.split("\n");
        boolean inData = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;
            if (trimmed.matches("data\\s+" + dataName + "(\\s+extends\\s+\\w+)?\\s*")) {
                inData = true;
                continue;
            }
            if (inData && trimmed.matches("end\\s+" + dataName + "\\s*;.*")) {
                break;
            }
            if (inData && trimmed.equalsIgnoreCase("features")) {
                return true;
            }
        }
        return false;
    }

    private static boolean threadImplHasConnections(String content, String threadName) {
        String[] lines = content.split("\n");
        boolean inImpl = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;
            if (trimmed.matches("thread\\s+implementation\\s+" + threadName + "\\.impl.*")) {
                inImpl = true;
                continue;
            }
            if (inImpl && trimmed.matches("end\\s+" + threadName + "\\.impl\\s*;.*")) {
                break;
            }
            if (inImpl && trimmed.equalsIgnoreCase("connections")) {
                return true;
            }
        }
        return false;
    }

    private static boolean processTypeHasFeature(String content, String typeName, String featureName) {
        String[] lines = content.split("\n");
        boolean inType = false;
        boolean inFeatures = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;
            if (trimmed.matches("process\\s+" + typeName + "(\\s+extends\\s+\\w+)?\\s*")) {
                inType = true;
                continue;
            }
            if (inType && trimmed.matches("end\\s+" + typeName + "\\s*;.*")) {
                break;
            }
            if (inType && trimmed.equalsIgnoreCase("features")) {
                inFeatures = true;
                continue;
            }
            if (inFeatures && trimmed.contains(featureName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean processImplHasFeaturesBlock(String content, String typeName) {
        String[] lines = content.split("\n");
        boolean inImpl = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;
            if (trimmed.matches("process\\s+implementation\\s+" + typeName + "\\.impl.*")) {
                inImpl = true;
                continue;
            }
            if (inImpl && trimmed.matches("end\\s+" + typeName + "\\.impl\\s*;.*")) {
                break;
            }
            if (inImpl && trimmed.equalsIgnoreCase("features")) {
                return true;
            }
        }
        return false;
    }

    private static boolean portConnUsesSingleArrow(String content, String connName) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;
            if (trimmed.contains(connName) && trimmed.contains("port") && trimmed.contains("<->")) {
                return false;
            }
            if (trimmed.contains(connName) && trimmed.contains("port") && trimmed.contains(" -> ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean processorImplHasSameNameInstance(String content, String procName) {
        String[] lines = content.split("\n");
        boolean inImpl = false;
        boolean inSubcomp = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;
            if (trimmed.matches("processor\\s+implementation\\s+" + procName + "\\.impl.*")) {
                inImpl = true;
                continue;
            }
            if (inImpl && trimmed.matches("end\\s+" + procName + "\\.impl\\s*;.*")) {
                break;
            }
            if (inImpl && trimmed.equalsIgnoreCase("subcomponents")) {
                inSubcomp = true;
                continue;
            }
            if (inSubcomp && trimmed.matches(procName + "\\s*:\\s*processor\\s+" + procName + "\\..*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkBlockOrder(String content, String busName) {
        String[] lines = content.split("\n");
        boolean inImpl = false;
        int subcompIdx = -1;
        int connIdx = -1;
        int propIdx = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("--")) continue;

            if (trimmed.matches("bus\\s+implementation\\s+" + busName + "\\.impl.*")) {
                inImpl = true;
                continue;
            }
            if (inImpl && trimmed.matches("end\\s+" + busName + "\\.impl\\s*;.*")) {
                break;
            }
            if (!inImpl) continue;

            if (trimmed.equalsIgnoreCase("subcomponents")) subcompIdx = i;
            if (trimmed.equalsIgnoreCase("connections")) connIdx = i;
            if (trimmed.equalsIgnoreCase("properties")) propIdx = i;
        }

        if (subcompIdx < 0 || connIdx < 0 || propIdx < 0) return false;
        return subcompIdx < connIdx && connIdx < propIdx;
    }
}
