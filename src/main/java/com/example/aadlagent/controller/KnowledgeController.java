package com.example.aadlagent.controller;

import com.example.aadlagent.rag.TextChunker;
import com.example.aadlagent.rag.VectorSearchService;
import com.example.aadlagent.rag.model.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final VectorSearchService vectorSearchService;
    
    private static final String KNOWLEDGE_DIR = "./knowledge";

    public KnowledgeController(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addKnowledge(@RequestBody Map<String, Object> request) {
        String content = (String) request.get("content");
        String title = (String) request.get("title");
        
        if (content == null || content.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "内容不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (title == null || title.trim().isEmpty()) {
            title = "知识条目";
        }

        List<String> chunks = TextChunker.chunk(content, 500, 100);
        List<String> addedIds = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            String id = "ADD-" + UUID.randomUUID().toString().substring(0, 8) + "-" + i;
            
            Document doc = Document.builder()
                    .id(id)
                    .content(chunks.get(i))
                    .title(title + " (chunk " + (i + 1) + ")")
                    .source("API添加")
                    .build();
            
            vectorSearchService.addDocument(doc);
            addedIds.add(id);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "知识添加成功");
        response.put("chunkCount", chunks.size());
        response.put("ids", addedIds);
        
        log.info("Added knowledge: {} ({} chunks)", title, chunks.size());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "文件不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".md") && !filename.endsWith(".txt"))) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "只支持 .md 和 .txt 文件");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Path knowledgePath = Paths.get(KNOWLEDGE_DIR);
            if (!Files.exists(knowledgePath)) {
                Files.createDirectories(knowledgePath);
            }
            
            Path filePath = knowledgePath.resolve(filename);
            Files.write(filePath, file.getBytes());
            
            String content = new String(file.getBytes());
            String title = filename.substring(0, filename.lastIndexOf('.'));
            
            List<String> chunks = TextChunker.chunk(content, 500, 100);
            List<String> addedIds = new ArrayList<>();
            
            for (int i = 0; i < chunks.size(); i++) {
                String id = "FILE-" + filename.hashCode() + "-" + i;
                
                Document doc = Document.builder()
                        .id(id)
                        .content(chunks.get(i))
                        .title(title + " (chunk " + (i + 1) + ")")
                        .source(filename)
                        .build();
                
                vectorSearchService.addDocument(doc);
                addedIds.add(id);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "文件上传成功");
            response.put("filename", filename);
            response.put("chunkCount", chunks.size());
            response.put("ids", addedIds);
            
            log.info("Uploaded knowledge file: {} ({} chunks)", filename, chunks.size());
            return ResponseEntity.ok(response);
            
        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "文件上传失败: " + e.getMessage());
            log.error("Failed to upload file: {}", filename, e);
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listDocuments() {
        List<Document> documents = vectorSearchService.getAllDocuments();
        
        List<Map<String, Object>> docList = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", doc.getId());
            item.put("title", doc.getTitle());
            item.put("source", doc.getSource());
            item.put("contentLength", doc.getContent().length());
            docList.add(item);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", docList.size());
        response.put("documents", docList);
        
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable String id) {
        vectorSearchService.removeDocument(id);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "文档删除成功");
        response.put("id", id);
        
        log.info("Deleted document: {}", id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadKnowledge() {
        try {
            vectorSearchService.reload();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "知识库重新加载成功");
            response.put("count", vectorSearchService.getDocumentCount());
            
            log.info("Knowledge base reloaded, {} documents", vectorSearchService.getDocumentCount());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "知识库加载失败: " + e.getMessage());
            log.error("Failed to reload knowledge base", e);
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> getDocumentCount() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", vectorSearchService.getDocumentCount());
        
        return ResponseEntity.ok(response);
    }
}