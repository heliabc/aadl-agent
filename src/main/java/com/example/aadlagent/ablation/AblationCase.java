package com.example.aadlagent.ablation;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 消融实验测试用例
 */
@Data
public class AblationCase {

    private String id;
    private String name;
    private String description;
    private String category;       // 错误分类
    private String difficulty;     // easy / medium / hard

    private String buggyCode;      // 有错误的 AADL 代码
    private String expectedCode;   // 预期正确代码（参考用）
    private List<String> errors = new ArrayList<>(); // 错误信息或修复指令

    public AblationCase() {}

    public AblationCase(String id, String name, String category, String difficulty,
                        String buggyCode, String expectedCode, List<String> errors) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.difficulty = difficulty;
        this.buggyCode = buggyCode;
        this.expectedCode = expectedCode;
        this.errors = errors;
    }

    public String getErrorsText() {
        return String.join("\n", errors);
    }
}
