package com.example.aadlagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
}