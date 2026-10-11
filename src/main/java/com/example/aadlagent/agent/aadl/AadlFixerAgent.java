package com.example.aadlagent.agent.aadl;

import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.agent.ReActAgent;
import com.example.aadlagent.agent.tool.CountAadlElementsTool;
import com.example.aadlagent.agent.tool.FormatAadlTool;
import com.example.aadlagent.agent.tool.SearchErrorMemoryTool;
import com.example.aadlagent.agent.tool.SearchKnowledgeTool;
import com.example.aadlagent.agent.tool.Tool;
import com.example.aadlagent.agent.tool.ValidateAadlTool;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AadlFixerAgent extends ReActAgent {

    private static final String AGENT_NAME = "AadlFixerAgent";

    private final ValidateAadlTool validateAadlTool;
    private final SearchKnowledgeTool searchKnowledgeTool;
    private final SearchErrorMemoryTool searchErrorMemoryTool;
    private final FormatAadlTool formatAadlTool;
    private final CountAadlElementsTool countAadlElementsTool;

    @Value("${agent.aadl-fixer.temperature:0.1}")
    private double temperature;

    @Value("${agent.aadl-fixer.max-tokens:16384}")
    private int maxTokens;

    public AadlFixerAgent(ModelService modelService,
                          ValidateAadlTool validateAadlTool,
                          SearchKnowledgeTool searchKnowledgeTool,
                          SearchErrorMemoryTool searchErrorMemoryTool,
                          FormatAadlTool formatAadlTool,
                          CountAadlElementsTool countAadlElementsTool) {
        super(modelService);
        this.validateAadlTool = validateAadlTool;
        this.searchKnowledgeTool = searchKnowledgeTool;
        this.searchErrorMemoryTool = searchErrorMemoryTool;
        this.formatAadlTool = formatAadlTool;
        this.countAadlElementsTool = countAadlElementsTool;
    }

    // ==================== ReActAgent 抽象方法实现 ====================

    @Override
    protected String getSystemPrompt() {
        return """
你是一位精通 AADL（Architecture Analysis & Design Language）的修复专家。
你的任务是修复 AADL 代码中的语法错误和引用问题，输出正确、可通过验证的 AADL 代码。

## 工作方式（ReAct 模式）

你需要遵循"思考→行动→观察"的循环，直到代码中没有错误为止：

1. **Thought（思考）**：分析当前 AADL 代码中的错误，思考修复方案
2. **Action（行动）**：根据需要调用工具：
   - 不确定 AADL 语法规则时，调用 `search_knowledge` 查询领域知识
   - 遇到不确定如何修复的错误时，调用 `search_error_memory` 查询历史修复案例
   - 每次修改代码后，**必须**调用 `validate_aadl` 验证修复结果
3. **Observation（观察）**：查看工具返回的结果，判断错误是否已修复
4. 重复以上步骤，直到 `validate_aadl` 返回无错误
5. **Final Answer**：输出最终修复后的完整 AADL 代码

## 重要规则

- **每次修改代码后必须调用 validate_aadl 验证**，不允许未经验证就直接给出最终答案
- 如果验证后仍有错误，继续分析并修复，直到所有错误都被解决
- 优先使用 search_error_memory 查找相似错误的历史修复经验
- 对 AADL 语法不确定时，使用 search_knowledge 查询
- 修复时请保持代码的原有结构和组件名称，只修改有问题的部分
- 在修改处可添加 `-- [修复]` 注释标记，方便用户查看改动

## 可用工具

- **validate_aadl**: 验证 AADL 代码的语法正确性和引用完整性，返回错误、警告和自动修复结果。每次修改代码后必须调用此工具。
- **search_knowledge**: 从 AADL 领域知识库中检索语法知识、代码示例和最佳实践。不确定 AADL 语法时调用。
- **search_error_memory**: 从错误记忆库中检索相似错误的历史修复案例。遇到不确定如何修复的错误时调用。
- **format_aadl**: 格式化 AADL 代码，调整缩进和空行，修复完成后可调用使代码更规范。
- **count_aadl_elements**: 统计 AADL 代码中组件、端口、连接等元素数量，辅助检查修复完整性。

## 输出要求

最终答案请用 ```aadl 代码块包裹完整的修复后 AADL 代码。
确保代码可以通过 validate_aadl 工具的验证（零错误）。
""";
    }

    @Override
    protected String buildUserMessage(AgentInput input) {
        String rawAadl = input.getContent();
        String errors = input.getMetadata();

        StringBuilder sb = new StringBuilder();
        sb.append("请修复以下 AADL 代码中的错误。\n\n");

        // 清理输入代码（移除静态分析标记和所有注释，避免干扰）
        String cleanAadl = stripAllComments(sanitizeAadlContent(rawAadl));
        sb.append("## 待修复的 AADL 代码\n\n");
        sb.append("```aadl\n").append(cleanAadl).append("\n```\n\n");

        // 已知错误信息
        if (errors != null && !errors.trim().isEmpty()) {
            sb.append("## 已知错误信息\n\n");
            sb.append(errors).append("\n\n");
        } else {
            sb.append("## 说明\n\n");
            sb.append("请先调用 validate_aadl 工具验证代码，获取错误列表后再进行修复。\n\n");
        }

        sb.append("请按照 ReAct 模式工作：\n");
        sb.append("1. 首先调用 validate_aadl 验证当前代码，确认错误\n");
        sb.append("2. 如需参考，调用 search_knowledge 或 search_error_memory 获取帮助\n");
        sb.append("3. 分析错误并修改代码\n");
        sb.append("4. 每次修改后调用 validate_aadl 验证结果\n");
        sb.append("5. 重复直到无错误，然后输出最终的修复后代码\n");

        return sb.toString();
    }

    @Override
    protected List<Tool> getTools() {
        return Arrays.asList(validateAadlTool, searchKnowledgeTool, searchErrorMemoryTool, formatAadlTool, countAadlElementsTool);
    }

    @Override
    protected AgentOutput buildFinalOutput(AgentInput input, String finalContent, long totalTimeMs) {
        try {
            String fixedAadl = extractAadlCode(finalContent);

            if (fixedAadl == null || fixedAadl.trim().isEmpty()) {
                log.error("无法从最终回答中提取 AADL 代码");
                return AgentOutput.failure(input.getSessionId(), "无法从最终回答中提取 AADL 代码");
            }

            // 统计信息（用于日志）
            int fixMarkerCount = countFixMarkers(fixedAadl);
            int componentCount = countComponents(fixedAadl);
            int connectionCount = countConnections(fixedAadl);

            log.info("========================================");
            log.info("{} 执行完成!", AGENT_NAME);
            log.info("组件数量: {} 个", componentCount);
            log.info("连接数量: {} 个", connectionCount);
            log.info("修复标记: {} 个", fixMarkerCount);
            log.info("总耗时: {}ms", totalTimeMs);
            log.info("========================================");

            printAadlSummary(fixedAadl);

            return AgentOutput.success(input.getSessionId(), fixedAadl, totalTimeMs);

        } catch (Exception e) {
            log.error("解析最终回答失败: {}", e.getMessage(), e);
            return AgentOutput.failure(input.getSessionId(), "解析最终回答失败: " + e.getMessage());
        }
    }

    @Override
    protected double getTemperature() {
        return temperature;
    }

    @Override
    protected int getMaxTokens() {
        return maxTokens;
    }

    /**
     * 消融实验兼容方法：内部委托给 ReAct 模式的 execute()
     * 注：ReAct 模式下 rag/staticAnalysis/prompt 参数由工具调用机制自动处理，
     * 不再需要单独控制。如需禁用某些工具，应通过 getTools() 调整。
     */
    public String fixForAblation(String buggyCode, String errorsText, String ragContext,
                                 ModelType modelType, Object ablationConfig) {
        AgentInput input = AgentInput.builder()
                .sessionId("ablation-" + System.currentTimeMillis())
                .content(buggyCode)
                .metadata(errorsText != null ? errorsText : "")
                .modelType(modelType)
                .ragContext(ragContext)
                .build();

        AgentOutput output = execute(input);
        if (output.isSuccess() && output.getContent() != null) {
            return output.getContent();
        }
        log.warn("消融实验修复失败: {}", output.getErrorMessage());
        return buggyCode; // 失败时返回原始代码
    }

    @Override
    protected String getKnowledgeDomain() {
        return "aadl";
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 LLM 响应中提取 AADL 代码块内容。
     */
    private String extractAadlCode(String response) {
        String cleaned = response.trim();

        if (cleaned.startsWith("```aadl")) {
            int start = cleaned.indexOf("```aadl") + 7;
            int end = cleaned.lastIndexOf("```");
            if (end > start) {
                cleaned = cleaned.substring(start, end).trim();
            }
        } else if (cleaned.startsWith("```")) {
            int start = cleaned.indexOf("```") + 3;
            int end = cleaned.lastIndexOf("```");
            if (end > start) {
                cleaned = cleaned.substring(start, end).trim();
            }
        }

        if (cleaned.startsWith("package")) {
            cleaned = fixMissingEndStatements(cleaned);

            Matcher pkgMatcher = Pattern.compile("^\\s*package\\s+(\\w+)\\s*").matcher(cleaned);
            String packageName = "System";
            if (pkgMatcher.find()) {
                packageName = pkgMatcher.group(1);
            }

            if (!cleaned.matches(".*end\\s+" + packageName + "\\s*;\\s*$")) {
                cleaned = cleaned.replaceAll("\\s*end\\s+\\w*\\s*;\\s*$", "").trim();
                cleaned = cleaned + "\nend " + packageName + ";\n";
            }
        }

        return cleaned;
    }

    /**
     * 清理AADL代码中静态分析器和修复过程注入的注释标记。
     * 这些注释会干扰LLM判断，在传入LLM之前必须清理掉。
     */
    private String sanitizeAadlContent(String aadlContent) {
        if (aadlContent == null || aadlContent.isEmpty()) {
            return aadlContent;
        }

        String[] lines = aadlContent.split("\n");
        List<String> cleanedLines = new ArrayList<>();

        // 匹配整行是验证报告注释的模式
        Pattern reportLinePattern = Pattern.compile(
                "^\\s*--\\s*\\[(错误|警告|自动修复|ERROR|WARNING|错误报告|验证报告|修复建议)\\]"
        );

        // 匹配行尾的修复/自动修正/修复建议标记注释
        Pattern trailingMarkerPattern = Pattern.compile(
                "\\s+--\\s*\\[(自动修正|自动修复|修复|修复建议|ERROR|WARNING|错误|警告)\\][^\\r\\n]*$"
        );

        for (String line : lines) {
            // 检查是否是纯报告行
            Matcher reportMatcher = reportLinePattern.matcher(line);
            if (reportMatcher.find()) {
                String beforeComment = line.substring(0, reportMatcher.start()).trim();
                if (beforeComment.isEmpty()) {
                    continue; // 整行都是报告注释，跳过
                }
            }

            // 移除行尾的标记注释
            Matcher trailingMatcher = trailingMarkerPattern.matcher(line);
            String cleaned = trailingMatcher.replaceAll("");
            cleaned = cleaned.replaceAll("\\s+$", "");

            // 如果清理后行为空且原行是纯标记注释行，跳过
            if (cleaned.trim().isEmpty()) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--")) {
                    String content = trimmed.replaceAll("^--\\s*", "");
                    if (content.matches("\\s*\\[(自动修正|自动修复|修复|修复建议|ERROR|WARNING|错误|警告|自动修复)\\][^\\[]*")) {
                        continue;
                    }
                }
            }

            cleanedLines.add(cleaned);
        }

        // 移除顶部连续空行
        while (!cleanedLines.isEmpty() && cleanedLines.get(0).trim().isEmpty()) {
            cleanedLines.remove(0);
        }

        // 移除底部连续空行
        while (!cleanedLines.isEmpty() && cleanedLines.get(cleanedLines.size() - 1).trim().isEmpty()) {
            cleanedLines.remove(cleanedLines.size() - 1);
        }

        return String.join("\n", cleanedLines);
    }

    /**
     * 彻底删除 AADL 代码中的所有注释（整行 -- 注释 + 行尾 -- 注释）。
     * 用于迭代修复时，确保每次传入 LLM 的都是纯净的代码，避免注释干扰。
     */
    private String stripAllComments(String aadlContent) {
        if (aadlContent == null || aadlContent.isEmpty()) {
            return aadlContent;
        }

        // 先保护 annex 块（EMV2 等 {** ... **}）
        List<String> annexBlocks = new ArrayList<>();
        String protectedContent = aadlContent;
        Pattern annexPattern = Pattern.compile("\\{\\*\\*[\\s\\S]*?\\*\\*\\}");
        Matcher annexMatcher = annexPattern.matcher(aadlContent);
        while (annexMatcher.find()) {
            annexBlocks.add(annexMatcher.group());
        }
        // 用占位符替换
        for (int i = 0; i < annexBlocks.size(); i++) {
            protectedContent = protectedContent.replace(annexBlocks.get(i), "@@ANNEX_" + i + "@@");
        }

        // 逐行处理
        String[] lines = protectedContent.split("\n", -1);
        List<String> cleanedLines = new ArrayList<>();

        for (String line : lines) {
            int commentPos = findCommentPosition(line);
            if (commentPos >= 0) {
                String beforeComment = line.substring(0, commentPos).trim();
                if (beforeComment.isEmpty()) {
                    // 整行都是注释，跳过
                    continue;
                } else {
                    // 保留代码部分，去掉注释
                    cleanedLines.add(beforeComment);
                }
            } else {
                // 没有注释，整行保留
                cleanedLines.add(line);
            }
        }

        String result = String.join("\n", cleanedLines);

        // 恢复 annex 块
        for (int i = 0; i < annexBlocks.size(); i++) {
            result = result.replace("@@ANNEX_" + i + "@@", annexBlocks.get(i));
        }

        // 移除顶部连续空行
        while (!cleanedLines.isEmpty() && cleanedLines.get(0).trim().isEmpty()) {
            cleanedLines.remove(0);
        }

        return result;
    }

    /**
     * 查找一行中 AADL 注释 "--" 的起始位置。
     * 会跳过字符串字面量中的 "--"。
     */
    private int findCommentPosition(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '-' && line.charAt(i + 1) == '-') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 修复缺失的 end 语句。
     */
    private String fixMissingEndStatements(String aadlContent) {
        String[] lines = aadlContent.split("\n");
        int fixedCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            if (trimmed.startsWith("thread ") || trimmed.startsWith("process ") ||
                trimmed.startsWith("system ") || trimmed.startsWith("processor ") ||
                trimmed.startsWith("memory ") || trimmed.startsWith("device ") ||
                trimmed.startsWith("bus ") || trimmed.startsWith("data ") ||
                trimmed.startsWith("subprogram ")) {

                if (trimmed.contains("implementation")) {
                    continue;
                }

                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2) {
                    String componentName = parts[1];

                    boolean hasEnd = false;
                    for (int j = i + 1; j < lines.length; j++) {
                        String nextTrimmed = lines[j].trim();
                        if (nextTrimmed.equals("end " + componentName + ";")) {
                            hasEnd = true;
                            break;
                        }
                    }

                    if (!hasEnd) {
                        lines[i] = lines[i] + "\nend " + componentName + ";";
                        fixedCount++;
                    }
                }
            }
        }

        return String.join("\n", lines).trim();
    }

    /**
     * 统计 AADL 代码中的组件数量。
     */
    private int countComponents(String aadlContent) {
        Pattern pattern = Pattern.compile("\\b(thread|process|processor|device|memory|system|bus)\\b");
        Matcher matcher = pattern.matcher(aadlContent);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 统计 AADL 代码中的连接块数量。
     */
    private int countConnections(String aadlContent) {
        Pattern pattern = Pattern.compile("connections");
        Matcher matcher = pattern.matcher(aadlContent);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 统计修复代码中 -- [修复] 标记的数量。
     */
    private int countFixMarkers(String aadlContent) {
        if (aadlContent == null || aadlContent.isEmpty()) {
            return 0;
        }
        Pattern pattern = Pattern.compile("--\\s*\\[修复\\]");
        Matcher matcher = pattern.matcher(aadlContent);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 打印 AADL 代码摘要（前20行预览）。
     */
    private void printAadlSummary(String aadlContent) {
        String[] lines = aadlContent.split("\n");
        log.info("修复后的AADL预览（前20行）:");
        int displayLines = Math.min(lines.length, 20);
        for (int i = 0; i < displayLines; i++) {
            log.info("  {}", lines[i]);
        }
        if (lines.length > 20) {
            log.info("  ... (共 {} 行)", lines.length);
        }
    }
}
