package com.example.aadlagent.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AADL 元素统计工具
 *
 * 统计 AADL 代码中各种元素的数量（组件、端口、连接等），
 * 帮助 LLM 自查生成结果是否完整。
 *
 * 用途：在生成 AADL 代码后，调用此工具快速统计各类元素数量，
 * 让 LLM 检查是否遗漏了必要的组件、端口或连接。
 */
@Slf4j
@Component
public class CountAadlElementsTool implements Tool {

    private static final String TOOL_NAME = "count_aadl_elements";

    // 组件类型列表
    private static final String[] COMPONENT_TYPES = {
            "system", "process", "thread", "device",
            "processor", "memory", "bus", "data", "subprogram"
    };

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "统计 AADL 代码中各元素的数量，包括组件声明、实现声明、端口特征、连接关系、属性绑定等。用于检查生成结果是否完整。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> codeParam = new LinkedHashMap<>();
        codeParam.put("type", "string");
        codeParam.put("description", "需要统计的 AADL 代码");
        props.put("aadl_code", codeParam);

        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("aadl_code")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String aadlCode = (String) arguments.get("aadl_code");
        if (aadlCode == null || aadlCode.trim().isEmpty()) {
            return "错误：aadl_code 参数不能为空";
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("=== AADL 元素统计 ===\n");

            // 1. Package 声明数量
            int packageCount = countPackageDeclarations(aadlCode);
            sb.append("Package 声明: ").append(packageCount).append(" 个\n");

            // 2. 组件声明（按类型细分）
            Map<String, Integer> componentCounts = countComponentDeclarations(aadlCode);
            int totalComponents = componentCounts.values().stream().mapToInt(Integer::intValue).sum();
            sb.append("组件声明: ").append(totalComponents).append(" 个（");
            boolean first = true;
            for (String type : COMPONENT_TYPES) {
                int count = componentCounts.getOrDefault(type, 0);
                if (count > 0) {
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append(type).append(":").append(count);
                    first = false;
                }
            }
            sb.append("）\n");

            // 3. 实现声明
            int implementationCount = countImplementationDeclarations(aadlCode);
            sb.append("实现声明: ").append(implementationCount).append(" 个\n");

            // 4. 端口特征（按方向细分）
            Map<String, Integer> portCounts = countPortFeatures(aadlCode);
            int totalPorts = portCounts.values().stream().mapToInt(Integer::intValue).sum();
            sb.append("端口特征: ").append(totalPorts).append(" 个（");
            int inCount = portCounts.getOrDefault("in", 0);
            int outCount = portCounts.getOrDefault("out", 0);
            int busAccessCount = portCounts.getOrDefault("bus access", 0);
            sb.append("in:").append(inCount)
                    .append(", out:").append(outCount);
            if (busAccessCount > 0) {
                sb.append(", bus access:").append(busAccessCount);
            }
            sb.append("）\n");

            // 5. 子组件实例
            int subcomponentCount = countSubcomponents(aadlCode);
            sb.append("子组件实例: ").append(subcomponentCount).append(" 个\n");

            // 6. 连接关系
            Map<String, Integer> connectionCounts = countConnections(aadlCode);
            int totalConnections = connectionCounts.values().stream().mapToInt(Integer::intValue).sum();
            sb.append("连接关系: ").append(totalConnections).append(" 个（");
            boolean firstConn = true;
            for (Map.Entry<String, Integer> entry : connectionCounts.entrySet()) {
                if (entry.getValue() > 0) {
                    if (!firstConn) {
                        sb.append(", ");
                    }
                    sb.append(entry.getKey()).append(":").append(entry.getValue());
                    firstConn = false;
                }
            }
            sb.append("）\n");

            // 7. 属性绑定
            int propertyBindingCount = countPropertyBindings(aadlCode);
            sb.append("属性绑定: ").append(propertyBindingCount).append(" 个\n");

            log.info("CountAadlElementsTool: {} packages, {} components, {} implementations, {} ports, {} subcomponents, {} connections, {} property bindings",
                    packageCount, totalComponents, implementationCount, totalPorts, subcomponentCount, totalConnections, propertyBindingCount);

            return sb.toString();

        } catch (Exception e) {
            log.error("CountAadlElementsTool execution error: {}", e.getMessage(), e);
            return "统计工具执行异常: " + e.getMessage();
        }
    }

    /**
     * 统计 package 声明数量
     */
    private int countPackageDeclarations(String code) {
        Pattern pattern = Pattern.compile("(?i)^\\s*package\\s+\\S+", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(code);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 统计组件声明数量，按类型分类
     * 匹配模式：xxx 组件类型 （如 "system my_sys", "process my_proc"）
     */
    private Map<String, Integer> countComponentDeclarations(String code) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String type : COMPONENT_TYPES) {
            // 匹配 "类型 名称" 形式的组件声明，排除 implementation 行和子组件声明
            Pattern pattern = Pattern.compile(
                    "(?i)^\\s*" + type + "\\s+\\w+\\s*$",
                    Pattern.MULTILINE
            );
            Matcher matcher = pattern.matcher(code);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            counts.put(type, count);
        }
        return counts;
    }

    /**
     * 统计实现声明数量
     * 匹配模式：xxx implementation xxx
     */
    private int countImplementationDeclarations(String code) {
        Pattern pattern = Pattern.compile(
                "(?i)\\bimplementation\\s+\\w+",
                Pattern.MULTILINE
        );
        Matcher matcher = pattern.matcher(code);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 统计端口特征数量，按方向分类
     * 包括 data port, event port, event data port, bus access
     */
    private Map<String, Integer> countPortFeatures(String code) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int inCount = 0;
        int outCount = 0;
        int busAccessCount = 0;

        // data port in/out
        Pattern dataPortIn = Pattern.compile("(?i)data\\s+port\\s+in\\s+\\w+", Pattern.MULTILINE);
        Pattern dataPortOut = Pattern.compile("(?i)data\\s+port\\s+out\\s+\\w+", Pattern.MULTILINE);

        // event port in/out
        Pattern eventPortIn = Pattern.compile("(?i)event\\s+port\\s+in\\s+\\w+", Pattern.MULTILINE);
        Pattern eventPortOut = Pattern.compile("(?i)event\\s+port\\s+out\\s+\\w+", Pattern.MULTILINE);

        // event data port in/out
        Pattern eventDataPortIn = Pattern.compile("(?i)event\\s+data\\s+port\\s+in\\s+\\w+", Pattern.MULTILINE);
        Pattern eventDataPortOut = Pattern.compile("(?i)event\\s+data\\s+port\\s+out\\s+\\w+", Pattern.MULTILINE);

        // bus access
        Pattern busAccessPattern = Pattern.compile("(?i)bus\\s+access\\s+\\w+", Pattern.MULTILINE);

        // 统计 in 方向（注意 event data port 也会匹配 event port，所以先统计更具体的）
        // 用减法避免重复计数
        int eventDataIn = countMatches(eventDataPortIn, code);
        int eventIn = countMatches(eventPortIn, code) - eventDataIn;
        int dataIn = countMatches(dataPortIn, code) - eventDataIn - eventIn;
        inCount = dataIn + eventIn + eventDataIn;

        int eventDataOut = countMatches(eventDataPortOut, code);
        int eventOut = countMatches(eventPortOut, code) - eventDataOut;
        int dataOut = countMatches(dataPortOut, code) - eventDataOut - eventOut;
        outCount = dataOut + eventOut + eventDataOut;

        busAccessCount = countMatches(busAccessPattern, code);

        counts.put("in", inCount);
        counts.put("out", outCount);
        counts.put("bus access", busAccessCount);

        return counts;
    }

    /**
     * 统计子组件实例数量
     * 匹配 subcomponents 块内的声明
     */
    private int countSubcomponents(String code) {
        // 找到 subcomponents 块
        Pattern subcomponentsBlock = Pattern.compile(
                "(?is)subcomponents\\s*\\n(.*?)(?=\\n\\s*(connections|properties|flows|end)\\b|$)",
                Pattern.MULTILINE
        );
        Matcher blockMatcher = subcomponentsBlock.matcher(code);
        int totalCount = 0;

        while (blockMatcher.find()) {
            String blockContent = blockMatcher.group(1);
            // 在块内统计子组件声明行："名称 : 组件类型 引用;"
            Pattern subcompPattern = Pattern.compile(
                    "(?i)^\\s*\\w+\\s*:\\s*(system|process|thread|device|processor|memory|bus|data|subprogram)\\s+\\w+",
                    Pattern.MULTILINE
            );
            Matcher subcompMatcher = subcompPattern.matcher(blockContent);
            while (subcompMatcher.find()) {
                totalCount++;
            }
        }

        return totalCount;
    }

    /**
     * 统计连接关系数量
     * 包括 port connection 和 access connection
     */
    private Map<String, Integer> countConnections(String code) {
        Map<String, Integer> counts = new LinkedHashMap<>();

        Pattern portConnPattern = Pattern.compile(
                "(?i)port\\s+connection\\s+\\w+",
                Pattern.MULTILINE
        );
        Pattern accessConnPattern = Pattern.compile(
                "(?i)access\\s+connection\\s+\\w+",
                Pattern.MULTILINE
        );
        Pattern featureConnPattern = Pattern.compile(
                "(?i)feature\\s+connection\\s+\\w+",
                Pattern.MULTILINE
        );

        counts.put("port connection", countMatches(portConnPattern, code));
        counts.put("access connection", countMatches(accessConnPattern, code));
        counts.put("feature connection", countMatches(featureConnPattern, code));

        return counts;
    }

    /**
     * 统计属性绑定数量
     * 包括 Actual_Processor_Binding, Actual_Connection_Binding, Actual_Memory_Binding 等
     */
    private int countPropertyBindings(String code) {
        Pattern bindingPattern = Pattern.compile(
                "(?i)Actual_(Processor|Connection|Memory|Bus)_Binding\\s*=>",
                Pattern.MULTILINE
        );
        return countMatches(bindingPattern, code);
    }

    /**
     * 辅助方法：统计正则表达式匹配次数
     */
    private int countMatches(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
