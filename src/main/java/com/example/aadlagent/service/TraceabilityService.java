package com.example.aadlagent.service;

import com.example.aadlagent.client.LlmClient;
import com.example.aadlagent.client.OllamaClient;
import com.example.aadlagent.model.Requirement;
import com.example.aadlagent.model.TraceabilityRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TraceabilityService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OllamaClient ollamaClient;

    @Value("${app.output.directory:./output}")
    private String outputDirectory;

    private final ConcurrentHashMap<String, List<TraceabilityRecord>> traceabilityRecords = new ConcurrentHashMap<>();

    public TraceabilityService(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public void addRequirementTraceability(String sessionId, String originalRequirement, String requirementsJson) {
        try {
            List<Requirement> requirements = objectMapper.readValue(requirementsJson, new TypeReference<List<Requirement>>() {});
            
            List<TraceabilityRecord> records = traceabilityRecords.computeIfAbsent(sessionId, k -> new ArrayList<>());
            
            List<String> originalSentences = splitIntoSentences(originalRequirement);
            
            for (Requirement req : requirements) {
                String matchedSentence = findBestMatchingSentence(req, originalSentences);
                
                TraceabilityRecord record = TraceabilityRecord.builder()
                        .id(UUID.randomUUID().toString())
                        .originalRequirement(truncate(matchedSentence, 800))
                        .requirementId(req.getRequirementId())
                        .requirementTitle(req.getTitle())
                        .requirementDescription(req.getDescription())
                        .traceType("REQUIREMENT")
                        .source("RequirementAgent")
                        .build();
                records.add(record);
            }
            
            log.info("添加了 {} 条需求追溯记录，会话ID: {}", requirements.size(), sessionId);
        } catch (Exception e) {
            log.error("添加需求追溯记录失败: {}", e.getMessage());
        }
    }

    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        
        String[] rawSentences = text.split("(?<=[。！？\\.!?])\\s*");
        
        for (String s : rawSentences) {
            s = s.trim();
            if (!s.isEmpty()) {
                sentences.add(s);
            }
        }
        
        if (sentences.isEmpty()) {
            String[] rawParagraphs = text.split("\\n\\s*\\n");
            for (String p : rawParagraphs) {
                p = p.trim();
                if (!p.isEmpty()) {
                    sentences.add(p);
                }
            }
        }
        
        if (sentences.isEmpty()) {
            String[] lines = text.split("\\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty()) {
                    sentences.add(line);
                }
            }
        }
        
        if (sentences.isEmpty()) {
            sentences.add(text);
        }
        
        List<String> mergedSentences = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            if (sentence.length() < 15 && i < sentences.size() - 1) {
                String nextSentence = sentences.get(i + 1);
                mergedSentences.add(sentence + " " + nextSentence);
                i++;
            } else {
                mergedSentences.add(sentence);
            }
        }
        
        return mergedSentences;
    }

    private String findBestMatchingSentence(Requirement req, List<String> sentences) {
        if (sentences == null || sentences.isEmpty()) {
            return "";
        }
        
        String reqText = (req.getTitle() + " " + (req.getDescription() != null ? req.getDescription() : "")).toLowerCase();
        
        double bestScore = 0.0;
        String bestSentence = sentences.get(0);
        int bestSentenceIndex = 0;
        
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            double score = calculateSimilarity(reqText, sentence.toLowerCase());
            
            if (score > bestScore) {
                bestScore = score;
                bestSentence = sentence;
                bestSentenceIndex = i;
            }
        }
        
        if (bestScore < 0.05 && sentences.size() > 1) {
            String prevSentence = bestSentenceIndex > 0 ? sentences.get(bestSentenceIndex - 1) : "";
            String nextSentence = bestSentenceIndex < sentences.size() - 1 ? sentences.get(bestSentenceIndex + 1) : "";
            
            if (!prevSentence.isEmpty()) {
                double prevScore = calculateSimilarity(reqText, prevSentence.toLowerCase());
                if (prevScore > bestScore) {
                    return prevSentence;
                }
            }
            
            if (!nextSentence.isEmpty()) {
                double nextScore = calculateSimilarity(reqText, nextSentence.toLowerCase());
                if (nextScore > bestScore) {
                    return nextSentence;
                }
            }
        }
        
        return bestSentence;
    }

    private double calculateSimilarity(String text1, String text2) {
        if (text1.isEmpty() || text2.isEmpty()) {
            return 0.0;
        }
        
        List<String> words1 = tokenize(text1);
        List<String> words2 = tokenize(text2);
        
        if (words1.isEmpty() || words2.isEmpty()) {
            return 0.0;
        }
        
        double jaccardScore = calculateJaccardSimilarity(words1, words2);
        
        double substringScore = calculateSubstringSimilarity(text1, text2);
        
        double exactMatchScore = calculateExactMatchScore(words1, words2);
        
        double orderScore = calculateOrderScore(words1, words2);
        
        double combinedScore = (jaccardScore * 0.4) + (substringScore * 0.3) + (exactMatchScore * 0.2) + (orderScore * 0.1);
        
        return Math.min(combinedScore, 1.0);
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        
        String[] rawTokens = text.split("[\\s,，。；;、！？？：:()【】《》<>\\-]+");
        for (String token : rawTokens) {
            token = token.trim();
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        
        List<String> ngrams = generateNgrams(text, 2);
        for (String ngram : ngrams) {
            if (!tokens.contains(ngram)) {
                tokens.add(ngram);
            }
        }
        
        return tokens;
    }

    private List<String> generateNgrams(String text, int n) {
        List<String> ngrams = new ArrayList<>();
        text = text.replaceAll("[\\s,，。；;、！？：:()【】《》<>\\-]+", "");
        
        for (int i = 0; i <= text.length() - n; i++) {
            ngrams.add(text.substring(i, i + n));
        }
        
        return ngrams;
    }

    private double calculateJaccardSimilarity(List<String> words1, List<String> words2) {
        int intersection = 0;
        int union = words1.size() + words2.size();
        
        for (String word1 : words1) {
            for (String word2 : words2) {
                if (word1.equals(word2) || word1.contains(word2) || word2.contains(word1)) {
                    intersection++;
                    break;
                }
            }
        }
        
        union -= intersection;
        
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private double calculateSubstringSimilarity(String text1, String text2) {
        double score = 0.0;
        int matchCount = 0;
        
        for (int i = 0; i < text1.length() - 2; i++) {
            String substring = text1.substring(i, i + 3);
            if (text2.contains(substring)) {
                matchCount++;
            }
        }
        
        if (text1.length() > 3) {
            score = (double) matchCount / (text1.length() - 2);
        }
        
        if (text2.contains(text1) || text1.contains(text2)) {
            score += 0.3;
        }
        
        return Math.min(score, 1.0);
    }

    private double calculateExactMatchScore(List<String> words1, List<String> words2) {
        int exactMatches = 0;
        int longWordCount = 0;
        
        for (String word1 : words1) {
            if (word1.length() >= 3) {
                longWordCount++;
                for (String word2 : words2) {
                    if (word1.equals(word2)) {
                        exactMatches++;
                        break;
                    }
                }
            }
        }
        
        return longWordCount == 0 ? 0.0 : (double) exactMatches / longWordCount;
    }

    private double calculateOrderScore(List<String> words1, List<String> words2) {
        int orderMatches = 0;
        int possibleMatches = 0;
        
        for (int i = 0; i < words1.size() - 1; i++) {
            for (int j = 0; j < words2.size() - 1; j++) {
                if (words1.get(i).equals(words2.get(j)) && words1.get(i + 1).equals(words2.get(j + 1))) {
                    orderMatches++;
                    break;
                }
            }
            possibleMatches++;
        }
        
        return possibleMatches == 0 ? 0.0 : (double) orderMatches / possibleMatches;
    }

    public void addAadlTraceability(String sessionId, String aadlContent) {
        try {
            List<TraceabilityRecord> records = traceabilityRecords.get(sessionId);
            if (records == null || records.isEmpty()) {
                log.warn("没有找到需求追溯记录，无法添加 AADL 追溯关系");
                return;
            }

            List<TraceabilityRecord> requirementRecords = new ArrayList<>();
            for (TraceabilityRecord r : records) {
                if ("REQUIREMENT".equals(r.getTraceType())) {
                    requirementRecords.add(r);
                }
            }

            if (requirementRecords.isEmpty()) {
                log.warn("没有找到需求级追溯记录");
                return;
            }

            List<AadlComponentInfo> components = parseAadlComponentsWithInfo(aadlContent);
            
            if (components.isEmpty()) {
                log.warn("没有解析到 AADL 组件");
                return;
            }

            List<TraceabilityMapping> mappings = generateMappingsWithLlm(requirementRecords, components);
            
            for (TraceabilityMapping mapping : mappings) {
                TraceabilityRecord aadlRecord = TraceabilityRecord.builder()
                        .id(UUID.randomUUID().toString())
                        .originalRequirement(mapping.getOriginalRequirement())
                        .requirementId(mapping.getRequirementId())
                        .requirementTitle(mapping.getRequirementTitle())
                        .requirementDescription(mapping.getRequirementDescription())
                        .aadlComponent(mapping.getAadlComponent())
                        .aadlCode(truncate(mapping.getAadlCode(), 2000))
                        .traceType("AADL")
                        .source("LLM")
                        .build();
                records.add(aadlRecord);
            }
            
            log.info("添加了 {} 条 AADL 追溯记录，会话ID: {}", mappings.size(), sessionId);
        } catch (Exception e) {
            log.error("添加 AADL 追溯记录失败: {}", e.getMessage());
            fallbackToSimpleMapping(sessionId, aadlContent);
        }
    }

    private List<TraceabilityMapping> generateMappingsWithLlm(List<TraceabilityRecord> requirements, List<AadlComponentInfo> components) {
        List<TraceabilityMapping> mappings = new ArrayList<>();
        
        StringBuilder requirementsText = new StringBuilder();
        for (int i = 0; i < requirements.size(); i++) {
            TraceabilityRecord req = requirements.get(i);
            requirementsText.append(i + 1).append(". ")
                    .append(req.getRequirementId()).append(": ")
                    .append(req.getRequirementTitle())
                    .append("\n   描述: ").append(req.getRequirementDescription())
                    .append("\n   原始需求: ").append(truncate(req.getOriginalRequirement(), 150))
                    .append("\n\n");
        }

        StringBuilder componentsText = new StringBuilder();
        for (int i = 0; i < components.size(); i++) {
            AadlComponentInfo comp = components.get(i);
            componentsText.append(i + 1).append(". ")
                    .append(comp.getName())
                    .append(" (").append(comp.getType()).append(")")
                    .append("\n   代码摘要: ").append(truncate(comp.getCode(), 200))
                    .append("\n\n");
        }

        String prompt = """
                请分析以下需求与AADL组件之间的追溯关系。
                
                需求列表：
                %s
                
                AADL组件列表：
                %s
                
                请找出每个需求对应的AADL组件（一个需求可能对应多个组件，一个组件也可能对应多个需求）。
                
                请以JSON格式输出，格式如下：
                [
                  {
                    "requirementIndex": 需求编号,
                    "requirementId": "需求ID",
                    "requirementTitle": "需求标题",
                    "requirementDescription": "需求描述",
                    "originalRequirement": "原始需求文本",
                    "componentIndex": 组件编号,
                    "aadlComponent": "组件名称",
                    "aadlType": "组件类型",
                    "mappingReason": "映射原因（简要说明为什么这个需求对应这个组件）"
                  }
                ]
                
                注意：
                1. 只输出JSON数组，不要输出其他任何内容
                2. requirementIndex和componentIndex对应上面列表中的编号（从1开始）
                3. 如果一个需求没有对应任何组件，也请输出，但componentIndex设为0，aadlComponent设为""
                """.formatted(requirementsText.toString(), componentsText.toString());

        try {
            log.info("调用LLM进行AADL追溯分析，需求数: {}, 组件数: {}", requirements.size(), components.size());
            
            String response = ollamaClient.chat(prompt, 0.1, 4096);
            
            if (response != null && !response.trim().isEmpty()) {
                String jsonStr = extractJsonFromResponse(response);
                
                List<Map<String, Object>> rawMappings = objectMapper.readValue(jsonStr, 
                        new TypeReference<List<Map<String, Object>>>() {});
                
                for (Map<String, Object> raw : rawMappings) {
                    int reqIdx = ((Number) raw.get("requirementIndex")).intValue() - 1;
                    int compIdx = ((Number) raw.get("componentIndex")).intValue() - 1;
                    
                    if (reqIdx >= 0 && reqIdx < requirements.size()) {
                        TraceabilityRecord req = requirements.get(reqIdx);
                        String aadlComponent = "";
                        String aadlCode = "";
                        
                        if (compIdx >= 0 && compIdx < components.size()) {
                            AadlComponentInfo comp = components.get(compIdx);
                            aadlComponent = comp.getName();
                            aadlCode = comp.getCode();
                        }
                        
                        mappings.add(new TraceabilityMapping(
                                req.getOriginalRequirement(),
                                req.getRequirementId(),
                                req.getRequirementTitle(),
                                req.getRequirementDescription(),
                                aadlComponent,
                                aadlCode
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.error("LLM追溯分析失败，将使用简单匹配作为降级: {}", e.getMessage());
        }
        
        return mappings.isEmpty() ? generateSimpleMappings(requirements, components) : mappings;
    }

    private List<TraceabilityMapping> generateSimpleMappings(List<TraceabilityRecord> requirements, List<AadlComponentInfo> components) {
        List<TraceabilityMapping> mappings = new ArrayList<>();
        
        for (TraceabilityRecord req : requirements) {
            List<AadlComponentInfo> matchedComponents = new ArrayList<>();
            String reqText = (req.getRequirementTitle() + " " + req.getRequirementDescription()).toLowerCase();
            
            for (AadlComponentInfo comp : components) {
                if (reqText.contains(comp.getName().toLowerCase()) || 
                    comp.getName().toLowerCase().contains(req.getRequirementTitle().toLowerCase().substring(0, Math.min(req.getRequirementTitle().length(), 5)))) {
                    matchedComponents.add(comp);
                }
            }
            
            if (matchedComponents.isEmpty()) {
                for (AadlComponentInfo comp : components) {
                    double score = calculateSimilarity(reqText, comp.getName().toLowerCase());
                    if (score > 0.3) {
                        matchedComponents.add(comp);
                    }
                }
            }
            
            if (matchedComponents.isEmpty()) {
                mappings.add(new TraceabilityMapping(
                        req.getOriginalRequirement(),
                        req.getRequirementId(),
                        req.getRequirementTitle(),
                        req.getRequirementDescription(),
                        "",
                        ""
                ));
            } else {
                for (AadlComponentInfo comp : matchedComponents) {
                    mappings.add(new TraceabilityMapping(
                            req.getOriginalRequirement(),
                            req.getRequirementId(),
                            req.getRequirementTitle(),
                            req.getRequirementDescription(),
                            comp.getName(),
                            comp.getCode()
                    ));
                }
            }
        }
        
        return mappings;
    }

    private void fallbackToSimpleMapping(String sessionId, String aadlContent) {
        try {
            List<TraceabilityRecord> records = traceabilityRecords.get(sessionId);
            if (records == null) return;

            List<TraceabilityRecord> requirementRecords = new ArrayList<>();
            for (TraceabilityRecord r : records) {
                if ("REQUIREMENT".equals(r.getTraceType())) {
                    requirementRecords.add(r);
                }
            }

            List<AadlComponentInfo> components = parseAadlComponentsWithInfo(aadlContent);
            List<TraceabilityMapping> mappings = generateSimpleMappings(requirementRecords, components);
            
            for (TraceabilityMapping mapping : mappings) {
                if (mapping.getAadlComponent() != null && !mapping.getAadlComponent().isEmpty()) {
                    TraceabilityRecord aadlRecord = TraceabilityRecord.builder()
                            .id(UUID.randomUUID().toString())
                            .originalRequirement(mapping.getOriginalRequirement())
                            .requirementId(mapping.getRequirementId())
                            .requirementTitle(mapping.getRequirementTitle())
                            .requirementDescription(mapping.getRequirementDescription())
                            .aadlComponent(mapping.getAadlComponent())
                            .aadlCode(truncate(mapping.getAadlCode(), 2000))
                            .traceType("AADL")
                            .source("SimpleMapping")
                            .build();
                    records.add(aadlRecord);
                }
            }
            
            log.info("降级：使用简单匹配添加了 {} 条 AADL 追溯记录", mappings.size());
        } catch (Exception e) {
            log.error("降级匹配也失败了: {}", e.getMessage());
        }
    }

    private String extractJsonFromResponse(String response) {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        
        if (start != -1 && end != -1 && end > start) {
            return response.substring(start, end + 1);
        }
        
        start = response.indexOf('{');
        end = response.lastIndexOf('}');
        
        if (start != -1 && end != -1 && end > start) {
            return "[" + response.substring(start, end + 1) + "]";
        }
        
        return response;
    }

    private List<AadlComponentInfo> parseAadlComponentsWithInfo(String aadlContent) {
        List<AadlComponentInfo> components = new ArrayList<>();
        String[] lines = aadlContent.split("\n");
        
        String currentComponentName = null;
        String currentComponentType = null;
        StringBuilder currentCode = new StringBuilder();
        int braceCount = 0;
        boolean inComponent = false;
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            
            if (!inComponent) {
                if (trimmedLine.startsWith("system ")) {
                    currentComponentType = "system";
                    currentComponentName = parseComponentName(trimmedLine);
                    inComponent = true;
                    braceCount = countOccurrences(trimmedLine, '{');
                    currentCode.append(line).append("\n");
                } else if (trimmedLine.startsWith("process ")) {
                    currentComponentType = "process";
                    currentComponentName = parseComponentName(trimmedLine);
                    inComponent = true;
                    braceCount = countOccurrences(trimmedLine, '{');
                    currentCode.append(line).append("\n");
                } else if (trimmedLine.startsWith("thread ")) {
                    currentComponentType = "thread";
                    currentComponentName = parseComponentName(trimmedLine);
                    inComponent = true;
                    braceCount = countOccurrences(trimmedLine, '{');
                    currentCode.append(line).append("\n");
                } else if (trimmedLine.startsWith("device ")) {
                    currentComponentType = "device";
                    currentComponentName = parseComponentName(trimmedLine);
                    inComponent = true;
                    braceCount = countOccurrences(trimmedLine, '{');
                    currentCode.append(line).append("\n");
                } else if (trimmedLine.startsWith("component ")) {
                    currentComponentType = "component";
                    currentComponentName = parseComponentName(trimmedLine);
                    inComponent = true;
                    braceCount = countOccurrences(trimmedLine, '{');
                    currentCode.append(line).append("\n");
                } else if (trimmedLine.startsWith("subsystem ")) {
                    currentComponentType = "subsystem";
                    currentComponentName = parseComponentName(trimmedLine);
                    inComponent = true;
                    braceCount = countOccurrences(trimmedLine, '{');
                    currentCode.append(line).append("\n");
                }
            } else {
                currentCode.append(line).append("\n");
                braceCount += countOccurrences(line, '{');
                braceCount -= countOccurrences(line, '}');
                
                if (braceCount <= 0 && currentComponentName != null) {
                    components.add(new AadlComponentInfo(currentComponentName, currentComponentType, currentCode.toString()));
                    currentComponentName = null;
                    currentComponentType = null;
                    currentCode = new StringBuilder();
                    inComponent = false;
                }
            }
        }
        
        return components;
    }

    private String parseComponentName(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            String name = parts[1];
            if (name.endsWith("{")) {
                name = name.substring(0, name.length() - 1);
            }
            return name;
        }
        return "";
    }

    private int countOccurrences(String str, char c) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    private static class AadlComponentInfo {
        private final String name;
        private final String type;
        private final String code;

        public AadlComponentInfo(String name, String type, String code) {
            this.name = name;
            this.type = type;
            this.code = code;
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public String getCode() { return code; }
    }

    private static class TraceabilityMapping {
        private final String originalRequirement;
        private final String requirementId;
        private final String requirementTitle;
        private final String requirementDescription;
        private final String aadlComponent;
        private final String aadlCode;

        public TraceabilityMapping(String originalRequirement, String requirementId, 
                                   String requirementTitle, String requirementDescription,
                                   String aadlComponent, String aadlCode) {
            this.originalRequirement = originalRequirement;
            this.requirementId = requirementId;
            this.requirementTitle = requirementTitle;
            this.requirementDescription = requirementDescription;
            this.aadlComponent = aadlComponent;
            this.aadlCode = aadlCode;
        }

        public String getOriginalRequirement() { return originalRequirement; }
        public String getRequirementId() { return requirementId; }
        public String getRequirementTitle() { return requirementTitle; }
        public String getRequirementDescription() { return requirementDescription; }
        public String getAadlComponent() { return aadlComponent; }
        public String getAadlCode() { return aadlCode; }
    }

    public String generateExcelFile(String sessionId) throws IOException {
        List<TraceabilityRecord> records = traceabilityRecords.get(sessionId);
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("没有找到追溯记录，会话ID: " + sessionId);
        }

        String fileName = "traceability_" + sessionId + "_" + 
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
        
        ensureOutputDirectory();
        Path filePath = Paths.get(outputDirectory, fileName);
        
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("需求追溯矩阵");
            
            Row headerRow = sheet.createRow(0);
            String[] headers = {"记录ID", "原始需求", "需求ID", "需求标题", "需求描述", 
                               "AADL组件", "AADL代码", "追溯类型", "来源"};
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                CellStyle style = workbook.createCellStyle();
                Font font = workbook.createFont();
                font.setBold(true);
                style.setFont(font);
                style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                cell.setCellStyle(style);
            }
            
            int rowNum = 1;
            for (TraceabilityRecord record : records) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(record.getId());
                row.createCell(1).setCellValue(record.getOriginalRequirement());
                row.createCell(2).setCellValue(record.getRequirementId());
                row.createCell(3).setCellValue(record.getRequirementTitle());
                row.createCell(4).setCellValue(record.getRequirementDescription());
                row.createCell(5).setCellValue(record.getAadlComponent());
                row.createCell(6).setCellValue(record.getAadlCode());
                row.createCell(7).setCellValue(record.getTraceType());
                row.createCell(8).setCellValue(record.getSource());
            }
            
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                workbook.write(fos);
            }
        }
        
        log.info("追溯Excel文件已生成: {}", filePath);
        return filePath.toString();
    }

    public String generateCsvFile(String sessionId) throws IOException {
        List<TraceabilityRecord> records = traceabilityRecords.get(sessionId);
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("没有找到追溯记录，会话ID: " + sessionId);
        }

        String fileName = "traceability_" + sessionId + "_" + 
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";
        
        ensureOutputDirectory();
        Path filePath = Paths.get(outputDirectory, fileName);
        
        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            writer.write("\uFEFF");
            writer.write("记录ID,原始需求,需求ID,需求标题,需求描述,AADL组件,AADL代码,追溯类型,来源\n");
            
            for (TraceabilityRecord record : records) {
                writer.write(escapeCsv(record.getId()) + ",");
                writer.write(escapeCsv(record.getOriginalRequirement()) + ",");
                writer.write(escapeCsv(record.getRequirementId()) + ",");
                writer.write(escapeCsv(record.getRequirementTitle()) + ",");
                writer.write(escapeCsv(record.getRequirementDescription()) + ",");
                writer.write(escapeCsv(record.getAadlComponent()) + ",");
                writer.write(escapeCsv(record.getAadlCode()) + ",");
                writer.write(escapeCsv(record.getTraceType()) + ",");
                writer.write(escapeCsv(record.getSource()) + "\n");
            }
        }
        
        log.info("追溯CSV文件已生成: {}", filePath);
        return filePath.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private void ensureOutputDirectory() throws IOException {
        Path path = Paths.get(outputDirectory);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    public List<TraceabilityRecord> getRecords(String sessionId) {
        return traceabilityRecords.getOrDefault(sessionId, new ArrayList<>());
    }

    public void clearRecords(String sessionId) {
        traceabilityRecords.remove(sessionId);
    }
}