package com.example.aadlagent.memory;

import com.example.aadlagent.rag.KnowledgeBaseManager;
import com.example.aadlagent.rag.model.ErrorCorrection;
import com.example.aadlagent.util.AadlReferenceValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 错误记忆服务（长期记忆的一部分）
 *
 * 负责：
 * 1. 从修复前后的代码和验证结果中提取错误模式
 * 2. 生成错误指纹用于去重
 * 3. 自动沉淀到知识库
 * 4. 修复时召回相似错误的历史解法
 */
@Slf4j
@Service
public class ErrorMemoryService {

    private final KnowledgeBaseManager knowledgeBaseManager;

    /** 知识库类型（独立的错误记忆库，对应 knowledge/error_memory.json） */
    private static final String KB_TYPE = "error_memory";

    /** 内存中的错误指纹缓存（加速去重检查） */
    private final Set<String> fingerprintCache = new HashSet<>();

    public ErrorMemoryService(KnowledgeBaseManager knowledgeBaseManager) {
        this.knowledgeBaseManager = knowledgeBaseManager;
        // 初始化时加载已有指纹到缓存
        loadFingerprintCache();
    }

    /**
     * 加载已有错误修正的指纹到内存缓存
     */
    private void loadFingerprintCache() {
        try {
            List<ErrorCorrection> existing = knowledgeBaseManager.getErrorCorrections(KB_TYPE);
            if (existing != null) {
                for (ErrorCorrection ec : existing) {
                    String fp = generateFingerprint(ec.getErrorType(), ec.getErrorDescription());
                    if (fp != null) {
                        fingerprintCache.add(fp);
                    }
                }
                log.info("Loaded {} error correction fingerprints into cache", fingerprintCache.size());
            }
        } catch (Exception e) {
            log.warn("Failed to load fingerprint cache: {}", e.getMessage());
        }
    }

    /**
     * 从修复结果中提取并归档错误修正
     *
     * @param originalCode 修复前的代码
     * @param fixedCode    修复后的代码
     * @param beforeResult 修复前的验证结果
     * @param afterResult  修复后的验证结果
     * @return 成功归档的数量
     */
    public int archiveErrorFixes(String originalCode, String fixedCode,
                                  AadlReferenceValidator.ValidationResult beforeResult,
                                  AadlReferenceValidator.ValidationResult afterResult) {
        if (beforeResult == null || afterResult == null) {
            return 0;
        }

        List<String> beforeErrors = beforeResult.errors;
        List<String> afterErrors = afterResult.errors;
        List<String> suggestions = beforeResult.suggestions;

        if (beforeErrors == null || beforeErrors.isEmpty()) {
            return 0;
        }

        // 找出被修复的错误（修复前有、修复后没有的）
        Set<String> afterErrorSet = new HashSet<>(afterErrors != null ? afterErrors : Collections.emptyList());
        int archivedCount = 0;

        for (int i = 0; i < beforeErrors.size(); i++) {
            String error = beforeErrors.get(i);
            if (!afterErrorSet.contains(error)) {
                // 这个错误被修复了
                String suggestion = (suggestions != null && i < suggestions.size()) ? suggestions.get(i) : "";

                boolean archived = archiveSingleErrorFix(originalCode, fixedCode, error, suggestion);
                if (archived) {
                    archivedCount++;
                }
            }
        }

        if (archivedCount > 0) {
            log.info("Archived {} new error corrections to long-term memory", archivedCount);
        }

        return archivedCount;
    }

