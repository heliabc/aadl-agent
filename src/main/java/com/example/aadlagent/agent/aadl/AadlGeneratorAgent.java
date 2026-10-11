package com.example.aadlagent.agent.aadl;

import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.agent.ReActAgent;
import com.example.aadlagent.agent.tool.CountAadlElementsTool;
import com.example.aadlagent.agent.tool.FormatAadlTool;
import com.example.aadlagent.agent.tool.SearchKnowledgeTool;
import com.example.aadlagent.agent.tool.Tool;
import com.example.aadlagent.agent.tool.ValidateAadlTool;
import com.example.aadlagent.client.ModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AadlGeneratorAgent extends ReActAgent {

    private static final String AGENT_NAME = "AadlGeneratorAgent";

    private final SearchKnowledgeTool searchKnowledgeTool;
    private final ValidateAadlTool validateAadlTool;
    private final FormatAadlTool formatAadlTool;
    private final CountAadlElementsTool countAadlElementsTool;

    @Value("${agent.aadl.temperature:0.1}")
    private double temperature;

    @Value("${agent.aadl.max-tokens:16384}")
    private int maxTokens;

    public AadlGeneratorAgent(ModelService modelService,
                              SearchKnowledgeTool searchKnowledgeTool,
                              ValidateAadlTool validateAadlTool,
                              FormatAadlTool formatAadlTool,
                              CountAadlElementsTool countAadlElementsTool) {
        super(modelService);
        this.searchKnowledgeTool = searchKnowledgeTool;
        this.validateAadlTool = validateAadlTool;
        this.formatAadlTool = formatAadlTool;
        this.countAadlElementsTool = countAadlElementsTool;
    }

    // ==================== ReActAgent 抽象方法实现 ====================

    @Override
    protected String getSystemPrompt() {
        return """
你是一位精通 AADL（Architecture Analysis & Design Language）的代码生成专家。
你的任务是根据架构模型和模块分析结果，生成完整、正确的 AADL 代码。

## 工作方式（ReAct 模式）

1. **Thought（思考）**：分析架构模型和模块分析，规划 AADL 代码结构
2. **Action（行动）**：
   - 不确定 AADL 语法或组件用法时，调用 `search_knowledge` 查询领域知识
   - 生成或修改 AADL 代码后，必须调用 `validate_aadl` 验证代码正确性
3. **Observation（观察）**：查看工具返回的知识或验证结果
4. 重复以上步骤，直到验证通过、代码无错误
5. **Final Answer**：输出最终的 AADL 代码

## 可用工具

- **search_knowledge**: 检索 AADL 领域知识，包括语法规则、组件类型、代码示例和最佳实践。不确定 AADL 语法时调用。
- **validate_aadl**: 验证 AADL 代码的语法正确性和引用完整性，返回错误、警告和自动修复结果。每次生成或修改代码后必须调用此工具验证。
- **format_aadl**: 格式化 AADL 代码，调整缩进和空行，使代码更规范
- **count_aadl_elements**: 统计 AADL 代码中组件、端口、连接等元素数量，检查生成结果是否完整

## 输出要求

- 最终答案请用 ```aadl 代码块包裹完整的 AADL 代码
- 代码必须包含完整的 package 声明和所有组件定义
- 确保所有引用的组件都已声明
- 确保所有 connections 中的端口引用都存在
- 代码格式规范，缩进一致
""";
    }

    @Override
    protected String buildUserMessage(AgentInput input) {
        String architectureJson = input.getContent();
        String modulesJson = input.getMetadata();
        String ragContext = input.getRagContext();

        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下架构模型和模块分析，生成完整的 AADL 代码：\n\n");

        sb.append("## 架构模型\n");
        sb.append("```json\n").append(architectureJson != null ? architectureJson : "").append("\n```\n\n");

        sb.append("## 模块分析\n");
        sb.append("```json\n").append(modulesJson != null ? modulesJson : "").append("\n```\n\n");

        if (ragContext != null && !ragContext.trim().isEmpty()) {
            sb.append("## 参考知识（RAG 检索结果）\n");
            sb.append(ragContext).append("\n\n");
        }

        sb.append("## 生成要求\n");
        sb.append("1. 根据架构模型设计 AADL 组件层级和结构\n");
        sb.append("2. 根据模块分析补充组件的 features、properties 等细节\n");
        sb.append("3. 生成完整的 AADL package 代码\n");
        sb.append("4. 生成后请立即调用 validate_aadl 工具验证代码正确性\n");
        sb.append("5. 如果验证发现错误，根据错误信息修正代码，然后再次验证\n");
        sb.append("6. 重复修正-验证循环，直到代码验证通过\n");
        sb.append("7. 最终输出用 ```aadl 代码块包裹\n");

        return sb.toString();
    }

    @Override
    protected List<Tool> getTools() {
        return Arrays.asList(searchKnowledgeTool, validateAadlTool, formatAadlTool, countAadlElementsTool);
    }

    @Override
    protected AgentOutput buildFinalOutput(AgentInput input, String finalContent, long totalTimeMs) {
        try {
            String aadlContent = extractAadlCode(finalContent);

            if (aadlContent == null || aadlContent.trim().isEmpty()) {
                log.error("无法从最终回答中提取 AADL 代码");
                return AgentOutput.failure(input.getSessionId(), "无法提取 AADL 代码");
            }

            int componentCount = countComponents(aadlContent);
            int connectionCount = countConnections(aadlContent);

            log.info("========================================");
            log.info("{} 执行成功!", AGENT_NAME);
            log.info("组件数量: {} 个", componentCount);
            log.info("连接数量: {} 个", connectionCount);
            log.info("AADL 代码长度: {} 字符", aadlContent.length());
            log.info("总耗时: {}ms", totalTimeMs);
            log.info("========================================");

            printAadlSummary(aadlContent);

            return AgentOutput.success(input.getSessionId(), aadlContent, totalTimeMs);

        } catch (Exception e) {
            log.error("构建最终输出失败: {}", e.getMessage(), e);
            return AgentOutput.failure(input.getSessionId(), "构建最终输出失败: " + e.getMessage());
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

    @Override
    protected String getKnowledgeDomain() {
        return "aadl";
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }

    // ==================== 代码提取辅助方法 ====================

    /**
     * 从 LLM 响应中提取 AADL 代码块。
     * 优先提取 ```aadl 代码块，其次是通用 ``` 代码块，
     * 如果没有代码块则尝试找到 package 声明的起始位置。
     */
    private String extractAadlCode(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        String cleaned = response.trim();

        // 1. 优先尝试 aadl 代码块
        Pattern aadlPattern = Pattern.compile("```aadl\\s*\\n(.*?)\\n```", Pattern.DOTALL);
        Matcher aadlMatcher = aadlPattern.matcher(cleaned);
        if (aadlMatcher.find()) {
            String code = aadlMatcher.group(1).trim();
            if (code.startsWith("package")) {
                return fixMissingEndStatements(code);
            }
            return code;
        }

        // 2. 通用代码块（取最后一个以 package 开头的代码块）
        Pattern codePattern = Pattern.compile("```\\w*\\s*\\n(.*?)\\n```", Pattern.DOTALL);
        Matcher codeMatcher = codePattern.matcher(cleaned);
        String lastMatch = null;
        while (codeMatcher.find()) {
            String candidate = codeMatcher.group(1).trim();
            if (candidate.startsWith("package")) {
                lastMatch = candidate;
            }
        }
        if (lastMatch != null) {
            return fixMissingEndStatements(lastMatch);
        }

        // 3. 没有代码块时：找到第一个 package 声明的位置
        int packageIndex = findAadlStartIndex(cleaned);
        if (packageIndex >= 0) {
            String code = cleaned.substring(packageIndex).trim();
            return fixMissingEndStatements(code);
        }

        return null;
    }

    /**
     * 查找 AADL 代码的起始位置。
     * 跳过前面的解释文字，找到第一个 package 声明的位置。
     * 如果找不到，返回 -1。
     */
    private int findAadlStartIndex(String text) {
        if (text.startsWith("package")) {
            return 0;
        }

        Pattern pkgPattern = Pattern.compile("(?m)^\\s*package\\s+\\w+");
        Matcher matcher = pkgPattern.matcher(text);
        if (matcher.find()) {
            int start = matcher.start();
            // 确保前面不是 JSON 字符串里的 package
            String before = text.substring(Math.max(0, start - 3), start);
            if (before.contains("\"")) {
                // 可能是 JSON 字段，继续找下一个
                while (matcher.find()) {
                    start = matcher.start();
                    before = text.substring(Math.max(0, start - 3), start);
                    if (!before.contains("\"")) {
                        return start;
                    }
                }
                return -1;
            }
            return start;
        }

        return -1;
    }

    /**
     * 修复缺失的 end 语句。
     * 对 thread、process、system、processor、memory、device、bus、data、subprogram
     * 等组件声明，如果找不到对应的 end 语句，则自动补全。
     */
    private String fixMissingEndStatements(String aadlContent) {
        String[] lines = aadlContent.split("\n");

        int fixedCount = 0;
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            result.append(lines[i]).append("\n");

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

                    // 检查后续是否有对应的 end 语句
                    boolean hasEnd = false;
                    for (int j = i + 1; j < lines.length; j++) {
                        String nextTrimmed = lines[j].trim();
                        if (nextTrimmed.equals("end " + componentName + ";")) {
                            hasEnd = true;
                            break;
                        }
                    }

                    if (!hasEnd) {
                        result.append("end ").append(componentName).append(";\n");
                        fixedCount++;
                    }
                }
            }
        }

        if (fixedCount > 0) {
            log.info("自动补全了 {} 个缺失的 end 语句", fixedCount);
        }

        return result.toString().trim();
    }

    // ==================== 统计与日志辅助方法 ====================

    private int countComponents(String aadlContent) {
        Pattern pattern = Pattern.compile("\\b(thread|process|processor|device|memory|system|bus)\\b");
        Matcher matcher = pattern.matcher(aadlContent);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int countConnections(String aadlContent) {
        Pattern pattern = Pattern.compile("connections");
        Matcher matcher = pattern.matcher(aadlContent);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private void printAadlSummary(String aadlContent) {
        String[] lines = aadlContent.split("\n");
        log.info("AADL文件预览（前20行）:");
        int displayLines = Math.min(lines.length, 20);
        for (int i = 0; i < displayLines; i++) {
            log.info("  {}", lines[i]);
        }
        if (lines.length > 20) {
            log.info("  ... (共 {} 行)", lines.length);
        }
    }
}
