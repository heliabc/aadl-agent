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
    private List<String> collections = Arrays.asList("requirement", "architecture", "module", "aadl", "success_cases");
    private int maxRetries = 3;
    private long retryDelayMs = 200;
    /**
     * 启动时是否执行全量同步（upsert + 孤儿清理）。
     * true  = 每次启动都把 JSON 全量写入 Qdrant 并清理孤儿点（默认，开发环境推荐）
     * false = 启动只确保集合存在，直接复用 Qdrant 已有数据；
     *         仅当集合为空（count==0）时才做一次初始写入。
     *         适合 Qdrant 独立部署、项目频繁重建的场景。
     */
    private boolean syncOnStartup = true;

    @PostConstruct
    public void init() {
        log.info("Qdrant configuration: host={}, port={}, embeddingSize={}, syncOnStartup={}, maxRetries={}, retryDelayMs={}",
                host, port, embeddingSize, syncOnStartup, maxRetries, retryDelayMs);
    }
}
