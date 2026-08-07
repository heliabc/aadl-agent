package com.example.aadlagent.controller;

import com.example.aadlagent.agent.AgentInput;
import com.example.aadlagent.agent.AgentOutput;
import com.example.aadlagent.agent.aadl.AadlErrorParserAgent;
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
import com.example.aadlagent.service.TaskCancellationService;
import com.example.aadlagent.service.TraceabilityService;
import com.example.aadlagent.util.DocFileReader;
import com.example.aadlagent.util.AadlReferenceValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
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
    private final AadlErrorParserAgent aadlErrorParserAgent;
    private final DocFileReader docFileReader;
    private final FileConfig fileConfig;
    private final ModelService modelService;
    private final DeepSeekConfig deepSeekConfig;
    private final RagService ragService;
    private final SessionManager sessionManager;
    private final TraceabilityService traceabilityService;
    private final TaskCancellationService cancellationService;
    private final AadlReferenceValidator aadlValidator;

    public RequirementController(RequirementAgent requirementAgent, AadlArchitectureAgent architectureAgent,
                                 ModuleAnalysisAgent moduleAnalysisAgent, AadlGeneratorAgent aadlGeneratorAgent,
                                 AadlFixerAgent aadlFixerAgent, AadlErrorParserAgent aadlErrorParserAgent,
                                 DocFileReader docFileReader, FileConfig fileConfig, 
                                 ModelService modelService, DeepSeekConfig deepSeekConfig,
                                 RagService ragService, SessionManager sessionManager,
                                 TraceabilityService traceabilityService, TaskCancellationService cancellationService,
                                 AadlReferenceValidator aadlValidator) {
        this.requirementAgent = requirementAgent;
        this.architectureAgent = architectureAgent;
        this.moduleAnalysisAgent = moduleAnalysisAgent;
        this.aadlGeneratorAgent = aadlGeneratorAgent;
        this.aadlFixerAgent = aadlFixerAgent;
        this.aadlErrorParserAgent = aadlErrorParserAgent;
        this.docFileReader = docFileReader;
        this.fileConfig = fileConfig;
        this.modelService = modelService;
        this.deepSeekConfig = deepSeekConfig;
        this.ragService = ragService;
        this.sessionManager = sessionManager;
        this.traceabilityService = traceabilityService;
        this.cancellationService = cancellationService;
        this.aadlValidator = aadlValidator;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyzeRequirements(@RequestBody Map<String, Object> request) {
        String requirementDoc = (String) request.get("content");
        String modelTypeStr = (String) request.get("model");
        String sessionId = (String) request.get("sessionId");
        String fileName = (String) request.get("fileName");

        if (requirementDoc == null || requirementDoc.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "需求文档内容不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        ModelType modelType = parseModelType(modelTypeStr);

        // session ID 优先级：已有session > 文件名 > 文字片段 > 随机生成
        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            if (fileName != null && !fileName.trim().isEmpty()) {
                // 文件上传：用文件名作为 session ID
                sessionId = sessionManager.createSession(fileName);
            } else {
                // 文字输入：取前20个字符作为 session ID
                String snippet = extractTextSnippet(requirementDoc);
                sessionId = sessionManager.createSession(snippet);
            }
        }

        log.info("Received requirement analysis request, content length: {} characters, model: {}, session: {}", 
                requirementDoc.length(), modelType.name(), sessionId);

        String ragContext = ragService.getEnhancedContext(requirementDoc, "requirement");
        String sessionContext = sessionManager.buildContext(sessionId, 10);
        if (sessionContext != null && !sessionContext.isEmpty()) {
            ragContext = sessionContext + "\n\n" + ragContext;
        }

        sessionManager.addMessage(sessionId, ChatMessage.user(requirementDoc));

        java.util.concurrent.atomic.AtomicBoolean cancellationFlag = cancellationService.registerTask(sessionId);

        AgentInput input = AgentInput.builder()
                .sessionId(sessionId)
                .content(requirementDoc)
                .modelType(modelType)
                .ragContext(ragContext)
                .cancelled(cancellationFlag)
                .build();

        AgentOutput output = requirementAgent.execute(input);

        cancellationService.unregisterTask(sessionId);

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
            traceabilityService.addRequirementTraceability(sessionId, requirementDoc, output.getContent());
            
            String outputFileName = "requirements_" + sessionId + ".json";
            String outputFilePath = Paths.get(fileConfig.getRequirementsPath(), outputFileName).toString();
            try {
                docFileReader.writeFile(output.getContent(), outputFilePath);
                response.put("outputFile", outputFileName);
                log.info("Saved requirements file: {}", outputFilePath);
            } catch (IOException e) {
                log.warn("Failed to save requirements file: {}", e.getMessage());
            }
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

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            // 从需求文件名推导 session ID
            String derivedId = deriveSessionFromFileName(fileName);
            sessionId = sessionManager.createSession(derivedId);
        }

        try {
            // 如果没有传fileName，通过sessionId自动生成文件名
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = "requirements_" + sessionId + ".json";
                log.info("架构生成 - 未传fileName，通过sessionId自动生成: {}", fileName);
            }

            // 获取绝对路径用于诊断
            java.nio.file.Path requirementsDir = java.nio.file.Paths.get(fileConfig.getRequirementsPath()).toAbsolutePath().normalize();
            java.nio.file.Path requirementsFullPath = requirementsDir.resolve(fileName);
            String requirementsFilePath = requirementsFullPath.toString();
            
            log.info("架构生成 - 查找需求文件: fileName={}, sessionId={}, requirementsPath={}, fullPath={}", 
                    fileName, sessionId, fileConfig.getRequirementsPath(), requirementsFilePath);
            
            // 列出目录内容用于诊断
            try {
                log.info("架构生成 - requirements目录文件列表:");
                java.nio.file.Files.list(requirementsDir).limit(20).forEach(path -> {
                    log.info("  {}", path.getFileName());
                });
            } catch (Exception e) {
                log.warn("架构生成 - 无法列出目录内容: {}", e.getMessage());
            }
            
            if (!java.nio.file.Files.exists(requirementsFullPath)) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "需求文件不存在，请先运行需求分析: " + fileName + " (搜索路径: " + requirementsFilePath + ")");
                return ResponseEntity.badRequest().body(error);
            }

            String requirementsJson = docFileReader.readFile(requirementsFilePath);
            
            // 提取需求列表（从完整分析结果中）
            String requirementsListJson = extractRequirementsList(requirementsJson);
            if (requirementsListJson == null || requirementsListJson.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "无法从需求文件中提取需求列表");
                return ResponseEntity.badRequest().body(error);
            }

            String ragContext = ragService.getEnhancedContext(requirementsListJson, "architecture");
            String sessionContext = sessionManager.buildContext(sessionId, 10);
            if (sessionContext != null && !sessionContext.isEmpty()) {
                ragContext = sessionContext + "\n\n" + ragContext;
            }

            sessionManager.addMessage(sessionId, ChatMessage.user("生成架构: " + fileName));

            java.util.concurrent.atomic.AtomicBoolean cancellationFlag = cancellationService.registerTask(sessionId);

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(requirementsListJson)
                    .modelType(modelType)
                    .ragContext(ragContext)
                    .cancelled(cancellationFlag)
                    .build();

            AgentOutput output = architectureAgent.execute(input);

            cancellationService.unregisterTask(sessionId);

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

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            // 从需求文件名推导 session ID
            String derivedId = deriveSessionFromFileName(requirementsFileName);
            sessionId = sessionManager.createSession(derivedId);
        }

        // 如果没有传文件名，通过sessionId自动生成
        if (requirementsFileName == null || requirementsFileName.trim().isEmpty()) {
            requirementsFileName = "requirements_" + sessionId + ".json";
            log.info("模块分析 - 未传requirementsFile，通过sessionId自动生成: {}", requirementsFileName);
        }
        if (architectureFileName == null || architectureFileName.trim().isEmpty()) {
            architectureFileName = "requirements_" + sessionId + "-architecture.json";
            log.info("模块分析 - 未传architectureFile，通过sessionId自动生成: {}", architectureFileName);
        }

        try {
            // 获取绝对路径用于诊断
            java.nio.file.Path requirementsDir = java.nio.file.Paths.get(fileConfig.getRequirementsPath()).toAbsolutePath().normalize();
            java.nio.file.Path architectureDir = java.nio.file.Paths.get(fileConfig.getArchitecturePath()).toAbsolutePath().normalize();
            
            java.nio.file.Path requirementsFullPath = requirementsDir.resolve(requirementsFileName);
            java.nio.file.Path architectureFullPath = architectureDir.resolve(architectureFileName);
            
            String requirementsFilePath = requirementsFullPath.toString();
            String architectureFilePath = architectureFullPath.toString();
            
            log.info("模块分析 - 查找文件: requirementsFileName={}, architectureFileName={}, sessionId={}", 
                    requirementsFileName, architectureFileName, sessionId);
            log.info("模块分析 - requirementsDir={}, architectureDir={}", requirementsDir, architectureDir);
            
            // 列出目录内容用于诊断
            try {
                log.info("模块分析 - requirements目录文件:");
                java.nio.file.Files.list(requirementsDir).limit(20).forEach(path -> {
                    log.info("  {}", path.getFileName());
                });
                log.info("模块分析 - architecture目录文件:");
                java.nio.file.Files.list(architectureDir).limit(20).forEach(path -> {
                    log.info("  {}", path.getFileName());
                });
            } catch (Exception e) {
                log.warn("模块分析 - 无法列出目录内容: {}", e.getMessage());
            }

            if (!java.nio.file.Files.exists(requirementsFullPath)) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "需求文件不存在: " + requirementsFileName + " (搜索路径: " + requirementsFilePath + ")");
                return ResponseEntity.badRequest().body(error);
            }

            if (!java.nio.file.Files.exists(architectureFullPath)) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "架构文件不存在: " + architectureFileName + " (搜索路径: " + architectureFilePath + ")");
                return ResponseEntity.badRequest().body(error);
            }

            String requirementsJson = docFileReader.readFile(requirementsFilePath);
            String architectureJson = docFileReader.readFile(architectureFilePath);
            
            // 提取需求列表
            String requirementsListJson = extractRequirementsList(requirementsJson);
            if (requirementsListJson == null || requirementsListJson.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "无法从需求文件中提取需求列表");
                return ResponseEntity.badRequest().body(error);
            }

            String ragContext = ragService.getEnhancedContext(requirementsListJson + "\n" + architectureJson, "module");
            String sessionContext = sessionManager.buildContext(sessionId, 10);
            if (sessionContext != null && !sessionContext.isEmpty()) {
                ragContext = sessionContext + "\n\n" + ragContext;
            }

            sessionManager.addMessage(sessionId, ChatMessage.user("分析模块: " + requirementsFileName + " + " + architectureFileName));

            java.util.concurrent.atomic.AtomicBoolean cancellationFlag = cancellationService.registerTask(sessionId);

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(requirementsListJson)
                    .metadata(architectureJson)
                    .modelType(modelType)
                    .ragContext(ragContext)
                    .cancelled(cancellationFlag)
                    .build();

            AgentOutput output = moduleAnalysisAgent.execute(input);

            cancellationService.unregisterTask(sessionId);
            
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
            // 从架构文件名推导 session ID
            String derivedId = deriveSessionFromFileName(architectureFileName);
            sessionId = sessionManager.createSession(derivedId);
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

            java.util.concurrent.atomic.AtomicBoolean cancellationFlag = cancellationService.registerTask(sessionId);

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(architectureJson)
                    .metadata(modulesJson)
                    .modelType(modelType)
                    .ragContext(ragContext)
                    .cancelled(cancellationFlag)
                    .build();

            AgentOutput output = aadlGeneratorAgent.execute(input);

            cancellationService.unregisterTask(sessionId);
            
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
                traceabilityService.addAadlTraceability(sessionId, output.getContent());
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
            String derivedId = deriveSessionFromFileName(architectureFileName);
            sessionId = sessionManager.createSession(derivedId);
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

            java.util.concurrent.atomic.AtomicBoolean cancellationFlag = cancellationService.registerTask(sessionId);

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(architectureJson)
                    .metadata(modulesJson)
                    .ragContext(ragContext)
                    .modelType(modelType)
                    .cancelled(cancellationFlag)
                    .build();

            AgentOutput output = aadlGeneratorAgent.execute(input);

            cancellationService.unregisterTask(sessionId);
            
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

    @PostMapping("/parse-errors")
    public ResponseEntity<Map<String, Object>> parseErrors(@RequestBody Map<String, Object> request) {
        String aadlContent = (String) request.get("aadlContent");
        String rawErrors = (String) request.get("errors");
        String modelTypeStr = (String) request.get("model");
        String sessionId = (String) request.get("sessionId");

        if (aadlContent == null || aadlContent.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "AADL内容不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (rawErrors == null || rawErrors.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "错误信息不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        ModelType modelType = parseModelType(modelTypeStr);

        if (sessionId == null || sessionId.trim().isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        try {
            String ragContext = ragService.getEnhancedContext(rawErrors, "aadl");
            String sessionContext = sessionManager.buildContext(sessionId, 10);
            if (sessionContext != null && !sessionContext.isEmpty()) {
                ragContext = sessionContext + "\n\n" + ragContext;
            }

            sessionManager.addMessage(sessionId, ChatMessage.user("解析错误信息"));

            java.util.concurrent.atomic.AtomicBoolean cancellationFlag = cancellationService.registerTask(sessionId);

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(aadlContent)
                    .metadata(rawErrors)
                    .ragContext(ragContext)
                    .modelType(modelType)
                    .cancelled(cancellationFlag)
                    .build();

            AgentOutput output = aadlErrorParserAgent.execute(input);

            cancellationService.unregisterTask(sessionId);

            if (output.isSuccess()) {
                sessionManager.addMessage(sessionId, ChatMessage.assistant("错误解析完成", "AadlErrorParserAgent"));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", output.isSuccess());
            response.put("sessionId", output.getSessionId());
            response.put("executionTime", output.getExecutionTime());
            response.put("model", modelType.name());

            if (output.isSuccess()) {
                response.put("data", output.getContent());
                log.info("Successfully parsed errors");
            } else {
                response.put("message", output.getErrorMessage());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "解析错误失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/fix-aadl")
    public ResponseEntity<Map<String, Object>> fixAadl(@RequestBody Map<String, Object> request) {
        String aadlFileName = (String) request.get("aadlFile");
        String aadlContent = (String) request.get("aadlContent");
        String errors = (String) request.get("errors");
        String modelTypeStr = (String) request.get("model");
        String sessionId = (String) request.get("sessionId");

        if ((aadlFileName == null || aadlFileName.trim().isEmpty()) && 
            (aadlContent == null || aadlContent.trim().isEmpty())) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "AADL文件名或内容不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (errors == null || errors.trim().isEmpty()) {
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

            String ragContext = ragService.getEnhancedContext(errors, "aadl");
            String sessionContext = sessionManager.buildContext(sessionId, 10);
            if (sessionContext != null && !sessionContext.isEmpty()) {
                ragContext = sessionContext + "\n\n" + ragContext;
            }

            sessionManager.addMessage(sessionId, ChatMessage.user("修复AADL错误"));

            java.util.concurrent.atomic.AtomicBoolean cancellationFlag = cancellationService.registerTask(sessionId);

            AgentInput input = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(aadlContent)
                    .metadata(errors)
                    .ragContext(ragContext)
                    .modelType(modelType)
                    .cancelled(cancellationFlag)
                    .build();

            AgentOutput output = aadlFixerAgent.execute(input);

            cancellationService.unregisterTask(sessionId);

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
                log.info("Successfully fixed AADL");
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

    /**
     * 测试 AADL 静态验证和自动修复功能（不调用大模型）。
     * 输入 AADL 内容，输出验证结果和自动修复后的代码。
     *
     * 请求体：
     * - content: AADL 代码内容（与 file 二选一）
     * - file: output/aadl 目录下的文件名（与 content 二选一）
     * - outputFile: 可选，修复后保存的文件名
     */
    @PostMapping("/validate-aadl")
    public ResponseEntity<Map<String, Object>> validateAadl(@RequestBody Map<String, Object> request) {
        String content = (String) request.get("content");
        String fileName = (String) request.get("file");
        String outputFileName = (String) request.get("outputFile");

        if ((content == null || content.trim().isEmpty()) && 
            (fileName == null || fileName.trim().isEmpty())) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "AADL内容或文件名不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            // 读取文件或直接使用内容
            String aadlContent = content;
            if (aadlContent == null || aadlContent.trim().isEmpty()) {
                String aadlFilePath = Paths.get(fileConfig.getAadlPath(), fileName).toString();
                if (!java.nio.file.Files.exists(java.nio.file.Paths.get(aadlFilePath))) {
                    Map<String, Object> error = new HashMap<>();
                    error.put("success", false);
                    error.put("message", "AADL文件不存在: " + fileName);
                    return ResponseEntity.badRequest().body(error);
                }
                aadlContent = docFileReader.readFile(aadlFilePath);
            }

            // 执行静态验证和自动修复
            AadlReferenceValidator.ValidationResult result = aadlValidator.validateSyntax(aadlContent);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("originalContent", aadlContent);
            response.put("fixedContent", result.fixedContent);
            response.put("errors", result.errors);
            response.put("warnings", result.warnings);
            response.put("fixes", result.fixes);
            response.put("errorCount", result.errors.size());
            response.put("warningCount", result.warnings.size());
            response.put("fixCount", result.fixes.size());
            response.put("hasIssues", result.hasIssues());

            // 如果指定了输出文件名，保存修复后的文件
            if (outputFileName != null && !outputFileName.trim().isEmpty() && result.fixedContent != null) {
                String outputFilePath = Paths.get(fileConfig.getAadlPath(), outputFileName).toString();
                docFileReader.writeFile(result.fixedContent, outputFilePath);
                response.put("savedTo", outputFileName);
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "处理文件失败: " + e.getMessage());
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

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody Map<String, String> request) {
        String modelTypeStr = request.get("model");
        ModelType modelType = parseModelType(modelTypeStr);
        
        Map<String, Object> result = modelService.testConnection(modelType);
        return ResponseEntity.ok(result);
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

    private String extractRequirementsList(String analysisResultJson) {
        try {
            String trimmed = analysisResultJson.trim();

            // 兼容旧格式：直接的需求列表数组（以 [ 开头），优先返回避免反序列化为Map失败
            if (trimmed.startsWith("[")) {
                return analysisResultJson;
            }

            ObjectMapper mapper = new ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            Map<String, Object> resultMap = mapper.readValue(analysisResultJson, new TypeReference<Map<String, Object>>() {});

            // 检查是否包含阶段3结果
            if (resultMap.containsKey("stage3") && resultMap.get("stage3") != null) {
                Map<String, Object> stage3 = (Map<String, Object>) resultMap.get("stage3");
                if (stage3.containsKey("mergedRequirements")) {
                    return mapper.writeValueAsString(stage3.get("mergedRequirements"));
                }
            }

            log.warn("无法从需求分析结果中提取需求列表");
        } catch (Exception e) {
            log.warn("解析需求分析结果失败: {}", e.getMessage());
        }
        return null;
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

    /**
     * 从需求文字中提取一段作为 session ID 的基底。
     * 取第一个句号/换行前的内容，最多20个字符。
     */
    private String extractTextSnippet(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String snippet = text.trim();
        // 取第一个句末标点或换行前的内容
        int cutPos = snippet.length();
        for (int i = 0; i < snippet.length(); i++) {
            char c = snippet.charAt(i);
            if (c == '。' || c == '；' || c == '\n' || c == '\r' || c == '!' || c == '？') {
                cutPos = i;
                break;
            }
        }
        snippet = snippet.substring(0, Math.min(cutPos, 20));
        return snippet.trim();
    }

    /**
     * 从需求文件名推导 session ID。
     * 如 "requirements_brake_system.json" → "brake_system"
     */
    private String deriveSessionFromFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }
        return SessionManager.sanitizeSessionId(fileName);
    }

    @GetMapping("/traceability/excel")
    public ResponseEntity<Resource> downloadTraceabilityExcel(@RequestParam String sessionId) {
        try {
            String filePath = traceabilityService.generateExcelFile(sessionId);
            File file = new File(filePath);
            FileSystemResource resource = new FileSystemResource(file);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                    .body(resource);
        } catch (IOException e) {
            log.error("生成追溯Excel文件失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        } catch (IllegalArgumentException e) {
            log.warn("无效的会话ID: {}", sessionId);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/traceability/csv")
    public ResponseEntity<Resource> downloadTraceabilityCsv(@RequestParam String sessionId) {
        try {
            String filePath = traceabilityService.generateCsvFile(sessionId);
            File file = new File(filePath);
            FileSystemResource resource = new FileSystemResource(file);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                    .body(resource);
        } catch (IOException e) {
            log.error("生成追溯CSV文件失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        } catch (IllegalArgumentException e) {
            log.warn("无效的会话ID: {}", sessionId);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/traceability/records")
    public ResponseEntity<Map<String, Object>> getTraceabilityRecords(@RequestParam String sessionId) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("records", traceabilityService.getRecords(sessionId));
        return ResponseEntity.ok(response);
    }
}