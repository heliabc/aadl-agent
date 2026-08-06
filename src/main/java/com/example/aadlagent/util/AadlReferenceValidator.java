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
        // ===== 软件类构件 (Software Components) =====
        // process：单个进程地址空间，线程必须直接或间接包含在 process 内
        CONTAINMENT_RULES.put("process", new LinkedHashSet<>(Arrays.asList(
                "thread", "thread group", "subprogram", "subprogram group", "data"
        )));
        // thread：执行线程内部逻辑，只能包含子程序和数据
        CONTAINMENT_RULES.put("thread", new LinkedHashSet<>(Arrays.asList(
                "subprogram", "subprogram group", "data"
        )));
        // thread group：逻辑组织单元，打包多个相关线程
        CONTAINMENT_RULES.put("thread group", new LinkedHashSet<>(Arrays.asList(
                "thread", "thread group", "subprogram", "subprogram group", "data"
        )));
        // subprogram：对应函数，可声明私有局部静态数据
        CONTAINMENT_RULES.put("subprogram", new LinkedHashSet<>(Arrays.asList(
                "data"
        )));
        // subprogram group：子程序接口及声明的逻辑分组
        CONTAINMENT_RULES.put("subprogram group", new LinkedHashSet<>(Arrays.asList(
                "subprogram", "subprogram group"
        )));
        // data：复合数据结构或类（OOP 建模），可包含数据字段和方法
        CONTAINMENT_RULES.put("data", new LinkedHashSet<>(Arrays.asList(
                "data", "subprogram"
        )));

        // ===== 硬件类构件 (Execution Platform Components) =====
        // processor：多核 CPU、CPU 内置 Cache、分区
        CONTAINMENT_RULES.put("processor", new LinkedHashSet<>(Arrays.asList(
                "processor", "virtual processor", "memory", "virtual bus"
        )));
        // virtual processor：用于建模逻辑分区或虚拟机，可多层嵌套
        CONTAINMENT_RULES.put("virtual processor", new LinkedHashSet<>(Arrays.asList(
                "virtual processor"
        )));
        // memory：内存分块、虚拟存储
        CONTAINMENT_RULES.put("memory", new LinkedHashSet<>(Arrays.asList(
                "memory", "virtual bus"
        )));
        // bus：物理总线之上的逻辑通道（如 CAN 上的信号通道）
        CONTAINMENT_RULES.put("bus", new LinkedHashSet<>(Arrays.asList(
                "virtual bus"
        )));
        // virtual bus：虚拟总线/协议层
        CONTAINMENT_RULES.put("virtual bus", new LinkedHashSet<>(Arrays.asList(
                "virtual bus"
        )));
        // device：传感器硬件及其内部数据缓存
        CONTAINMENT_RULES.put("device", new LinkedHashSet<>(Arrays.asList(
                "data"
        )));

        // ===== 复合与抽象构件 =====
        // system：系统级集成看板，连接软硬件
        CONTAINMENT_RULES.put("system", new LinkedHashSet<>(Arrays.asList(
                "system", "process", "processor", "virtual processor",
                "memory", "bus", "virtual bus", "device", "data", "abstract"
        )));
        // abstract：早期设计阶段占位，可包含任何组件类型
        CONTAINMENT_RULES.put("abstract", new LinkedHashSet<>(Arrays.asList(
                "system", "process", "processor", "virtual processor",
                "memory", "bus", "virtual bus", "device",
                "thread", "thread group", "data",
                "subprogram", "subprogram group", "abstract"
        )));
    }

    /**
     * Feature 类型合规性规则表：每种组件类型允许的 feature 分类集合。
     *
     * feature 分类（category）：
     * - data port: 数据端口
     * - event port: 事件端口
     * - event data port: 事件数据端口
     * - bus access: 总线访问
     * - data access: 数据访问
     * - subprogram access: 子程序访问
     * - feature group: 特征组
     * - abstract feature: 抽象特征
     *
     * 规则说明：
     * - 软件组件（process/thread）：只有 port 类，不能有 bus access
     * - 硬件组件（processor/memory/bus）：只有 bus access，不能有 data port
     * - device：软硬件桥梁，port 和 bus access 都可以有
     * - data：被动类型，不能有 features
     * - system：万能容器，所有 feature 都可以有
     * - abstract：早期设计占位，允许所有 feature
     */
    private static final Map<String, Set<String>> FEATURE_RULES = new LinkedHashMap<>();

    static {
        // ===== 软件类构件 =====
        // process：软件进程，只能有端口（数据流/控制流接口）
        FEATURE_RULES.put("process", new LinkedHashSet<>(Arrays.asList(
                "data port", "event port", "event data port",
                "subprogram access", "data access",
                "feature group", "abstract feature"
        )));
        // thread：线程，只能有端口
        FEATURE_RULES.put("thread", new LinkedHashSet<>(Arrays.asList(
                "data port", "event port", "event data port",
                "subprogram access", "data access",
                "feature group", "abstract feature"
        )));
        // thread group：线程组，继承 thread 的接口
        FEATURE_RULES.put("thread group", new LinkedHashSet<>(Arrays.asList(
                "data port", "event port", "event data port",
                "feature group", "abstract feature"
        )));
        // subprogram：子程序，有 in/out 参数
        FEATURE_RULES.put("subprogram", new LinkedHashSet<>(Arrays.asList(
                "data port", "event port", "event data port",
                "subprogram access", "data access",
                "feature group", "abstract feature"
        )));
        // subprogram group：子程序组
        FEATURE_RULES.put("subprogram group", new LinkedHashSet<>(Arrays.asList(
                "subprogram access", "feature group", "abstract feature"
        )));
        // data：被动数据类型，不能有 features
        FEATURE_RULES.put("data", new LinkedHashSet<>(Arrays.asList(
                // data 组件严禁拥有 features 块
        )));

        // ===== 硬件类构件 =====
        // processor：处理器，只能有 bus access（不能有 port，端口在 device 上）
        FEATURE_RULES.put("processor", new LinkedHashSet<>(Arrays.asList(
                "bus access", "virtual bus access",
                "feature group", "abstract feature"
        )));
        // virtual processor：虚拟处理器，继承 processor 接口
        FEATURE_RULES.put("virtual processor", new LinkedHashSet<>(Arrays.asList(
                "bus access", "virtual bus access",
                "feature group", "abstract feature"
        )));
        // memory：内存，只能有 bus access（通过总线访问）
        FEATURE_RULES.put("memory", new LinkedHashSet<>(Arrays.asList(
                "bus access", "virtual bus access",
                "data access",
                "feature group", "abstract feature"
        )));
        // bus：总线，只能有 bus access
        FEATURE_RULES.put("bus", new LinkedHashSet<>(Arrays.asList(
                "bus access", "virtual bus access",
                "feature group", "abstract feature"
        )));
        // virtual bus：虚拟总线
        FEATURE_RULES.put("virtual bus", new LinkedHashSet<>(Arrays.asList(
                "bus access", "virtual bus access",
                "feature group", "abstract feature"
        )));
        // device：软硬件桥梁，既有 port（与软件通信）又有 bus access（与硬件总线通信）
        FEATURE_RULES.put("device", new LinkedHashSet<>(Arrays.asList(
                "data port", "event port", "event data port",
                "bus access", "virtual bus access",
                "data access", "subprogram access",
                "feature group", "abstract feature"
        )));

        // ===== 复合与抽象构件 =====
        // system：系统级集成，所有类型 feature 都可以有
        FEATURE_RULES.put("system", new LinkedHashSet<>(Arrays.asList(
                "data port", "event port", "event data port",
                "bus access", "virtual bus access",
                "data access", "subprogram access",
                "feature group", "abstract feature"
        )));
        // abstract：早期设计占位，允许所有 feature
        FEATURE_RULES.put("abstract", new LinkedHashSet<>(Arrays.asList(
                "data port", "event port", "event data port",
                "bus access", "virtual bus access",
                "data access", "subprogram access",
                "feature group", "abstract feature"
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
        public String sourceInstance;    // 源子组件实例名；代理连接时为 null（父端口）
        public String sourceFeature;     // 源端口/访问点名
        public String destInstance;      // 目标子组件实例名；代理连接时为 null（父端口）
        public String destFeature;       // 目标端口/访问点名
        public String connType;          // "port" 或 "bus access"
        public String connOperator;      // "->" 或 "<->"
        public String parentImpl;        // 所在的 implementation 名
        public int lineNumber;
        public boolean isDelegation;     // true = 代理连接（port delegation，一端是父组件端口）
        public String parentSide;        // 代理连接时："source"=源端是父端口, "dest"=目标端是父端口, "both"=两端都是父端口, null=非代理
    }

    /** Feature 的完整信息：分类、方向、数据类型 */
    public static class FeatureDetail {
        public String category;    // "data port", "event port", "event data port", "bus access", "data access", "other"
        public String direction;   // "in", "out", "requires", "provides"
        public String dataType;    // data 类型名（端口引用的 data 组件名），bus access 为总线类型名，未指定时为 null
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

        // 4o. 检测 implementation 中 subcomponents → connections → properties 顺序违规
        checkImplementationOrder(aadlContent, result);

        // 4q. 检测 thread 类型声明中的 requires/provides bus access feature
        checkThreadBusAccessFeature(aadlContent, result);

        // 预解析 feature 详情（供后续方向检测、操作符检测、feature 合规检测使用）
        Map<String, Map<String, FeatureDetail>> featureDetails = parseFeatureDetails(aadlContent);
        log.info("featureDetails 解析完成：{} 个组件有 feature 详情", featureDetails.size());

        // 4qa. 检测 feature 类型与组件类型是否匹配（每种组件允许的 feature 类别不同）
        checkFeatureTypeCompliance(aadlDeclarations, featureDetails, result);

        // 4r. 检测连接类型与端点 feature 类型是否匹配（port 连 port，access 连 access）
        Map<String, Map<String, String>> featureTypes = parseFeatureTypes(aadlContent);
        log.info("featureTypes 解析完成：{} 个组件有 feature 类型分类", featureTypes.size());
        checkConnectionTypeMatch(connectionRefs, featureTypes, subcomponentRefs, result);

        // 4t. 检测 data 组件中非法的 features 块（subprogram 可以有 features）
        checkDataComponentFeatures(aadlContent, result);

        // 4u. 检测 port 连接的端口方向（源端必须 out，目标端必须 in；代理连接两端方向相同）
        checkPortDirection(connectionRefs, featureDetails, subcomponentRefs, result);

        // 4s. 检测连接操作符是否正确（port 用 ->，bus access 用 <->；in out 双向端口允许 <->）
        checkConnectionOperator(connectionRefs, featureDetails, subcomponentRefs, result);

        // 4v. 检测 port 连接两端的数据类型一致性
        checkPortDataTypeConsistency(connectionRefs, featureDetails, subcomponentRefs, result);

        // 4w. 检测连接与实体类型的匹配（软件实体只能 port 连接，硬件实体只能 bus access 连接）
        checkConnectionEntityTypeMatch(connectionRefs, aadlDeclarations, result);

        // 4x. 检测属性绑定完整性（process/thread 缺少 Actual_Processor_Binding）
        checkPropertyBindingCompleteness(aadlContent, subcomponentRefs, result);

        // 4y. 检测数据类型一致性深度校验（连接两端 Data_Size 不匹配）
        checkDataSizeConsistency(aadlContent, connectionRefs, featureDetails, subcomponentRefs, result);

        // 4z. 检测命名空间冲突（实例名/连接名/类型名重名）
        checkNamingCollision(aadlContent, subcomponentRefs, connectionRefs, aadlDeclarations, result);

        // 4aa. 检测畸形 end 语句（逗号、多余空格、多个标识符等）
        checkMalformedEndStatements(aadlContent, result);

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
        // 或：  InstanceName : thread group TypeName.impl;
        // 或：  InstanceName : subprogram group TypeName.impl;
        // 或：  InstanceName : virtual bus TypeName.impl;
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+processor|virtual\\s+bus)\\s+(\\w+)\\.impl\\s*;"
        );

        // 当前所在的 implementation 上下文
        String currentImpl = null;
        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+processor|virtual\\s+bus)\\s+implementation\\s+(\\w+)\\.impl"
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

    /**
     * 解析 AADL 代码中所有组件类型声明的 features 块，提取每个 feature 的类型分类。
     * 用于连接类型匹配验证：port 连接的端点必须是 data/event port，bus access 连接的端点必须是 bus access。
     *
     * @return Map: 组件类型名 → (feature名 → 类型分类)
     *         类型分类值: "data port", "event port", "event data port", "bus access", "other"
     */
    private Map<String, Map<String, String>> parseFeatureTypes(String aadlContent) {
        Map<String, Map<String, String>> featureTypes = new LinkedHashMap<>();
        String[] lines = aadlContent.split("\n");

        Pattern typeDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+(\\w+)\\s*$"
        );
        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+(\\w+)\\s*$"
        );

        String currentTypeDecl = null;
        boolean inImplementation = false;
        boolean inFeaturesBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            if (implDeclPattern.matcher(line).find()) {
                inImplementation = true;
                inFeaturesBlock = false;
                currentTypeDecl = null;
                continue;
            }

            Matcher virtualTypeMatcher = virtualTypePattern.matcher(line);
            if (virtualTypeMatcher.find()) {
                currentTypeDecl = virtualTypeMatcher.group(1);
                inImplementation = false;
                inFeaturesBlock = false;
                continue;
            }

            Matcher typeMatcher = typeDeclPattern.matcher(line);
            if (typeMatcher.find()) {
                currentTypeDecl = typeMatcher.group(2);
                inImplementation = false;
                inFeaturesBlock = false;
                continue;
            }

            if (line.matches("end\\s+\\w+(\\.impl)?\\s*;")) {
                currentTypeDecl = null;
                inFeaturesBlock = false;
                inImplementation = false;
                continue;
            }

            if (line.equals("features") && !inImplementation && currentTypeDecl != null) {
                inFeaturesBlock = true;
                continue;
            }

            if (inFeaturesBlock && (line.equals("properties") || line.equals("flows") ||
                    line.equals("connections") || line.equals("subcomponents") ||
                    line.startsWith("annex") || line.startsWith("end "))) {
                inFeaturesBlock = false;
                continue;
            }

            if (inFeaturesBlock && currentTypeDecl != null && !line.isEmpty()) {
                String featureName = null;
                String typeCategory = null;

                // 按优先级匹配 feature 类型（双向端口 in out 优先于单向 in/out 匹配）
                if (line.matches("\\w+\\s*:\\s*in\\s+out\\s+data\\s+port.*") ||
                    line.matches("\\w+\\s*:\\s*in\\s+data\\s+port.*") ||
                    line.matches("\\w+\\s*:\\s*out\\s+data\\s+port.*")) {
                    typeCategory = "data port";
                } else if (line.matches("\\w+\\s*:\\s*in\\s+out\\s+event\\s+data\\s+port.*") ||
                           line.matches("\\w+\\s*:\\s*in\\s+event\\s+data\\s+port.*") ||
                           line.matches("\\w+\\s*:\\s*out\\s+event\\s+data\\s+port.*")) {
                    typeCategory = "event data port";
                } else if (line.matches("\\w+\\s*:\\s*in\\s+out\\s+event\\s+port.*") ||
                           line.matches("\\w+\\s*:\\s*in\\s+event\\s+port.*") ||
                           line.matches("\\w+\\s*:\\s*out\\s+event\\s+port.*")) {
                    typeCategory = "event port";
                } else if (line.matches("\\w+\\s*:\\s*(requires|provides)\\s+bus\\s+access.*")) {
                    typeCategory = "bus access";
                } else if (line.matches("\\w+\\s*:\\s*(requires|provides)\\s+data\\s+access.*")) {
                    typeCategory = "data access";
                } else if (line.matches("\\w+\\s*:\\s*(in\\s+out|in|out)\\s+port.*")) {
                    typeCategory = "data port"; // 纯 port 也归类为 data port
                } else {
                    typeCategory = "other";
                }

                // 提取 feature 名
                Matcher nameMatcher = Pattern.compile("^(\\w+)\\s*:").matcher(line);
                if (nameMatcher.find()) {
                    featureName = nameMatcher.group(1);
                }

                if (featureName != null) {
                    featureTypes.computeIfAbsent(currentTypeDecl, k -> new LinkedHashMap<>())
                            .put(featureName, typeCategory);
                }
            }
        }

        return featureTypes;
    }

    /**
     * 解析 AADL 代码中所有组件类型声明的 features，提取完整信息（分类、方向、数据类型）。
     * 用于端口方向验证和数据类型一致性验证。
     *
     * @return Map: 组件类型名 → (feature名 → FeatureDetail)
     */
    private Map<String, Map<String, FeatureDetail>> parseFeatureDetails(String aadlContent) {
        Map<String, Map<String, FeatureDetail>> details = new LinkedHashMap<>();
        String[] lines = aadlContent.split("\n");

        Pattern typeDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+(\\w+)\\s*$"
        );
        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+(\\w+)\\s*$"
        );

        String currentTypeDecl = null;
        boolean inImplementation = false;
        boolean inFeaturesBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            if (implDeclPattern.matcher(line).find()) {
                inImplementation = true;
                inFeaturesBlock = false;
                currentTypeDecl = null;
                continue;
            }

            Matcher virtualTypeMatcher = virtualTypePattern.matcher(line);
            if (virtualTypeMatcher.find()) {
                currentTypeDecl = virtualTypeMatcher.group(1);
                inImplementation = false;
                inFeaturesBlock = false;
                continue;
            }

            Matcher typeMatcher = typeDeclPattern.matcher(line);
            if (typeMatcher.find()) {
                currentTypeDecl = typeMatcher.group(2);
                inImplementation = false;
                inFeaturesBlock = false;
                continue;
            }

            if (line.matches("end\\s+\\w+(\\.impl)?\\s*;")) {
                currentTypeDecl = null;
                inFeaturesBlock = false;
                inImplementation = false;
                continue;
            }

            if (line.equals("features") && !inImplementation && currentTypeDecl != null) {
                inFeaturesBlock = true;
                continue;
            }

            if (inFeaturesBlock && (line.equals("properties") || line.equals("flows") ||
                    line.equals("connections") || line.equals("subcomponents") ||
                    line.startsWith("annex") || line.startsWith("end "))) {
                inFeaturesBlock = false;
                continue;
            }

            if (inFeaturesBlock && currentTypeDecl != null && !line.isEmpty()) {
                FeatureDetail fd = parseSingleFeature(line);
                if (fd != null) {
                    Matcher nameMatcher = Pattern.compile("^(\\w+)\\s*:").matcher(line);
                    if (nameMatcher.find()) {
                        String featureName = nameMatcher.group(1);
                        details.computeIfAbsent(currentTypeDecl, k -> new LinkedHashMap<>())
                                .put(featureName, fd);
                    }
                }
            }
        }

        return details;
    }

    /**
     * 解析单行 feature 声明，提取分类、方向和数据类型。
     */
    private FeatureDetail parseSingleFeature(String line) {
        FeatureDetail fd = new FeatureDetail();

        // in out data port TypeName（双向端口，必须放在 in/out 单独匹配之前，避免被 in 模式先命中）
        if (line.matches("\\w+\\s*:\\s*in\\s+out\\s+data\\s+port.*")) {
            fd.category = "data port";
            fd.direction = "in out";
            fd.dataType = extractDataType(line);
        } else if (line.matches("\\w+\\s*:\\s*in\\s+out\\s+event\\s+data\\s+port.*")) {
            fd.category = "event data port";
            fd.direction = "in out";
            fd.dataType = extractDataType(line);
        } else if (line.matches("\\w+\\s*:\\s*in\\s+out\\s+event\\s+port.*")) {
            fd.category = "event port";
            fd.direction = "in out";
            fd.dataType = null;
        } else if (line.matches("\\w+\\s*:\\s*in\\s+data\\s+port.*")) {
            fd.category = "data port";
            fd.direction = "in";
            fd.dataType = extractDataType(line);
        } else if (line.matches("\\w+\\s*:\\s*out\\s+data\\s+port.*")) {
            fd.category = "data port";
            fd.direction = "out";
            fd.dataType = extractDataType(line);
        } else if (line.matches("\\w+\\s*:\\s*in\\s+event\\s+data\\s+port.*")) {
            fd.category = "event data port";
            fd.direction = "in";
            fd.dataType = extractDataType(line);
        } else if (line.matches("\\w+\\s*:\\s*out\\s+event\\s+data\\s+port.*")) {
            fd.category = "event data port";
            fd.direction = "out";
            fd.dataType = extractDataType(line);
        } else if (line.matches("\\w+\\s*:\\s*in\\s+event\\s+port.*")) {
            fd.category = "event port";
            fd.direction = "in";
            fd.dataType = null;
        } else if (line.matches("\\w+\\s*:\\s*out\\s+event\\s+port.*")) {
            fd.category = "event port";
            fd.direction = "out";
            fd.dataType = null;
        } else if (line.matches("\\w+\\s*:\\s*requires\\s+bus\\s+access.*")) {
            fd.category = "bus access";
            fd.direction = "requires";
            fd.dataType = extractAccessTypeName(line, "bus");
        } else if (line.matches("\\w+\\s*:\\s*provides\\s+bus\\s+access.*")) {
            fd.category = "bus access";
            fd.direction = "provides";
            fd.dataType = extractAccessTypeName(line, "bus");
        } else if (line.matches("\\w+\\s*:\\s*requires\\s+data\\s+access.*")) {
            fd.category = "data access";
            fd.direction = "requires";
            fd.dataType = extractAccessTypeName(line, "data");
        } else if (line.matches("\\w+\\s*:\\s*provides\\s+data\\s+access.*")) {
            fd.category = "data access";
            fd.direction = "provides";
            fd.dataType = extractAccessTypeName(line, "data");
        } else if (line.matches("\\w+\\s*:\\s*in\\s+port.*")) {
            fd.category = "data port";
            fd.direction = "in";
            fd.dataType = extractDataType(line);
        } else if (line.matches("\\w+\\s*:\\s*out\\s+port.*")) {
            fd.category = "data port";
            fd.direction = "out";
            fd.dataType = extractDataType(line);
        } else {
            return null; // 无法识别的 feature 行
        }

        return fd;
    }

    /** 从端口声明行中提取数据类型名 */
    private String extractDataType(String line) {
        // 匹配: port TypeName; 或 port TypeName;  (TypeName 在 port 关键字之后)
        Matcher m = Pattern.compile(
                "(?:data\\s+port|event\\s+data\\s+port|event\\s+port|port)\\s+([A-Za-z_]\\w*)",
                Pattern.CASE_INSENSITIVE
        ).matcher(line);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /** 从 access 声明行中提取访问的类型名 */
    private String extractAccessTypeName(String line, String accessKind) {
        Matcher m = Pattern.compile(
                accessKind + "\\s+access\\s+([A-Za-z_]\\w*)",
                Pattern.CASE_INSENSITIVE
        ).matcher(line);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * 解析每个 implementation 中的 subcomponents 实例名到类型名的映射。
     * 用于动态推断连接行中缺失的端口名。
     *
     * @return Map: implementation名 → (实例名 → 类型名)
     */
    private Map<String, Map<String, String>> parseSubcomponentInstances(String aadlContent) {
        Map<String, Map<String, String>> implInstances = new LinkedHashMap<>();
        String[] lines = aadlContent.split("\n");

        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+processor|virtual\\s+bus)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+processor|virtual\\s+bus)\\s+(\\w+)\\.impl\\s*;"
        );

        String currentImpl = null;
        boolean inSubcomponents = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;

            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                inSubcomponents = false;
                continue;
            }

            if (currentImpl != null && trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                inSubcomponents = false;
                continue;
            }

            if (currentImpl != null && trimmed.equals("subcomponents")) {
                inSubcomponents = true;
                continue;
            }

            if (inSubcomponents && (trimmed.equals("connections") || trimmed.equals("properties") ||
                    trimmed.equals("features") || trimmed.equals("flows") ||
                    trimmed.startsWith("end ") || trimmed.startsWith("annex"))) {
                inSubcomponents = false;
                continue;
            }

            if (inSubcomponents && currentImpl != null) {
                Matcher subcompMatcher = subcompPattern.matcher(trimmed);
                if (subcompMatcher.find()) {
                    String instanceName = subcompMatcher.group(1);
                    String typeName = subcompMatcher.group(3);
                    implInstances.computeIfAbsent(currentImpl, k -> new LinkedHashMap<>())
                            .put(instanceName, typeName);
                }
            }
        }

        return implInstances;
    }

    /**
     * 从组件类型的 features 中动态推断指定方向的端口名。
     *
     * @param featureDetails 组件类型 → (feature名 → FeatureDetail)
     * @param componentType  组件类型名
     * @param direction      需要的方向 ("out" 或 "in")
     * @return 第一个匹配方向的端口名，未找到时返回 null
     */
    private String inferPortName(Map<String, Map<String, FeatureDetail>> featureDetails,
                                  String componentType, String direction) {
        if (componentType == null) return null;
        Map<String, FeatureDetail> features = featureDetails.get(componentType);
        if (features == null || features.isEmpty()) return null;

        for (Map.Entry<String, FeatureDetail> entry : features.entrySet()) {
            FeatureDetail fd = entry.getValue();
            if (direction.equals(fd.direction) &&
                    ("data port".equals(fd.category) || "event data port".equals(fd.category) ||
                     "event port".equals(fd.category))) {
                return entry.getKey();
            }
        }
        return null;
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
        // 格式1（同级连接）: connName : port Instance1.feature1 -> Instance2.feature2;
        // 格式2（bus access）: connName : bus access Instance1.feature1 <-> Instance2.feature2;
        // 格式3（输入代理）: connName : port parentFeature -> Instance.feature;  （源端是父端口）
        // 格式4（输出代理）: connName : port Instance.feature -> parentFeature;  （目标端是父端口）
        // 格式5（双端代理）: connName : port parentFeature1 <-> parentFeature2;（两端都是父端口，少见但合法）
        // 正则策略：分别匹配三种情况
        // 完整格式（两端都是 实例.端口）：(\w+)\.(\w+)\s*(->|<->)\s*(\w+)\.(\w+)
        // 源端是父端口（无点号）：(\w+)\s*(->|<->)\s*(\w+)\.(\w+)  （但需要排除 connName : type 部分，所以需要更精确的正则）
        //
        // 采用更灵活的方式：先提取 connName : type 后面的部分，再解析箭头两侧
        Pattern connHeaderPattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*(port|bus\\s+access)\\s+(.*)$"
        );
        Pattern arrowPattern = Pattern.compile("\\s*(->|<->)\\s*");
        Pattern dotRefPattern = Pattern.compile("^(\\w+)\\.(\\w+)\\s*$");
        Pattern bareRefPattern = Pattern.compile("^(\\w+)\\s*$");

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

            Matcher headerMatcher = connHeaderPattern.matcher(line);
            if (!headerMatcher.find()) {
                continue;
            }

            String connName = headerMatcher.group(1);
            String connType = headerMatcher.group(2).replaceAll("\\s+", " ");
            String rest = headerMatcher.group(3);

            // 去掉内联属性 {...} 和末尾分号
            rest = rest.replaceAll("\\{[^}]*\\}", "").replaceAll(";.*$", "").trim();

            // 用箭头分割左右两侧
            Matcher arrowMatcher = arrowPattern.matcher(rest);
            if (!arrowMatcher.find()) {
                continue; // 找不到箭头，跳过
            }
            String op = arrowMatcher.group(1);
            String leftSide = rest.substring(0, arrowMatcher.start()).trim();
            String rightSide = rest.substring(arrowMatcher.end()).trim();

            ConnectionRef ref = new ConnectionRef();
            ref.connName = connName;
            ref.connType = connType;
            ref.connOperator = op;
            ref.parentImpl = currentImpl;
            ref.lineNumber = i + 1;
            ref.isDelegation = false;
            ref.parentSide = null;

            // 解析左侧（源端）
            Matcher leftDot = dotRefPattern.matcher(leftSide);
            Matcher leftBare = bareRefPattern.matcher(leftSide);
            if (leftDot.find()) {
                ref.sourceInstance = leftDot.group(1);
                ref.sourceFeature = leftDot.group(2);
            } else if (leftBare.find()) {
                ref.sourceInstance = null; // 父端口，无实例名
                ref.sourceFeature = leftBare.group(1);
            } else {
                continue; // 无法解析
            }

            // 解析右侧（目标端）
            Matcher rightDot = dotRefPattern.matcher(rightSide);
            Matcher rightBare = bareRefPattern.matcher(rightSide);
            if (rightDot.find()) {
                ref.destInstance = rightDot.group(1);
                ref.destFeature = rightDot.group(2);
            } else if (rightBare.find()) {
                ref.destInstance = null; // 父端口，无实例名
                ref.destFeature = rightBare.group(1);
            } else {
                continue; // 无法解析
            }

            // 判断是否为代理连接
            boolean sourceIsParent = (ref.sourceInstance == null);
            boolean destIsParent = (ref.destInstance == null);
            if (sourceIsParent || destIsParent) {
                ref.isDelegation = true;
                if (sourceIsParent && destIsParent) {
                    ref.parentSide = "both";
                } else if (sourceIsParent) {
                    ref.parentSide = "source";
                } else {
                    ref.parentSide = "dest";
                }
            }

            refs.add(ref);
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

            // 检查源端
            if (conn.sourceInstance == null) {
                // 代理连接：源端是父组件自身的端口，检查 parentImpl 对应类型的 features
                checkParentEndpoint(conn, conn.sourceFeature,
                        conn.destInstance, conn.destFeature,
                        instanceMap, componentFeatures, declarations, result, "源");
            } else {
                checkSingleEndpoint(conn, conn.sourceInstance, conn.sourceFeature,
                        conn.destInstance, conn.destFeature,
                        instanceMap, componentFeatures, declarations, result, "源");
            }

            // 检查目标端
            if (conn.destInstance == null) {
                // 代理连接：目标端是父组件自身的端口
                checkParentEndpoint(conn, conn.destFeature,
                        conn.sourceInstance, conn.sourceFeature,
                        instanceMap, componentFeatures, declarations, result, "目标");
            } else {
                checkSingleEndpoint(conn, conn.destInstance, conn.destFeature,
                        conn.sourceInstance, conn.sourceFeature,
                        instanceMap, componentFeatures, declarations, result, "目标");
            }
        }
    }

    /**
     * 检查代理连接中父组件端口端点。
     * 父端口必须存在于 parentImpl 对应组件类型的 features 中。
     */
    private void checkParentEndpoint(ConnectionRef conn, String featureName,
                                      String otherInstance, String otherFeature,
                                      Map<String, String> instanceMap,
                                      Map<String, Map<String, String>> componentFeatures,
                                      Map<String, AadlDeclaration> declarations,
                                      ValidationResult result, String endpointLabel) {
        // 父类型名就是 parentImpl（因为 implementation 名为 TypeName.impl，类型名就是 TypeName）
        String parentTypeName = conn.parentImpl;

        // data 组件不能有端口
        AadlDeclaration parentDecl = declarations.get(parentTypeName);
        if (parentDecl != null && "data".equals(parentDecl.type)) {
            result.errors.add(String.format(
                    "第%d行: 连接 '%s' 的%s端引用父组件 '%s' 的端口 '%s'，但 '%s' 是 data 组件。data 组件严禁拥有 features 块。",
                    conn.lineNumber, conn.connName, endpointLabel, parentTypeName, featureName, parentTypeName
            ));
            return;
        }

        // 检查父类型的 features
        Map<String, String> parentFeatures = componentFeatures.get(parentTypeName);
        if (parentFeatures == null || parentFeatures.isEmpty()) {
            result.errors.add(String.format(
                    "第%d行: 连接 '%s' 的%s端引用父组件端口 '%s'（代理连接），但组件类型 '%s' 没有 features 块或 features 为空",
                    conn.lineNumber, conn.connName, endpointLabel, featureName, parentTypeName
            ));
            String dataType = resolveDataType(otherInstance, otherFeature, instanceMap, componentFeatures);
            result.missingFeatures.computeIfAbsent(parentTypeName, k -> new LinkedHashMap<>())
                    .put(featureName, dataType != null ? dataType : "");
        } else if (!parentFeatures.containsKey(featureName)) {
            String availableFeatures = String.join(", ", parentFeatures.keySet());
            result.errors.add(String.format(
                    "第%d行: 连接 '%s' 的%s端引用父组件端口 '%s'（代理连接），但组件类型 '%s' 的 features 中不存在 '%s'（可用: %s）",
                    conn.lineNumber, conn.connName, endpointLabel, featureName,
                    parentTypeName, featureName, availableFeatures
            ));
            String dataType = resolveDataType(otherInstance, otherFeature, instanceMap, componentFeatures);
            result.missingFeatures.computeIfAbsent(parentTypeName, k -> new LinkedHashMap<>())
                    .put(featureName, dataType != null ? dataType : "");
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

        // 2. 检查组件类型是否为 data —— data 组件是纯类型分类器，严禁拥有 features 块
        AadlDeclaration decl = declarations.get(typeName);
        if (decl != null && "data".equals(decl.type)) {
            result.errors.add(String.format(
                    "第%d行: 连接 '%s' 的%s端引用 '%s.%s'，但 '%s' 是 data 组件。data 组件是纯类型分类器，严禁拥有 features 块。" +
                    "该连接无效，应删除此连接行或修正为引用主动构件（thread/process/device/system）的端口",
                    conn.lineNumber, conn.connName, endpointLabel, instanceName, featureName, typeName
            ));
            // 不加入 missingFeatures，避免为 data 组件注入 feature
            return;
        }

        // 3. 检查端口名是否在对应组件类型的 features 中声明过
        Map<String, String> features = componentFeatures.get(typeName);
        if (features == null || features.isEmpty()) {
            // 组件类型没有 features 块 → 需要补全（data 组件已在上方拦截，此处仅处理主动构件）
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

    /**
     * 当 resolveDataType 无法从连接另一端获取数据类型时，从 AADL 声明中回退查找合适的 data 组件。
     *
     * 查找策略：
     * 1. 尝试从 feature 名中提取关键词（如 in_Pressure → Pressure），匹配同名 data 组件
     * 2. 尝试 feature 名直接匹配 data 组件名
     * 3. 如果以上都失败，返回第一个可用的 data 组件名
     * 4. 如果没有任何 data 组件，返回 Base_Type 作为最终回退
     *
     * @param featName     feature 名称（如 in_Pressure, out_Control）
     * @param declarations AADL 声明表
     * @return 数据类型名
     */
    private String findFallbackDataType(String featName,
                                         Map<String, AadlDeclaration> declarations) {
        if (featName == null || declarations == null || declarations.isEmpty()) {
            return "Base_Type";
        }

        // 收集所有 data 类型的组件名
        List<String> dataComponents = new ArrayList<>();
        for (Map.Entry<String, AadlDeclaration> entry : declarations.entrySet()) {
            if ("data".equalsIgnoreCase(entry.getValue().type)) {
                dataComponents.add(entry.getKey());
            }
        }

        if (dataComponents.isEmpty()) {
            return "Base_Type";
        }

        // 策略1：从 feature 名提取关键词匹配 data 组件
        // 去掉 in_/out_ 前缀后尝试匹配
        String stripped = featName;
        if (stripped.toLowerCase().startsWith("in_")) {
            stripped = stripped.substring(3);
        } else if (stripped.toLowerCase().startsWith("out_")) {
            stripped = stripped.substring(4);
        }

        for (String dataName : dataComponents) {
            if (dataName.equalsIgnoreCase(stripped) ||
                dataName.equalsIgnoreCase(stripped + "Data") ||
                dataName.equalsIgnoreCase(stripped + "Type") ||
                dataName.equalsIgnoreCase(stripped + "Signal")) {
                return dataName;
            }
        }

        // 策略2：feature 名直接匹配
        for (String dataName : dataComponents) {
            if (dataName.equalsIgnoreCase(featName)) {
                return dataName;
            }
        }

        // 策略3：返回第一个可用的 data 组件
        return dataComponents.get(0);
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

    /**
     * 4q. 检测 thread 类型声明中的 requires bus access feature。
     *
     * 分层架构规范：线程（thread）专注功能逻辑与数据流转，不应直接访问物理总线。
     * requires bus access 只能出现在 device 或 processor 的 features 中。
     * thread 的 features 中只能有 in/out data port 或 in/out event port。
     */
    private void checkThreadBusAccessFeature(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        // 匹配 thread 类型声明（不含 implementation）
        Pattern threadTypePattern = Pattern.compile(
                "^\\s*thread\\s+(\\w+)\\s*$"
        );
        // 匹配 requires bus access feature 行
        Pattern busAccessFeaturePattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*requires\\s+bus\\s+access\\s+", Pattern.CASE_INSENSITIVE
        );
        // 匹配 provides bus access feature 行
        Pattern providesBusAccessPattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*provides\\s+bus\\s+access\\s+", Pattern.CASE_INSENSITIVE
        );

        boolean inThreadType = false;
        boolean inFeaturesBlock = false;
        String currentThreadName = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            // thread 类型声明
            Matcher threadMatcher = threadTypePattern.matcher(line);
            if (threadMatcher.find()) {
                inThreadType = true;
                currentThreadName = threadMatcher.group(1);
                inFeaturesBlock = false;
                continue;
            }

            // 退出 thread 类型声明
            if (inThreadType && line.matches("end\\s+" + Pattern.quote(currentThreadName) + "\\s*;")) {
                inThreadType = false;
                currentThreadName = null;
                inFeaturesBlock = false;
                continue;
            }

            // 也可能是遇到 implementation 声明退出
            if (inThreadType && line.matches("thread\\s+implementation\\s+\\w+\\.impl")) {
                inThreadType = false;
                currentThreadName = null;
                inFeaturesBlock = false;
                continue;
            }

            // features 块开始
            if (inThreadType && line.equals("features")) {
                inFeaturesBlock = true;
                continue;
            }

            // 退出 features 块
            if (inFeaturesBlock && (line.equals("properties") || line.equals("flows") ||
                    line.equals("connections") || line.equals("subcomponents") ||
                    line.startsWith("annex") || line.startsWith("end "))) {
                inFeaturesBlock = false;
                continue;
            }

            // 检测 requires bus access 或 provides bus access
            if (inFeaturesBlock) {
                Matcher baMatcher = busAccessFeaturePattern.matcher(line);
                if (baMatcher.find()) {
                    String featureName = baMatcher.group(1);
                    result.errors.add(String.format(
                            "第%d行: 分层架构违规 - thread '%s' 的 feature '%s' 使用了 requires bus access; " +
                            "线程不应直接访问物理总线，bus access 只能出现在 device 或 processor 中; " +
                            "线程的 features 只能有 in/out data port 或 event port",
                            i + 1, currentThreadName, featureName
                    ));
                }
                Matcher pbaMatcher = providesBusAccessPattern.matcher(line);
                if (pbaMatcher.find()) {
                    String featureName = pbaMatcher.group(1);
                    result.errors.add(String.format(
                            "第%d行: 分层架构违规 - thread '%s' 的 feature '%s' 使用了 provides bus access; " +
                            "线程不应直接访问物理总线，bus access 只能出现在 device 或 processor 中",
                            i + 1, currentThreadName, featureName
                    ));
                }
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
     * 4qa. 检测 feature 类型与组件类型是否匹配。
     *
     * 每种组件类型只允许拥有特定类别的 feature：
     * - 软件组件（process/thread）：只能有 port 类和 access 类，不能有 bus access
     * - 纯硬件组件（processor/memory/bus）：只能有 bus access，不能有 data port
     * - device：软硬件桥梁，port 和 bus access 都可以有
     * - data：被动类型，不能有 features
     * - system/abstract：万能，允许所有 feature
     *
     * @param declarations    AADL 声明表（含组件类型）
     * @param featureDetails  每个组件类型的 feature 详情（含 category）
     * @param result          验证结果
     */
    private void checkFeatureTypeCompliance(Map<String, AadlDeclaration> declarations,
                                             Map<String, Map<String, FeatureDetail>> featureDetails,
                                             ValidationResult result) {
        if (featureDetails == null || featureDetails.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Map<String, FeatureDetail>> entry : featureDetails.entrySet()) {
            String compName = entry.getKey();
            Map<String, FeatureDetail> feats = entry.getValue();

            AadlDeclaration decl = declarations.get(compName);
            if (decl == null || decl.type == null) {
                continue;
            }
            String compType = decl.type;

            // 获取该组件类型允许的 feature 类别
            Set<String> allowedCategories = FEATURE_RULES.get(compType);
            if (allowedCategories == null) {
                continue; // 未知组件类型，跳过
            }

            // data 组件由 checkDataComponentFeatures 专门检测，此处跳过
            if ("data".equals(compType)) {
                continue;
            }

            for (Map.Entry<String, FeatureDetail> featEntry : feats.entrySet()) {
                String featName = featEntry.getKey();
                FeatureDetail fd = featEntry.getValue();

                if (!allowedCategories.contains(fd.category)) {
                    String allowedStr = allowedCategories.isEmpty()
                            ? "(无)"
                            : String.join(", ", allowedCategories);
                    result.errors.add(String.format(
                            "feature 类型违规 - %s 组件 '%s' 的 feature '%s' 类型为 '%s'，该组件不允许拥有此类 feature; " +
                            "%s 组件允许的 feature 类型: %s",
                            compType, compName, featName, fd.category,
                            compType, allowedStr
                    ));
                }
            }
        }
    }

    /**
     * 4r. 检测连接类型与端点 feature 类型是否匹配。
     *
     * SAE AADL 标准规范：
     * - port 连接（connName : port ...）的端点必须是 data port / event port / event data port
     * - bus access 连接（connName : bus access ...）的端点必须是 requires/provides bus access
     * - 严禁混连：bus access 端点不能与 port 端点直接相连
     *
     * @param connections   连接引用列表
     * @param featureTypes  组件类型 → (feature名 → 类型分类)
     * @param subcomponentRefs subcomponents 引用列表
     * @param result        验证结果
     */
    private void checkConnectionTypeMatch(List<ConnectionRef> connections,
                                           Map<String, Map<String, String>> featureTypes,
                                           List<SubcomponentRef> subcomponentRefs,
                                           ValidationResult result) {
        // 建立 implementation → (实例名 → 类型名) 映射
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
            if (instanceMap == null) {
                continue;
            }

            String sourceType = instanceMap.get(conn.sourceInstance);
            String destType = instanceMap.get(conn.destInstance);

            String sourceFeatureType = null;
            String destFeatureType = null;

            if (sourceType != null) {
                Map<String, String> sourceFeatures = featureTypes.get(sourceType);
                if (sourceFeatures != null) {
                    sourceFeatureType = sourceFeatures.get(conn.sourceFeature);
                }
            }
            if (destType != null) {
                Map<String, String> destFeatures = featureTypes.get(destType);
                if (destFeatures != null) {
                    destFeatureType = destFeatures.get(conn.destFeature);
                }
            }

            // 无法确定类型时跳过（可能 feature 尚未声明，由其他检查处理）
            if (sourceFeatureType == null || destFeatureType == null) {
                continue;
            }

            boolean sourceIsPort = "data port".equals(sourceFeatureType) ||
                    "event port".equals(sourceFeatureType) ||
                    "event data port".equals(sourceFeatureType);
            boolean destIsPort = "data port".equals(destFeatureType) ||
                    "event port".equals(destFeatureType) ||
                    "event data port".equals(destFeatureType);
            boolean sourceIsAccess = "bus access".equals(sourceFeatureType) ||
                    "data access".equals(sourceFeatureType);
            boolean destIsAccess = "bus access".equals(destFeatureType) ||
                    "data access".equals(destFeatureType);

            // 检查 1：port 连接的端点必须都是 port 类型
            if ("port".equals(conn.connType)) {
                if (!sourceIsPort || !destIsPort) {
                    result.errors.add(String.format(
                            "第%d行: 连接类型不匹配 - 连接 '%s' 使用 port 类型，但端点 feature 类型不匹配; " +
                            "源端 %s.%s 类型=%s, 目标端 %s.%s 类型=%s; port 连接的两端必须是 data port / event port",
                            conn.lineNumber, conn.connName,
                            conn.sourceInstance, conn.sourceFeature, sourceFeatureType,
                            conn.destInstance, conn.destFeature, destFeatureType
                    ));
                }
            }

            // 检查 2：bus access 连接的端点必须都是 bus access 类型
            if ("bus access".equals(conn.connType)) {
                if (!sourceIsAccess || !destIsAccess) {
                    result.errors.add(String.format(
                            "第%d行: 连接类型不匹配 - 连接 '%s' 使用 bus access 类型，但端点 feature 类型不匹配; " +
                            "源端 %s.%s 类型=%s, 目标端 %s.%s 类型=%s; bus access 连接的两端必须是 requires/provides bus access",
                            conn.lineNumber, conn.connName,
                            conn.sourceInstance, conn.sourceFeature, sourceFeatureType,
                            conn.destInstance, conn.destFeature, destFeatureType
                    ));
                }
            }

            // 检查 3：严禁 port 与 bus access 混连
            if ((sourceIsPort && destIsAccess) || (sourceIsAccess && destIsPort)) {
                result.errors.add(String.format(
                        "第%d行: 硬件与软件隔离违规 - 连接 '%s' 混连了 port 和 bus access; " +
                        "源端 %s.%s 类型=%s, 目标端 %s.%s 类型=%s; bus access 不能与 port 直接相连",
                        conn.lineNumber, conn.connName,
                        conn.sourceInstance, conn.sourceFeature, sourceFeatureType,
                        conn.destInstance, conn.destFeature, destFeatureType
                ));
            }
        }
    }

    /**
     * 4s. 检测连接操作符是否正确。
     *
     * SAE AADL 标准规范：
     * - port 连接必须使用单向操作符 ->
     * - bus access 连接必须使用双向操作符 <->
     *
     * @param aadlContent AADL 代码
     * @param result      验证结果
     */
    /**
     * 4s. 检测连接操作符是否正确。
     *
     * SAE AADL 标准规范：
     * - bus access 连接必须使用双向 <->（总线访问是双向绑定）
     * - port 连接默认使用单向 ->（数据流从 out 到 in）
     * - 例外：如果连接两端都是 in out data port（双向端口），使用 <-> 是合法的
     *
     * @param connections    解析出的连接引用
     * @param featureDetails 每个组件类型的 feature 详情（含方向信息）
     * @param subcomponentRefs subcomponents 引用（用于建立实例→类型映射）
     * @param result         验证结果
     */
    private void checkConnectionOperator(List<ConnectionRef> connections,
                                          Map<String, Map<String, FeatureDetail>> featureDetails,
                                          List<SubcomponentRef> subcomponentRefs,
                                          ValidationResult result) {
        // 建立 implementation → (实例名 → 类型名) 映射
        Map<String, Map<String, String>> implInstanceMap = new HashMap<>();
        for (SubcomponentRef ref : subcomponentRefs) {
            if (ref.parentImpl != null) {
                implInstanceMap.computeIfAbsent(ref.parentImpl, k -> new HashMap<>())
                        .put(ref.instanceName, ref.typeName);
            }
        }

        for (ConnectionRef conn : connections) {
            if (conn.parentImpl == null || conn.connOperator == null) {
                continue;
            }

            // bus access 连接必须使用 <->
            if ("bus access".equals(conn.connType)) {
                if ("->".equals(conn.connOperator)) {
                    result.errors.add(String.format(
                            "第%d行: 连接操作符错误 - bus access 连接 '%s' 使用了单向 ->; " +
                            "bus access 连接必须使用双向 <->",
                            conn.lineNumber, conn.connName
                    ));
                }
                continue;
            }

            // port 连接
            if ("port".equals(conn.connType)) {
                if ("<->".equals(conn.connOperator)) {
                    // 检查两端是否都是 in out 双向端口
                    Map<String, String> instanceMap = implInstanceMap.get(conn.parentImpl);
                    boolean sourceIsInOut = isInOutPort(conn.sourceInstance, conn.sourceFeature,
                            conn.parentImpl, instanceMap, featureDetails);
                    boolean destIsInOut = isInOutPort(conn.destInstance, conn.destFeature,
                            conn.parentImpl, instanceMap, featureDetails);

                    if (!sourceIsInOut || !destIsInOut) {
                        // 不是双向端口却用了 <->，报 warning
                        String reason;
                        if (!sourceIsInOut && !destIsInOut) {
                            reason = "两端都不是 in out 双向端口";
                        } else if (!sourceIsInOut) {
                            reason = "源端不是 in out 双向端口";
                        } else {
                            reason = "目标端不是 in out 双向端口";
                        }
                        result.warnings.add(String.format(
                                "第%d行: 连接操作符建议 - port 连接 '%s' 使用了双向 <->（%s）; " +
                                "普通 port 连接应使用单向 -> 表示数据流方向；只有 in out data port（双向端口）之间才能使用 <->",
                                conn.lineNumber, conn.connName, reason
                        ));
                    }
                    // 两端都是 in out 双向端口，<-> 是合法的，不报错
                }
            }
        }
    }

    /**
     * 判断指定端点是否为 in out 双向端口。
     */
    private boolean isInOutPort(String instanceName, String featureName,
                                 String parentImpl,
                                 Map<String, String> instanceMap,
                                 Map<String, Map<String, FeatureDetail>> featureDetails) {
        String typeName;
        if (instanceName != null && instanceMap != null) {
            typeName = instanceMap.get(instanceName);
        } else {
            // 父端口，类型名就是 parentImpl
            typeName = parentImpl;
        }
        if (typeName == null) {
            return false;
        }
        Map<String, FeatureDetail> features = featureDetails.get(typeName);
        if (features == null) {
            return false;
        }
        FeatureDetail fd = features.get(featureName);
        return fd != null && "in out".equals(fd.direction);
    }

    /**
     * 4t. 检测 data 组件中非法的 features 块。
     *
     * SAE AADL 标准规范：
     * - data 是被动构件（passive component），作为类型分类器（classifier）被端口引用
     * - data 不应直接拥有顶层 features（如端口、访问特征）
     * - 数据的流动和交互应由主动构件（thread、process、device）持有端口并完成收发
     * - data 组件只需声明类型，例如 `data CommandData end CommandData;`，
     *   然后被端口引用：`out data port CommandData;`
     * - 注意：subprogram 可以定义 features（如 in/out parameter），不属于被动构件约束范围
     *
     * @param aadlContent AADL 代码
     * @param result      验证结果
     */
    private void checkDataComponentFeatures(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        // 匹配 data 类型声明（不含 implementation）
        // 注意：subprogram 可以定义 features，只有 data 才是纯类型分类器
        Pattern passiveTypePattern = Pattern.compile(
                "^\\s*data\\s+(\\w+)\\s*$"
        );
        Pattern implDeclPattern = Pattern.compile(
                "^\\s*data\\s+implementation\\s+(\\w+)\\.impl"
        );

        String currentTypeName = null;
        String currentCompType = null;
        boolean inPassiveType = false;
        boolean inFeaturesBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            // 进入 implementation 声明 → 退出类型声明上下文
            if (implDeclPattern.matcher(line).find()) {
                inPassiveType = false;
                inFeaturesBlock = false;
                currentTypeName = null;
                currentCompType = null;
                continue;
            }

            // 匹配 data 类型声明
            Matcher passiveMatcher = passiveTypePattern.matcher(line);
            if (passiveMatcher.find()) {
                currentCompType = "data";
                currentTypeName = passiveMatcher.group(1);
                inPassiveType = true;
                inFeaturesBlock = false;
                continue;
            }

            // 退出类型声明
            if (inPassiveType && currentTypeName != null &&
                    line.matches("end\\s+" + Pattern.quote(currentTypeName) + "\\s*;")) {
                inPassiveType = false;
                inFeaturesBlock = false;
                currentTypeName = null;
                currentCompType = null;
                continue;
            }

            // 检测 features 块开始
            if (inPassiveType && line.equals("features")) {
                inFeaturesBlock = true;
                result.errors.add(String.format(
                        "第%d行: data 组件违规 - data 组件 '%s' 中出现了 features 块; " +
                        "data 是被动构件（类型分类器），不应直接拥有 features; " +
                        "数据的流动应由主动构件（thread/process/device）的端口引用 data 类型来完成",
                        i + 1, currentTypeName
                ));
                continue;
            }

            // features 块结束
            if (inFeaturesBlock && (line.equals("properties") || line.equals("flows") ||
                    line.equals("connections") || line.equals("subcomponents") ||
                    line.startsWith("annex") || line.startsWith("end "))) {
                inFeaturesBlock = false;
                continue;
            }
        }
    }

    /**
     * 4u. 检测 port 连接的端口方向是否正确。
     *
     * SAE AADL 标准规范：
     * - 数据流向必须符合端口定义的输入输出方向
     * - 源端（-> 左侧）必须是 out port 或 in out port
     * - 目标端（-> 右侧）必须是 in port 或 in out port
     * - 严禁 in → in、out → out、in → out（数据无法流出或流入）
     *
     * @param connections   连接引用列表
     * @param featureDetails 组件类型 → (feature名 → FeatureDetail)
     * @param subcomponentRefs subcomponents 引用列表
     * @param result        验证结果
     */
    private void checkPortDirection(List<ConnectionRef> connections,
                                     Map<String, Map<String, FeatureDetail>> featureDetails,
                                     List<SubcomponentRef> subcomponentRefs,
                                     ValidationResult result) {
        // 建立 implementation → (实例名 → 类型名) 映射
        Map<String, Map<String, String>> implInstanceMap = new HashMap<>();
        for (SubcomponentRef ref : subcomponentRefs) {
            if (ref.parentImpl != null) {
                implInstanceMap.computeIfAbsent(ref.parentImpl, k -> new HashMap<>())
                        .put(ref.instanceName, ref.typeName);
            }
        }

        for (ConnectionRef conn : connections) {
            // 只检查 port 连接的方向
            if (!"port".equals(conn.connType) || conn.parentImpl == null) {
                continue;
            }

            Map<String, String> instanceMap = implInstanceMap.get(conn.parentImpl);
            if (instanceMap == null) {
                continue;
            }

            String parentTypeName = conn.parentImpl;

            // 获取源端方向和目标端方向
            String sourceDir = null;
            String destDir = null;
            String sourceDesc = null;
            String destDesc = null;

            if (conn.sourceInstance != null) {
                // 源端是子组件实例
                String sourceType = instanceMap.get(conn.sourceInstance);
                if (sourceType != null) {
                    Map<String, FeatureDetail> sourceFeatures = featureDetails.get(sourceType);
                    if (sourceFeatures != null) {
                        FeatureDetail fd = sourceFeatures.get(conn.sourceFeature);
                        if (fd != null) {
                            sourceDir = fd.direction;
                        }
                    }
                }
                sourceDesc = conn.sourceInstance + "." + conn.sourceFeature;
            } else {
                // 源端是父组件端口（代理连接）
                Map<String, FeatureDetail> parentFeatures = featureDetails.get(parentTypeName);
                if (parentFeatures != null) {
                    FeatureDetail fd = parentFeatures.get(conn.sourceFeature);
                    if (fd != null) {
                        sourceDir = fd.direction;
                    }
                }
                sourceDesc = "父." + conn.sourceFeature;
            }

            if (conn.destInstance != null) {
                // 目标端是子组件实例
                String destType = instanceMap.get(conn.destInstance);
                if (destType != null) {
                    Map<String, FeatureDetail> destFeatures = featureDetails.get(destType);
                    if (destFeatures != null) {
                        FeatureDetail fd = destFeatures.get(conn.destFeature);
                        if (fd != null) {
                            destDir = fd.direction;
                        }
                    }
                }
                destDesc = conn.destInstance + "." + conn.destFeature;
            } else {
                // 目标端是父组件端口（代理连接）
                Map<String, FeatureDetail> parentFeatures = featureDetails.get(parentTypeName);
                if (parentFeatures != null) {
                    FeatureDetail fd = parentFeatures.get(conn.destFeature);
                    if (fd != null) {
                        destDir = fd.direction;
                    }
                }
                destDesc = "父." + conn.destFeature;
            }

            // 无法确定方向时跳过
            if (sourceDir == null || destDir == null) {
                continue;
            }

            boolean directionOk;
            String expectedRule;

            if (conn.isDelegation) {
                // 代理连接（port delegation）：两端方向必须相同
                // 输入代理：父 in -> 子 in
                // 输出代理：子 out -> 父 out
                // 双向代理：父 in out <-> 子 in out
                // "requires"/"provides" 不适用 port 方向，视为未知跳过
                boolean sourceIsPort = "in".equals(sourceDir) || "out".equals(sourceDir) || "in out".equals(sourceDir);
                boolean destIsPort = "in".equals(destDir) || "out".equals(destDir) || "in out".equals(destDir);
                if (!sourceIsPort || !destIsPort) {
                    continue;
                }
                directionOk = sourceDir.equals(destDir);
                if ("source".equals(conn.parentSide)) {
                    expectedRule = "输入代理连接：父端 in -> 子端 in（方向必须相同）";
                } else if ("dest".equals(conn.parentSide)) {
                    expectedRule = "输出代理连接：子端 out -> 父端 out（方向必须相同）";
                } else {
                    expectedRule = "代理连接：两端方向必须相同（in->in 或 out->out）";
                }
            } else {
                // 同级连接（assembly connection）：源端 out -> 目标端 in
                // 特殊情况：双向端口 in out 可以连接到 in 或 out
                boolean sourceIsOut = "out".equals(sourceDir) || "in out".equals(sourceDir);
                boolean destIsIn = "in".equals(destDir) || "in out".equals(destDir);
                directionOk = sourceIsOut && destIsIn;
                expectedRule = "同级连接：源端必须是 out port，目标端必须是 in port";
            }

            if (!directionOk) {
                String connTypeDesc = conn.isDelegation ? "代理连接" : "同级连接";
                result.errors.add(String.format(
                        "第%d行: 端口方向错误 - %s '%s' 的数据流方向不匹配; " +
                        "源端 %s 方向=%s, 目标端 %s 方向=%s; %s",
                        conn.lineNumber, connTypeDesc, conn.connName,
                        sourceDesc, sourceDir, destDesc, destDir, expectedRule
                ));
            }
        }
    }

    /**
     * 4v. 检测 port 连接两端的数据类型是否一致。
     *
     * SAE AADL 标准规范：
     * - port 连接的两端必须引用相同的数据类型
     * - 例如 out data port CommandData → in data port CommandData 是正确的
     * - out data port CommandData → in data port SensorData 是类型不匹配
     *
     * @param connections   连接引用列表
     * @param featureDetails 组件类型 → (feature名 → FeatureDetail)
     * @param subcomponentRefs subcomponents 引用列表
     * @param result        验证结果
     */
    private void checkPortDataTypeConsistency(List<ConnectionRef> connections,
                                               Map<String, Map<String, FeatureDetail>> featureDetails,
                                               List<SubcomponentRef> subcomponentRefs,
                                               ValidationResult result) {
        // 建立 implementation → (实例名 → 类型名) 映射
        Map<String, Map<String, String>> implInstanceMap = new HashMap<>();
        for (SubcomponentRef ref : subcomponentRefs) {
            if (ref.parentImpl != null) {
                implInstanceMap.computeIfAbsent(ref.parentImpl, k -> new HashMap<>())
                        .put(ref.instanceName, ref.typeName);
            }
        }

        for (ConnectionRef conn : connections) {
            // 只检查 port 连接的数据类型
            if (!"port".equals(conn.connType) || conn.parentImpl == null) {
                continue;
            }

            Map<String, String> instanceMap = implInstanceMap.get(conn.parentImpl);
            if (instanceMap == null) {
                continue;
            }

            String sourceType = instanceMap.get(conn.sourceInstance);
            String destType = instanceMap.get(conn.destInstance);

            FeatureDetail sourceFd = null;
            FeatureDetail destFd = null;

            if (sourceType != null) {
                Map<String, FeatureDetail> sourceFeatures = featureDetails.get(sourceType);
                if (sourceFeatures != null) {
                    sourceFd = sourceFeatures.get(conn.sourceFeature);
                }
            }
            if (destType != null) {
                Map<String, FeatureDetail> destFeatures = featureDetails.get(destType);
                if (destFeatures != null) {
                    destFd = destFeatures.get(conn.destFeature);
                }
            }

            // 无法确定数据类型时跳过
            if (sourceFd == null || destFd == null) {
                continue;
            }

            // event port 没有数据类型，跳过
            if (sourceFd.dataType == null || destFd.dataType == null) {
                continue;
            }

            // 检查数据类型是否一致
            if (!sourceFd.dataType.equals(destFd.dataType)) {
                result.errors.add(String.format(
                        "第%d行: 数据类型不匹配 - 连接 '%s' 两端的数据类型不一致; " +
                        "源端 %s.%s 类型=%s, 目标端 %s.%s 类型=%s; " +
                        "port 连接的两端必须引用相同的数据类型",
                        conn.lineNumber, conn.connName,
                        conn.sourceInstance, conn.sourceFeature, sourceFd.dataType,
                        conn.destInstance, conn.destFeature, destFd.dataType
                ));
            }
        }
    }

    /**
     * 4w. 检测连接与实体类型的匹配：软件实体只能有 port 连接，硬件实体只能有 bus access 连接。
     *
     * 核心准则：
     * - 软件实体（process）的 connections 块中只能包含 port 连接（数据流/控制流）
     * - 硬件实体（device、processor、memory、bus）的 connections 块中只能包含 bus access 连接
     * - system 实体作为软硬件桥接容器，port 连接和 bus access 连接均可
     * - thread 实体不允许有 connections（由 4i 检查）
     *
     * @param connections   连接引用列表
     * @param declarations  AADL 声明映射（组件名 → 声明信息，含 type 字段）
     * @param result        验证结果
     */
    private void checkConnectionEntityTypeMatch(List<ConnectionRef> connections,
                                                 Map<String, AadlDeclaration> declarations,
                                                 ValidationResult result) {
        for (ConnectionRef conn : connections) {
            if (conn.parentImpl == null) {
                continue;
            }

            AadlDeclaration implDecl = declarations.get(conn.parentImpl);
            if (implDecl == null || implDecl.type == null) {
                continue;
            }

            String implType = implDecl.type;

            // system 和 device 作为软硬件桥接容器，port 和 bus access 均可
            // - system：顶层系统，连接软件和硬件
            // - device：执行平台组件但可定义 port 与软件端口相连，是软硬件交互的桥梁
            if ("system".equals(implType) || "device".equals(implType)) {
                continue;
            }

            // 软件实体（process、thread）只能有 port 连接
            // 注：thread impl 中严禁 connections 块，由 checkThreadConnectionsBlock 单独检测
            if ("process".equals(implType)) {
                if ("bus access".equals(conn.connType)) {
                    result.errors.add(String.format(
                            "第%d行: 连接类型与实体类型不匹配 - 软件实体 '%s' (process) 的 connections 中出现了 bus access 连接 '%s'; " +
                            "软件实体只能定义 port 连接（数据流/控制流），bus access 连接应出现在硬件实体或 system/device 实现中",
                            conn.lineNumber, conn.parentImpl, conn.connName
                    ));
                }
            }

            // 纯硬件实体（processor、memory、bus）只能有 bus access 连接
            // device 除外：device 是软硬件桥梁，允许同时拥有 port 和 bus access 连接
            if ("processor".equals(implType) || "memory".equals(implType) || "bus".equals(implType)
                    || "virtual processor".equals(implType)) {
                if ("port".equals(conn.connType)) {
                    result.errors.add(String.format(
                            "第%d行: 连接类型与实体类型不匹配 - 纯硬件实体 '%s' (%s) 的 connections 中出现了 port 连接 '%s'; " +
                            "除 device 外的硬件实体只能定义 bus access 连接，port 连接应出现在软件实体、device 或 system 实现中",
                            conn.lineNumber, conn.parentImpl, implType, conn.connName
                    ));
                }
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
            String trimmed = line.trim();
            // 跳过纯注释行
            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }
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
     * 1. 连接行中一端只有实例名没有端口名（如 Instance 而非 Instance.feature）→ 动态推断端口名
     *    扫描通信两端组件的 features 列表，提取真实存在的 in/out data port 名称进行补全
     * 2. 连接行中 Instance. 后面缺少端口名 → 动态推断端口名
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

        // 预解析：组件类型 → (feature名 → FeatureDetail)，用于动态推断端口名
        Map<String, Map<String, FeatureDetail>> featureDetails = parseFeatureDetails(content);
        // 预解析：implementation名 → (实例名 → 类型名)，用于查找连接行中实例对应的组件类型
        Map<String, Map<String, String>> implInstances = parseSubcomponentInstances(content);

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
        String currentImpl = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            Matcher implMatcher = implContextPattern.matcher(trimmed);
            Matcher virtualMatcher = virtualImplPattern.matcher(trimmed);
            if (implMatcher.find()) {
                inImplementation = true;
                inConnections = false;
                currentImpl = implMatcher.group(1);
                resultLines.add(line);
                continue;
            }
            if (virtualMatcher.find()) {
                inImplementation = true;
                inConnections = false;
                currentImpl = virtualMatcher.group(1);
                resultLines.add(line);
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                inImplementation = false;
                inConnections = false;
                currentImpl = null;
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
                    String fixedRest = fixConnectionRest(rest, connName, result, inlineComments,
                            featureDetails, implInstances, currentImpl);
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
     * 端口名通过动态扫描通信两端组件的 features 列表推断，回退到 dataOut/dataIn。
     */
    private String fixConnectionRest(String rest, String connName, ValidationResult result,
                                      List<String> inlineComments,
                                      Map<String, Map<String, FeatureDetail>> featureDetails,
                                      Map<String, Map<String, String>> implInstances,
                                      String currentImpl) {
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

        // 获取当前 implementation 的实例名→类型名映射
        Map<String, String> instanceMap = currentImpl != null ? implInstances.get(currentImpl) : null;
        // 当前组件类型的 features（用于判断单个单词是自身 port 还是子组件实例）
        Map<String, FeatureDetail> selfFeatures = currentImpl != null ? featureDetails.get(currentImpl) : null;

        // 修复源端：可能是 Instance.featureName，也可能是当前组件自身的 portName
        if (sourcePart.matches("\\w+\\.")) {
            // Instance. 后面没有端口名 → 动态推断 out 端口
            String instanceName = sourcePart.substring(0, sourcePart.length() - 1);
            String componentType = instanceMap != null ? instanceMap.get(instanceName) : null;
            String portName = inferPortName(featureDetails, componentType, "out");
            if (portName == null) portName = "dataOut"; // 回退
            sourcePart = sourcePart + portName;
            String msg = String.format("已补全连接 '%s' 源端缺失的端口名: .%s", connName, portName);
            result.fixes.add(msg);
            inlineComments.add("补全源端端口名 ." + portName);
        } else if (sourcePart.matches("\\w+")) {
            // 只有一个单词：先判断是子组件实例还是当前组件自身的 port
            boolean isSelfPort = false;
            if (selfFeatures != null && selfFeatures.containsKey(sourcePart)) {
                FeatureDetail fd = selfFeatures.get(sourcePart);
                // 源端方向必须是 out（或 in out 双向）
                if ("out".equals(fd.direction) || "in out".equals(fd.direction)) {
                    isSelfPort = true; // 自身 port，直接用，不加后缀
                }
            }
            if (!isSelfPort && instanceMap != null && instanceMap.containsKey(sourcePart)) {
                // 是子组件实例，需要补全端口名
                String componentType = instanceMap.get(sourcePart);
                String portName = inferPortName(featureDetails, componentType, "out");
                if (portName == null) portName = "dataOut"; // 回退
                sourcePart = sourcePart + "." + portName;
                String msg = String.format("已补全连接 '%s' 源端缺失的端口名: .%s", connName, portName);
                result.fixes.add(msg);
                inlineComments.add("补全源端端口名 ." + portName);
            }
            // 如果既不是自身 port 也不是已知实例，保持原样（可能是解析问题，不强行补）
        }

        // 修复目标端：可能是 Instance.featureName，也可能是当前组件自身的 portName
        if (destPart.matches("\\w+\\.")) {
            // Instance. 后面没有端口名 → 动态推断 in 端口
            String instanceName = destPart.substring(0, destPart.length() - 1);
            String componentType = instanceMap != null ? instanceMap.get(instanceName) : null;
            String portName = inferPortName(featureDetails, componentType, "in");
            if (portName == null) portName = "dataIn"; // 回退
            destPart = destPart + portName;
            String msg = String.format("已补全连接 '%s' 目标端缺失的端口名: .%s", connName, portName);
            result.fixes.add(msg);
            inlineComments.add("补全目标端端口名 ." + portName);
        } else if (destPart.matches("\\w+")) {
            // 只有一个单词：先判断是子组件实例还是当前组件自身的 port
            boolean isSelfPort = false;
            if (selfFeatures != null && selfFeatures.containsKey(destPart)) {
                FeatureDetail fd = selfFeatures.get(destPart);
                // 目标端方向必须是 in（或 in out 双向）
                if ("in".equals(fd.direction) || "in out".equals(fd.direction)) {
                    isSelfPort = true; // 自身 port，直接用，不加后缀
                }
            }
            if (!isSelfPort && instanceMap != null && instanceMap.containsKey(destPart)) {
                // 是子组件实例，需要补全端口名
                String componentType = instanceMap.get(destPart);
                String portName = inferPortName(featureDetails, componentType, "in");
                if (portName == null) portName = "dataIn"; // 回退
                destPart = destPart + "." + portName;
                String msg = String.format("已补全连接 '%s' 目标端缺失的端口名: .%s", connName, portName);
                result.fixes.add(msg);
                inlineComments.add("补全目标端端口名 ." + portName);
            }
            // 如果既不是自身 port 也不是已知实例，保持原样（可能是解析问题，不强行补）
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
     * 自动修正 0i：处理 thread 类型声明 features 中的 requires/provides bus access。
     *
     * 分层架构规范：线程不应直接访问物理总线。
     * 语义转移策略：
     * 1. 在包含该 thread 的 process/system 级别创建桥接 device
     * 2. 将 bus access 转移给该 device
     * 3. 在 device 上创建与 thread 数据端口对应的反向端口
     * 4. 建立 thread 到 device 的 port 连接
     * 5. 删除 thread 中的 bus access feature
     */
    private String fixThreadBusAccessFeature(String content, ValidationResult result) {
        String[] lines = content.split("\n");

        Pattern threadTypePattern = Pattern.compile(
                "^\\s*thread\\s+(\\w+)\\s*$"
        );
        Pattern busAccessFeaturePattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(requires|provides)\\s+bus\\s+access\\s+", Pattern.CASE_INSENSITIVE
        );
        Pattern dataPortPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(in|out)\\s+(?:data\\s+port|event\\s+data\\s+port|event\\s+port|port)\\s+(\\w+)", Pattern.CASE_INSENSITIVE
        );

        // ===== 第一阶段：收集 thread 的 bus access info + data ports =====
        // busAccessInfo: threadName → list of [featureName, accessType, busTypeName]
        Map<String, List<String[]>> busAccessInfo = new LinkedHashMap<>();
        // threadDataPorts: threadName → list of [portName, direction, dataType]
        Map<String, List<String[]>> threadDataPorts = new LinkedHashMap<>();

        boolean inThreadType = false;
        boolean inFeaturesBlock = false;
        String currentThreadName = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("--")) continue;

            Matcher threadMatcher = threadTypePattern.matcher(line);
            if (threadMatcher.find()) {
                inThreadType = true;
                currentThreadName = threadMatcher.group(1);
                inFeaturesBlock = false;
                continue;
            }

            if (inThreadType && currentThreadName != null &&
                    line.matches("end\\s+" + Pattern.quote(currentThreadName) + "\\s*;")) {
                inThreadType = false;
                currentThreadName = null;
                inFeaturesBlock = false;
                continue;
            }

            if (inThreadType && line.matches("thread\\s+implementation\\s+\\w+\\.impl")) {
                inThreadType = false;
                currentThreadName = null;
                inFeaturesBlock = false;
                continue;
            }

            if (inThreadType && line.equals("features")) {
                inFeaturesBlock = true;
                continue;
            }

            if (inFeaturesBlock && (line.equals("properties") || line.equals("flows") ||
                    line.equals("connections") || line.equals("subcomponents") ||
                    line.startsWith("annex") || line.startsWith("end "))) {
                inFeaturesBlock = false;
                continue;
            }

            if (inFeaturesBlock && currentThreadName != null) {
                // 检查 bus access feature
                Matcher busMatcher = busAccessFeaturePattern.matcher(line);
                if (busMatcher.find()) {
                    String featureName = busMatcher.group(1);
                    String accessType = busMatcher.group(2);
                    // 提取 bus 类型名
                    String busTypeName = extractAccessTypeName(line, "bus");
                    busAccessInfo.computeIfAbsent(currentThreadName, k -> new ArrayList<>())
                            .add(new String[]{featureName, accessType, busTypeName});
                    continue;
                }

                // 检查 data port
                Matcher portMatcher = dataPortPattern.matcher(line);
                if (portMatcher.find()) {
                    String portName = portMatcher.group(1);
                    String direction = portMatcher.group(2).toLowerCase();
                    String dataType = portMatcher.group(3);
                    threadDataPorts.computeIfAbsent(currentThreadName, k -> new ArrayList<>())
                            .add(new String[]{portName, direction, dataType});
                }
            }
        }

        // 收集所有需要删除的 feature 名
        Set<String> removedFeatureNames = new LinkedHashSet<>();
        for (List<String[]> infoList : busAccessInfo.values()) {
            for (String[] info : infoList) {
                removedFeatureNames.add(info[0]);
            }
        }

        if (removedFeatureNames.isEmpty()) {
            return content;
        }

        // ===== 第一阶段补充：查找包含每个 thread 的容器 + 最上层 system =====
        // threadToContainer: threadName → [directContainerImpl, threadInstanceName, directContainerType]
        // threadToSystem: threadName → [systemImplName, processInstanceName, threadInstanceName]
        Map<String, String[]> threadToContainer = new LinkedHashMap<>();
        Map<String, String[]> threadToSystem = new LinkedHashMap<>();
        Pattern implContextPattern = Pattern.compile(
                "^\\s*(system|process)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern threadSubcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*thread\\s+(\\w+)\\.impl\\s*;"
        );
        Pattern processSubcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*process\\s+(\\w+)\\.impl\\s*;"
        );

        // 两阶段建立 impl 父子关系（因为 impl 声明顺序不一定是嵌套顺序）
        // 第一阶段：收集所有 system/process impl
        // implTypes: implName → implType ("system" 或 "process")
        Map<String, String> implTypes = new LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;
            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                implTypes.put(implMatcher.group(2), implMatcher.group(1));
            }
        }

        // 第二阶段：在每个 impl 的 subcomponents 中查找子组件引用，建立父子关系
        // implParent: childImplName → [parentImplName, parentType, instanceNameInParent]
        Map<String, String[]> implParent = new LinkedHashMap<>();
        String currentScanningImpl = null;
        boolean inScanningSubcomps = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;

            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentScanningImpl = implMatcher.group(2);
                inScanningSubcomps = false;
                continue;
            }
            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentScanningImpl = null;
                inScanningSubcomps = false;
                continue;
            }
            if (currentScanningImpl != null && trimmed.equals("subcomponents")) {
                inScanningSubcomps = true;
                continue;
            }
            if (inScanningSubcomps && (trimmed.equals("connections") || trimmed.equals("properties") ||
                    trimmed.equals("features") || trimmed.equals("flows") || trimmed.startsWith("end "))) {
                inScanningSubcomps = false;
                continue;
            }

            if (inScanningSubcomps && currentScanningImpl != null) {
                // 查找 process 子组件
                Matcher procMatcher = processSubcompPattern.matcher(trimmed);
                if (procMatcher.find()) {
                    String instanceName = procMatcher.group(1);
                    String procTypeName = procMatcher.group(2);
                    if (implTypes.containsKey(procTypeName)) {
                        String parentType = implTypes.get(currentScanningImpl);
                        implParent.put(procTypeName, new String[]{currentScanningImpl, parentType, instanceName});
                    }
                }
                // 查找 thread 子组件（记录 thread 的直接容器）
                Matcher threadMatcher = threadSubcompPattern.matcher(trimmed);
                if (threadMatcher.find()) {
                    String instanceName = threadMatcher.group(1);
                    String typeName = threadMatcher.group(2);
                    if (busAccessInfo.containsKey(typeName) && !threadToContainer.containsKey(typeName)) {
                        String containerType = implTypes.get(currentScanningImpl);
                        threadToContainer.put(typeName, new String[]{currentScanningImpl, instanceName, containerType});
                    }
                }
            }
        }

        // 为每个有 bus access 的 thread 找到最上层的 system
        for (Map.Entry<String, List<String[]>> entry : busAccessInfo.entrySet()) {
            String threadName = entry.getKey();
            String[] container = threadToContainer.get(threadName);
            if (container == null) continue;

            String containerImpl = container[0];   // 直接容器名
            String containerType = container[2];   // "system" 或 "process"

            if ("system".equals(containerType)) {
                // 直接在 system 里
                threadToSystem.put(threadName, new String[]{containerImpl, null, container[1]});
            } else {
                // 在 process 里，向上找 system
                String systemImpl = null;
                String sysProcInstance = container[1]; // process 在 system 中的实例名

                // 沿着 implParent 向上找 system
                String current = containerImpl;
                int safety = 0;
                while (current != null && safety < 20) {
                    String[] parentInfo = implParent.get(current);
                    if (parentInfo == null) break;
                    String parentName = parentInfo[0];
                    String parentType = parentInfo[1];
                    if (parentInfo[2] != null) {
                        sysProcInstance = parentInfo[2];
                    }
                    if ("system".equals(parentType)) {
                        systemImpl = parentName;
                        break;
                    }
                    current = parentName;
                    safety++;
                }

                if (systemImpl != null) {
                    threadToSystem.put(threadName, new String[]{systemImpl, sysProcInstance, container[1]});
                }
            }
        }

        // ===== 第二阶段：删除 bus access feature 行 + 引用这些 feature 的 connection 行 =====
        List<String> resultLines = new ArrayList<>();
        inThreadType = false;
        inFeaturesBlock = false;
        currentThreadName = null;
        int removedFeatureCount = 0;
        int removedConnCount = 0;

        boolean inConnectionsBlock = false;
        boolean inAnyImpl = false;

        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            Matcher implMatcher = implDeclPattern.matcher(trimmed);
            if (implMatcher.find()) {
                inAnyImpl = true;
                inConnectionsBlock = false;
                resultLines.add(line);
                continue;
            }

            if (inAnyImpl && trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                inAnyImpl = false;
                inConnectionsBlock = false;
                resultLines.add(line);
                continue;
            }

            if (inAnyImpl && trimmed.equals("connections")) {
                inConnectionsBlock = true;
                resultLines.add(line);
                continue;
            }

            if (inConnectionsBlock && (trimmed.equals("subcomponents") || trimmed.equals("properties") ||
                    trimmed.equals("flows") || trimmed.startsWith("end "))) {
                inConnectionsBlock = false;
                resultLines.add(line);
                continue;
            }

            // 删除 connections 中引用了被删除 feature 的行
            if (inConnectionsBlock && !trimmed.isEmpty()) {
                boolean referencesRemovedFeature = false;
                for (String featName : removedFeatureNames) {
                    // 使用单词边界精确匹配 "实例名.端口名"，避免子串误匹配（如 data 误匹配 dataOut）
                    if (trimmed.matches(".*\\b\\w+\\." + Pattern.quote(featName) + "\\b.*")) {
                        referencesRemovedFeature = true;
                        break;
                    }
                }
                if (referencesRemovedFeature) {
                    removedConnCount++;
                    result.fixes.add(String.format(
                            "已删除引用被移除 bus access feature 的连接行: %s", trimmed));
                    continue;
                }
            }

            // thread 类型声明上下文跟踪
            Matcher threadMatcher = threadTypePattern.matcher(trimmed);
            if (threadMatcher.find()) {
                inThreadType = true;
                currentThreadName = threadMatcher.group(1);
                inFeaturesBlock = false;
                resultLines.add(line);
                continue;
            }

            if (inThreadType && currentThreadName != null &&
                    trimmed.matches("end\\s+" + Pattern.quote(currentThreadName) + "\\s*;")) {
                inThreadType = false;
                currentThreadName = null;
                inFeaturesBlock = false;
                resultLines.add(line);
                continue;
            }

            if (inThreadType && trimmed.matches("thread\\s+implementation\\s+\\w+\\.impl")) {
                inThreadType = false;
                currentThreadName = null;
                inFeaturesBlock = false;
                resultLines.add(line);
                continue;
            }

            if (inThreadType && trimmed.equals("features")) {
                inFeaturesBlock = true;
                resultLines.add(line);
                continue;
            }

            if (inFeaturesBlock && (trimmed.equals("properties") || trimmed.equals("flows") ||
                    trimmed.equals("connections") || trimmed.equals("subcomponents") ||
                    trimmed.startsWith("annex") || trimmed.startsWith("end "))) {
                inFeaturesBlock = false;
                resultLines.add(line);
                continue;
            }

            // 删除 thread features 中的 bus access 行
            if (inFeaturesBlock) {
                Matcher m = busAccessFeaturePattern.matcher(trimmed);
                if (m.find()) {
                    String featureName = m.group(1);
                    String accessType = m.group(2);
                    removedFeatureCount++;
                    result.fixes.add(String.format(
                            "已从 thread '%s' 移除 %s bus access feature: %s（将转移至桥接 device）",
                            currentThreadName, accessType, featureName));
                    continue;
                }
            }

            resultLines.add(line);
        }

        // ===== 第三阶段：创建桥接 device + 添加到 system + 建立逻辑连接 + Actual_Connection_Binding =====
        String intermediate = String.join("\n", resultLines);
        intermediate = addBridgeDevices(intermediate, busAccessInfo, threadDataPorts,
                threadToContainer, threadToSystem, result);

        if (removedFeatureCount > 0) {
            log.info("自动修正：从 thread 类型声明中移除了 {} 行 bus access feature（已转移至桥接 device）", removedFeatureCount);
        }
        if (removedConnCount > 0) {
            log.info("自动修正：删除了 {} 行引用被移除 bus access feature 的连接行", removedConnCount);
        }
        return intermediate;
    }

    /**
     * 为每个有 bus access 的 thread 创建桥接 device，按正确的 AADL 架构放置：
     * 1. 桥接 device 声明放在 package 级别
     * 2. device 实例放在最上层 system 的 subcomponents 中（和 process 平级）
     * 3. 在 system 的 connections 中建立 device 数据端口 ↔ process/thread 外部端口的逻辑 port 连接
     * 4. 添加 Actual_Connection_Binding 属性，将逻辑连接绑定到物理总线
     */
    private String addBridgeDevices(String content,
                                     Map<String, List<String[]>> busAccessInfo,
                                     Map<String, List<String[]>> threadDataPorts,
                                     Map<String, String[]> threadToContainer,
                                     Map<String, String[]> threadToSystem,
                                     ValidationResult result) {
        StringBuilder deviceDecls = new StringBuilder();
        // 按 system 分组：systemImpl → list of [deviceInstance, deviceName, processInstance, threadInstance, threadName]
        Map<String, List<String[]>> systemDevices = new LinkedHashMap<>();
        // 按 system 分组：systemImpl → list of 逻辑连接行 (device ↔ process 外部端口)
        Map<String, List<String>> systemConnections = new LinkedHashMap<>();
        // 按 system 分组：systemImpl → list of Actual_Connection_Binding 属性行
        Map<String, List<String>> systemBindings = new LinkedHashMap<>();
        // 收集每个 thread 对应的 bus 类型名（用于绑定属性）
        // threadName → busTypeName
        Map<String, String> threadBusType = new LinkedHashMap<>();

        // === process 需要补充的外部端口和内部连接 ===
        // procExternalPorts: processTypeName → list of [portName, direction, dataType]
        Map<String, List<String[]>> procExternalPorts = new LinkedHashMap<>();
        // procInternalConns: processImplName → list of 内部连接行 (thread.port ↔ process外部端口)
        Map<String, List<String>> procInternalConns = new LinkedHashMap<>();

        for (Map.Entry<String, List<String[]>> entry : busAccessInfo.entrySet()) {
            String threadName = entry.getKey();
            List<String[]> busInfoList = entry.getValue();
            String[] sysInfo = threadToSystem.get(threadName);

            if (sysInfo == null) {
                // 未找到上层 system，只创建 device 声明但不添加 subcomponent
                result.warnings.add(String.format(
                        "thread '%s' 有 bus access 但未找到上层 system，已创建桥接 device 声明但未自动添加到系统架构中",
                        threadName));
            }

            String deviceName = threadName + "_Bridge";

            // 收集 bus 类型名（取第一个 bus access 的类型）
            String busTypeName = null;
            if (busInfoList != null && !busInfoList.isEmpty()) {
                busTypeName = busInfoList.get(0)[2];
                if (busTypeName == null) busTypeName = "Base_Bus";
            }
            threadBusType.put(threadName, busTypeName);

            // 创建 device 类型声明
            deviceDecls.append("\n    -- [自动修正] 桥接 device：承接 thread '").append(threadName).append("' 的 bus access\n");
            deviceDecls.append("  device ").append(deviceName).append("\n");
            deviceDecls.append("    features\n");
            // 添加 bus access
            for (String[] busInfo : busInfoList) {
                String featName = busInfo[0];
                String accessType = busInfo[1];
                String busType = busInfo[2];
                deviceDecls.append("      ").append(featName).append(" : ").append(accessType)
                        .append(" bus access ").append(busType != null ? busType : "Base_Bus").append(";\n");
            }
            // 添加与 thread 数据端口对应的反向端口（用于逻辑连接）
            List<String[]> ports = threadDataPorts.get(threadName);
            if (ports != null) {
                for (String[] port : ports) {
                    String portName = port[0];
                    String direction = port[1];
                    String dataType = port[2];
                    // 反向方向：thread in → device out, thread out → device in
                    String reversedDir = "in".equals(direction) ? "out" : "in";
                    deviceDecls.append("      ").append(portName).append(" : ").append(reversedDir)
                            .append(" data port ").append(dataType).append(";\n");
                }
            }
            deviceDecls.append("  end ").append(deviceName).append(";\n\n");
            deviceDecls.append("  device implementation ").append(deviceName).append(".impl\n");
            deviceDecls.append("  end ").append(deviceName).append(".impl;\n");

            // 记录到 system 容器
            if (sysInfo != null) {
                String systemImpl = sysInfo[0];
                String procInstance = sysInfo[1];  // process 在 system 中的实例名（可能为 null，表示 thread 直接在 system 里）
                String threadInstance = sysInfo[2]; // thread 在 process 中的实例名
                String deviceInstance = threadInstance + "_Bridge";

                systemDevices.computeIfAbsent(systemImpl, k -> new ArrayList<>())
                        .add(new String[]{deviceInstance, deviceName, procInstance, threadInstance, threadName});

                // 创建逻辑 port 连接：device 端口 ↔ process 外部端口
                // 如果 thread 在 process 里，需要通过 process 外部端口中转
                // 这里简化：device 端口 → process 实例的外部端口（port 名和 thread 的 port 名相同）
                List<String> conns = systemConnections.computeIfAbsent(systemImpl, k -> new ArrayList<>());
                List<String> bindings = systemBindings.computeIfAbsent(systemImpl, k -> new ArrayList<>());

                if (ports != null) {
                    for (String[] port : ports) {
                        String portName = port[0];
                        String direction = port[1];
                        String dataType = port[2];
                        String connName = "conn_" + threadInstance + "_" + portName;

                        if (procInstance != null) {
                            // thread 在 process 里：device ↔ process 外部端口
                            // 需要给 process 类型补充外部端口，给 process impl 补充内部连接
                            String[] containerInfo = threadToContainer.get(threadName);
                            String procTypeName = containerInfo != null ? containerInfo[0] : null;
                            if (procTypeName == null) procTypeName = "UnknownProcess";

                            // 1. 记录 process 需要补充的外部端口（和 thread 端口同名同方向）
                            List<String[]> extPorts = procExternalPorts.computeIfAbsent(procTypeName, k -> new ArrayList<>());
                            extPorts.add(new String[]{portName, direction, dataType});

                            // 2. 记录 process 内部连接：thread 端口 ↔ process 外部端口
                            List<String> innerConns = procInternalConns.computeIfAbsent(procTypeName, k -> new ArrayList<>());
                            String innerConnName = "inner_" + threadInstance + "_" + portName;
                            if ("in".equals(direction)) {
                                // process外部in → thread in
                                innerConns.add(String.format("      %s : port %s -> %s.%s;",
                                        innerConnName, portName, threadInstance, portName));
                            } else {
                                // thread out → process外部out
                                innerConns.add(String.format("      %s : port %s.%s -> %s;",
                                        innerConnName, threadInstance, portName, portName));
                            }

                            // 3. system 级逻辑连接：device 端口 ↔ process 外部端口
                            if ("in".equals(direction)) {
                                // thread in ← device out → process in
                                conns.add(String.format("      %s : port %s.%s -> %s.%s;",
                                        connName, deviceInstance, portName, procInstance, portName));
                            } else {
                                // thread out → device in ← process out
                                conns.add(String.format("      %s : port %s.%s -> %s.%s;",
                                        connName, procInstance, portName, deviceInstance, portName));
                            }
                        } else {
                            // thread 直接在 system 里：device ↔ thread
                            if ("in".equals(direction)) {
                                conns.add(String.format("      %s : port %s.%s -> %s.%s;",
                                        connName, deviceInstance, portName, threadInstance, portName));
                            } else {
                                conns.add(String.format("      %s : port %s.%s -> %s.%s;",
                                        connName, threadInstance, portName, deviceInstance, portName));
                            }
                        }

                        // Actual_Connection_Binding：把逻辑连接绑定到 bus
                        if (busTypeName != null) {
                            bindings.add(String.format("    Actual_Connection_Binding => (reference (%s)) applies to %s;",
                                    busTypeName, connName));
                        }
                    }
                }

                result.fixes.add(String.format(
                        "已为 thread '%s' 创建桥接 device '%s'，添加到 system '%s' 中，建立逻辑连接并绑定到总线",
                        threadName, deviceName, systemImpl));
            }
        }

        // 将 device 声明插入到 package public 块的末尾（end 包名 之前）
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();

        // 用栈跟踪嵌套层级，只有栈顶是 package 时的 end 才是 package end
        Pattern componentStartPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|" +
                "virtual\\s+processor|virtual\\s+bus|thread\\s+group|abstract)\\s+" +
                "([A-Za-z_]\\w*)\\b", Pattern.CASE_INSENSITIVE);
        Pattern implStartPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|" +
                "virtual\\s+processor|virtual\\s+bus|thread\\s+group|abstract)\\s+" +
                "implementation\\s+([A-Za-z_]\\w*)\\.impl\\b", Pattern.CASE_INSENSITIVE);
        Pattern endPattern = Pattern.compile("^\\s*end\\s+([A-Za-z_]\\w*(?:\\.impl)?)\\s*;\\s*$", Pattern.CASE_INSENSITIVE);
        Pattern packageStartPattern = Pattern.compile("^\\s*package\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
        Stack<String> nestingStack = new Stack<>();
        boolean insertedDecls = false;

        // 用于后续 subcomponent 和 connection 插入
        Pattern implContextPattern = Pattern.compile(
                "^\\s*(system|process)\\s+implementation\\s+(\\w+)\\.impl"
        );
        String currentImpl = null;
        String currentImplType = null;  // "system" 或 "process"
        boolean inSubcomponents = false;
        boolean inConnections = false;
        boolean inProperties = false;

        // 用于 process 类型声明的外部端口补全
        Pattern processTypePattern = Pattern.compile("^\\s*process\\s+(\\w+)\\s*$");
        String currentProcessType = null;
        boolean inProcTypeFeatures = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // 跳过注释行（但仍参与 subcomponent/connection 上下文判断）
            boolean isComment = trimmed.startsWith("--");

            // 去掉行尾注释后的内容，用于 end 语句匹配（支持 "end Foo;  -- xxx" 写法）
            String trimmedNoComment = trimmed.replaceAll("--.*$", "").trim();

            // 先判断是否为 package 级别的 end（必须在栈更新之前检查，否则弹出后栈大小会变小）
            boolean isPackageEnd = false;
            if (!insertedDecls && !isComment && nestingStack.size() == 1) {
                Matcher endMatcher = endPattern.matcher(trimmedNoComment);
                if (endMatcher.find()) {
                    isPackageEnd = true;
                }
            }

            // 更新嵌套栈
            if (!isComment) {
                Matcher pkgMatcher = packageStartPattern.matcher(trimmed);
                if (pkgMatcher.find()) {
                    nestingStack.push(pkgMatcher.group(1));
                } else {
                    Matcher implMatcherStack = implStartPattern.matcher(trimmed);
                    if (implMatcherStack.find()) {
                        nestingStack.push(implMatcherStack.group(2) + ".impl");
                    } else {
                        Matcher compMatcher = componentStartPattern.matcher(trimmed);
                        if (compMatcher.find() && !trimmed.toLowerCase().contains("implementation")) {
                            nestingStack.push(compMatcher.group(2));
                        } else {
                            Matcher endMatcher = endPattern.matcher(trimmedNoComment);
                            if (endMatcher.find() && !nestingStack.isEmpty()) {
                                nestingStack.pop();
                            }
                        }
                    }
                }
            }

            // 在 package end 之前插入 device 声明（只有栈大小为 1 时的 end 才是 package end）
            if (isPackageEnd) {
                resultLines.add(deviceDecls.toString());
                insertedDecls = true;
            }

            // === process 类型声明：补充外部端口 ===
            Matcher procTypeMatcher = processTypePattern.matcher(trimmed);
            if (procTypeMatcher.find()) {
                currentProcessType = procTypeMatcher.group(1);
                inProcTypeFeatures = false;
                resultLines.add(line);
                continue;
            }
            if (currentProcessType != null && !trimmed.toLowerCase().contains("implementation") &&
                    trimmed.matches("end\\s+" + Pattern.quote(currentProcessType) + "\\s*;")) {
                // 如果该 process 需要补充端口但没有 features 块
                if (procExternalPorts.containsKey(currentProcessType) && !inProcTypeFeatures) {
                    resultLines.add("  features");
                    for (String[] port : procExternalPorts.get(currentProcessType)) {
                        resultLines.add(String.format("    %s : %s data port %s;  -- [自动修正] 补充桥接用外部端口",
                                port[0], port[1], port[2] != null ? port[2] : "Base_Type"));
                    }
                }
                currentProcessType = null;
                inProcTypeFeatures = false;
                resultLines.add(line);
                continue;
            }
            if (currentProcessType != null && trimmed.equals("features") && procExternalPorts.containsKey(currentProcessType)) {
                inProcTypeFeatures = true;
                resultLines.add(line);
                // 在 features 块末尾补充端口（先记下当前位置，等遇到块结束时再补）
                // 简化：直接在 features 关键字后面追加
                for (String[] port : procExternalPorts.get(currentProcessType)) {
                    resultLines.add(String.format("    %s : %s data port %s;  -- [自动修正] 补充桥接用外部端口",
                            port[0], port[1], port[2] != null ? port[2] : "Base_Type"));
                }
                continue;
            }
            if (inProcTypeFeatures && (trimmed.equals("properties") || trimmed.equals("flows") ||
                    trimmed.equals("connections") || trimmed.equals("subcomponents") ||
                    trimmed.startsWith("annex") || trimmed.startsWith("end "))) {
                inProcTypeFeatures = false;
            }

            // 在 system 的 subcomponents 块中添加 device subcomponent
            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImplType = implMatcher.group(1);
                currentImpl = implMatcher.group(2);
                inSubcomponents = false;
                inConnections = false;
                inProperties = false;
                resultLines.add(line);
                continue;
            }

            if (currentImpl != null && trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                // === system impl 补充 ===
                if ("system".equals(currentImplType)) {
                    // 如果该 system 有 connections 需要添加但没有 connections 块
                    if (systemConnections.containsKey(currentImpl) && !inConnections) {
                        resultLines.add("    connections");
                        for (String conn : systemConnections.get(currentImpl)) {
                            resultLines.add(conn);
                        }
                    }
                    // 如果该 system 有 binding 属性需要添加但没有 properties 块
                    if (systemBindings.containsKey(currentImpl) && !inProperties) {
                        resultLines.add("    properties");
                        for (String binding : systemBindings.get(currentImpl)) {
                            resultLines.add(binding);
                        }
                    }
                }
                // === process impl 补充：内部连接 ===
                if ("process".equals(currentImplType)) {
                    if (procInternalConns.containsKey(currentImpl) && !inConnections) {
                        resultLines.add("    connections");
                        for (String conn : procInternalConns.get(currentImpl)) {
                            resultLines.add(conn);
                        }
                    }
                }
                currentImpl = null;
                currentImplType = null;
                inSubcomponents = false;
                inConnections = false;
                inProperties = false;
                resultLines.add(line);
                continue;
            }

            if (currentImpl != null && trimmed.equals("subcomponents")) {
                inSubcomponents = true;
                inConnections = false;
                inProperties = false;
                resultLines.add(line);
                // 添加 device subcomponent（只在 system impl 中添加）
                if ("system".equals(currentImplType) && systemDevices.containsKey(currentImpl)) {
                    for (String[] dev : systemDevices.get(currentImpl)) {
                        resultLines.add(String.format("      %s : device %s.impl;", dev[0], dev[1]));
                    }
                }
                continue;
            }

            if (currentImpl != null && trimmed.equals("connections")) {
                inConnections = true;
                inSubcomponents = false;
                inProperties = false;
                resultLines.add(line);
                // system impl：添加逻辑 port 连接（device ↔ process）
                if ("system".equals(currentImplType) && systemConnections.containsKey(currentImpl)) {
                    for (String conn : systemConnections.get(currentImpl)) {
                        resultLines.add(conn);
                    }
                }
                // process impl：添加内部连接（thread ↔ 外部端口）
                if ("process".equals(currentImplType) && procInternalConns.containsKey(currentImpl)) {
                    for (String conn : procInternalConns.get(currentImpl)) {
                        resultLines.add(conn);
                    }
                }
                continue;
            }

            if (currentImpl != null && trimmed.equals("properties")) {
                inProperties = true;
                inSubcomponents = false;
                inConnections = false;
                resultLines.add(line);
                // 添加 Actual_Connection_Binding 属性（只在 system impl 中添加）
                if ("system".equals(currentImplType) && systemBindings.containsKey(currentImpl)) {
                    for (String binding : systemBindings.get(currentImpl)) {
                        resultLines.add(binding);
                    }
                }
                continue;
            }

            if (inSubcomponents || inConnections || inProperties) {
                if (trimmed.equals("subcomponents")) { inSubcomponents = true; inConnections = false; inProperties = false; }
                else if (trimmed.equals("connections")) { inConnections = true; inSubcomponents = false; inProperties = false; }
                else if (trimmed.equals("properties")) { inProperties = true; inSubcomponents = false; inConnections = false; }
                else if (trimmed.equals("features") || trimmed.equals("flows") || trimmed.startsWith("end ")) {
                    inSubcomponents = false;
                    inConnections = false;
                    inProperties = false;
                }
            }

            resultLines.add(line);
        }

        // 如果没有找到 package end，追加到末尾
        if (!insertedDecls) {
            resultLines.add(deviceDecls.toString());
        }

        return String.join("\n", resultLines);
    }

    /**
     * 自动修正：删除 data 组件类型声明中的整个 features 块。
     *
     * data 是被动构件（类型分类器），不应拥有 features。检测到 features 块时，
     * 删除从 "features" 关键字到下一个块关键字（properties/flows/connections/subcomponents/annex/end）
     * 之间的所有行。
     * 注意：subprogram 可以定义 features，不属于此约束范围。
     *
     * @param content AADL 代码
     * @param result  验证结果
     * @return 修正后的 AADL 代码
     */
    private String fixDataComponentFeatures(String content, ValidationResult result) {
        String[] lines = content.split("\n");

        // 只匹配 data 类型声明（subprogram 可以有 features）
        Pattern passiveTypePattern = Pattern.compile(
                "^\\s*data\\s+(\\w+)\\s*$"
        );
        Pattern implDeclPattern = Pattern.compile(
                "^\\s*data\\s+implementation\\s+(\\w+)\\.impl"
        );
        // 用于提取 feature 名
        Pattern featureNamePattern = Pattern.compile(
                "^(\\w+)\\s*:", Pattern.CASE_INSENSITIVE
        );

        // ===== 第一阶段：扫描收集需要删除的 feature 名 =====
        Set<String> removedFeatureNames = new LinkedHashSet<>();
        String currentTypeName = null;
        String currentCompType = null;
        boolean inPassiveType = false;
        boolean inFeaturesBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("--")) {
                continue;
            }

            if (implDeclPattern.matcher(line).find()) {
                inPassiveType = false;
                inFeaturesBlock = false;
                currentTypeName = null;
                currentCompType = null;
                continue;
            }

            Matcher passiveMatcher = passiveTypePattern.matcher(line);
            if (passiveMatcher.find()) {
                currentCompType = "data";
                currentTypeName = passiveMatcher.group(1);
                inPassiveType = true;
                inFeaturesBlock = false;
                continue;
            }

            if (inPassiveType && currentTypeName != null &&
                    line.matches("end\\s+" + Pattern.quote(currentTypeName) + "\\s*;")) {
                inPassiveType = false;
                inFeaturesBlock = false;
                currentTypeName = null;
                currentCompType = null;
                continue;
            }

            if (inPassiveType && line.equals("features")) {
                inFeaturesBlock = true;
                continue;
            }

            if (inFeaturesBlock && (line.equals("properties") || line.equals("flows") ||
                    line.equals("connections") || line.equals("subcomponents") ||
                    line.startsWith("annex") || line.startsWith("end "))) {
                inFeaturesBlock = false;
                continue;
            }

            if (inFeaturesBlock) {
                Matcher nameMatcher = featureNamePattern.matcher(line);
                if (nameMatcher.find()) {
                    removedFeatureNames.add(nameMatcher.group(1));
                }
            }
        }

        if (removedFeatureNames.isEmpty()) {
            return content; // 没有需要删除的 feature
        }

        // ===== 第二阶段：实际删除 features 块 + 引用这些 feature 的 connection 行 =====
        List<String> resultLines = new ArrayList<>();
        inPassiveType = false;
        inFeaturesBlock = false;
        currentTypeName = null;
        currentCompType = null;
        int removedFeatureCount = 0;
        int removedConnCount = 0;

        // connections 块跟踪
        boolean inConnectionsBlock = false;
        boolean inAnyImpl = false;

        Pattern generalImplPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            // === implementation 上下文跟踪 ===
            Matcher implMatcher = generalImplPattern.matcher(trimmed);
            if (implMatcher.find()) {
                inAnyImpl = true;
                inConnectionsBlock = false;
                resultLines.add(line);
                continue;
            }

            if (inAnyImpl && trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                inAnyImpl = false;
                inConnectionsBlock = false;
                resultLines.add(line);
                continue;
            }

            // connections 块跟踪
            if (inAnyImpl && trimmed.equals("connections")) {
                inConnectionsBlock = true;
                resultLines.add(line);
                continue;
            }

            if (inConnectionsBlock && (trimmed.equals("subcomponents") || trimmed.equals("properties") ||
                    trimmed.equals("flows") || trimmed.startsWith("end "))) {
                inConnectionsBlock = false;
                resultLines.add(line);
                continue;
            }

            // === 删除 connections 中引用了被删除 feature 的行（级联删除）===
            if (inConnectionsBlock && !trimmed.isEmpty()) {
                boolean referencesRemovedFeature = false;
                for (String featName : removedFeatureNames) {
                    // 检查连接行是否引用了 实例名.featName（使用单词边界避免子串误匹配）
                    if (trimmed.matches(".*\\b\\w+\\." + Pattern.quote(featName) + "\\b.*")) {
                        referencesRemovedFeature = true;
                        break;
                    }
                }
                if (referencesRemovedFeature) {
                    removedConnCount++;
                    result.fixes.add(String.format(
                            "已删除引用被移除的被动构件 feature 的连接行: %s", trimmed));
                    continue; // 跳过该行
                }
            }

            // === 被动构件类型声明上下文跟踪 ===
            if (implDeclPattern.matcher(trimmed).find()) {
                inPassiveType = false;
                inFeaturesBlock = false;
                currentTypeName = null;
                currentCompType = null;
                resultLines.add(line);
                continue;
            }

            Matcher passiveMatcher = passiveTypePattern.matcher(trimmed);
            if (passiveMatcher.find()) {
                currentCompType = "data";
                currentTypeName = passiveMatcher.group(1);
                inPassiveType = true;
                inFeaturesBlock = false;
                resultLines.add(line);
                continue;
            }

            if (inPassiveType && currentTypeName != null &&
                    trimmed.matches("end\\s+" + Pattern.quote(currentTypeName) + "\\s*;")) {
                inPassiveType = false;
                inFeaturesBlock = false;
                currentTypeName = null;
                currentCompType = null;
                resultLines.add(line);
                continue;
            }

            // === 删除 data 组件 features 块 ===
            if (inPassiveType && trimmed.equals("features")) {
                inFeaturesBlock = true;
                removedFeatureCount++;
                result.fixes.add(String.format(
                        "已删除 data '%s' 中非法的 features 块（data 是类型分类器，不应拥有 features）",
                        currentTypeName));
                continue; // 跳过 "features" 行
            }

            if (inFeaturesBlock && (trimmed.equals("properties") || trimmed.equals("flows") ||
                    trimmed.equals("connections") || trimmed.equals("subcomponents") ||
                    trimmed.startsWith("annex") || trimmed.startsWith("end "))) {
                inFeaturesBlock = false;
                resultLines.add(line);
                continue;
            }

            // 在 features 块内 → 删除所有 feature 声明行
            if (inFeaturesBlock) {
                continue; // 跳过该行
            }

            resultLines.add(line);
        }

        if (removedFeatureCount > 0) {
            log.info("自动修正：从 data 组件中删除了 {} 个非法 features 块", removedFeatureCount);
        }
        if (removedConnCount > 0) {
            log.info("自动修正：级联删除了 {} 行引用 data 组件 feature 的连接行", removedConnCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 自动修正：删除软件实体中的 bus access 连接和硬件实体中的 port 连接。
     *
     * 核心准则：
     * - 软件实体（process）的 connections 中只允许 port 连接，bus access 连接需删除
     * - 硬件实体（device、processor、memory、bus）的 connections 中只允许 bus access 连接，port 连接需删除
     * - system 实体作为桥接容器，两种连接均保留
     *
     * @param content      AADL 代码
     * @param declarations AADL 声明映射（组件名 → 声明信息，含 type 字段）
     * @param result       验证结果
     * @return 修正后的 AADL 代码
     */
    private String fixConnectionEntityTypeMismatch(String content,
                                                    Map<String, AadlDeclaration> declarations,
                                                    ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();

        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        // 连接行模式（与 parseConnections 保持一致）
        Pattern connPattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*(port|bus\\s+access)\\s+" +
                "(\\w+)\\.(\\w+)\\s*(->|<->)\\s*(\\w+)\\.(\\w+)"
        );

        String currentImpl = null;
        String currentImplType = null;
        int fixCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过注释行
            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            // 跟踪 implementation 上下文
            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                AadlDeclaration decl = declarations.get(currentImpl);
                currentImplType = (decl != null) ? decl.type : null;
                resultLines.add(line);
                continue;
            }

            // 退出 implementation 上下文
            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                currentImplType = null;
                resultLines.add(line);
                continue;
            }

            // 检查连接行是否需要删除
            if (currentImplType != null && currentImpl != null) {
                Matcher connMatcher = connPattern.matcher(trimmed);
                if (connMatcher.find()) {
                    String connName = connMatcher.group(1);
                    String connType = connMatcher.group(2).replaceAll("\\s+", " ");
                    boolean shouldRemove = false;
                    String reason = null;

                    // 软件实体（process）中的 bus access 连接 → 删除
                    if ("process".equals(currentImplType) && "bus access".equals(connType)) {
                        shouldRemove = true;
                        reason = String.format("软件实体 %s (process) 中不能有 bus access 连接", currentImpl);
                    }

                    // 纯硬件实体中的 port 连接 → 删除（device 是软硬件桥梁，允许 port 连接）
                    if (("processor".equals(currentImplType) || "memory".equals(currentImplType) ||
                            "bus".equals(currentImplType)) &&
                            "port".equals(connType)) {
                        shouldRemove = true;
                        reason = String.format("纯硬件实体 %s (%s) 中不能有 port 连接", currentImpl, currentImplType);
                    }

                    if (shouldRemove) {
                        fixCount++;
                        result.fixes.add(String.format(
                                "已删除非法连接: %s (连接名: %s)", reason, connName
                        ));
                        log.info("自动修正：删除 {} 中的非法连接 '{}'", currentImpl, connName);
                        continue; // 跳过该行（不添加到结果中）
                    }
                }
            }

            resultLines.add(line);
        }

        if (fixCount > 0) {
            log.info("自动修正：共删除 {} 行连接类型与实体类型不匹配的连接行", fixCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 自动修正：删除引用 data 组件端口的 port 连接行。
     *
     * data 组件是纯类型分类器，严禁拥有 features 块。任何 port 连接引用了 data 组件实例的端口都是非法的。
     * 此方法扫描每个 implementation 中的 port 连接，若任一端点引用的实例是 data 组件，则删除该连接行。
     *
     * @param content      AADL 代码
     * @param declarations AADL 声明映射
     * @param result       验证结果
     * @return 修正后的 AADL 代码
     */
    private String fixPortConnectionToDataComponent(String content,
                                                     Map<String, AadlDeclaration> declarations,
                                                     ValidationResult result) {
        String[] lines = content.split("\n");

        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        // subcomponents 行模式：实例名 : 组件关键字 类型名.impl;
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract|virtual\\s+processor)\\s+(\\w+)\\.impl\\s*;"
        );
        // port 连接行模式
        Pattern portConnPattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*port\\s+(\\w+)\\.(\\w+)\\s*->\\s*(\\w+)\\.(\\w+)"
        );

        // ===== 第一遍扫描：收集每个 implementation 中被 port 连接引用的 data 实例名 =====
        // key = implementation 名, value = 该 impl 内被连接引用的 data 实例名集合
        Map<String, Set<String>> dataInstancesToRemove = new HashMap<>();

        String currentImpl = null;
        Map<String, String> instanceTypeMap = new HashMap<>();

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                continue;
            }

            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                instanceTypeMap.clear();
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                instanceTypeMap.clear();
                continue;
            }

            if (currentImpl != null) {
                Matcher subcompMatcher = subcompPattern.matcher(trimmed);
                if (subcompMatcher.find()) {
                    String instanceName = subcompMatcher.group(1);
                    String compKeyword = subcompMatcher.group(2).replaceAll("\\s+", " ");
                    instanceTypeMap.put(instanceName, compKeyword);
                    continue;
                }

                Matcher connMatcher = portConnPattern.matcher(trimmed);
                if (connMatcher.find()) {
                    String srcInstance = connMatcher.group(2);
                    String dstInstance = connMatcher.group(4);
                    String srcKeyword = instanceTypeMap.get(srcInstance);
                    String dstKeyword = instanceTypeMap.get(dstInstance);

                    if ("data".equals(srcKeyword)) {
                        dataInstancesToRemove.computeIfAbsent(currentImpl, k -> new LinkedHashSet<>()).add(srcInstance);
                    }
                    if ("data".equals(dstKeyword)) {
                        dataInstancesToRemove.computeIfAbsent(currentImpl, k -> new LinkedHashSet<>()).add(dstInstance);
                    }
                }
            }
        }

        if (dataInstancesToRemove.isEmpty()) {
            return content; // 没有需要处理的 data 实例
        }

        // ===== 第二遍扫描：删除引用 data 端口的连接行 + 删除 data 实例的 subcomponent 声明行 =====
        List<String> resultLines = new ArrayList<>();
        currentImpl = null;
        instanceTypeMap.clear();
        int removedConnCount = 0;
        int removedSubcompCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过注释行（保留）
            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            // 跟踪 implementation 上下文
            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                instanceTypeMap.clear();
                resultLines.add(line);
                continue;
            }

            // 退出 implementation 上下文
            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                instanceTypeMap.clear();
                resultLines.add(line);
                continue;
            }

            if (currentImpl != null) {
                // 检查是否是 subcomponent 声明行
                Matcher subcompMatcher = subcompPattern.matcher(trimmed);
                if (subcompMatcher.find()) {
                    String instanceName = subcompMatcher.group(1);
                    String compKeyword = subcompMatcher.group(2).replaceAll("\\s+", " ");
                    instanceTypeMap.put(instanceName, compKeyword);

                    // 如果该实例是被连接引用的 data 组件，删除此 subcomponent 声明行
                    Set<String> toRemove = dataInstancesToRemove.get(currentImpl);
                    if (toRemove != null && toRemove.contains(instanceName) && "data".equals(compKeyword)) {
                        removedSubcompCount++;
                        result.fixes.add(String.format(
                                "已删除 data 组件 '%s' 的非法 subcomponent 声明：data 组件是纯类型分类器，不应作为子组件实例化并通过连接传输数据",
                                instanceName
                        ));
                        log.info("自动修正：删除 data 组件 '{}' 的 subcomponent 声明（impl: {}）", instanceName, currentImpl);
                        continue; // 跳过该行
                    }

                    resultLines.add(line);
                    continue;
                }

                // 检查 port 连接行是否引用了 data 组件的端口
                Matcher connMatcher = portConnPattern.matcher(trimmed);
                if (connMatcher.find()) {
                    String connName = connMatcher.group(1);
                    String srcInstance = connMatcher.group(2);
                    String dstInstance = connMatcher.group(4);

                    String srcKeyword = instanceTypeMap.get(srcInstance);
                    String dstKeyword = instanceTypeMap.get(dstInstance);

                    boolean shouldRemove = false;
                    String reason = null;

                    if ("data".equals(srcKeyword)) {
                        shouldRemove = true;
                        reason = String.format("连接 '%s' 的源端 '%s' 是 data 组件，data 组件严禁拥有端口", connName, srcInstance);
                    }
                    if ("data".equals(dstKeyword)) {
                        shouldRemove = true;
                        reason = String.format("连接 '%s' 的目标端 '%s' 是 data 组件，data 组件严禁拥有端口", connName, dstInstance);
                    }

                    if (shouldRemove) {
                        removedConnCount++;
                        result.fixes.add(String.format(
                                "已删除引用 data 组件端口的非法连接: %s", reason
                        ));
                        log.info("自动修正：删除引用 data 组件端口的连接 '{}'", connName);
                        continue; // 跳过该行
                    }
                }
            }

            resultLines.add(line);
        }

        if (removedConnCount > 0 || removedSubcompCount > 0) {
            log.info("自动修正：共删除 {} 行引用 data 组件端口的连接行，{} 行 data subcomponent 声明",
                    removedConnCount, removedSubcompCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 自动修正 0m：处理非法嵌套的 subcomponent。
     *
     * 根据 CONTAINMENT_RULES 检查每个 implementation 中 subcomponent 的父子类型是否合法。
     * - process 在 processor 中 → 重构修复：提取到包含该 processor 的 system 中，补 Actual_Processor_Binding
     * - 其他非法嵌套 → 删除该 subcomponent 声明行，同时级联删除引用该实例的连接行
     *
     * @param content      AADL 代码
     * @param declarations AADL 声明映射
     * @param result       验证结果
     * @return 修正后的 AADL 代码
     */
    private String fixIllegalSubcomponentNesting(String content,
                                                  Map<String, AadlDeclaration> declarations,
                                                  ValidationResult result) {
        String[] lines = content.split("\n");

        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+processor|virtual\\s+bus)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+processor|virtual\\s+bus)\\s+(\\w+)\\.impl\\s*;"
        );
        Pattern connPattern = Pattern.compile(
                "^(\\w+)\\s*:\\s*(port|bus\\s+access)\\s+(\\w+)\\.(\\w+)\\s*(->|<->)\\s*(\\w+)\\.(\\w+)"
        );

        // ===== 第一遍扫描：收集所有 implementation 的 subcomponents + 识别非法嵌套 =====
        // allSubcomponents: implName → list of [instanceName, childType, typeName, originalLine]
        Map<String, List<String[]>> allSubcomponents = new LinkedHashMap<>();
        // deletionCases: implName → 需要删除的实例名集合
        Map<String, Set<String>> deletionCases = new HashMap<>();
        // refactorCases: list of [instanceName, childTypeName, processorImplName]
        // 后续补充: [3]=systemImplName, [4]=processorInstanceNameInSystem
        List<String[]> refactorCases = new ArrayList<>();

        String currentImpl = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) continue;

            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                continue;
            }

            if (currentImpl != null) {
                Matcher subcompMatcher = subcompPattern.matcher(trimmed);
                if (subcompMatcher.find()) {
                    String instanceName = subcompMatcher.group(1);
                    String childType = subcompMatcher.group(2).replaceAll("\\s+", " ");
                    String typeName = subcompMatcher.group(3);

                    // 记录所有 subcomponent
                    allSubcomponents.computeIfAbsent(currentImpl, k -> new ArrayList<>())
                            .add(new String[]{instanceName, childType, typeName, trimmed});

                    AadlDeclaration parentDecl = declarations.get(currentImpl);
                    if (parentDecl == null || parentDecl.type == null) continue;

                    Set<String> allowed = CONTAINMENT_RULES.get(parentDecl.type);
                    if (allowed != null && !allowed.contains(childType)) {
                        // 特殊处理：process 在 processor 中 → 重构修复
                        if ("process".equals(childType) && "processor".equals(parentDecl.type)) {
                            refactorCases.add(new String[]{instanceName, typeName, currentImpl, null, null});
                        } else {
                            deletionCases.computeIfAbsent(currentImpl, k -> new LinkedHashSet<>()).add(instanceName);
                        }
                    }
                }
            }
        }

        if (deletionCases.isEmpty() && refactorCases.isEmpty()) {
            return content;
        }

        // ===== 为重构案例查找目标 system =====
        // 查找包含该 processor 的 system implementation
        for (String[] refactor : refactorCases) {
            String processorImplName = refactor[2];  // processor 的 implementation 名
            for (Map.Entry<String, List<String[]>> entry : allSubcomponents.entrySet()) {
                String implName = entry.getKey();
                AadlDeclaration decl = declarations.get(implName);
                if (decl != null && "system".equals(decl.type)) {
                    for (String[] subcomp : entry.getValue()) {
                        // subcomp[2] 是类型名，如果该 system 有一个 processor 子组件类型匹配
                        if (subcomp[2].equals(processorImplName)) {
                            AadlDeclaration subcompDecl = declarations.get(subcomp[2]);
                            if (subcompDecl != null && "processor".equals(subcompDecl.type)) {
                                refactor[3] = implName;       // system impl name
                                refactor[4] = subcomp[0];     // processor 在 system 中的实例名
                                break;
                            }
                        }
                    }
                }
            }
        }

        // ===== 第二遍扫描：执行删除和重构 =====
        List<String> resultLines = new ArrayList<>();
        currentImpl = null;
        int removedSubcompCount = 0;
        int removedConnCount = 0;
        int refactoredCount = 0;

        // 构建需要从 processor 中移除的重构实例名集合
        Set<String> refactorInstanceNames = new HashSet<>();
        Set<String> refactorImpls = new HashSet<>();
        for (String[] refactor : refactorCases) {
            refactorInstanceNames.add(refactor[0]);
            refactorImpls.add(refactor[2]);
        }

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                resultLines.add(line);
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                resultLines.add(line);
                continue;
            }

            if (currentImpl != null) {
                // === 处理重构案例：从 processor 中移除 process subcomponent ===
                if (refactorImpls.contains(currentImpl)) {
                    Matcher subcompMatcher = subcompPattern.matcher(trimmed);
                    if (subcompMatcher.find()) {
                        String instanceName = subcompMatcher.group(1);
                        if (refactorInstanceNames.contains(instanceName)) {
                            refactoredCount++;
                            // 查找对应的重构信息
                            String[] refactorInfo = null;
                            for (String[] r : refactorCases) {
                                if (r[0].equals(instanceName) && r[2].equals(currentImpl)) {
                                    refactorInfo = r;
                                    break;
                                }
                            }
                            if (refactorInfo != null && refactorInfo[3] != null) {
                                result.fixes.add(String.format(
                                        "已将 process '%s' 从 processor '%s' 提取到 system '%s' 中，并补充 Actual_Processor_Binding => reference (%s)",
                                        instanceName, currentImpl, refactorInfo[3], refactorInfo[4]
                                ));
                            } else {
                                result.fixes.add(String.format(
                                        "已将 process '%s' 从 processor '%s' 中移除（未找到包含该 processor 的 system，已删除）",
                                        instanceName, currentImpl
                                ));
                            }
                            log.info("自动修正：重构 process '{}' 从 processor '{}' 提取到 system", instanceName, currentImpl);
                            continue;  // 跳过该行（不添加到结果中）
                        }
                    }
                }

                // === 处理删除案例 ===
                Set<String> toRemove = deletionCases.get(currentImpl);

                // 检查是否是非法 subcomponent 声明行
                Matcher subcompMatcher = subcompPattern.matcher(trimmed);
                if (subcompMatcher.find() && toRemove != null) {
                    String instanceName = subcompMatcher.group(1);
                    if (toRemove.contains(instanceName)) {
                        removedSubcompCount++;
                        result.fixes.add(String.format(
                                "已删除非法嵌套的 subcomponent 声明: '%s' (类型: %s) 不能直接放在 '%s' (类型: %s) 中",
                                instanceName, subcompMatcher.group(2).replaceAll("\\s+", " "),
                                currentImpl, declarations.get(currentImpl) != null ? declarations.get(currentImpl).type : "未知"
                        ));
                        log.info("自动修正：删除非法嵌套 subcomponent '{}' (impl: {})", instanceName, currentImpl);
                        continue;
                    }
                }

                // 检查是否是引用了被删除实例的连接行
                Matcher connMatcher = connPattern.matcher(trimmed);
                if (connMatcher.find() && toRemove != null) {
                    String srcInstance = connMatcher.group(3);
                    String dstInstance = connMatcher.group(6);
                    String connName = connMatcher.group(1);

                    if (toRemove.contains(srcInstance) || toRemove.contains(dstInstance)) {
                        removedConnCount++;
                        result.fixes.add(String.format(
                                "已级联删除引用被移除实例的连接: '%s'", connName
                        ));
                        log.info("自动修正：级联删除连接 '{}' (引用了被移除的实例)", connName);
                        continue;
                    }
                }
            }

            resultLines.add(line);
        }

        // ===== 第三步：为重构案例在目标 system 中补充 subcomponent 和 binding =====
        if (refactoredCount > 0) {
            String intermediate = String.join("\n", resultLines);
            intermediate = addRefactoredProcessToSystem(intermediate, refactorCases, result);
            resultLines = new ArrayList<>(Arrays.asList(intermediate.split("\n")));
        }

        if (removedSubcompCount > 0 || removedConnCount > 0) {
            log.info("自动修正：共删除 {} 行非法嵌套 subcomponent 声明，{} 行级联连接",
                    removedSubcompCount, removedConnCount);
        }
        if (refactoredCount > 0) {
            log.info("自动修正：共重构 {} 个 process 从 processor 提取到 system", refactoredCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 将重构的 process subcomponent 添加到目标 system 的 subcomponents 块中，
     * 并在 properties 块中补充 Actual_Processor_Binding。
     */
    private String addRefactoredProcessToSystem(String content, List<String[]> refactorCases,
                                                  ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();

        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+processor|virtual\\s+bus)\\s+implementation\\s+(\\w+)\\.impl"
        );

        // 按目标 system 分组重构案例
        Map<String, List<String[]>> systemRefactors = new LinkedHashMap<>();
        for (String[] refactor : refactorCases) {
            if (refactor[3] != null) {
                systemRefactors.computeIfAbsent(refactor[3], k -> new ArrayList<>()).add(refactor);
            }
        }

        String currentImpl = null;
        boolean inSubcomponents = false;
        boolean inProperties = false;
        boolean inConnections = false;
        Set<String> processedSystems = new HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            Matcher implMatcher = implContextPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                inSubcomponents = false;
                inProperties = false;
                inConnections = false;
                resultLines.add(line);
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                // 在 system impl 结束前，如果该 system 有重构案例但还没有 properties 块，补充一个
                if (currentImpl != null && systemRefactors.containsKey(currentImpl) && !processedSystems.contains(currentImpl)) {
                    List<String[]> refactors = systemRefactors.get(currentImpl);
                    resultLines.add("    properties");
                    for (String[] r : refactors) {
                        resultLines.add(String.format(
                                "      Actual_Processor_Binding => reference (%s) applies to %s;",
                                r[4], r[0]
                        ));
                    }
                    processedSystems.add(currentImpl);
                }
                currentImpl = null;
                inSubcomponents = false;
                inProperties = false;
                inConnections = false;
                resultLines.add(line);
                continue;
            }

            // 进入 subcomponents 块
            if (currentImpl != null && trimmed.equals("subcomponents")) {
                inSubcomponents = true;
                inProperties = false;
                inConnections = false;
                resultLines.add(line);

                // 在 subcomponents 块开头添加重构的 process subcomponent
                if (systemRefactors.containsKey(currentImpl) && !processedSystems.contains(currentImpl)) {
                    List<String[]> refactors = systemRefactors.get(currentImpl);
                    for (String[] r : refactors) {
                        resultLines.add(String.format("      %s : process %s.impl;", r[0], r[1]));
                    }
                }
                continue;
            }

            // 进入 properties 块
            if (currentImpl != null && trimmed.equals("properties")) {
                inProperties = true;
                inSubcomponents = false;
                inConnections = false;
                resultLines.add(line);

                // 在 properties 块开头添加 Actual_Processor_Binding
                if (systemRefactors.containsKey(currentImpl) && !processedSystems.contains(currentImpl)) {
                    List<String[]> refactors = systemRefactors.get(currentImpl);
                    for (String[] r : refactors) {
                        resultLines.add(String.format(
                                "      Actual_Processor_Binding => reference (%s) applies to %s;",
                                r[4], r[0]
                        ));
                    }
                    processedSystems.add(currentImpl);
                }
                continue;
            }

            // 进入 connections 块
            if (currentImpl != null && trimmed.equals("connections")) {
                inConnections = true;
                inSubcomponents = false;
                inProperties = false;
                resultLines.add(line);
                continue;
            }

            // 退出各块
            if (trimmed.equals("subcomponents") || trimmed.equals("properties") ||
                    trimmed.equals("connections") || trimmed.equals("features") ||
                    trimmed.equals("flows") || trimmed.startsWith("annex")) {
                if (trimmed.equals("subcomponents")) inSubcomponents = true;
                else if (trimmed.equals("properties")) inProperties = true;
                else if (trimmed.equals("connections")) inConnections = true;
                else { inSubcomponents = false; inProperties = false; inConnections = false; }
            }

            resultLines.add(line);
        }

        return String.join("\n", resultLines);
    }

    /**
     * 自动修正 0n：修复连接操作符错误。
     *
     * port 连接必须用 ->（单向），bus access 连接必须用 <->（双向）。
     * 检测到操作符不匹配时直接替换：
     * - port 连接使用了 <-> → 替换为 ->
     * - bus access 连接使用了 -> → 替换为 <->
     */
    private String fixConnectionOperator(String content, ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();
        int fixCount = 0;

        Pattern connLinePattern = Pattern.compile(
                "^(\\s*)(\\w+)\\s*:\\s*(port|bus\\s+access)\\s+(\\w+)\\.(\\w+)\\s*(->|<->)\\s*(\\w+)\\.(\\w+)(.*)"
        );

        boolean inConnections = false;
        boolean inImplementation = false;
        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            if (implContextPattern.matcher(trimmed).find()) {
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

            if (inConnections && (trimmed.equals("properties") || trimmed.equals("subcomponents") ||
                    trimmed.equals("features") || trimmed.equals("flows") ||
                    trimmed.matches("end\\s+\\w+\\.impl\\s*;"))) {
                inConnections = false;
                resultLines.add(line);
                continue;
            }

            if (inConnections) {
                Matcher m = connLinePattern.matcher(line);
                if (m.find()) {
                    String indent = m.group(1);
                    String connName = m.group(2);
                    String connType = m.group(3).replaceAll("\\s+", " ");
                    String operator = m.group(6);
                    String rest = m.group(9);
                    String fixedOperator = operator;
                    String fixReason = null;

                    // port 连接必须用 ->
                    if ("port".equals(connType) && "<->".equals(operator)) {
                        fixedOperator = "->";
                        fixReason = "port 连接应使用单向 ->";
                    }

                    // bus access 连接必须用 <->
                    if ("bus access".equals(connType) && "->".equals(operator)) {
                        fixedOperator = "<->";
                        fixReason = "bus access 连接应使用双向 <->";
                    }

                    if (fixReason != null) {
                        String fixedLine = indent + connName + " : " + connType + " " +
                                m.group(4) + "." + m.group(5) + " " + fixedOperator + " " +
                                m.group(7) + "." + m.group(8) + rest;
                        resultLines.add(fixedLine);
                        fixCount++;
                        result.fixes.add(String.format(
                                "已修复连接 '%s' 的操作符: %s → %s (%s)",
                                connName, operator, fixedOperator, fixReason
                        ));
                        log.info("自动修正：连接 '{}' 操作符 {} → {}", connName, operator, fixedOperator);
                        continue;
                    }
                }
            }

            resultLines.add(line);
        }

        if (fixCount > 0) {
            log.info("自动修正：共修复 {} 处连接操作符错误", fixCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 自动修正 0p：修复 port 连接方向错误。
     *
     * 当 port 连接的源端是 in port、目标端是 out port 时（方向写反），
     * 交换源端和目标端，使数据流方向正确（out → in）。
     * 仅在能明确判断源端 in + 目标端 out 时执行交换。
     */
    private String fixPortDirectionAuto(String content, ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();
        int fixCount = 0;

        // 匹配 port 连接行
        Pattern connLinePattern = Pattern.compile(
                "^(\\s*)(\\w+)\\s*:\\s*port\\s+(\\w+)\\.(\\w+)\\s*->\\s*(\\w+)\\.(\\w+)(.*)"
        );

        // 解析 features 以获取端口方向
        Map<String, Map<String, String>> featureDirections = parseFeatureDirections(content);

        // 解析 subcomponents 以获取实例名 → 类型名映射
        Map<String, Map<String, String>> implInstanceMap = new HashMap<>();
        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+processor|virtual\\s+bus)\\s+(\\w+)\\.impl\\s*;"
        );

        String currentImpl = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;
            Matcher implM = implContextPattern.matcher(trimmed);
            if (implM.find()) {
                currentImpl = implM.group(1);
                continue;
            }
            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                continue;
            }
            if (currentImpl != null) {
                Matcher subM = subcompPattern.matcher(trimmed);
                if (subM.find()) {
                    implInstanceMap.computeIfAbsent(currentImpl, k -> new HashMap<>())
                            .put(subM.group(1), subM.group(3));
                }
            }
        }

        // 第二遍：修复方向错误的 port 连接
        boolean inConnections = false;
        currentImpl = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            Matcher implM = implContextPattern.matcher(trimmed);
            if (implM.find()) {
                currentImpl = implM.group(1);
                inConnections = false;
                resultLines.add(line);
                continue;
            }

            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                inConnections = false;
                resultLines.add(line);
                continue;
            }

            if (trimmed.equals("connections")) {
                inConnections = true;
                resultLines.add(line);
                continue;
            }

            if (inConnections && (trimmed.equals("properties") || trimmed.equals("subcomponents") ||
                    trimmed.equals("features") || trimmed.equals("flows"))) {
                inConnections = false;
                resultLines.add(line);
                continue;
            }

            if (inConnections && currentImpl != null) {
                Matcher m = connLinePattern.matcher(line);
                if (m.find()) {
                    String indent = m.group(1);
                    String connName = m.group(2);
                    String srcInstance = m.group(3);
                    String srcFeature = m.group(4);
                    String dstInstance = m.group(5);
                    String dstFeature = m.group(6);
                    String rest = m.group(7);

                    // 获取实例的类型名
                    Map<String, String> instanceMap = implInstanceMap.get(currentImpl);
                    if (instanceMap == null) {
                        resultLines.add(line);
                        continue;
                    }

                    String srcType = instanceMap.get(srcInstance);
                    String dstType = instanceMap.get(dstInstance);
                    if (srcType == null || dstType == null) {
                        resultLines.add(line);
                        continue;
                    }

                    // 获取端口方向
                    Map<String, String> srcFeatures = featureDirections.get(srcType);
                    Map<String, String> dstFeatures = featureDirections.get(dstType);
                    if (srcFeatures == null || dstFeatures == null) {
                        resultLines.add(line);
                        continue;
                    }

                    String srcDir = srcFeatures.get(srcFeature);
                    String dstDir = dstFeatures.get(dstFeature);

                    // 仅在源端 in + 目标端 out 时交换
                    if ("in".equals(srcDir) && "out".equals(dstDir)) {
                        String fixedLine = indent + connName + " : port " +
                                dstInstance + "." + dstFeature + " -> " +
                                srcInstance + "." + srcFeature + rest;
                        resultLines.add(fixedLine);
                        fixCount++;
                        result.fixes.add(String.format(
                                "已修复连接 '%s' 的方向: 交换源端和目标端 (%s.%s[in] ← %s.%s[out] → %s.%s[out] → %s.%s[in])",
                                connName, srcInstance, srcFeature, dstInstance, dstFeature,
                                dstInstance, dstFeature, srcInstance, srcFeature
                        ));
                        log.info("自动修正：连接 '{}' 方向修复（交换源端和目标端）", connName);
                        continue;
                    }
                }
            }

            resultLines.add(line);
        }

        if (fixCount > 0) {
            log.info("自动修正：共修复 {} 处 port 连接方向错误", fixCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 解析所有组件类型声明中 features 的端口方向。
     *
     * @return 组件类型名 → (feature名 → 方向["in"|"out"])
     */
    private Map<String, Map<String, String>> parseFeatureDirections(String aadlContent) {
        Map<String, Map<String, String>> result = new HashMap<>();
        String[] lines = aadlContent.split("\n");

        // 匹配组件类型声明（非 implementation）
        Pattern typeDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+(\\w+)\\s*$"
        );
        Pattern featurePattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(in|out)\\s+(?:data\\s+)?(?:port|event\\s+data\\s+port|data\\s+port)", Pattern.CASE_INSENSITIVE
        );

        String currentType = null;
        boolean inFeatures = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;

            Matcher typeM = typeDeclPattern.matcher(trimmed);
            if (typeM.find()) {
                currentType = typeM.group(2);
                inFeatures = false;
                continue;
            }

            if (currentType != null && trimmed.matches("end\\s+" + Pattern.quote(currentType) + "\\s*;")) {
                currentType = null;
                inFeatures = false;
                continue;
            }

            if (currentType != null && trimmed.contains("implementation")) {
                currentType = null;
                inFeatures = false;
                continue;
            }

            if (currentType != null && trimmed.equals("features")) {
                inFeatures = true;
                continue;
            }

            if (inFeatures && (trimmed.equals("properties") || trimmed.equals("flows") ||
                    trimmed.equals("connections") || trimmed.equals("subcomponents") ||
                    trimmed.startsWith("end "))) {
                inFeatures = false;
                continue;
            }

            if (inFeatures) {
                Matcher fM = featurePattern.matcher(trimmed);
                if (fM.find()) {
                    result.computeIfAbsent(currentType, k -> new HashMap<>())
                            .put(fM.group(1), fM.group(2).toLowerCase());
                }
            }
        }

        return result;
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
            // 去掉行尾注释后再比较，支持 "features  -- xxx" 这种写法
            String trimmedNoComment = trimmed.replaceAll("--.*$", "").trim();
            if (inImplementation && trimmedNoComment.equals("features")) {
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
                // 跳过注释行和空行，不将注释作为 feature 收集
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    implFeaturesMap.get(currentImplName).add(trimmed);
                }
                continue; // 跳过原 features 块中的行
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

    // ========================= 新增校验规则 =========================

    /**
     * 4x. 检测属性绑定完整性。
     * process 或 thread 没有在 properties 块中配置 Actual_Processor_Binding 时发出警告。
     * 代码生成强依赖于软硬件部署关系的明确，自动指派处理器可能违背架构师意图，仅警告。
     */
    private void checkPropertyBindingCompleteness(String aadlContent,
                                                    List<SubcomponentRef> subcomponentRefs,
                                                    ValidationResult result) {
        // 收集每个 implementation 中有 Actual_Processor_Binding 的实例名
        Map<String, Set<String>> boundInstances = new HashMap<>();
        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualImplPattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern bindingPattern = Pattern.compile(
                "Actual_Processor_Binding\\s*=>\\s*reference\\s*\\([^)]+\\)\\s*applies\\s+to\\s+(\\w+)", Pattern.CASE_INSENSITIVE
        );

        String currentImpl = null;
        for (String line : aadlContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;

            Matcher implMatcher = implContextPattern.matcher(trimmed);
            Matcher virtualMatcher = virtualImplPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                continue;
            }
            if (virtualMatcher.find()) {
                currentImpl = virtualMatcher.group(1);
                continue;
            }
            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentImpl = null;
                continue;
            }
            if (currentImpl != null) {
                Matcher bindingMatcher = bindingPattern.matcher(trimmed);
                if (bindingMatcher.find()) {
                    boundInstances.computeIfAbsent(currentImpl, k -> new HashSet<>())
                            .add(bindingMatcher.group(1));
                }
            }
        }

        // 检查每个 process/thread 实例是否有 binding
        for (SubcomponentRef ref : subcomponentRefs) {
            if (!"process".equals(ref.componentKeyword) && !"thread".equals(ref.componentKeyword)) {
                continue;
            }
            Set<String> bound = boundInstances.get(ref.parentImpl);
            if (bound == null || !bound.contains(ref.instanceName)) {
                result.warnings.add(String.format(
                        "属性绑定完整性: '%s' (%s) 在 '%s' 中缺少 Actual_Processor_Binding 属性；" +
                        "代码生成强依赖于软硬件部署关系，请补充部署绑定属性",
                        ref.instanceName, ref.componentKeyword, ref.parentImpl
                ));
            }
        }
    }

    /**
     * 4y. 检测数据类型一致性深度校验。
     * 连接的两端虽然都是 data port，但其 Data_Size 不一致时发出警告。
     * 用于确保向 SCADE 或 Rust 等强类型语言转换时不会出现缓冲区溢出或数据截断。
     */
    private void checkDataSizeConsistency(String aadlContent,
                                           List<ConnectionRef> connections,
                                           Map<String, Map<String, FeatureDetail>> featureDetails,
                                           List<SubcomponentRef> subcomponentRefs,
                                           ValidationResult result) {
        // 解析每个 data 组件的 Data_Size
        Map<String, String> dataSizes = new HashMap<>();
        Pattern dataImplPattern = Pattern.compile(
                "^\\s*data\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern dataSizePattern = Pattern.compile(
                "Data_Size\\s*=>\\s*(\\d+)\\s*Bytes", Pattern.CASE_INSENSITIVE
        );
        String currentDataImpl = null;
        for (String line : aadlContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;

            Matcher dataMatcher = dataImplPattern.matcher(trimmed);
            if (dataMatcher.find()) {
                currentDataImpl = dataMatcher.group(1);
                continue;
            }
            if (currentDataImpl != null && trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                currentDataImpl = null;
                continue;
            }
            if (currentDataImpl != null) {
                Matcher sizeMatcher = dataSizePattern.matcher(trimmed);
                if (sizeMatcher.find()) {
                    dataSizes.put(currentDataImpl, sizeMatcher.group(1));
                }
            }
        }

        // 构建 实例名→类型名 映射（用于查找端口引用的数据类型）
        Map<String, String> instanceToType = new HashMap<>();
        for (SubcomponentRef ref : subcomponentRefs) {
            instanceToType.put(ref.instanceName + "@" + ref.parentImpl, ref.typeName);
        }

        // 检查每个 port 连接两端的数据类型 Data_Size 是否一致
        for (ConnectionRef conn : connections) {
            if (!"port".equals(conn.connType)) continue;

            // 查找源端和目标端的数据类型
            String srcType = findComponentType(conn.sourceInstance, conn.parentImpl, subcomponentRefs);
            String dstType = findComponentType(conn.destInstance, conn.parentImpl, subcomponentRefs);

            if (srcType == null || dstType == null) continue;

            // 查找源端和目标端 feature 的数据类型
            String srcDataType = getFeatureDataType(featureDetails, srcType, conn.sourceFeature);
            String dstDataType = getFeatureDataType(featureDetails, dstType, conn.destFeature);

            if (srcDataType == null || dstDataType == null) continue;
            if (srcDataType.equals(dstDataType)) continue;

            // 查找两端的 Data_Size
            String srcSize = dataSizes.get(srcDataType);
            String dstSize = dataSizes.get(dstDataType);

            if (srcSize != null && dstSize != null && !srcSize.equals(dstSize)) {
                result.warnings.add(String.format(
                        "数据类型一致性: 连接 '%s' (%s.%s -> %s.%s) 两端 Data_Size 不匹配: " +
                        "%s (%s Bytes) vs %s (%s Bytes)；存在潜在的缓冲区溢出或数据截断风险",
                        conn.connName, conn.sourceInstance, conn.sourceFeature,
                        conn.destInstance, conn.destFeature,
                        srcDataType, srcSize, dstDataType, dstSize
                ));
            }
        }
    }

    /** 辅助方法：通过实例名和父 impl 查找组件类型名 */
    private String findComponentType(String instanceName, String parentImpl,
                                      List<SubcomponentRef> subcomponentRefs) {
        for (SubcomponentRef ref : subcomponentRefs) {
            if (ref.instanceName.equals(instanceName) && ref.parentImpl.equals(parentImpl)) {
                return ref.typeName;
            }
        }
        return null;
    }

    /** 辅助方法：获取组件类型中某个 feature 的数据类型 */
    private String getFeatureDataType(Map<String, Map<String, FeatureDetail>> featureDetails,
                                       String componentType, String featureName) {
        if (componentType == null || featureName == null) return null;
        Map<String, FeatureDetail> features = featureDetails.get(componentType);
        if (features == null) return null;
        FeatureDetail fd = features.get(featureName);
        return fd != null ? fd.dataType : null;
    }

    /**
     * 4z. 检测命名空间冲突。
     * subcomponents 中的实例名称与顶层包名、其他类型名称或 connections 名称完全相同时发出错误。
     * 自动修复：在实例或连接名称后自动追加 _inst 或 _conn 后缀。
     */
    /**
     * 4z. 检测命名空间冲突（作用域限定在每个 implementation 内部）。
     *
     * AADL 作用域规则：
     * 1. 子组件实例名（Subcomponent Name）不能与当前 implementation 所属的类型名（Type Name）相同
     *    （例如在 system implementation MainSystem.impl 中，不能有名为 MainSystem 的实例）
     * 2. 同一 implementation 作用域内，实例名不能重复
     * 3. 同一 implementation 作用域内，连接名不能重复
     * 4. 同一 implementation 作用域内，实例名和连接名不能冲突
     *
     * 注意：实例名与其他组件类型名（非当前 impl 所属类型）同名是合法的，
     * 因为 AADL 中类型名和实例名在不同命名空间。
     */
    private void checkNamingCollision(String aadlContent,
                                       List<SubcomponentRef> subcomponentRefs,
                                       List<ConnectionRef> connectionRefs,
                                       Map<String, AadlDeclaration> declarations,
                                       ValidationResult result) {
        // 按 parentImpl 分组：建立每个 implementation 内的实例名集合和连接名集合
        Map<String, Set<String>> implInstances = new LinkedHashMap<>();
        Map<String, Set<String>> implConns = new LinkedHashMap<>();
        // 记录重复项（避免重复报错）
        Map<String, Set<String>> reportedDups = new HashMap<>();

        for (SubcomponentRef ref : subcomponentRefs) {
            if (ref.parentImpl == null) continue;
            Set<String> names = implInstances.computeIfAbsent(ref.parentImpl, k -> new LinkedHashSet<>());
            // 规则1：实例名不能与当前 impl 所属的类型名相同
            if (ref.instanceName.equals(ref.parentImpl)) {
                result.errors.add(String.format(
                        "第%d行: 命名空间冲突 - 实例名 '%s' 与当前 implementation 所属的类型名 '%s' 相同（在 '%s.impl' 中）; " +
                        "子组件实例名不能与包含它的组件类型同名，可能导致编译器 AST 解析错误",
                        ref.lineNumber, ref.instanceName, ref.parentImpl, ref.parentImpl
                ));
            }
            // 规则2：同一作用域内实例名不能重复
            if (names.contains(ref.instanceName)) {
                String key = ref.parentImpl + "::" + ref.instanceName;
                if (!reportedDups.containsKey(key) || !reportedDups.get(key).contains("inst")) {
                    result.errors.add(String.format(
                            "第%d行: 命名空间冲突 - 实例名 '%s' 在 '%s.impl' 的 subcomponents 中重复声明; " +
                            "同一作用域内实例名必须唯一",
                            ref.lineNumber, ref.instanceName, ref.parentImpl
                    ));
                    reportedDups.computeIfAbsent(key, k -> new HashSet<>()).add("inst");
                }
            } else {
                names.add(ref.instanceName);
            }
        }

        for (ConnectionRef conn : connectionRefs) {
            if (conn.parentImpl == null) continue;
            Set<String> connNames = implConns.computeIfAbsent(conn.parentImpl, k -> new LinkedHashSet<>());
            Set<String> instNames = implInstances.getOrDefault(conn.parentImpl, Collections.emptySet());

            // 规则1（连接端）：连接名不能与当前 impl 所属的类型名相同
            if (conn.connName.equals(conn.parentImpl)) {
                result.errors.add(String.format(
                        "第%d行: 命名空间冲突 - 连接名 '%s' 与当前 implementation 所属的类型名 '%s' 相同（在 '%s.impl' 中）; " +
                        "连接名不能与包含它的组件类型同名",
                        conn.lineNumber, conn.connName, conn.parentImpl, conn.parentImpl
                ));
            }
            // 规则3：同一作用域内连接名不能重复
            if (connNames.contains(conn.connName)) {
                String key = conn.parentImpl + "::" + conn.connName;
                if (!reportedDups.containsKey(key) || !reportedDups.get(key).contains("conn")) {
                    result.errors.add(String.format(
                            "第%d行: 命名空间冲突 - 连接名 '%s' 在 '%s.impl' 的 connections 中重复声明; " +
                            "同一作用域内连接名必须唯一",
                            conn.lineNumber, conn.connName, conn.parentImpl
                    ));
                    reportedDups.computeIfAbsent(key, k -> new HashSet<>()).add("conn");
                }
            } else {
                connNames.add(conn.connName);
            }
            // 规则4：连接名不能与同一作用域内的实例名冲突
            if (instNames.contains(conn.connName)) {
                result.errors.add(String.format(
                        "第%d行: 命名空间冲突 - 连接名 '%s' 与同一作用域 '%s.impl' 内的实例名重名; " +
                        "连接名和实例名共享同一命名空间，不能重名",
                        conn.lineNumber, conn.connName, conn.parentImpl
                ));
            }
        }
    }

    /**
     * 4aa. 检测畸形 end 语句。
     * 通过追踪组件声明栈来识别畸形 end：凡是以 "end " 开头但不符合正常格式的，都算畸形。
     * 正常格式：end 标识符; 或 end 标识符.impl;
     */
    private void checkMalformedEndStatements(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");
        // 组件声明开头：类型关键字 + 组件名（可选 .impl）
        Pattern declStartPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|" +
                "virtual\\s+processor|virtual\\s+bus|thread\\s+group|abstract)\\s+" +
                "implementation\\s+([A-Za-z_]\\w*)\\.impl\\b",
                Pattern.CASE_INSENSITIVE
        );
        Pattern typeStartPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|" +
                "virtual\\s+processor|virtual\\s+bus|thread\\s+group|abstract)\\s+" +
                "([A-Za-z_]\\w*)\\s*$",
                Pattern.CASE_INSENSITIVE
        );
        // 正常 end 语句
        Pattern normalEndPattern = Pattern.compile(
                "^\\s*end\\s+[A-Za-z_]\\w*(?:\\.impl)?\\s*;\\s*$"
        );
        // 疑似 end 语句：以 end 开头，以分号结尾
        Pattern suspiciousEndPattern = Pattern.compile(
                "^\\s*end\\s+.*;\\s*$", Pattern.CASE_INSENSITIVE
        );

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("--")) continue;

            if (suspiciousEndPattern.matcher(trimmed).find()
                    && !normalEndPattern.matcher(trimmed).matches()) {
                result.errors.add(String.format(
                        "第%d行: 畸形 end 语句 '%s' — 不符合 'end 标识符[.impl];' 的规范格式",
                        i + 1, trimmed
                ));
            }
        }
    }

    /**
     * 自动修正 0r：修复畸形 end 语句。
     * 策略：不猜畸形文本的含义，直接用组件栈顶的正确名字替换。
     * - 维护一个组件声明栈：遇到类型/实现声明开头就压栈
     * - 遇到畸形 end 语句时，弹出栈顶组件名，生成正确的 end 语句
     * - 遇到正常 end 语句也弹出栈顶（用于同步栈状态）
     */
    private String fixMalformedEndStatements(String content, ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();
        int fixCount = 0;

        // 组件声明开头（implementation）
        Pattern implStartPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|" +
                "virtual\\s+processor|virtual\\s+bus|thread\\s+group|abstract)\\s+" +
                "implementation\\s+([A-Za-z_]\\w*)\\.impl\\b",
                Pattern.CASE_INSENSITIVE
        );
        // 类型声明开头
        Pattern typeStartPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|" +
                "virtual\\s+processor|virtual\\s+bus|thread\\s+group|abstract)\\s+" +
                "([A-Za-z_]\\w*)\\s*$",
                Pattern.CASE_INSENSITIVE
        );
        // 正常 end 语句
        Pattern normalEndPattern = Pattern.compile(
                "^(\\s*)end\\s+([A-Za-z_]\\w*(?:\\.impl)?)\\s*;\\s*$"
        );
        // 疑似畸形 end 语句
        Pattern suspiciousEndPattern = Pattern.compile(
                "^(\\s*)end\\s+.*;\\s*$", Pattern.CASE_INSENSITIVE
        );
        // package 声明
        Pattern packageStartPattern = Pattern.compile(
                "^\\s*package\\s+(\\S+)", Pattern.CASE_INSENSITIVE
        );

        // 组件栈：存组件全名（如 "PwmBus" 或 "PwmBus.impl"）
        Stack<String> componentStack = new Stack<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }

            // package 开头
            Matcher pkgMatcher = packageStartPattern.matcher(trimmed);
            if (pkgMatcher.find()) {
                componentStack.push(pkgMatcher.group(1));
                resultLines.add(line);
                continue;
            }

            // implementation 声明开头
            Matcher implMatcher = implStartPattern.matcher(line);
            if (implMatcher.find()) {
                componentStack.push(implMatcher.group(2) + ".impl");
                resultLines.add(line);
                continue;
            }

            // 类型声明开头（必须在 implementation 之后判断，避免把 "system implementation" 误判成类型声明）
            Matcher typeMatcher = typeStartPattern.matcher(line);
            if (typeMatcher.find() && !trimmed.toLowerCase().contains("implementation")) {
                componentStack.push(typeMatcher.group(2));
                resultLines.add(line);
                continue;
            }

            // 正常 end 语句：先校验名称是否匹配，匹配才弹出栈顶；不匹配则交由下方畸形逻辑修正
            // 去掉行尾注释后再匹配，支持 "end Foo;  -- xxx" 这种写法
            String trimmedNoComment = trimmed.replaceAll("--.*$", "").trim();
            Matcher normalEndMatcher = normalEndPattern.matcher(trimmedNoComment);
            if (normalEndMatcher.matches()) {
                String declaredName = normalEndMatcher.group(2);
                if (!componentStack.isEmpty()) {
                    String expectedName = componentStack.peek();
                    // 名称匹配才出栈；不匹配则继续向下，交由畸形 end 逻辑自动修正名称
                    if (declaredName.equalsIgnoreCase(expectedName)) {
                        componentStack.pop();
                        resultLines.add(line);
                        continue;
                    }
                } else {
                    // 栈空但有合法格式的 end，也加入结果（可能是多余的 end，由后续逻辑处理）
                    resultLines.add(line);
                    continue;
                }
            }

            // 畸形 end 语句：用栈顶名字替换（同样去掉行尾注释再匹配）
            Matcher susMatcher = suspiciousEndPattern.matcher(trimmedNoComment);
            if (susMatcher.find()) {
                // 从原始行提取缩进
                Matcher indentMatcher = Pattern.compile("^(\\s*)").matcher(line);
                String indent = indentMatcher.find() ? indentMatcher.group(1) : "";

                if (componentStack.isEmpty()) {
                    // 栈空说明没有未关闭的组件，这行畸形 end 是多余的，直接删除
                    fixCount++;
                    result.fixes.add(String.format(
                            "第%d行: 已删除多余的畸形 end 语句 '%s'（当前无未关闭的组件）",
                            i + 1, trimmed
                    ));
                    continue; // 不加入结果，相当于删除
                }

                String correctName = componentStack.pop();
                String fixedLine = indent + "end " + correctName + ";";

                fixCount++;
                result.fixes.add(String.format(
                        "第%d行: 已修正畸形 end 语句 '%s' → 'end %s;'（基于当前组件上下文）",
                        i + 1, trimmed, correctName
                ));
                resultLines.add(fixedLine);
            } else {
                resultLines.add(line);
            }
        }

        if (fixCount > 0) {
            log.info("自动修正：共修复 {} 处畸形 end 语句", fixCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 自动修正 0q：修复命名空间冲突。
     * 实例名与类型名/连接名冲突时，在实例名后追加 _inst 后缀。
     * 连接名与实例名/类型名冲突时，在连接名后追加 _conn 后缀。
     *
     * 关键修复：精确区分实例引用和类型引用，避免将 TypeName.impl 中的类型名误替换。
     * - 实例引用特征：instName.featureName（featureName != "impl"）
     * - 类型引用特征：TypeName.impl
     * - 注释行、类型声明行、implementation声明行、end语句行不进行替换
     */
    private String fixNamingCollision(String content, ValidationResult result) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();
        int fixCount = 0;

        // ==================== 第一阶段：按 impl 作用域收集名称并构建重命名映射 ====================
        // 结构：implName → (oldName → newName)，分别记录实例重命名和连接重命名
        Map<String, Map<String, String>> instRenamesByImpl = new LinkedHashMap<>();
        Map<String, Map<String, String>> connRenamesByImpl = new LinkedHashMap<>();

        Pattern implStartPattern = Pattern.compile(
                "^\\s*(?:virtual\\s+processor|system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl\\b"
        );
        Pattern virtualImplStartPattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+implementation\\s+(\\w+)\\.impl\\b"
        );
        Pattern endImplPattern = Pattern.compile("^\\s*end\\s+\\w+\\.impl\\s*;");
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(?:virtual\\s+processor|system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+(\\w+)\\.impl\\s*;"
        );
        Pattern connDeclPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(?:port|bus\\s+access|event\\s+port|event\\s+data\\s+port|data\\s+port)\\s+"
        );

        String currentImplName = null;
        // 逐行收集
        for (String line : lines) {
            String codePart = stripComment(line);
            if (codePart.trim().isEmpty()) continue;
            String trimmed = codePart.trim();

            if (trimmed.startsWith("--")) continue;

            Matcher implMatcher = implStartPattern.matcher(trimmed);
            if (implMatcher.find()) {
                currentImplName = implMatcher.group(1);
                instRenamesByImpl.putIfAbsent(currentImplName, new LinkedHashMap<>());
                connRenamesByImpl.putIfAbsent(currentImplName, new LinkedHashMap<>());
                continue;
            }
            Matcher vImplMatcher = virtualImplStartPattern.matcher(trimmed);
            if (vImplMatcher.find()) {
                currentImplName = vImplMatcher.group(1);
                instRenamesByImpl.putIfAbsent(currentImplName, new LinkedHashMap<>());
                connRenamesByImpl.putIfAbsent(currentImplName, new LinkedHashMap<>());
                continue;
            }
            if (endImplPattern.matcher(trimmed).find()) {
                currentImplName = null;
                continue;
            }

            if (currentImplName == null) continue;

            Map<String, String> instRenames = instRenamesByImpl.get(currentImplName);
            Map<String, String> connRenames = connRenamesByImpl.get(currentImplName);
            Set<String> usedNewNames = new HashSet<>();
            usedNewNames.addAll(instRenames.values());
            usedNewNames.addAll(connRenames.values());

            Matcher sm = subcompPattern.matcher(codePart);
            if (sm.find()) {
                String instName = sm.group(1);
                // 规则：实例名等于当前 impl 名称时需要重命名
                if (instName.equals(currentImplName) && !instRenames.containsKey(instName)) {
                    String newName = instName + "_inst";
                    while (usedNewNames.contains(newName)) {
                        newName = newName + "_x";
                    }
                    instRenames.put(instName, newName);
                    usedNewNames.add(newName);
                }
            }

            Matcher cm = connDeclPattern.matcher(codePart);
            if (cm.find()) {
                String connName = cm.group(1);
                // 规则：连接名等于当前 impl 名称时需要重命名
                if (connName.equals(currentImplName) && !connRenames.containsKey(connName)) {
                    String newName = connName + "_conn";
                    while (usedNewNames.contains(newName)) {
                        newName = newName + "_x";
                    }
                    connRenames.put(connName, newName);
                    usedNewNames.add(newName);
                }
            }
        }

        // ==================== 第二阶段：逐行精确替换 ====================
        currentImplName = null;

        // 匹配 subcomponent 声明行开头的实例名（冒号左边）
        Pattern subcompInstPattern = Pattern.compile(
                "^(\\s*)(\\w+)(\\s*:\\s*(?:virtual\\s+processor|system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+)"
        );
        // 匹配连接声明行开头的连接名（冒号左边）
        Pattern connDeclNamePattern = Pattern.compile(
                "^(\\s*)(\\w+)(\\s*:\\s*(?:port|bus\\s+access|event\\s+port|event\\s+data\\s+port|data\\s+port)\\s+)"
        );
        // 类型声明和end语句保护
        Pattern typeDeclLinePattern = Pattern.compile(
                "^\\s*(?:virtual\\s+processor|system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+\\w+\\s*$"
        );

        List<String> allFixes = new ArrayList<>();

        for (String line : lines) {
            // 先跟踪 impl 上下文（基于原始行，不受替换影响）
            String rawCode = stripComment(line);
            String rawTrimmed = rawCode.trim();

            Matcher implMatcher = implStartPattern.matcher(rawTrimmed);
            if (implMatcher.find()) {
                currentImplName = implMatcher.group(1);
                resultLines.add(line);
                continue;
            }
            Matcher vImplMatcher = virtualImplStartPattern.matcher(rawTrimmed);
            if (vImplMatcher.find()) {
                currentImplName = vImplMatcher.group(1);
                resultLines.add(line);
                continue;
            }
            if (endImplPattern.matcher(rawTrimmed).find()) {
                currentImplName = null;
                resultLines.add(line);
                continue;
            }

            if (currentImplName == null) {
                resultLines.add(line);
                continue;
            }

            Map<String, String> instRenames = instRenamesByImpl.getOrDefault(currentImplName, Collections.emptyMap());
            Map<String, String> connRenames = connRenamesByImpl.getOrDefault(currentImplName, Collections.emptyMap());

            if (instRenames.isEmpty() && connRenames.isEmpty()) {
                resultLines.add(line);
                continue;
            }

            // 分离代码部分和注释部分
            int commentIdx = line.indexOf("--");
            String codePart = commentIdx >= 0 ? line.substring(0, commentIdx) : line;
            String commentPart = commentIdx >= 0 ? line.substring(commentIdx) : "";

            if (codePart.trim().isEmpty()) {
                resultLines.add(line);
                continue;
            }

            String modified = codePart;
            boolean changed = false;

            // 判断是否是受保护的行（类型声明行等）
            boolean isProtected = typeDeclLinePattern.matcher(codePart.trim()).find()
                    && !codePart.contains("implementation") && !codePart.contains(":");

            if (!isProtected) {
                // 替换连接声明行开头的连接名
                Matcher cm = connDeclNamePattern.matcher(modified);
                if (cm.find()) {
                    String connName = cm.group(2);
                    if (connRenames.containsKey(connName)) {
                        String newName = connRenames.get(connName);
                        modified = cm.replaceFirst("$1" + newName + "$3");
                        changed = true;
                    }
                }

                // 替换 subcomponent 声明行开头的实例名
                Matcher sm = subcompInstPattern.matcher(modified);
                if (sm.find()) {
                    String instName = sm.group(2);
                    if (instRenames.containsKey(instName)) {
                        String newName = instRenames.get(instName);
                        modified = sm.replaceFirst("$1" + newName + "$3");
                        changed = true;
                    }
                }

                // 替换连接行中对实例名的引用：inst.feature 但排除 TypeName.impl
                for (Map.Entry<String, String> entry : instRenames.entrySet()) {
                    String oldName = entry.getKey();
                    String newName = entry.getValue();
                    String before = modified;
                    modified = modified.replaceAll(
                            "\\b" + Pattern.quote(oldName) + "\\.(?!impl\\b)",
                            Matcher.quoteReplacement(newName + ".")
                    );
                    if (!modified.equals(before)) changed = true;
                }

                // 替换 applies to 中的实例名
                for (Map.Entry<String, String> entry : instRenames.entrySet()) {
                    String oldName = entry.getKey();
                    String newName = entry.getValue();
                    String before = modified;
                    modified = modified.replaceAll(
                            "(applies\\s+to\\s+(?:[\\w,\\s\\(\\)]*?))\\b" + Pattern.quote(oldName) + "\\b",
                            "$1" + Matcher.quoteReplacement(newName)
                    );
                    if (!modified.equals(before)) changed = true;
                }
            }

            String resultLine = modified + commentPart;
            if (changed) {
                fixCount++;
            }
            resultLines.add(resultLine);
        }

        // 记录修复日志
        for (Map.Entry<String, Map<String, String>> implEntry : instRenamesByImpl.entrySet()) {
            for (Map.Entry<String, String> e : implEntry.getValue().entrySet()) {
                String msg = String.format(
                        "已重命名 %s.impl 中实例 '%s' → '%s'（实例名不能与包含它的组件类型同名）",
                        implEntry.getKey(), e.getKey(), e.getValue());
                result.fixes.add(msg);
                allFixes.add(msg);
            }
        }
        for (Map.Entry<String, Map<String, String>> implEntry : connRenamesByImpl.entrySet()) {
            for (Map.Entry<String, String> e : implEntry.getValue().entrySet()) {
                String msg = String.format(
                        "已重命名 %s.impl 中连接 '%s' → '%s'（连接名不能与包含它的组件类型同名）",
                        implEntry.getKey(), e.getKey(), e.getValue());
                result.fixes.add(msg);
                allFixes.add(msg);
            }
        }

        if (fixCount > 0) {
            log.info("自动修正：共重命名 {} 处命名空间冲突", fixCount);
        }
        return String.join("\n", resultLines);
    }

    /**
     * 在原始内容中，对每个有错误/警告的行，在其前一行插入注释标注。
     * 错误标注为 "-- [ERROR] 消息"，警告标注为 "-- [WARNING] 消息"。
     * 同一行有多个问题时，每个问题单独一行注释。
     * 无行号信息的消息汇总为文件头部注释块。
     * 注意：插入的是独立注释行，不修改代码行本身，避免干扰后续修复方法的正则匹配。
     */
    private String annotateErrorsAndWarningsInline(String content, ValidationResult result) {
        Map<Integer, List<String>> errorMap = new TreeMap<>();
        Map<Integer, List<String>> warningMap = new TreeMap<>();
        List<String> noLineErrors = new ArrayList<>();
        List<String> noLineWarnings = new ArrayList<>();

        Pattern lineNumPattern = Pattern.compile("^第(\\d+)行:\\s*(.*)$");

        for (String err : result.errors) {
            Matcher m = lineNumPattern.matcher(err);
            if (m.find()) {
                int lineNum = Integer.parseInt(m.group(1));
                String msg = m.group(2);
                errorMap.computeIfAbsent(lineNum, k -> new ArrayList<>()).add(msg);
            } else {
                noLineErrors.add(err);
            }
        }
        for (String warn : result.warnings) {
            Matcher m = lineNumPattern.matcher(warn);
            if (m.find()) {
                int lineNum = Integer.parseInt(m.group(1));
                String msg = m.group(2);
                warningMap.computeIfAbsent(lineNum, k -> new ArrayList<>()).add(msg);
            } else {
                noLineWarnings.add(warn);
            }
        }

        if (errorMap.isEmpty() && warningMap.isEmpty() && noLineErrors.isEmpty() && noLineWarnings.isEmpty()) {
            return content;
        }

        String[] lines = content.split("\n", -1);
        List<String> resultLines = new ArrayList<>();

        if (!noLineErrors.isEmpty() || !noLineWarnings.isEmpty()) {
            resultLines.add("-- =======================================");
            resultLines.add("-- [验证结果] 以下问题无法定位到具体行：");
            for (String msg : noLineErrors) {
                resultLines.add("-- [ERROR] " + msg);
            }
            for (String msg : noLineWarnings) {
                resultLines.add("-- [WARNING] " + msg);
            }
            resultLines.add("-- =======================================");
        }

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            // 在当前代码行之前插入注释标注
            List<String> lineErrors = errorMap.get(lineNum);
            List<String> lineWarnings = warningMap.get(lineNum);

            if (lineErrors != null) {
                for (String msg : lineErrors) {
                    resultLines.add("-- [ERROR] " + msg);
                }
            }
            if (lineWarnings != null) {
                for (String msg : lineWarnings) {
                    resultLines.add("-- [WARNING] " + msg);
                }
            }

            resultLines.add(line);
        }

        return String.join("\n", resultLines);
    }

    /**
     * 剥离行尾注释，返回纯代码部分。
     * 注意：不处理字符串中的 "--"（AADL中字符串极少出现此模式）。
     */
    private String stripComment(String line) {
        int idx = line.indexOf("--");
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    /**
     * 自动修正（按顺序执行）：
     * 0r. 修复畸形 end 语句（必须最先执行，否则后续解析会出错）
     * 0a. 修正非法 'requires data port' 语法 → 'in data port'
     * 0b. 修复截断/不完整的连接行（动态推断端口名 + 补充分号）
     * 0c. 删除线程 implementation 中非法的 connections 块
     * 0e. 将 implementation 中非法的 features 块移到对应的类型声明中
     * 0h. 重排 implementation 中的块顺序为 subcomponents → connections → properties
     * 0n. 修复连接操作符错误（port 用 <-> 改 ->，access 用 -> 改 <->）
     * 0q. 修复命名空间冲突（实例名/连接名与类型名重名时追加后缀）
     * 1.  补全缺失的组件声明（类型声明 + 实现声明）
     * 2.  补全不完整的声明（只有类型声明补实现声明，或反之）
     * 3.  补全 connections 引用中缺失的 feature 声明
     *
     * 注意：0d/0i/0j/0k/0l/0m/0p 已移除，这些问题改为在原文对应位置标注 [ERROR]/[WARNING] 注释。
     */
    private String applyFixes(String aadlContent,
                              Map<String, AadlDeclaration> declarations,
                              Map<String, AadlInputParser.ArchNode> archComponents,
                              Map<String, Map<String, String>> componentFeatures,
                              ValidationResult result) {
        // 0. 在原始内容对应行尾标注所有错误和警告（行内注释，不新增行）
        String content = annotateErrorsAndWarningsInline(aadlContent, result);

        // 0r. 修复畸形 end 语句（必须最先执行，否则后续解析会出错）
        content = fixMalformedEndStatements(content, result);

        // 0a. 修正非法 requires data port 语法
        content = fixRequiresDataPort(content, result);

        // 0b. 修复截断/不完整的连接行（硬编码补充缺失端口名 + 分号）
        content = fixIncompleteConnectionLines(content, result);

        // 0c. 删除线程 implementation 中非法的 connections 块
        content = fixThreadConnectionsBlocks(content, result);

        // 0e. 将 implementation 中非法的 features 块移到对应的类型声明中
        content = fixFeaturesPlacement(content, result);

        // 0h. 重排 implementation 中的块顺序为 subcomponents → connections → properties
        content = fixImplementationOrder(content, result);

        // 0n. 修复连接操作符错误（port 用 <-> 改 ->，access 用 -> 改 <->）
        content = fixConnectionOperator(content, result);

        // 0q. 修复命名空间冲突（实例名/连接名与类型名重名时追加后缀）
        content = fixNamingCollision(content, result);

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

                // 安全网：跳过 data 组件 —— data 组件是纯类型分类器，严禁拥有 features 块
                AadlDeclaration decl = declarations.get(typeName);
                if (decl != null && "data".equals(decl.type)) {
                    result.warnings.add(String.format(
                            "跳过为 data 组件 '%s' 注入 feature 声明：data 组件是纯类型分类器，严禁拥有 features 块。" +
                            "引用 '%s' 端口的连接行应被删除或修正",
                            typeName, String.join("', '", missingFeats.keySet())
                    ));
                    log.warn("跳过为 data 组件 '{}' 注入 feature（安全网拦截）", typeName);
                    continue;
                }

                Map<String, String> existingFeats = componentFeatures.get(typeName);

                // 过滤掉已存在的（可能在补全过程中已被其他逻辑添加）
                Map<String, String> toAdd = new LinkedHashMap<>();
                for (Map.Entry<String, String> fe : missingFeats.entrySet()) {
                    String featName = fe.getKey();
                    String dataType = fe.getValue();
                    if (existingFeats == null || !existingFeats.containsKey(featName)) {
                        // 数据类型为空时，从 AADL 声明中回退查找 data 组件
                        if (dataType == null || dataType.isEmpty()) {
                            dataType = findFallbackDataType(featName, declarations);
                            log.info("feature '{}' 数据类型为空，回退查找结果: {}", featName, dataType);
                        }
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
            // 安全网：数据类型为空时使用 Base_Type，避免生成非法的 "data port;" 无类型声明
            if (dataType == null || dataType.isEmpty()) {
                dataType = "Base_Type";
            }
            featureLines.append("    ").append(featName).append(" : ")
                    .append(direction).append(" data port ").append(dataType).append(";\n");
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
