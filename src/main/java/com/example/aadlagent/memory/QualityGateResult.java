package com.example.aadlagent.memory;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.ArrayList;
import java.util.List;

/**
 * 质量检查结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QualityGateResult {

    /** 检查状态 */
    private QualityGateStatus status;

    /** 检查项列表（用于日志和调试） */
    @Builder.Default
    private List<String> passedChecks = new ArrayList<>();

    /** 失败的检查项及原因 */
    @Builder.Default
    private List<String> failedChecks = new ArrayList<>();

    /** 建议的重试/回退原因（给用户或日志看） */
    private String reason;

    /**
     * 快速创建 PASS 结果
     */
    public static QualityGateResult pass() {
        return QualityGateResult.builder()
                .status(QualityGateStatus.PASS)
                .build();
    }

    /**
     * 快速创建 RETRY 结果
     */
    public static QualityGateResult retry(String reason) {
        return QualityGateResult.builder()
                .status(QualityGateStatus.RETRY)
                .reason(reason)
                .build();
    }

    /**
     * 快速创建 ROLLBACK 结果
     */
    public static QualityGateResult rollback(String reason) {
        return QualityGateResult.builder()
                .status(QualityGateStatus.ROLLBACK)
                .reason(reason)
                .build();
    }

    /**
     * 快速创建 FAIL 结果
     */
    public static QualityGateResult fail(String reason) {
        return QualityGateResult.builder()
                .status(QualityGateStatus.FAIL)
                .reason(reason)
                .build();
    }

    /**
     * 添加通过的检查项
     */
    public QualityGateResult addPassedCheck(String check) {
        if (passedChecks == null) {
            passedChecks = new ArrayList<>();
        }
        passedChecks.add(check);
        return this;
    }

    /**
     * 添加失败的检查项
     */
    public QualityGateResult addFailedCheck(String check) {
        if (failedChecks == null) {
            failedChecks = new ArrayList<>();
        }
        failedChecks.add(check);
        return this;
    }

    /**
     * 是否通过
     */
    public boolean isPassed() {
        return status == QualityGateStatus.PASS;
    }
}
