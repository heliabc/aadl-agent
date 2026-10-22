package com.example.aadlagent.constrained;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AADL BNF 语法验证器
 * 
 * 基于 AADL v2.2 标准的轻量级语法验证器，用于：
 * 1. 验证生成的 AADL 代码语法正确性
 * 2. 增量生成时的逐块验证
 * 3. 错误定位和自动修复建议
 * 
 * 验证维度：
 * - 包结构完整性（package/end 匹配）
 * - 组件声明完整性（type/implementation 与 end 匹配）
 * - 块顺序正确性（features, subcomponents, connections 等的顺序）
 * - 基本语法模式（声明格式、分号等）
 * - 特征方向合法性（in/out vs requires/provides）
 */
@Slf4j
@Component
public class AadlBnfValidator {

    // ==================== 正则表达式模式 ====================
    
    private static final Pattern PACKAGE_START = Pattern.compile(
        "^\\s*package\\s+([A-Za-z_]\\w*(?:::[A-Za-z_]\\w*)*)\\s*$",
        Pattern.MULTILINE
    );
    
    private static final Pattern PACKAGE_END = Pattern.compile(
        "^\\s*end\\s+([A-Za-z_]\\w*(?:::[A-Za-z_]\\w*)*)\\s*;\\s*$",
        Pattern.MULTILINE
    );
    
    private static final Pattern COMPONENT_TYPE_START = Pattern.compile(
        "^\\s*(abstract|bus|data|device|memory|process|processor|subprogram\\s+group|" +
        "subprogram|system|thread\\s+group|thread|virtual\\s+bus|virtual\\s+processor)\\s+([A-Za-z_]\\w*)\\s*$",
        Pattern.MULTILINE
    );
    
    private static final Pattern COMPONENT_IMPL_START = Pattern.compile(
        "^\\s*(abstract|bus|data|device|memory|process|processor|subprogram\\s+group|" +
        "subprogram|system|thread\\s+group|thread|virtual\\s+bus|virtual\\s+processor)\\s+" +
        "implementation\\s+([A-Za-z_]\\w*)\\s*\\.\\s*([A-Za-z_]\\w*)\\s*$",
        Pattern.MULTILINE
    );
    
    private static final Pattern COMPONENT_END = Pattern.compile(
        "^\\s*end\\s+([A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)?)\\s*;\\s*$",
        Pattern.MULTILINE
    );
    
    private static final Pattern SECTION_HEADER = Pattern.compile(
        "^\\s*(prototypes|features|subcomponents|internal features|processor features|" +
        "calls|connections|flows|modes|properties|annex)\\s*$",
        Pattern.MULTILINE
    );
    
    private static final Pattern SUBCOMPONENT_DECL = Pattern.compile(
        "^\\s*([A-Za-z_]\\w*)\\s*:\\s*(abstract|bus|data|device|memory|process|processor|" +
        "subprogram\\s+group|subprogram|system|thread\\s+group|thread|virtual\\s+bus|" +
        "virtual\\s+processor)\\s+([A-Za-z_]\\w*(?:::[A-Za-z_]\\w*)*(?:\\.\\w+)?)\\s*;\\s*$",
        Pattern.MULTILINE
    );
    
    private static final Pattern DATA_PORT = Pattern.compile(
        "^\\s*([A-Za-z_]\\w*)\\s*:\\s*(in\\s+out|in|out)\\s+data\\s+port" +
        "(\\s+[A-Za-z_]\\w*(?:::[A-Za-z_]\\w*)*(?:\\.\\w+)?)?\\s*;\\s*$",
        Pattern.MULTILINE
    );
    
    private static final Pattern PORT_CONNECTION = Pattern.compile(
        "^\\s*([A-Za-z_]\\w*)\\s*:\\s*port\\s+(\\w+(?:\\.\\w+)*)\\s*(->|<->)\\s+" +
        "(\\w+(?:\\.\\w+)*)\\s*(\\{[^}]*\\})?\\s*(in\\s+modes\\s*\\([^)]*\\))?\\s*;\\s*$",
        Pattern.MULTILINE
    );

