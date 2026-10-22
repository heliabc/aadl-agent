package com.example.aadlagent.constrained;

/**
 * GBNF 受限解码服务接口
 * 
 * 基于 llama.cpp GBNF 语法的真正 token 级约束解码。
 * 模型在生成每个 token 时都受到语法规则约束，输出保证语法正确。
 */
public interface GbnfConstrainedDecodingService {
    
    /**
     * 使用 GBNF 语法约束生成 AADL 代码。
     * 
     * @param request 生成请求
     * @return 生成结果
     */
    GbnfDecodingResult generate(GbnfDecodingRequest request);
    
    /**
     * 无约束生成（用于对比实验 baseline）。
     */
    GbnfDecodingResult generateBaseline(GbnfDecodingRequest request);
    
    /**
     * 验证 AADL 代码语法。
     */
    GrammarValidationResult validate(String code);
    
    /**
     * 检查服务是否可用。
     */
    boolean isAvailable();
}
