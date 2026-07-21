package com.example.aadlplugin.controller;

import com.example.aadlplugin.agent.AgentInput;
import com.example.aadlplugin.agent.AgentOutput;
import com.example.aadlplugin.agent.aadl.AadlFixerAgent;
import com.example.aadlplugin.agent.aadl.AadlGeneratorAgent;
import com.example.aadlplugin.agent.architecture.AadlArchitectureAgent;
import com.example.aadlplugin.agent.module.ModuleAnalysisAgent;
import com.example.aadlplugin.agent.requirement.RequirementAgent;
import com.example.aadlplugin.client.ModelType;
import com.example.aadlplugin.config.FileConfig;
import com.example.aadlplugin.rag.RagService;
import com.example.aadlplugin.session.SessionManager;
import com.example.aadlplugin.service.TraceabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PluginController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String executeFullFlow(String requirement, ModelType modelType, String existingSessionId,
                                         SessionManager sessionManager, RagService ragService,
                                         TraceabilityService traceabilityService, FileConfig fileConfig,
                                         RequirementAgent requirementAgent,
                                         AadlArchitectureAgent architectureAgent,
                                         ModuleAnalysisAgent moduleAnalysisAgent,
                                         AadlGeneratorAgent aadlGeneratorAgent,
                                         AadlFixerAgent aadlFixerAgent) {
        Map<String, Object> response = new HashMap<>();

        try {
            String sessionId = existingSessionId != null ? existingSessionId : UUID.randomUUID().toString();
            sessionManager.createSession(sessionId);
            
            String requirementSummary = requirement.length() > 100 ? requirement.substring(0, 100) + "..." : requirement;
            sessionManager.setSessionName(sessionId, requirementSummary);
            sessionManager.setSessionRequirementSummary(sessionId, requirementSummary);
            sessionManager.setSessionModelType(sessionId, modelType.name());

            AgentInput reqInput = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(requirement)
                    .modelType(modelType)
                    .build();
            AgentOutput reqOutput = requirementAgent.execute(reqInput);

            if (!reqOutput.isSuccess()) {
                response.put("success", false);
                response.put("message", reqOutput.getErrorMessage());
                return objectMapper.writeValueAsString(response);
            }

            String reqContent = reqOutput.getContent();
            traceabilityService.addRequirementTraceability(sessionId, requirement, reqContent);
            
            try {
                com.fasterxml.jackson.databind.JsonNode reqJson = objectMapper.readTree(reqContent);
                com.fasterxml.jackson.databind.JsonNode requirementsArray = reqJson.get("requirements");
                if (requirementsArray != null && requirementsArray.isArray()) {
                    sessionManager.setSessionRequirementCount(sessionId, requirementsArray.size());
                }
            } catch (Exception e) {
                sessionManager.setSessionRequirementCount(sessionId, 0);
            }

            String reqFileName = "requirements_" + sessionId.substring(0, 8) + ".json";
            Path reqPath = Paths.get(fileConfig.getRequirementsPath(), reqFileName).toAbsolutePath().normalize();
            Path reqParent = reqPath.getParent();
            if (reqParent != null && !Files.exists(reqParent)) {
                Files.createDirectories(reqParent);
            }
            Files.writeString(reqPath, reqContent);

            AgentInput archInput = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(reqContent)
                    .modelType(modelType)
                    .build();
            AgentOutput archOutput = architectureAgent.execute(archInput);

            if (!archOutput.isSuccess()) {
                response.put("success", false);
                response.put("message", archOutput.getErrorMessage());
                return objectMapper.writeValueAsString(response);
            }

            String archContent = archOutput.getContent();
            String archFileName = "requirements-architecture_" + sessionId.substring(0, 8) + ".json";
            Path archPath = Paths.get(fileConfig.getRequirementsPath(), archFileName).toAbsolutePath().normalize();
            Path archParent = archPath.getParent();
            if (archParent != null && !Files.exists(archParent)) {
                Files.createDirectories(archParent);
            }
            Files.writeString(archPath, archContent);

            AgentInput moduleInput = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(reqContent)
                    .metadata(archContent)
                    .modelType(modelType)
                    .build();
            AgentOutput moduleOutput = moduleAnalysisAgent.execute(moduleInput);

            if (!moduleOutput.isSuccess()) {
                response.put("success", false);
                response.put("message", moduleOutput.getErrorMessage());
                return objectMapper.writeValueAsString(response);
            }

            String moduleContent = moduleOutput.getContent();
            String moduleFileName = "requirements-modules_" + sessionId.substring(0, 8) + ".json";
            Path modulePath = Paths.get(fileConfig.getRequirementsPath(), moduleFileName).toAbsolutePath().normalize();
            Path moduleParent = modulePath.getParent();
            if (moduleParent != null && !Files.exists(moduleParent)) {
                Files.createDirectories(moduleParent);
            }
            Files.writeString(modulePath, moduleContent);

            AgentInput aadlInput = AgentInput.builder()
                    .sessionId(sessionId)
                    .content(archContent)
                    .metadata(moduleContent)
                    .modelType(modelType)
                    .build();
            AgentOutput aadlOutput = aadlGeneratorAgent.execute(aadlInput);

            if (!aadlOutput.isSuccess()) {
                response.put("success", false);
                response.put("message", aadlOutput.getErrorMessage());
                return objectMapper.writeValueAsString(response);
            }

            String aadlContent = aadlOutput.getContent();
            traceabilityService.addAadlTraceability(sessionId, aadlContent);

            String aadlFileName = "generated_model_" + sessionId.substring(0, 8) + ".aadl";
            Path aadlPath = Paths.get(fileConfig.getOutputPath(), aadlFileName).toAbsolutePath().normalize();
            Path aadlParent = aadlPath.getParent();
            if (aadlParent != null && !Files.exists(aadlParent)) {
                Files.createDirectories(aadlParent);
            }
            Files.writeString(aadlPath, aadlContent);
            
            sessionManager.setSessionHasAadlGenerated(sessionId, true);

            response.put("success", true);
            response.put("data", aadlContent);
            response.put("outputFile", aadlFileName);
            response.put("sessionId", sessionId);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            e.printStackTrace();
        }

        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}