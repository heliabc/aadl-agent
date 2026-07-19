package com.example.aadlplugin.controller;

import com.example.aadlplugin.agent.AgentInput;
import com.example.aadlplugin.agent.AgentOutput;
import com.example.aadlplugin.agent.aadl.AadlFixerAgent;
import com.example.aadlplugin.agent.aadl.AadlGeneratorAgent;
import com.example.aadlplugin.agent.architecture.AadlArchitectureAgent;
import com.example.aadlplugin.agent.module.ModuleAnalysisAgent;
import com.example.aadlplugin.agent.requirement.RequirementAgent;
import com.example.aadlplugin.client.ModelService;
import com.example.aadlplugin.client.ModelType;
import com.example.aadlplugin.config.DeepSeekConfig;
import com.example.aadlplugin.config.FileConfig;
import com.example.aadlplugin.rag.RagService;
import com.example.aadlplugin.session.ChatMessage;
import com.example.aadlplugin.session.SessionManager;
import com.example.aadlplugin.service.TraceabilityService;
import com.example.aadlplugin.util.DocFileReader;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

public class RequirementController {

    private static final Logger log = Logger.getLogger(RequirementController.class.getName());
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RequirementAgent requirementAgent;
    private final AadlArchitectureAgent architectureAgent;
    private final ModuleAnalysisAgent moduleAnalysisAgent;
    private final AadlGeneratorAgent aadlGeneratorAgent;
    private final AadlFixerAgent aadlFixerAgent;
    private final DocFileReader docFileReader;
    private final FileConfig fileConfig;
    private final ModelService modelService;
    private final DeepSeekConfig deepSeekConfig;
    private final RagService ragService;
    private final SessionManager sessionManager;
    private final TraceabilityService traceabilityService;

    public RequirementController(RequirementAgent requirementAgent, AadlArchitectureAgent architectureAgent,
                                 ModuleAnalysisAgent moduleAnalysisAgent, AadlGeneratorAgent aadlGeneratorAgent,
                                 AadlFixerAgent aadlFixerAgent,
                                 DocFileReader docFileReader, FileConfig fileConfig,
                                 ModelService modelService, DeepSeekConfig deepSeekConfig,
                                 RagService ragService, SessionManager sessionManager,
                                 TraceabilityService traceabilityService) {
        this.requirementAgent = requirementAgent;
        this.architectureAgent = architectureAgent;
        this.moduleAnalysisAgent = moduleAnalysisAgent;
        this.aadlGeneratorAgent = aadlGeneratorAgent;
        this.aadlFixerAgent = aadlFixerAgent;
        this.docFileReader = docFileReader;
        this.fileConfig = fileConfig;
        this.modelService = modelService;
        this.deepSeekConfig = deepSeekConfig;
        this.ragService = ragService;
        this.sessionManager = sessionManager;
        this.traceabilityService = traceabilityService;
    }

    public Map<String, Object> analyzeRequirements(String requirementDoc, String modelTypeStr, String sessionId) {
        Map<String, Object> response = new HashMap<>();

        if (requirementDoc == null || requirementDoc.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "需求文档内容不能为空");
            return response;
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        log.info("Received requirement analysis request, content length: " + requirementDoc.length() + " characters, model: " + modelType.name() + ", session: " + sessionId);

        String ragContext = ragService.getEnhancedContext(requirementDoc, "requirement");
        String sessionContext = sessionManager.buildContext(sessionId, 10);
        if (sessionContext != null && !sessionContext.isEmpty()) {
            ragContext = sessionContext + "\n\n" + ragContext;
        }

        sessionManager.addMessage(sessionId, ChatMessage.user(requirementDoc));

        AgentInput input = AgentInput.builder()
                .sessionId(sessionId)
                .content(requirementDoc)
                .modelType(modelType)
                .ragContext(ragContext)
                .build();

        AgentOutput output = requirementAgent.execute(input);

        if (output.isSuccess()) {
            sessionManager.addMessage(sessionId, ChatMessage.assistant(output.getContent(), "RequirementAgent"));
        }

        response.put("success", output.isSuccess());
        response.put("sessionId", sessionId);
        response.put("executionTime", output.getExecutionTime());
        response.put("model", modelType.name());

        if (output.isSuccess()) {
            response.put("data", output.getContent());
            traceabilityService.addRequirementTraceability(sessionId, requirementDoc, output.getContent());

            String outputFileName = "requirements_" + sessionId.substring(0, 8) + ".json";
            String outputFilePath = Paths.get(fileConfig.getRequirementsPath(), outputFileName).toString();
            try {
                docFileReader.writeFile(output.getContent(), outputFilePath);
                response.put("outputFile", outputFileName);
                log.info("Saved requirements file: " + outputFilePath);
            } catch (IOException e) {
                log.warning("Failed to save requirements file: " + e.getMessage());
            }
        } else {
            response.put("message", output.getErrorMessage());
        }

        return response;
    }

