package com.example.aadlagent.constrained;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * GBNF 受限解码服务实现（调用 Python llama-cpp-python 服务）
 * 
 * 工作原理：
 * - 通过 HTTP 调用 Python GBNF 服务
 * - 服务端使用 llama.cpp 的 GBNF 语法约束功能
 * - 模型在 token 级受到语法约束，输出保证语法正确
 * 
 * 部署方式：
 * - Python 服务独立部署在 GPU 机器上
 * - Java 端通过 REST API 调用
 * - 支持水平扩展（多实例 Python 服务 + 负载均衡）
 */
@Slf4j
@Service
public class GbnfConstrainedDecodingServiceImpl implements GbnfConstrainedDecodingService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AadlBnfValidator localValidator;

    @Value("${gbnf.server.base-url:http://localhost:8082}")
    private String serverBaseUrl;

    @Value("${gbnf.server.timeout-seconds:300}")
    private int timeoutSeconds;

    public GbnfConstrainedDecodingServiceImpl(AadlBnfValidator localValidator) {
        this.localValidator = localValidator;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        this.restTemplate = new RestTemplate(factory);
        
        // 忽略 HTTP 错误，自己处理
        restTemplate.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) 
                    throws java.io.IOException {
                return false;
            }
        });
    }

    @Override
    public GbnfDecodingResult generate(GbnfDecodingRequest request) {
        return callGenerateEndpoint("/api/generate", request);
    }

    @Override
    public GbnfDecodingResult generateBaseline(GbnfDecodingRequest request) {
        return callGenerateEndpoint("/api/generate/baseline", request);
    }

    @Override
    public GrammarValidationResult validate(String code) {
        // 优先使用本地验证器（更快）
        AadlBnfValidator.ValidationResult localResult = localValidator.validate(code);
        GrammarValidationResult result = new GrammarValidationResult();
        result.setErrors(localResult.errors);
        result.setErrorCount(localResult.errors.size());
        result.setScore(Math.max(0, 100 - localResult.errors.size() * 10));
        return result;
    }

    @Override
    public boolean isAvailable() {
        try {
            String url = serverBaseUrl + "/health";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                var body = objectMapper.readTree(response.getBody());
                return body.path("model_loaded").asBoolean(false)
                        && body.path("grammar_loaded").asBoolean(false);
            }
        } catch (Exception e) {
            log.debug("GBNF service not available: {}", e.getMessage());
        }
        return false;
    }

    // ==================== 内部方法 ====================

    private GbnfDecodingResult callGenerateEndpoint(String endpoint, GbnfDecodingRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            String url = serverBaseUrl + endpoint;
            
            Map<String, Object> body = new HashMap<>();
            body.put("prompt", request.getPrompt());
            body.put("max_tokens", request.getMaxTokens());
            body.put("temperature", request.getTemperature());
            body.put("top_p", request.getTopP());
            body.put("grammar_root", request.getGrammarRoot());
            
            if (request.getCustomGrammar() != null && !request.getCustomGrammar().isEmpty()) {
                body.put("grammar", request.getCustomGrammar());
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> httpRequest = new HttpEntity<>(body, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, httpRequest, String.class
            );
            
            if (!response.getStatusCode().is2xxSuccessful()) {
                return GbnfDecodingResult.builder()
                    .success(false)
                    .errorMessage("HTTP " + response.getStatusCode() + ": " + response.getBody())
                    .build();
            }
            
            var json = objectMapper.readTree(response.getBody());
            
            if (!json.path("success").asBoolean(false)) {
                return GbnfDecodingResult.builder()
                    .success(false)
                    .errorMessage(json.path("error").asText("Unknown error"))
                    .build();
            }
            
            String text = json.path("text").asText("");
            int tokens = json.path("tokens_generated").asInt(0);
            long elapsed = json.path("elapsed_ms").asLong(0);
            double tokPerSec = json.path("tokens_per_sec").asDouble(0);
            String finishReason = json.path("finish_reason").asText("");
            boolean constrained = json.path("grammar_constrained").asBoolean(false);
            
            // 解析语法检查结果
            GrammarValidationResult grammarCheck = new GrammarValidationResult();
            var gcNode = json.path("grammar_check");
            if (!gcNode.isMissingNode()) {
                grammarCheck.setScore(gcNode.path("score").asInt(0));
                grammarCheck.setErrorCount(gcNode.path("error_count").asInt(0));
                grammarCheck.setHasPackage(gcNode.path("has_package").asBoolean(false));
                grammarCheck.setPackageClosed(gcNode.path("package_closed").asBoolean(false));
                
                var errorsNode = gcNode.path("errors");
                if (errorsNode.isArray()) {
                    java.util.List<String> errors = new java.util.ArrayList<>();
                    for (var err : errorsNode) {
                        errors.add(err.asText());
                    }
                    grammarCheck.setErrors(errors);
                }
            }
            
            return GbnfDecodingResult.builder()
                .success(true)
                .text(text)
                .tokensGenerated(tokens)
                .elapsedMs(elapsed > 0 ? elapsed : System.currentTimeMillis() - startTime)
                .tokensPerSec(tokPerSec)
                .finishReason(finishReason)
                .grammarConstrained(constrained)
                .grammarCheck(grammarCheck)
                .build();
                
        } catch (Exception e) {
            log.error("GBNF generation failed: {}", e.getMessage());
            return GbnfDecodingResult.builder()
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }
}
