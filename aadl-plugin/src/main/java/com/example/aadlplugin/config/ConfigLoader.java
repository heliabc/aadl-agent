package com.example.aadlplugin.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class ConfigLoader {
    
    private static final Logger log = Logger.getLogger(ConfigLoader.class.getName());

    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public static OllamaConfig loadOllamaConfig() {
        OllamaConfig config = new OllamaConfig();
        try {
            JsonNode root = loadYaml();
            if (root != null && root.has("ollama")) {
                JsonNode ollamaNode = root.get("ollama");
                if (ollamaNode.has("base-url")) {
                    config.setBaseUrl(ollamaNode.get("base-url").asText());
                }
                if (ollamaNode.has("chat-model")) {
                    config.setChatModel(ollamaNode.get("chat-model").asText());
                }
                if (ollamaNode.has("embedding-model")) {
                    config.setEmbeddingModel(ollamaNode.get("embedding-model").asText());
                }
                if (ollamaNode.has("timeout")) {
                    config.setTimeout(ollamaNode.get("timeout").asInt());
                }
            }
        } catch (Exception e) {
            log.warning(String.format("Failed to load Ollama config, using defaults: %s", e.getMessage()));
        }
        return config;
    }

    public static QdrantConfig loadQdrantConfig() {
        QdrantConfig config = new QdrantConfig();
        try {
            JsonNode root = loadYaml();
            if (root != null && root.has("qdrant")) {
                JsonNode qdrantNode = root.get("qdrant");
                if (qdrantNode.has("use-tls")) {
                    config.setUseTls(qdrantNode.get("use-tls").asBoolean());
                }
                if (qdrantNode.has("host")) {
                    config.setHost(qdrantNode.get("host").asText());
                }
                if (qdrantNode.has("port")) {
                    config.setPort(qdrantNode.get("port").asInt());
                }
                if (qdrantNode.has("api-key")) {
                    config.setApiKey(qdrantNode.get("api-key").asText());
                }
                if (qdrantNode.has("embedding-size")) {
                    config.setEmbeddingSize(qdrantNode.get("embedding-size").asInt());
                }
                if (qdrantNode.has("max-retries")) {
                    config.setMaxRetries(qdrantNode.get("max-retries").asInt());
                }
                if (qdrantNode.has("retry-delay-ms")) {
                    config.setRetryDelayMs(qdrantNode.get("retry-delay-ms").asLong());
                }
                if (qdrantNode.has("collections")) {
                    JsonNode collectionsNode = qdrantNode.get("collections");
                    if (collectionsNode.isArray()) {
                        List<String> collections = new ArrayList<>();
                        for (JsonNode node : collectionsNode) {
                            collections.add(node.asText());
                        }
                        config.setCollections(collections);
                    }
                }
            }
        } catch (Exception e) {
            log.warning(String.format("Failed to load Qdrant config, using defaults: %s", e.getMessage()));
        }
        log.info(String.format("Qdrant configuration: host=%s, port=%d, embeddingSize=%d, maxRetries=%d, retryDelayMs=%d",
                config.getHost(), config.getPort(), config.getEmbeddingSize(), config.getMaxRetries(), config.getRetryDelayMs()));
        return config;
    }

    public static FileConfig loadFileConfig() {
        FileConfig config = new FileConfig();
        try {
            JsonNode root = loadYaml();
            if (root != null && root.has("file")) {
                JsonNode fileNode = root.get("file");
                if (fileNode.has("input-path")) {
                    config.setInputPath(fileNode.get("input-path").asText());
                }
                if (fileNode.has("output-path")) {
                    config.setOutputPath(fileNode.get("output-path").asText());
                }
                if (fileNode.has("requirements-path")) {
                    config.setRequirementsPath(fileNode.get("requirements-path").asText());
                }
                if (fileNode.has("architecture-path")) {
                    config.setArchitecturePath(fileNode.get("architecture-path").asText());
                }
                if (fileNode.has("modules-path")) {
                    config.setModulesPath(fileNode.get("modules-path").asText());
                }
                if (fileNode.has("aadl-path")) {
                    config.setAadlPath(fileNode.get("aadl-path").asText());
                }
            }
        } catch (Exception e) {
            log.warning(String.format("Failed to load File config, using defaults: %s", e.getMessage()));
        }
        return config;
    }

    public static DeepSeekConfig loadDeepSeekConfig() {
        DeepSeekConfig config = new DeepSeekConfig();
        try {
            JsonNode root = loadYaml();
            if (root != null && root.has("deepseek")) {
                JsonNode deepseekNode = root.get("deepseek");
                if (deepseekNode.has("api-key")) {
                    config.setApiKey(deepseekNode.get("api-key").asText());
                }
                if (deepseekNode.has("base-url")) {
                    config.setBaseUrl(deepseekNode.get("base-url").asText());
                }
                if (deepseekNode.has("chat-model")) {
                    config.setChatModel(deepseekNode.get("chat-model").asText());
                }
                if (deepseekNode.has("embedding-model")) {
                    config.setEmbeddingModel(deepseekNode.get("embedding-model").asText());
                }
                if (deepseekNode.has("timeout")) {
                    config.setTimeout(deepseekNode.get("timeout").asInt());
                }
            }
        } catch (Exception e) {
            log.warning(String.format("Failed to load DeepSeek config, using defaults: %s", e.getMessage()));
        }
        return config;
    }

    public static RagConfig loadRagConfig() {
        RagConfig config = new RagConfig();
        try {
            JsonNode root = loadYaml();
            if (root != null && root.has("rag")) {
                JsonNode ragNode = root.get("rag");
                if (ragNode.has("top-k")) {
                    config.setTopK(ragNode.get("top-k").asInt());
                }
                if (ragNode.has("rerank-top-k")) {
                    config.setRerankTopK(ragNode.get("rerank-top-k").asInt());
                }
                if (ragNode.has("rewrite-query")) {
                    config.setRewriteQuery(ragNode.get("rewrite-query").asBoolean());
                }
                if (ragNode.has("use-rrf")) {
                    config.setUseRrf(ragNode.get("use-rrf").asBoolean());
                }
                if (ragNode.has("chunk-size")) {
                    config.setChunkSize(ragNode.get("chunk-size").asInt());
                }
                if (ragNode.has("chunk-overlap")) {
                    config.setChunkOverlap(ragNode.get("chunk-overlap").asInt());
                }
            }
        } catch (Exception e) {
            log.warning(String.format("Failed to load RAG config, using defaults: %s", e.getMessage()));
        }
        return config;
    }

    private static JsonNode loadYaml() {
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (is != null) {
                return yamlMapper.readTree(is);
            }
        } catch (Exception e) {
            log.warning(String.format("Error loading application.yml: %s", e.getMessage()));
        }
        return null;
    }
}