    /**
     * 归档单个错误修正
     *
     * @return 是否成功归档（true = 新增，false = 已存在或失败）
     */
    public boolean archiveSingleErrorFix(String originalCode, String fixedCode,
                                          String errorMsg, String suggestion) {
        try {
            // 1. 提取错误类型
            String errorType = extractErrorType(errorMsg);

            // 2. 生成错误指纹
            String fingerprint = generateFingerprint(errorType, errorMsg);
            if (fingerprint == null) {
                return false;
            }

            // 3. 去重检查
            if (fingerprintCache.contains(fingerprint)) {
                log.debug("Error correction already exists, skipping: {}", errorType);
                return false;
            }

            // 4. 提取修复前后的代码片段
            String errorContent = extractRelevantSnippet(originalCode, errorMsg, 15);
            String correctContent = extractRelevantSnippet(fixedCode, errorMsg, 15);

            // 如果提取不到代码片段，跳过（质量不够）
            if (errorContent == null || errorContent.trim().isEmpty()) {
                return false;
            }

            // 5. 生成标题
            String title = generateTitle(errorType, errorMsg);

            // 6. 提取标签
            List<String> tags = extractTags(errorType, errorMsg);

            // 7. 构建 ErrorCorrection 对象
            ErrorCorrection ec = ErrorCorrection.builder()
                    .id(null) // 让 KnowledgeBaseManager 自动生成
                    .title(title)
                    .errorType(errorType)
                    .errorContent(errorContent)
                    .errorDescription(errorMsg)
                    .correctContent(correctContent != null ? correctContent : "")
                    .correctionExplanation("") // 自动归档的没有解释，可后续人工补充
                    .suggestion(suggestion != null ? suggestion : "")
                    .tags(tags)
                    .build();

            // 8. 入库
            knowledgeBaseManager.addErrorCorrection(KB_TYPE, ec);
            fingerprintCache.add(fingerprint);

            log.info("Archived error correction: {} ({})", title, errorType);
            return true;

        } catch (Exception e) {
            log.warn("Failed to archive error correction: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从错误消息中提取错误类型分类
     */
    public String extractErrorType(String errorMsg) {
        if (errorMsg == null || errorMsg.trim().isEmpty()) {
            return "unknown";
        }

        String msg = errorMsg.toLowerCase();

        // 按优先级匹配（更具体的优先）
        if (msg.contains("缺少 end") || msg.contains("missing end") || msg.contains("未闭合")) {
            return "missing_end";
        }
        if (msg.contains("subcomponents") && msg.contains("未声明") || msg.contains("undefined") && msg.contains("subcomponent")) {
            return "missing_subcomponent_decl";
        }
        if (msg.contains("引用") && msg.contains("未声明") || msg.contains("reference") && msg.contains("not found")) {
            return "missing_reference";
        }
        if (msg.contains("feature") && (msg.contains("缺失") || msg.contains("not found") || msg.contains("不存在"))) {
            return "missing_feature";
        }
        if (msg.contains("连接") && (msg.contains("方向") || msg.contains("direction"))) {
            return "connection_direction";
        }
        if (msg.contains("类型") && (msg.contains("不匹配") || msg.contains("mismatch"))) {
            return "type_mismatch";
        }
        if (msg.contains("端口") && (msg.contains("重复") || msg.contains("duplicate"))) {
            return "duplicate_port";
        }
        if (msg.contains("property") || msg.contains("属性")) {
            return "property_issue";
        }
        if (msg.contains("语法") || msg.contains("syntax")) {
            return "syntax_error";
        }
        if (msg.contains("层级") || msg.contains("hierarchy") || msg.contains("嵌套")) {
            return "hierarchy_violation";
        }

        return "other";
    }

    /**
     * 生成错误指纹（用于去重）
     *
     * 指纹 = 错误类型 + 关键信息的标准化（去掉具体组件名、变量名等）
     */
    public String generateFingerprint(String errorType, String errorMsg) {
        if (errorType == null || errorMsg == null) {
            return null;
        }

        // 对错误消息做标准化：
        // 1. 转小写
        // 2. 去掉具体的标识符名称（组件名、变量名等）
        // 3. 保留错误类型关键词
        String normalized = errorMsg.toLowerCase();

        // 移除引号中的标识符
        normalized = normalized.replaceAll("['\"][^'\"]+['\"]", "IDENT");
        // 移除代码标识符（以字母开头的单词序列）
        normalized = normalized.replaceAll("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b", "NAME");
        // 移除数字
        normalized = normalized.replaceAll("\\d+", "NUM");
        // 压缩空白
        normalized = normalized.replaceAll("\\s+", " ");

        return errorType + "|" + normalized.trim().hashCode();
    }

    /**
     * 生成错误标题
     */
    private String generateTitle(String errorType, String errorMsg) {
        // 从错误消息中提取前30个字符作为标题的一部分
        String shortMsg = errorMsg.length() > 40 ? errorMsg.substring(0, 40) + "..." : errorMsg;
        return errorType + " - " + shortMsg;
    }

    /**
     * 提取标签
     */
    private List<String> extractTags(String errorType, String errorMsg) {
        List<String> tags = new ArrayList<>();
        tags.add(errorType);

        String msg = errorMsg.toLowerCase();

        // 组件类型标签
        if (msg.contains("thread")) tags.add("thread");
        if (msg.contains("process")) tags.add("process");
        if (msg.contains("system")) tags.add("system");
        if (msg.contains("processor")) tags.add("processor");
        if (msg.contains("device")) tags.add("device");
        if (msg.contains("memory")) tags.add("memory");
        if (msg.contains("bus")) tags.add("bus");
        if (msg.contains("data")) tags.add("data");

        // 严重程度标签（根据错误类型判断）
        if (errorType.contains("missing")) tags.add("error");
        if (errorType.contains("syntax")) tags.add("syntax");
        if (errorType.contains("hierarchy")) tags.add("structure");

        return tags;
    }

    /**
     * 从代码中提取相关的代码片段（围绕错误位置）
     *
     * @param code     完整代码
     * @param errorMsg 错误消息
     * @param context  前后各取多少行
     * @return 相关代码片段
     */
    public String extractRelevantSnippet(String code, String errorMsg, int context) {
        if (code == null || errorMsg == null) {
            return "";
        }

        String[] lines = code.split("\n");
        if (lines.length == 0) return "";

        // 尝试从错误消息中提取行号
        int lineNumber = extractLineNumber(errorMsg);

        if (lineNumber > 0 && lineNumber <= lines.length) {
            // 找到具体行号，按行号提取
            int start = Math.max(0, lineNumber - context - 1);
            int end = Math.min(lines.length, lineNumber + context);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString().trim();
        }

        // 没有行号，尝试通过关键词匹配定位
        String keyword = extractKeyword(errorMsg);
        if (keyword != null) {
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].toLowerCase().contains(keyword.toLowerCase())) {
                    int start = Math.max(0, i - context);
                    int end = Math.min(lines.length, i + context + 1);
                    StringBuilder sb = new StringBuilder();
                    for (int j = start; j < end; j++) {
                        sb.append(lines[j]).append("\n");
                    }
                    return sb.toString().trim();
                }
            }
        }

        // 实在找不到，返回前30行
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(context * 2, lines.length); i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 从错误消息中提取行号
     */
    private int extractLineNumber(String errorMsg) {
        Pattern pattern = Pattern.compile("行\\s*(\\d+)|line\\s*(\\d+)|L(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(errorMsg);
        if (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                if (matcher.group(i) != null) {
                    try {
                        return Integer.parseInt(matcher.group(i));
                    } catch (NumberFormatException e) {
                        // continue
                    }
                }
            }
        }
        return -1;
    }

    /**
     * 从错误消息中提取用于定位代码的关键词
     */
    private String extractKeyword(String errorMsg) {
        // 尝试提取引号中的内容
        Pattern pattern = Pattern.compile("['\"]([^'\"]+)['\"]");
        Matcher matcher = pattern.matcher(errorMsg);
        if (matcher.find()) {
            String candidate = matcher.group(1);
            // 只保留看起来像标识符的（不是纯数字、不是通用词）
            if (candidate.length() > 2 && !candidate.matches("\\d+")) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 获取错误记忆库的大小
     */
    public int getErrorCorrectionCount() {
        return fingerprintCache.size();
    }

    /**
     * 检查某个错误是否已有记录
     */
    public boolean hasErrorCorrection(String errorType, String errorMsg) {
        String fp = generateFingerprint(errorType, errorMsg);
        return fp != null && fingerprintCache.contains(fp);
    }

    /**
     * 根据错误信息检索相似的历史错误修正案例
     *
     * @param errorText   当前的错误信息（可以是多条错误）
     * @param maxResults  最多返回多少个
     * @return 相似错误修正列表（按相关度降序）
     */
    public List<ErrorCorrection> retrieveSimilarFixes(String errorText, int maxResults) {
        if (errorText == null || errorText.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<ErrorCorrection> all = knowledgeBaseManager.getErrorCorrections(KB_TYPE);
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }

        String lowerError = errorText.toLowerCase();

        // 计算每个错误修正的匹配分数
        List<Map.Entry<ErrorCorrection, Integer>> scored = new ArrayList<>();
        for (ErrorCorrection ec : all) {
            int score = calculateRelevanceScore(ec, lowerError);
            if (score > 0) {
                scored.add(new AbstractMap.SimpleEntry<>(ec, score));
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
     * 将检索到的错误修正格式化为提示词文本
     */
    public String formatForPrompt(List<ErrorCorrection> fixes) {
        if (fixes == null || fixes.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【历史错误修复经验】\n");
        sb.append("以下是之前遇到过的相似错误及其修复方案，供你参考：\n\n");

        for (int i = 0; i < fixes.size(); i++) {
            ErrorCorrection ec = fixes.get(i);
            sb.append("--- 案例 ").append(i + 1).append(": ").append(ec.getTitle()).append(" ---\n");
            sb.append("错误类型: ").append(ec.getErrorType()).append("\n");
            sb.append("错误描述: ").append(ec.getErrorDescription() != null ? ec.getErrorDescription() : "").append("\n");
            if (ec.getErrorContent() != null && !ec.getErrorContent().isEmpty()) {
                sb.append("错误代码示例:\n```aadl\n").append(ec.getErrorContent()).append("\n```\n");
            }
            if (ec.getCorrectContent() != null && !ec.getCorrectContent().isEmpty()) {
                sb.append("正确写法:\n```aadl\n").append(ec.getCorrectContent()).append("\n```\n");
            }
            if (ec.getSuggestion() != null && !ec.getSuggestion().isEmpty()) {
                sb.append("修复建议: ").append(ec.getSuggestion()).append("\n");
            }
            if (ec.getCorrectionExplanation() != null && !ec.getCorrectionExplanation().isEmpty()) {
                sb.append("修复说明: ").append(ec.getCorrectionExplanation()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 计算错误修正与当前错误的相关度分数
     */
    private int calculateRelevanceScore(ErrorCorrection ec, String lowerErrorText) {
        int score = 0;

        // 错误类型完全匹配（高分）
        String errorType = ec.getErrorType() != null ? ec.getErrorType().toLowerCase() : "";
        if (!errorType.isEmpty()) {
            // 根据错误类型提取关键词，看错误文本中是否包含
            if (lowerErrorText.contains(errorType)) {
                score += 20;
            }
        }

        // 错误描述中的关键词匹配
        String desc = ec.getErrorDescription() != null ? ec.getErrorDescription().toLowerCase() : "";
        if (!desc.isEmpty()) {
            // 提取错误描述中的关键词（2字以上的中文词、英文单词）
            Set<String> keywords = extractKeywordsFromText(desc);
            for (String kw : keywords) {
                if (lowerErrorText.contains(kw)) {
                    score += 3;
                }
            }
        }

        // 标签匹配
        if (ec.getTags() != null) {
            for (String tag : ec.getTags()) {
                if (lowerErrorText.contains(tag.toLowerCase())) {
                    score += 5;
                }
            }
        }

        return score;
    }

    /**
     * 从文本中提取关键词（简单的词频提取）
     */
    private Set<String> extractKeywordsFromText(String text) {
        Set<String> keywords = new HashSet<>();
        if (text == null) return keywords;

        // 提取英文单词（3个字母以上）
        Pattern wordPattern = Pattern.compile("\\b[a-zA-Z]{3,}\\b");
        Matcher matcher = wordPattern.matcher(text);
        while (matcher.find()) {
            keywords.add(matcher.group());
        }

        // 提取常见的中文关键词组合（2-4字）
        String[] cnKeywords = {
                "未声明", "未定义", "未找到", "不存在", "不匹配",
                "缺少", "缺失", "重复", "错误", "无效",
                "引用", "连接", "端口", "类型", "属性",
                "语法", "层级", "嵌套", "方向", "声明"
        };
        for (String kw : cnKeywords) {
            if (text.contains(kw)) {
                keywords.add(kw);
            }
        }

        return keywords;
    }
}
