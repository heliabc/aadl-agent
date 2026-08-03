package com.example.aadlagent.util;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AADL 引用完整性验证器（不依赖大模型）。
 *
 * 核心思路：复用 {@link AadlInputParser} 解析出的组件真值表作为"真值源"，
 * 解析生成的 AADL 代码中的所有声明和引用，交叉比对后：
 * 1. 检测并自动补全缺失的组件声明（类型声明 + 实现声明）
 * 2. 检测悬空引用（subcomponents 引用了未声明的组件）
 * 3. 检测幻觉组件（AADL 中声明了但架构树中不存在的组件）
 * 4. 检测架构树中存在但 AADL 中遗漏的组件
 * 5. 检测组件类型不匹配
 * 6. 检测 subcomponents 层级违规（如 process 直接放在 processor 下）
 */
@Slf4j
public class AadlReferenceValidator {

    /**
     * AADL 标准组件包含关系规则。
     * key = 父组件 implementation 的类型，value = 允许包含的子组件类型集合。
     * 参照 AADL 标准：process 不能直接放在 processor 的 subcomponents 中，
     * 而是放在 system implementation 的 subcomponents 中。
     */
    private static final Map<String, Set<String>> CONTAINMENT_RULES = new LinkedHashMap<>();

    static {
        // system implementation：可包含所有组件类型
        CONTAINMENT_RULES.put("system", new LinkedHashSet<>(Arrays.asList(
                "process", "processor", "memory", "device", "bus", "system",
                "virtual processor", "thread", "data", "subprogram", "abstract"
        )));
        // processor implementation：只能包含虚拟处理器、内存、总线（不能包含 process！）
        CONTAINMENT_RULES.put("processor", new LinkedHashSet<>(Arrays.asList(
                "virtual processor", "memory", "bus"
        )));
        // process implementation：可包含线程、数据、子程序
        CONTAINMENT_RULES.put("process", new LinkedHashSet<>(Arrays.asList(
                "thread", "data", "subprogram"
        )));
        // thread implementation：可包含数据、子程序
        CONTAINMENT_RULES.put("thread", new LinkedHashSet<>(Arrays.asList(
                "data", "subprogram"
        )));
        // virtual processor implementation：可包含线程、数据、子程序
        CONTAINMENT_RULES.put("virtual processor", new LinkedHashSet<>(Arrays.asList(
                "thread", "data", "subprogram"
        )));
        // memory implementation：可包含内存、总线
        CONTAINMENT_RULES.put("memory", new LinkedHashSet<>(Arrays.asList(
                "memory", "bus"
        )));
        // bus implementation：通常不包含组件
        CONTAINMENT_RULES.put("bus", new LinkedHashSet<>());
        // device implementation：通常不包含组件
        CONTAINMENT_RULES.put("device", new LinkedHashSet<>());
        // data implementation：可包含数据、子程序
        CONTAINMENT_RULES.put("data", new LinkedHashSet<>(Arrays.asList(
                "data", "subprogram"
        )));
        // subprogram implementation：通常不包含组件
        CONTAINMENT_RULES.put("subprogram", new LinkedHashSet<>());
        // abstract implementation：可包含任何组件
        CONTAINMENT_RULES.put("abstract", new LinkedHashSet<>(Arrays.asList(
                "process", "processor", "memory", "device", "bus", "system",
                "virtual processor", "thread", "data", "subprogram", "abstract"
        )));
    }

    /**
     * AADL 强保留关键字集合（不区分大小写）。
     * 这些词不能用作组件名、包名等标识符。
     * 当架构树中存在以保留字命名的组件时，自动补全应跳过并输出警告，
     * 因为 LLM 通常已按照命名规则将其重命名（如 System → Top_BSCU）。
     */
    private static final Set<String> AADL_RESERVED_WORDS = new LinkedHashSet<>(Arrays.asList(
            "system", "process", "thread", "processor", "memory", "device", "bus",
            "data", "subprogram", "abstract", "package", "end", "public", "private",
            "features", "subcomponents", "connections", "properties", "port", "event",
            "in", "out", "inout", "requires", "provides", "access", "virtual",
            "implementation", "annex", "behavior", "error", "states", "transitions",
            "events", "initial", "state", "applies", "to", "reference", "true", "false",
            "none", "all", "and", "or", "not", "if", "then", "else", "elsif", "end",
            "is", "of", "type", "subtype", "constant", "range", "delta", "digits",
            "array", "record", "tagged", "limited", "abstract", "synchronized",
            "interface", "task", "protected", "entry", "for", "use", "renames",
            "when", "loop", "while", "exit", "return", "abort", "accept", "delay",
            "select", "requeue", "terminate", "raise", "null", "begin", "declare",
            "exception", "generic", "pragma", "aliased", "at", "do", "reverse",
            "component", "module", "subsystem"
    ));

