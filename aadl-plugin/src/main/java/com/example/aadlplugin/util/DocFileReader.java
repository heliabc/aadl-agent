package com.example.aadlplugin.util;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.logging.Logger;

public class DocFileReader {

    private static final Logger log = Logger.getLogger(DocFileReader.class.getName());

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
            log.warning("使用HWPF解析.doc文件失败，尝试用XWPF解析（可能是.docx重命名）: " + e.getMessage());
            try {
                return readDocxFile(file);
            } catch (Exception e2) {
                log.severe("使用XWPF解析也失败: " + e2.getMessage());
                throw new IOException("无法解析文件: " + file.getName() + ", 错误: " + e2.getMessage());
            }
        }
    }

    private String readDocFile(File file) throws IOException {
        try (InputStream is = new FileInputStream(file);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String readDocxFile(File file) throws IOException {
        try (InputStream is = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(is)) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String paragraphText = paragraph.getText();
                if (paragraphText != null && !paragraphText.trim().isEmpty()) {
                    text.append(paragraphText).append("\n");
                }
            }
            return text.toString().trim();
        }
    }

    private String readTxtFile(File file) throws IOException {
        return Files.readString(file.toPath());
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
        log.info("文件已写入: " + filePath);
    }
}
