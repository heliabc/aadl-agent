package com.example.aadlagent.agent.requirement;

import com.example.aadlagent.agent.Agent;
import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.model.GlobalAnchor;
import com.example.aadlagent.model.Requirement;
import com.example.aadlagent.model.RequirementAnalysisResult;
import com.example.aadlagent.model.RequirementAnalysisResult.*;
import com.example.aadlagent.util.TextCleaner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RequirementAgent implements Agent<AgentInput, AgentOutput> {

    private static final String AGENT_NAME = "RequirementAgent";

    private final ModelService modelService;
    private final ObjectMapper objectMapper;
    private final RequirementPrompt prompt;

    @Value("${agent.requirement.max-retries:3}")
    private int maxRetries;

    @Value("${agent.requirement.temperature:0.1}")
    private double temperature;

    @Value("${agent.requirement.max-tokens:8192}")
    private int maxTokens;

    @Value("${agent.requirement.chunk-size:5000}")
    private int chunkSize;

    @Value("${agent.requirement.overlap-percent:15}")
    private int overlapPercent;

    @Value("${agent.requirement.direct-process-threshold:4000}")
    private int directProcessThreshold;

    public RequirementAgent(ModelService modelService) {
        this.modelService = modelService;
        this.objectMapper = new ObjectMapper();
        this.prompt = new RequirementPrompt();
    }

    @Override
    public AgentOutput execute(AgentInput input) {
        long startTime = System.currentTimeMillis();

        ModelType modelType = input.getModelType() != null ? input.getModelType() : ModelType.OLLAMA;
        LlmClient llmClient = modelService.getClient(modelType);

        log.info("========================================");
        log.info("RequirementAgent starting execution");
        log.info("Session ID: {}", input.getSessionId());
        log.info("Model: {} ({})", modelType.name(), llmClient.getModelName());
        log.info("========================================");

        String requirementDoc = input.getContent();
        if (requirementDoc == null || requirementDoc.trim().isEmpty()) {
            log.error("需求文档内容为空，无法继续处理");
            return AgentOutput.failure(input.getSessionId(), "需求文档内容不能为空");
        }

        log.info("需求文档长度: {} 字符", requirementDoc.length());
        log.info("配置参数: temperature={}, maxTokens={}, maxRetries={}", temperature, maxTokens, maxRetries);

        // 文本清洗：删除页眉页脚/水印、修订记录、分隔线、引导词、压缩空白、删除零宽字符
        String cleanedDoc = TextCleaner.clean(requirementDoc);
        log.info("文本清洗完成，清洗后长度: {} 字符", cleanedDoc.length());

        RequirementAnalysisResult analysisResult = RequirementAnalysisResult.builder()
                .rawInput(requirementDoc)
                .build();

        try {
            // 阶段0：预处理——生成全局上下文卡片
            log.info("\n\n========== 阶段0：预处理——生成全局上下文卡片 ==========");
            Stage0Result stage0 = executeStage0(cleanedDoc, llmClient);
            analysisResult.setStage0(stage0);
            log.info("阶段0完成，耗时: {}ms", stage0.getExecutionTime());
            log.info("全局上下文卡片:\n{}", stage0.getContextCard());

            if (input.isCancelled()) {
                log.info("任务已取消，RequirementAgent停止执行");
                return AgentOutput.cancelled(input.getSessionId());
            }

            // 阶段1：分层分块——注入全局视野
            log.info("\n\n========== 阶段1：分层分块——注入全局视野 ==========");
            boolean isDirectProcess = cleanedDoc.length() <= directProcessThreshold;
            if (isDirectProcess) {
                log.info("文档长度 {} 字符，小于直接处理阈值 {}，采用直接处理模式", cleanedDoc.length(), directProcessThreshold);
            }
            Stage1Result stage1 = executeStage1(cleanedDoc, stage0.getContextCard(), isDirectProcess);
            analysisResult.setStage1(stage1);
            log.info("阶段1完成，耗时: {}ms，分块数量: {} 个", stage1.getExecutionTime(), 
                    stage1.getChunks() != null ? stage1.getChunks().size() : 0);

            if (input.isCancelled()) {
                log.info("任务已取消，RequirementAgent停止执行");
                return AgentOutput.cancelled(input.getSessionId());
            }

            // 阶段2：条目化提取——显式绑定全局引用
            log.info("\n\n========== 阶段2：条目化提取——显式绑定全局引用 ==========");
            Stage2Result stage2 = executeStage2(stage1.getChunks(), llmClient, input);
            analysisResult.setStage2(stage2);
            int totalRequirements = stage2.getChunkResults().stream()
                    .mapToInt(List::size)
                    .sum();
            log.info("阶段2完成，耗时: {}ms，提取需求总数: {} 条", stage2.getExecutionTime(), totalRequirements);

            if (input.isCancelled()) {
                log.info("任务已取消，RequirementAgent停止执行");
                return AgentOutput.cancelled(input.getSessionId());
            }

            // 阶段3：合并与校验——机械拼接，杜绝幻觉融合
            log.info("\n\n========== 阶段3：合并与校验——机械拼接 ==========");
            Stage3Result stage3 = executeStage3(stage2.getChunkResults(), stage0.getContextCard());
            analysisResult.setStage3(stage3);
            log.info("阶段3完成，耗时: {}ms，合并后需求: {} 条，冲突: {} 个", 
                    stage3.getExecutionTime(),
                    stage3.getMergedRequirements() != null ? stage3.getMergedRequirements().size() : 0,
                    stage3.getConflicts() != null ? stage3.getConflicts().size() : 0);

            if (stage3.getConflicts() != null && !stage3.getConflicts().isEmpty()) {
                log.warn("检测到冲突:");
                for (Conflict conflict : stage3.getConflicts()) {
                    log.warn("  - {}: {}", conflict.getConflictId(), conflict.getDescription());
                }
            }

            long totalExecutionTime = System.currentTimeMillis() - startTime;
            analysisResult.setTotalExecutionTime(totalExecutionTime);

            String outputJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(analysisResult);

            log.info("========================================");
            log.info("RequirementAgent执行成功!");
            log.info("总耗时: {}ms", totalExecutionTime);
            log.info("最终需求数量: {} 条", stage3.getMergedRequirements().size());
            log.info("========================================");

            return AgentOutput.success(input.getSessionId(), outputJson, totalExecutionTime);

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("RequirementAgent执行失败!", e);
            return AgentOutput.failure(input.getSessionId(), "需求分析失败: " + e.getMessage());
        }
    }

    private static class PatternConfig {
        private String name;
        private String regex;
        private Pattern pattern;
        private String category;
        private String anchorPrefix;

        public PatternConfig() {}

        public PatternConfig(String name, String regex, String category, String anchorPrefix) {
            this.name = name;
            this.regex = regex;
            this.category = category;
            this.anchorPrefix = anchorPrefix;
            this.pattern = Pattern.compile(regex);
        }

        public void compile() {
            this.pattern = Pattern.compile(regex);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRegex() {
            return regex;
        }

        public void setRegex(String regex) {
            this.regex = regex;
        }

        public Pattern getPattern() {
            return pattern;
        }

        public void setPattern(Pattern pattern) {
            this.pattern = pattern;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getAnchorPrefix() {
            return anchorPrefix;
        }

        public void setAnchorPrefix(String anchorPrefix) {
            this.anchorPrefix = anchorPrefix;
        }
    }

    @Data
    private static class CardConfig {
        int maxItemsPerCategory;
        int maxCardLength;
        int contextExtendLength;
    }

    @Data
    private static class ContextPatternsConfig {
        List<PatternConfig> patterns;
        CardConfig card;
    }

    private List<PatternConfig> patterns;
    private CardConfig cardConfig;

    @PostConstruct
    public void init() {
        loadPatternsFromConfig();
    }

    private void loadPatternsFromConfig() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("context-patterns.yml")) {
            if (is != null) {
                Yaml yaml = new Yaml();
                ContextPatternsConfig config = yaml.loadAs(is, ContextPatternsConfig.class);
                
                // 编译正则表达式
                for (PatternConfig patternConfig : config.getPatterns()) {
                    patternConfig.compile();
                }
                
                this.patterns = config.getPatterns();
                this.cardConfig = config.getCard();
                
                log.info("成功加载 {} 个正则模式配置", patterns.size());
            } else {
                log.warn("未找到 context-patterns.yml，使用默认模式");
                loadDefaultPatterns();
            }
        } catch (Exception e) {
            log.error("加载正则模式配置失败，使用默认模式: {}", e.getMessage());
            loadDefaultPatterns();
        }
    }

    private void loadDefaultPatterns() {
        this.patterns = Arrays.asList(
                new PatternConfig("clock", "(\\d+\\.?\\d*)\\s*([kKMG]?[Hh]z)", "时钟频率", "PARAM"),
                new PatternConfig("time", "(\\d+\\.?\\d*)\\s*(毫秒|微秒|纳秒|秒|ms|us|μs|ns|s)", "时间参数", "PARAM"),
                new PatternConfig("memory", "(\\d+\\.?\\d*)\\s*([kKMG]?[Bb](yte)?)", "内存大小", "PARAM"),
                new PatternConfig("baud", "(\\d+\\.?\\d*)\\s*([kKMG]?[Bb]ps)", "波特率", "PARAM"),
                new PatternConfig("deadline", "(截止|响应|执行|反应)时间\\s*[:：=]?\\s*(\\d+\\.?\\d*)\\s*(ms|us|μs|ns|s|毫秒|微秒)", "截止时间", "PARAM"),
                new PatternConfig("period", "(周期|period)\\s*[:：=]?\\s*(\\d+\\.?\\d*)\\s*(ms|毫秒|Hz)", "周期", "PARAM"),
                new PatternConfig("jitter", "(抖动|jitter)\\s*[:：=]?\\s*(不超过|≤|<)?\\s*(\\d+\\.?\\d*)\\s*(ms|us|μs)", "抖动", "PARAM"),
                new PatternConfig("priority", "(优先级|priority)\\s*[:：=]?\\s*(\\d+|[高H中M低L])", "优先级", "PARAM"),
                new PatternConfig("abbr", "([A-Z]{2,6})\\s*[（(]\\s*([^）)]{1,30})\\s*[）)]", "缩写", "ABBR"),
                new PatternConfig("iface", "(CAN|UART|SPI|I2C|I2S|GPIO|PWM|ADC|DAC|USB|Ethernet|PCIe?|SDIO|FlexRay|LIN|RS232|RS485)\\s*(\\d*)", "接口协议", "IFACE"),
                new PatternConfig("safety", "(DAL-[A-E]|ASIL\\s*[A-D]|SIL\\s*[1-4])", "安全等级", "SAFETY"),
                new PatternConfig("assume", "(假设|前提|假定)\\s*[:：]?\\s*(.{10,200}?)(?=[。；！\\n]|$)", "假设前提", "ASSUMP"),
                new PatternConfig("limit", "(不超过|不低于|≥|≤|max|min|最大|最小)\\s*[:：]?\\s*(\\d+\\.?\\d*)\\s*(ms|us|MHz|KB|%)", "限制条件", "CONST"),
                new PatternConfig("constraint", "(必须|不得|禁止|严禁|应当|不应|务必)\\s+(.{1,50}?)(?=[。；！\\n]|$)", "约束条件", "CONST")
        );
        
        // 编译默认正则表达式
        for (PatternConfig patternConfig : this.patterns) {
            patternConfig.compile();
        }
        
        this.cardConfig = new CardConfig();
        this.cardConfig.setMaxItemsPerCategory(5);
        this.cardConfig.setMaxCardLength(800);
        this.cardConfig.setContextExtendLength(80);
    }

    private Stage0Result executeStage0(String document, LlmClient llmClient) {
        long startTime = System.currentTimeMillis();

        // 正则匹配提取信息，生成上下文卡片
        String contextCard = buildContextCard(document);

        return Stage0Result.builder()
                .contextCard(contextCard)
                .executionTime(System.currentTimeMillis() - startTime)
                .build();
    }

    private String buildContextCard(String document) {
        Map<String, Integer> counters = new HashMap<>();
        Map<String, List<String>> grouped = new HashMap<>();
        
        for (PatternConfig config : patterns) {
            Matcher matcher = config.pattern.matcher(document);
            while (matcher.find()) {
                String context = extractContext(document, matcher.start(), matcher.end(), cardConfig.getContextExtendLength());
                counters.put(config.category, counters.getOrDefault(config.category, 0) + 1);
                int count = counters.get(config.category);
                String anchorId = generateAnchorId(config.anchorPrefix, count);
                String itemWithId = anchorId + " | " + context;
                grouped.computeIfAbsent(config.category, k -> new ArrayList<>()).add(itemWithId);
            }
        }

        // 将细分类别聚合到三个大类：约束条件、参数配置、接口协议
        Map<String, List<String>> aggregated = aggregateToCategories(grouped);
        
        // 策略：不压缩内容，按顺序删除类别
        // 删除顺序：参数配置 → 接口协议 → 约束条件（约束条件最重要）
        
        // 级别1：包含所有三个大类
        String level1 = buildCardWithCategories(aggregated, Arrays.asList("约束条件", "参数配置", "接口协议"));
        if (level1.length() <= cardConfig.getMaxCardLength()) {
            return level1;
        }
        
        // 级别2：删除参数配置（一般不具备全局属性）
        String level2 = buildCardWithCategories(aggregated, Arrays.asList("约束条件", "接口协议"));
        if (level2.length() <= cardConfig.getMaxCardLength()) {
            return level2;
        }
        
        // 级别3：删除接口协议
        String level3 = buildCardWithCategories(aggregated, Arrays.asList("约束条件"));
        return level3;
    }

    private Map<String, List<String>> aggregateToCategories(Map<String, List<String>> grouped) {
        Map<String, List<String>> aggregated = new HashMap<>();
        
        // 约束条件大类：包含所有约束类细分类别
        List<String> constraintCategories = Arrays.asList("约束条件", "限制条件", "假设前提", "容错能力", "合规要求");
        
        // 参数配置大类：包含所有参数类细分类别
        List<String> paramCategories = Arrays.asList("时钟频率", "时间参数", "内存大小", "波特率", "截止时间", 
                "周期", "抖动", "优先级", "电源约束", "温度范围", "可靠性指标", "版本要求", 
                "存储容量", "网络带宽", "中断优先级", "数据格式");
        
        // 接口协议大类：包含所有接口协议类细分类别
        List<String> ifaceCategories = Arrays.asList("接口协议", "通信协议", "加密要求");
        
        // 聚合约束条件
        List<String> constraints = new ArrayList<>();
        for (String cat : constraintCategories) {
            if (grouped.containsKey(cat)) {
                constraints.addAll(grouped.get(cat));
            }
        }
        if (!constraints.isEmpty()) {
            aggregated.put("约束条件", constraints);
        }
        
        // 聚合参数配置
        List<String> params = new ArrayList<>();
        for (String cat : paramCategories) {
            if (grouped.containsKey(cat)) {
                params.addAll(grouped.get(cat));
            }
        }
        if (!params.isEmpty()) {
            aggregated.put("参数配置", params);
        }
        
        // 聚合接口协议
        List<String> ifaces = new ArrayList<>();
        for (String cat : ifaceCategories) {
            if (grouped.containsKey(cat)) {
                ifaces.addAll(grouped.get(cat));
            }
        }
        if (!ifaces.isEmpty()) {
            aggregated.put("接口协议", ifaces);
        }
        
        return aggregated;
    }

    private String buildCardWithCategories(Map<String, List<String>> aggregated, List<String> categoriesToInclude) {
        StringBuilder card = new StringBuilder();
        card.append("【全局上下文卡片】\n");
        card.append("以下是从整个文档中提取的全局约束、参数和接口定义，每个条目都有唯一锚点ID。\n");
        card.append("在处理当前分块时，必须遵守这些全局约束，并在输出中通过 globalRef 字段引用相关锚点ID。\n\n");

        for (String category : categoriesToInclude) {
            if (!aggregated.containsKey(category)) {
                continue;
            }
            
            List<String> items = aggregated.get(category);
            card.append("- ").append(category).append(":\n");
            int count = 0;
            for (String content : items) {
                if (count >= cardConfig.getMaxItemsPerCategory()) {
                    card.append("  * ...(共").append(items.size()).append("条)\n");
                    break;
                }
                // 保留完整句子上下文，不压缩
                card.append("  * ").append(content).append("\n");
                count++;
            }
        }

        card.append("\n【锚点ID规则说明】\n");
        card.append("- CONST-xxx：约束条件（必须/不得/禁止等）\n");
        card.append("- PARAM-xxx：参数配置（时钟频率/时间参数/内存大小/波特率/周期等）\n");
        card.append("- IFACE-xxx：接口协议\n");

        return card.toString();
    }

    private String generateAnchorId(String anchorPrefix, int count) {
        return anchorPrefix + "-" + String.format("%03d", count);
    }

    private String extractContext(String document, int start, int end, int contextLength) {
        int sentenceStart = start;
        int sentenceEnd = end;

        for (int i = start - 1; i >= 0 && i >= start - contextLength; i--) {
            char c = document.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == ';' || c == '；' || c == '\n') {
                sentenceStart = i + 1;
                break;
            }
        }

        for (int i = end; i < document.length() && i <= end + contextLength; i++) {
            char c = document.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == ';' || c == '；' || c == '\n') {
                sentenceEnd = i + 1;
                break;
            }
        }

        String context = document.substring(sentenceStart, sentenceEnd).trim();
        if (context.length() > 150) {
            context = context.substring(0, 150) + "...";
        }
        return context;
    }

    private Stage1Result executeStage1(String document, String contextCard, boolean isDirectProcess) {
        long startTime = System.currentTimeMillis();
        List<DocumentChunk> chunks = new ArrayList<>();

        // 直接处理模式：文档较小，无需分块，将整个文档作为一个分块处理
        if (isDirectProcess) {
            String injectedContent = contextCard + "\n\n【当前内容】\n" + document;
            chunks.add(DocumentChunk.builder()
                    .chunkId(1)
                    .content(injectedContent)
                    .sectionId("SEC-001")
                    .sectionTitle("完整文档")
                    .startLine(1)
                    .endLine(1)
                    .build());
            
            return Stage1Result.builder()
                    .chunks(chunks)
                    .executionTime(System.currentTimeMillis() - startTime)
                    .build();
        }

        // 分块处理模式：文档较大，按固定chunkSize分割
        int chunkId = 1;
        int currentPos = 0;
        int docLength = document.length();

        while (currentPos < docLength) {
            // 确定分块结束位置（固定chunkSize）
            int endPos = Math.min(currentPos + chunkSize, docLength);
            
            // 检查最后一句是否完整，如果不完整则向后扩展到句末（最后一个分块不需要扩展）
            if (endPos < docLength) {
                char lastChar = document.charAt(endPos - 1);
                // 如果最后一个字符不是句末标点，向后查找直到句末
                if (lastChar != '。' && lastChar != '！' && lastChar != '？' && 
                    lastChar != ';' && lastChar != '；' && lastChar != '\n') {
                    // 向后查找最近的句末标点
                    for (int i = endPos; i < Math.min(docLength, endPos + 200); i++) {
                        char c = document.charAt(i);
                        if (c == '。' || c == '！' || c == '？' || c == ';' || c == '；') {
                            endPos = i + 1;
                            break;
                        }
                    }
                }
            }

            // 提取分块内容
            String chunkContent = document.substring(currentPos, endPos).trim();
            
            // 跳过空内容
            if (chunkContent.isEmpty()) {
                currentPos++;
                continue;
            }
            
            // 创建分块（注入全局上下文卡片）
            String injectedContent = contextCard + "\n\n【当前内容】\n" + chunkContent;
            
            chunks.add(DocumentChunk.builder()
                    .chunkId(chunkId)
                    .content(injectedContent)
                    .sectionId("SEC-" + String.format("%03d", chunkId))
                    .sectionTitle("分块 " + chunkId)
                    .startLine(1)
                    .endLine(1)
                    .build());

            // 下一个分块直接从endPos开始，不回退（无重叠）
            currentPos = endPos;
            chunkId++;
        }

        return Stage1Result.builder()
                .chunks(chunks)
                .executionTime(System.currentTimeMillis() - startTime)
                .build();
    }

    private Stage2Result executeStage2(List<DocumentChunk> chunks, LlmClient llmClient, AgentInput input) {
        long startTime = System.currentTimeMillis();
        List<List<Requirement>> chunkResults = new ArrayList<>();

        for (DocumentChunk chunk : chunks) {
            if (input.isCancelled()) {
                log.info("任务已取消，停止处理剩余分块");
                break;
            }

            log.info("处理分块 {}: {} (行 {} - {})", 
                    chunk.getChunkId(), chunk.getSectionTitle(), chunk.getStartLine(), chunk.getEndLine());

            List<Requirement> requirements = processChunk(chunk, llmClient, input);
            chunkResults.add(requirements);
            
            log.info("  分块 {} 提取需求: {} 条", chunk.getChunkId(), requirements.size());
        }

        return Stage2Result.builder()
                .chunkResults(chunkResults)
                .executionTime(System.currentTimeMillis() - startTime)
                .build();
    }

    private List<Requirement> processChunk(DocumentChunk chunk, LlmClient llmClient, AgentInput input) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (input.isCancelled()) {
                return Collections.emptyList();
            }

            try {
                String systemPrompt = prompt.buildPromptWithGlobalContext(chunk.getContent(), chunk.getSectionId());
                String llmResponse = llmClient.chat(systemPrompt, temperature, maxTokens);

                if (llmResponse == null || llmResponse.trim().isEmpty()) {
                    log.warn("分块 {} 第{}次尝试：LLM返回空响应", chunk.getChunkId(), attempt);
                    continue;
                }

                List<Requirement> requirements = parseRequirements(llmResponse);
                if (requirements != null && !requirements.isEmpty()) {
                    // 为每个需求添加章节信息
                    for (Requirement req : requirements) {
                        if (req.getDependencies() == null) {
                            req.setDependencies(new ArrayList<>());
                        }
                        req.getDependencies().add("章节: " + chunk.getSectionId());
                    }
                    return requirements;
                }

                log.warn("分块 {} 第{}次尝试：解析出的需求列表为空", chunk.getChunkId(), attempt);

            } catch (Exception e) {
                log.warn("分块 {} 第{}次尝试失败: {}", chunk.getChunkId(), attempt, e.getMessage());
            }
        }

        return Collections.emptyList();
    }

    private Stage3Result executeStage3(List<List<Requirement>> chunkResults, String contextCard) {
        long startTime = System.currentTimeMillis();

        // 机械拼接：按原始顺序合并所有需求
        List<Requirement> mergedRequirements = new ArrayList<>();
        int reqCounter = 1;
        
        for (List<Requirement> chunkReqs : chunkResults) {
            for (Requirement req : chunkReqs) {
                // 生成唯一ID
                String reqId = "REQ-" + String.format("%04d", reqCounter++);
                req.setRequirementId(reqId);
                mergedRequirements.add(req);
            }
        }

        // 冲突检测（规则驱动）
        List<Conflict> conflicts = detectConflicts(mergedRequirements);

        // 全局约束校验：检查需求是否违反全局约束
        List<Conflict> globalConstraintConflicts = validateGlobalConstraints(mergedRequirements, contextCard);
        conflicts.addAll(globalConstraintConflicts);

        return Stage3Result.builder()
                .mergedRequirements(mergedRequirements)
                .conflicts(conflicts)
                .executionTime(System.currentTimeMillis() - startTime)
                .build();
    }

    private List<Conflict> validateGlobalConstraints(List<Requirement> requirements, String contextCard) {
        List<Conflict> conflicts = new ArrayList<>();
        
        // 提取全局约束中的参数值
        Map<String, String> globalParams = extractGlobalParams(contextCard);
        
        for (Requirement req : requirements) {
            String description = req.getDescription();
            
            // 检查数值参数是否与全局约束冲突
            for (Map.Entry<String, String> entry : globalParams.entrySet()) {
                String paramKey = entry.getKey();
                String globalValue = entry.getValue();
                
                // 如果需求描述中包含该参数但值不同，标记为冲突
                if (description.contains(paramKey) && !description.contains(globalValue)) {
                    conflicts.add(Conflict.builder()
                            .conflictId("GLOBAL-CONFLICT-" + String.format("%03d", conflicts.size() + 1))
                            .description("需求 " + req.getRequirementId() + " 与全局约束冲突：" + paramKey + "，全局约束值为 " + globalValue)
                            .conflictingRequirementIds(Collections.singletonList(req.getRequirementId()))
                            .conflictingValues(Arrays.asList("需求值", globalValue))
                            .build());
                }
            }
            
            // 检查是否缺少必要的全局引用
            if (req.getGlobalRef() == null || req.getGlobalRef().isEmpty()) {
                // 检查是否存在应该引用全局约束的关键词
                boolean shouldReference = false;
                String[] constraintKeywords = {"必须", "不得", "禁止", "时钟", "频率", "周期", "响应时间", "截止时间", "优先级"};
                for (String keyword : constraintKeywords) {
                    if (description.contains(keyword)) {
                        shouldReference = true;
                        break;
                    }
                }
                if (shouldReference) {
                    conflicts.add(Conflict.builder()
                            .conflictId("GLOBAL-REF-MISSING-" + String.format("%03d", conflicts.size() + 1))
                            .description("需求 " + req.getRequirementId() + " 包含全局约束相关关键词但未引用全局锚点")
                            .conflictingRequirementIds(Collections.singletonList(req.getRequirementId()))
                            .conflictingValues(Collections.emptyList())
                            .build());
                }
            }
        }
        
        return conflicts;
    }

    private Map<String, String> extractGlobalParams(String contextCard) {
        Map<String, String> params = new HashMap<>();
        
        // 提取时钟频率
        Pattern clockPattern = Pattern.compile("PARAM-\\d{3} \\| .*?(\\d+\\.?\\d*\\s*[kKMG]?[Hh]z)", Pattern.CASE_INSENSITIVE);
        Matcher clockMatcher = clockPattern.matcher(contextCard);
        while (clockMatcher.find()) {
            params.put("时钟频率", clockMatcher.group(1));
        }
        
        // 提取周期
        Pattern periodPattern = Pattern.compile("PARAM-\\d{3} \\| .*?(\\d+\\.?\\d*\\s*(ms|毫秒|Hz))");
        Matcher periodMatcher = periodPattern.matcher(contextCard);
        while (periodMatcher.find()) {
            params.put("周期", periodMatcher.group(1));
        }
        
        // 提取截止时间/响应时间
        Pattern deadlinePattern = Pattern.compile("PARAM-\\d{3} \\| .*?(\\d+\\.?\\d*\\s*(ms|微秒|毫秒|秒))");
        Matcher deadlineMatcher = deadlinePattern.matcher(contextCard);
        while (deadlineMatcher.find()) {
            params.put("响应时间", deadlineMatcher.group(1));
        }
        
        // 提取优先级
        Pattern priorityPattern = Pattern.compile("PARAM-\\d{3} \\| .*?优先级.*?([高H中M低L])", Pattern.CASE_INSENSITIVE);
        Matcher priorityMatcher = priorityPattern.matcher(contextCard);
        while (priorityMatcher.find()) {
            params.put("优先级", priorityMatcher.group(1));
        }
        
        return params;
    }

    private List<Conflict> detectConflicts(List<Requirement> requirements) {
        List<Conflict> conflicts = new ArrayList<>();

        // 检查所有需求之间的数值约束冲突
        List<String> allConstraints = new ArrayList<>();
        List<String> allReqIds = new ArrayList<>();
        
        for (Requirement req : requirements) {
            Pattern numPattern = Pattern.compile("(≤|<|≥|>|==|=)\\s*(\\d+\\.?\\d*\\s*(ms|μs|ns|秒|MHz|GHz|MB|GB|Hz))");
            Matcher matcher = numPattern.matcher(req.getDescription());
            while (matcher.find()) {
                allConstraints.add(matcher.group(0));
                allReqIds.add(req.getRequirementId());
            }
        }

        // 检查是否存在矛盾
        if (allConstraints.size() >= 2 && hasContradiction(allConstraints)) {
            conflicts.add(Conflict.builder()
                    .conflictId("CONFLICT-" + String.format("%03d", conflicts.size() + 1))
                    .description("检测到数值约束冲突")
                    .conflictingRequirementIds(allReqIds)
                    .conflictingValues(allConstraints)
                    .build());
        }

        return conflicts;
    }

    private boolean hasContradiction(List<String> constraints) {
        // 简单的矛盾检测：检查是否同时存在"≤ X"和"> X"等情况
        for (int i = 0; i < constraints.size(); i++) {
            for (int j = i + 1; j < constraints.size(); j++) {
                String c1 = constraints.get(i);
                String c2 = constraints.get(j);
                
                // 提取操作符和数值
                Pattern pattern = Pattern.compile("(≤|<|≥|>|==|=)\\s*(\\d+\\.?\\d*)");
                Matcher m1 = pattern.matcher(c1);
                Matcher m2 = pattern.matcher(c2);
                
                if (m1.find() && m2.find()) {
                    String op1 = m1.group(1);
                    double val1 = Double.parseDouble(m1.group(2));
                    String op2 = m2.group(1);
                    double val2 = Double.parseDouble(m2.group(2));
                    
                    // 检查矛盾
                    if ((op1.equals("≤") || op1.equals("<")) && (op2.equals("≥") || op2.equals(">")) && val1 < val2) {
                        return true;
                    }
                    if ((op1.equals("≥") || op1.equals(">")) && (op2.equals("≤") || op2.equals("<")) && val1 > val2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<Requirement> parseRequirements(String response) throws Exception {
        String jsonContent = extractJson(response);
        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            throw new IllegalArgumentException("无法从响应中提取JSON内容");
        }
        return objectMapper.readValue(jsonContent, new TypeReference<List<Requirement>>() {});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String response) throws Exception {
        String jsonContent = extractJson(response);
        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return objectMapper.readValue(jsonContent, new TypeReference<List<Map<String, Object>>>() {});
    }

    private String extractJson(String response) {
        int startIndex = response.indexOf('[');
        int endIndex = response.lastIndexOf(']');

        if (startIndex >= 0 && endIndex > startIndex) {
            return response.substring(startIndex, endIndex + 1);
        }

        startIndex = response.indexOf('{');
        endIndex = response.lastIndexOf('}');

        if (startIndex >= 0 && endIndex > startIndex) {
            return "[" + response.substring(startIndex, endIndex + 1) + "]";
        }

        return null;
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }
}
