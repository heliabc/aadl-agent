
package com.example.aadlagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Requirement {

    private String requirementId;

    private String title;

    private String description;

    private String priority;

    private List<String> acceptanceCriteria;

    private List<String> dependencies;

    private List<String> globalRef;

    /** AADL 建模提示，供下游架构树生成和 AADL 代码生成参考 */
    private AadlHints aadlHints;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AadlHints {
        /** 建议的 AADL 组件类型：thread, process, device, bus, processor, memory, data */
        private String componentType;
        /** 数据流向：input, output, internal, bidirectional */
        private String dataDirection;
        /** 接口类型：data_port, event_port, bus_access, none */
        private String interfaceType;
        /** 时序约束 */
        private TimingConstraint timingConstraint;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimingConstraint {
        /** 周期（毫秒），非周期任务为 null */
        private Double periodMs;
        /** 截止时间（毫秒），无要求为 null */
        private Double deadlineMs;
    }
}