    public Map<String, Object> processFiles(String model) {
        long startTime = System.currentTimeMillis();

        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        ModelType modelType = parseModelType(model);

        try {
            List<String> supportedExtensions = Arrays.asList(".doc", ".docx", ".txt");
            List<String> files = docFileReader.listFiles(fileConfig.getInputPath(), supportedExtensions);

            log.info("Found " + files.size() + " files to process in input directory, model: " + modelType.name());

            if (files.isEmpty()) {
                response.put("success", false);
                response.put("message", "input目录下没有找到需要处理的文档文件（支持.doc, .docx, .txt格式）");
                return response;
            }

            for (String filePath : files) {
                Map<String, Object> fileResult = processFile(filePath, modelType);
                results.add(fileResult);
                if ("success".equals(fileResult.get("status"))) {
                    successCount++;
                } else {
                    failCount++;
                }
            }

            long totalTime = System.currentTimeMillis() - startTime;

            response.put("success", true);
            response.put("totalFiles", files.size());
            response.put("successCount", successCount);
            response.put("failCount", failCount);
            response.put("totalTime", totalTime);
            response.put("model", modelType.name());
            response.put("results", results);

            log.info("File processing completed: " + successCount + " success, " + failCount + " fail, total time: " + totalTime + "ms, model: " + modelType.name());

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "扫描input目录失败: " + e.getMessage());
            log.severe("Error scanning input directory: " + e.getMessage());
        }

