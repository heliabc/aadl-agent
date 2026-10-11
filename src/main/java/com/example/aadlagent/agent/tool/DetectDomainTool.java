package com.example.aadlagent.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 领域检测工具
 *
 * 检测输入文本（需求文档、系统描述等）所属的应用领域，
 * 返回领域标签和置信度，帮助确定系统类型和适用的设计规范。
 *
 * 用途：在生成 AADL 模型之前，先识别系统所属领域，
 * 以便选择对应的设计规范、架构模式和验证标准。
 */
@Slf4j
@Component
public class DetectDomainTool implements Tool {

    private static final String TOOL_NAME = "detect_domain";

    /**
     * 各领域关键词库（中文关键词）
     */
    private static final Map<String, List<String>> DOMAIN_KEYWORDS = new LinkedHashMap<>();

    static {
        DOMAIN_KEYWORDS.put("汽车电子", Arrays.asList(
                "制动", "发动机", "ECU", "CAN", "车载", "油门", "转向",
                "ABS", "ESP", "ADAS", "整车", "车辆"
        ));
        DOMAIN_KEYWORDS.put("航空航天", Arrays.asList(
                "飞行", "航电", "飞控", "卫星", "机载", "航空", "航天",
                "导弹", "雷达", "无人机"
        ));
        DOMAIN_KEYWORDS.put("工业控制", Arrays.asList(
                "PLC", "工控", "流水线", "传感器", "工业总线", "Modbus",
                "PROFINET", "工厂", "机床"
        ));
        DOMAIN_KEYWORDS.put("医疗设备", Arrays.asList(
                "医疗", "诊断", "监护", "生命支持", "输液", "起搏器",
                "MRI", "CT", "医用"
        ));
        DOMAIN_KEYWORDS.put("通信系统", Arrays.asList(
                "通信", "基站", "路由器", "交换机", "5G", "LTE", "网络",
                "数据传输", "协议"
        ));
        DOMAIN_KEYWORDS.put("通用嵌入式", Arrays.asList(
                "嵌入式", "MCU", "单片机", "RTOS", "裸机", "嵌入式系统"
        ));
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "检测输入文本所属的应用领域，返回领域标签和置信度。帮助确定系统类型和适用的设计规范。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> textParam = new LinkedHashMap<>();
        textParam.put("type", "string");
        textParam.put("description", "需要检测领域的文本（需求文档、系统描述等）");
        props.put("text", textParam);

        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("text")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String text = (String) arguments.get("text");
        if (text == null || text.trim().isEmpty()) {
            return "错误：text 参数不能为空";
        }

        try {
            // 统计各领域关键词命中次数及具体关键词命中详情
            Map<String, Integer> domainHitCount = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> domainKeywordHits = new LinkedHashMap<>();
            int totalHits = 0;

            for (Map.Entry<String, List<String>> entry : DOMAIN_KEYWORDS.entrySet()) {
                String domain = entry.getKey();
                List<String> keywords = entry.getValue();
                int count = 0;
                Map<String, Integer> keywordCount = new LinkedHashMap<>();

                for (String keyword : keywords) {
                    int keywordHit = countKeywordOccurrences(text, keyword);
                    if (keywordHit > 0) {
                        keywordCount.put(keyword, keywordHit);
                        count += keywordHit;
                    }
                }

                domainHitCount.put(domain, count);
                domainKeywordHits.put(domain, keywordCount);
                totalHits += count;
            }

            // 如果总命中数为 0，返回未识别
            if (totalHits == 0) {
                log.info("DetectDomainTool: 未识别到任何领域关键词");
                return "=== 领域检测结果 ===\n" +
                        "主要领域: 未识别 (置信度: 0%)\n" +
                        "说明: 输入文本中未检测到已知领域的关键词，请提供更详细的系统描述。";
            }

            // 按命中次数降序排序
            List<Map.Entry<String, Integer>> sortedDomains = domainHitCount.entrySet()
                    .stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toList());

            // 取命中最多的 3 个领域
            int topN = Math.min(3, sortedDomains.size());

            StringBuilder sb = new StringBuilder();
            sb.append("=== 领域检测结果 ===\n");

            String[] labels = {"主要领域", "次要领域", "其他领域"};
            for (int i = 0; i < topN; i++) {
                Map.Entry<String, Integer> entry = sortedDomains.get(i);
                String domain = entry.getKey();
                int hits = entry.getValue();
                double confidence = (double) hits / totalHits * 100;
                sb.append(labels[i]).append(": ").append(domain)
                        .append(" (置信度: ").append(String.format("%.0f", confidence)).append("%)\n");
            }

            // 汇总关键词命中详情（按命中次数降序）
            List<Map.Entry<String, Integer>> allKeywordHits = new ArrayList<>();
            for (Map<String, Integer> kwMap : domainKeywordHits.values()) {
                allKeywordHits.addAll(kwMap.entrySet());
            }
            allKeywordHits.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

            sb.append("关键词命中: ");
            for (int i = 0; i < allKeywordHits.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Map.Entry<String, Integer> kw = allKeywordHits.get(i);
                sb.append(kw.getKey()).append("(").append(kw.getValue()).append("次)");
            }
            sb.append("\n");

            log.info("DetectDomainTool: 总命中 {} 次，主要领域: {}",
                    totalHits, sortedDomains.get(0).getKey());

            return sb.toString();

        } catch (Exception e) {
            log.error("DetectDomainTool execution error: {}", e.getMessage(), e);
            return "领域检测工具执行异常: " + e.getMessage();
        }
    }

    /**
     * 统计关键词在文本中出现的次数（不区分大小写，支持中英文字符串）
     *
     * @param text    输入文本
     * @param keyword 关键词
     * @return 出现次数
     */
    private int countKeywordOccurrences(String text, String keyword) {
        if (text == null || keyword == null || keyword.isEmpty()) {
            return 0;
        }
        // 不区分大小写匹配
        String lowerText = text.toLowerCase();
        String lowerKeyword = keyword.toLowerCase();

        int count = 0;
        int idx = 0;
        while ((idx = lowerText.indexOf(lowerKeyword, idx)) != -1) {
            count++;
            idx += lowerKeyword.length();
        }
        return count;
    }
}
