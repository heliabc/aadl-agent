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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    @Value("${agent.requirement.chunk-size:2500}")
    private int chunkSize;

    @Value("${agent.requirement.overlap-percent:15}")
    private int overlapPercent;

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

        RequirementAnalysisResult analysisResult = RequirementAnalysisResult.builder()
                .rawInput(requirementDoc)
                .build();

        try {
            // 阶段0：预处理——抽离"全局锚点"
            log.info("\n\n========== 阶段0：预处理——抽离全局锚点 ==========");
            Stage0Result stage0 = executeStage0(requirementDoc, llmClient);
            analysisResult.setStage0(stage0);
            log.info("阶段0完成，耗时: {}ms，提取锚点: {} 个", stage0.getExecutionTime(), 
                    stage0.getAnchors() != null ? stage0.getAnchors().size() : 0);
            log.info("全局上下文卡片:\n{}", stage0.getContextCard());

            if (input.isCancelled()) {
                log.info("任务已取消，RequirementAgent停止执行");
                return AgentOutput.cancelled(input.getSessionId());
            }

            // 阶段1：分层分块——注入全局视野
            log.info("\n\n========== 阶段1：分层分块——注入全局视野 ==========");
            Stage1Result stage1 = executeStage1(requirementDoc, stage0.getContextCard());
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
            Stage3Result stage3 = executeStage3(stage2.getChunkResults(), stage0.getAnchors());
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
        String name;
        Pattern pattern;
        boolean isParameter;
        String category;
        String anchorType;

        PatternConfig(String name, String regex, boolean isParameter, String category, String anchorType) {
            this.name = name;
            this.pattern = Pattern.compile(regex);
            this.isParameter = isParameter;
            this.category = category;
            this.anchorType = anchorType;
        }
    }

    private static final List<PatternConfig> PATTERNS = Arrays.asList(
            new PatternConfig("clock", "(\\d+\\.?\\d*)\\s*([kKMG]?[Hh]z)", true, "时钟频率", "PARAMETER"),
            new PatternConfig("time", "(\\d+\\.?\\d*)\\s*(毫秒|微秒|纳秒|秒|ms|us|μs|ns|s)", true, "时间参数", "PARAMETER"),
            new PatternConfig("memory", "(\\d+\\.?\\d*)\\s*([kKMG]?[Bb](yte)?)", true, "内存大小", "PARAMETER"),
            new PatternConfig("baud", "(\\d+\\.?\\d*)\\s*([kKMG]?[Bb]ps)", true, "波特率", "PARAMETER"),
            new PatternConfig("deadline", "(截止|响应|执行|反应)时间\\s*[:：=]?\\s*(\\d+\\.?\\d*)\\s*(ms|us|μs|ns|s|毫秒|微秒)", true, "截止时间", "PARAMETER"),
            new PatternConfig("period", "(周期|period)\\s*[:：=]?\\s*(\\d+\\.?\\d*)\\s*(ms|毫秒|Hz)", true, "周期", "PARAMETER"),
            new PatternConfig("jitter", "(抖动|jitter)\\s*[:：=]?\\s*(不超过|≤|<)?\\s*(\\d+\\.?\\d*)\\s*(ms|us|μs)", true, "抖动", "PARAMETER"),
            new PatternConfig("priority", "(优先级|priority)\\s*[:：=]?\\s*(\\d+|[高H中M低L])", true, "优先级", "PARAMETER"),
            new PatternConfig("abbr", "([A-Z]{2,6})\\s*[（(]\\s*([^）)]{1,30})\\s*[）)]", false, "缩写", "TERMINOLOGY"),
            new PatternConfig("iface", "(CAN|UART|SPI|I2C|I2S|GPIO|PWM|ADC|DAC|USB|Ethernet|PCIe?|SDIO|FlexRay|LIN|RS232|RS485)\\s*(\\d*)", false, "接口协议", "INTERFACE"),
            new PatternConfig("safety", "(DAL-[A-E]|ASIL\\s*[A-D]|SIL\\s*[1-4])", false, "安全等级", "CONSTRAINT"),
            new PatternConfig("assume", "(假设|前提|假定)\\s*[:：]?\\s*(.{10,200}?)(?=[。；！\\n]|$)", false, "假设前提", "ASSUMPTION"),
            new PatternConfig("limit", "(不超过|不低于|≥|≤|max|min|最大|最小)\\s*[:：]?\\s*(\\d+\\.?\\d*)\\s*(ms|us|MHz|KB|%)", true, "限制条件", "CONSTRAINT"),
            new PatternConfig("constraint", "(必须|不得|禁止|严禁|应当|不应|务必)\\s+(.{1,50}?)(?=[。；！\\n]|$)", false, "约束条件", "CONSTRAINT")
    );

    private Stage0Result executeStage0(String document, LlmClient llmClient) {
        long startTime = System.currentTimeMillis();
        List<GlobalAnchor> anchors = new ArrayList<>();

        // 完全正则匹配提取全局锚点
        int idCounter = 1;
        for (PatternConfig config : PATTERNS) {
            Matcher matcher = config.pattern.matcher(document);
            while (matcher.find()) {
                String content = matcher.group(0).trim();
                String anchorId = config.isParameter ? "PARAM-" + String.format("%03d", idCounter++) 
                                                     : getAnchorIdByType(config.anchorType, idCounter++);
                
                anchors.add(GlobalAnchor.builder()
                        .anchorId(anchorId)
                        .anchorType(config.anchorType)
                        .content(content)
                        .source("正则提取")
                        .category(config.category)
                        .build());
            }
        }

        // 生成全局上下文卡片（压缩至200-300字）
        String contextCard = buildContextCard(anchors);

        return Stage0Result.builder()
                .anchors(anchors)
                .contextCard(contextCard)
                .executionTime(System.currentTimeMillis() - startTime)
                .build();
    }

    private String getAnchorIdByType(String type, int counter) {
        switch (type) {
            case "TERMINOLOGY":
                return "TERM-" + String.format("%03d", counter);
            case "CONSTRAINT":
                return "CONST-" + String.format("%03d", counter);
            case "INTERFACE":
                return "IFACE-" + String.format("%03d", counter);
            case "ASSUMPTION":
                return "ASSUMP-" + String.format("%03d", counter);
            default:
                return "OTHER-" + String.format("%03d", counter);
        }
    }

    private String buildContextCard(List<GlobalAnchor> anchors) {
        StringBuilder card = new StringBuilder();
        card.append("【全局上下文卡片】\n");

        Map<String, List<GlobalAnchor>> grouped = anchors.stream()
                .collect(Collectors.groupingBy(GlobalAnchor::getCategory));

        for (Map.Entry<String, List<GlobalAnchor>> entry : grouped.entrySet()) {
            card.append("- ").append(entry.getKey()).append(":\n");
            for (GlobalAnchor anchor : entry.getValue()) {
                card.append("  * ").append(anchor.getAnchorId()).append(": ").append(anchor.getContent()).append("\n");
            }
        }

        // 压缩至200-300字
        String result = card.toString();
        if (result.length() > 300) {
            result = result.substring(0, 300) + "...\n[注：完整锚点见阶段0输出]";
        }
        return result;
    }

    private Stage1Result executeStage1(String document, String contextCard) {
        long startTime = System.currentTimeMillis();
        List<DocumentChunk> chunks = new ArrayList<>();

        // 直接按字符长度分割
        int chunkId = 1;
        int currentPos = 0;
        int overlapSize = (int) (chunkSize * overlapPercent / 100.0);
        int docLength = document.length();

        while (currentPos < docLength) {
            // 确定分块结束位置
            int endPos = Math.min(currentPos + chunkSize, docLength);
            
            // 尝试在句末分割，避免截断句子
            if (endPos < docLength) {
                // 向前查找最近的句末标点
                int lastPunctuation = -1;
                for (int i = endPos; i > Math.max(currentPos, endPos - 200); i--) {
                    char c = document.charAt(i);
                    if (c == '。' || c == '！' || c == '？' || c == ';' || c == '\n' || c == '\r') {
                        lastPunctuation = i;
                        break;
                    }
                }
                if (lastPunctuation > currentPos) {
                    endPos = lastPunctuation + 1;
                }
            }

            // 提取分块内容
            String chunkContent = document.substring(currentPos, endPos);
            
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

            // 计算下一个分块的起始位置（带重叠）
            int stepSize = endPos - currentPos;
            int overlapStep = (int) (stepSize * overlapPercent / 100.0);
            currentPos = endPos - overlapStep;
            
            // 防止无限循环
            if (currentPos >= endPos) {
                currentPos = endPos;
            }

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

    private Stage3Result executeStage3(List<List<Requirement>> chunkResults, List<GlobalAnchor> anchors) {
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
        List<Conflict> conflicts = detectConflicts(mergedRequirements, anchors);

        return Stage3Result.builder()
                .mergedRequirements(mergedRequirements)
                .conflicts(conflicts)
                .executionTime(System.currentTimeMillis() - startTime)
                .build();
    }

    private List<Conflict> detectConflicts(List<Requirement> requirements, List<GlobalAnchor> anchors) {
        List<Conflict> conflicts = new ArrayList<>();

        // 按全局锚点分组检查
        Map<String, List<Requirement>> groupedByAnchor = new HashMap<>();
        for (Requirement req : requirements) {
            if (req.getGlobalRef() != null) {
                for (String anchorId : req.getGlobalRef()) {
                    groupedByAnchor.computeIfAbsent(anchorId, k -> new ArrayList<>()).add(req);
                }
            }
        }

        // 检查同一锚点下的矛盾约束
        for (Map.Entry<String, List<Requirement>> entry : groupedByAnchor.entrySet()) {
            String anchorId = entry.getKey();
            List<Requirement> reqs = entry.getValue();
            
            if (reqs.size() >= 2) {
                // 检查数值约束冲突
                List<String> conflictingValues = new ArrayList<>();
                List<String> conflictingIds = new ArrayList<>();
                
                for (Requirement req : reqs) {
                    // 提取描述中的数值约束
                    Pattern numPattern = Pattern.compile("(≤|<|≥|>|==|=)\\s*(\\d+\\.?\\d*\\s*(ms|μs|ns|秒|MHz|GHz|MB|GB|Hz))");
                    Matcher matcher = numPattern.matcher(req.getDescription());
                    while (matcher.find()) {
                        String constraint = matcher.group(0);
                        conflictingValues.add(constraint);
                        conflictingIds.add(req.getRequirementId());
                    }
                }

                if (conflictingValues.size() >= 2) {
                    // 检查是否存在矛盾
                    if (hasContradiction(conflictingValues)) {
                        conflicts.add(Conflict.builder()
                                .conflictId("CONFLICT-" + String.format("%03d", conflicts.size() + 1))
                                .anchorId(anchorId)
                                .description("对锚点 " + anchorId + " 存在矛盾约束")
                                .conflictingRequirementIds(conflictingIds)
                                .conflictingValues(conflictingValues)
                                .build());
                    }
                }
            }
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
