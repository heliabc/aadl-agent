package com.example.aadlagent.constrained;

import lombok.Builder;
import lombok.Data;

/**
 * GBNF 受限解码请求
 */
@Data
@Builder
public class GbnfDecodingRequest {
    
    /** 生成提示词 */
    private String prompt;
    
    /** 最大生成 token 数 */
    @Builder.Default
    private int maxTokens = 2048;
    
    /** 温度参数 */
    @Builder.Default
    private double temperature = 0.1;
    
    /** Top-p 采样参数 */
    @Builder.Default
    private double topP = 0.95;
    
    /** GBNF 语法根规则名称 */
    @Builder.Default
    private String grammarRoot = "root";
    
    /** 自定义 GBNF 语法（可选） */
    private String customGrammar;
}