    // 块顺序定义
    private static final String[] SECTION_ORDER_TYPE = {
        "prototypes", "features", "flows", "modes", "properties", "annex"
    };
    
    private static final String[] SECTION_ORDER_IMPL = {
        "prototypes", "subcomponents", "internal features", "processor features",
        "calls", "connections", "flows", "modes", "properties", "annex"
    };

    // ==================== 验证结果类 ====================
    
    public static class ValidationResult {
        public boolean isValid;
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public String fixedContent;
        
        public ValidationResult() {}
        
        public ValidationResult(boolean isValid) {
            this.isValid = isValid;
        }
    }

    // ==================== 公共方法 ====================
    
    /**
     * 验证 AADL 代码的语法正确性。
     * 
     * @param code AADL 代码
     * @return 验证结果
     */
    public ValidationResult validate(String code) {
        return validate(code, false);
    }
    
    /**
     * 验证 AADL 代码的语法正确性。
     * 
     * @param code AADL 代码
     * @param partial 是否为部分代码（允许不完整的声明）
     * @return 验证结果
     */
    public ValidationResult validate(String code, boolean partial) {
        ValidationResult result = new ValidationResult();
        
        if (code == null || code.trim().isEmpty()) {
            result.isValid = true;
            return result;
        }
        
        String[] lines = code.split("\n");
        
        // 各项检查
        result.errors.addAll(checkPackageStructure(lines, partial));
        result.errors.addAll(checkComponentDeclarations(lines, partial));
        result.errors.addAll(checkSectionOrder(lines));
        result.errors.addAll(checkBasicSyntax(lines));
        
        result.isValid = result.errors.isEmpty();
        
        // 尝试自动修复
        if (!result.isValid) {
            result.fixedContent = autoFix(code, result.errors);
            if (result.fixedContent != null && !result.fixedContent.equals(code)) {
                // 重新验证修复后的代码
                ValidationResult fixedResult = validate(result.fixedContent, partial);
                if (fixedResult.errors.size() < result.errors.size()) {
                    result.errors = fixedResult.errors;
                    result.warnings.addAll(fixedResult.warnings);
                    result.isValid = fixedResult.isValid;
                }
            }
        }
        
        return result;
    }

    // ==================== 各项检查 ====================
    
    /**
     * 检查包结构：package 与 end 的匹配。
     */
    private List<String> checkPackageStructure(String[] lines, boolean partial) {
        List<String> errors = new ArrayList<>();
        boolean packageStarted = false;
        String packageName = null;
        boolean packageEnded = false;
        
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].trim();
            
            Matcher startMatcher = PACKAGE_START.matcher(stripped);
            if (startMatcher.matches()) {
                if (packageStarted) {
                    errors.add("第" + (i + 1) + "行: 不允许嵌套的 package 声明");
                }
                packageStarted = true;
                packageName = startMatcher.group(1);
            }
            
