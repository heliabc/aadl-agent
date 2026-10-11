package com.example.aadlagent.agent.tool;

import com.example.aadlagent.rag.RagService;
import com.example.aadlagent.rag.model.Document;
import com.example.aadlagent.rag.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领域知识检索工具
 *
 * 从 AADL 领域知识库中检索相关的基础知识、示例和最佳实践，
 * 为 LLM 提供生成或修复 AADL 代码时的参考上下文。
 */
@Slf4j
@Component
public class SearchKnowledgeTool implements Tool {

    private static final String TOOL_NAME = "search_knowledge";

    private final RagService ragService;

    public SearchKnowledgeTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "从 AADL 领域知识库中检索相关的语法知识、代码示例和最佳实践。" +
                "当你不确定 AADL 语法、组件用法或需要参考示例时调用此工具。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> queryParam = new LinkedHashMap<>();
        queryParam.put("type", "string");
        queryParam.put("description", "检索查询，描述你想了解的 AADL 知识点或问题");
        props.put("query", queryParam);

        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("query")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String query = (String) arguments.get("query");
        if (query == null || query.trim().isEmpty()) {
            return "错误：query 参数不能为空";
        }

        // knowledge_domain 由 Agent 基类自动注入，LLM 无需感知
        String domain = "aadl";
        Object domainObj = arguments.get("_knowledge_domain");
        if (domainObj instanceof String && !((String) domainObj).trim().isEmpty()) {
            domain = (String) domainObj;
        }

        try {
            SearchResult result = ragService.search(query, domain);

            if (result == null || result.getDocuments() == null || result.getDocuments().isEmpty()) {
                return "未检索到相关的领域知识。";
            }

            List<Document> docs = result.getDocuments();

            StringBuilder sb = new StringBuilder();
            sb.append("=== 领域知识检索结果（共 ").append(docs.size()).append(" 条）===\n");
            sb.append("（查询改写: ").append(result.getRewrittenQuery()).append("）\n\n");

            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                sb.append("--- 知识 ").append(i + 1).append(" ---\n");
                if (doc.getTitle() != null && !doc.getTitle().isEmpty()) {
                    sb.append("标题: ").append(doc.getTitle()).append("\n");
                }
                if (doc.getCategory() != null && !doc.getCategory().isEmpty()) {
                    sb.append("分类: ").append(doc.getCategory()).append("\n");
                }
                if (doc.getSource() != null && !doc.getSource().isEmpty()) {
                    sb.append("来源: ").append(doc.getSource()).append("\n");
                }
                sb.append("内容:\n").append(doc.getContent() != null ? doc.getContent() : "").append("\n\n");
            }

            log.info("SearchKnowledgeTool: 找到 {} 条相关知识 (query: {})",
                    docs.size(), query.length() > 50 ? query.substring(0, 50) + "..." : query);

            return sb.toString();

        } catch (Exception e) {
            log.error("SearchKnowledgeTool execution error: {}", e.getMessage(), e);
            return "知识检索工具执行异常: " + e.getMessage();
        }
    }
}
