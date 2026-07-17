package com.example.aadlagent.service;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TraceabilityService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.output.directory:./output}")
    private String outputDirectory;

    private final ConcurrentHashMap<String, List<TraceabilityRecord>> traceabilityRecords = new ConcurrentHashMap<>();

    public void addRequirementTraceability(String sessionId, String originalRequirement, String requirementsJson) {
        try {
            List<Requirement> requirements = objectMapper.readValue(requirementsJson, new TypeReference<List<Requirement>>() {});
            
            List<TraceabilityRecord> records = traceabilityRecords.computeIfAbsent(sessionId, k -> new ArrayList<>());
            
            for (Requirement req : requirements) {
                TraceabilityRecord record = TraceabilityRecord.builder()
                        .id(UUID.randomUUID().toString())
                        .originalRequirement(truncate(originalRequirement, 2000))
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

    public void addAadlTraceability(String sessionId, String aadlContent) {
        try {
            List<TraceabilityRecord> records = traceabilityRecords.get(sessionId);
            if (records == null || records.isEmpty()) {
                log.warn("没有找到需求追溯记录，无法添加 AADL 追溯关系");
                return;
            }

            List<String> aadlComponents = parseAadlComponents(aadlContent);
            
            for (TraceabilityRecord record : records) {
                if ("REQUIREMENT".equals(record.getTraceType())) {
                    for (String component : aadlComponents) {
                        if (matchesRequirement(record, component)) {
                            TraceabilityRecord aadlRecord = TraceabilityRecord.builder()
                                    .id(UUID.randomUUID().toString())
                                    .originalRequirement(record.getOriginalRequirement())
                                    .requirementId(record.getRequirementId())
                                    .requirementTitle(record.getRequirementTitle())
                                    .requirementDescription(record.getRequirementDescription())
                                    .aadlComponent(component)
                                    .aadlCode(truncate(extractComponentCode(aadlContent, component), 2000))
                                    .traceType("AADL")
                                    .source("AadlAgent")
                                    .build();
                            records.add(aadlRecord);
                        }
                    }
                }
            }
            
            log.info("添加了 AADL 追溯记录，会话ID: {}", sessionId);
        } catch (Exception e) {
            log.error("添加 AADL 追溯记录失败: {}", e.getMessage());
        }
    }

    private List<String> parseAadlComponents(String aadlContent) {
        List<String> components = new ArrayList<>();
        String[] lines = aadlContent.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("system ") || line.startsWith("process ") || 
                line.startsWith("thread ") || line.startsWith("device ") ||
                line.startsWith("component ") || line.startsWith("subsystem ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String name = parts[1];
                    if (name.endsWith("{")) {
                        name = name.substring(0, name.length() - 1);
                    }
                    components.add(name);
                }
            }
        }
        
        return components;
    }

    private String extractComponentCode(String aadlContent, String componentName) {
        StringBuilder code = new StringBuilder();
        String[] lines = aadlContent.split("\n");
        boolean inComponent = false;
        int braceCount = 0;
        
        for (String line : lines) {
            if (line.trim().contains(componentName) && (line.contains("{") || line.contains(":"))) {
                inComponent = true;
            }
            
            if (inComponent) {
                code.append(line).append("\n");
                braceCount += countOccurrences(line, '{');
                braceCount -= countOccurrences(line, '}');
                
                if (braceCount <= 0) {
                    break;
                }
            }
        }
        
        return code.toString().trim();
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

    private boolean matchesRequirement(TraceabilityRecord record, String component) {
        String title = record.getRequirementTitle().toLowerCase();
        String desc = record.getRequirementDescription() != null ? record.getRequirementDescription().toLowerCase() : "";
        String comp = component.toLowerCase();
        
        return title.contains(comp) || desc.contains(comp) || 
               comp.contains(title.substring(0, Math.min(title.length(), 10)).toLowerCase()) ||
               comp.contains(title.split("\\s+")[0].toLowerCase());
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