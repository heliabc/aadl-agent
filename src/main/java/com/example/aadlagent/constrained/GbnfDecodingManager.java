package com.example.aadlagent.constrained;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * GBNF 受限解码管理器
 * 
 * 统一管理 GBNF 受限解码服务的访问，提供：
 * - 服务可用性检测
 * - 优雅降级（服务不可用时返回 null，由调用方处理）
 * - 本地语法验证器（始终可用，用于快速检查）
 */
@Slf4j
@Component
public class GbnfDecodingManager {

    private final GbnfConstrainedDecodingService gbnfService;
    private final AadlBnfValidator localValidator;

    public GbnfDecodingManager(GbnfConstrainedDecodingService gbnfService,
                               AadlBnfValidator localValidator) {
        this.gbnfService = gbnfService;
        this.localValidator = localValidator;
    }

    @PostConstruct
    public void init() {
        log.info("初始化 GBNF 受限解码管理器...");
        boolean available = gbnfService.isAvailable();
        log.info("GBNF 服务状态: {}", available ? "可用" : "不可用");
        if (!available) {
            log.info("  将使用本地验证器进行语法检查（无约束生成能力）");
        }
    }

    /**
     * GBNF 约束生成。
     * 
     * @return 生成结果，如果服务不可用返回 null
     */
    public GbnfDecodingResult constrainedGenerate(GbnfDecodingRequest request) {
        if (!gbnfService.isAvailable()) {
            log.warn("GBNF 服务不可用，无法进行约束生成");
            return null;
        }
        return gbnfService.generate(request);
    }

    /**
     * 无约束生成（baseline，用于对比）。
     */
    public GbnfDecodingResult baselineGenerate(GbnfDecodingRequest request) {
        if (!gbnfService.isAvailable()) {
            return null;
        }
        return gbnfService.generateBaseline(request);
    }

    /**
     * 本地语法验证（快速，不依赖外部服务）。
     */
    public AadlBnfValidator.ValidationResult localValidate(String code) {
        return localValidator.validate(code);
    }

    /**
     * 检查 GBNF 约束生成服务是否可用。
     */
    public boolean isGbnfAvailable() {
        return gbnfService.isAvailable();
    }

    /**
     * 获取本地验证器。
     */
    public AadlBnfValidator getLocalValidator() {
        return localValidator;
    }
}
