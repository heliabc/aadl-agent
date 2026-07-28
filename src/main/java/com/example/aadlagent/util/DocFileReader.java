package com.example.aadlagent.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class DocFileReader {

    public String readFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("文件不存在: " + filePath);
        }

        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".docx")) {
            return readDocxFile(file);
        } else if (fileName.endsWith(".doc")) {
            return readDocFileWithFallback(file);
        } else if (fileName.endsWith(".txt")) {
            return readTxtFile(file);
        } else if (fileName.endsWith(".json")) {
            return readTxtFile(file);
        } else if (fileName.endsWith(".aadl")) {
            return readTxtFile(file);
        } else {
            throw new IOException("不支持的文件格式: " + fileName);
        }
    }

    private String readDocFileWithFallback(File file) throws IOException {
        try {
            return readDocFile(file);
        } catch (Exception e) {
            log.warn("使用HWPF解析.doc文件失败，尝试用XWPF解析（可能是.docx重命名）: {}", e.getMessage());
            try {
                return readDocxFile(file);
            } catch (Exception e2) {
                log.error("使用XWPF解析也失败: {}", e2.getMessage());
                throw new IOException("无法解析文件: " + file.getName() + ", 错误: " + e2.getMessage());
            }
        }
    }

    private String readDocFile(File file) throws IOException {
        try (InputStream is = new FileInputStream(file);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {
            StringBuilder text = new StringBuilder();
            
            // 使用WordExtractor提取文本（包含段落）
            text.append(extractor.getText());
            
            // 补充解析表格内容
            org.apache.poi.hwpf.usermodel.TableIterator tableIterator = new org.apache.poi.hwpf.usermodel.TableIterator(document.getRange());
            while (tableIterator.hasNext()) {
                org.apache.poi.hwpf.usermodel.Table table = tableIterator.next();
                for (int i = 0; i < table.numRows(); i++) {
                    org.apache.poi.hwpf.usermodel.TableRow row = table.getRow(i);
                    StringBuilder rowText = new StringBuilder();
                    for (int j = 0; j < row.numCells(); j++) {
                        org.apache.poi.hwpf.usermodel.TableCell cell = row.getCell(j);
                        String cellText = cell.text().trim();
                        if (!cellText.isEmpty()) {
                            if (rowText.length() > 0) {
                                rowText.append(" | ");
                            }
                            rowText.append(cellText);
                        }
                    }
                    if (rowText.length() > 0) {
                        text.append("\n").append(rowText);
                    }
                }
                text.append("\n");
            }
            
            return text.toString().trim();
        }
    }

    private String readDocxFile(File file) throws IOException {
        try (InputStream is = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(is)) {
            StringBuilder text = new StringBuilder();
            
            // 解析段落内容
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String paragraphText = paragraph.getText();
                if (paragraphText != null && !paragraphText.trim().isEmpty()) {
                    text.append(paragraphText).append("\n");
                }
            }
            
            // 解析表格内容（需求文档中常见表格形式的需求列表）
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder rowText = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String cellText = cell.getText();
                        if (cellText != null && !cellText.trim().isEmpty()) {
                            if (rowText.length() > 0) {
                                rowText.append(" | ");
                            }
                            rowText.append(cellText.trim());
                        }
                    }
                    if (rowText.length() > 0) {
                        text.append(rowText).append("\n");
                    }
                }
                // 表格之间添加空行分隔
                text.append("\n");
            }
            
            return text.toString().trim();
        }
    }

    private String readTxtFile(File file) throws IOException {
        return readTxtFileWithEncoding(file, Arrays.asList(
                StandardCharsets.UTF_8,
                Charset.forName("GBK"),
                Charset.forName("GB2312"),
                StandardCharsets.ISO_8859_1
        ));
    }

    private String readTxtFileWithEncoding(File file, List<Charset> charsets) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        
        for (Charset charset : charsets) {
            try {
                String text = new String(bytes, charset);
                // 简单验证：检查是否包含大量乱码字符
                if (!containsGarbledText(text)) {
                    log.debug("成功使用编码 {} 读取文件: {}", charset.name(), file.getName());
                    return text;
                }
            } catch (Exception e) {
                log.debug("使用编码 {} 读取文件失败: {}", charset.name(), e.getMessage());
            }
        }
        
        // 默认使用UTF-8
        log.warn("所有编码都失败，使用默认UTF-8读取文件: {}", file.getName());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private boolean containsGarbledText(String text) {
        // 检查是否包含常见的乱码字符模式
        int garbledCount = 0;
        for (char c : text.toCharArray()) {
            // 检查是否是常见的GBK/UTF-8乱码字符
            if ((c >= 0xFFFD && c <= 0xFFFF) ||  // Unicode替换字符及私有区域
                (c >= 0xA0 && c <= 0xFF && !Character.isLetterOrDigit(c))) {  // 扩展ASCII区域的非字母数字
                garbledCount++;
            }
        }
        // 如果乱码字符超过总字符的10%，认为是乱码
        return garbledCount > text.length() * 0.1;
    }

    public List<String> listFiles(String directoryPath, List<String> extensions) throws IOException {
        Path dir = Paths.get(directoryPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IOException("目录不存在: " + directoryPath);
        }

        try (Stream<Path> paths = Files.list(dir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return !fileName.startsWith("~$");
                    })
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return extensions.stream().anyMatch(ext -> fileName.endsWith(ext.toLowerCase()));
                    })
                    .map(Path::toString)
                    .collect(Collectors.toList());
        }
    }

    public void writeFile(String content, String filePath) throws IOException {
        Path path = Paths.get(filePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        log.info("文件已写入: {}", filePath);
    }
}