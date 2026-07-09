package com.example.aadlagent.controller;

import com.example.aadlagent.rag.KnowledgeBaseManager;
import com.example.aadlagent.rag.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseManager knowledgeBaseManager;

    public KnowledgeController(KnowledgeBaseManager knowledgeBaseManager) {
        this.knowledgeBaseManager = knowledgeBaseManager;
    }

    @GetMapping("/bases")
    public ResponseEntity<Map<String, Object>> listKnowledgeBases() {
        List<String> bases = knowledgeBaseManager.listKnowledgeBases();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("bases", bases);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{agentType}")
    public ResponseEntity<Map<String, Object>> getKnowledgeBase(@PathVariable String agentType) {
        KnowledgeBase kb = knowledgeBaseManager.getKnowledgeBase(agentType);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("agentType", agentType);
        response.put("basics", kb.getBasics());
        response.put("examples", kb.getExamples());
        response.put("errorCorrections", kb.getErrorCorrections());
        response.put("totalEntries", kb.getTotalEntries());
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{agentType}/count")
    public ResponseEntity<Map<String, Object>> getEntryCount(@PathVariable String agentType) {
        KnowledgeBase kb = knowledgeBaseManager.getKnowledgeBase(agentType);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("agentType", agentType);
        response.put("total", kb.getTotalEntries());
        response.put("basics", kb.getBasics().size());
        response.put("examples", kb.getExamples().size());
        response.put("errorCorrections", kb.getErrorCorrections().size());
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{agentType}/basics")
    public ResponseEntity<Map<String, Object>> listBasics(@PathVariable String agentType) {
        List<BasicKnowledge> basics = knowledgeBaseManager.getBasics(agentType);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("agentType", agentType);
        response.put("count", basics.size());
        response.put("basics", basics);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{agentType}/basics/{basicId}")
    public ResponseEntity<Map<String, Object>> getBasic(@PathVariable String agentType, @PathVariable String basicId) {
        BasicKnowledge basic = knowledgeBaseManager.getBasic(agentType, basicId);
        
        Map<String, Object> response = new HashMap<>();
        if (basic != null) {
            response.put("success", true);
            response.put("basic", basic);
        } else {
            response.put("success", false);
            response.put("message", "基础知识不存在");
        }
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{agentType}/basics")
    public ResponseEntity<Map<String, Object>> addBasic(@PathVariable String agentType, @RequestBody BasicKnowledge basic) {
        if (basic.getContent() == null || basic.getContent().trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "内容不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (basic.getTitle() == null || basic.getTitle().trim().isEmpty()) {
            basic.setTitle("基础知识");
        }

        BasicKnowledge saved = knowledgeBaseManager.addBasic(agentType, basic);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "基础知识添加成功");
        response.put("basic", saved);
        
        log.info("Added basic knowledge to {}: {}", agentType, saved.getId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{agentType}/basics/{basicId}")
    public ResponseEntity<Map<String, Object>> updateBasic(
            @PathVariable String agentType, 
            @PathVariable String basicId, 
            @RequestBody BasicKnowledge basic) {
        
        boolean updated = knowledgeBaseManager.updateBasic(agentType, basicId, basic);
        
        Map<String, Object> response = new HashMap<>();
        if (updated) {
            response.put("success", true);
            response.put("message", "基础知识更新成功");
            response.put("basic", knowledgeBaseManager.getBasic(agentType, basicId));
        } else {
            response.put("success", false);
            response.put("message", "基础知识不存在");
        }
        
        log.info("Updated basic knowledge in {}: {}", agentType, basicId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{agentType}/basics/{basicId}")
    public ResponseEntity<Map<String, Object>> deleteBasic(@PathVariable String agentType, @PathVariable String basicId) {
        boolean deleted = knowledgeBaseManager.deleteBasic(agentType, basicId);
        
        Map<String, Object> response = new HashMap<>();
        if (deleted) {
            response.put("success", true);
            response.put("message", "基础知识删除成功");
            response.put("basicId", basicId);
        } else {
            response.put("success", false);
            response.put("message", "基础知识不存在");
        }
        
        log.info("Deleted basic knowledge from {}: {}", agentType, basicId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{agentType}/examples")
    public ResponseEntity<Map<String, Object>> listExamples(@PathVariable String agentType) {
        List<ExampleKnowledge> examples = knowledgeBaseManager.getExamples(agentType);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("agentType", agentType);
        response.put("count", examples.size());
        response.put("examples", examples);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{agentType}/examples/{exampleId}")
    public ResponseEntity<Map<String, Object>> getExample(@PathVariable String agentType, @PathVariable String exampleId) {
        ExampleKnowledge example = knowledgeBaseManager.getExample(agentType, exampleId);
        
        Map<String, Object> response = new HashMap<>();
        if (example != null) {
            response.put("success", true);
            response.put("example", example);
        } else {
            response.put("success", false);
            response.put("message", "示例不存在");
        }
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{agentType}/examples")
    public ResponseEntity<Map<String, Object>> addExample(@PathVariable String agentType, @RequestBody ExampleKnowledge example) {
        if (example.getInput() == null || example.getInput().trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "输入不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (example.getTitle() == null || example.getTitle().trim().isEmpty()) {
            example.setTitle("示例");
        }

        ExampleKnowledge saved = knowledgeBaseManager.addExample(agentType, example);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "示例添加成功");
        response.put("example", saved);
        
        log.info("Added example to {}: {}", agentType, saved.getId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{agentType}/examples/{exampleId}")
    public ResponseEntity<Map<String, Object>> updateExample(
            @PathVariable String agentType, 
            @PathVariable String exampleId, 
            @RequestBody ExampleKnowledge example) {
        
        boolean updated = knowledgeBaseManager.updateExample(agentType, exampleId, example);
        
        Map<String, Object> response = new HashMap<>();
        if (updated) {
            response.put("success", true);
            response.put("message", "示例更新成功");
            response.put("example", knowledgeBaseManager.getExample(agentType, exampleId));
        } else {
            response.put("success", false);
            response.put("message", "示例不存在");
        }
        
        log.info("Updated example in {}: {}", agentType, exampleId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{agentType}/examples/{exampleId}")
    public ResponseEntity<Map<String, Object>> deleteExample(@PathVariable String agentType, @PathVariable String exampleId) {
        boolean deleted = knowledgeBaseManager.deleteExample(agentType, exampleId);
        
        Map<String, Object> response = new HashMap<>();
        if (deleted) {
            response.put("success", true);
            response.put("message", "示例删除成功");
            response.put("exampleId", exampleId);
        } else {
            response.put("success", false);
            response.put("message", "示例不存在");
        }
        
        log.info("Deleted example from {}: {}", agentType, exampleId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{agentType}/error-corrections")
    public ResponseEntity<Map<String, Object>> listErrorCorrections(@PathVariable String agentType) {
        List<ErrorCorrection> corrections = knowledgeBaseManager.getErrorCorrections(agentType);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("agentType", agentType);
        response.put("count", corrections.size());
        response.put("errorCorrections", corrections);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{agentType}/error-corrections/{ecId}")
    public ResponseEntity<Map<String, Object>> getErrorCorrection(@PathVariable String agentType, @PathVariable String ecId) {
        ErrorCorrection ec = knowledgeBaseManager.getErrorCorrection(agentType, ecId);
        
        Map<String, Object> response = new HashMap<>();
        if (ec != null) {
            response.put("success", true);
            response.put("errorCorrection", ec);
        } else {
            response.put("success", false);
            response.put("message", "错误修正不存在");
        }
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{agentType}/error-corrections")
    public ResponseEntity<Map<String, Object>> addErrorCorrection(@PathVariable String agentType, @RequestBody ErrorCorrection ec) {
        if (ec.getErrorContent() == null || ec.getErrorContent().trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "错误内容不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (ec.getCorrectContent() == null || ec.getCorrectContent().trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "正确内容不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (ec.getTitle() == null || ec.getTitle().trim().isEmpty()) {
            ec.setTitle("错误修正");
        }

        ErrorCorrection saved = knowledgeBaseManager.addErrorCorrection(agentType, ec);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "错误修正添加成功");
        response.put("errorCorrection", saved);
        
        log.info("Added error correction to {}: {}", agentType, saved.getId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{agentType}/error-corrections/{ecId}")
    public ResponseEntity<Map<String, Object>> updateErrorCorrection(
            @PathVariable String agentType, 
            @PathVariable String ecId, 
            @RequestBody ErrorCorrection ec) {
        
        boolean updated = knowledgeBaseManager.updateErrorCorrection(agentType, ecId, ec);
        
        Map<String, Object> response = new HashMap<>();
        if (updated) {
            response.put("success", true);
            response.put("message", "错误修正更新成功");
            response.put("errorCorrection", knowledgeBaseManager.getErrorCorrection(agentType, ecId));
        } else {
            response.put("success", false);
            response.put("message", "错误修正不存在");
        }
        
        log.info("Updated error correction in {}: {}", agentType, ecId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{agentType}/error-corrections/{ecId}")
    public ResponseEntity<Map<String, Object>> deleteErrorCorrection(@PathVariable String agentType, @PathVariable String ecId) {
        boolean deleted = knowledgeBaseManager.deleteErrorCorrection(agentType, ecId);
        
        Map<String, Object> response = new HashMap<>();
        if (deleted) {
            response.put("success", true);
            response.put("message", "错误修正删除成功");
            response.put("ecId", ecId);
        } else {
            response.put("success", false);
            response.put("message", "错误修正不存在");
        }
        
        log.info("Deleted error correction from {}: {}", agentType, ecId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{agentType}/reload")
    public ResponseEntity<Map<String, Object>> reloadKnowledgeBase(@PathVariable String agentType) {
        try {
            knowledgeBaseManager.reloadKnowledgeBase(agentType);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "知识库重新加载成功");
            response.put("agentType", agentType);
            
            KnowledgeBase kb = knowledgeBaseManager.getKnowledgeBase(agentType);
            response.put("total", kb.getTotalEntries());
            response.put("basics", kb.getBasics().size());
            response.put("examples", kb.getExamples().size());
            response.put("errorCorrections", kb.getErrorCorrections().size());
            
            log.info("Knowledge base {} reloaded", agentType);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "知识库加载失败: " + e.getMessage());
            log.error("Failed to reload knowledge base {}", agentType, e);
            return ResponseEntity.badRequest().body(error);
        }
    }
}