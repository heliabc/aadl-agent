package com.example.aadlagent.client;

import com.example.aadlagent.client.dto.EmbeddingResponse;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OllamaEmbeddingTest {

    private static final String BASE_URL = "http://localhost:11434";
    private static final String EMBEDDING_MODEL = "qwen3-embedding:latest";

    public static void main(String[] args) {
        System.out.println("=== Ollama Embedding Test ===");
        System.out.println("Base URL: " + BASE_URL);
        System.out.println("Embedding Model: " + EMBEDDING_MODEL);
        System.out.println();

        RestTemplate restTemplate = new RestTemplate();

        testEmbeddingApi(restTemplate, "hello world");
        testEmbeddingApi(restTemplate, "嵌入式实时系统架构设计");
        testEmbeddingApi(restTemplate, "AADL syntax error fix");
    }

    private static void testEmbeddingApi(RestTemplate restTemplate, String text) {
        System.out.println("Testing with text: \"" + text + "\"");
        System.out.println("Text length: " + text.length());

        String url = BASE_URL + "/api/embed";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", EMBEDDING_MODEL);
        requestBody.put("input", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            System.out.println("Raw response status: " + rawResponse.getStatusCode());
            System.out.println("Raw response body: " + rawResponse.getBody());
            System.out.println();

            ResponseEntity<EmbeddingResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    EmbeddingResponse.class
            );

            EmbeddingResponse embeddingResponse = response.getBody();

            if (embeddingResponse == null) {
                System.out.println("ERROR: EmbeddingResponse is null");
                System.out.println();
                return;
            }

            System.out.println("Model: " + embeddingResponse.getModel());
            System.out.println("Prompt Eval Count: " + embeddingResponse.getPromptEvalCount());
            System.out.println("Usage: " + embeddingResponse.getUsage());

            List<List<Float>> embeddings = embeddingResponse.getEmbeddings();
            if (embeddings == null) {
                System.out.println("ERROR: embeddings is null");
                System.out.println();
                return;
            }

            System.out.println("Embeddings array count: " + embeddings.size());

            if (embeddings.isEmpty()) {
                System.out.println("ERROR: embeddings array is empty");
                System.out.println();
                return;
            }

            List<Float> embeddingList = embeddings.get(0);
            if (embeddingList == null || embeddingList.isEmpty()) {
                System.out.println("ERROR: First embedding vector is null or empty");
                System.out.println();
                return;
            }

            System.out.println("Embedding dimension: " + embeddingList.size());
            System.out.println("First 10 values: " + embeddingList.subList(0, Math.min(10, embeddingList.size())));

            float[] embedding = new float[embeddingList.size()];
            for (int i = 0; i < embeddingList.size(); i++) {
                Float value = embeddingList.get(i);
                embedding[i] = (value != null) ? value : 0.0f;
            }

            System.out.println("SUCCESS: Embedding generated with dimension " + embedding.length);
            System.out.println();

        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.out.println();
        }
    }
}