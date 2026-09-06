package com.example.aadlagent.memory;

import com.example.aadlagent.util.AadlReferenceValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 长期记忆服务 - 成功案例库
 *
 * 存储和管理历史成功生成的案例，用于：
 * 1. 新需求进来时，召回相似案例作为参考
 * 2. 积累领域知识
 *
 * 存储方式：JSON 文件（knowledge/success_cases.json）
 */
@Slf4j
@Service
public class LongTermMemoryService {

    @Value("${memory.long-term.storage-path:knowledge/success_cases.json}")
    private String storagePath;

    /** 内存中的案例索引 */
    private final Map<String, SuccessCase> caseIndex = new LinkedHashMap<>();

    private final ObjectMapper objectMapper;

    public LongTermMemoryService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        loadCases();
        log.info("Long-term memory initialized: {} success cases loaded", caseIndex.size());
    }

    /**
     * 从文件加载所有成功案例
     */
    private synchronized void loadCases() {
        try {
            Path path = Paths.get(storagePath);
            if (!Files.exists(path)) {
                log.info("Success cases file not found, starting with empty library: {}", storagePath);
                return;
            }

            String json = Files.readString(path);
            SuccessCase[] cases = objectMapper.readValue(json, SuccessCase[].class);
            for (SuccessCase c : cases) {
                if (c.getCaseId() != null) {
                    caseIndex.put(c.getCaseId(), c);
                }
            }
            log.info("Loaded {} success cases from {}", caseIndex.size(), storagePath);
        } catch (Exception e) {
            log.error("Failed to load success cases: {}", e.getMessage());
        }
    }

    /**
     * 保存所有案例到文件
     */
    private synchronized void saveCases() {
        try {
            Path path = Paths.get(storagePath);
            // 确保父目录存在
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }

            List<SuccessCase> cases = new ArrayList<>(caseIndex.values());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cases);
            Files.writeString(path, json);
            log.debug("Saved {} success cases to {}", cases.size(), storagePath);
        } catch (Exception e) {
            log.error("Failed to save success cases: {}", e.getMessage());
        }
    }

    /**
     * 添加成功案例
     *
     * @param requirementDoc 需求文档
     * @param aadlCode       生成的 AADL 代码
     * @param validator      验证器（用于计算质量分数）
     * @return 新案例的 ID
     */
    public String addSuccessCase(String requirementDoc, String aadlCode, AadlReferenceValidator validator) {
        if (requirementDoc == null || aadlCode == null ||
                requirementDoc.trim().isEmpty() || aadlCode.trim().isEmpty()) {
            return null;
        }

        // 生成案例ID
        String caseId = "CASE-" + UUID.randomUUID().toString().substring(0, 8);

        // 生成需求摘要（前200字）
        String summary = requirementDoc.length() > 200
                ? requirementDoc.substring(0, 200) + "..."
                : requirementDoc;

        // 计算质量分数（基于验证错误数量）
        int qualityScore = 100;
        int componentCount = 0;
        int connectionCount = 0;

        if (validator != null) {
            try {
                AadlReferenceValidator.ValidationResult result = validator.validateSyntax(aadlCode);
                int errorCount = result.errors != null ? result.errors.size() : 0;
                // 每1个错误扣2分，最低0分
                qualityScore = Math.max(0, 100 - errorCount * 2);

                // 统计组件和连接数量
                componentCount = countComponents(aadlCode);
                connectionCount = countConnections(aadlCode);
            } catch (Exception e) {
                log.warn("Failed to calculate quality score: {}", e.getMessage());
            }
        }

        // 自动提取标签
        List<String> domainTags = extractDomainTags(requirementDoc);
        List<String> techTags = extractTechTags(requirementDoc, aadlCode);

        // 生成标题
        String title = generateTitle(requirementDoc, componentCount);

        SuccessCase newCase = SuccessCase.builder()
                .caseId(caseId)
                .title(title)
                .requirementDoc(requirementDoc)
                .requirementSummary(summary)
                .aadlCode(aadlCode)
                .aadlCodeLength(aadlCode.length())
                .componentCount(componentCount)
                .connectionCount(connectionCount)
                .domainTags(domainTags)
                .techTags(techTags)
                .qualityScore(qualityScore)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        caseIndex.put(caseId, newCase);
        saveCases();

        log.info("Added success case: {} ({} components, quality={})",
                title, componentCount, qualityScore);

        return caseId;
    }

    /**
     * 根据需求文档召回相似的成功案例（基于关键词匹配）
     *
     * @param query     查询文本（需求文档或描述）
     * @param maxResults 最多返回多少个
     * @return 相似案例列表（按相似度降序）
     */
    public List<SuccessCase> findSimilarCases(String query, int maxResults) {
        if (query == null || query.trim().isEmpty() || caseIndex.isEmpty()) {
            return Collections.emptyList();
        }

        // 从查询中提取关键词
        Set<String> queryKeywords = extractKeywords(query);

        // 计算每个案例的匹配分数
        List<Map.Entry<SuccessCase, Integer>> scored = new ArrayList<>();
        for (SuccessCase c : caseIndex.values()) {
            int score = calculateMatchScore(c, queryKeywords);
            if (score > 0) {
                scored.add(new AbstractMap.SimpleEntry<>(c, score));
            }
        }

        // 按分数降序排序
        scored.sort((a, b) -> b.getValue() - a.getValue());

        // 取前 N 个
        return scored.stream()
                .limit(maxResults)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有成功案例
     */
    public List<SuccessCase> getAllCases() {
        return new ArrayList<>(caseIndex.values());
    }

    /**
     * 获取成功案例数量
     */
    public int getCaseCount() {
        return caseIndex.size();
    }

    /**
     * 删除指定案例
     */
    public boolean removeCase(String caseId) {
        if (caseIndex.remove(caseId) != null) {
            saveCases();
            log.info("Removed success case: {}", caseId);
            return true;
        }
        return false;
    }

    // ==================== 辅助方法 ====================

    /**
     * 从查询中提取关键词
     */
    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        if (text == null) return keywords;

        String lower = text.toLowerCase();

        // 领域关键词
        String[] domainKeywords = {
                "飞控", "航空", "航天", "无人机", "卫星", "火箭",
                "汽车", "自动驾驶", "车载", "动力", "制动",
                "工业", "控制", "机器人", "自动化",
                "医疗", "设备", "监护",
                "通信", "网络", "协议",
                "实时", "嵌入式", "分布式", "容错", "安全"
        };

        for (String kw : domainKeywords) {
            if (lower.contains(kw)) {
                keywords.add(kw);
            }
        }

        // 技术关键词（从 AADL 组件类型角度）
        String[] techKeywords = {
                "thread", "process", "processor", "device", "memory",
                "system", "bus", "data", "connection", "port",
                "线程", "进程", "处理器", "设备", "内存",
                "系统", "总线", "数据", "连接", "端口"
        };

        for (String kw : techKeywords) {
            if (lower.contains(kw)) {
                keywords.add(kw);
            }
        }

        return keywords;
    }

    /**
     * 计算案例与查询的匹配分数
     */
    private int calculateMatchScore(SuccessCase c, Set<String> queryKeywords) {
        int score = 0;

        // 匹配领域标签
        if (c.getDomainTags() != null) {
            for (String tag : c.getDomainTags()) {
                if (queryKeywords.contains(tag.toLowerCase())) {
                    score += 10;
                }
            }
        }

        // 匹配技术标签
        if (c.getTechTags() != null) {
            for (String tag : c.getTechTags()) {
                if (queryKeywords.contains(tag.toLowerCase())) {
                    score += 5;
                }
            }
        }

        // 标题和摘要中的关键词匹配
        String text = (c.getTitle() != null ? c.getTitle().toLowerCase() : "")
                + " " + (c.getRequirementSummary() != null ? c.getRequirementSummary().toLowerCase() : "");
        for (String kw : queryKeywords) {
            if (text.contains(kw)) {
                score += 3;
            }
        }

        // 质量分加成（质量高的案例排名靠前）
        score += c.getQualityScore() / 20; // 0-5 分的加成

        return score;
    }

    /**
     * 从需求文档中提取领域标签
     */
    private List<String> extractDomainTags(String requirementDoc) {
        List<String> tags = new ArrayList<>();
        if (requirementDoc == null) return tags;

        String lower = requirementDoc.toLowerCase();

        if (lower.contains("飞控") || lower.contains("航空") || lower.contains("航天")
                || lower.contains("无人机") || lower.contains("卫星")) {
            tags.add("航空航天");
        }
        if (lower.contains("汽车") || lower.contains("自动驾驶") || lower.contains("车载")
                || lower.contains("制动") || lower.contains("动力")) {
            tags.add("汽车电子");
        }
        if (lower.contains("工业") || lower.contains("机器人") || lower.contains("自动化")) {
            tags.add("工业控制");
        }
        if (lower.contains("医疗") || lower.contains("监护") || lower.contains("诊断")) {
            tags.add("医疗设备");
        }
        if (lower.contains("通信") || lower.contains("网络") || lower.contains("协议")) {
            tags.add("通信系统");
        }

        return tags;
    }

    /**
     * 从需求和代码中提取技术标签
     */
    private List<String> extractTechTags(String requirementDoc, String aadlCode) {
        List<String> tags = new ArrayList<>();
        String combined = (requirementDoc + " " + aadlCode).toLowerCase();

        if (combined.contains("实时") || combined.contains("real-time") || combined.contains("deadline")) {
            tags.add("实时系统");
        }
        if (combined.contains("分布式") || combined.contains("distributed")) {
            tags.add("分布式");
        }
        if (combined.contains("容错") || combined.contains("fault") || combined.contains("redundant")) {
            tags.add("容错");
        }
        if (combined.contains("安全") || combined.contains("safety") || combined.contains("security")) {
            tags.add("安全关键");
        }
        if (combined.contains("并发") || combined.contains("concurrent") || combined.contains("thread")) {
            tags.add("多线程");
        }

        return tags;
    }

    /**
     * 生成案例标题
     */
    private String generateTitle(String requirementDoc, int componentCount) {
        // 取需求文档的前 30 个字符作为标题基础
        String base = requirementDoc.trim().replaceAll("\\s+", " ");
        if (base.length() > 30) {
            base = base.substring(0, 30) + "...";
        }
        return base + " (" + componentCount + "组件)";
    }

    /**
     * 统计 AADL 代码中的组件数量
     */
    private int countComponents(String aadlCode) {
        int count = 0;
        String[] patterns = {"thread ", "process ", "system ", "processor ",
                "device ", "memory ", "bus ", "data "};
        for (String pattern : patterns) {
            int idx = 0;
            while ((idx = aadlCode.indexOf(pattern, idx)) != -1) {
                // 排除 implementation 行
                int lineEnd = aadlCode.indexOf('\n', idx);
                String line = lineEnd > 0 ? aadlCode.substring(idx, lineEnd) : aadlCode.substring(idx);
                if (!line.contains("implementation")) {
                    count++;
                }
                idx += pattern.length();
            }
        }
        return count;
    }

    /**
     * 统计连接数量
     */
    private int countConnections(String aadlCode) {
        int count = 0;
        int idx = 0;
        while ((idx = aadlCode.indexOf("connections", idx)) != -1) {
            count++;
            idx += "connections".length();
        }
        return count;
    }
}
