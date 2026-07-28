package com.example.aadlagent.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "file")
public class FileConfig {

    private String inputPath = "./input";

    private String outputPath = "./output";

    private String requirementsPath = "./output/requirements";

    private String architecturePath = "./output/architecture";

    private String modulesPath = "./output/modules";

    private String aadlPath = "./output/aadl";

    @PostConstruct
    public void init() {
        // 将所有相对路径转换为绝对路径
        this.inputPath = toAbsolutePath(inputPath);
        this.outputPath = toAbsolutePath(outputPath);
        this.requirementsPath = toAbsolutePath(requirementsPath);
        this.architecturePath = toAbsolutePath(architecturePath);
        this.modulesPath = toAbsolutePath(modulesPath);
        this.aadlPath = toAbsolutePath(aadlPath);
        
        log.info("FileConfig initialized with absolute paths:");
        log.info("  inputPath: {}", inputPath);
        log.info("  outputPath: {}", outputPath);
        log.info("  requirementsPath: {}", requirementsPath);
        log.info("  architecturePath: {}", architecturePath);
        log.info("  modulesPath: {}", modulesPath);
        log.info("  aadlPath: {}", aadlPath);
    }

    private String toAbsolutePath(String path) {
        if (path == null) {
            return null;
        }
        Path absolutePath = Paths.get(path).toAbsolutePath().normalize();
        return absolutePath.toString();
    }
}