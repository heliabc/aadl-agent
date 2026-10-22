package com.example.aadlagent.constrained;

import lombok.Builder;
import lombok.Data;

/**
 * GBNF 受限解码结果
 */
@Data
@Builder
public class GbnfDecodingResult {
    
    /** 是否成功 */
    private boolean success;
    
    /** 生成的文本 */
    private String text;
    
    /** 生成的 token 数 */
    private int tokensGenerated;
    
    /** 生成耗时（毫秒） */
    private long elapsedMs;
    
    /** 生成速度（token/秒） */
    private double tokensPerSec;
    
    /** 结束原因 */
    private String finishReason;
    
    /** 是否使用了 GBNF 语法约束 */
    private boolean grammarConstrained;
    
    /** 语法验证结果 */
    private GrammarValidationResult grammarCheck;
    
    /** 错误信息（失败时） */
    private String errorMessage;
}
