package com.example.aadlagent.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AADL 代码格式化工具
 *
 * 对给定的 AADL 代码进行缩进和空行整理，
 * 使代码结构更清晰、更易读。
 *
 * 用途：当 LLM 生成或修改的 AADL 代码缩进混乱时，
 * 调用此工具统一格式化，提升代码可读性。
 */
@Slf4j
@Component
public class FormatAadlTool implements Tool {

    private static final String TOOL_NAME = "format_aadl";

    // 增加缩进的关键字（行首匹配，不区分大小写）
    private static final String[] INDENT_INCREASE_KEYWORDS = {
            "package",
            "public",
            "private",
            "features",
            "subcomponents",
            "connections",
            "properties",
            "flows",
            "modes",
            "calls",
            "prototypes",
            "annex"
    };

    // 组件类型关键字（声明行，后面跟有 features/subcomponents 等块）
    private static final String[] COMPONENT_TYPE_KEYWORDS = {
            "system",
            "thread",
            "process",
            "device",
            "processor",
            "memory",
            "data",
            "subprogram",
            "abstract",
            "bus",
            "virtual bus",
            "virtual processor"
    };

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "格式化 AADL 代码，调整缩进和空行，使代码结构更清晰易读。修复缩进混乱的代码时调用此工具。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> codeParam = new LinkedHashMap<>();
        codeParam.put("type", "string");
        codeParam.put("description", "需要格式化的 AADL 代码");
        props.put("aadl_code", codeParam);

        Map<String, Object> indentParam = new LinkedHashMap<>();
        indentParam.put("type", "integer");
        indentParam.put("description", "缩进空格数，默认4");
        indentParam.put("default", 4);
        props.put("indent_size", indentParam);

        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("aadl_code")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String aadlCode = (String) arguments.get("aadl_code");
        if (aadlCode == null || aadlCode.trim().isEmpty()) {
            return "错误：aadl_code 参数不能为空";
        }

        int indentSize = 4;
        Object indentSizeObj = arguments.get("indent_size");
        if (indentSizeObj instanceof Number) {
            indentSize = ((Number) indentSizeObj).intValue();
            if (indentSize < 0) {
                indentSize = 4;
            }
        }

        try {
            String formatted = formatCode(aadlCode, indentSize);

            log.info("FormatAadlTool: 格式化完成，原代码 {} 字符，格式化后 {} 字符",
                    aadlCode.length(), formatted.length());

            return "=== AADL 格式化结果 ===\n\n" + formatted;

        } catch (Exception e) {
            log.error("FormatAadlTool execution error: {}", e.getMessage(), e);
            return "格式化工具执行异常: " + e.getMessage();
        }
    }

    /**
     * 格式化 AADL 代码
     *
     * @param code       原始代码
     * @param indentSize 缩进空格数
     * @return 格式化后的代码
     */
    private String formatCode(String code, int indentSize) {
        String[] lines = code.split("\n", -1);
        List<String> resultLines = new ArrayList<>();

        int indentLevel = 0;
        boolean prevLineEmpty = false;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();

            // 处理空行：连续空行只保留一行
            if (trimmed.isEmpty()) {
                if (!prevLineEmpty && !resultLines.isEmpty()) {
                    resultLines.add("");
                }
                prevLineEmpty = true;
                continue;
            }
            prevLineEmpty = false;

            // 检查是否是 end 行：先减少缩进，再输出
            if (isEndLine(trimmed)) {
                indentLevel--;
                if (indentLevel < 0) {
                    indentLevel = 0;
                }
            }

            // 添加带缩进的行
            String indent = buildIndent(indentLevel, indentSize);
            resultLines.add(indent + trimmed);

            // 检查是否需要增加缩进（当前行开启了一个新块）
            if (shouldIncreaseIndent(trimmed)) {
                indentLevel++;
            }
        }

        // 移除首尾空行
        while (!resultLines.isEmpty() && resultLines.get(0).isEmpty()) {
            resultLines.remove(0);
        }
        while (!resultLines.isEmpty() && resultLines.get(resultLines.size() - 1).isEmpty()) {
            resultLines.remove(resultLines.size() - 1);
        }

        return String.join("\n", resultLines);
    }

    /**
     * 判断一行是否以 end 开头（关闭块）
     */
    private boolean isEndLine(String trimmedLine) {
        String lower = trimmedLine.toLowerCase();
        // 匹配 "end xxx;" 或 "end xxx" 形式
        return lower.startsWith("end ") || lower.equals("end");
    }

    /**
     * 判断当前行是否开启了一个新块（需要增加缩进）
     */
    private boolean shouldIncreaseIndent(String trimmedLine) {
        String lower = trimmedLine.toLowerCase();

        // 排除 end 行（不会增加缩进）
        if (lower.startsWith("end ")) {
            return false;
        }

        // 检查 "xxx implementation" 形式（组件实现声明）
        // 例如 "system implementation MySys.impl"
        if (lower.contains(" implementation ") && !lower.startsWith("implementation")) {
            return true;
        }

        // 检查行首关键字（package, public, private, features 等）
        for (String keyword : INDENT_INCREASE_KEYWORDS) {
            if (startsWithKeyword(lower, keyword)) {
                return true;
            }
        }

        // 检查组件类型声明行
        // 例如 "system MySystem"  "thread MyThread"
        for (String keyword : COMPONENT_TYPE_KEYWORDS) {
            if (isComponentTypeDeclaration(lower, keyword)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断行是否以指定关键字开头（后面跟空格或分号或直接结束）
     */
    private boolean startsWithKeyword(String lowerLine, String keyword) {
        if (lowerLine.equals(keyword)) {
            return true;
        }
        if (lowerLine.startsWith(keyword + " ")) {
            return true;
        }
        if (lowerLine.startsWith(keyword + ";")) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否为组件类型声明行
     * 例如 "system MySystem" 或 "system MySystem extends ..."
     */
    private boolean isComponentTypeDeclaration(String lowerLine, String keyword) {
        // 排除 "xxx implementation" 的情况，已单独处理
        if (lowerLine.contains(" implementation ")) {
            return false;
        }

        String prefix = keyword + " ";
        if (!lowerLine.startsWith(prefix)) {
            return false;
        }

        // 关键字后面还有内容（组件名称等），则认为是声明行
        String rest = lowerLine.substring(prefix.length()).trim();
        return !rest.isEmpty();
    }

    /**
     * 构建缩进字符串
     */
    private String buildIndent(int level, int indentSize) {
        if (level <= 0) {
            return "";
        }
        int totalSpaces = level * indentSize;
        StringBuilder sb = new StringBuilder(totalSpaces);
        for (int i = 0; i < totalSpaces; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