        return response;
    }

    public Map<String, Object> processFileByName(String fileName, String modelTypeStr) {
        Map<String, Object> response = new HashMap<>();

        if (fileName == null || fileName.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "文件名不能为空");
            return response;
        }

        ModelType modelType = parseModelType(modelTypeStr);

        try {
            String filePath = Paths.get(fileConfig.getInputPath(), fileName).toString();
            Map<String, Object> result = processFile(filePath, modelType);

            result.put("success", "success".equals(result.get("status")));
            result.put("model", modelType.name());

            return result;
        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "文件不存在或无法访问: " + fileName);
            return response;
        }
    }

    private Map<String, Object> processFile(String filePath, ModelType modelType) throws IOException {
        Map<String, Object> fileResult = new HashMap<>();
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();

        fileResult.put("fileName", fileName);

        try {
            log.info("Processing file: " + fileName + ", model: " + modelType.name());

            String content = docFileReader.readFile(filePath);

            String ragContext = ragService.getEnhancedContext(content, "requirement");

            AgentInput input = AgentInput.builder()
                    .sessionId(UUID.randomUUID().toString())
                    .content(content)
                    .modelType(modelType)
                    .ragContext(ragContext)
                    .build();

            AgentOutput output = requirementAgent.execute(input);

            if (output.isSuccess()) {
                String outputFileName = fileName.replaceAll("\\.(doc|docx|txt)$", ".json");
                String outputFilePath = Paths.get(fileConfig.getRequirementsPath(), outputFileName).toString();
                docFileReader.writeFile(output.getContent(), outputFilePath);

                fileResult.put("status", "success");
                fileResult.put("outputFile", outputFileName);
                fileResult.put("executionTime", output.getExecutionTime());
                log.info("Successfully processed file: " + fileName + " -> " + outputFileName + ", model: " + modelType.name());
            } else {
                fileResult.put("status", "failed");
                fileResult.put("error", output.getErrorMessage());
                log.warning("Failed to process file: " + fileName + ", error: " + output.getErrorMessage() + ", model: " + modelType.name());
            }

        } catch (IOException e) {
            fileResult.put("status", "failed");
            fileResult.put("error", "读取文件失败: " + e.getMessage());
            log.severe("Error reading file: " + fileName + ", error: " + e.getMessage());
        }

        return fileResult;
    }

    public Map<String, Object> generateArchitecture(String fileName, String modelTypeStr, String sessionId) {
        Map<String, Object> response = new HashMap<>();

        if (fileName == null || fileName.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "文件名不能为空");
            return response;
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            String requirementsFilePath = Paths.get(fileConfig.getRequirementsPath(), fileName).toString();

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(requirementsFilePath))) {
                response.put("success", false);
                response.put("message", "需求文件不存在，请先运行需求分析: " + fileName);
                return response;
            }

            String requirementsJson = docFileReader.readFile(requirementsFilePath);

            String ragContext = ragService.getEnhancedContext(requirementsJson, "architecture");
            String sessionContext = sessionManager.buildContext(sessionId, 10);
            if (sessionContext != null && !sessionContext.isEmpty()) {
                ragContext = sessionContext + "\n\n" + ragContext;
            }

            sessionManager.addMessage(sessionId, ChatMessage.user("生成架构: " + fileName));

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(requirementsJson)
                    .modelType(modelType)
                    .ragContext(ragContext)
                    .build();

            AgentOutput output = architectureAgent.execute(input);

            if (output.isSuccess()) {
                sessionManager.addMessage(sessionId, ChatMessage.assistant("架构生成成功", "AadlArchitectureAgent"));
            }

            response.put("success", output.isSuccess());
            response.put("sessionId", sessionId);
            response.put("executionTime", output.getExecutionTime());
            response.put("model", modelType.name());

            if (output.isSuccess()) {
                String architectureFileName = fileName.replaceAll("\\.(json)$", "-architecture.json");
                String architectureFilePath = Paths.get(fileConfig.getArchitecturePath(), architectureFileName).toString();
                docFileReader.writeFile(output.getContent(), architectureFilePath);

                response.put("data", output.getContent());
                response.put("outputFile", architectureFileName);
                log.info("Successfully generated architecture: " + fileName + " -> " + architectureFileName);
            } else {
                response.put("message", output.getErrorMessage());
            }

            return response;

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "读取需求文件失败: " + e.getMessage());
            return response;
        }
    }

    public Map<String, Object> analyzeModules(String requirementsFileName, String architectureFileName,
                                               String modelTypeStr, String sessionId) {
        Map<String, Object> response = new HashMap<>();

        if (requirementsFileName == null || requirementsFileName.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "需求文件名不能为空");
            return response;
        }

        if (architectureFileName == null || architectureFileName.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "架构文件名不能为空");
            return response;
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            String requirementsFilePath = Paths.get(fileConfig.getRequirementsPath(), requirementsFileName).toString();
            String architectureFilePath = Paths.get(fileConfig.getArchitecturePath(), architectureFileName).toString();

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(requirementsFilePath))) {
                response.put("success", false);
                response.put("message", "需求文件不存在: " + requirementsFileName);
                return response;
            }

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(architectureFilePath))) {
                response.put("success", false);
                response.put("message", "架构文件不存在: " + architectureFileName);
                return response;
            }

            String requirementsJson = docFileReader.readFile(requirementsFilePath);
            String architectureJson = docFileReader.readFile(architectureFilePath);

            String ragContext = ragService.getEnhancedContext(requirementsJson + "\n" + architectureJson, "module");
            String sessionContext = sessionManager.buildContext(sessionId, 10);
            if (sessionContext != null && !sessionContext.isEmpty()) {
                ragContext = sessionContext + "\n\n" + ragContext;
            }

            sessionManager.addMessage(sessionId, ChatMessage.user("分析模块: " + requirementsFileName + " + " + architectureFileName));

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(requirementsJson)
                    .metadata(architectureJson)
                    .modelType(modelType)
                    .ragContext(ragContext)
                    .build();

            AgentOutput output = moduleAnalysisAgent.execute(input);

            if (output.isSuccess()) {
                sessionManager.addMessage(sessionId, ChatMessage.assistant("模块分析成功", "ModuleAnalysisAgent"));
            }

            response.put("success", output.isSuccess());
            response.put("sessionId", output.getSessionId());
            response.put("executionTime", output.getExecutionTime());
            response.put("model", modelType.name());

            if (output.isSuccess()) {
                String outputFileName = requirementsFileName.replaceAll("\\.(json)$", "-modules.json");
                String outputFilePath = Paths.get(fileConfig.getModulesPath(), outputFileName).toString();
                docFileReader.writeFile(output.getContent(), outputFilePath);

                response.put("data", output.getContent());
                response.put("outputFile", outputFileName);
                log.info("Successfully analyzed modules: " + requirementsFileName + " + " + architectureFileName + " -> " + outputFileName);
            } else {
                response.put("message", output.getErrorMessage());
            }

            return response;

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "读取文件失败: " + e.getMessage());
            return response;
        }
    }

    public Map<String, Object> generateAadl(String architectureFileName, String modulesFileName,
                                             String modelTypeStr, String sessionId) {
        Map<String, Object> response = new HashMap<>();

        if (architectureFileName == null || architectureFileName.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "架构文件名不能为空");
            return response;
        }

        if (modulesFileName == null || modulesFileName.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "模块分析文件名不能为空");
            return response;
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            String architectureFilePath = Paths.get(fileConfig.getArchitecturePath(), architectureFileName).toString();
            String modulesFilePath = Paths.get(fileConfig.getModulesPath(), modulesFileName).toString();

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(architectureFilePath))) {
                response.put("success", false);
                response.put("message", "架构文件不存在: " + architectureFileName);
                return response;
            }

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(modulesFilePath))) {
                response.put("success", false);
                response.put("message", "模块分析文件不存在: " + modulesFileName);
                return response;
            }

            String architectureJson = docFileReader.readFile(architectureFilePath);
            String modulesJson = docFileReader.readFile(modulesFilePath);

            String ragContext = ragService.getEnhancedContext(architectureJson + "\n" + modulesJson, "aadl");
            String sessionContext = sessionManager.buildContext(sessionId, 10);
            if (sessionContext != null && !sessionContext.isEmpty()) {
                ragContext = sessionContext + "\n\n" + ragContext;
            }

            sessionManager.addMessage(sessionId, ChatMessage.user("生成AADL: " + architectureFileName + " + " + modulesFileName));

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(architectureJson)
                    .metadata(modulesJson)
                    .modelType(modelType)
                    .ragContext(ragContext)
                    .build();

            AgentOutput output = aadlGeneratorAgent.execute(input);

            if (output.isSuccess()) {
                sessionManager.addMessage(sessionId, ChatMessage.assistant("AADL生成成功", "AadlGeneratorAgent"));
            }

            response.put("success", output.isSuccess());
            response.put("sessionId", output.getSessionId());
            response.put("executionTime", output.getExecutionTime());
            response.put("model", modelType.name());

            if (output.isSuccess()) {
                String outputFileName = architectureFileName.replaceAll("\\-architecture\\.json$", ".aadl");
                if (outputFileName.equals(architectureFileName)) {
                    outputFileName = architectureFileName.replaceAll("\\.json$", ".aadl");
                }
                String outputFilePath = Paths.get(fileConfig.getAadlPath(), outputFileName).toString();
                docFileReader.writeFile(output.getContent(), outputFilePath);

                response.put("data", output.getContent());
                response.put("outputFile", outputFileName);
                traceabilityService.addAadlTraceability(sessionId, output.getContent());
                log.info("Successfully generated AADL: " + architectureFileName + " + " + modulesFileName + " -> " + outputFileName);
            } else {
                response.put("message", output.getErrorMessage());
            }

            return response;

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "读取文件失败: " + e.getMessage());
            return response;
        }
    }

    public Map<String, Object> regenerateAadl(String architectureFileName, String modulesFileName,
                                               String previousAadlContent, List<String> errors,
                                               String modelTypeStr, String sessionId) {
        Map<String, Object> response = new HashMap<>();

        if (architectureFileName == null || architectureFileName.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "架构文件名不能为空");
            return response;
        }

        if (modulesFileName == null || modulesFileName.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "模块分析文件名不能为空");
            return response;
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            String architectureFilePath = Paths.get(fileConfig.getArchitecturePath(), architectureFileName).toString();
            String modulesFilePath = Paths.get(fileConfig.getModulesPath(), modulesFileName).toString();

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(architectureFilePath))) {
                response.put("success", false);
                response.put("message", "架构文件不存在: " + architectureFileName);
                return response;
            }

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(modulesFilePath))) {
                response.put("success", false);
                response.put("message", "模块分析文件不存在: " + modulesFileName);
                return response;
            }

            String architectureJson = docFileReader.readFile(architectureFilePath);
            String modulesJson = docFileReader.readFile(modulesFilePath);
            String ragContext = ragService.getEnhancedContext(architectureJson + "\n" + modulesJson, "aadl");
            String sessionContext = sessionManager.buildContext(sessionId, 10);
            if (sessionContext != null && !sessionContext.isEmpty()) {
                ragContext = sessionContext + "\n\n" + ragContext;
            }

            StringBuilder errorFeedback = new StringBuilder();
            if (errors != null && !errors.isEmpty()) {
                errorFeedback.append("\n\n【上一次生成错误反馈】\n");
                errorFeedback.append("请修复以下AADL语法问题：\n");
                for (int i = 0; i < errors.size(); i++) {
                    errorFeedback.append(String.format("%d. %s\n", i + 1, errors.get(i)));
                }
                errorFeedback.append("\n请基于以上反馈重新生成完整的AADL模型。");
            }

            String combinedContent = architectureJson + "\n\n" + modulesJson;
            if (previousAadlContent != null && !previousAadlContent.isEmpty()) {
                combinedContent += "\n\n【上一次生成的AADL】\n" + previousAadlContent;
            }
            if (errorFeedback.length() > 0) {
                combinedContent += errorFeedback.toString();
            }

            sessionManager.addMessage(sessionId, ChatMessage.user("修复AADL错误: " + (errors != null ? errors.size() : 0) + " 个问题"));

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(architectureJson)
                    .metadata(modulesJson)
                    .ragContext(ragContext)
                    .modelType(modelType)
                    .build();

            AgentOutput output = aadlGeneratorAgent.execute(input);

            if (output.isSuccess()) {
                sessionManager.addMessage(sessionId, ChatMessage.assistant("AADL修复成功", "AadlGeneratorAgent"));
            }

            response.put("success", output.isSuccess());
            response.put("sessionId", output.getSessionId());
            response.put("executionTime", output.getExecutionTime());
            response.put("model", modelType.name());

            if (output.isSuccess()) {
                String outputFileName = architectureFileName.replaceAll("\\-architecture\\.json$", ".aadl");
                if (outputFileName.equals(architectureFileName)) {
                    outputFileName = architectureFileName.replaceAll("\\.json$", ".aadl");
                }
                String outputFilePath = Paths.get(fileConfig.getAadlPath(), outputFileName).toString();
                docFileReader.writeFile(output.getContent(), outputFilePath);

                response.put("data", output.getContent());
                response.put("outputFile", outputFileName);
                log.info("Successfully regenerated AADL: " + architectureFileName + " + " + modulesFileName + " -> " + outputFileName);
            } else {
                response.put("message", output.getErrorMessage());
            }

            return response;

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "读取文件失败: " + e.getMessage());
            return response;
        }
    }

    public Map<String, Object> fixAadl(String aadlFileName, String aadlContent, List<String> errors,
                                        String modelTypeStr, String sessionId) {
        Map<String, Object> response = new HashMap<>();

        if ((aadlFileName == null || aadlFileName.trim().isEmpty()) &&
            (aadlContent == null || aadlContent.trim().isEmpty())) {
            response.put("success", false);
            response.put("message", "AADL文件名或内容不能为空");
            return response;
        }

        if (errors == null || errors.isEmpty()) {
            response.put("success", false);
            response.put("message", "错误列表不能为空");
            return response;
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            if (aadlContent == null || aadlContent.trim().isEmpty()) {
                String aadlFilePath = Paths.get(fileConfig.getAadlPath(), aadlFileName).toString();
                if (!java.nio.file.Files.exists(java.nio.file.Paths.get(aadlFilePath))) {
                    response.put("success", false);
                    response.put("message", "AADL文件不存在: " + aadlFileName);
                    return response;
                }
                aadlContent = docFileReader.readFile(aadlFilePath);
            }

            String errorText = String.join("\n", errors);
            String ragContext = ragService.getEnhancedContext(errorText, "aadl");
            String sessionContext = sessionManager.buildContext(sessionId, 10);
            if (sessionContext != null && !sessionContext.isEmpty()) {
                ragContext = sessionContext + "\n\n" + ragContext;
            }

            sessionManager.addMessage(sessionId, ChatMessage.user("修复AADL错误: " + errors.size() + " 个问题"));

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(aadlContent)
                    .metadata(errorText)
                    .ragContext(ragContext)
                    .modelType(modelType)
                    .build();

            AgentOutput output = aadlFixerAgent.execute(input);

            if (output.isSuccess()) {
                sessionManager.addMessage(sessionId, ChatMessage.assistant("AADL修复成功", "AadlFixerAgent"));
            }

            response.put("success", output.isSuccess());
            response.put("sessionId", output.getSessionId());
            response.put("executionTime", output.getExecutionTime());
            response.put("model", modelType.name());

            if (output.isSuccess()) {
                String outputFileName = aadlFileName;
                if (outputFileName == null || outputFileName.trim().isEmpty()) {
                    outputFileName = "fixed_system.aadl";
                }
                String outputFilePath = Paths.get(fileConfig.getAadlPath(), outputFileName).toString();
                docFileReader.writeFile(output.getContent(), outputFilePath);

                response.put("data", output.getContent());
                response.put("outputFile", outputFileName);
                log.info("Successfully fixed AADL: " + errors.size() + " errors -> " + outputFileName);
            } else {
                response.put("message", output.getErrorMessage());
            }

            return response;

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "读取文件失败: " + e.getMessage());
            return response;
        }
    }

    public Map<String, Object> listFiles() {
        Map<String, Object> response = new HashMap<>();

        try {
            List<String> supportedExtensions = Arrays.asList(".doc", ".docx", ".txt");
            List<String> files = docFileReader.listFiles(fileConfig.getInputPath(), supportedExtensions);

            List<Map<String, Object>> fileList = new ArrayList<>();
            for (String filePath : files) {
                Path path = Paths.get(filePath);
                Map<String, Object> fileInfo = new HashMap<>();
                fileInfo.put("name", path.getFileName().toString());
                fileInfo.put("path", filePath);
                fileList.add(fileInfo);
            }

            response.put("success", true);
            response.put("files", fileList);
            response.put("count", fileList.size());

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "扫描input目录失败: " + e.getMessage());
        }

        return response;
    }

    public Map<String, Object> getModelStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("models", modelService.getModelStatus());
        return response;
    }

    public Map<String, Object> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("agent", requirementAgent.getAgentName());
        response.put("models", modelService.getModelStatus());
        return response;
    }

    public Map<String, Object> testConfig() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("inputPath", fileConfig.getInputPath());
        response.put("outputPath", fileConfig.getOutputPath());
        response.put("deepSeekApiKey", deepSeekConfig.getApiKey());
        response.put("deepSeekBaseUrl", deepSeekConfig.getBaseUrl());
        response.put("deepSeekApiKeyConfigured", deepSeekConfig.getApiKey() != null && !deepSeekConfig.getApiKey().isEmpty());
        return response;
    }

    private ModelType parseModelType(String modelTypeStr) {
        if (modelTypeStr != null && !modelTypeStr.isEmpty()) {
            try {
                return ModelType.valueOf(modelTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warning("Invalid model type: " + modelTypeStr + ", using OLLAMA as default");
            }
        }
        return ModelType.OLLAMA;
    }

    public Map<String, Object> getTraceabilityRecords(String sessionId) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("records", traceabilityService.getRecords(sessionId));
        return response;
    }
}