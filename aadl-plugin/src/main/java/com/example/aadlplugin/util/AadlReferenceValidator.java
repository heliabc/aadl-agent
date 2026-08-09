package com.example.aadlplugin.util;


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
public class AadlReferenceValidator {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AadlReferenceValidator.class);

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

    // ========================= 统一正则表达式常量（避免各处不一致）=========================

    /**
     * 所有AADL组件类型关键字的正则片段（不含virtual）。
     * 用于内联组合到更大的正则中。
     * 注意：thread group和subprogram group用可选group匹配。
     */
    private static final String COMPONENT_TYPES_REGEX =
            "system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract";

    /**
     * virtual组件类型关键字的正则片段。
     */
    private static final String VIRTUAL_TYPES_REGEX = "virtual\\s+(?:processor|bus)";

    /**
     * 所有组件类型（含virtual）的正则片段。
     */
    private static final String ALL_COMPONENT_TYPES_REGEX =
            "(?:" + COMPONENT_TYPES_REGEX + "|" + VIRTUAL_TYPES_REGEX + ")";

    /**
     * 类型声明正则（支持extends）：匹配 "system Foo" 或 "system Foo extends Bar"。
     * group(1)=类型关键字, group(2)=组件名
     */
    private static final Pattern TYPE_DECL_PATTERN = Pattern.compile(
            "^\\s*(" + COMPONENT_TYPES_REGEX + ")\\s+(\\w+)(?:\\s+extends\\s+\\w+)?\\s*$"
    );

    /**
     * virtual类型声明正则（支持extends）：匹配 "virtual processor Foo"。
     * group(1)=virtual类型(virtual processor/virtual bus), group(2)=组件名
     */
    private static final Pattern VIRTUAL_TYPE_DECL_PATTERN = Pattern.compile(
            "^\\s*(" + VIRTUAL_TYPES_REGEX + ")\\s+(\\w+)(?:\\s+extends\\s+\\w+)?\\s*$"
    );

    /**
     * 实现声明正则：匹配 "system implementation Foo.impl"。
     * group(1)=类型关键字, group(2)=组件名(不含.impl)
     */
    private static final Pattern IMPL_DECL_PATTERN = Pattern.compile(
            "^\\s*(" + COMPONENT_TYPES_REGEX + ")\\s+implementation\\s+(\\w+)\\.impl(?:\\s+extends\\s+\\w+\\.impl)?\\s*$"
    );

    /**
     * virtual实现声明正则：匹配 "virtual processor implementation Foo.impl"。
     * group(1)=virtual类型, group(2)=组件名
     */
    private static final Pattern VIRTUAL_IMPL_DECL_PATTERN = Pattern.compile(
            "^\\s*(" + VIRTUAL_TYPES_REGEX + ")\\s+implementation\\s+(\\w+)\\.impl(?:\\s+extends\\s+\\w+\\.impl)?\\s*$"
    );

    /**
     * subcomponent声明行正则：匹配 "inst : type Type.impl;"。
     * group(1)=实例名, group(2)=组件关键字, group(3)=类型名
     */
    private static final Pattern SUBCOMP_PATTERN = Pattern.compile(
            "^\\s*(\\w+)\\s*:\\s*(" + ALL_COMPONENT_TYPES_REGEX + ")\\s+(\\w+)\\.impl\\s*;.*$"
    );

    /**
     * impl上下文开始正则（进入某个implementation）。
     * group(1)=impl名(不含.impl)
     */
    private static final Pattern IMPL_CONTEXT_PATTERN = Pattern.compile(
            "^\\s*(" + ALL_COMPONENT_TYPES_REGEX + ")\\s+implementation\\s+(\\w+)\\.impl(?:\\s+extends\\s+[\\w.]+)?\\s*$"
    );

    /** 检查名称是否为 AADL 保留字（不区分大小写） */
    private boolean isReservedWord(String name) {
        return name != null && AADL_RESERVED_WORDS.contains(name.toLowerCase());
    }

    /**
     * 去除行尾注释并 trim。
     * AADL 注释以 "--" 开头到行尾，但在 annex {** ... **} 块内不应处理。
     * 注意：所有parse/check/fix方法应使用 protectAnnexBlocks 预处理内容，
     *       这样annex内部行会被注释掉，自动被startsWith("--")检查跳过。
     */
    private String stripComment(String line) {
        if (line == null) return "";
        // 如果是被保护的annex行，直接返回空（整行都是注释）
        if (line.startsWith(ANNEX_PROTECT_PREFIX)) {
            return "";
        }
        int idx = line.indexOf("--");
        if (idx >= 0) {
            line = line.substring(0, idx);
        }
        return line.trim();
    }

    /**
     * Annex块保护前缀标记。
     * 在解析/修复前，annex {** ... **} 内部的每一行会被此前缀注释掉，
     * 避免被误识别为AADL声明、subcomponent、connection、end语句等。
     * 处理完成后由 restoreAnnexBlocks 移除此前缀恢复原始内容。
     */
    private static final String ANNEX_PROTECT_PREFIX = "-- __ANNEX_PROTECTED__: ";

    /**
     * 预处理AADL内容，将annex块（annex Name {** ... **};）内部的所有行
     * 用注释前缀保护起来，防止被正则表达式错误匹配。
     * 注意：{** 和 **} 所在行保持不变，只保护它们之间的行。
     *
     * @param content 原始AADL内容
     * @return 保护后的AADL内容
     */
    private String protectAnnexBlocks(String content) {
        if (content == null || !content.contains("{**")) {
            return content; // 快速路径：没有annex块
        }
        String[] lines = content.split("\n", -1); // -1保留末尾空行
        StringBuilder sb = new StringBuilder();
        boolean inAnnex = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (!inAnnex) {
                // 检查是否进入annex块：行中包含 {** 但不包含 **}（或**}在{**之前）
                int openIdx = line.indexOf("{**");
                int closeIdx = line.indexOf("**}");
                if (openIdx >= 0 && (closeIdx < 0 || closeIdx < openIdx)) {
                    inAnnex = true;
                    // {** 所在行不保护（可能有前缀如 "annex EMV2 {**"）
                    sb.append(line);
                    // 检查同一行是否立即关闭（罕见情况：{** ... **}; 在同一行）
                    if (closeIdx > openIdx) {
                        inAnnex = false;
                    }
                } else {
                    sb.append(line);
                }
            } else {
                // 在annex块内部，检查是否退出
                int closeIdx = line.indexOf("**}");
                if (closeIdx >= 0) {
                    inAnnex = false;
                    // **} 所在行不保护
                    sb.append(line);
                } else {
                    // 保护annex内部行：用注释前缀标记
                    // 保留原始缩进，在缩进后插入前缀
                    int indentLen = 0;
                    while (indentLen < line.length() && (line.charAt(indentLen) == ' ' || line.charAt(indentLen) == '\t')) {
                        indentLen++;
                    }
                    sb.append(line.substring(0, indentLen));
                    sb.append(ANNEX_PROTECT_PREFIX);
                    sb.append(line.substring(indentLen));
                }
            }

            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 恢复被 protectAnnexBlocks 保护的annex块内容，移除保护前缀。
     *
     * @param content 保护后的AADL内容
     * @return 恢复后的AADL内容
     */
    private String restoreAnnexBlocks(String content) {
        if (content == null || !content.contains(ANNEX_PROTECT_PREFIX)) {
            return content;
        }
        // 逐行移除保护前缀
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 移除保护前缀（前缀前可能有缩进）
            int prefixIdx = line.indexOf(ANNEX_PROTECT_PREFIX);
            if (prefixIdx >= 0) {
                line = line.substring(0, prefixIdx) + line.substring(prefixIdx + ANNEX_PROTECT_PREFIX.length());
            }
            sb.append(line);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /** 判断一行（去除注释后）是否以指定关键字结尾（如 "end Xxx;"） */
    private static boolean matchesEndDecl(String lineNoComment, String keyword, String name) {
        // 匹配 "end keyword name;" 或 "end name;" 模式（去除注释后）
        return lineNoComment.matches("end\\s+(?:\\w+\\s+)?" + Pattern.quote(name) + "\\s*;");
    }

    /** 判断一行（去除注释后）是否匹配 end Xxx.impl; */
    private static boolean matchesEndImpl(String lineNoComment, String name) {
        return lineNoComment.matches("end\\s+\\w+\\.impl\\s*;") ||
               lineNoComment.matches("end\\s+" + Pattern.quote(name) + "\\.impl\\s*;");
    }

    /**
     * 判断给定类型名是否为合法的 AADL 组件类型。
     * 用于过滤架构树中可能出现的非组件节点（如 port、interface 等 feature）。
     */
    private boolean isValidAadlComponentType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return false;
        }
        String normalized = type.trim().toLowerCase().replace('_', ' ');
        return CONTAINMENT_RULES.containsKey(normalized);
    }

    /**
     * 判断是否为纯硬件组件类型（只能有 bus access，不能有 data port）。
     * 纯硬件组件：processor, virtual processor, memory, bus, virtual bus
     * 注意：device 是软硬件交汇点，既可以有 data port 也可以有 bus access，因此不算纯硬件。
     */
    private boolean isHardwareComponentType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return false;
        }
        String normalized = type.trim().toLowerCase().replace('_', ' ');
        return normalized.equals("processor")
                || normalized.equals("virtual processor")
                || normalized.equals("memory")
                || normalized.equals("bus")
                || normalized.equals("virtual bus");
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
        /** 修复建议列表，与 errors 一一对应（相同索引） */
        public List<String> suggestions = new ArrayList<>();
        public String fixedContent;
        /** 需要自动补全的 feature 列表：key = 组件类型名, value = {feature名 → 数据类型} */
        public Map<String, Map<String, String>> missingFeatures = new LinkedHashMap<>();
        public boolean hasIssues() {
            return !errors.isEmpty() || !warnings.isEmpty() || !fixes.isEmpty();
        }
    }

    // ========================= 公共入口 =========================

    /**
     * 纯语法验证（不依赖架构树）。
     * 用于 fix agent 迭代修复场景，只检查 AADL 代码本身的语法正确性，
     * 不对比架构树（不检查幻觉组件、遗漏组件、类型不匹配等需要真值表的检查）。
     *
     * @param aadlContent 待验证的 AADL 代码
     * @return 验证结果（含修正后的代码）
     */
    public ValidationResult validateSyntax(String aadlContent) {
        AadlInputParser.ParseResult emptyResult = new AadlInputParser.ParseResult();
        // archComponents 为空，与架构树对比的检查会自动跳过
        return validate(aadlContent, emptyResult);
    }

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

        // 0. 预处理：保护annex块（EMV2等 {** ... **}），防止内部内容被误解析
        //     annex内部行会被注释掉，避免end behavior;等被误识别为组件end语句
        String protectedContent = protectAnnexBlocks(aadlContent);

        // 1. 从 ParseResult 获取组件真值表（无需重复解析 JSON）
        Map<String, AadlInputParser.ArchNode> archComponents = parseResult.archComponents;
        log.info("使用解析器提供的组件真值表：{} 个组件", archComponents.size());

        // 2. 解析 AADL 声明（使用受保护的内容）
        Map<String, AadlDeclaration> aadlDeclarations = parseAadlDeclarations(protectedContent);
        log.info("AADL 声明解析完成：{} 个组件声明", aadlDeclarations.size());

        // 3. 解析 subcomponents 引用
        List<SubcomponentRef> subcomponentRefs = parseSubcomponentRefs(protectedContent);
        log.info("subcomponents 引用解析完成：{} 条", subcomponentRefs.size());

        // 3b. 解析组件 features（类型声明中的端口/访问点）
        Map<String, Map<String, String>> componentFeatures = parseFeatures(protectedContent);
        log.info("features 解析完成：{} 个组件有 features 声明", componentFeatures.size());

        // 3c. 解析 connections 引用
        List<ConnectionRef> connectionRefs = parseConnections(protectedContent);
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
        checkFeaturesPlacement(protectedContent, result);

        // 4h. 检测 connections 引用悬空 feature（引用了组件中不存在的端口）
        checkConnectionReferences(connectionRefs, componentFeatures, subcomponentRefs, aadlDeclarations, result);

        // 4i. 检测线程 implementation 中非法包含 connections 块
        checkThreadConnectionsBlock(protectedContent, aadlDeclarations, result);

        // 4j. 检测非法语法 requires data port（应为 in/out data port）
        checkIllegalRequiresDataPort(protectedContent, result);

        // 4k. 检测 properties 中 applies to 引用了未声明的子组件实例或连接名
        checkAppliesToReferences(protectedContent, subcomponentRefs, connectionRefs, result);

        // 4ka. 检测 reference 属性值的括号格式（列表型属性应为 (reference (...)) 双括号）
        checkReferenceParentheses(protectedContent, result);

        // 4l. 检测截断/不完整的连接行（缺少分号或端口名）
        checkIncompleteConnections(protectedContent, result);

        // 4m. 检测设备端口类型与数据组件混淆
        checkDevicePortTypeMismatch(protectedContent, aadlDeclarations, result);

        // 4o. 检测 implementation 中 subcomponents → connections → properties 顺序违规
        checkImplementationOrder(protectedContent, result);

        // 4q. 检测 thread 类型声明中的 requires/provides bus access feature
        checkThreadBusAccessFeature(protectedContent, result);

        // 预解析 feature 详情（供后续方向检测、操作符检测、feature 合规检测使用）
        Map<String, Map<String, FeatureDetail>> featureDetails = parseFeatureDetails(protectedContent);
        log.info("featureDetails 解析完成：{} 个组件有 feature 详情", featureDetails.size());

        // 4qa. 检测 feature 类型与组件类型是否匹配（每种组件允许的 feature 类别不同）
        checkFeatureTypeCompliance(aadlDeclarations, featureDetails, result);

        // 4r. 检测连接类型与端点 feature 类型是否匹配（port 连 port，access 连 access）
        Map<String, Map<String, String>> featureTypes = parseFeatureTypes(protectedContent);
        log.info("featureTypes 解析完成：{} 个组件有 feature 类型分类", featureTypes.size());
        checkConnectionTypeMatch(connectionRefs, featureTypes, subcomponentRefs, result);

        // 4t. 检测 data 组件中非法的 features 块（subprogram 可以有 features）
        checkDataComponentFeatures(protectedContent, result);

        // 4u. 检测 port 连接的端口方向（源端必须 out，目标端必须 in；代理连接两端方向相同）
        checkPortDirection(connectionRefs, featureDetails, subcomponentRefs, result);

        // 4u2. 检测 bus access 连接的方向规则
        checkBusAccessDirection(connectionRefs, featureDetails, subcomponentRefs, result);

        // 4s. 检测连接操作符是否正确（port 用 ->，bus access 用 <->；in out 双向端口允许 <->）
        checkConnectionOperator(connectionRefs, featureDetails, subcomponentRefs, result);

        // 4v. 检测 port 连接两端的数据类型一致性
        checkPortDataTypeConsistency(connectionRefs, featureDetails, subcomponentRefs, result);

        // 4w. 检测连接与实体类型的匹配（软件实体只能 port 连接，硬件实体只能 bus access 连接）
        checkConnectionEntityTypeMatch(connectionRefs, aadlDeclarations, result);

        // 4x. 检测属性绑定完整性（process/thread 缺少 Actual_Processor_Binding）
        checkPropertyBindingCompleteness(protectedContent, subcomponentRefs, result);

        // 4y. 检测数据类型一致性深度校验（连接两端 Data_Size 不匹配）
        checkDataSizeConsistency(protectedContent, connectionRefs, featureDetails, subcomponentRefs, result);

        // 4z. 检测命名空间冲突（实例名/连接名/类型名重名）
        checkNamingCollision(protectedContent, subcomponentRefs, connectionRefs, aadlDeclarations, result);

        // 4aa. 检测畸形 end 语句（逗号、多余空格、多个标识符等）
        checkMalformedEndStatements(protectedContent, result);

        // 5. 自动修正（使用受保护的内容，修复完成后恢复annex块）
        if (!result.errors.isEmpty() || hasAutoFixableIssues(aadlDeclarations, archComponents)
                || !result.missingFeatures.isEmpty()) {
            String fixedProtected = applyFixes(protectedContent, aadlDeclarations, archComponents,
                    componentFeatures, subcomponentRefs, connectionRefs, result);
            result.fixedContent = restoreAnnexBlocks(fixedProtected);
        } else {
            // 没有修复，直接恢复原始内容（或保留原始内容）
            result.fixedContent = aadlContent;
        }

        // 6. 为每个错误生成修复建议
        for (String error : result.errors) {
            result.suggestions.add(generateSuggestion(error));
        }

        return result;
    }

    // ========================= 修复建议生成 =========================

    /**
     * 根据错误消息内容，通过模式匹配生成对应的修复建议。
     * 覆盖常见 AADL 语法错误的修复指导。
     *
     * @param errorMessage 错误消息
     * @return 修复建议字符串，无匹配时返回通用建议
     */
    private String generateSuggestion(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return "请检查 AADL 语法规范。";
        }
        String msg = errorMessage.toLowerCase();

        // 端口方向错误
        if (msg.contains("端口方向错误") || msg.contains("数据流方向不匹配")) {
            if (msg.contains("同级连接")) {
                return "同级组件连接必须 out -> in。将源端端口改为 out data port，或将目标端端口改为 in data port。";
            }
            if (msg.contains("向下委派")) {
                return "向下委派必须 in -> in。确保父组件端口和子组件端口都是 in 方向。格式：port parent_in -> Child.data_in;";
            }
            if (msg.contains("向上委派")) {
                return "向上委派必须 out -> out。确保子组件端口和父组件端口都是 out 方向。格式：port Child.data_out -> parent_out;";
            }
            return "检查连接两端的端口方向是否匹配对应连接模式（同级 out->in、向下委派 in->in、向上委派 out->out）。";
        }

        // 总线方向错误
        if (msg.contains("总线方向错误") || msg.contains("bus access 方向不匹配")) {
            if (msg.contains("同级连接")) {
                return "同级 bus access 连接必须 requires <-> provides。确保一端是 requires bus access，另一端是 provides bus access。";
            }
            if (msg.contains("委派") || msg.contains("Delegation")) {
                return "委派 bus access 连接两端方向必须相同。向外提供总线：父 provides <-> 子 provides；向外索取总线：父 requires <-> 子 requires。";
            }
            return "检查 bus access 连接两端的方向：同级连接 requires<->provides，委派连接两端方向相同。";
        }

        // features 放置错误
        if (msg.contains("features") && (msg.contains("implementation") || msg.contains("implementation"))) {
            return "将 features 块从 implementation 声明中移除，放到对应的类型声明（type declaration）中。";
        }

        // data 组件有 features
        if (msg.contains("data") && msg.contains("features")) {
            return "data 组件是纯类型分类器，严禁拥有 features 块。删除 data 组件中的 features 块。";
        }

        // thread 内有 connections
        if (msg.contains("thread") && msg.contains("connections")) {
            return "thread implementation 中严禁出现 connections 块。将连接移到包含该线程的 process 或 system implementation 中。";
        }

        // requires data port 非法
        if (msg.contains("requires data port") || msg.contains("requires") && msg.contains("data port")) {
            return "数据端口只能使用 in/out 方向关键字。将 'requires data port' 改为 'in data port' 或 'out data port'。";
        }

        // thread 有 bus access
        if (msg.contains("thread") && msg.contains("bus access")) {
            return "thread 严禁声明 requires/provides bus access。删除 thread features 中的 bus access 行，总线访问通过 device 桥接。";
        }

        // 缺少分号
        if (msg.contains("缺少分号") || msg.contains("分号结尾")) {
            return "在该行末尾添加分号 ';'。AADL 中每个声明和连接行必须以分号结尾。";
        }

        // 缺少 end 语句
        if (msg.contains("缺少") && msg.contains("end")) {
            return "在组件声明末尾添加 'end 组件名;' 或 'end 组件名.impl;'。类型声明用组件名，实现声明用 组件名.impl。";
        }

        // 缺少 implementation 声明
        if (msg.contains("缺少") && msg.contains("implementation")) {
            return "添加对应的 implementation 声明：组件类型 implementation 组件名.impl ... end 组件名.impl;";
        }

        // 缺少类型声明
        if (msg.contains("缺少") && msg.contains("类型声明")) {
            return "添加对应的类型声明：组件类型 组件名 ... end 组件名;";
        }

        // 悬空引用 - 未声明
        if (msg.contains("未声明") || msg.contains("引用") && msg.contains("不存在")) {
            return "确保 subcomponents 中引用的组件类型名在同一个 package 中有完整的类型声明 + 实现声明。检查拼写是否一致。";
        }

        // 嵌套违规
        if (msg.contains("嵌套") || msg.contains("包含") && msg.contains("不允许") || msg.contains("非法")) {
            return "检查父子组件类型是否合法。thread 不能直接放 system 下（需包装进 process）；process 不能放 processor 下。参考 R9 包含关系表。";
        }

        // 连接引用悬空 feature
        if (msg.contains("feature") && (msg.contains("不存在") || msg.contains("未声明") || msg.contains("找不到"))) {
            return "确保连接引用的 '实例名.端口名' 中的端口名在对应组件类型的 features 块中已声明。检查拼写和组件归属。";
        }

        // 连接类型不匹配
        if (msg.contains("port") && msg.contains("access") && (msg.contains("混连") || msg.contains("不匹配"))) {
            return "port 连接只能连接 data port/event port，bus access 连接只能连接 requires/provides bus access。严禁混连。";
        }

        // 连接操作符错误
        if (msg.contains("操作符") || msg.contains("->") && msg.contains("<->")) {
            return "port 连接用 '->'（双向 in out 端口可用 '<->'），bus access 连接用 '<->'。检查连接操作符是否正确。";
        }

        // 缺少 Actual_Processor_Binding
        if (msg.contains("actual_processor_binding") || msg.contains("处理器绑定") || msg.contains("部署绑定")) {
            return "在包含该 process/thread 的 system implementation 的 properties 块中添加：Actual_Processor_Binding => (reference (处理器实例名)) applies to 实例名;";
        }

        // 数据类型不一致
        if (msg.contains("数据类型") && (msg.contains("不一致") || msg.contains("不匹配"))) {
            return "确保连接两端的 data port 引用相同的数据类型组件。检查两端 features 中声明的类型名是否一致。";
        }

        // reference 缺少外层括号
        if (msg.contains("reference") && msg.contains("外层列表括号")) {
            return "列表类型属性的 reference 值必须使用双括号格式：(reference (目标名))。将 reference (xxx) 改为 (reference (xxx))。";
        }

        // applies to 引用连接名（属于正常情况，此处仅提供更详细说明）
        if (msg.contains("applies to") && msg.contains("subcomponents 或 connections")) {
            return "applies to 可以引用 subcomponents 中的实例名或 connections 中的连接名。确保引用的名称在当前 implementation 作用域内已声明。";
        }

        // Data_Size 不一致
        if (msg.contains("data_size") || msg.contains("数据大小")) {
            return "确保连接两端 data 组件的 Data_Size 属性值一致。修改其中一方的 Data_Size 使其与另一方匹配。";
        }

        // 命名冲突
        if (msg.contains("重名") || msg.contains("命名冲突") || msg.contains("重复")) {
            return "subcomponents 实例名、connections 连接名、组件类型名三者不得重复。实例名冲突追加 _inst，连接名冲突追加 _conn。";
        }

        // 畸形 end 语句
        if (msg.contains("end") && (msg.contains("畸形") || msg.contains("格式错误") || msg.contains("不匹配"))) {
            return "检查 end 语句格式：类型声明用 'end 组件名;'，实现声明用 'end 组件名.impl;'。确保名称与声明完全一致。";
        }

        // 遗漏组件
        if (msg.contains("遗漏") || msg.contains("缺失") && msg.contains("组件")) {
            return "架构树中存在但 AADL 中缺失的组件，需要补充完整的类型声明 + 实现声明。";
        }

        // 幻觉组件
        if (msg.contains("幻觉") || msg.contains("多余") && msg.contains("组件")) {
            return "AADL 中声明了但架构树中不存在的组件。如果确实需要，更新架构树；否则删除多余的组件声明。";
        }

        // applies to 引用未声明
        if (msg.contains("applies to") && (msg.contains("未声明") || msg.contains("不存在"))) {
            return "properties 中 'applies to' 引用的实例名必须在当前 implementation 的 subcomponents 中已声明。检查实例名拼写。";
        }

        // 顺序违规
        if (msg.contains("顺序") || msg.contains("subcomponents") && msg.contains("connections") && msg.contains("properties")) {
            return "implementation 内部必须按 subcomponents → connections → properties 顺序编写。调整块的顺序。";
        }

        // 通用建议
        return "请对照 AADL 核心红线规则 R1-R13 检查并修复此问题。";
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

        for (int i = 0; i < lines.length; i++) {
            // 跳过纯注释行（stripComment 前判断）
            String rawTrimmed = lines[i].trim();
            if (rawTrimmed.startsWith("--")) {
                continue;
            }
            String line = stripComment(lines[i]);
            if (line.isEmpty()) {
                continue;
            }

            // 实现声明（优先匹配，因为 "system implementation" 也包含 "system"）
            Matcher implMatcher = IMPL_DECL_PATTERN.matcher(line);
            if (implMatcher.find()) {
                String type = implMatcher.group(1).replaceAll("\\s+", " ");
                String name = implMatcher.group(2);
                AadlDeclaration decl = declarations.computeIfAbsent(name, k -> new AadlDeclaration());
                decl.name = name;
                decl.type = type;
                decl.hasImplDecl = true;
                decl.implDeclLine = i + 1;
                continue;
            }

            // virtual 实现声明（virtual processor / virtual bus）
            Matcher virtualImplMatcher = VIRTUAL_IMPL_DECL_PATTERN.matcher(line);
            if (virtualImplMatcher.find()) {
                String type = virtualImplMatcher.group(1).replaceAll("\\s+", " ");
                String name = virtualImplMatcher.group(2);
                AadlDeclaration decl = declarations.computeIfAbsent(name, k -> new AadlDeclaration());
                decl.name = name;
                decl.type = type;
                decl.hasImplDecl = true;
                decl.implDeclLine = i + 1;
                continue;
            }

            // 类型声明
            Matcher typeMatcher = TYPE_DECL_PATTERN.matcher(line);
            if (typeMatcher.find()) {
                String type = typeMatcher.group(1).replaceAll("\\s+", " ");
                String name = typeMatcher.group(2);
                AadlDeclaration decl = declarations.computeIfAbsent(name, k -> new AadlDeclaration());
                decl.name = name;
                decl.type = type;
                decl.hasTypeDecl = true;
                decl.typeDeclLine = i + 1;
                continue;
            }

            // virtual 类型声明（virtual processor / virtual bus）
            Matcher virtualTypeMatcher = VIRTUAL_TYPE_DECL_PATTERN.matcher(line);
            if (virtualTypeMatcher.find()) {
                String type = virtualTypeMatcher.group(1).replaceAll("\\s+", " ");
                String name = virtualTypeMatcher.group(2);
                AadlDeclaration decl = declarations.computeIfAbsent(name, k -> new AadlDeclaration());
                decl.name = name;
                decl.type = type;
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
            // 跳过纯注释行
            String rawTrimmed = lines[i].trim();
            if (rawTrimmed.startsWith("--")) {
                continue;
            }
            String line = stripComment(lines[i]);
            if (line.isEmpty()) {
                continue;
            }

            // 跟踪当前 implementation 上下文
            Matcher implMatcher = implContextPattern.matcher(line);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                continue;
            }

            // 检测 end ...impl; 退出 implementation 上下文（stripComment 后匹配，支持尾注释）
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
            // 架构树中存在非法组件类型：不报"遗漏组件"错误，报架构树本身的问题
            if (!isValidAadlComponentType(archComp.type)) {
                result.errors.add(String.format(
                        "架构树非法组件类型: '%s' 的类型 '%s' 不是合法的 AADL 组件类型，" +
                                "可能是将特征(feature)如 port/interface/bus_access 等误当作了组件。" +
                                "合法组件类型：system/process/thread/processor/device/bus/memory/data 等 14 种",
                        archComp.name, archComp.type
                ));
                continue;
            }
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
                "^\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract)\\s+(\\w+)\\s*$"
        );
        // 实现声明模式
        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        // virtual processor 类型声明
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+(\\w+)\\s*$"
        );
        // virtual bus 类型声明
        Pattern virtualBusTypePattern = Pattern.compile(
                "^\\s*virtual\\s+bus\\s+(\\w+)\\s*$"
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
            // 跳过纯注释行
            String rawTrimmed = lines[i].trim();
            if (rawTrimmed.startsWith("--")) {
                continue;
            }
            String line = stripComment(lines[i]);
            if (line.isEmpty()) {
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

            // virtual bus 类型声明
            Matcher virtualBusTypeMatcher = virtualBusTypePattern.matcher(line);
            if (virtualBusTypeMatcher.find()) {
                currentTypeDecl = virtualBusTypeMatcher.group(1);
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

            // end 语句退出当前声明（stripComment 后匹配，支持尾注释）
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
                "^\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract)\\s+(\\w+)\\s*$"
        );
        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+(\\w+)\\s*$"
        );
        Pattern virtualBusTypePattern = Pattern.compile(
                "^\\s*virtual\\s+bus\\s+(\\w+)\\s*$"
        );

        String currentTypeDecl = null;
        boolean inImplementation = false;
        boolean inFeaturesBlock = false;

        for (int i = 0; i < lines.length; i++) {
            // 跳过纯注释行
            String rawTrimmed = lines[i].trim();
            if (rawTrimmed.startsWith("--")) {
                continue;
            }
            String line = stripComment(lines[i]);
            if (line.isEmpty()) {
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

            Matcher virtualBusTypeMatcher = virtualBusTypePattern.matcher(line);
            if (virtualBusTypeMatcher.find()) {
                currentTypeDecl = virtualBusTypeMatcher.group(1);
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
                "^\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract)\\s+(\\w+)\\s*$"
        );
        Pattern implDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+(\\w+)\\s*$"
        );
        Pattern virtualBusTypePattern = Pattern.compile(
                "^\\s*virtual\\s+bus\\s+(\\w+)\\s*$"
        );

        String currentTypeDecl = null;
        boolean inImplementation = false;
        boolean inFeaturesBlock = false;

        for (int i = 0; i < lines.length; i++) {
            // 跳过纯注释行
            String rawTrimmed = lines[i].trim();
            if (rawTrimmed.startsWith("--")) {
                continue;
            }
            String line = stripComment(lines[i]);
            if (line.isEmpty()) {
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

            Matcher virtualBusTypeMatcher = virtualBusTypePattern.matcher(line);
            if (virtualBusTypeMatcher.find()) {
                currentTypeDecl = virtualBusTypeMatcher.group(1);
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
                "^\\s*(?:system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+processor|virtual\\s+bus)\\s+implementation\\s+(\\w+)\\.impl"
        );

        for (int i = 0; i < lines.length; i++) {
            // 跳过纯注释行
            String rawTrimmed = lines[i].trim();
            if (rawTrimmed.startsWith("--")) {
                continue;
            }
            String line = stripComment(lines[i]);
            if (line.isEmpty()) {
                continue;
            }

            // 跟踪 implementation 上下文
            Matcher implMatcher = implContextPattern.matcher(line);
            if (implMatcher.find()) {
                currentImpl = implMatcher.group(1);
                continue;
            }

            // 退出 implementation 上下文（stripComment 后匹配，支持尾注释）
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
     * 4k. 检测 properties 中 applies to 引用了未声明的子组件实例或连接名。
     *
     * AADL 中 applies to 可以引用：
     * 1. 子组件实例名（subcomponent instance）—— 最常见，如 Actual_Processor_Binding 绑定到 process 实例
     * 2. 连接名（connection name）—— 如 Allowed_Connection_Binding 绑定到 port 连接
     *
     * 只要是当前 implementation 的 subcomponents 或 connections 中声明的名称，都是合法的 applies to 目标。
     */
    private void checkAppliesToReferences(String aadlContent,
                                           List<SubcomponentRef> subcomponentRefs,
                                           List<ConnectionRef> connectionRefs,
                                           ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        // 匹配：... applies to Target1, Target2, Target3;（支持逗号分隔的多个目标）
        Pattern appliesToPattern = Pattern.compile(
                "applies\\s+to\\s+([\\w\\s,]+)\\s*;", Pattern.CASE_INSENSITIVE
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

        // 按 parentImpl 分组 connection 名称（applies to 也可以引用连接名）
        Map<String, Set<String>> implConnections = new HashMap<>();
        for (ConnectionRef ref : connectionRefs) {
            if (ref.parentImpl != null && ref.connName != null) {
                implConnections.computeIfAbsent(ref.parentImpl, k -> new HashSet<>())
                        .add(ref.connName);
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
                String targetsStr = m.group(1);
                // 按逗号分割多个目标，逐个校验
                String[] targets = targetsStr.split(",");
                Set<String> instances = implInstances.get(currentImpl);
                Set<String> connections = implConnections.get(currentImpl);

                for (String target : targets) {
                    String targetName = target.trim();
                    if (targetName.isEmpty()) continue;

                    boolean found = (instances != null && instances.contains(targetName))
                                 || (connections != null && connections.contains(targetName));

                    if (!found) {
                        result.errors.add(String.format(
                                "第%d行: 属性引用错误 - 'applies to %s' 引用的名称 '%s' 未在当前 implementation '%s.impl' 的 subcomponents 或 connections 中声明",
                                i + 1, targetsStr.trim(), targetName, currentImpl
                        ));
                    }
                }
            }
        }
    }

    // ========================= reference 括号格式检测 =========================

    /**
     * 4ka. 检测 properties 中 reference 值的括号格式是否正确。
     *
     * AADL 中，列表类型的属性值（如 Actual_Processor_Binding、Allowed_Connection_Binding）
     * 需要使用双括号格式：(reference (TargetName))
     *   - 外层括号表示这是一个列表值（list value）
     *   - 内层括号是 reference 关键字的引用语法
     *
     * 常见错误：只写 reference (TargetName)，缺少外层括号。
     *
     * 自动修复：将 reference (xxx) 替换为 (reference (xxx))
     */
    private void checkReferenceParentheses(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");

        // 匹配：属性值中 reference (xxx) 格式（缺少外层列表括号的情况）
        // 排除已经是 (reference (xxx)) 的正确格式
        Pattern singleParenPattern = Pattern.compile(
                "(?<![(\\s])reference\\s*\\(([^)]+)\\)(?![)\\s])", Pattern.CASE_INSENSITIVE
        );

        // 更精确的模式：在 => 后面跟着 reference (xxx)，且前面没有 (
        // 例如：Actual_Processor_Binding => reference (CPU) applies to proc;
        Pattern missingOuterParenPattern = Pattern.compile(
                "=>\\s*reference\\s*\\((\\w+)\\)", Pattern.CASE_INSENSITIVE
        );

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                continue;
            }

            Matcher m = missingOuterParenPattern.matcher(line);
            if (m.find()) {
                String refTarget = m.group(1);
                result.errors.add(String.format(
                        "第%d行: 属性语法错误 - reference 值缺少外层列表括号; " +
                        "列表类型属性应使用 (reference (%s)) 格式，而非 reference (%s)",
                        i + 1, refTarget, refTarget
                ));

                result.fixes.add(String.format(
                        "第%d行: 已修正 reference 属性格式 - 补充外层列表括号：reference (%s) → (reference (%s))",
                        i + 1, refTarget, refTarget
                ));
            }
        }
    }

    /**
     * 自动修正 0r：修复 reference 属性值括号格式错误。
     *
     * 将属性值中错误的 {@code reference (xxx)} 格式（缺少外层列表括号）
     * 替换为正确的 {@code (reference (xxx))} 格式。
     *
     * 注意：只修复 => 后面未被外层括号包裹的 reference (xxx)，
     * 不会误修改已经是 (reference (xxx)) 的正确格式。
     */
    private String fixReferenceParentheses(String content, ValidationResult result) {
        String[] lines = content.split("\n");
        int fixCount = 0;

        // 匹配 => 后直接跟 reference (xxx) 的情况（前面不能是 (，否则已经是正确格式）
        // 使用 (?>...) 原子组避免回溯导致误匹配
        Pattern missingParenPattern = Pattern.compile(
                "(=>\\s*)(reference\\s*\\(([^)]+)\\))",
                Pattern.CASE_INSENSITIVE
        );

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("--")) {
                continue;
            }

            // 检查是否已经是正确格式 (reference (xxx))：如果 => 后面紧跟 (reference，则跳过
            // 通过检查 => 后面第一个非空白字符是否为 ( 来判断
            Matcher alreadyCorrect = Pattern.compile(
                    "=>\\s*\\(\\s*reference\\s*\\(", Pattern.CASE_INSENSITIVE
            ).matcher(line);
            if (alreadyCorrect.find()) {
                continue;
            }

            Matcher m = missingParenPattern.matcher(line);
            StringBuffer sb = new StringBuffer();
            boolean found = false;
            while (m.find()) {
                found = true;
                String arrow = m.group(1);  // => 加上空白
                String refExpr = m.group(2); // reference (xxx)
                String refTarget = m.group(3); // xxx
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        arrow + "(" + refExpr + ")"
                ));
                fixCount++;
                result.fixes.add(String.format(
                        "已修正 reference 括号: reference (%s) → (reference (%s))",
                        refTarget, refTarget
                ));
                log.info("自动修正：reference 括号补全 reference ({}) → (reference ({}))", refTarget, refTarget);
            }
            if (found) {
                m.appendTail(sb);
                lines[i] = sb.toString();
            }
        }

        if (fixCount > 0) {
            log.info("自动修正：共修复 {} 处 reference 括号格式错误", fixCount);
        }
        return String.join("\n", lines);
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
     * AADL 端口方向委派规则（三种合法连接模式）：
     *
     * 1. 同级组件连接 (Peer-to-Peer)：
     *    - 规则：out -> in
     *    - 语义：数据从源组件的输出端口流出，流入目标组件的输入端口
     *    - 示例：conn1 : port Thread_A.data_out -> Thread_B.data_in;
     *    - 严禁：in -> in、out -> out（同级组件之间绝对不能出现）
     *
     * 2. 向下委派 (Delegation Down)：
     *    - 规则：in -> in
     *    - 语义：父容器的输入端口（外部数据的源头）指向子组件的输入端口（数据的终点）
     *    - 示例：conn2 : port parent_in_port -> Thread_A.data_in;
     *    - 源端是父组件端口（无实例名前缀），目标端是子组件端口
     *
     * 3. 向上委派 (Delegation Up)：
     *    - 规则：out -> out
     *    - 语义：子组件的输出端口（内部数据的源头）指向父容器的输出端口（向外发送的出口）
     *    - 示例：conn3 : port Thread_B.data_out -> parent_out_port;
     *    - 源端是子组件端口，目标端是父组件端口（无实例名前缀）
     *
     * 特殊情况：in out 双向端口
     *    - 同级连接中，in out 可作为源端（等同 out）或目标端（等同 in）
     *    - 委派连接中，in out 父端口只能委派给 in out 子端口（双向委派）
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
            boolean sourceIsParent = false;
            boolean destIsParent = false;

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
                sourceIsParent = true;
                Map<String, FeatureDetail> parentFeatures = featureDetails.get(parentTypeName);
                if (parentFeatures != null) {
                    FeatureDetail fd = parentFeatures.get(conn.sourceFeature);
                    if (fd != null) {
                        sourceDir = fd.direction;
                    }
                }
                sourceDesc = conn.sourceFeature + "(父端口)";
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
                destIsParent = true;
                Map<String, FeatureDetail> parentFeatures = featureDetails.get(parentTypeName);
                if (parentFeatures != null) {
                    FeatureDetail fd = parentFeatures.get(conn.destFeature);
                    if (fd != null) {
                        destDir = fd.direction;
                    }
                }
                destDesc = conn.destFeature + "(父端口)";
            }

            // 无法确定方向时跳过
            if (sourceDir == null || destDir == null) {
                continue;
            }

            // "requires"/"provides" 不适用 port 方向，视为未知跳过
            boolean sourceIsPort = "in".equals(sourceDir) || "out".equals(sourceDir) || "in out".equals(sourceDir);
            boolean destIsPort = "in".equals(destDir) || "out".equals(destDir) || "in out".equals(destDir);
            if (!sourceIsPort || !destIsPort) {
                continue;
            }

            boolean directionOk;
            String connTypeDesc;
            String expectedRule;

            if (sourceIsParent && !destIsParent) {
                // 向下委派 (Delegation Down): 父 in -> 子 in
                // 父组件端口是源端（左侧），子组件端口是目标端（右侧）
                connTypeDesc = "向下委派(Delegation Down)";
                if ("in out".equals(sourceDir) && "in out".equals(destDir)) {
                    // 双向委派：父 in out -> 子 in out
                    directionOk = true;
                    expectedRule = "向下委派：父端 in out -> 子端 in out（双向委派，合法）";
                } else if ("in".equals(sourceDir) && "in".equals(destDir)) {
                    directionOk = true;
                    expectedRule = "向下委派：父端 in -> 子端 in（合法）";
                } else {
                    directionOk = false;
                    expectedRule = "向下委派规则：父端 in -> 子端 in（方向必须相同且为 in）；" +
                            "当前源端(父)=" + sourceDir + "，目标端(子)=" + destDir;
                }
            } else if (!sourceIsParent && destIsParent) {
                // 向上委派 (Delegation Up): 子 out -> 父 out
                // 子组件端口是源端（左侧），父组件端口是目标端（右侧）
                connTypeDesc = "向上委派(Delegation Up)";
                if ("in out".equals(sourceDir) && "in out".equals(destDir)) {
                    // 双向委派：子 in out -> 父 in out
                    directionOk = true;
                    expectedRule = "向上委派：子端 in out -> 父端 in out（双向委派，合法）";
                } else if ("out".equals(sourceDir) && "out".equals(destDir)) {
                    directionOk = true;
                    expectedRule = "向上委派：子端 out -> 父端 out（合法）";
                } else {
                    directionOk = false;
                    expectedRule = "向上委派规则：子端 out -> 父端 out（方向必须相同且为 out）；" +
                            "当前源端(子)=" + sourceDir + "，目标端(父)=" + destDir;
                }
            } else if (sourceIsParent && destIsParent) {
                // 双端代理（两端都是父端口，少见但合法）：方向必须相同
                connTypeDesc = "双端代理";
                directionOk = sourceDir.equals(destDir);
                expectedRule = "双端代理：两端方向必须相同（in->in 或 out->out）";
            } else {
                // 同级组件连接 (Peer-to-Peer): out -> in
                // 两端都是子组件实例
                connTypeDesc = "同级连接(Peer-to-Peer)";
                // in out 双向端口可作为源端（等同 out）或目标端（等同 in）
                boolean sourceIsOut = "out".equals(sourceDir) || "in out".equals(sourceDir);
                boolean destIsIn = "in".equals(destDir) || "in out".equals(destDir);
                directionOk = sourceIsOut && destIsIn;
                if (!directionOk) {
                    // 生成更具体的错误信息
                    if ("in".equals(sourceDir) && "in".equals(destDir)) {
                        expectedRule = "同级连接严禁 in -> in；正确规则为 out -> in（数据从源组件输出端口流向目标组件输入端口）";
                    } else if ("out".equals(sourceDir) && "out".equals(destDir)) {
                        expectedRule = "同级连接严禁 out -> out；正确规则为 out -> in（数据从源组件输出端口流向目标组件输入端口）";
                    } else if ("in".equals(sourceDir) && "out".equals(destDir)) {
                        expectedRule = "同级连接严禁 in -> out（方向完全反了）；正确规则为 out -> in（数据从源组件输出端口流向目标组件输入端口）";
                    } else {
                        expectedRule = "同级连接规则：源端 out -> 目标端 in（in out 双向端口可作为源端或目标端）；" +
                                "严禁 in -> in、out -> out、in -> out";
                    }
                } else {
                    expectedRule = "同级连接：源端 out -> 目标端 in（合法）";
                }
            }

            if (!directionOk) {
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
     * 4u2. 检测 bus access 连接的方向规则。
     *
     * AADL 标准规范：
     * - bus access 连接必须使用双向操作符 <->
     *
     * A. 同级组件连接 (Peer-to-Peer)
     * 规则：必须是 requires <-> provides（一端索取，一端提供）
     * 场景：同一个系统内的设备挂载到总线上
     * 示例：conn_1 : bus access CANController.canIn <-> CanBus_1.busOut;
     *
     * B. 跨级委派连接 (Delegation)
     * 场景 1（向外提供总线）：父容器 provides <-> 内部子组件 provides
     *   示例：conn_delegate_1 : bus access line1 <-> line1_bus.busOut;
     * 场景 2（向外索取总线）：父容器 requires <-> 内部子组件 requires
     *   示例：conn_delegate_2 : bus access parent_req_bus <-> CPU.bus_if;
     *
     * @param connections    连接列表
     * @param featureDetails feature 详情（含方向信息）
     * @param subcomponentRefs 子组件引用
     * @param result         验证结果
     */
    private void checkBusAccessDirection(List<ConnectionRef> connections,
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
            // 只检查 bus access 连接
            if (!"bus access".equals(conn.connType) || conn.parentImpl == null) {
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
            boolean sourceIsParent = false;
            boolean destIsParent = false;

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
                // 源端是父组件 feature（代理连接）
                sourceIsParent = true;
                Map<String, FeatureDetail> parentFeatures = featureDetails.get(parentTypeName);
                if (parentFeatures != null) {
                    FeatureDetail fd = parentFeatures.get(conn.sourceFeature);
                    if (fd != null) {
                        sourceDir = fd.direction;
                    }
                }
                sourceDesc = conn.sourceFeature + "(父)";
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
                // 目标端是父组件 feature（代理连接）
                destIsParent = true;
                Map<String, FeatureDetail> parentFeatures = featureDetails.get(parentTypeName);
                if (parentFeatures != null) {
                    FeatureDetail fd = parentFeatures.get(conn.destFeature);
                    if (fd != null) {
                        destDir = fd.direction;
                    }
                }
                destDesc = conn.destFeature + "(父)";
            }

            // 无法确定方向时跳过
            if (sourceDir == null || destDir == null) {
                continue;
            }

            // 只处理 requires/provides 方向的 bus access
            boolean sourceIsBusAccess = "requires".equals(sourceDir) || "provides".equals(sourceDir);
            boolean destIsBusAccess = "requires".equals(destDir) || "provides".equals(destDir);
            if (!sourceIsBusAccess || !destIsBusAccess) {
                continue;
            }

            boolean directionOk;
            String connTypeDesc;
            String expectedRule;

            if (sourceIsParent && !destIsParent) {
                // 委派连接：父 -> 子（父 feature 在左侧，子 feature 在右侧）
                // 向外提供总线：父 provides <-> 子 provides
                // 向外索取总线：父 requires <-> 子 requires
                connTypeDesc = "跨级委派(Delegation)";
                if (sourceDir.equals(destDir)) {
                    directionOk = true;
                    expectedRule = "委派连接：父端 " + sourceDir + " <-> 子端 " + destDir + "（方向相同，合法）";
                } else {
                    directionOk = false;
                    expectedRule = "委派连接规则：父端与子端方向必须相同（provides<->provides 或 requires<->requires）；" +
                            "当前父端=" + sourceDir + "，子端=" + destDir;
                }
            } else if (!sourceIsParent && destIsParent) {
                // 委派连接：子 -> 父（子 feature 在左侧，父 feature 在右侧）
                // 向外提供总线：子 provides <-> 父 provides
                // 向外索取总线：子 requires <-> 父 requires
                connTypeDesc = "跨级委派(Delegation)";
                if (sourceDir.equals(destDir)) {
                    directionOk = true;
                    expectedRule = "委派连接：子端 " + sourceDir + " <-> 父端 " + destDir + "（方向相同，合法）";
                } else {
                    directionOk = false;
                    expectedRule = "委派连接规则：子端与父端方向必须相同（provides<->provides 或 requires<->requires）；" +
                            "当前子端=" + sourceDir + "，父端=" + destDir;
                }
            } else if (sourceIsParent && destIsParent) {
                // 双端代理（两端都是父 feature，少见但合法）：方向必须相同
                connTypeDesc = "双端代理";
                directionOk = sourceDir.equals(destDir);
                expectedRule = "双端代理：两端方向必须相同（provides<->provides 或 requires<->requires）";
            } else {
                // 同级组件连接 (Peer-to-Peer): requires <-> provides
                // 两端都是子组件实例
                connTypeDesc = "同级连接(Peer-to-Peer)";
                boolean oneRequiresOneProvides =
                        ("requires".equals(sourceDir) && "provides".equals(destDir)) ||
                        ("provides".equals(sourceDir) && "requires".equals(destDir));
                directionOk = oneRequiresOneProvides;
                if (!directionOk) {
                    if ("requires".equals(sourceDir) && "requires".equals(destDir)) {
                        expectedRule = "同级连接严禁 requires <-> requires；正确规则为 requires <-> provides（一端索取，一端提供）";
                    } else if ("provides".equals(sourceDir) && "provides".equals(destDir)) {
                        expectedRule = "同级连接严禁 provides <-> provides；正确规则为 requires <-> provides（一端索取，一端提供）";
                    } else {
                        expectedRule = "同级连接规则：requires <-> provides（一端索取总线，一端提供总线）；" +
                                "严禁 requires<->requires、provides<->provides";
                    }
                } else {
                    expectedRule = "同级连接：" + sourceDir + " <-> " + destDir + "（合法）";
                }
            }

            if (!directionOk) {
                result.errors.add(String.format(
                        "第%d行: 总线方向错误 - %s '%s' 的 bus access 方向不匹配; " +
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
                                        "已将 process '%s' 从 processor '%s' 提取到 system '%s' 中，并补充 Actual_Processor_Binding => (reference (%s))",
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
                                "      Actual_Processor_Binding => (reference (%s)) applies to %s;",
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
                                "      Actual_Processor_Binding => (reference (%s)) applies to %s;",
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
     * - port 连接使用了 <-> → 替换为 ->（但双向端口 in out 之间的连接保留 <->）
     * - bus access 连接使用了 -> → 替换为 <->
     *
     * 双向端口判断：从 features 声明中查找两端 feature 的方向，如果都是 "in out" 则保留 <->。
     * 保守策略：如果无法确定任一端方向，则不改（保留 <->）。
     */
    private String fixConnectionOperator(String content, ValidationResult result) {
        String[] lines = content.split("\n");

        // ===== 第一遍：预解析 feature 方向（含 in out 双向端口）和 subcomponent 实例映射 =====
        Map<String, Map<String, String>> featureDirections = parseFeatureDirectionsWithInOut(content);
        Map<String, Map<String, String>> implInstanceMap = parseImplInstanceMap(content);

        List<String> resultLines = new ArrayList<>();
        int fixCount = 0;

        Pattern connLinePattern = Pattern.compile(
                "^(\\s*)(\\w+)\\s*:\\s*(port|bus\\s+access)\\s+(\\w+)\\.(\\w+)\\s*(->|<->)\\s*(\\w+)\\.(\\w+)(.*)"
        );

        boolean inConnections = false;
        String currentImpl = null;
        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract|virtual\\s+processor|virtual\\s+bus)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern endImplPattern = Pattern.compile("^\\s*end\\s+\\w+\\.impl\\s*;");

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

            if (endImplPattern.matcher(trimmed).find()) {
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
                    trimmed.equals("features") || trimmed.equals("flows") ||
                    trimmed.startsWith("annex") || endImplPattern.matcher(trimmed).find())) {
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
                    String srcInstance = m.group(4);
                    String srcFeature = m.group(5);
                    String operator = m.group(6);
                    String dstInstance = m.group(7);
                    String dstFeature = m.group(8);
                    String rest = m.group(9);
                    String fixedOperator = operator;
                    String fixReason = null;

                    // bus access 连接必须用 <->
                    if ("bus access".equals(connType) && "->".equals(operator)) {
                        fixedOperator = "<->";
                        fixReason = "bus access 连接应使用双向 <->";
                    }

                    // port 连接使用了 <->：检查是否为双向端口（in out）连接
                    if ("port".equals(connType) && "<->".equals(operator)) {
                        boolean isBidirectional = false;
                        boolean canDetermine = false;

                        if (currentImpl != null) {
                            Map<String, String> instanceMap = implInstanceMap.get(currentImpl);
                            if (instanceMap != null) {
                                String srcType = instanceMap.get(srcInstance);
                                String dstType = instanceMap.get(dstInstance);
                                if (srcType != null && dstType != null) {
                                    Map<String, String> srcFeats = featureDirections.get(srcType);
                                    Map<String, String> dstFeats = featureDirections.get(dstType);
                                    if (srcFeats != null && dstFeats != null) {
                                        String srcDir = srcFeats.get(srcFeature);
                                        String dstDir = dstFeats.get(dstFeature);
                                        if (srcDir != null && dstDir != null) {
                                            canDetermine = true;
                                            if ("in out".equals(srcDir) && "in out".equals(dstDir)) {
                                                isBidirectional = true;
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (canDetermine && isBidirectional) {
                            // 两端都是 in out 双向端口，保留 <->
                            fixReason = null;
                        } else if (canDetermine && !isBidirectional) {
                            // 能确定至少有一端不是 in out，改为 ->
                            fixedOperator = "->";
                            fixReason = "port 连接应使用单向 ->（非双向端口）";
                        } else {
                            // 保守策略：无法确定方向，不改
                            fixReason = null;
                            log.debug("无法确定连接 '{}' 两端端口方向，保守保留 <->", connName);
                        }
                    }

                    if (fixReason != null) {
                        String fixedLine = indent + connName + " : " + connType + " " +
                                srcInstance + "." + srcFeature + " " + fixedOperator + " " +
                                dstInstance + "." + dstFeature + rest;
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
     * 解析所有组件类型声明中 features 的端口方向（含 in out 双向端口）。
     *
     * @return 组件类型名 → (feature名 → 方向["in"|"out"|"in out"])
     */
    private Map<String, Map<String, String>> parseFeatureDirectionsWithInOut(String aadlContent) {
        Map<String, Map<String, String>> result = new HashMap<>();
        String[] lines = aadlContent.split("\n");

        // 匹配组件类型声明（非 implementation，含 virtual processor/bus）
        Pattern typeDeclPattern = Pattern.compile(
                "^\\s*(?:virtual\\s+processor|virtual\\s+bus|system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+(\\w+)\\s*(?:--.*)?$"
        );
        // 匹配 feature 行：先匹配 in out（放在 in/out 前面避免被 in 抢先匹配）
        Pattern featurePattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(in\\s+out|in|out)\\s+(?:data\\s+port|event\\s+data\\s+port|event\\s+port|port)",
                Pattern.CASE_INSENSITIVE
        );

        String currentType = null;
        boolean inFeatures = false;

        for (String line : lines) {
            String trimmed = line.trim();
            // 去除行尾注释后再匹配
            String stripped = stripComment(trimmed);

            if (stripped.startsWith("--") || stripped.isEmpty()) continue;

            Matcher typeM = typeDeclPattern.matcher(stripped);
            if (typeM.find()) {
                currentType = typeM.group(1);
                inFeatures = false;
                continue;
            }

            if (currentType != null && stripped.matches("end\\s+" + Pattern.quote(currentType) + "\\s*;")) {
                currentType = null;
                inFeatures = false;
                continue;
            }

            if (currentType != null && stripped.toLowerCase().contains("implementation")) {
                currentType = null;
                inFeatures = false;
                continue;
            }

            if (currentType != null && stripped.equals("features")) {
                inFeatures = true;
                continue;
            }

            if (inFeatures && (stripped.equals("properties") || stripped.equals("flows") ||
                    stripped.equals("connections") || stripped.equals("subcomponents") ||
                    stripped.startsWith("annex") || stripped.startsWith("end "))) {
                inFeatures = false;
                continue;
            }

            if (inFeatures) {
                Matcher fM = featurePattern.matcher(stripped);
                if (fM.find()) {
                    String dir = fM.group(2).toLowerCase().replaceAll("\\s+", " ");
                    result.computeIfAbsent(currentType, k -> new HashMap<>())
                            .put(fM.group(1), dir);
                }
            }
        }

        return result;
    }

    /**
     * 解析所有 implementation 中 subcomponents 的实例名 → 类型名映射。
     *
     * @return impl名称 → (实例名 → 类型名)
     */
    private Map<String, Map<String, String>> parseImplInstanceMap(String aadlContent) {
        Map<String, Map<String, String>> implInstanceMap = new HashMap<>();
        String[] lines = aadlContent.split("\n");

        Pattern implContextPattern = Pattern.compile(
                "^\\s*(?:system|process|thread|processor|memory|device|bus|data|subprogram|abstract|virtual\\s+processor|virtual\\s+bus)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern endImplPattern = Pattern.compile("^\\s*end\\s+\\w+\\.impl\\s*;");
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(?:system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+processor|virtual\\s+bus)\\s+(\\w+)\\.impl\\s*;"
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

            if (currentImpl != null && endImplPattern.matcher(trimmed).find()) {
                currentImpl = null;
                continue;
            }

            // 在当前 implementation 内查找 subcomponent 声明（不依赖块顺序，更鲁棒）
            if (currentImpl != null) {
                Matcher subM = subcompPattern.matcher(trimmed);
                if (subM.find()) {
                    implInstanceMap.computeIfAbsent(currentImpl, k -> new HashMap<>())
                            .put(subM.group(1), subM.group(2));
                }
            }
        }

        return implInstanceMap;
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
            // end 语句总是归到 otherLines（避免被错误归类到某个 section 中）
            if (trimmed.matches("end\\s+\\w+\\.impl\\s*;")) {
                otherLines.add(line);
                currentSection = null; // 重置，end之后不再属于任何section
            } else if (currentSection == null) {
                otherLines.add(line); // 声明行、注释等
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
        // 也匹配 virtual processor TypeName，支持行尾注释
        Pattern typeDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+" + Pattern.quote(typeName) + "\\s*(?:--.*)?$"
        );
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+" + Pattern.quote(typeName) + "\\s*(?:--.*)?$"
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
            String trimmed = stripComment(lines[i].trim());

            Matcher typeMatcher = typeDeclPattern.matcher(trimmed);
            Matcher virtualMatcher = virtualTypePattern.matcher(trimmed);
            if (typeMatcher.find() || virtualMatcher.find()) {
                typeDeclLineIdx = i;
                // 向下搜索是否已有 features 块
                for (int j = i + 1; j < lines.length; j++) {
                    String t = stripComment(lines[j].trim());
                    if (t.equals("features")) {
                        featuresLineIdx = j;
                        // 找 features 块的结束位置
                        for (int k = j + 1; k < lines.length; k++) {
                            String tk = stripComment(lines[k].trim());
                            if (blockEndPattern.matcher(tk).find() || tk.matches("end\\s+\\w+\\s*;")) {
                                featuresEndIdx = k;
                                break;
                            }
                        }
                        break;
                    }
                    // 遇到 end TypeName; 说明类型声明中无 features 块
                    if (t.matches("end\\s+" + Pattern.quote(typeName) + "\\s*;")) {
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
                sb.append("\n    features");
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
                "^\\s*(?:system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract)\\s+implementation\\s+(\\w+)\\.impl"
        );
        Pattern virtualImplPattern = Pattern.compile(
                "^\\s*virtual\\s+(?:processor|bus)\\s+implementation\\s+(\\w+)\\.impl"
        );
        // 匹配单个 applies to 目标（支持多个目标用逗号分隔的情况，这里只取第一个来判断）
        Pattern appliesToPattern = Pattern.compile(
                "applies\\s+to\\s+(.+?)\\s*;", Pattern.CASE_INSENSITIVE
        );
        // 匹配 subcomponent 声明行：instanceName : keyword TypeName.impl;
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(system|process|thread(?:\\s+group)?|processor|memory|device|bus|data|subprogram(?:\\s+group)?|abstract|virtual\\s+(?:processor|bus))\\s+(\\w+)\\.impl\\s*;"
        );
        // 匹配 connection 声明行：connectionName : port/access ...
        Pattern connectionPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(?:port|access|bus\\s+access|data\\s+port|event\\s+port|event\\s+data\\s+port)\\s+"
        );

        // implName -> (instanceName -> typeName)  按implementation分组记录需要补充的实例
        Map<String, Map<String, String>> toAddByImpl = new LinkedHashMap<>();
        // implName -> 最后一个subcomponent行号
        Map<String, Integer> lastSubcompLineByImpl = new LinkedHashMap<>();
        // implName -> 是否有subcomponents块
        Map<String, Boolean> hasSubcompBlockByImpl = new LinkedHashMap<>();

        String currentImpl = null;
        Set<String> currentInstances = new LinkedHashSet<>();
        Set<String> currentConnections = new LinkedHashSet<>();
        int lastSubcompLine = -1;
        boolean hasSubcompBlock = false;

        // 第一遍：收集每个implementation的现有实例、连接，以及需要补充的实例
        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String trimmed = stripComment(rawLine).trim();

            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }

            // 进入 implementation
            Matcher implMatcher = implContextPattern.matcher(trimmed);
            Matcher virtualImplMatcher = virtualImplPattern.matcher(trimmed);
            boolean isRegularImpl = implMatcher.find();
            boolean isVirtualImpl = virtualImplMatcher.find();
            if (isRegularImpl || isVirtualImpl) {
                // 保存前一个implementation的信息
                if (currentImpl != null) {
                    lastSubcompLineByImpl.put(currentImpl, lastSubcompLine);
                    hasSubcompBlockByImpl.put(currentImpl, hasSubcompBlock);
                }
                currentImpl = isRegularImpl ? implMatcher.group(1) : virtualImplMatcher.group(1);
                currentInstances.clear();
                currentConnections.clear();
                lastSubcompLine = -1;
                hasSubcompBlock = false;
                continue;
            }

            // 结束 implementation
            if (currentImpl != null && trimmed.matches("end\\s+[\\w.]+\\.impl\\s*;")) {
                lastSubcompLineByImpl.put(currentImpl, lastSubcompLine);
                hasSubcompBlockByImpl.put(currentImpl, hasSubcompBlock);
                currentImpl = null;
                continue;
            }

            if (currentImpl == null) {
                continue;
            }

            // 检测 subcomponents 块开始
            if (trimmed.equalsIgnoreCase("subcomponents")) {
                hasSubcompBlock = true;
                continue;
            }
            // connections/properties 块开始后停止记录 subcomponent
            if (trimmed.equalsIgnoreCase("connections") || trimmed.equalsIgnoreCase("properties")) {
                continue;
            }

            // 记录 subcomponent 实例
            Matcher subcompMatcher = subcompPattern.matcher(trimmed);
            if (subcompMatcher.find()) {
                currentInstances.add(subcompMatcher.group(1));
                lastSubcompLine = i;
                continue;
            }

            // 记录 connection 名
            Matcher connMatcher = connectionPattern.matcher(trimmed);
            if (connMatcher.find()) {
                currentConnections.add(connMatcher.group(1));
                continue;
            }

            // 检查 applies to 引用
            Matcher appliesMatcher = appliesToPattern.matcher(trimmed);
            if (appliesMatcher.find()) {
                String targetsStr = appliesMatcher.group(1);
                // 按逗号分割多个目标
                String[] targets = targetsStr.split(",");
                for (String target : targets) {
                    String targetName = target.trim();
                    // 跳过空值和关键字
                    if (targetName.isEmpty() || targetName.equalsIgnoreCase("all")) {
                        continue;
                    }
                    // 检查是否是已存在的实例或连接
                    if (currentInstances.contains(targetName) || currentConnections.contains(targetName)) {
                        continue;
                    }
                    // 检查这个名字是否是已声明的类型名（如果是，需要添加一个该类型的实例）
                    AadlDeclaration typeDecl = declarations.get(targetName);
                    if (typeDecl != null && typeDecl.hasTypeDecl) {
                        // 这个名字是一个类型名，可能LLM想引用该类型的实例但忘记声明实例了
                        // 生成一个实例名（类型名首字母小写 + _inst 后缀）
                        String instanceName = generateInstanceName(typeDecl.type, targetName, currentInstances);
                        Map<String, String> implToAdd = toAddByImpl.computeIfAbsent(currentImpl, k -> new LinkedHashMap<>());
                        implToAdd.put(instanceName, targetName);
                        result.fixes.add(String.format(
                                "已为 implementation '%s.impl' 补充缺失的子组件实例: %s (类型: %s, applies to 原始目标: %s)",
                                currentImpl, instanceName, targetName, targetName));
                        currentInstances.add(instanceName); // 避免重复添加
                    }
                    // 如果不是类型名，我们无法安全推断，保留警告不自动修复
                }
            }
        }

        if (toAddByImpl.isEmpty()) {
            return content;
        }

        // 第二遍：在对应implementation的subcomponents块中插入缺失的实例
        currentImpl = null;
        List<String> outputLines = new ArrayList<>();
        int fixCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String trimmed = stripComment(rawLine).trim();

            // 进入 implementation
            Matcher implMatcher = implContextPattern.matcher(trimmed);
            Matcher virtualImplMatcher = virtualImplPattern.matcher(trimmed);
            boolean isRegularImpl2 = implMatcher.find();
            boolean isVirtualImpl2 = virtualImplMatcher.find();
            if (isRegularImpl2 || isVirtualImpl2) {
                currentImpl = isRegularImpl2 ? implMatcher.group(1) : virtualImplMatcher.group(1);
                outputLines.add(rawLine);
                continue;
            }

            // 检查是否需要在当前位置插入（在最后一个subcomponent行之后）
            boolean inserted = false;
            if (currentImpl != null && toAddByImpl.containsKey(currentImpl)) {
                Integer lastLine = lastSubcompLineByImpl.get(currentImpl);
                if (lastLine != null && i == lastLine + 1) {
                    // 刚过完最后一个subcomponent，插入新实例
                    Map<String, String> instancesToAdd = toAddByImpl.get(currentImpl);
                    for (Map.Entry<String, String> entry : instancesToAdd.entrySet()) {
                        String instanceName = entry.getKey();
                        String typeName = entry.getValue();
                        AadlDeclaration decl = declarations.get(typeName);
                        String compType = (decl != null) ? decl.type : "process";
                        outputLines.add(String.format("    -- [自动修正] 补充 applies to 目标的子组件实例: %s (类型: %s)", instanceName, typeName));
                        outputLines.add(String.format("    %s : %s %s.impl;", instanceName, compType, typeName));
                        fixCount++;
                    }
                    toAddByImpl.remove(currentImpl);
                    inserted = true;
                }

                // 如果没有subcomponents块，在connections/properties之前或end之前插入
                Boolean hasBlock = hasSubcompBlockByImpl.get(currentImpl);
                if ((!hasBlock || lastLine == null || lastLine < 0)
                        && (trimmed.equalsIgnoreCase("connections")
                            || trimmed.equalsIgnoreCase("properties")
                            || trimmed.matches("end\\s+[\\w.]+\\.impl\\s*;"))) {
                    Map<String, String> instancesToAdd = toAddByImpl.get(currentImpl);
                    if (instancesToAdd != null && !instancesToAdd.isEmpty()) {
                        outputLines.add("  subcomponents");
                        for (Map.Entry<String, String> entry : instancesToAdd.entrySet()) {
                            String instanceName = entry.getKey();
                            String typeName = entry.getValue();
                            AadlDeclaration decl = declarations.get(typeName);
                            String compType = (decl != null) ? decl.type : "process";
                            outputLines.add(String.format("    -- [自动修正] 补充 applies to 目标的子组件实例: %s (类型: %s)", instanceName, typeName));
                            outputLines.add(String.format("    %s : %s %s.impl;", instanceName, compType, typeName));
                            fixCount++;
                        }
                        toAddByImpl.remove(currentImpl);
                        inserted = true;
                    }
                }
            }

            outputLines.add(rawLine);

            // 结束 implementation
            if (currentImpl != null && trimmed.matches("end\\s+[\\w.]+\\.impl\\s*;")) {
                currentImpl = null;
            }
        }

        log.info("自动修正：共补充 {} 个 applies to 缺失的子组件实例", fixCount);
        return String.join("\n", outputLines);
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

        // 收集需要自动重命名的实例（实例名=类型名的情况）
        // key=原实例名, value=新实例名
        Map<String, String> autoRenames = new LinkedHashMap<>();

        for (SubcomponentRef ref : subcomponentRefs) {
            if (ref.parentImpl == null) continue;
            Set<String> names = implInstances.computeIfAbsent(ref.parentImpl, k -> new LinkedHashSet<>());

            // 规则0：实例名不能与其引用的组件类型名相同（R13强制规则）
            // 例如：MainProcessor : processor MainProcessor.impl; → 应改为 main_cpu : processor MainProcessor.impl;
            if (ref.typeName != null && ref.instanceName.equals(ref.typeName)) {
                String newInstanceName = generateInstanceName(ref.componentKeyword, ref.typeName, autoRenames.values());
                autoRenames.put(ref.instanceName, newInstanceName);
                result.errors.add(String.format(
                        "第%d行: 命名空间冲突 - 实例名 '%s' 与其引用的组件类型名 '%s' 完全相同（违反R13强制规则）; " +
                        "实例名必须与类型名区分，建议改为 '%s'（已自动修正）",
                        ref.lineNumber, ref.instanceName, ref.typeName, newInstanceName
                ));
            }

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

        // 执行自动重命名：如果有实例名=类型名的情况，全局替换
        if (!autoRenames.isEmpty() && result.fixedContent == null) {
            String fixed = applyInstanceRenames(aadlContent, autoRenames);
            result.fixedContent = fixed;
            for (Map.Entry<String, String> e : autoRenames.entrySet()) {
                result.fixes.add(String.format(
                        "实例名自动重命名：'%s' → '%s'（避免实例名与类型名相同，违反R13）",
                        e.getKey(), e.getValue()
                ));
            }
            // 更新 subcomponentRefs 中的实例名（后续验证使用修正后的名称）
            for (SubcomponentRef ref : subcomponentRefs) {
                if (autoRenames.containsKey(ref.instanceName)) {
                    ref.instanceName = autoRenames.get(ref.instanceName);
                }
            }
            // 更新 connectionRefs 中的实例引用
            for (ConnectionRef conn : connectionRefs) {
                if (conn.sourceInstance != null && autoRenames.containsKey(conn.sourceInstance)) {
                    conn.sourceInstance = autoRenames.get(conn.sourceInstance);
                }
                if (conn.destInstance != null && autoRenames.containsKey(conn.destInstance)) {
                    conn.destInstance = autoRenames.get(conn.destInstance);
                }
            }
            // 重新构建 implInstances（因为名称变了）
            implInstances.clear();
            for (SubcomponentRef ref : subcomponentRefs) {
                if (ref.parentImpl != null) {
                    implInstances.computeIfAbsent(ref.parentImpl, k -> new LinkedHashSet<>())
                            .add(ref.instanceName);
                }
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
     * 根据组件类型生成合适的实例名后缀。
     * 命名规则（与R13一致）：
     * - processor → _cpu
     * - memory → _mem（RAM→_ram, Flash/ROM→_rom）
     * - device → _dev
     * - process → _proc
     * - thread → _thr
     * - bus → _bus（总线一般已有数字后缀）
     * - system/subsystem → _sys
     * - virtual processor → _vp
     * - data/subprogram → _inst
     */
    private String generateInstanceName(String componentKeyword, String typeName, Collection<String> existingNames) {
        if (typeName == null || typeName.isEmpty()) {
            return "instance";
        }

        // 默认策略：将大驼峰类型名转为小驼峰（首字母小写）
        // 例：MainProcessor → mainProcessor，KernelProcess → kernelProcess
        String candidate = toCamelCase(typeName);

        // 如果名称已存在，加数字后缀
        int counter = 1;
        Set<String> existing = new HashSet<>(existingNames);
        while (existing.contains(candidate)) {
            candidate = toCamelCase(typeName) + counter;
            counter++;
        }

        return candidate;
    }

    /**
     * 将大驼峰命名转为小驼峰（首字母小写）。
     * 例如：MainProcessor → mainProcessor
     */
    private String toCamelCase(String name) {
        if (name == null || name.isEmpty()) return name;
        if (name.length() == 1) return name.toLowerCase();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * 将大驼峰命名转换为小写下划线命名。
     * 例如：MainProcessor → main_processor, CANController → can_controller
     */
    private String toSnakeCase(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    // 检查是否是连续大写字母中的最后一个（如 CANController 中的 N 后面跟 C）
                    char prev = name.charAt(i - 1);
                    char next = (i + 1 < name.length()) ? name.charAt(i + 1) : 'a';
                    if (Character.isUpperCase(prev) && Character.isLowerCase(next)) {
                        sb.append("_");
                    } else if (Character.isLowerCase(prev)) {
                        sb.append("_");
                    }
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 在AADL代码中执行实例名全局替换。
     * 需要替换的位置：
     * 1. subcomponents 行：`OldName : category Type.impl;` → `NewName : category Type.impl;`
     * 2. connections 行：`port OldName.port -> ...` 或 `... -> OldName.port`
     * 3. properties 行：`reference (OldName)`、`applies to OldName`
     *
     * 使用单词边界匹配避免误替换（如替换 CPU 时不应影响 CPUImpl）。
     */
    private String applyInstanceRenames(String aadlContent, Map<String, String> renames) {
        String[] lines = aadlContent.split("\n");
        List<String> result = new ArrayList<>();

        for (String line : lines) {
            String modified = line;
            // 跳过纯注释行（但代码行尾的注释不影响替换）
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                result.add(line);
                continue;
            }

            for (Map.Entry<String, String> e : renames.entrySet()) {
                String oldName = e.getKey();
                String newName = e.getValue();

                // 使用单词边界替换，确保精确匹配
                // 但注意在 AADL 中，实例名出现在：
                // 1. 行首（subcomponents 声明）：`OldName :`
                // 2. 点号前面（端口引用）：`OldName.port`
                // 3. 括号内（reference/applies to）：`(OldName)` 或 `to OldName`
                // 4. 箭头旁边：`-> OldName.`
                modified = replaceWord(modified, oldName, newName);
            }

            result.add(modified);
        }

        return String.join("\n", result);
    }

    /**
     * 安全的单词替换：只替换作为独立标识符出现的 oldName。
     */
    private String replaceWord(String text, String oldWord, String newWord) {
        // 匹配 oldWord 作为独立标识符：前后不能是字母/数字/下划线/点
        // 注意：点号后面的是端口名，所以 OldName.port 中的 OldName 前面可能是空格、箭头、括号等
        Pattern p = Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(oldWord) + "(?![A-Za-z0-9_])");
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(newWord));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 4aa. 检测畸形 end 语句。
     * 通过追踪组件声明栈来识别畸形 end：凡是以 "end " 开头但不符合正常格式的，都算畸形。
     * 正常格式：end 标识符; 或 end 标识符.impl;
     */
    private void checkMalformedEndStatements(String aadlContent, ValidationResult result) {
        String[] lines = aadlContent.split("\n");
        // 组件声明开头：类型关键字 + 组件名（可选 .impl）
        Pattern implStartPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|" +
                "virtual\\s+processor|virtual\\s+bus|thread\\s+group|abstract)\\s+" +
                "implementation\\s+([A-Za-z_]\\w*)\\.impl\\b",
                Pattern.CASE_INSENSITIVE
        );
        Pattern typeStartPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|" +
                "virtual\\s+processor|virtual\\s+bus|thread\\s+group|abstract)\\s+" +
                "([A-Za-z_]\\w*)\\s*\\{?\\s*$",
                Pattern.CASE_INSENSITIVE
        );
        // EMV2 块开始模式 -> 期望的 end 名称
        // 注意：长模式在前，避免短模式先匹配（如 composite error behavior 须在 error behavior 前）
        String[][] emv2BlockPatterns = {
            {"^\\s*composite\\s+error\\s+behavior\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "behavior"},
            {"^\\s*error\\s+behavior\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "behavior"},
            {"^\\s*error\\s+type\\s+set\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "error type set"},
            {"^\\s*error\\s+type\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "error type"},
            {"^\\s*error\\s+flow\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "error flow"},
            {"^\\s*propagation\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "propagation"},
        };
        // 单独的右大括号行（大模型可能错误地生成 C/Java 风格的块语法）
        Pattern closingBracePattern = Pattern.compile("^\\s*\\}\\s*$");
        // EMV2 内部以 end 开头但不是块结束的语句（如 end to end flow 声明）
        // 注意：不要添加 propagation / error type 等作为块结束的关键字，避免误判
        Pattern emv2InternalEndPattern = Pattern.compile(
                "^\\s*end\\s+to\\s+end\\s+flow\\b",
                Pattern.CASE_INSENSITIVE
        );
        // 正常 end 语句格式（支持多单词 end 名称，如 end error type;）
        Pattern normalEndPattern = Pattern.compile(
                "^\\s*end\\s+(.+?)\\s*;\\s*$", Pattern.CASE_INSENSITIVE
        );
        // 疑似 end 语句：以 end 开头，以分号结尾
        Pattern suspiciousEndPattern = Pattern.compile(
                "^\\s*end\\s+.*;\\s*$", Pattern.CASE_INSENSITIVE
        );

        // 块栈：存期望的 end 名称
        Stack<String> blockStack = new Stack<>();
        // package 声明模式（去掉末尾分号）
        Pattern packageStartPattern = Pattern.compile(
                "^\\s*package\\s+([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*;?",
                Pattern.CASE_INSENSITIVE
        );

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("--")) continue;

            // package 声明开头
            Matcher pkgMatcher = packageStartPattern.matcher(trimmed);
            if (pkgMatcher.find()) {
                blockStack.push(pkgMatcher.group(1));
                continue;
            }

            // 普通 AADL implementation 声明开头
            Matcher implMatcher = implStartPattern.matcher(trimmed);
            if (implMatcher.find()) {
                blockStack.push(implMatcher.group(2) + ".impl");
                continue;
            }
            // 普通 AADL 类型声明开头
            Matcher typeMatcher = typeStartPattern.matcher(trimmed);
            if (typeMatcher.find() && !trimmed.toLowerCase().contains("implementation")) {
                blockStack.push(typeMatcher.group(2));
                continue;
            }

            // EMV2 块开始
            boolean emv2Found = false;
            for (String[] pair : emv2BlockPatterns) {
                Pattern p = Pattern.compile(pair[0], Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(trimmed);
                if (m.find()) {
                    blockStack.push(pair[1]);
                    emv2Found = true;
                    break;
                }
            }
            if (emv2Found) continue;

            // 跳过 EMV2 内部以 end 开头但不是块结束的语句（如 end to end flow 声明）
            if (emv2InternalEndPattern.matcher(trimmed).find()) {
                continue;
            }

            // 跳过单独的右大括号（大模型错误生成的 C/Java 风格语法，不影响栈结构）
            if (closingBracePattern.matcher(trimmed).matches()) {
                continue;
            }

            // end 语句检查
            if (suspiciousEndPattern.matcher(trimmed).find()) {
                Matcher endMatcher = normalEndPattern.matcher(trimmed);
                if (endMatcher.matches()) {
                    // 格式正常，检查名称是否匹配
                    String endName = endMatcher.group(1);
                    if (!blockStack.isEmpty()) {
                        String expected = blockStack.peek();
                        if (!endName.equalsIgnoreCase(expected)) {
                            result.errors.add(String.format(
                                    "第%d行: end 语句名称错误 '%s' — 当前块期望结束为 'end %s;'",
                                    i + 1, trimmed, expected
                            ));
                        }
                        blockStack.pop();
                    }
                } else {
                    // 格式畸形
                    result.errors.add(String.format(
                            "第%d行: 畸形 end 语句 '%s' — 不符合 'end 标识符;' 的规范格式",
                            i + 1, trimmed
                    ));
                    if (!blockStack.isEmpty()) {
                        blockStack.pop();
                    }
                }
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
                "([A-Za-z_]\\w*)\\s*\\{?\\s*$",
                Pattern.CASE_INSENSITIVE
        );
        // EMV2 块开始模式 -> 期望的 end 名称（长模式在前）
        String[][] emv2BlockPatterns = {
            {"^\\s*composite\\s+error\\s+behavior\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "behavior"},
            {"^\\s*error\\s+behavior\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "behavior"},
            {"^\\s*error\\s+type\\s+set\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "error type set"},
            {"^\\s*error\\s+type\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "error type"},
            {"^\\s*error\\s+flow\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "error flow"},
            {"^\\s*propagation\\s+([A-Za-z_]\\w*)\\s*\\{?\\s*$", "propagation"},
        };
        // 单独的右大括号行（大模型可能错误地生成 C/Java 风格的块语法）
        Pattern closingBracePattern = Pattern.compile("^\\s*\\}\\s*$");
        // EMV2 内部以 end 开头但不是块结束的语句（end to end flow 声明）
        Pattern emv2InternalEndPattern = Pattern.compile(
                "^\\s*end\\s+to\\s+end\\s+flow\\b",
                Pattern.CASE_INSENSITIVE
        );
        // 正常 end 语句（支持多单词名称，如 end error type;）
        Pattern normalEndPattern = Pattern.compile(
                "^(\\s*)end\\s+(.+?)\\s*;\\s*$", Pattern.CASE_INSENSITIVE
        );
        // 疑似畸形 end 语句（扩大范围：尾逗号、缺分号、尾点号等都算）
        // 以 end 开头，后面有内容，末尾不是正常分号（或有非分号字符）都视为可疑
        Pattern suspiciousEndPattern = Pattern.compile(
                "^(\\s*)end\\s+\\S.*$", Pattern.CASE_INSENSITIVE
        );
        // 正常 end 语句的末尾分号模式（用于排除正常情况）
        Pattern properEndSemicolonPattern = Pattern.compile(
                "^end\\s+.+\\s*;\\s*$", Pattern.CASE_INSENSITIVE
        );
        // package 声明（去掉末尾分号）
        Pattern packageStartPattern = Pattern.compile(
                "^\\s*package\\s+([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*;?",
                Pattern.CASE_INSENSITIVE
        );

        // 组件栈：存期望的 end 名称（如 "PwmBus.impl"、"behavior"、"error type"）
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

            // EMV2 块开头（error behavior / error type / propagation 等）
            boolean emv2Found = false;
            for (String[] pair : emv2BlockPatterns) {
                Pattern p = Pattern.compile(pair[0], Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(trimmed);
                if (m.find()) {
                    componentStack.push(pair[1]);
                    resultLines.add(line);
                    emv2Found = true;
                    break;
                }
            }
            if (emv2Found) continue;

            // 跳过 EMV2 内部以 end 开头但不是块结束的语句
            if (emv2InternalEndPattern.matcher(trimmed).find()) {
                resultLines.add(line);
                continue;
            }

            // 跳过单独的右大括号（大模型错误生成的 C/Java 风格语法，保留在输出中但不影响栈）
            if (closingBracePattern.matcher(trimmed).matches()) {
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

            // 畸形/名称错误的 end 语句：用栈顶名字替换（同样去掉行尾注释再匹配）
            Matcher susMatcher = suspiciousEndPattern.matcher(trimmedNoComment);
            if (susMatcher.find()) {
                // 从原始行提取缩进
                Matcher indentMatcher = Pattern.compile("^(\\s*)").matcher(line);
                String indent = indentMatcher.find() ? indentMatcher.group(1) : "";
                // 提取行尾注释（如果有的话），修复后保留
                String trailingComment = "";
                int commentIdx = line.indexOf("--");
                if (commentIdx >= 0) {
                    trailingComment = "  " + line.substring(commentIdx).trim();
                }

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
                String fixedLine = indent + "end " + correctName + ";" + trailingComment;

                fixCount++;
                result.fixes.add(String.format(
                        "第%d行: 已修正 end 语句 '%s' → 'end %s;'（基于当前块上下文）",
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
    private String fixNamingCollision(String content, ValidationResult result,
                                       List<SubcomponentRef> subcomponentRefs,
                                       List<ConnectionRef> connectionRefs) {
        String[] lines = content.split("\n");
        List<String> resultLines = new ArrayList<>();
        int fixCount = 0;

        // ==================== 第一阶段：按 impl 作用域收集名称并构建重命名映射 ====================
        // 结构：implName → (oldName → newName)，分别记录实例重命名和连接重命名
        Map<String, Map<String, String>> instRenamesByImpl = new LinkedHashMap<>();
        Map<String, Map<String, String>> connRenamesByImpl = new LinkedHashMap<>();
        // 记录每个 impl 作用域内已使用的新名（避免重命名冲突）
        Map<String, Set<String>> usedNamesByImpl = new LinkedHashMap<>();
        // 记录重命名原因：oldName → reason ("impl_collision" 或 "type_collision")
        Map<String, Map<String, String>> instRenameReasons = new LinkedHashMap<>();

        Pattern implStartPattern = Pattern.compile(
                "^\\s*(?:virtual\\s+processor|system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+implementation\\s+(\\w+)\\.impl\\b"
        );
        Pattern virtualImplStartPattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+implementation\\s+(\\w+)\\.impl\\b"
        );
        Pattern endImplPattern = Pattern.compile("^\\s*end\\s+\\w+\\.impl\\s*;");
        // 注意：virtual processor 必须在 processor 之前，避免短模式先匹配
        Pattern subcompPattern = Pattern.compile(
                "^\\s*(\\w+)\\s*:\\s*(virtual\\s+processor|system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+(\\w+)\\.impl\\s*;"
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
                usedNamesByImpl.putIfAbsent(currentImplName, new HashSet<>());
                instRenameReasons.putIfAbsent(currentImplName, new LinkedHashMap<>());
                continue;
            }
            Matcher vImplMatcher = virtualImplStartPattern.matcher(trimmed);
            if (vImplMatcher.find()) {
                currentImplName = vImplMatcher.group(1);
                instRenamesByImpl.putIfAbsent(currentImplName, new LinkedHashMap<>());
                connRenamesByImpl.putIfAbsent(currentImplName, new LinkedHashMap<>());
                usedNamesByImpl.putIfAbsent(currentImplName, new HashSet<>());
                instRenameReasons.putIfAbsent(currentImplName, new LinkedHashMap<>());
                continue;
            }
            if (endImplPattern.matcher(trimmed).find()) {
                currentImplName = null;
                continue;
            }

            if (currentImplName == null) continue;

            Map<String, String> instRenames = instRenamesByImpl.get(currentImplName);
            Map<String, String> connRenames = connRenamesByImpl.get(currentImplName);
            Set<String> usedNewNames = usedNamesByImpl.get(currentImplName);

            Matcher sm = subcompPattern.matcher(codePart);
            if (sm.find()) {
                String instName = sm.group(1);
                String compKeyword = sm.group(2);
                String typeName = sm.group(3);

                // 规则1：实例名等于当前 impl 名称时需要重命名
                if (instName.equals(currentImplName) && !instRenames.containsKey(instName)) {
                    String newName = instName + "_inst";
                    while (usedNewNames.contains(newName)) {
                        newName = newName + "_x";
                    }
                    instRenames.put(instName, newName);
                    instRenameReasons.get(currentImplName).put(instName, "impl_collision");
                    usedNewNames.add(newName);
                }

                // 规则2：实例名等于其引用的类型名时需要重命名（如 CPU : processor CPU.impl;）
                if (instName.equals(typeName) && !instRenames.containsKey(instName)) {
                    // 收集当前 impl 内已有的所有实例名作为避免冲突的参考
                    Set<String> existingNames = new HashSet<>(usedNewNames);
                    existingNames.addAll(instRenames.values());
                    existingNames.addAll(connRenames.values());
                    String newName = generateInstanceName(compKeyword, typeName, existingNames);
                    instRenames.put(instName, newName);
                    instRenameReasons.get(currentImplName).put(instName, "type_collision");
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

                // 替换独立出现的实例名（如 reference (OldName)、属性值中的引用等）
                // 使用自定义边界：前后不能是字母/数字，排除 TypeName.impl 模式
                for (Map.Entry<String, String> entry : instRenames.entrySet()) {
                    String oldName = entry.getKey();
                    String newName = entry.getValue();
                    String before = modified;
                    // 匹配独立标识符：前面不是字母/数字/点，后面不是字母/数字，且不是 .impl 模式
                    // 注意：前面不能是点（避免 foo.OldName 中的 OldName 被误替换，那是特性名）
                    modified = modified.replaceAll(
                            "(?<![a-zA-Z0-9_.])" + Pattern.quote(oldName) + "(?![a-zA-Z0-9_])(?!\\.impl\\b)",
                            Matcher.quoteReplacement(newName)
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

        // ==================== 第三阶段：更新 subcomponentRefs 和 connectionRefs 中的引用 ====================
        for (Map.Entry<String, Map<String, String>> implEntry : instRenamesByImpl.entrySet()) {
            String implName = implEntry.getKey();
            Map<String, String> renames = implEntry.getValue();
            if (renames.isEmpty()) continue;

            // 更新 subcomponentRefs
            for (SubcomponentRef ref : subcomponentRefs) {
                if (implName.equals(ref.parentImpl) && renames.containsKey(ref.instanceName)) {
                    ref.instanceName = renames.get(ref.instanceName);
                }
            }

            // 更新 connectionRefs 中的 sourceInstance 和 destInstance
            for (ConnectionRef conn : connectionRefs) {
                if (implName.equals(conn.parentImpl)) {
                    if (conn.sourceInstance != null && renames.containsKey(conn.sourceInstance)) {
                        conn.sourceInstance = renames.get(conn.sourceInstance);
                    }
                    if (conn.destInstance != null && renames.containsKey(conn.destInstance)) {
                        conn.destInstance = renames.get(conn.destInstance);
                    }
                }
            }
        }

        // 记录修复日志
        for (Map.Entry<String, Map<String, String>> implEntry : instRenamesByImpl.entrySet()) {
            Map<String, String> reasons = instRenameReasons.get(implEntry.getKey());
            for (Map.Entry<String, String> e : implEntry.getValue().entrySet()) {
                String reason = reasons != null ? reasons.get(e.getKey()) : null;
                String msg;
                if ("type_collision".equals(reason)) {
                    msg = String.format(
                            "已重命名 %s.impl 中实例 '%s' → '%s'（实例名不能与其引用的类型名同名）",
                            implEntry.getKey(), e.getKey(), e.getValue());
                } else {
                    msg = String.format(
                            "已重命名 %s.impl 中实例 '%s' → '%s'（实例名不能与包含它的组件类型同名）",
                            implEntry.getKey(), e.getKey(), e.getValue());
                }
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
     * 自动修正（按顺序执行，共6个阶段）：
     *
     * 第一阶段：语法结构修复（必须最先执行，否则后续解析会出错）
     *   0r. 修复畸形 end 语句
     *   0a. 修正非法 'requires data port' 语法 → 'in data port'
     *   0b. 修复截断/不完整的连接行（动态推断端口名 + 补充分号）
     *
     * 第二阶段：删除非法内容（先删除，避免后续处理无效行）
     *   0c. 删除线程 implementation 中非法的 connections 块
     *   0d. 删除 data 组件中非法的 features 块
     *   0g. 删除连接类型与实体类型不匹配的连接（软件组件用bus access、硬件组件用port连接）
     *   0g2. 删除引用data组件端口的port连接
     *   （thread的bus access问题只检测不自动修复，重构太复杂风险高）
     *
     * 第三阶段：移动/重排内容
     *   0e. 将 implementation 中非法的 features 块移到对应的类型声明中
     *   0h. 重排 implementation 中的块顺序为 subcomponents → connections → properties
     *
     * 第四阶段：简单语法替换（不改变结构，只修改内容）
     *   0n. 修复连接操作符错误（port 用 <-> 改 ->，access 用 -> 改 <->）
     *   0p. 自动修正端口方向错误（交换方向反了的连接端点）
     *   0s. 修复 reference 属性值括号格式（reference (xxx) → (reference (xxx))）
     *
     * 第五阶段：命名统一（必须在所有名称匹配的重构之前执行）
     *   0q. 修复命名空间冲突（实例名/连接名与类型名重名时追加后缀，回写refs列表）
     *
     * 第六阶段：复杂结构重构（依赖统一后的名称）
     *   0t. 修复非法 subcomponent 嵌套（如 process 直接放在 processor 下）
     *   0u. 修复 applies to 引用未声明实例的问题
     *
     * 第七阶段：补全缺失内容（最后执行，基于最终代码状态补全）
     *   1. 补全架构树中存在但AADL中缺失的组件声明
     *   1b.补全subcomponents中引用但未声明的组件类型
     *   3. 补全connections引用中缺失的feature声明
     */
    private String applyFixes(String aadlContent,
                              Map<String, AadlDeclaration> declarations,
                              Map<String, AadlInputParser.ArchNode> archComponents,
                              Map<String, Map<String, String>> componentFeatures,
                              List<SubcomponentRef> subcomponentRefs,
                              List<ConnectionRef> connectionRefs,
                              ValidationResult result) {
        // 0. 在原始内容对应行尾标注所有错误和警告（行内注释，不新增行）
        String content = annotateErrorsAndWarningsInline(aadlContent, result);

        // ===== 第一阶段：语法结构修复（必须最先执行，否则后续解析会出错）=====
        // 0r. 修复畸形 end 语句
        content = fixMalformedEndStatements(content, result);

        // 0a. 修正非法 requires data port 语法
        content = fixRequiresDataPort(content, result);

        // 0b. 修复截断/不完整的连接行（硬编码补充缺失端口名 + 分号）
        content = fixIncompleteConnectionLines(content, result);

        // ===== 第二阶段：删除非法内容（先删除，避免后续处理无效行）=====
        // 0c. 删除线程 implementation 中非法的 connections 块
        content = fixThreadConnectionsBlocks(content, result);

        // 0d. 删除 data 组件中非法的 features 块
        content = fixDataComponentFeatures(content, result);

        // 0g. 删除连接类型与实体类型不匹配的连接（如软件组件用bus access连接）
        content = fixConnectionEntityTypeMismatch(content, declarations, result);
        // 0g2. 删除引用data组件端口的port连接（data组件不能有port）
        content = fixPortConnectionToDataComponent(content, declarations, result);

        // 注：thread的bus access问题只检测不自动修复（重构太复杂，自动修复风险高）
        //     由 checkThreadBusAccessFeature 检测并报错，人工修复

        // ===== 第三阶段：移动/重排内容 =====
        // 0e. 将 implementation 中非法的 features 块移到对应的类型声明中
        content = fixFeaturesPlacement(content, result);

        // 0h. 重排 implementation 中的块顺序为 subcomponents → connections → properties
        content = fixImplementationOrder(content, result);

        // ===== 第四阶段：简单语法替换（不改变结构，只修改内容）=====
        // 0n. 修复连接操作符错误（port 用 <-> 改 ->，access 用 -> 改 <->）
        content = fixConnectionOperator(content, result);

        // 0p. 自动修正端口方向错误（交换方向反了的连接端点）
        content = fixPortDirectionAuto(content, result);

        // 0s. 修复 reference 属性值括号格式（reference (xxx) → (reference (xxx))）
        content = fixReferenceParentheses(content, result);

        // ===== 第五阶段：命名统一（必须在所有名称匹配的重构之前执行）=====
        // 0q. 修复命名空间冲突（实例名/连接名与类型名重名时追加后缀，回写subcomponentRefs/connectionRefs）
        content = fixNamingCollision(content, result, subcomponentRefs, connectionRefs);

        // ===== 第六阶段：复杂结构重构（依赖统一后的名称）=====
        // 0t. 修复非法 subcomponent 嵌套（如 process 直接放在 processor 下，自动包装 process）
        content = fixIllegalSubcomponentNesting(content, declarations, result);

        // 0u. 修复 applies to 引用未声明实例的问题
        content = fixAppliesToUndeclared(content, declarations, result);

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
                // 跳过类型不是合法 AADL 组件的节点（可能是大模型把 port/interface 等 feature 当成了组件）
                if (!isValidAadlComponentType(archComp.type)) {
                    log.debug("跳过非AADL组件类型的架构树节点: {} (type={})", archComp.name, archComp.type);
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
                    result.warnings.add(String.format(
                            "遗漏组件: '%s' (%s) 在架构树中存在但 AADL 中缺失声明，已自动补全",
                            archComp.name, archComp.type
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

        // 1b. 补全"subcomponents 中引用了但 AADL 中完全未声明"的类型
        //     （架构树里可能没有同名组件，但代码里引用了，也必须补空壳声明否则语法不通过）
        if (subcomponentRefs != null && !subcomponentRefs.isEmpty()) {
            // 用 set 去重（同一个类型可能被多个 subcomponent 引用）
            Map<String, String> undeclaredRefs = new LinkedHashMap<>(); // typeName -> componentKeyword
            for (SubcomponentRef ref : subcomponentRefs) {
                if (declarations.get(ref.typeName) == null
                        && ref.componentKeyword != null
                        && isValidAadlComponentType(ref.componentKeyword)) {
                    undeclaredRefs.putIfAbsent(ref.typeName, ref.componentKeyword);
                }
            }
            // 排除架构树已经处理过的（第 1 步已补全的）
            for (String archName : archComponents.keySet()) {
                undeclaredRefs.remove(archName);
            }
            for (Map.Entry<String, String> entry : undeclaredRefs.entrySet()) {
                String typeName = entry.getKey();
                String compType = entry.getValue();
                if (isReservedWord(typeName)) {
                    log.debug("跳过保留字未声明类型补全: {}", typeName);
                    continue;
                }
                fixBlock.append(generateFullDeclaration(typeName, compType));
                fixCount++;
                result.fixes.add(String.format(
                        "已补全引用中未声明的组件: %s (%s)", typeName, compType
                ));
                // 架构树中不存在 → 标记为疑似幻觉组件（但仍然补全以保证语法通过）
                if (!archComponents.containsKey(typeName)) {
                    result.warnings.add(String.format(
                            "疑似幻觉组件: '%s' (%s) 在架构树中不存在，但 AADL 中被引用，已补全空壳声明。" +
                                    "建议检查该组件是否为 LLM 额外生成的不必要组件",
                            typeName, compType
                    ));
                    log.warn("疑似幻觉组件，已补全空壳声明: {} ({})", typeName, compType);
                }
                // 更新 declarations，避免后续步骤重复处理
                AadlDeclaration newDecl = new AadlDeclaration();
                newDecl.name = typeName;
                newDecl.type = compType;
                newDecl.hasTypeDecl = true;
                newDecl.hasImplDecl = true;
                declarations.put(typeName, newDecl);
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

                // 获取组件类型，用于决定补 data port 还是 bus access
                String compType = (decl != null) ? decl.type : "system";

                content = injectMissingFeatures(content, typeName, compType, toAdd);
                featureFixCount += toAdd.size();
                for (Map.Entry<String, String> fe : toAdd.entrySet()) {
                    String featName = fe.getKey();
                    String dataType = fe.getValue();
                    String direction = inferDirection(featName);
                    String typeDesc;
                    if (isHardwareComponentType(compType)) {
                        // 纯硬件组件补 bus access
                        typeDesc = direction + " bus access";
                    } else {
                        typeDesc = (dataType != null && !dataType.isEmpty())
                                ? direction + " data port " + dataType
                                : direction + " data port";
                    }
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
     * 根据组件类型决定补 data port 还是 bus access：
     * - 纯硬件组件（processor/memory/bus/virtual processor/virtual bus）：补 bus access
     * - 软件/复合组件（system/process/thread/device 等）：补 data port
     *
     * @param content      AADL 代码
     * @param typeName     组件类型名
     * @param componentType 组件类型（system/process/thread/processor/...）
     * @param missingFeats 需要补全的 feature（feature名 → 数据类型，数据类型可能为空字符串）
     * @return 修正后的 AADL 代码
     */
    private String injectMissingFeatures(String content, String typeName, String componentType,
                                          Map<String, String> missingFeats) {
        String[] lines = content.split("\n");
        StringBuilder featureLines = new StringBuilder();
        List<String> featNames = new ArrayList<>(missingFeats.keySet());
        boolean isHardware = isHardwareComponentType(componentType);

        for (Map.Entry<String, String> entry : missingFeats.entrySet()) {
            String featName = entry.getKey();
            String dataType = entry.getValue();
            String direction = inferDirection(featName);

            if (isHardware) {
                // 纯硬件组件：生成 bus access（不需要数据类型）
                featureLines.append("    ").append(featName).append(" : ")
                        .append(direction).append(" bus access;\n");
            } else {
                // 软件/复合组件：生成 data port
                // 安全网：数据类型为空时使用 Base_Type，避免生成非法的 "data port;" 无类型声明
                if (dataType == null || dataType.isEmpty()) {
                    dataType = "Base_Type";
                }
                featureLines.append("    ").append(featName).append(" : ")
                        .append(direction).append(" data port ").append(dataType).append(";\n");
            }
        }

        // 查找类型声明行：如 "processor MainProcessor" 或 "device PowerSupply"
        // 支持行尾注释（-- comment）
        Pattern typeDeclPattern = Pattern.compile(
                "^\\s*(system|process|thread|processor|memory|device|bus|data|subprogram|abstract)\\s+" +
                Pattern.quote(typeName) + "\\s*(?:--.*)?$"
        );
        // virtual processor 特殊处理
        Pattern virtualTypePattern = Pattern.compile(
                "^\\s*virtual\\s+processor\\s+" + Pattern.quote(typeName) + "\\s*(?:--.*)?$"
        );

        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i].trim());

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
                String nextLine = stripComment(lines[j].trim());
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
                // 没有 features 块，在类型声明行之后新建（4空格缩进，与 generateTypeDeclaration 风格一致）
                StringBuilder sb = new StringBuilder();
                sb.append("    features\n");
                sb.append("    -- [自动修正] 补全 connections 引用中缺失的 feature 声明: ")
                  .append(String.join(", ", featNames)).append("\n");
                sb.append(featureLines);
                // 去掉 featureLines 末尾多余的换行符，避免空行
                String injectBlock = sb.toString();
                if (injectBlock.endsWith("\n")) {
                    injectBlock = injectBlock.substring(0, injectBlock.length() - 1);
                }
                lines[i] = lines[i] + "\n" + injectBlock;
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
        // 输出方向指示词（精确单词边界匹配，避免 route/outer/without 等子串误判）
        // 边界定义：前后不能是字母/数字（下划线、箭头、括号、起止位置均视为边界）
        Pattern outPattern = Pattern.compile("(?<![a-zA-Z0-9])(out|output|send|src)(?![a-zA-Z0-9])");
        if (outPattern.matcher(lower).find()) {
            return "out";
        }
        // 输入方向指示词
        Pattern inPattern = Pattern.compile("(?<![a-zA-Z0-9])(in|input|recv|dst|dest)(?![a-zA-Z0-9])");
        if (inPattern.matcher(lower).find()) {
            return "in";
        }
        // 默认 in（保守策略）
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
        // 1. 提取 package 名称（支持层次包名如 MyProject.AadlCode）
        Pattern pkgPattern = Pattern.compile("package\\s+(\\w+(?:\\.\\w+)*)");
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
