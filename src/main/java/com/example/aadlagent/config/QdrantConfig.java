package com.example.aadlagent.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;

@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "qdrant")
public class QdrantConfig {

    private boolean useTls = false;
    private String host = "localhost";
    private int port = 6334;
    private String apiKey;
    private int embeddingSize = 384;
    private List<String> collections = Arrays.asList("requirement", "architecture", "module", "aadl");

    @PostConstruct
    public void init() {
        log.info("Qdrant configuration: host={}, port={}", host, port);
    }
}