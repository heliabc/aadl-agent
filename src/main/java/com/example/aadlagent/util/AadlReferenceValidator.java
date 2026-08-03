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
        // virtual processor implementation：只能包含虚拟处理器和进程（不能包含 thread！）
        // 线程只能作为 process 的子组件，不能直接放在 virtual processor 下
        CONTAINMENT_RULES.put("virtual processor", new LinkedHashSet<>(Arrays.asList(
                "virtual processor", "process"
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

    /** AADL 代码中 connections 行的引用 */
    public static class ConnectionRef {
        public String connName;
        public String sourceInstance;    // 源子组件实例名
        public String sourceFeature;     // 源端口/访问点名
        public String destInstance;      // 目标子组件实例名
        public String destFeature;       // 目标端口/访问点名
        public String connType;          // "port" 或 "bus access"
        public String parentImpl;        // 所在的 implementation 名
        public int lineNumber;
    }

    /** 验证结果 */
    public static class ValidationResult {
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<String> fixes = new ArrayList<>();
        public String fixedContent;
        /** 需要自动补全的 feature 列表：key = 组件类型名, value = {feature名 → 数据类型} */
        public Map<String, Map<String, String>> missingFeatures = new LinkedHashMap<>();
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

        // 3b. 解析组件 features（类型声明中的端口/访问点）
        Map<String, Map<String, String>> componentFeatures = parseFeatures(aadlContent);
        log.info("features 解析完成：{} 个组件有 features 声明", componentFeatures.size());

        // 3c. 解析 connections 引用
        List<ConnectionRef> connectionRefs = parseConnections(aadlContent);
        log.info("connections 引用解析完成：{} 条", connectionRefs.size());

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

        // 4g. 检测 features 放置错误（features 出现在 implementation 中）
        checkFeaturesPlacement(aadlContent, result);

        // 4h. 检测 connections 引用悬空 feature（引用了组件中不存在的端口）
        checkConnectionReferences(connectionRefs, componentFeatures, subcomponentRefs, aadlDeclarations, result);

        // 4i. 检测线程 implementation 中非法包含 connections 块
        checkThreadConnectionsBlock(aadlContent, aadlDeclarations, result);

        // 4j. 检测非法语法 requires data port（应为 in/out data port）
        checkIllegalRequiresDataPort(aadlContent, result);

        // 4k. 检测 properties 中 applies to 引用了未声明的子组件实例
        checkAppliesToReferences(aadlContent, subcomponentRefs, result);

        // 4l. 检测截断/不完整的连接行（缺少分号或端口名）
        checkIncompleteConnections(aadlContent, result);

        // 4m. 检测设备端口类型与数据组件混淆
        checkDevicePortTypeMismatch(aadlContent, aadlDeclarations, result);

        // 4n. 检测 process implementation 中非法的 bus access 连接
        checkBusAccessInProcess(aadlContent, result);

        // 4o. 检测 implementation 中 subcomponents → connections → properties 顺序违规
        checkImplementationOrder(aadlContent, result);

        // 4p. 检测 process/thread implementation 中的双向连接（<->）
        checkBidirectionalInSoftwareLayer(aadlContent, result);

        // 5. 自动修正
        if (!result.errors.isEmpty() || hasAutoFixableIssues(aadlDeclarations, archComponents)
                || !result.missingFeatures.isEmpty()) {
            result.fixedContent = applyFixes(aadlContent, aadlDeclarations, archComponents,
                    componentFeatures, result);
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

    // ========================= features 解析 =========================

    /**
     * 解析 AADL 代码中所有组件类型声明的 features 块。
     * 只解析类型声明中的 features（不解析 implementation 中的）。
     *
     * @return Map: 组件类型名 → 该组件 features 块中声明的端口/访问点名集合
     */
    private Map<String, Map<String, String>> parseFeatures(String aadlContent) {
        Map<String, Map<String, String>> componentFeatures = new LinkedHashMap<>();
        String[] lines = aadlContent.split("\n");

        // 类型声明模式（不含 implementation）
        Pattern typeDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+(\\w+)\\s*$"
        );
        // 实现声明模式
        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        // virtual processor 类型声明
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+(\\w+)\\s*$"
        );
        // feature 行模式：featureName : in data port TypeName / out data port TypeName / ...
        // 捕获: 1=featureName, 2=方向+端口类型, 4=数据类型(可选)
        Pattern featurePattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*(in\\s+data\\s+port|out\\s+data\\s+port|in\\s+event\\s+port|out\\s+event\\s+port|" +
                "in\\s+event\\s+data\\s+port|out\\s+event\\s+data\\s+port|" +
                "requires\\s+bus\\s+access|provides\\s+bus\\s+access|" +
                "requires\\s+data\\s+access|provides\\s+data\\s+access|" +
                "in\\s+port|out\\s+port)(\\s+([A-Za-z_]\\w*(?:::[A-Za-z_]\\w*)*(?:\\.\\w+)?))?\\s*;"
        );

        String currentTypeDecl = null;  // 当前所在的类型声明名
        boolean inImplementation = false;
        boolean inFeaturesBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            // 实现声明
            if (implDeclPattern.matcher(line).find()) {
                inImplementation = true;
                inFeaturesBlock = false;
                currentTypeDecl = null;
                continue;
            }

            // virtual processor 类型声明
            Matcher virtualTypeMatcher = virtualTypePattern.matcher(line);
            if (virtualTypeMatcher.find()) {
                currentTypeDecl = virtualTypeMatcher.group(1);
                inImplementation = false;
                inFeaturesBlock = false;
                continue;
            }

            // 类型声明
            Matcher typeMatcher = typeDeclPattern.matcher(line);
            if (typeMatcher.find()) {
                currentTypeDecl = typeMatcher.group(2);
                inImplementation = false;
                inFeaturesBlock = false;
                continue;
            }

            // end 语句退出当前声明
            if (line.matches("end\\s+\\w+(\\.impl)?\\s*;")) {
                currentTypeDecl = null;
                inFeaturesBlock = false;
                inImplementation = false;
                continue;
            }

            // features 块开始
            if (line.equals("features") && !inImplementation && currentTypeDecl != null) {
                inFeaturesBlock = true;
                continue;
            }

            // 退出 features 块（遇到其他块关键字）
            if (inFeaturesBlock && (line.equals("properties") || line.equals("flows") ||
                    line.equals("connections") || line.equals("subcomponents") ||
                    line.equals("annex") || line.startsWith("annex"))) {
                inFeaturesBlock = false;
                continue;
            }

            // 解析 feature 行
            if (inFeaturesBlock && currentTypeDecl != null && !line.isEmpty()) {
                Matcher featureMatcher = featurePattern.matcher(line);
                if (featureMatcher.find()) {
                    String featureName = featureMatcher.group(1);
                    String dataType = featureMatcher.group(4);  // 数据类型，可能为 null
                    componentFeatures.computeIfAbsent(currentTypeDecl, k -> new LinkedHashMap<>())
                            .put(featureName, dataType != null ? dataType : "");
                }
            }
        }

        return componentFeatures;
    }

    // ========================= connections 解析 =========================

    /**
     * 解析 AADL 代码中所有 connections 行的引用。
     * 支持两种格式：
     *   1. port 连接：connName : port source.feature -> dest.feature;
     *   2. bus access 连接：connName : bus access source.feature <-> dest.feature;
     */
    private List<ConnectionRef> parseConnections(String aadlContent) {
        List<ConnectionRef> refs = new ArrayList<>();
        String[] lines = aadlContent.split("\n");

        // 连接行模式
        // 格式1: connName : port Instance1.feature1 -> Instance2.feature2;
        // 格式2: connName : bus access Instance1.feature1 <-> Instance2.feature2;
        // 可能有内联属性 {...}，所以末尾不强制要求分号
        Pattern connPattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*(port|bus\\s+access)\\s+" +
                "(\\w+)\\.(\\w+)\\s*(->|<->)\\s*(\\w+)\\.(\\w+)"
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

            // 跟踪 implementation 上下文
            Matcher implMatcher = implContextPattern.matcher(line);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                continue;
            }

            // 退出 implementation 上下文
            if (line.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                continue;
            }

            Matcher m = connPattern.matcher(line);
            if (m.find()) {
                ConnectionRef ref = new ConnectionRef();
                ref.connName = m.group(1);
                ref.connType = m.group(2).replaceAll("\\s+", " ");
                ref.sourceInstance = m.group(3);
                ref.sourceFeature = m.group(4);
                ref.destInstance = m.group(6);
                ref.destFeature = m.group(7);
                ref.parentImpl = currentImpl;
                ref.lineNumber = i + 1;
                refs.add(ref);
            }
        }

        return refs;
    }

    // ========================= features 放置检测 =========================

    /**
     * 4g. 检测 features 放置错误：features 块出现在 implementation 声明中。
     * AADL 标准要求 features 只能出现在组件类型声明中。
     */
    private void checkFeaturesPlacement(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );

        boolean inImplementation = false;
        String currentImplName = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            Matcher implMatcher = implDeclPattern.matcher(line);
            if (implMatcher.find()) {
                inImplementation = true;
                currentImplName = implMatcher.group(2);
                continue;
            }

            if (line.matches("end\\s+\\w+\\.impl\\s*;")) {
                inImplementation = false;
                currentImplName = null;
                continue;
            }

            if (inImplementation && line.equals("features")) {
                result.errors.add(String.format(
                        "第%d行: features 放置错误 - features 块出现在 implementation '%s.impl' 中; " +
                        "features 只能出现在组件类型声明中，不能出现在 implementation 中",
                        i + 1, currentImplName
                ));
            }
        }
    }

    // ========================= connections 引用检测 =========================

    /**
     * 4h. 检测 connections 引用悬空 feature。
     * 对每条连接，检查引用的 实例名.端口名 中：
     * 1. 实例名是否在当前 implementation 的 subcomponents 中声明过
     * 2. 端口名是否在该实例对应组件类型的 features 块中声明过
     *
     * @param connections      解析出的 connections 引用
     * @param componentFeatures 每个组件类型的 features（feature名 → 数据类型）
     * @param subcomponentRefs  解析出的 subcomponents 引用（用于建立 实例名→类型名 映射）
     * @param declarations      AADL 声明（用于查找类型）
     * @param result            验证结果
     */
    private void checkConnectionReferences(List<ConnectionRef> connections,
                                            Map<String, Map<String, String>> componentFeatures,
                                            List<SubcomponentRef> subcomponentRefs,
                                            Map<String, AadlDeclaration> declarations,
                                            ValidationResult result) {
        // 按 parentImpl 分组 subcomponents，建立每个 implementation 内的 实例名→类型名 映射
        Map<String, Map<String, String>> implInstanceMap = new HashMap<>();
        for (SubcomponentRef ref : subcomponentRefs) {
            if (ref.parentImpl != null) {
                implInstanceMap.computeIfAbsent(ref.parentImpl, k -> new HashMap<>())
                        .put(ref.instanceName, ref.typeName);
            }
        }

        for (ConnectionRef conn : connections) {
            if (conn.parentImpl == null) {
                continue;
            }

            Map<String, String> instanceMap = implInstanceMap.get(conn.parentImpl);

            // 检查源端，传入目标端信息用于数据类型推断
            checkSingleEndpoint(conn, conn.sourceInstance, conn.sourceFeature,
                    conn.destInstance, conn.destFeature,
                    instanceMap, componentFeatures, declarations, result, "源");

            // 检查目标端，传入源端信息用于数据类型推断
            checkSingleEndpoint(conn, conn.destInstance, conn.destFeature,
                    conn.sourceInstance, conn.sourceFeature,
                    instanceMap, componentFeatures, declarations, result, "目标");
        }
    }

    /**
     * 检查连接的单个端点（源或目标）。
     * 如果端口不存在于 features 中，将记录到 result.missingFeatures 供自动补全使用。
     * 补全时从连接另一端的 feature 声明中查找数据类型。
     *
     * @param conn              当前连接
     * @param instanceName      本端实例名
     * @param featureName       本端端口名
     * @param otherInstance     另一端实例名
     * @param otherFeature      另一端端口名
     * @param instanceMap       实例名→类型名映射
     * @param componentFeatures 组件类型 → (feature名 → 数据类型)
     * @param declarations      AADL 声明
     * @param result            验证结果
     * @param endpointLabel     "源" 或 "目标"
     */
    private void checkSingleEndpoint(ConnectionRef conn, String instanceName, String featureName,
                                      String otherInstance, String otherFeature,
                                      Map<String, String> instanceMap,
                                      Map<String, Map<String, String>> componentFeatures,
                                      Map<String, AadlDeclaration> declarations,
                                      ValidationResult result, String endpointLabel) {
        if (instanceMap == null) {
            return;
        }

        // 1. 检查实例名是否在 subcomponents 中声明过
        String typeName = instanceMap.get(instanceName);
        if (typeName == null) {
            result.errors.add(String.format(
                    "第%d行: 连接 '%s' 的%s端引用实例 '%s' 未在当前 implementation 的 subcomponents 中声明",
                    conn.lineNumber, conn.connName, endpointLabel, instanceName
            ));
            return;
        }

        // 2. 检查端口名是否在对应组件类型的 features 中声明过
        Map<String, String> features = componentFeatures.get(typeName);
        if (features == null || features.isEmpty()) {
            // 组件类型没有 features 块 → 需要补全
            result.errors.add(String.format(
                    "第%d行: 连接 '%s' 的%s端引用 '%s.%s'，但组件类型 '%s' 没有 features 块或 features 为空",
                    conn.lineNumber, conn.connName, endpointLabel, instanceName, featureName, typeName
            ));
            String dataType = resolveDataType(otherInstance, otherFeature, instanceMap, componentFeatures);
            result.missingFeatures.computeIfAbsent(typeName, k -> new LinkedHashMap<>())
                    .put(featureName, dataType != null ? dataType : "");
        } else if (!features.containsKey(featureName)) {
            String availableFeatures = String.join(", ", features.keySet());
            result.errors.add(String.format(
                    "第%d行: 连接 '%s' 的%s端引用 '%s.%s'，但组件类型 '%s' 的 features 中不存在 '%s'（可用: %s）",
                    conn.lineNumber, conn.connName, endpointLabel, instanceName, featureName,
                    typeName, featureName, availableFeatures
            ));
            // 收集缺失的 feature 供自动补全，从连接另一端查找数据类型
            String dataType = resolveDataType(otherInstance, otherFeature, instanceMap, componentFeatures);
            result.missingFeatures.computeIfAbsent(typeName, k -> new LinkedHashMap<>())
                    .put(featureName, dataType != null ? dataType : "");
        }
    }

    /**
     * 从连接另一端的 feature 声明中查找数据类型。
     * 如果另一端实例的组件类型 features 中存在对应 feature 且有数据类型，则返回该类型。
     *
     * @param otherInstance     另一端实例名
     * @param otherFeature      另一端端口名
     * @param instanceMap       实例名→类型名映射
     * @param componentFeatures 组件类型 → (feature名 → 数据类型)
     * @return 数据类型名，或 null 如果无法确定
     */
    private String resolveDataType(String otherInstance, String otherFeature,
                                    Map<String, String> instanceMap,
                                    Map<String, Map<String, String>> componentFeatures) {
        if (otherInstance == null || otherFeature == null || instanceMap == null) {
            return null;
        }
        String otherType = instanceMap.get(otherInstance);
        if (otherType == null) {
            return null;
        }
        Map<String, String> otherFeatures = componentFeatures.get(otherType);
        if (otherFeatures == null) {
            return null;
        }
        String dataType = otherFeatures.get(otherFeature);
        return (dataType != null && !dataType.isEmpty()) ? dataType : null;
    }

    // ========================= 线程 connections 块检测 =========================

    /**
     * 4i. 检测线程 implementation 中非法包含 connections 块。
     * AADL 语法规定：线程（Thread）内部严禁出现 connections 块。
     * 组件之间的连接只能写在 System 或 Process 的 implementation 中。
     */
    private void checkThreadConnectionsBlock(String aadlContent,
                                              Map<String, AadlDeclaration> declarations,
                                              ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualImplPattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+implementation\\s+(\\w+)\\.impl"
        );

        String currentImplName = null;
        String currentImplType = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            Matcher implMatcher = implDeclPattern.matcher(line);
            if (implMatcher.find()) {
                currentImplType = implMatcher.group(1);
                currentImplName = implMatcher.group(2);
                continue;
            }

            Matcher virtualImplMatcher = virtualImplPattern.matcher(line);
            if (virtualImplMatcher.find()) {
                currentImplType = "virtual processor";
                currentImplName = virtualImplMatcher.group(1);
                continue;
            }

            if (line.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImplName = null;
                currentImplType = null;
                continue;
            }

            if ("thread".equals(currentImplType) && line.equals("connections")) {
                result.errors.add(String.format(
                        "第%d行: 语法错误 - connections 块出现在线程 implementation '%s.impl' 中; " +
                        "线程内部严禁包含 connections 块，连接只能写在 system 或 process 的 implementation 中",
                        i + 1, currentImplName
                ));
            }
        }
    }

    // ========================= 非法 requires data port 检测 =========================

    /**
     * 4j. 检测非法语法 requires data port。
     * AADL 中 requires 关键字只能用于 bus access、subprogram access 等，
     * 数据端口只能用 in data port 或 out data port，不存在 requires data port。
     */
    private void checkIllegalRequiresDataPort(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");
        // 匹配：featureName : requires data port XXX;
        Pattern illegalPattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*requires\\s+data\\s+port\\s+\\w+", Pattern.CASE_INSENSITIVE
        );

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("--")) {
                continue;
            }

            Matcher m = illegalPattern.matcher(line);
            if (m.find()) {
                String featureName = m.group(1);
                result.errors.add(String.format(
                        "第%d行: 语法错误 - feature '%s' 使用了非法语法 'requires data port'; " +
                        "requires 关键字只能用于 bus access / subprogram access; " +
                        "数据端口必须使用 'in data port' 或 'out data port'",
                        i + 1, featureName
                ));
            }
        }
    }

    // ========================= applies to 引用检测 =========================

    /**
     * 4k. 检测 properties 中 applies to 引用了未声明的子组件实例。
     * 例如：Actual_Processor_Binding => (reference (MainProcessor)) applies to MainProcess;
     * 如果 MainProcess 不在当前 implementation 的 subcomponents 中，则报错。
     */
    private void checkAppliesToReferences(String aadlContent,
                                           List<SubcomponentRef> subcomponentRefs,
                                           ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        // 匹配：... applies to InstanceName;
        Pattern appliesToPattern = Pattern.compile(
                "applies\\s+to\\s+(\\w+)\\s*;", Pattern.CASE_INSENSITIVE
        );

        // 当前 implementation 上下文
        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualImplPattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+implementation\\s+(\\w+)\\.impl"
        );

        // 按 parentImpl 分组 subcomponents
        Map<String, Set<String>> implInstances = new HashMap<>();
        for (SubcomponentRef ref : subcomponentRefs) {
            if (ref.parentImpl != null) {
                implInstances.computeIfAbsent(ref.parentImpl, k -> new HashSet<>())
                        .add(ref.instanceName);
            }
        }

        String currentImpl = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            Matcher implMatcher = implContextPattern.matcher(line);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                continue;
            }

            Matcher virtualImplMatcher = virtualImplPattern.matcher(line);
            if (virtualImplMatcher.find()) {
                currentImpl = virtualImplMatcher.group(1);
                continue;
            }

            if (line.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                continue;
            }

            Matcher m = appliesToPattern.matcher(line);
            if (m.find() && currentImpl != null) {
                String targetInstance = m.group(1);
                Set<String> instances = implInstances.get(currentImpl);
                if (instances == null || !instances.contains(targetInstance)) {
                    result.errors.add(String.format(
                            "第%d行: 属性引用错误 - 'applies to %s' 引用的实例 '%s' 未在当前 implementation '%s.impl' 的 subcomponents 中声明",
                            i + 1, targetInstance, targetInstance, currentImpl
                    ));
                }
            }
        }
    }

    // ========================= 截断/不完整连接行检测 =========================

    /**
     * 4l. 检测截断/不完整的连接行。
     * 检测两种情况：
     * 1. 连接行缺少末尾分号
     * 2. 连接行中端口引用不完整（如 Instance. 而非 Instance.feature）
     */
    private void checkIncompleteConnections(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        // 连接行模式（匹配以 connName : port 或 connName : bus access 开头的行）
        Pattern connStartPattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*(port|bus\\s+access)\\s+", Pattern.CASE_INSENSITIVE
        );

        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );

        boolean inConnections = false;
        boolean inImplementation = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            if (implContextPattern.matcher(line).find()) {
                inImplementation = true;
                inConnections = false;
                continue;
            }

            if (line.matches("end\\s+\\w+\\.impl\\s*;")) {
                inImplementation = false;
                inConnections = false;
                continue;
            }

            if (line.equals("connections")) {
                inConnections = true;
                continue;
            }

            if (inConnections) {
                // 退出 connections 块
                if (line.equals("properties") || line.equals("subcomponents") ||
                        line.equals("features") || line.equals("flows") ||
                        line.matches("end\\s+\\w+\\.impl\\s*;")) {
                    inConnections = false;
                    continue;
                }

                Matcher connMatcher = connStartPattern.matcher(line);
                if (connMatcher.find()) {
                    String connName = connMatcher.group(1);

                    // 1. 检查是否以分号结尾（忽略内联属性 {...}）
                    String lineWithoutInlineProp = line.replaceAll("\\{[^}]*\\}", "").trim();
                    if (!lineWithoutInlineProp.endsWith(";")) {
                        result.errors.add(String.format(
                                "第%d行: 语法错误 - 连接 '%s' 缺少末尾分号 ';'，可能导致解析截断",
                                i + 1, connName
                        ));
                    }

                    // 2. 检查端口引用是否完整（Instance.feature）
                    // 匹配不完整的引用：Instance. 后面没有标识符
                    if (line.matches(".*\\w+\\.(?!\\w).*") || line.matches(".*\\w+\\.\\s*$")) {
                        result.errors.add(String.format(
                                "第%d行: 语法错误 - 连接 '%s' 的端口引用不完整，缺少端口名（格式应为 实例名.端口名）",
                                i + 1, connName
                        ));
                    }
                }
            }
        }
    }

    // ========================= 设备端口类型检测 =========================

    /**
     * 4m. 检测设备端口类型与数据组件混淆。
     * AADL 中数据端口（in/out data port）引用的类型应该是已声明的 data 组件类型。
     * 如果引用的类型不是 data 组件（如误用 device、process 等类型名），则报类型不匹配错误。
     * 如果引用的类型未声明，则报警告。
     */
    private void checkDevicePortTypeMismatch(String aadlContent,
                                              Map<String, AadlDeclaration> declarations,
                                              ValidationResult result) {
        String[] lines = aadlContent.split("\n");
        // 匹配：featureName : in data port TypeName; 或 featureName : out data port TypeName;
        Pattern dataPortPattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*(?:in|out)\\s+data\\s+port\\s+(\\w+)", Pattern.CASE_INSENSITIVE
        );

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("--")) {
                continue;
            }

            Matcher m = dataPortPattern.matcher(line);
            if (m.find()) {
                String featureName = m.group(1);
                String typeName = m.group(2);
                AadlDeclaration decl = declarations.get(typeName);
                if (decl == null) {
                    result.warnings.add(String.format(
                            "第%d行: 端口 '%s' 引用的数据类型 '%s' 未在 AADL 中声明（可能是外部数据类型）",
                            i + 1, featureName, typeName
                    ));
                } else if (!"data".equals(decl.type)) {
                    result.errors.add(String.format(
                            "第%d行: 类型不匹配 - 端口 '%s' 引用的类型 '%s' 是 %s 组件，应为 data 组件; " +
                            "数据端口的类型必须是 data 组件，不能是 %s",
                            i + 1, featureName, typeName, decl.type, decl.type
                    ));
                }
            }
        }
    }

    /**
     * 4n. 检测 process implementation 中非法的 bus access 连接。
     *
     * 分层架构规范：bus access 连接只能出现在 system implementation 中，
     * 严禁出现在 process implementation 中。进程内部只做纯粹的 port 数据流连接。
     *
     * 检测到时报告 error，自动修正阶段会删除这些连接行。
     */
    private void checkBusAccessInProcess(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        Pattern processImplPattern = Pattern.compile(
                "^\\s*process\\s+implementation\\s+(\\w+)\\.impl"
        );
        // 匹配 bus access 连接行：connName : bus access ... <-> ...
        Pattern busAccessConnPattern = Pattern.compile(
                "^\\s*\\w+\\s*:\\s*bus\\s+access\\s+"
        );

        boolean inProcessImpl = false;
        String currentProcessImplName = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            Matcher processMatcher = processImplPattern.matcher(line);
            if (processMatcher.find()) {
                inProcessImpl = true;
                currentProcessImplName = processMatcher.group(1);
                continue;
            }

            if (line.matches("end\\s+\\w+\\.impl\\s*;")) {
                inProcessImpl = false;
                currentProcessImplName = null;
                continue;
            }

            if (inProcessImpl && busAccessConnPattern.matcher(line).find()) {
                result.errors.add(String.format(
                        "第%d行: 分层架构违规 - bus access 连接出现在 process implementation '%s.impl' 中; " +
                        "bus access 连接只能出现在 system implementation 中，进程内部只做 port 数据流连接",
                        i + 1, currentProcessImplName
                ));
            }
        }
    }

    /**
     * 4o. 检测 implementation 中 subcomponents → connections → properties 的顺序违规。
     *
     * 三步规范：编写 implementation 时必须严格遵守顺序：
     *   第一步 subcomponents → 第二步 connections → 第三步 properties
     * 如果出现顺序倒置（如 properties 出现在 subcomponents 之前），报告错误。
     */
    private void checkImplementationOrder(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        // 匹配任意组件类型的 implementation 声明
        Pattern implPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        // 匹配块关键字
        Pattern subcompPattern = Pattern.compile("^\\s*subcomponents\\s*$");
        Pattern connPattern = Pattern.compile("^\\s*connections\\s*$");
        Pattern propPattern = Pattern.compile("^\\s*properties\\s*$");

        boolean inImpl = false;
        String currentImplName = null;
        int lastSectionOrder = 0; // 0=未出现, 1=subcomponents, 2=connections, 3=properties

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            Matcher implMatcher = implPattern.matcher(line);
            if (implMatcher.find()) {
                inImpl = true;
                currentImplName = implMatcher.group(2);
                lastSectionOrder = 0;
                continue;
            }

            if (inImpl && line.matches("end\\s+\\w+\\.impl\\s*;")) {
                inImpl = false;
                currentImplName = null;
                lastSectionOrder = 0;
                continue;
            }

            if (!inImpl) {
                continue;
            }

            int currentOrder = 0;
            if (subcompPattern.matcher(line).find()) {
                currentOrder = 1;
            } else if (connPattern.matcher(line).find()) {
                currentOrder = 2;
            } else if (propPattern.matcher(line).find()) {
                currentOrder = 3;
            }

            if (currentOrder > 0) {
                if (currentOrder < lastSectionOrder) {
                    String[] sectionNames = {"", "subcomponents", "connections", "properties"};
                    result.errors.add(String.format(
                            "第%d行: 三步规范违规 - implementation '%s.impl' 中 '%s' 出现在 '%s' 之后; " +
                            "必须严格遵守顺序: subcomponents → connections → properties",
                            i + 1, currentImplName, sectionNames[currentOrder], sectionNames[lastSectionOrder]
                    ));
                }
                lastSectionOrder = currentOrder;
            }
        }
    }

    /**
     * 4p. 检测 process/thread implementation 中的双向连接（<->）。
     *
     * 分层架构规范：软件层（process / thread）中的 port 连接必须使用单向 ->，
     * 双向 <-> 容易引发跨层耦合和语法解析问题，应避免使用。
     */
    private void checkBidirectionalInSoftwareLayer(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        // 匹配 process 或 thread implementation
        Pattern softwareImplPattern = Pattern.compile(
                "^\\s*(process|thread)\\s+implementation\\s+(\\w+)\\.impl"
        );
        // 匹配包含 <-> 的连接行
        Pattern bidirConnPattern = Pattern.compile("<->");

        boolean inSoftwareImpl = false;
        String currentImplName = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            Matcher implMatcher = softwareImplPattern.matcher(line);
            if (implMatcher.find()) {
                inSoftwareImpl = true;
                currentImplName = implMatcher.group(2);
                continue;
            }

            if (line.matches("end\\s+\\w+\\.impl\\s*;")) {
                inSoftwareImpl = false;
                currentImplName = null;
                continue;
            }

            if (inSoftwareImpl && bidirConnPattern.matcher(line).find()) {
                result.warnings.add(String.format(
                        "第%d行: 分层架构建议 - process/thread implementation '%s.impl' 中使用了双向连接 <->; " +
                        "软件层组件间应使用单向 -> 进行数据流连接，避免跨层耦合",
                        i + 1, currentImplName
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
        // 以下类型的错误也可以自动修正（但需要在 validate 中已检测到 errors）
        // - requires data port → in data port
        // - 截断/不完整的连接行
        // - 线程中的 connections 块
        // - applies to 引用未声明的子组件
        return false;
    }

    // ========================= 语法自动修正方法 =========================

    /**
     * 自动修正：将非法的 'requires data port' 替换为 'in data port'。
     * AADL 中 requires 关键字只能用于 bus access / subprogram access，
     * 数据端口必须使用 in data port 或 out data port。
     * 默认替换为 in data port（输入端口）。
     *
     * @param content AADL 代码
     * @param result  验证结果（记录修正信息）
     * @return 修正后的 AADL 代码
     */
    private String fixRequiresDataPort(String content, ValidationResult result) {
        // 匹配：featureName : requires data port TypeName;
        Pattern pattern = Pattern.compile(
                "(\\w+\\s*:\\s*)requires\\s+data\\s+port\\s+(\\w+)", Pattern.CASE_INSENSITIVE
        );
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();
        int fixCount = 0;
        for (String line : lines) {
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                String prefix = m.group(1);
                String typeName = m.group(2);
                String featureName = prefix.trim().replace(":", "").trim();
                String fixedLine = m.replaceAll(prefix + "in data port " + typeName);
                // 在修复行末尾添加注释说明
                fixedLine = fixedLine + "  -- [自动修正] 'requires data port' → 'in data port'";
                resultLines.add(fixedLine);
                result.fixes.add(String.format(
                        "已修正非法语法: '%s' 的 'requires data port' → 'in data port'", featureName
                ));
                fixCount++;
            } else {
                resultLines.add(line);
            }
        }
        if (fixCount > 0) {
            log.info("自动修正：共修正 {} 处 'requires data port' 语法", fixCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 自动修正：修复截断/不完整的连接行。
     * 1. 连接行中一端只有实例名没有端口名（如 Instance 而非 Instance.feature）→ 硬编码补充端口名
     *    源端默认补 .dataOut，目标端默认补 .dataIn
     * 2. 连接行中 Instance. 后面缺少端口名 → 硬编码补充端口名
     * 3. 连接行缺少末尾分号 → 补充分号
     *
     * @param content AADL 代码
     * @param result  验证结果（记录修正信息）
     * @return 修正后的 AADL 代码
     */
    private String fixIncompleteConnectionLines(String content, ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();
        int fixCount = 0;

        // 匹配连接行开头：connName : port ... 或 connName : bus access ...
        Pattern connStartPattern = Pattern.compile(
                "^(\\s*)(\\w+)\\s*:\\s*(port|bus\\s+access)\\s+(.*)", Pattern.CASE_INSENSITIVE
        );

        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualImplPattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+implementation\\s+(\\w+)\\.impl"
        );

        boolean inConnections = false;
        boolean inImplementation = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            if (implContextPattern.matcher(trimmed).find() || virtualImplPattern.matcher(trimmed).find()) {
                inImplementation = true;
                inConnections = false;
                resultLines.add(line);
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                inImplementation = false;
                inConnections = false;
                resultLines.add(line);
                continue;
            }

            if (trimmed.equals("connections")) {
                inConnections = true;
                resultLines.add(line);
                continue;
            }

            if (inConnections) {
                // 退出 connections 块
                if (trimmed.equals("properties") || trimmed.equals("subcomponents") ||
                        trimmed.equals("features") || trimmed.equals("flows") ||
                        trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                    inConnections = false;
                    resultLines.add(line);
                    continue;
                }

                Matcher connMatcher = connStartPattern.matcher(line);
                if (connMatcher.find()) {
                    String indent = connMatcher.group(1);
                    String connName = connMatcher.group(2);
                    String connType = connMatcher.group(3);
                    String rest = connMatcher.group(4);

                    List<String> inlineComments = new ArrayList<>();
                    String fixedRest = fixConnectionRest(rest, connName, result, inlineComments);
                    String fixedLine = indent + connName + " : " + connType + " " + fixedRest;

                    if (!fixedLine.equals(line)) {
                        // 在修复行末尾添加注释说明
                        if (!inlineComments.isEmpty()) {
                            fixedLine = fixedLine + "  -- [自动修正] " + String.join("; ", inlineComments);
                        }
                        resultLines.add(fixedLine);
                        fixCount++;
                    } else {
                        resultLines.add(line);
                    }
                } else {
                    resultLines.add(line);
                }
            } else {
                resultLines.add(line);
            }
        }

        if (fixCount > 0) {
            log.info("自动修正：共修复 {} 处不完整连接行", fixCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 修复单个连接行的剩余部分（connName : port 之后的内容）。
     * 处理：不完整端口引用、缺少分号。
     */
    private String fixConnectionRest(String rest, String connName, ValidationResult result,
                                      List<String> inlineComments) {
        String fixed = rest.trim();

        // 识别箭头类型
        String arrow = null;
        if (fixed.contains("<->")) {
            arrow = "<->";
        } else if (fixed.contains("->")) {
            arrow = "->";
        }

        if (arrow == null) {
            // 无箭头，无法修复
            return rest;
        }

        int arrowIdx = fixed.indexOf(arrow);
        String sourcePart = fixed.substring(0, arrowIdx).trim();
        String destPart = fixed.substring(arrowIdx + arrow.length()).trim();

        // 分离内联属性 {...}
        String inlineProp = "";
        int braceIdx = destPart.indexOf('{');
        if (braceIdx >= 0) {
            inlineProp = destPart.substring(braceIdx);
            destPart = destPart.substring(0, braceIdx).trim();
        }

        // 去除末尾分号便于分析
        boolean hasSemicolon = destPart.endsWith(";");
        if (hasSemicolon) {
            destPart = destPart.substring(0, destPart.length() - 1).trim();
        }

        // 修复源端：应为 Instance.featureName
        if (sourcePart.matches("\\w+\\.")) {
            // Instance. 后面没有端口名
            sourcePart = sourcePart + "dataOut";
            String msg = String.format("已补全连接 '%s' 源端缺失的端口名: .dataOut", connName);
            result.fixes.add(msg);
            inlineComments.add("补全源端端口名 .dataOut");
        } else if (sourcePart.matches("\\w+")) {
            // 只有 Instance 没有 .featureName
            sourcePart = sourcePart + ".dataOut";
            String msg = String.format("已补全连接 '%s' 源端缺失的端口名: .dataOut", connName);
            result.fixes.add(msg);
            inlineComments.add("补全源端端口名 .dataOut");
        }

        // 修复目标端：应为 Instance.featureName
        if (destPart.matches("\\w+\\.")) {
            destPart = destPart + "dataIn";
            String msg = String.format("已补全连接 '%s' 目标端缺失的端口名: .dataIn", connName);
            result.fixes.add(msg);
            inlineComments.add("补全目标端端口名 .dataIn");
        } else if (destPart.matches("\\w+")) {
            // 只有 Instance 没有 .featureName（如用户示例中的 <-> Pressure）
            destPart = destPart + ".dataIn";
            String msg = String.format("已补全连接 '%s' 目标端缺失的端口名: .dataIn", connName);
            result.fixes.add(msg);
            inlineComments.add("补全目标端端口名 .dataIn");
        }

        // 重组连接行
        fixed = sourcePart + " " + arrow + " " + destPart;

        // 补全缺失的分号
        if (!fixed.endsWith(";")) {
            fixed = fixed + ";";
            String msg = String.format("已补全连接 '%s' 缺失的末尾分号 ';'", connName);
            result.fixes.add(msg);
            inlineComments.add("补全末尾分号 ';'");
        }

        // 加回内联属性
        if (!inlineProp.isEmpty()) {
            fixed = fixed + " " + inlineProp;
        }

        return fixed;
    }

    /**
     * 自动修正：删除线程 implementation 中的 connections 块。
     * AADL 语法规定线程内部严禁包含 connections 块。
     * 此方法直接移除线程 implementation 中的整个 connections 段（含 connections 关键字行及其下所有连接行）。
     *
     * @param content AADL 代码
     * @param result  验证结果（记录修正信息）
     * @return 修正后的 AADL 代码
     */
    private String fixThreadConnectionsBlocks(String content, ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();

        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualImplPattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+implementation\\s+(\\w+)\\.impl"
        );

        String currentImplType = null;
        String currentImplName = null;
        boolean inConnectionsBlock = false;
        int removedCount = 0;
        // 记录当前正在删除的 connections 块的线程名
        String removedThreadName = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--") && !inConnectionsBlock) {
                resultLines.add(line);
                continue;
            }

            Matcher implMatcher = implDeclPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImplType = implMatcher.group(1);
                currentImplName = implMatcher.group(2);
                inConnectionsBlock = false;
                resultLines.add(line);
                continue;
            }

            Matcher virtualImplMatcher = virtualImplPattern.matcher(trimmed);
            if (virtualImplMatcher.find()) {
                currentImplType = "virtual processor";
                currentImplName = virtualImplMatcher.group(1);
                inConnectionsBlock = false;
                resultLines.add(line);
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImplType = null;
                currentImplName = null;
                inConnectionsBlock = false;
                resultLines.add(line);
                continue;
            }

            // 线程 implementation 中遇到 connections → 进入删除模式
            if ("thread".equals(currentImplType) && trimmed.equals("connections")) {
                inConnectionsBlock = true;
                removedThreadName = currentImplName;
                removedCount = 0;
                result.fixes.add(String.format(
                        "已删除线程 '%s.impl' 中非法的 connections 块", currentImplName));
                continue; // 跳过 "connections" 行
            }

            // 在删除模式中，跳过连接行直到遇到其他块关键字或 end
            if (inConnectionsBlock) {
                if (trimmed.equals("properties") || trimmed.equals("subcomponents") ||
                        trimmed.equals("features") || trimmed.equals("flows") ||
                        trimmed.startsWith("end ")) {
                    // 退出删除模式，在当前位置插入注释说明删除了什么
                    resultLines.add(String.format(
                            "    -- [自动修正] 已删除线程 '%s.impl' 中非法的 connections 块（共 %d 行），线程内部不允许包含 connections",
                            removedThreadName, removedCount));
                    inConnectionsBlock = false;
                    resultLines.add(line);
                    continue;
                }
                // 跳过连接行
                removedCount++;
                continue;
            }

            resultLines.add(line);
        }

        // 如果到文件末尾仍在删除模式中（end 行可能已被跳过）
        if (inConnectionsBlock) {
            resultLines.add(String.format(
                    "    -- [自动修正] 已删除线程 '%s.impl' 中非法的 connections 块（共 %d 行），线程内部不允许包含 connections",
                    removedThreadName, removedCount));
        }

        if (removedCount > 0) {
            log.info("自动修正：从线程 implementation 中删除了 {} 行 connections", removedCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 自动修正：为 applies to 引用的未声明子组件补充 subcomponent 声明。
     * 如果 properties 中写了 applies to MainProcess，但 MainProcess 不在当前 implementation
     * 的 subcomponents 中，则在 subcomponents 块中补充声明。
     *
     * @param content      AADL 代码
     * @param declarations AADL 声明（用于查找组件类型）
     * @param result       验证结果（记录修正信息）
     * @return 修正后的 AADL 代码
     */
    /**
     * 自动修正：删除 process implementation 中非法的 bus access 连接行。
     *
     * 分层架构规范：bus access 连接只能出现在 system implementation 中。
     * 进程内部的 bus access 连接行会被直接移除。
     */
    private String fixBusAccessInProcess(String content, ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();

        Pattern processImplPattern = Pattern.compile(
                "^\\s*process\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern busAccessConnPattern = Pattern.compile(
                "^\\s*\\w+\\s*:\\s*bus\\s+access\\s+"
        );

        boolean inProcessImpl = false;
        String currentProcessImplName = null;
        int removedCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            Matcher processMatcher = processImplPattern.matcher(trimmed);
            if (processMatcher.find()) {
                inProcessImpl = true;
                currentProcessImplName = processMatcher.group(1);
                resultLines.add(line);
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                inProcessImpl = false;
                currentProcessImplName = null;
                resultLines.add(line);
                continue;
            }

            if (inProcessImpl && busAccessConnPattern.matcher(trimmed).find()) {
                removedCount++;
                result.fixes.add(String.format(
                        "已删除 process implementation '%s.impl' 中非法的 bus access 连接行: %s",
                        currentProcessImplName, trimmed));
                continue; // 跳过该行
            }

            resultLines.add(line);
        }

        if (removedCount > 0) {
            log.info("自动修正：从 process implementation 中删除了 {} 行非法 bus access 连接", removedCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 自动修正：将 process/thread implementation 中的双向连接（<->）替换为单向（->）。
     *
     * 分层架构规范：软件层组件间应使用单向 -> 进行数据流连接。
     */
    private String fixBidirectionalInSoftwareLayer(String content, ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();

        Pattern softwareImplPattern = Pattern.compile(
                "^\\s*(process|thread)\\s+implementation\\s+(\\w+)\\.impl"
        );

        boolean inSoftwareImpl = false;
        String currentImplName = null;
        int fixCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            Matcher implMatcher = softwareImplPattern.matcher(trimmed);
            if (implMatcher.find()) {
                inSoftwareImpl = true;
                currentImplName = implMatcher.group(2);
                resultLines.add(line);
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                inSoftwareImpl = false;
                currentImplName = null;
                resultLines.add(line);
                continue;
            }

            if (inSoftwareImpl && line.contains("<->")) {
                String fixedLine = line.replace("<->", "->");
                fixCount++;
                result.fixes.add(String.format(
                        "已将 process/thread implementation '%s.impl' 中的双向连接 <-> 替换为单向 -> : %s",
                        currentImplName, trimmed));
                resultLines.add(fixedLine);
            } else {
                resultLines.add(line);
            }
        }

        if (fixCount > 0) {
            log.info("自动修正：在软件层中将 {} 处双向连接 <-> 替换为单向 ->", fixCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 自动修正：重排 implementation 中的块顺序为 subcomponents → connections → properties。
     *
     * 三步规范：当检测到块顺序违规时，提取各块内容并按正确顺序重新组装。
     */
    private String fixImplementationOrder(String content, ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();

        Pattern implPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            Matcher implMatcher = implPattern.matcher(trimmed);
            if (implMatcher.find()) {
                // 找到 implementation 块的开始，提取整个块
                String implName = implMatcher.group(2);
                List<String> blockLines = new ArrayList<>();
                blockLines.add(line);
                i++;

                // 收集直到 end ... .impl;
                while (i < lines.length) {
                    String blockLine = lines[i];
                    blockLines.add(blockLine);
                    if (blockLine.trim().matches("end\\s+\\w+\\.impl\\s*;")) {
                        i++;
                        break;
                    }
                    i++;
                }

                // 分析块内顺序，必要时重排
                List<String> reordered = reorderImplementationBlock(blockLines, implName, result);
                resultLines.addAll(reordered);
            } else {
                resultLines.add(line);
                i++;
            }
        }

        return String.join("\n", resultLines);
    }

    /**
     * 重排单个 implementation 块的内部顺序。
     * 将块内容分为 subcomponents / connections / properties / 其他 四组，
     * 按正确顺序重新组装。
     */
    private List<String> reorderImplementationBlock(List<String> blockLines, String implName,
                                                     ValidationResult result) {
        // 分类收集各块行
        List<String> subcompLines = new ArrayList<>();
        List<String> connLines = new ArrayList<>();
        List<String> propLines = new ArrayList<>();
        List<String> otherLines = new ArrayList<>(); // 声明行、end 行等

        Pattern subcompHeader = Pattern.compile("^\\s*subcomponents\\s*$");
        Pattern connHeader = Pattern.compile("^\\s*connections\\s*$");
        Pattern propHeader = Pattern.compile("^\\s*properties\\s*$");

        String currentSection = null; // "subcomp", "conn", "prop", null

        for (String line : blockLines) {
            String trimmed = line.trim();

            if (subcompHeader.matcher(trimmed).find()) {
                currentSection = "subcomp";
                subcompLines.add(line);
                continue;
            }
            if (connHeader.matcher(trimmed).find()) {
                currentSection = "conn";
                connLines.add(line);
                continue;
            }
            if (propHeader.matcher(trimmed).find()) {
                currentSection = "prop";
                propLines.add(line);
                continue;
            }

            // 非块头行
            if (currentSection == null) {
                otherLines.add(line); // 声明行、end 行等
            } else if ("subcomp".equals(currentSection)) {
                subcompLines.add(line);
            } else if ("conn".equals(currentSection)) {
                connLines.add(line);
            } else if ("prop".equals(currentSection)) {
                propLines.add(line);
            }
        }

        // 检查是否需要重排：如果原顺序已经正确（subcomp 在 conn 之前，conn 在 prop 之前），则不重排
        // 通过检查 otherLines 中各块头的相对位置来判断
        // 简单策略：如果有内容且顺序错误，直接重排
        boolean needsReorder = false;
        int subcompIdx = -1, connIdx = -1, propIdx = -1;
        for (int idx = 0; idx < blockLines.size(); idx++) {
            String t = blockLines.get(idx).trim();
            if (subcompHeader.matcher(t).find()) subcompIdx = idx;
            else if (connHeader.matcher(t).find()) connIdx = idx;
            else if (propHeader.matcher(t).find()) propIdx = idx;
        }
        // 判断顺序是否违规
        if (subcompIdx >= 0 && connIdx >= 0 && subcompIdx > connIdx) needsReorder = true;
        if (subcompIdx >= 0 && propIdx >= 0 && subcompIdx > propIdx) needsReorder = true;
        if (connIdx >= 0 && propIdx >= 0 && connIdx > propIdx) needsReorder = true;

        if (!needsReorder) {
            return blockLines; // 顺序正确，无需重排
        }

        result.fixes.add(String.format(
                "已重排 implementation '%s.impl' 中的块顺序为: subcomponents → connections → properties",
                implName));
        log.info("自动修正：重排 implementation '{}.impl' 中的块顺序", implName);

        // 重新组装：声明行 → subcomponents → connections → properties → end 行
        List<String> reordered = new ArrayList<>();

        // 分离声明行和 end 行
        List<String> headerLines = new ArrayList<>();
        List<String> endLines = new ArrayList<>();
        for (String line : otherLines) {
            String trimmed = line.trim();
            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                endLines.add(line);
            } else {
                headerLines.add(line);
            }
        }

        // 组装
        reordered.addAll(headerLines);
        if (!subcompLines.isEmpty()) {
            reordered.addAll(subcompLines);
        }
        if (!connLines.isEmpty()) {
            reordered.addAll(connLines);
        }
        if (!propLines.isEmpty()) {
            reordered.addAll(propLines);
        }
        reordered.addAll(endLines);

        return reordered;
    }

    /**
     * 自动修正：将 implementation 中非法的 features 块移到对应的组件类型声明中。
     *
     * AADL 语法规定 features 块只能出现在组件类型(type)声明中。
     * 当 features 块出现在 implementation 中时：
     * 1. 按 implementation 名分组提取 features 行
     * 2. 从 implementation 中删除整个 features 块
     * 3. 注入到对应的组件类型声明中（已有 features 块则追加，否则新建）
     */
    private String fixFeaturesPlacement(String content, ValidationResult result) {
        String[] lines = content.split("\n");

        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualImplPattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern blockEndPattern = Pattern.compile(
                "^\\s*(subcomponents|connections|flows|properties|modes|calls|internal\\s+features|processor\\s+features|annex|end\\s+)\\b"
        );

        // 收集每个 implementation 名 → 提取的 features 行
        Map<String, List<String>> implFeaturesMap = new LinkedHashMap<>();
        List<String> resultLines = new ArrayList<>();

        boolean inImplementation = false;
        String currentImplName = null;
        boolean inFeaturesBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--") && !inFeaturesBlock) {
                resultLines.add(line);
                continue;
            }

            Matcher implMatcher = implDeclPattern.matcher(trimmed);
            if (implMatcher.find()) {
                inImplementation = true;
                currentImplName = implMatcher.group(2);
                inFeaturesBlock = false;
                resultLines.add(line);
                continue;
            }

            Matcher virtualImplMatcher = virtualImplPattern.matcher(trimmed);
            if (virtualImplMatcher.find()) {
                inImplementation = true;
                currentImplName = virtualImplMatcher.group(1);
                inFeaturesBlock = false;
                resultLines.add(line);
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                inImplementation = false;
                currentImplName = null;
                inFeaturesBlock = false;
                resultLines.add(line);
                continue;
            }

            // 在 implementation 中遇到 features → 进入跳过+收集模式
            if (inImplementation && trimmed.equals("features")) {
                inFeaturesBlock = true;
                if (!implFeaturesMap.containsKey(currentImplName)) {
                    implFeaturesMap.put(currentImplName, new ArrayList<>());
                }
                continue; // 跳过 "features" 行
            }

            if (inFeaturesBlock) {
                if (blockEndPattern.matcher(trimmed).find() || trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                    inFeaturesBlock = false;
                    resultLines.add(line);
                    continue;
                }
                if (!trimmed.isEmpty()) {
                    implFeaturesMap.get(currentImplName).add(trimmed);
                }
                continue; // 跳过 feature 行
            }

            resultLines.add(line);
        }

        if (implFeaturesMap.isEmpty()) {
            return content;
        }

        // 将提取的 features 注入到对应的类型声明中
        String movedContent = String.join("\n", resultLines);

        for (Map.Entry<String, List<String>> entry : implFeaturesMap.entrySet()) {
            String implName = entry.getKey();
            List<String> features = entry.getValue();
            if (features.isEmpty()) {
                continue;
            }

            result.fixes.add(String.format(
                    "已将 implementation '%s.impl' 中非法的 features 块 (%d 条) 移至类型声明 '%s'",
                    implName, features.size(), implName));

            movedContent = injectFeaturesIntoTypeDeclaration(movedContent, implName, features);
        }

        return movedContent;
    }

    /**
     * 将 features 行注入到指定组件的类型声明中。
     * 如果类型声明已有 features 块，追加到现有块末尾；否则新建 features 块。
     */
    private String injectFeaturesIntoTypeDeclaration(String content, String typeName, List<String> features) {
        String[] lines = content.split("\n");

        // 匹配类型声明：system TypeName / process TypeName / thread TypeName 等（不含 implementation）
        // 也匹配 virtual processor TypeName
        Pattern typeDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+" + Pattern.quote(typeName) + "\\s*$"
        );
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+" + Pattern.quote(typeName) + "\\s*$"
        );
        // 匹配类型声明中的块关键字（用于确定 features 块的结束位置）
        Pattern blockEndPattern = Pattern.compile(
                "^\\s*(subcomponents|connections|flows|properties|modes|calls|internal\\s+features|processor\\s+features|annex|end\\s+)\\b"
        );

        int typeDeclLineIdx = -1;
        int featuresLineIdx = -1;
        int featuresEndIdx = -1;

        // 第一遍：找到类型声明行和现有 features 块的位置
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            Matcher typeMatcher = typeDeclPattern.matcher(trimmed);
            Matcher virtualMatcher = virtualTypePattern.matcher(trimmed);
            if (typeMatcher.find() || virtualMatcher.find()) {
                typeDeclLineIdx = i;
                // 向下搜索是否已有 features 块
                for (int j = i + 1; j < lines.length; j++) {
                    String t = lines[j].trim();
                    if (t.equals("features")) {
                        featuresLineIdx = j;
                        // 找 features 块的结束位置
                        for (int k = j + 1; k < lines.length; k++) {
                            String tk = lines[k].trim();
                            if (blockEndPattern.matcher(tk).find() || tk.matches("end\\s+\\w+\\s*;")) {
                                featuresEndIdx = k;
                                break;
                            }
                        }
                        break;
                    }
                    // 遇到 end TypeName; 说明类型声明中无 features 块
                    if (trimmed.matches("end\\s+" + Pattern.quote(typeName) + "\\s*;")) {
                        break;
                    }
                }
                break;
            }
        }

        if (typeDeclLineIdx == -1) {
            // 找不到类型声明，无法注入，追加到行首注释提示
            log.warn("未找到组件类型声明 '{}'，无法注入 features", typeName);
            return content;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]);

            // 情况1：类型声明已有 features 块 → 在 features 块末尾追加
            if (featuresLineIdx != -1 && i == (featuresEndIdx > 0 ? featuresEndIdx - 1 : featuresLineIdx)) {
                // 在最后一个 feature 行之后追加
                if (i == featuresEndIdx - 1 || (featuresEndIdx == -1 && i == featuresLineIdx)) {
                    for (String f : features) {
                        sb.append("\n    ").append(f);
                    }
                }
            }

            // 情况2：类型声明无 features 块 → 在类型声明行后插入新 features 块
            if (featuresLineIdx == -1 && i == typeDeclLineIdx) {
                sb.append("\nfeatures");
                for (String f : features) {
                    sb.append("\n    ").append(f);
                }
            }

            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String fixAppliesToUndeclared(String content,
                                           Map<String, AadlDeclaration> declarations,
                                           ValidationResult result) {
        String[] lines = content.split("\n");

        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualImplPattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern appliesToPattern = Pattern.compile(
                "applies\\s+to\\s+(\\w+)\\s*;", Pattern.CASE_INSENSITIVE
        );
        // 匹配 subcomponent 声明行：instanceName : keyword TypeName.impl;
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract|virtual\\s+processor)\\s+(\\w+)\\.impl\\s*;"
        );

        String currentImpl = null;
        int subcompEndIdx = -1;  // subcomponents 块的最后一行索引
        Set<String> currentImplInstances = new LinkedHashSet<>();
        // 需要补充的子组件：key = 实例名, value = 组件类型名
        Map<String, String> toAdd = new LinkedHashMap<>();
        // 记录每个 implementation 的 subcomponents 块范围
        Map<String, int[]> implSubcompRange = new HashMap<>();

        // 第一遍：收集每个 implementation 的 subcomponents 实例名和范围
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            if (trimmed.startsWith("--")) continue;

            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                currentImplInstances.clear();
                subcompEndIdx = -1;
                continue;
            }

            Matcher virtualImplMatcher = virtualImplPattern.matcher(trimmed);
            if (virtualImplMatcher.find()) {
                currentImpl = virtualImplMatcher.group(1);
                currentImplInstances.clear();
                subcompEndIdx = -1;
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                if (currentImpl != null) {
                    implSubcompRange.put(currentImpl, new int[]{-1, subcompEndIdx});
                }
                currentImpl = null;
                continue;
            }

            if (currentImpl != null) {
                Matcher subcompMatcher = subcompPattern.matcher(trimmed);
                if (subcompMatcher.find()) {
                    currentImplInstances.add(subcompMatcher.group(1));
                    subcompEndIdx = i;
                }

                // 检查 applies to 引用
                Matcher appliesMatcher = appliesToPattern.matcher(trimmed);
                if (appliesMatcher.find()) {
                    String targetInstance = appliesMatcher.group(1);
                    if (!currentImplInstances.contains(targetInstance)) {
                        // 需要补充的子组件
                        AadlDeclaration decl = declarations.get(targetInstance);
                        String typeName = decl != null ? decl.name : targetInstance;
                        toAdd.put(targetInstance, typeName);
                        result.fixes.add(String.format(
                                "已为 implementation '%s.impl' 补充缺失的子组件声明: %s (applies to 目标)",
                                currentImpl, targetInstance));
                    }
                }
            }
        }

        if (toAdd.isEmpty()) {
            return content;
        }

        // 第二遍：在对应 implementation 的 subcomponents 末尾补充缺失的子组件
        // 重新扫描以找到每个需要修改的 implementation 的 subcomponents 最后一行
        currentImpl = null;
        int lastSubcompLine = -1;
        int insertAfterLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            if (trimmed.startsWith("--")) continue;

            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                // 处理前一个 implementation 的待补充
                if (currentImpl != null && insertAfterLine >= 0 && toAdd.containsKey(currentImpl)) {
                    // 不会执行到这里，在遇到 end 时处理
                }
                currentImpl = implMatcher.group(1);
                lastSubcompLine = -1;
                continue;
            }

            Matcher virtualImplMatcher = virtualImplPattern.matcher(trimmed);
            if (virtualImplMatcher.find()) {
                currentImpl = virtualImplMatcher.group(1);
                lastSubcompLine = -1;
                continue;
            }

            if (currentImpl != null) {
                Matcher subcompMatcher = subcompPattern.matcher(trimmed);
                if (subcompMatcher.find()) {
                    lastSubcompLine = i;
                }

                if (trimmed.matches("end\\s+\\w+\\.impl\\s*;") && toAdd.containsKey(currentImpl)) {
                    // 在最后一个 subcomponent 行之后插入，或在 properties 之前插入
                    String typeName = toAdd.get(currentImpl);
                    AadlDeclaration decl = declarations.get(typeName);
                    String componentKeyword = decl != null ? decl.type : "process";
                    String newSubcomp = String.format(
                            "    %s : %s %s.impl;",
                            currentImpl.substring(0, 1).toLowerCase() + currentImpl.substring(1) + "_inst",
                            componentKeyword, typeName);
                    // 在补充声明前添加注释说明
                    String comment = String.format(
                            "    -- [自动修正] 补充 applies to 目标的子组件声明: %s (类型: %s)",
                            toAdd.keySet().iterator().next(), componentKeyword);

                    // 如果有 subcomponents 行，在最后一行后插入
                    if (lastSubcompLine >= 0) {
                        lines[lastSubcompLine] = lines[lastSubcompLine] + "\n" + comment + "\n" + newSubcomp;
                    } else {
                        // 没有 subcomponents，在 end 之前插入 subcomponents 块
                        lines[i] = "  subcomponents\n" + comment + "\n" + newSubcomp + "\n" + lines[i];
                    }
                    toAdd.remove(currentImpl);
                }

                if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                    currentImpl = null;
                    lastSubcompLine = -1;
                }
            }
        }

        log.info("自动修正：共补充 {} 个 applies to 缺失的子组件声明", toAdd.size());
        return String.join("\n", lines);
    }

    /**
     * 自动修正（按顺序执行）：
     * 0a. 修正非法 'requires data port' 语法 → 'in data port'
     * 0b. 修复截断/不完整的连接行（硬编码补充缺失端口名 + 分号）
     * 0c. 删除线程 implementation 中非法的 connections 块
     * 0d. 为 applies to 引用的未声明子组件补充 subcomponent 声明
     * 1.  补全缺失的组件声明（类型声明 + 实现声明）
     * 2.  补全不完整的声明（只有类型声明补实现声明，或反之）
     * 3.  补全 connections 引用中缺失的 feature 声明（在组件类型声明的 features 块中补充）
     */
    private String applyFixes(String aadlContent,
                              Map<String, AadlDeclaration> declarations,
                              Map<String, AadlInputParser.ArchNode> archComponents,
                              Map<String, Map<String, String>> componentFeatures,
                              ValidationResult result) {
        // 0a. 修正非法 requires data port 语法
        String content = fixRequiresDataPort(aadlContent, result);

        // 0b. 修复截断/不完整的连接行（硬编码补充缺失端口名 + 分号）
        content = fixIncompleteConnectionLines(content, result);

        // 0c. 删除线程 implementation 中非法的 connections 块
        content = fixThreadConnectionsBlocks(content, result);

        // 0d. 为 applies to 引用的未声明子组件补充 subcomponent 声明
        content = fixAppliesToUndeclared(content, declarations, result);

        // 0e. 将 implementation 中非法的 features 块移到对应的类型声明中
        content = fixFeaturesPlacement(content, result);

        // 0f. 删除 process implementation 中非法的 bus access 连接行
        content = fixBusAccessInProcess(content, result);

        // 0g. 将软件层中的双向连接 <-> 替换为单向 ->
        content = fixBidirectionalInSoftwareLayer(content, result);

        // 0h. 重排 implementation 中的块顺序为 subcomponents → connections → properties
        content = fixImplementationOrder(content, result);

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
                        fixBlock.append(String.format("    -- [自动修正] 补全缺失的类型声明: %s (%s)\n", archComp.name, archComp.type));
                        fixBlock.append(generateTypeDeclaration(archComp.name, archComp.type));
                        fixCount++;
                        result.fixes.add(String.format(
                                "已补全类型声明: %s (%s)", archComp.name, archComp.type
                        ));
                    }
                    if (!decl.hasImplDecl) {
                        fixBlock.append(String.format("    -- [自动修正] 补全缺失的实现声明: %s (%s)\n", archComp.name, archComp.type));
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
                    fixBlock.append(String.format("    -- [自动修正] 补全缺失的类型声明: %s (%s)\n", decl.name, decl.type));
                    fixBlock.append(generateTypeDeclaration(decl.name, decl.type));
                    fixCount++;
                    result.fixes.add(String.format(
                            "已补全类型声明: %s (%s)", decl.name, decl.type
                    ));
                }
                if (!decl.hasImplDecl) {
                    fixBlock.append(String.format("    -- [自动修正] 补全缺失的实现声明: %s (%s)\n", decl.name, decl.type));
                    fixBlock.append(generateImplDeclaration(decl.name, decl.type));
                    fixCount++;
                    result.fixes.add(String.format(
                            "已补全实现声明: %s (%s)", decl.name, decl.type
                    ));
                }
            }
        }

        // 3. 补全 connections 引用中缺失的 feature 声明
        //    对每个缺失 feature 的组件类型，在其类型声明的 features 块中补充（或新建 features 块）
        int featureFixCount = 0;
        if (!result.missingFeatures.isEmpty()) {
            if (fixCount > 0) {
                content = insertFixBlockBeforeEndPackage(content, fixBlock.toString());
            }

            for (Map.Entry<String, Map<String, String>> entry : result.missingFeatures.entrySet()) {
                String typeName = entry.getKey();
                Map<String, String> missingFeats = entry.getValue();  // feature名 → 数据类型
                Map<String, String> existingFeats = componentFeatures.get(typeName);

                // 过滤掉已存在的（可能在补全过程中已被其他逻辑添加）
                Map<String, String> toAdd = new LinkedHashMap<>();
                for (Map.Entry<String, String> fe : missingFeats.entrySet()) {
                    String featName = fe.getKey();
                    String dataType = fe.getValue();
                    if (existingFeats == null || !existingFeats.containsKey(featName)) {
                        toAdd.put(featName, dataType);
                    }
                }

                if (toAdd.isEmpty()) {
                    continue;
                }

                content = injectMissingFeatures(content, typeName, toAdd);
                featureFixCount += toAdd.size();
                for (Map.Entry<String, String> fe : toAdd.entrySet()) {
                    String featName = fe.getKey();
                    String dataType = fe.getValue();
                    String direction = inferDirection(featName);
                    String typeDesc = (dataType != null && !dataType.isEmpty())
                            ? direction + " data port " + dataType
                            : direction + " data port";
                    result.fixes.add(String.format(
                            "已补全缺失 feature 声明: %s.%s (%s)", typeName, featName, typeDesc
                    ));
                }
            }
            result.fixedContent = content;
            log.info("自动修正：共补全 {} 个缺失 feature 声明", featureFixCount);
            return content;
        }

        if (fixCount == 0) {
            return content;
        }

        log.info("自动修正：共补全 {} 个声明", fixCount);

        // 将补全的声明插入到 "end package;" 之前
        return insertFixBlockBeforeEndPackage(content, fixBlock.toString());
    }

    /**
     * 将补全块插入到 "end package;" 之前。
     */
    private String insertFixBlockBeforeEndPackage(String content, String fixBlock) {
        int endPkgIdx = findEndPackagePosition(content);

        if (endPkgIdx >= 0) {
            StringBuilder sb = new StringBuilder(content.substring(0, endPkgIdx));
            sb.append("\n    -- =======================================\n");
            sb.append("    -- [自动修正] 以下为 AadlReferenceValidator 自动补全的组件声明\n");
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
        sb.append("    -- [自动修正] 补全缺失的组件声明: ").append(name).append(" (").append(type).append(")\n");
        sb.append(generateTypeDeclaration(name, type));
        sb.append(generateImplDeclaration(name, type));
        return sb.toString();
    }

    /**
     * 在组件类型声明中注入缺失的 feature 声明。
     * 如果类型声明已有 features 块，在块末尾追加；如果没有 features 块，在类型名行之后新建。
     *
     * @param content      AADL 代码
     * @param typeName     组件类型名
     * @param missingFeats 需要补全的 feature（feature名 → 数据类型，数据类型可能为空字符串）
     * @return 修正后的 AADL 代码
     */
    private String injectMissingFeatures(String content, String typeName, Map<String, String> missingFeats) {
        String[] lines = content.split("\n");
        StringBuilder featureLines = new StringBuilder();
        List<String> featNames = new ArrayList<>(missingFeats.keySet());
        for (Map.Entry<String, String> entry : missingFeats.entrySet()) {
            String featName = entry.getKey();
            String dataType = entry.getValue();
            String direction = inferDirection(featName);
            if (dataType != null && !dataType.isEmpty()) {
                featureLines.append("    ").append(featName).append(" : ")
                        .append(direction).append(" data port ").append(dataType).append(";\n");
            } else {
                featureLines.append("    ").append(featName).append(" : ")
                        .append(direction).append(" data port;\n");
            }
        }

        // 查找类型声明行：如 "processor MainProcessor" 或 "device PowerSupply"
        Pattern typeDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+" +
                Pattern.quote(typeName) + "\\s*$"
        );
        // virtual processor 特殊处理
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+" + Pattern.quote(typeName) + "\\s*$"
        );

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // 匹配类型声明
            boolean isTypeDecl = typeDeclPattern.matcher(line).find() ||
                    virtualTypePattern.matcher(line).find();
            if (!isTypeDecl) {
                continue;
            }

            // 检查后续行是否已有 features 块
            int featuresLineIdx = -1;
            int endFeaturesIdx = -1;
            for (int j = i + 1; j < lines.length; j++) {
                String nextLine = lines[j].trim();
                if (nextLine.equals("features")) {
                    featuresLineIdx = j;
                    continue;
                }
                if (featuresLineIdx >= 0 && endFeaturesIdx < 0) {
                    // features 块结束：遇到其他块关键字或 end
                    if (nextLine.equals("properties") || nextLine.equals("flows") ||
                            nextLine.equals("connections") || nextLine.equals("subcomponents") ||
                            nextLine.startsWith("annex") || nextLine.startsWith("end ")) {
                        endFeaturesIdx = j;
                        break;
                    }
                }
                // 遇到 end 语句
                if (nextLine.matches("end\\s+" + Pattern.quote(typeName) + "\\s*;")) {
                    if (featuresLineIdx >= 0 && endFeaturesIdx < 0) {
                        endFeaturesIdx = j;
                    }
                    break;
                }
            }

            if (featuresLineIdx >= 0 && endFeaturesIdx >= 0) {
                // 已有 features 块，在块末尾（endFeaturesIdx 之前）追加
                StringBuilder sb = new StringBuilder();
                sb.append("    -- [自动修正] 补全 connections 引用中缺失的 feature 声明: ")
                  .append(String.join(", ", featNames)).append("\n");
                sb.append(featureLines);
                lines[endFeaturesIdx] = sb.toString() + lines[endFeaturesIdx];
                return String.join("\n", lines);
            } else {
                // 没有 features 块，在类型声明行之后新建
                StringBuilder sb = new StringBuilder();
                sb.append("  features\n");
                sb.append("    -- [自动修正] 补全 connections 引用中缺失的 feature 声明: ")
                  .append(String.join(", ", featNames)).append("\n");
                sb.append(featureLines);
                lines[i] = lines[i] + "\n" + sb.toString().trim();
                return String.join("\n", lines);
            }
        }

        // 未找到类型声明，无法注入
        log.warn("未找到组件类型 '{}' 的声明，无法补全 feature", typeName);
        return content;
    }

    /**
     * 根据 feature 名推断端口方向。
     * 名字包含 "out"（不区分大小写）→ out，否则默认 in。
     */
    private String inferDirection(String featureName) {
        if (featureName == null) {
            return "in";
        }
        String lower = featureName.toLowerCase();
        if (lower.contains("out") || lower.contains("output") || lower.contains("send") || lower.contains("src")) {
            return "out";
        }
        return "in";
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
