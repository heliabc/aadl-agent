package com.example.aadlagent.client;

import com.example.aadlagent.client.dto.ChatResponse;
import com.example.aadlagent.client.dto.ChatMessage;
import com.example.aadlagent.client.dto.EmbeddingResponse;
import com.example.aadlagent.config.OllamaConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OllamaClient implements LlmClient {

    private final OllamaConfig config;
    private final RestTemplate restTemplate;

    public OllamaClient(OllamaConfig config) {
        this.config = config;
        this.restTemplate = createRestTemplate();
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getTimeout());
        factory.setReadTimeout(config.getTimeout());
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(factory);
        // 配置错误处理器，让我们能够自己处理HTTP错误响应而不抛出异常
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                return false;
            }
        });
        return restTemplate;
    }

    @Override
    public String chat(String prompt, Double temperature, Integer maxTokens) {
        return chat(prompt, temperature, maxTokens, null);
    }

    @Override
    public String chat(String prompt, Double temperature, Integer maxTokens, String modelName) {
        String url = buildUrl("/api/chat");

        Map<String, Object> requestBody = new HashMap<>();
        // 使用指定的模型名称，否则使用默认配置
        String model = (modelName != null && !modelName.isEmpty()) ? modelName : config.getChatModel();
        requestBody.put("model", model);

        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        requestBody.put("messages", Arrays.asList(message));
        requestBody.put("stream", false);

        if (temperature != null) {
            requestBody.put("temperature", temperature);
        }
        if (maxTokens != null) {
            requestBody.put("max_tokens", maxTokens);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<ChatResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    ChatResponse.class
            );

            log.debug("Ollama chat response status: {}", response.getStatusCode());

            if (response.getStatusCode().is2xxSuccessful()) {
                ChatResponse chatResponse = response.getBody();
                if (chatResponse != null) {
                    log.debug("Ollama chat parsed response: model={}, done={}, message=null={}", 
                            chatResponse.getModel(), chatResponse.isDone(), 
                            chatResponse.getMessage() == null);
                    
                    if (chatResponse.getMessage() != null) {
                        String content = chatResponse.getMessage().getContent();
                        log.debug("Ollama chat message content: null={}, length={}", 
                                content == null, content != null ? content.length() : -1);
                        String cleaned = cleanThinkingContent(content);
                        if (cleaned == null || cleaned.isEmpty()) {
                            log.warn("Ollama chat response: content is empty after cleaning thinking tags; " +
                                    "original content length={}", content == null ? "null" : content.length());
                        }
                        return cleaned;
                    } else {
                        log.warn("Ollama chat response: message is null in response");
                    }
                } else {
                    log.warn("Ollama chat response: response body is null");
                }
            } else {
                log.error("Ollama chat request failed with status: {}", response.getStatusCode());
            }
            return null;
        } catch (RestClientException e) {
            log.error("Ollama chat request exception: {}", e.getMessage(), e);
            return null;
        }
    }

    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            log.warn("Input text is empty, skipping embedding generation");
            return null;
        }

        String url = buildUrl("/api/embed");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getEmbeddingModel());
        requestBody.put("input", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<EmbeddingResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    EmbeddingResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                EmbeddingResponse embeddingResponse = response.getBody();
                
                if (embeddingResponse == null) {
                    log.error("Ollama returned null response body");
                    return null;
                }
                
                log.debug("Ollama embedding response: model={}, embeddings count={}, usage={}",
                        embeddingResponse.getModel(),
                        embeddingResponse.getEmbeddings() != null ? embeddingResponse.getEmbeddings().size() : "null",
                        embeddingResponse.getUsage());
                
                if (embeddingResponse.getEmbeddings() == null || embeddingResponse.getEmbeddings().isEmpty()) {
                    log.error("Ollama returned null or empty embeddings container");
                    return null;
                }
                
                List<Float> embeddingList = embeddingResponse.getEmbeddings().get(0);
                if (embeddingList == null || embeddingList.isEmpty()) {
                    log.error("The first embedding vector is null or empty");
                    return null;
                }
                
                float[] embedding = new float[embeddingList.size()];
                for (int i = 0; i < embeddingList.size(); i++) {
                    Float value = embeddingList.get(i);
                    embedding[i] = (value != null) ? value : 0.0f;
                }
                
                log.debug("Successfully generated embedding of dimension: {}", embedding.length);
                return embedding;
            }
            
            log.error("Ollama embedding request failed with status: {}", response.getStatusCode());
            return null;
            
        } catch (RestClientException e) {
            log.error("Ollama embedding request exception: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public boolean checkModel(String modelName) {
        String url = buildUrl("/api/tags");

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                String responseBody = response.getBody();
                return responseBody.contains(modelName);
            }
            return false;
        } catch (RestClientException e) {
            log.warn("Ollama tags request exception: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return checkModel(config.getChatModel());
    }

    @Override
    public String getModelName() {
        return config.getChatModel();
    }

    /**
     * 构建URL，确保正确处理斜杠
     */
    private String buildUrl(String path) {
        String baseUrl = config.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return baseUrl + path;
    }

    /**
     * 移除 qwen3 等模型在 content 中返回的 <think>...</think> 思考标签及其内容。
     * 这些标签会干扰后续的 JSON 提取与解析。
     */
    private String cleanThinkingContent(String content) {
        if (content == null) {
            return null;
        }
        // 移除完整的 <think>...</think> 块（包括跨行内容）
        String result = content.replaceAll("(?s)<think>.*?</think>", "");
        // 处理未闭合的 <think> 标签（输出被截断，只有开始标签没有结束标签）
        int thinkStart = result.indexOf("<think>");
        if (thinkStart >= 0) {
            result = result.substring(0, thinkStart);
        }
        return result.trim();
    }
}