    /** 检查名称是否为 AADL 保留字（不区分大小写） */
    private boolean isReservedWord(String name) {
        return name != null && AADL_RESERVED_WORDS.contains(name.toLowerCase());
    }

    // ========================= 数据结构 =========================

    /** AADL 代码中解析出的组件声明 */
    public static class AadlDeclaration {
        public String name;
        public String type;           // system, process, thread, ...
        public boolean hasTypeDecl;   // "system Foo end Foo;"
        public boolean hasImplDecl;   // "system implementation Foo.impl end Foo.impl;"
        public int typeDeclLine = -1;
        public int implDeclLine = -1;
    }

    /** AADL 代码中 subcomponents 行的引用 */
    public static class SubcomponentRef {
        public String instanceName;
        public String componentKeyword;  // system, process, thread, ...
        public String typeName;          // 引用的类型名（不含 .impl）
        public String parentImpl;        // 所在的 implementation 名
        public int lineNumber;
    }

    /** 验证结果 */
    public static class ValidationResult {
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<String> fixes = new ArrayList<>();
        public String fixedContent;
        public boolean hasIssues() {
            return !errors.isEmpty() || !warnings.isEmpty() || !fixes.isEmpty();
        }
    }

    // ========================= 公共入口 =========================

    /**
     * 验证并修正 AADL 代码。
     *
     * @param aadlContent  生成的 AADL 代码
     * @param parseResult  AadlInputParser 解析结果（组件真值表 + 模块约束）
     * @return 验证结果（含修正后的代码）
     */
    public ValidationResult validate(String aadlContent, AadlInputParser.ParseResult parseResult) {
        ValidationResult result = new ValidationResult();
        result.fixedContent = aadlContent;

        if (aadlContent == null || aadlContent.trim().isEmpty()) {
            result.errors.add("AADL 内容为空");
            return result;
        }

        // 1. 从 ParseResult 获取组件真值表（无需重复解析 JSON）
        Map<String, AadlInputParser.ArchNode> archComponents = parseResult.archComponents;
        log.info("使用解析器提供的组件真值表：{} 个组件", archComponents.size());

        // 2. 解析 AADL 声明
        Map<String, AadlDeclaration> aadlDeclarations = parseAadlDeclarations(aadlContent);
        log.info("AADL 声明解析完成：{} 个组件声明", aadlDeclarations.size());

        // 3. 解析 subcomponents 引用
        List<SubcomponentRef> subcomponentRefs = parseSubcomponentRefs(aadlContent);
        log.info("subcomponents 引用解析完成：{} 条", subcomponentRefs.size());

        // 4. 交叉验证
        // 4a. 检测悬空引用：subcomponents 引用了未声明的组件
        checkDanglingReferences(subcomponentRefs, aadlDeclarations, archComponents, result);

        // 4b. 检测缺失声明：有类型声明但缺实现声明，或反之
        checkIncompleteDeclarations(aadlDeclarations, archComponents, result);

        // 4c. 检测幻觉组件：AADL 中声明了但架构树中不存在的组件
        checkHallucinatedComponents(aadlDeclarations, archComponents, result);

        // 4d. 检测遗漏组件：架构树中存在但 AADL 中缺失的组件
        checkMissingComponents(aadlDeclarations, archComponents, result);

        // 4e. 检测类型不匹配
        checkTypeMismatches(aadlDeclarations, archComponents, result);

        // 4f. 检测 subcomponents 层级违规（如 process 直接放在 processor 下）
        checkContainmentCompliance(subcomponentRefs, aadlDeclarations, result);

        // 5. 自动修正
        if (!result.errors.isEmpty() || hasAutoFixableIssues(aadlDeclarations, archComponents)) {
            result.fixedContent = applyFixes(aadlContent, aadlDeclarations, archComponents, result);
        }

        return result;
    }

    // ========================= AADL 声明解析 =========================

