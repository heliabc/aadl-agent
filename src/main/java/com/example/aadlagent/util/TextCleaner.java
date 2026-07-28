package com.example.aadlagent.util;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextCleaner {

    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(^(\\s*[Pp]age\\s+\\d+\\s*[/\\\\]?\\s*\\d*\\s*$)|" +
            "(^\\s*\\d+\\s*[/\\\\]\\s*\\d+\\s*$)|" +
            "(^\\s*[第]?\\s*\\d+\\s*[页]?\\s*$)|" +
            "(^\\s*\\d+\\s*$))"
    );

    private static final Pattern WATERMARK_PATTERN = Pattern.compile(
            "(?i)(confidential|confidentiality|机密|保密|secret|内部资料|内部使用|仅限内部)"
    );

    private static final List<String> REVISION_KEYWORDS = Arrays.asList(
            "修订记录", "版本历史", "变更记录", "修改记录", "版本变更", "更新记录",
            "版本号", "修改日期", "修订日期", "更改说明", "变更说明",
            "revision", "version", "change history", "update history"
    );

    private static final Pattern SEPARATOR_LINE_PATTERN = Pattern.compile(
            "^\\s*([=\\-\\*_~#]+)\\s*$"
    );

    private static final List<String> INTRO_WORDS = Arrays.asList(
            "本文档", "本需求", "本系统", "本项目", "本方案", "本设计", "本规范",
            "根据", "按照", "为了", "鉴于", "关于", "基于", "针对", "就", "对于",
            "在", "从", "通过", "利用", "采用", "结合", "依据", "遵循", "参照",
            "依据上述", "根据以上", "基于上述", "按照以上", "依据前文", "根据前文"
    );

    private static final Pattern ZERO_WIDTH_CHARS_PATTERN = Pattern.compile(
            "[\\u200B\\u200C\\u200D\\uFEFF\\u200E\\u200F\\u2028\\u2029]"
    );

    private static final Pattern MULTIPLE_SPACES_PATTERN = Pattern.compile("\\s{2,}");

    private static final Pattern TAB_PATTERN = Pattern.compile("\\t");

    private static final Pattern CONTROL_CHARS_PATTERN = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private TextCleaner() {
    }

    public static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String cleaned = text;

        cleaned = removeZeroWidthCharacters(cleaned);

        cleaned = removeControlCharacters(cleaned);

        cleaned = removeTabCharacters(cleaned);

        cleaned = removePageNumbersAndWatermarks(cleaned);

        cleaned = removeRevisionTables(cleaned);

        cleaned = removeSeparatorLines(cleaned);

        cleaned = removeIntroWords(cleaned);

        cleaned = compressWhitespace(cleaned);

        return cleaned.trim();
    }

    private static String removeZeroWidthCharacters(String text) {
        return ZERO_WIDTH_CHARS_PATTERN.matcher(text).replaceAll("");
    }

    private static String removeControlCharacters(String text) {
        return CONTROL_CHARS_PATTERN.matcher(text).replaceAll("");
    }

    private static String removeTabCharacters(String text) {
        return TAB_PATTERN.matcher(text).replaceAll(" ");
    }

    private static String removePageNumbersAndWatermarks(String text) {
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n");

        for (String line : lines) {
            String trimmedLine = line.trim();

            if (trimmedLine.isEmpty()) {
                result.append(line).append("\n");
                continue;
            }

            if (PAGE_NUMBER_PATTERN.matcher(trimmedLine).matches()) {
                continue;
            }

            if (WATERMARK_PATTERN.matcher(trimmedLine).find()) {
                continue;
            }

            result.append(line).append("\n");
        }

        String resultStr = result.toString();
        if (resultStr.endsWith("\n")) {
            resultStr = resultStr.substring(0, resultStr.length() - 1);
        }
        return resultStr;
    }

    private static String removeRevisionTables(String text) {
        String[] paragraphs = text.split("\n\n");
        StringBuilder result = new StringBuilder();

        for (String paragraph : paragraphs) {
            String trimmedParagraph = paragraph.trim();
            if (trimmedParagraph.isEmpty()) {
                result.append("\n\n");
                continue;
            }

            boolean isRevisionSection = false;
            for (String keyword : REVISION_KEYWORDS) {
                if (trimmedParagraph.toLowerCase().contains(keyword.toLowerCase())) {
                    isRevisionSection = true;
                    break;
                }
            }

            if (!isRevisionSection) {
                result.append(paragraph).append("\n\n");
            }
        }

        String resultStr = result.toString();
        while (resultStr.endsWith("\n\n")) {
            resultStr = resultStr.substring(0, resultStr.length() - 2);
        }
        return resultStr;
    }

    private static String removeSeparatorLines(String text) {
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n");

        for (String line : lines) {
            if (!SEPARATOR_LINE_PATTERN.matcher(line).matches()) {
                result.append(line).append("\n");
            }
        }

        String resultStr = result.toString();
        if (resultStr.endsWith("\n")) {
            resultStr = resultStr.substring(0, resultStr.length() - 1);
        }
        return resultStr;
    }

    private static String removeIntroWords(String text) {
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n");

        for (String line : lines) {
            String processedLine = line;

            for (String introWord : INTRO_WORDS) {
                if (processedLine.startsWith(introWord)) {
                    processedLine = processedLine.substring(introWord.length()).trim();
                    if (!processedLine.isEmpty() && (processedLine.charAt(0) == '：' || processedLine.charAt(0) == ':')) {
                        processedLine = processedLine.substring(1).trim();
                    }
                    break;
                }

                Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(introWord) + "\\s*");
                Matcher matcher = pattern.matcher(processedLine);
                if (matcher.find()) {
                    processedLine = matcher.replaceFirst("").trim();
                    if (!processedLine.isEmpty() && (processedLine.charAt(0) == '：' || processedLine.charAt(0) == ':')) {
                        processedLine = processedLine.substring(1).trim();
                    }
                    break;
                }
            }

            result.append(processedLine).append("\n");
        }

        String resultStr = result.toString();
        if (resultStr.endsWith("\n")) {
            resultStr = resultStr.substring(0, resultStr.length() - 1);
        }
        return resultStr;
    }

    private static String compressWhitespace(String text) {
        String result = MULTIPLE_SPACES_PATTERN.matcher(text).replaceAll(" ");

        String[] lines = result.split("\n");
        StringBuilder compressed = new StringBuilder();
        boolean previousEmpty = false;

        for (String line : lines) {
            String trimmedLine = line.trim();

            if (trimmedLine.isEmpty()) {
                if (!previousEmpty) {
                    compressed.append("\n");
                    previousEmpty = true;
                }
            } else {
                compressed.append(trimmedLine).append("\n");
                previousEmpty = false;
            }
        }

        String finalResult = compressed.toString();
        while (finalResult.endsWith("\n")) {
            finalResult = finalResult.substring(0, finalResult.length() - 1);
        }

        return finalResult;
    }
}