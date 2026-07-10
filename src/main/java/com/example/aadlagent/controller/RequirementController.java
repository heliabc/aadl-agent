package com.example.aadlagent.controller;

import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.agent.aadl.AadlFixerAgent;
import com.example.aadlagent.agent.aadl.AadlGeneratorAgent;
import com.example.aadlagent.agent.architecture.AadlArchitectureAgent;
import com.example.aadlagent.agent.module.ModuleAnalysisAgent;
import com.example.aadlagent.agent.requirement.RequirementAgent;
import com.example.aadlagent.client.ModelService;
import com.example.aadlagent.client.ModelType;
import com.example.aadlagent.config.DeepSeekConfig;
import com.example.aadlagent.config.FileConfig;
import com.example.aadlagent.rag.RagService;
import com.example.aadlagent.session.ChatMessage;
import com.example.aadlagent.session.SessionManager;
import com.example.aadlagent.util.DocFileReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/requirement")
public class RequirementController {

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

    public RequirementController(RequirementAgent requirementAgent, AadlArchitectureAgent architectureAgent,
                                 ModuleAnalysisAgent moduleAnalysisAgent, AadlGeneratorAgent aadlGeneratorAgent,
                                 AadlFixerAgent aadlFixerAgent,
                                 DocFileReader docFileReader, FileConfig fileConfig, 
                                 ModelService modelService, DeepSeekConfig deepSeekConfig,
                                 RagService ragService, SessionManager sessionManager) {
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
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyzeRequirements(@RequestBody Map<String, Object> request) {
        String requirementDoc = (String) request.get("content");
        String modelTypeStr = (String) request.get("model");
        String sessionId = (String) request.get("sessionId");

        if (requirementDoc == null || requirementDoc.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "需求文档内容不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        log.info("Received requirement analysis request, content length: {} characters, model: {}, session: {}", 
                requirementDoc.length(), modelType.name(), sessionId);

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

        Map<String, Object> response = new HashMap<>();
        response.put("success", output.isSuccess());
        response.put("sessionId", sessionId);
        response.put("executionTime", output.getExecutionTime());
        response.put("model", modelType.name());

        if (output.isSuccess()) {
            response.put("data", output.getContent());
        } else {
            response.put("message", output.getErrorMessage());
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/process-files")
    public ResponseEntity<Map<String, Object>> processFiles(@RequestParam(required = false) String model) {
        long startTime = System.currentTimeMillis();

        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        ModelType modelType = parseModelType(model);

        try {
            List<String> supportedExtensions = Arrays.asList(".doc", ".docx", ".txt");
            List<String> files = docFileReader.listFiles(fileConfig.getInputPath(), supportedExtensions);

            log.info("Found {} files to process in input directory, model: {}", files.size(), modelType.name());

            if (files.isEmpty()) {
                response.put("success", false);
                response.put("message", "input目录下没有找到需要处理的文档文件（支持.doc, .docx, .txt格式）");
                return ResponseEntity.ok(response);
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

            log.info("File processing completed: {} success, {} fail, total time: {}ms, model: {}", 
                    successCount, failCount, totalTime, modelType.name());

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "扫描input目录失败: " + e.getMessage());
            log.error("Error scanning input directory: {}", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/process-file")
    public ResponseEntity<Map<String, Object>> processFileByName(@RequestBody Map<String, String> request) {
        String fileName = request.get("fileName");
        String modelTypeStr = request.get("model");

        if (fileName == null || fileName.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "文件名不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        ModelType modelType = parseModelType(modelTypeStr);

        try {
            String filePath = Paths.get(fileConfig.getInputPath(), fileName).toString();
            Map<String, Object> result = processFile(filePath, modelType);
            
            result.put("success", "success".equals(result.get("status")));
            result.put("model", modelType.name());
            
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "文件不存在或无法访问: " + fileName);
            return ResponseEntity.badRequest().body(error);
        }
    }

    private Map<String, Object> processFile(String filePath, ModelType modelType) throws IOException {
        Map<String, Object> fileResult = new HashMap<>();
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();

        fileResult.put("fileName", fileName);

        try {
            log.info("Processing file: {}, model: {}", fileName, modelType.name());

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
                log.info("Successfully processed file: {} -> {}, model: {}", fileName, outputFileName, modelType.name());
            } else {
                fileResult.put("status", "failed");
                fileResult.put("error", output.getErrorMessage());
                log.warn("Failed to process file: {}, error: {}, model: {}", fileName, output.getErrorMessage(), modelType.name());
            }

        } catch (IOException e) {
            fileResult.put("status", "failed");
            fileResult.put("error", "读取文件失败: " + e.getMessage());
            log.error("Error reading file: {}, error: {}", fileName, e.getMessage());
        }

        return fileResult;
    }

    @PostMapping("/generate-architecture")
    public ResponseEntity<Map<String, Object>> generateArchitecture(@RequestBody Map<String, String> request) {
        String fileName = request.get("fileName");
        String modelTypeStr = request.get("model");
        String sessionId = request.get("sessionId");

        if (fileName == null || fileName.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "文件名不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            String requirementsFilePath = Paths.get(fileConfig.getRequirementsPath(), fileName).toString();
            
            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(requirementsFilePath))) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "需求文件不存在，请先运行需求分析: " + fileName);
                return ResponseEntity.badRequest().body(error);
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

            Map<String, Object> response = new HashMap<>();
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
                log.info("Successfully generated architecture: {} -> {}", fileName, architectureFileName);
            } else {
                response.put("message", output.getErrorMessage());
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "读取需求文件失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/analyze-modules")
    public ResponseEntity<Map<String, Object>> analyzeModules(@RequestBody Map<String, String> request) {
        String requirementsFileName = request.get("requirementsFile");
        String architectureFileName = request.get("architectureFile");
        String modelTypeStr = request.get("model");
        String sessionId = request.get("sessionId");

        if (requirementsFileName == null || requirementsFileName.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "需求文件名不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (architectureFileName == null || architectureFileName.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "架构文件名不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            String requirementsFilePath = Paths.get(fileConfig.getRequirementsPath(), requirementsFileName).toString();
            String architectureFilePath = Paths.get(fileConfig.getArchitecturePath(), architectureFileName).toString();

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(requirementsFilePath))) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "需求文件不存在: " + requirementsFileName);
                return ResponseEntity.badRequest().body(error);
            }

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(architectureFilePath))) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "架构文件不存在: " + architectureFileName);
                return ResponseEntity.badRequest().body(error);
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

            Map<String, Object> response = new HashMap<>();
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
                log.info("Successfully analyzed modules: {} + {} -> {}", requirementsFileName, architectureFileName, outputFileName);
            } else {
                response.put("message", output.getErrorMessage());
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "读取文件失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/generate-aadl")
    public ResponseEntity<Map<String, Object>> generateAadl(@RequestBody Map<String, String> request) {
        String architectureFileName = request.get("architectureFile");
        String modulesFileName = request.get("modulesFile");
        String modelTypeStr = request.get("model");
        String sessionId = request.get("sessionId");

        if (architectureFileName == null || architectureFileName.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "架构文件名不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (modulesFileName == null || modulesFileName.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "模块分析文件名不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            String architectureFilePath = Paths.get(fileConfig.getArchitecturePath(), architectureFileName).toString();
            String modulesFilePath = Paths.get(fileConfig.getModulesPath(), modulesFileName).toString();

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(architectureFilePath))) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "架构文件不存在: " + architectureFileName);
                return ResponseEntity.badRequest().body(error);
            }

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(modulesFilePath))) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "模块分析文件不存在: " + modulesFileName);
                return ResponseEntity.badRequest().body(error);
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

            Map<String, Object> response = new HashMap<>();
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
                log.info("Successfully generated AADL: {} + {} -> {}", architectureFileName, modulesFileName, outputFileName);
            } else {
                response.put("message", output.getErrorMessage());
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "读取文件失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/regenerate-aadl")
    public ResponseEntity<Map<String, Object>> regenerateAadl(@RequestBody Map<String, Object> request) {
        String architectureFileName = (String) request.get("architectureFile");
        String modulesFileName = (String) request.get("modulesFile");
        String previousAadlContent = (String) request.get("previousAadl");
        List<String> errors = (List<String>) request.get("errors");
        String modelTypeStr = (String) request.get("model");
        String sessionId = (String) request.get("sessionId");

        if (architectureFileName == null || architectureFileName.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "架构文件名不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (modulesFileName == null || modulesFileName.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "模块分析文件名不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            String architectureFilePath = Paths.get(fileConfig.getArchitecturePath(), architectureFileName).toString();
            String modulesFilePath = Paths.get(fileConfig.getModulesPath(), modulesFileName).toString();

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(architectureFilePath))) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "架构文件不存在: " + architectureFileName);
                return ResponseEntity.badRequest().body(error);
            }

            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(modulesFilePath))) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "模块分析文件不存在: " + modulesFileName);
                return ResponseEntity.badRequest().body(error);
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

            Map<String, Object> response = new HashMap<>();
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
                log.info("Successfully regenerated AADL: {} + {} -> {}", architectureFileName, modulesFileName, outputFileName);
            } else {
                response.put("message", output.getErrorMessage());
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "读取文件失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/fix-aadl")
    public ResponseEntity<Map<String, Object>> fixAadl(@RequestBody Map<String, Object> request) {
        String aadlFileName = (String) request.get("aadlFile");
        String aadlContent = (String) request.get("aadlContent");
        List<String> errors = (List<String>) request.get("errors");
        String modelTypeStr = (String) request.get("model");
        String sessionId = (String) request.get("sessionId");

        if ((aadlFileName == null || aadlFileName.trim().isEmpty()) && 
            (aadlContent == null || aadlContent.trim().isEmpty())) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "AADL文件名或内容不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (errors == null || errors.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "错误列表不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            if (aadlContent == null || aadlContent.trim().isEmpty()) {
                String aadlFilePath = Paths.get(fileConfig.getAadlPath(), aadlFileName).toString();
                if (!java.nio.file.Files.exists(java.nio.file.Paths.get(aadlFilePath))) {
                    Map<String, Object> error = new HashMap<>();
                    error.put("success", false);
                    error.put("message", "AADL文件不存在: " + aadlFileName);
                    return ResponseEntity.badRequest().body(error);
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

            Map<String, Object> response = new HashMap<>();
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
                log.info("Successfully fixed AADL: {} errors -> {}", errors.size(), outputFileName);
            } else {
                response.put("message", output.getErrorMessage());
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "读取文件失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/list-files")
    public ResponseEntity<Map<String, Object>> listFiles() {
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

        return ResponseEntity.ok(response);
    }

    @GetMapping("/model-status")
    public ResponseEntity<Map<String, Object>> getModelStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("models", modelService.getModelStatus());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("agent", requirementAgent.getAgentName());
        response.put("models", modelService.getModelStatus());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/config-test")
    public ResponseEntity<Map<String, Object>> testConfig() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("inputPath", fileConfig.getInputPath());
        response.put("outputPath", fileConfig.getOutputPath());
        response.put("deepSeekApiKey", deepSeekConfig.getApiKey());
        response.put("deepSeekBaseUrl", deepSeekConfig.getBaseUrl());
        response.put("deepSeekApiKeyConfigured", deepSeekConfig.getApiKey() != null && !deepSeekConfig.getApiKey().isEmpty());
        return ResponseEntity.ok(response);
    }

    private ModelType parseModelType(String modelTypeStr) {
        if (modelTypeStr != null && !modelTypeStr.isEmpty()) {
            try {
                return ModelType.valueOf(modelTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid model type: {}, using OLLAMA as default", modelTypeStr);
            }
        }
        return ModelType.OLLAMA;
    }
}