    /**
     * 解析 AADL 代码中的所有组件声明。
     * 捕获两种模式：
     *   1. 类型声明：  system Foo ... end Foo;
     *   2. 实现声明：  system implementation Foo.impl ... end Foo.impl;
     */
    private Map<String, AadlDeclaration> parseAadlDeclarations(String aadlContent) {
        Map<String, AadlDeclaration> declarations = new LinkedHashMap<>();
        String[] lines = aadlContent.split("\n");

        // 类型声明模式：system Foo / process Bar / thread Baz ...（不含 implementation）
        Pattern typeDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+(\\w+)\\s*$"
        );
        // 实现声明模式：system implementation Foo.impl
        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl\\s*$"
        );
        // virtual processor 特殊处理
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+(\\w+)\\s*$"
        );
        Pattern virtualImplPattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+implementation\\s+(\\w+)\\.impl\\s*$"
        );

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // 跳过注释行
            if (line.startsWith("--")) {
                continue;
            }

            // 实现声明（优先匹配，因为 "system implementation" 也包含 "system"）
            Matcher implMatcher = implDeclPattern.matcher(line);
            if (implMatcher.find()) {
                String type = implMatcher.group(1);
                String name = implMatcher.group(2);
                AadlDeclaration decl = declarations.computeIfAbsent(name, k -> new AadlDeclaration());
                decl.name = name;
                decl.type = type;
                decl.hasImplDecl = true;
                decl.implDeclLine = i + 1;
                continue;
            }

            // virtual processor 实现声明
            Matcher virtualImplMatcher = virtualImplPattern.matcher(line);
            if (virtualImplMatcher.find()) {
                String name = virtualImplMatcher.group(1);
                AadlDeclaration decl = declarations.computeIfAbsent(name, k -> new AadlDeclaration());
                decl.name = name;
                decl.type = "virtual processor";
                decl.hasImplDecl = true;
                decl.implDeclLine = i + 1;
                continue;
            }

            // 类型声明
            Matcher typeMatcher = typeDeclPattern.matcher(line);
            if (typeMatcher.find()) {
                String type = typeMatcher.group(1);
                String name = typeMatcher.group(2);
                AadlDeclaration decl = declarations.computeIfAbsent(name, k -> new AadlDeclaration());
                decl.name = name;
                decl.type = type;
                decl.hasTypeDecl = true;
                decl.typeDeclLine = i + 1;
                continue;
            }

            // virtual processor 类型声明
            Matcher virtualTypeMatcher = virtualTypePattern.matcher(line);
            if (virtualTypeMatcher.find()) {
                String name = virtualTypeMatcher.group(1);
                AadlDeclaration decl = declarations.computeIfAbsent(name, k -> new AadlDeclaration());
                decl.name = name;
                decl.type = "virtual processor";
                decl.hasTypeDecl = true;
                decl.typeDeclLine = i + 1;
                continue;
            }
        }

        return declarations;
    }

    // ========================= subcomponents 引用解析 =========================

    /**
     * 解析 AADL 代码中所有 subcomponents 行的引用。
     * 格式：实例名 : 组件关键字 类型名.impl;
     */
    private List<SubcomponentRef> parseSubcomponentRefs(String aadlContent) {
        List<SubcomponentRef> refs = new ArrayList<>();
        String[] lines = aadlContent.split("\n");

        // 匹配：InstanceName : system TypeName.impl;
        // 或：  InstanceName : virtual processor TypeName.impl;
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract|virtual\\s+processor)\\s+(\\w+)\\.impl\\s*;"
        );

        // 当前所在的 implementation 上下文
        String currentImpl = null;
        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            // 跟踪当前 implementation 上下文
            Matcher implMatcher = implContextPattern.matcher(line);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                continue;
            }

            // 检测 end ...impl; 退出 implementation 上下文
            if (line.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                continue;
            }

            Matcher m = subcompPattern.matcher(line);
            if (m.find()) {
                SubcomponentRef ref = new SubcomponentRef();
                ref.instanceName = m.group(1);
                ref.componentKeyword = m.group(2).replaceAll("\\s+", " ");
                ref.typeName = m.group(3);
                ref.parentImpl = currentImpl;
                ref.lineNumber = i + 1;
                refs.add(ref);
            }
        }

        return refs;
    }

    // ========================= 交叉验证逻辑 =========================

    /**
     * 4a. 检测悬空引用：subcomponents 引用了未声明的组件。
     */
    private void checkDanglingReferences(List<SubcomponentRef> refs,
                                         Map<String, AadlDeclaration> declarations,
                                         Map<String, AadlInputParser.ArchNode> archComponents,
                                         ValidationResult result) {
        for (SubcomponentRef ref : refs) {
            AadlDeclaration decl = declarations.get(ref.typeName);

            if (decl == null) {
                // 引用的组件完全未声明
                String archInfo = "";
                AadlInputParser.ArchNode archComp = archComponents.get(ref.typeName);
                if (archComp != null) {
                    archInfo = String.format("（架构树中存在该组件，类型: %s）", archComp.type);
                }
                result.errors.add(String.format(
                        "第%d行: subcomponents 引用 '%s' 的类型 '%s' 未声明%s",
                        ref.lineNumber, ref.instanceName, ref.typeName, archInfo
                ));
            } else {
                // 检查是否有实现声明
                if (!decl.hasImplDecl) {
                    result.errors.add(String.format(
                            "第%d行: subcomponents 引用 '%s.impl' 但组件 '%s' 缺少 implementation 声明",
                            ref.lineNumber, ref.typeName, ref.typeName
                    ));
                }
                // 检查是否有类型声明
                if (!decl.hasTypeDecl) {
                    result.errors.add(String.format(
                            "第%d行: subcomponents 引用 '%s' 但组件 '%s' 缺少类型声明",
                            ref.lineNumber, ref.typeName, ref.typeName
                    ));
                }
            }
        }
    }

    /**
     * 4b. 检测不完整的声明：有类型声明但缺实现声明，或反之。
     */
    private void checkIncompleteDeclarations(Map<String, AadlDeclaration> declarations,
                                             Map<String, AadlInputParser.ArchNode> archComponents,
                                             ValidationResult result) {
        for (AadlDeclaration decl : declarations.values()) {
            if (decl.hasTypeDecl && !decl.hasImplDecl) {
                // 有类型声明但无实现声明
                // 检查是否被 subcomponents 引用（如果被引用，则必须要有 .impl）
                // 这里先记录为警告
                result.warnings.add(String.format(
                        "组件 '%s' 有类型声明但缺少 implementation 声明（第%d行）",
                        decl.name, decl.typeDeclLine
                ));
            }
            if (!decl.hasTypeDecl && decl.hasImplDecl) {
                result.errors.add(String.format(
                        "组件 '%s' 有 implementation 声明（第%d行）但缺少类型声明",
                        decl.name, decl.implDeclLine
                ));
            }
        }
    }

    /**
     * 4c. 检测幻觉组件：AADL 中声明了但架构树中不存在的组件。
     */
    private void checkHallucinatedComponents(Map<String, AadlDeclaration> declarations,
                                             Map<String, AadlInputParser.ArchNode> archComponents,
                                             ValidationResult result) {
        if (archComponents.isEmpty()) {
            return; // 没有架构树数据，跳过此检查
        }

        for (AadlDeclaration decl : declarations.values()) {
            if (!archComponents.containsKey(decl.name)) {
                result.warnings.add(String.format(
                        "幻觉组件: '%s' (%s) 在架构树中不存在，可能是 LLM 生成的不必要组件",
                        decl.name, decl.type
                ));
            }
        }
    }

    /**
     * 4d. 检测遗漏组件：架构树中存在但 AADL 中缺失的组件。
     */
    private void checkMissingComponents(Map<String, AadlDeclaration> declarations,
                                        Map<String, AadlInputParser.ArchNode> archComponents,
                                        ValidationResult result) {
        if (archComponents.isEmpty()) {
            return;
        }

        for (AadlInputParser.ArchNode archComp : archComponents.values()) {
            if (!declarations.containsKey(archComp.name)) {
                // 保留字命名的组件降级为警告：LLM 通常已按命名规则重命名（如 System → Top_BSCU）
                if (isReservedWord(archComp.name)) {
                    result.warnings.add(String.format(
                            "架构树组件 '%s' (%s) 使用了 AADL 保留关键字作为名称，" +
                            "AADL 中可能已用合规名称替代（非硬性错误）",
                            archComp.name, archComp.type
                    ));
                } else {
                    result.errors.add(String.format(
                            "遗漏组件: '%s' (%s) 在架构树中存在但 AADL 中缺失声明",
                            archComp.name, archComp.type
                    ));
                }
            }
        }
    }

    /**
     * 4e. 检测类型不匹配：AADL 中的组件类型与架构树中的不一致。
     */
    private void checkTypeMismatches(Map<String, AadlDeclaration> declarations,
                                     Map<String, AadlInputParser.ArchNode> archComponents,
                                     ValidationResult result) {
        if (archComponents.isEmpty()) {
            return;
        }

        for (AadlDeclaration decl : declarations.values()) {
            AadlInputParser.ArchNode archComp = archComponents.get(decl.name);
            if (archComp != null && !decl.type.equals(archComp.type)) {
                result.errors.add(String.format(
                        "类型不匹配: 组件 '%s' 在架构树中类型为 '%s'，但在 AADL 中声明为 '%s'",
                        decl.name, archComp.type, decl.type
                ));
            }
        }
    }

    /**
     * 4f. 检测 subcomponents 层级违规。
     * 根据 AADL 标准包含关系规则，检查每个 subcomponent 引用是否被合法地放置在父组件下。
     * 例如：process 不能直接放在 processor 的 subcomponents 中。
     *
     * @param refs         AADL 中解析出的 subcomponents 引用
     * @param declarations AADL 中解析出的组件声明（用于查找父组件类型）
     * @param result       验证结果
     */
    private void checkContainmentCompliance(List<SubcomponentRef> refs,
                                             Map<String, AadlDeclaration> declarations,
                                             ValidationResult result) {
        for (SubcomponentRef ref : refs) {
            if (ref.parentImpl == null) {
                continue;
            }

            // 查找父组件的类型
            AadlDeclaration parentDecl = declarations.get(ref.parentImpl);
            if (parentDecl == null || parentDecl.type == null) {
                continue;
            }

            String parentType = parentDecl.type;
            String childType = ref.componentKeyword;

            Set<String> allowed = CONTAINMENT_RULES.get(parentType);
            if (allowed == null) {
                continue;
            }

            if (!allowed.contains(childType)) {
                String allowedStr = allowed.isEmpty()
                        ? "(无)"
                        : String.join(", ", allowed);
                result.errors.add(String.format(
                        "第%d行: 层级违规 - 组件 '%s' (类型: %s) 不能直接放在 '%s' (类型: %s) 的 subcomponents 中; " +
                        "%s implementation 只能包含: %s",
                        ref.lineNumber, ref.instanceName, childType,
                        ref.parentImpl, parentType,
                        parentType, allowedStr
                ));
            }
        }
    }

    // ========================= 自动修正 =========================

    private boolean hasAutoFixableIssues(Map<String, AadlDeclaration> declarations,
                                         Map<String, AadlInputParser.ArchNode> archComponents) {
        // 如果有架构树中存在但 AADL 中缺失的组件，可以自动补全（跳过保留字命名的组件）
        if (!archComponents.isEmpty()) {
            for (AadlInputParser.ArchNode archComp : archComponents.values()) {
                if (isReservedWord(archComp.name)) {
                    continue;
                }
                if (!declarations.containsKey(archComp.name)) {
                    return true;
                }
            }
        }
        // 如果有声明不完整的组件，可以自动补全
        for (AadlDeclaration decl : declarations.values()) {
            if (decl.hasTypeDecl != decl.hasImplDecl) {
                return true;
            }
        }
        return false;
    }

    /**
     * 自动修正：
     * 1. 补全缺失的组件声明（类型声明 + 实现声明）
     * 2. 补全不完整的声明（只有类型声明补实现声明，或反之）
     */
    private String applyFixes(String aadlContent,
                              Map<String, AadlDeclaration> declarations,
                              Map<String, AadlInputParser.ArchNode> archComponents,
                              ValidationResult result) {
        StringBuilder fixBlock = new StringBuilder();
        int fixCount = 0;

        // 1. 补全架构树中存在但 AADL 中缺失的组件
        if (!archComponents.isEmpty()) {
            for (AadlInputParser.ArchNode archComp : archComponents.values()) {
                // 跳过 AADL 保留字命名的组件（LLM 通常已按命名规则重命名，如 System → Top_BSCU）
                if (isReservedWord(archComp.name)) {
                    result.warnings.add(String.format(
                            "组件 '%s' (%s) 使用了 AADL 保留关键字作为名称，已跳过自动补全。" +
                            "LLM 可能已按照命名规则使用合规名称替代",
                            archComp.name, archComp.type
                    ));
                    log.warn("跳过保留字组件自动补全: {} ({})", archComp.name, archComp.type);
                    continue;
                }
                AadlDeclaration decl = declarations.get(archComp.name);
                if (decl == null) {
                    // 完全缺失，生成类型声明 + 实现声明
                    fixBlock.append(generateFullDeclaration(archComp.name, archComp.type));
                    fixCount++;
                    result.fixes.add(String.format(
                            "已补全缺失组件声明: %s (%s)", archComp.name, archComp.type
                    ));
                } else {
                    // 部分缺失
                    if (!decl.hasTypeDecl) {
                        fixBlock.append(generateTypeDeclaration(archComp.name, archComp.type));
                        fixCount++;
                        result.fixes.add(String.format(
                                "已补全类型声明: %s (%s)", archComp.name, archComp.type
                        ));
                    }
                    if (!decl.hasImplDecl) {
                        fixBlock.append(generateImplDeclaration(archComp.name, archComp.type));
                        fixCount++;
                        result.fixes.add(String.format(
                                "已补全实现声明: %s (%s)", archComp.name, archComp.type
                        ));
                    }
                }
            }
        } else {
            // 没有架构树数据，只补全 AADL 内部不完整的声明
            for (AadlDeclaration decl : declarations.values()) {
                if (!decl.hasTypeDecl) {
                    fixBlock.append(generateTypeDeclaration(decl.name, decl.type));
                    fixCount++;
                    result.fixes.add(String.format(
                            "已补全类型声明: %s (%s)", decl.name, decl.type
                    ));
                }
                if (!decl.hasImplDecl) {
                    fixBlock.append(generateImplDeclaration(decl.name, decl.type));
                    fixCount++;
                    result.fixes.add(String.format(
                            "已补全实现声明: %s (%s)", decl.name, decl.type
                    ));
                }
            }
        }

        if (fixCount == 0) {
            return aadlContent;
        }

        log.info("自动修正：共补全 {} 个声明", fixCount);

        // 将补全的声明插入到 "end package;" 之前
        String content = aadlContent;
        int endPkgIdx = findEndPackagePosition(content);

        if (endPkgIdx >= 0) {
            StringBuilder sb = new StringBuilder(content.substring(0, endPkgIdx));
            sb.append("\n    -- =======================================\n");
            sb.append("    -- 自动补全的组件声明（由 AadlReferenceValidator 生成）\n");
            sb.append("    -- =======================================\n");
            sb.append(fixBlock);
            sb.append(content.substring(endPkgIdx));
            return sb.toString();
        } else {
            // 找不到 end package;，追加到末尾
            log.warn("未找到 'end package;' 语句，将补全声明追加到末尾");
            return content + "\n" + fixBlock;
        }
    }

    /** 生成完整的组件声明（类型 + 实现） */
    private String generateFullDeclaration(String name, String type) {
        StringBuilder sb = new StringBuilder();
        sb.append(generateTypeDeclaration(name, type));
        sb.append(generateImplDeclaration(name, type));
        return sb.toString();
    }

    /** 生成类型声明 */
    private String generateTypeDeclaration(String name, String type) {
        StringBuilder sb = new StringBuilder();
        sb.append("    ").append(type).append(" ").append(name).append("\n");
        sb.append("    end ").append(name).append(";\n\n");
        return sb.toString();
    }

    /** 生成实现声明 */
    private String generateImplDeclaration(String name, String type) {
        StringBuilder sb = new StringBuilder();
        sb.append("    ").append(type).append(" implementation ").append(name).append(".impl\n");
        sb.append("    end ").append(name).append(".impl;\n\n");
        return sb.toString();
    }

    /**
     * 找到 "end <package_name>;" 的位置。
     *
     * 实现策略：
     * 1. 从文件头部 "package XXX" 声明中提取包名
     * 2. 精确匹配 "end <包名>;" 的最后一个出现位置
     *
     * 这样可以避免误匹配 EMV2 块内的 "end behavior;" 或组件的 "end Foo;"。
     */
    private int findEndPackagePosition(String content) {
        // 1. 提取 package 名称
        Pattern pkgPattern = Pattern.compile("package\\s+(\\w+)");
        Matcher pkgMatcher = pkgPattern.matcher(content);
        if (!pkgMatcher.find()) {
            log.warn("未找到 package 声明，无法定位 end package 位置");
            return -1;
        }
        String pkgName = pkgMatcher.group(1);

        // 2. 查找 "end <pkgName>;" 的最后一个出现位置
        Pattern endPkgPattern = Pattern.compile(
                "end\\s+" + Pattern.quote(pkgName) + "\\s*;", Pattern.MULTILINE
        );
        Matcher m = endPkgPattern.matcher(content);
        int lastPos = -1;
        while (m.find()) {
            lastPos = m.start();
        }
        if (lastPos < 0) {
            log.warn("未找到 'end {};' 语句", pkgName);
        }
        return lastPos;
    }
}
