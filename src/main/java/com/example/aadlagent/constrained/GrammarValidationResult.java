package com.example.aadlagent.constrained;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 语法验证结果
 */
@Data
public class GrammarValidationResult {
    
    /** 语法分数 (0-100) */
    private int score;
    
    /** 错误数量 */
    private int errorCount;
    
    /** 错误列表 */
    private List<String> errors = new ArrayList<>();
    
    /** 是否有 package 声明 */
    private boolean hasPackage;
    
    /** package 是否闭合 */
    private boolean packageClosed;
}
