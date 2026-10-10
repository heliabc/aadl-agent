package com.example.aadlagent.agent.tool;

import com.example.aadlagent.memory.ErrorMemoryService;
import com.example.aadlagent.rag.model.ErrorCorrection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 错误记忆检索工具
 *
 * 从错误记忆库中检索与当前错误相似的历史修复案例，
 * 为 LLM 提供参考修复方案，实现修复经验的跨模型复用。
 */
@Slf4j
@Component
public class SearchErrorMemoryTool implements Tool {

    private static final String TOOL_NAME = "search_error_memory";

    private final ErrorMemoryService errorMemoryService;

    public SearchErrorMemoryTool(ErrorMemoryService errorMemoryService) {
        this.errorMemoryService = errorMemoryService;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "从错误记忆库中检索相似错误的历史修复案例和修复方案。" +
                "当遇到不确定如何修复的错误时，调用此工具获取历史修复经验作为参考。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> queryParam = new LinkedHashMap<>();
        queryParam.put("type", "string");
        queryParam.put("description", "错误描述或错误信息，可以是单个错误或多个错误的列表");
        props.put("error_query", queryParam);

        Map<String, Object> limitParam = new LinkedHashMap<>();
        limitParam.put("type", "integer");
        limitParam.put("description", "最多返回多少条相似案例，默认3条");
        props.put("limit", limitParam);

        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("error_query")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String errorQuery = (String) arguments.get("error_query");
        if (errorQuery == null || errorQuery.trim().isEmpty()) {
            return "错误：error_query 参数不能为空";
        }

        int limit = 3;
        Object limitObj = arguments.get("limit");
        if (limitObj instanceof Number) {
            limit = ((Number) limitObj).intValue();
        }

        try {
            List<ErrorCorrection> similarFixes = errorMemoryService.retrieveSimilarFixes(errorQuery, limit);

            if (similarFixes == null || similarFixes.isEmpty()) {
                return "未找到相似的历史错误修复案例。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== 相似错误修复案例（共 ").append(similarFixes.size()).append(" 条）===\n\n");

            for (int i = 0; i < similarFixes.size(); i++) {
                ErrorCorrection ec = similarFixes.get(i);
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

            log.info("SearchErrorMemoryTool: 找到 {} 条相似案例", similarFixes.size());
            return sb.toString();

        } catch (Exception e) {
            log.error("SearchErrorMemoryTool execution error: {}", e.getMessage(), e);
            return "错误记忆检索工具执行异常: " + e.getMessage();
        }
    }
}
