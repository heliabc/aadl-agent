package com.example.aadlagent.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON 容错工具类，用于处理大模型输出不完整 JSON 的常见情况。
 *
 * 功能：
 * 1. 从任意文本中提取 JSON 内容
 * 2. 自动补全不完整/被截断的 JSON（缺失的 ] } " 等）
 * 3. 兼容 markdown 代码块包裹（```json ... ```）
 */
@Slf4j
public class JsonRepairUtil {

    /**
     * 从响应文本中提取并修复 JSON。
     * 优先尝试正常解析，失败则自动补全后再解析。
     *
     * @param response 原始响应文本
     * @return 提取/修复后的 JSON 字符串，如果无法提取则返回 null
     */
    public static String extractAndFixJson(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        String jsonContent = extractJson(response);
        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            return null;
        }

        // 先尝试原样解析（快速路径）
        if (isValidJson(jsonContent)) {
            return jsonContent;
        }

        // 尝试补全
        String fixed = fixIncompleteJson(jsonContent);
        if (fixed != null && !fixed.equals(jsonContent) && isValidJson(fixed)) {
            log.info("JSON补全成功: 原长度={}字符, 补全后={}字符", jsonContent.length(), fixed.length());
            return fixed;
        }

        // 补全失败，返回原始内容（调用方自行处理异常）
        return jsonContent;
    }

    /**
     * 从文本中提取 JSON 内容。
     * 支持 markdown 代码块和纯 JSON 两种格式。
     */
    public static String extractJson(String response) {
        if (response == null) return null;

        // 尝试匹配 markdown 代码块 ```json ... ```
        int codeBlockStart = response.indexOf("```json");
        if (codeBlockStart < 0) {
            codeBlockStart = response.indexOf("```JSON");
        }
        if (codeBlockStart >= 0) {
            int contentStart = codeBlockStart + 7; // 跳过 ```json
            int codeBlockEnd = response.indexOf("```", contentStart);
            if (codeBlockEnd > contentStart) {
                return response.substring(contentStart, codeBlockEnd).trim();
            }
            // 只有开头没有结尾，返回开头之后的内容（让 fixIncompleteJson 处理）
            if (contentStart < response.length()) {
                return response.substring(contentStart).trim();
            }
        }

        // 尝试匹配普通代码块 ``` ... ```
        int genericCodeStart = response.indexOf("```");
        if (genericCodeStart >= 0) {
            int contentStart = genericCodeStart + 3;
            // 跳过可能的语言标识行
            int newlineAfter = response.indexOf('\n', contentStart);
            if (newlineAfter > 0 && newlineAfter - contentStart < 20) {
                contentStart = newlineAfter + 1;
            }
            int codeBlockEnd = response.indexOf("```", contentStart);
            if (codeBlockEnd > contentStart) {
                return response.substring(contentStart, codeBlockEnd).trim();
            }
            if (contentStart < response.length()) {
                return response.substring(contentStart).trim();
            }
        }

        // 纯文本中找 JSON
        int startBrace = response.indexOf('{');
        int startBracket = response.indexOf('[');
        int startIndex = -1;
        char startChar = '{';

        if (startBrace >= 0 && (startBracket < 0 || startBrace < startBracket)) {
            startIndex = startBrace;
            startChar = '{';
        } else if (startBracket >= 0) {
            startIndex = startBracket;
            startChar = '[';
        }

        if (startIndex < 0) {
            return null;
        }

        // 找对应的结束符号
        char endChar = (startChar == '{') ? '}' : ']';
        int endIndex = response.lastIndexOf(endChar);

        if (endIndex > startIndex) {
            return response.substring(startIndex, endIndex + 1);
        }

        // 没有结束符号，返回从开头开始的内容（让 fixIncompleteJson 处理）
        return response.substring(startIndex);
    }

    /**
     * 尝试修复不完整的 JSON。
     * 通过追踪括号/引号状态，补全缺失的闭合符号。
     *
     * 处理场景：
     * - 数组未闭合（缺 ]）
     * - 对象未闭合（缺 }）
     * - 字符串未闭合（缺 "）
     * - 末尾残留逗号/冒号
     * - 最后一个对象/元素被截断（回退到上一个完整结构）
     */
    public static String fixIncompleteJson(String json) {
        if (json == null || json.isEmpty()) return null;

        json = json.trim();
        if (json.isEmpty()) return null;

        List<Character> stack = new ArrayList<>();
        boolean inString = false;
        boolean escape = false;
        int lastValidEnd = -1;
        int lastCompleteStructureEnd = -1;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                    lastValidEnd = i;
                }
                continue;
            }

            switch (c) {
                case '"':
                    inString = true;
                    break;
                case '{':
                    stack.add('}');
                    break;
                case '[':
                    stack.add(']');
                    break;
                case '}':
                case ']':
                    if (!stack.isEmpty() && stack.get(stack.size() - 1) == c) {
                        stack.remove(stack.size() - 1);
                        lastValidEnd = i;
                        if (stack.isEmpty()) {
                            lastCompleteStructureEnd = i;
                        }
                    }
                    break;
                case ',':
                    // 逗号是结构分隔符，前面应该是一个完整的值
                    lastValidEnd = i;
                    break;
                case ':':
                    break;
                default:
                    if (Character.isWhitespace(c)) {
                        lastValidEnd = i;
                    }
                    break;
            }
        }

        // 如果已经完整，直接返回
        if (stack.isEmpty() && !inString) {
            return json;
        }

        // 回退策略：
        // 1. 如果有完整的顶层结构，优先回退到那里（保留最多有效数据）
        // 2. 否则回退到最后一个有效位置
        StringBuilder sb;
        int cutPoint;

        if (lastCompleteStructureEnd > 0 && stack.size() > 1) {
            // 有完整顶层结构，但后面还有不完整的内容
            // 比如 {"modules": [{"name": "a"}, {"name": "b  ← 第二个对象不完整
            // 回退策略要看具体情况，如果只有一层未闭合，尝试补全
            cutPoint = lastValidEnd >= 0 ? lastValidEnd + 1 : json.length();
            sb = new StringBuilder(json.substring(0, cutPoint));
        } else {
            cutPoint = lastValidEnd >= 0 ? lastValidEnd + 1 : json.length();
            sb = new StringBuilder(json.substring(0, cutPoint));
        }

        // 如果在字符串中间，先闭合字符串
        if (inString) {
            sb.append('"');
        }

        // 去掉末尾残留的逗号、冒号和空白
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last == ',' || last == ':' || Character.isWhitespace(last)) {
                sb.deleteCharAt(sb.length() - 1);
            } else {
                break;
            }
        }

        // 按栈逆序补全闭合符号
        for (int i = stack.size() - 1; i >= 0; i--) {
            sb.append(stack.get(i));
        }

        String result = sb.toString();

        if (!result.equals(json)) {
            int removed = json.length() - cutPoint;
            int added = stack.size() + (inString ? 1 : 0);
            log.debug("JSON补全: 移除{}字符, 补全{}个闭合符号", removed, added);
        }

        return result;
    }

    /**
     * 快速校验 JSON 是否有效（仅检查结构完整性，不做完整解析）。
     */
    private static boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) return false;

        json = json.trim();
        char first = json.charAt(0);
        char last = json.charAt(json.length() - 1);

        // 快速检查首尾
        if ((first == '{' && last == '}') || (first == '[' && last == ']')) {
            // 进一步检查括号是否匹配
            int depth = 0;
            boolean inString = false;
            boolean escape = false;

            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inString) {
                    if (escape) {
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') inString = true;
                else if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
            }

            return depth == 0 && !inString;
        }

        return false;
    }
}
