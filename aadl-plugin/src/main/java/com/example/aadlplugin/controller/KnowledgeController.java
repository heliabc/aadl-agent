package com.example.aadlplugin.controller;

import com.example.aadlplugin.rag.KnowledgeBaseManager;
import com.example.aadlplugin.rag.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class KnowledgeController {

    private static final Logger log = Logger.getLogger(KnowledgeController.class.getName());

    private final KnowledgeBaseManager knowledgeBaseManager;

    public KnowledgeController(KnowledgeBaseManager knowledgeBaseManager) {
        this.knowledgeBaseManager = knowledgeBaseManager;
    }

    public Map<String, Object> listKnowledgeBases() {
        List<String> bases = knowledgeBaseManager.listKnowledgeBases();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("bases", bases);

        return response;
    }

    public Map<String, Object> getKnowledgeBase(String agentType) {
        KnowledgeBase kb = knowledgeBaseManager.getKnowledgeBase(agentType);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("agentType", agentType);
        response.put("basics", kb.getBasics());
        response.put("examples", kb.getExamples());
        response.put("errorCorrections", kb.getErrorCorrections());
        response.put("totalEntries", kb.getTotalEntries());

        return response;
    }

    public Map<String, Object> getEntryCount(String agentType) {
        KnowledgeBase kb = knowledgeBaseManager.getKnowledgeBase(agentType);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("agentType", agentType);
        response.put("total", kb.getTotalEntries());
        response.put("basics", kb.getBasics().size());
        response.put("examples", kb.getExamples().size());
        response.put("errorCorrections", kb.getErrorCorrections().size());

        return response;
    }

    public Map<String, Object> listBasics(String agentType) {
        List<BasicKnowledge> basics = knowledgeBaseManager.getBasics(agentType);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("agentType", agentType);
        response.put("count", basics.size());
        response.put("basics", basics);

        return response;
    }

    public Map<String, Object> getBasic(String agentType, String basicId) {
        BasicKnowledge basic = knowledgeBaseManager.getBasic(agentType, basicId);

        Map<String, Object> response = new HashMap<>();
        if (basic != null) {
            response.put("success", true);
            response.put("basic", basic);
        } else {
            response.put("success", false);
            response.put("message", "基础知识不存在");
        }

        return response;
    }

    public Map<String, Object> addBasic(String agentType, BasicKnowledge basic) {
        Map<String, Object> response = new HashMap<>();

        if (basic.getContent() == null || basic.getContent().trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "内容不能为空");
            return response;
        }

        if (basic.getTitle() == null || basic.getTitle().trim().isEmpty()) {
            basic.setTitle("基础知识");
        }

        BasicKnowledge saved = knowledgeBaseManager.addBasic(agentType, basic);

        response.put("success", true);
        response.put("message", "基础知识添加成功");
        response.put("basic", saved);

        log.info("Added basic knowledge to " + agentType + ": " + saved.getId());
        return response;
    }

    public Map<String, Object> updateBasic(String agentType, String basicId, BasicKnowledge basic) {
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

        log.info("Updated basic knowledge in " + agentType + ": " + basicId);
        return response;
    }

    public Map<String, Object> deleteBasic(String agentType, String basicId) {
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

        log.info("Deleted basic knowledge from " + agentType + ": " + basicId);
        return response;
    }

    public Map<String, Object> listExamples(String agentType) {
        List<ExampleKnowledge> examples = knowledgeBaseManager.getExamples(agentType);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("agentType", agentType);
        response.put("count", examples.size());
        response.put("examples", examples);

        return response;
    }

    public Map<String, Object> getExample(String agentType, String exampleId) {
        ExampleKnowledge example = knowledgeBaseManager.getExample(agentType, exampleId);

        Map<String, Object> response = new HashMap<>();
        if (example != null) {
            response.put("success", true);
            response.put("example", example);
        } else {
            response.put("success", false);
            response.put("message", "示例不存在");
        }

        return response;
    }

    public Map<String, Object> addExample(String agentType, ExampleKnowledge example) {
        Map<String, Object> response = new HashMap<>();

        if (example.getInput() == null || example.getInput().trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "输入不能为空");
            return response;
        }

        if (example.getTitle() == null || example.getTitle().trim().isEmpty()) {
            example.setTitle("示例");
        }

        ExampleKnowledge saved = knowledgeBaseManager.addExample(agentType, example);

        response.put("success", true);
        response.put("message", "示例添加成功");
        response.put("example", saved);

        log.info("Added example to " + agentType + ": " + saved.getId());
        return response;
    }

    public Map<String, Object> updateExample(String agentType, String exampleId, ExampleKnowledge example) {
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

        log.info("Updated example in " + agentType + ": " + exampleId);
        return response;
    }

    public Map<String, Object> deleteExample(String agentType, String exampleId) {
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

        log.info("Deleted example from " + agentType + ": " + exampleId);
        return response;
    }

    public Map<String, Object> listErrorCorrections(String agentType) {
        List<ErrorCorrection> corrections = knowledgeBaseManager.getErrorCorrections(agentType);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("agentType", agentType);
        response.put("count", corrections.size());
        response.put("errorCorrections", corrections);

        return response;
    }

    public Map<String, Object> getErrorCorrection(String agentType, String ecId) {
        ErrorCorrection ec = knowledgeBaseManager.getErrorCorrection(agentType, ecId);

        Map<String, Object> response = new HashMap<>();
        if (ec != null) {
            response.put("success", true);
            response.put("errorCorrection", ec);
        } else {
            response.put("success", false);
            response.put("message", "错误修正不存在");
        }

        return response;
    }

    public Map<String, Object> addErrorCorrection(String agentType, ErrorCorrection ec) {
        Map<String, Object> response = new HashMap<>();

        if (ec.getErrorContent() == null || ec.getErrorContent().trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "错误内容不能为空");
            return response;
        }

        if (ec.getCorrectContent() == null || ec.getCorrectContent().trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "正确内容不能为空");
            return response;
        }

        if (ec.getTitle() == null || ec.getTitle().trim().isEmpty()) {
            ec.setTitle("错误修正");
        }

        ErrorCorrection saved = knowledgeBaseManager.addErrorCorrection(agentType, ec);

        response.put("success", true);
        response.put("message", "错误修正添加成功");
        response.put("errorCorrection", saved);

        log.info("Added error correction to " + agentType + ": " + saved.getId());
        return response;
    }

    public Map<String, Object> updateErrorCorrection(String agentType, String ecId, ErrorCorrection ec) {
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

        log.info("Updated error correction in " + agentType + ": " + ecId);
        return response;
    }

    public Map<String, Object> deleteErrorCorrection(String agentType, String ecId) {
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

        log.info("Deleted error correction from " + agentType + ": " + ecId);
        return response;
    }

    public Map<String, Object> reloadKnowledgeBase(String agentType) {
        Map<String, Object> response = new HashMap<>();

        try {
            knowledgeBaseManager.reloadKnowledgeBase(agentType);

            response.put("success", true);
            response.put("message", "知识库重新加载成功");
            response.put("agentType", agentType);

            KnowledgeBase kb = knowledgeBaseManager.getKnowledgeBase(agentType);
            response.put("total", kb.getTotalEntries());
            response.put("basics", kb.getBasics().size());
            response.put("examples", kb.getExamples().size());
            response.put("errorCorrections", kb.getErrorCorrections().size());

            log.info("Knowledge base " + agentType + " reloaded");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "知识库加载失败: " + e.getMessage());
            log.severe("Failed to reload knowledge base " + agentType + ": " + e.getMessage());
        }

        return response;
    }
}