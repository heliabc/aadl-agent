package com.example.aadlplugin;

import com.example.aadlplugin.agent.aadl.AadlFixerAgent;
import com.example.aadlplugin.agent.aadl.AadlGeneratorAgent;
import com.example.aadlplugin.agent.architecture.AadlArchitectureAgent;
import com.example.aadlplugin.agent.module.ModuleAnalysisAgent;
import com.example.aadlplugin.agent.requirement.RequirementAgent;
import com.example.aadlplugin.client.DeepSeekClient;
import com.example.aadlplugin.client.ModelService;
import com.example.aadlplugin.client.ModelType;
import com.example.aadlplugin.client.OllamaClient;
import com.example.aadlplugin.config.*;
import com.example.aadlplugin.rag.*;
import com.example.aadlplugin.service.TraceabilityService;
import com.example.aadlplugin.session.SessionManager;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.example.aadlplugin";

    private static Activator plugin;

    private SessionManager sessionManager;
    private RagService ragService;
    private TraceabilityService traceabilityService;
    private KnowledgeBaseManager knowledgeBaseManager;
    private ModelService modelService;
    private FileConfig fileConfig;
    
    private RequirementAgent requirementAgent;
    private AadlArchitectureAgent architectureAgent;
    private ModuleAnalysisAgent moduleAnalysisAgent;
    private AadlGeneratorAgent aadlGeneratorAgent;
    private AadlFixerAgent aadlFixerAgent;

    private static final Logger log = Logger.getLogger(Activator.class.getName());

    public Activator() {
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        
        initServices();
    }

    private void initServices() {
        try {
            log.info("Initializing AADL Plugin services...");
            
            fileConfig = ConfigLoader.loadFileConfig();
            Path pluginStatePath = getStateLocation().toFile().toPath().toAbsolutePath().normalize();
            String pluginStateDir = pluginStatePath.toString();
            log.info("Plugin state location: " + pluginStateDir);
            
            fileConfig.setInputPath(pluginStateDir + "/input");
            fileConfig.setOutputPath(pluginStateDir + "/output");
            fileConfig.setRequirementsPath(pluginStateDir + "/output/requirements");
            fileConfig.setArchitecturePath(pluginStateDir + "/output/architecture");
            fileConfig.setModulesPath(pluginStateDir + "/output/modules");
            fileConfig.setAadlPath(pluginStateDir + "/output/aadl");
            
            OllamaConfig ollamaConfig = ConfigLoader.loadOllamaConfig();
            QdrantConfig qdrantConfig = ConfigLoader.loadQdrantConfig();
            DeepSeekConfig deepSeekConfig = ConfigLoader.loadDeepSeekConfig();
            RagConfig ragConfig = ConfigLoader.loadRagConfig();

            OllamaClient ollamaClient = new OllamaClient(ollamaConfig);
            DeepSeekClient deepSeekClient = new DeepSeekClient(deepSeekConfig);
            modelService = new ModelService(ollamaClient, deepSeekClient);

            EmbeddingService embeddingService = new EmbeddingService(ollamaClient);
            QdrantVectorStore qdrantVectorStore = new QdrantVectorStore(qdrantConfig);
            qdrantVectorStore.init();
            QueryRewriter queryRewriter = new QueryRewriter(ollamaClient);
            Reranker reranker = new Reranker(ollamaClient, ragConfig);
            RrfFusion rrfFusion = new RrfFusion(ragConfig);
            VectorSearchService vectorSearchService = new VectorSearchService(embeddingService, ragConfig);
            vectorSearchService.init();

            knowledgeBaseManager = new KnowledgeBaseManager(embeddingService, qdrantVectorStore);
            knowledgeBaseManager.init();

            ragService = new RagService(queryRewriter, qdrantVectorStore, vectorSearchService, 
                    rrfFusion, reranker, ragConfig, knowledgeBaseManager, embeddingService);

            sessionManager = new SessionManager();
            traceabilityService = new TraceabilityService(ollamaClient, fileConfig.getOutputPath());

            requirementAgent = new RequirementAgent(modelService);
            architectureAgent = new AadlArchitectureAgent(modelService);
            moduleAnalysisAgent = new ModuleAnalysisAgent(modelService);
            aadlGeneratorAgent = new AadlGeneratorAgent(modelService);
            aadlFixerAgent = new AadlFixerAgent(modelService);

            log.info("AADL Plugin services initialized successfully");
        } catch (Exception e) {
            log.severe("Failed to initialize AADL Plugin services: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public RagService getRagService() {
        return ragService;
    }

    public TraceabilityService getTraceabilityService() {
        return traceabilityService;
    }

    public KnowledgeBaseManager getKnowledgeBaseManager() {
        return knowledgeBaseManager;
    }

    public ModelService getModelService() {
        return modelService;
    }

    public FileConfig getFileConfig() {
        return fileConfig;
    }

    public RequirementAgent getRequirementAgent() {
        return requirementAgent;
    }

    public AadlArchitectureAgent getArchitectureAgent() {
        return architectureAgent;
    }

    public ModuleAnalysisAgent getModuleAnalysisAgent() {
        return moduleAnalysisAgent;
    }

    public AadlGeneratorAgent getAadlGeneratorAgent() {
        return aadlGeneratorAgent;
    }

    public AadlFixerAgent getAadlFixerAgent() {
        return aadlFixerAgent;
    }

    public String executeAgent(String requirement, ModelType modelType) {
        return com.example.aadlplugin.controller.PluginController.executeFullFlow(
                requirement, modelType, null,
                sessionManager, ragService, traceabilityService, fileConfig,
                requirementAgent, architectureAgent, moduleAnalysisAgent, 
                aadlGeneratorAgent, aadlFixerAgent
        );
    }

    public String executeAgentIncremental(String requirement, ModelType modelType, String sessionId) {
        return com.example.aadlplugin.controller.PluginController.executeFullFlow(
                requirement, modelType, sessionId,
                sessionManager, ragService, traceabilityService, fileConfig,
                requirementAgent, architectureAgent, moduleAnalysisAgent, 
                aadlGeneratorAgent, aadlFixerAgent
        );
    }
}