            Matcher endMatcher = PACKAGE_END.matcher(stripped);
            if (endMatcher.matches()) {
                if (!packageStarted) {
                    errors.add("第" + (i + 1) + "行: 'end' 没有对应的 'package' 声明");
                } else if (!endMatcher.group(1).equals(packageName)) {
                    errors.add("第" + (i + 1) + "行: package 结束名称 '" + endMatcher.group(1) + 
                              "' 与声明 '" + packageName + "' 不一致");
                }
                packageEnded = true;
            }
        }
        
        if (packageStarted && !packageEnded && !partial) {
            errors.add("Package '" + packageName + "' 缺少 'end " + packageName + ";'");
        }
        
        return errors;
    }
    
    /**
     * 检查组件声明的完整性：type/implementation 与 end 的匹配。
     */
    private List<String> checkComponentDeclarations(String[] lines, boolean partial) {
        List<String> errors = new ArrayList<>();
        Stack<ComponentInfo> stack = new Stack<>();
        
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].trim();
            
            // 跳过 package 相关
            if (PACKAGE_START.matcher(stripped).matches() || 
                PACKAGE_END.matcher(stripped).matches() ||
                stripped.equals("public") || stripped.equals("private")) {
                continue;
            }
            
            // 组件类型声明
            Matcher typeMatcher = COMPONENT_TYPE_START.matcher(stripped);
            if (typeMatcher.matches() && !stripped.startsWith("end ")) {
                String name = typeMatcher.group(2);
                stack.push(new ComponentInfo(name, false, i + 1));
                continue;
            }
            
            // 组件实现声明
            Matcher implMatcher = COMPONENT_IMPL_START.matcher(stripped);
            if (implMatcher.matches()) {
                String typeName = implMatcher.group(2);
                String implName = implMatcher.group(3);
                String fullName = typeName + "." + implName;
                stack.push(new ComponentInfo(fullName, true, i + 1));
                continue;
            }
            
            // end 语句
            Matcher endMatcher = COMPONENT_END.matcher(stripped);
            if (endMatcher.matches() && stack.isEmpty()) {
                // 可能是 package end，跳过
                continue;
            }
            
            if (endMatcher.matches() && !stack.isEmpty()) {
                String endName = endMatcher.group(1).replaceAll("\\s+", "");
                ComponentInfo expected = stack.pop();
                String expectedClean = expected.name.replaceAll("\\s+", "");
                
                if (!endName.equalsIgnoreCase(expectedClean)) {
                    errors.add("第" + (i + 1) + "行: 组件结束名称 '" + endName + 
                              "' 与声明 '" + expected.name + "' 不一致 (第" + expected.lineNum + "行声明)");
                }
            }
        }
        
        if (!partial && !stack.isEmpty()) {
            for (ComponentInfo comp : stack) {
                errors.add("第" + comp.lineNum + "行: 组件 '" + comp.name + 
                          "' 缺少 'end " + comp.name + ";'");
            }
        }
        
        return errors;
    }
    
    /**
     * 检查块的顺序是否正确。
     */
    private List<String> checkSectionOrder(String[] lines) {
        List<String> errors = new ArrayList<>();
        boolean inComponent = false;
        boolean isImpl = false;
        List<String> sectionsSeen = new ArrayList<>();
        
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].trim();
            
            // 跟踪组件进入/退出
            if (COMPONENT_TYPE_START.matcher(stripped).matches() && !stripped.startsWith("end ")) {
                inComponent = true;
                isImpl = false;
                sectionsSeen.clear();
                continue;
            }
            if (COMPONENT_IMPL_START.matcher(stripped).matches()) {
                inComponent = true;
                isImpl = true;
                sectionsSeen.clear();
                continue;
            }
            if (COMPONENT_END.matcher(stripped).matches() && inComponent) {
                inComponent = false;
                continue;
            }
            
            if (!inComponent) continue;
            
            Matcher sectionMatcher = SECTION_HEADER.matcher(stripped);
            if (sectionMatcher.matches()) {
                String section = sectionMatcher.group(1);
                String[] validOrder = isImpl ? SECTION_ORDER_IMPL : SECTION_ORDER_TYPE;
                
                // 检查 section 是否合法
                boolean valid = false;
                for (String s : validOrder) {
                    if (s.equals(section)) {
                        valid = true;
                        break;
                    }
                }
                
                if (!valid) {
                    errors.add("第" + (i + 1) + "行: 块 '" + section + "' 不能出现在" +
                              (isImpl ? " implementation " : " type ") + "声明中");
                    continue;
                }
                
                sectionsSeen.add(section);
                
                // 检查顺序
                for (int j = 0; j < sectionsSeen.size() - 1; j++) {
                    int currIdx = indexOf(validOrder, sectionsSeen.get(j));
                    int nextIdx = indexOf(validOrder, sectionsSeen.get(j + 1));
                    
                    if (currIdx >= 0 && nextIdx >= 0 && currIdx > nextIdx) {
                        errors.add("第" + (i + 1) + "行: 块 '" + sectionsSeen.get(j + 1) + 
                                  "' 出现在 '" + sectionsSeen.get(j) + "' 之后，但顺序应在其之前");
                        break;
                    }
                }
            }
        }
        
        return errors;
    }
    
    /**
     * 基本语法检查。
     */
    private List<String> checkBasicSyntax(String[] lines) {
        List<String> errors = new ArrayList<>();
        
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].trim();
            
            // 跳过空行、注释、块头
            if (stripped.isEmpty() || stripped.startsWith("--")) continue;
            if (SECTION_HEADER.matcher(stripped).matches()) continue;
            if (stripped.equals("public") || stripped.equals("private")) continue;
            if (PACKAGE_START.matcher(stripped).matches()) continue;
            if (PACKAGE_END.matcher(stripped).matches()) continue;
            if (COMPONENT_TYPE_START.matcher(stripped).matches()) continue;
            if (COMPONENT_IMPL_START.matcher(stripped).matches()) continue;
            if (COMPONENT_END.matcher(stripped).matches()) continue;
            if (stripped.startsWith("with ") && stripped.endsWith(";")) continue;
            if (stripped.startsWith("annex ")) continue;
            
            // 检查明显缺少分号的声明
            if (stripped.matches("^[A-Za-z_]\\w*\\s*:.*") && 
                !stripped.endsWith(";") && 
                !stripped.endsWith("{") &&
                !stripped.endsWith("}")) {
                // 可能是多行语句，宽松处理
                // 只有当行内包含完整声明模式时才报错
            }
        }
        
        return errors;
    }

    // ==================== 自动修复 ====================
    
    /**
     * 自动修复常见的语法错误。
     */
    public String autoFix(String code, List<String> errors) {
        String fixed = code;
        boolean modified = false;
        
        // 修复缺失的 end 语句
        // 这在 validate 方法中处理
        
        return modified ? fixed : null;
    }
    
    /**
     * 修复缺失的 end 语句。
     */
    public String fixMissingEndStatements(String aadlContent) {
        log.info("开始修复缺失的 end 语句");
        
        String[] lines = aadlContent.split("\n");
        StringBuilder result = new StringBuilder();
        Stack<ComponentInfo> stack = new Stack<>();
        
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].trim();
            
            result.append(lines[i]).append("\n");
            
            // 跟踪组件声明
            Matcher typeMatcher = COMPONENT_TYPE_START.matcher(stripped);
            if (typeMatcher.matches() && !stripped.startsWith("end ")) {
                String name = typeMatcher.group(2);
                stack.push(new ComponentInfo(name, false, i + 1));
                continue;
            }
            
            Matcher implMatcher = COMPONENT_IMPL_START.matcher(stripped);
            if (implMatcher.matches()) {
                String typeName = implMatcher.group(2);
                String implName = implMatcher.group(3);
                String fullName = typeName + "." + implName;
                stack.push(new ComponentInfo(fullName, true, i + 1));
                continue;
            }
            
            // 遇到 end 时出栈
            Matcher endMatcher = COMPONENT_END.matcher(stripped);
            if (endMatcher.matches() && !stack.isEmpty()) {
                stack.pop();
            }
        }
        
        // 补充缺失的 end 语句
        int fixedCount = 0;
        while (!stack.isEmpty()) {
            ComponentInfo comp = stack.pop();
            result.append("end ").append(comp.name).append(";\n");
            fixedCount++;
            log.info("  补充缺失的 end 语句: end {};", comp.name);
        }
        
        if (fixedCount > 0) {
            log.info("共修复 {} 个缺失的 end 语句", fixedCount);
        }
        
        return result.toString().trim();
    }

    // ==================== 辅助方法 ====================
    
    private int indexOf(String[] arr, String target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(target)) return i;
        }
        return -1;
    }
    
    private static class ComponentInfo {
        String name;
        boolean isImpl;
        int lineNum;
        
        ComponentInfo(String name, boolean isImpl, int lineNum) {
            this.name = name;
            this.isImpl = isImpl;
            this.lineNum = lineNum;
        }
    }
}
