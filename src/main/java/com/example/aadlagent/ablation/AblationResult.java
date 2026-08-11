package com.example.aadlagent.ablation;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 单次消融实验结果
 */
@Data
public class AblationResult {

    private String caseId;
    private String caseName;
    private String setupLabel;       // 实验组标签

    private int initialErrors;       // 初始错误数
    private int finalErrors;         // 最终错误数
    private boolean success;         // 是否完全修复（最终错误数为 0）

    private String fixedCode;        // 修复后的代码
    private List<String> remainingErrors = new ArrayList<>(); // 剩余错误

    private long timeMs;             // 耗时

    public double getFixRate() {
        if (initialErrors == 0) return finalErrors == 0 ? 1.0 : 0.0;
        return Math.max(0.0, (double) (initialErrors - finalErrors) / initialErrors);
    }
}
