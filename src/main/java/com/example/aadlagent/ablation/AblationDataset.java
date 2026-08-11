package com.example.aadlagent.ablation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 消融实验测试用例集
 */
public class AblationDataset {

    public static List<AblationCase> getAll() {
        List<AblationCase> cases = new ArrayList<>();

        // ===== 简单 =====
        cases.add(new AblationCase("E01", "缺少分号", "语法错误", "easy",
                buggyMissingSemicolon(), expectedMissingSemicolon(),
                Arrays.asList("第6行: 语法错误 - end SensorData 后缺少分号")));

        cases.add(new AblationCase("E02", "错误端口方向", "语法错误", "easy",
                buggyWrongPortDirection(), expectedWrongPortDirection(),
                Arrays.asList(
                    "第8行: 语法错误 - 非法的端口方向 'requires data port'，应改为 'in data port'",
                    "第9行: 语法错误 - 非法的端口方向 'provides data port'，应改为 'out data port'"
                )));

        cases.add(new AblationCase("E03", "缺少 end 语句", "语法错误", "easy",
                buggyMissingEnd(), expectedMissingEnd(),
                Arrays.asList("第12行: 语法错误 - thread implementation MyThread.impl 缺少 end 语句")));

        // ===== 中等 =====
        cases.add(new AblationCase("E04", "features 在 impl 中", "结构错误", "medium",
                buggyFeaturesInImpl(), expectedFeaturesInImpl(),
                Arrays.asList("第11行: 结构错误 - features 块不能出现在 thread implementation 中")));

        cases.add(new AblationCase("E05", "连接操作符错误", "结构错误", "medium",
                buggyConnectionOperator(), expectedConnectionOperator(),
                Arrays.asList("第23行: 连接错误 - data port 应使用单向操作符 ->")));

        cases.add(new AblationCase("E06", "未声明组件类型", "引用错误", "medium",
                buggyUndeclaredType(), expectedUndeclaredType(),
                Arrays.asList("第10行: 引用错误 - subcomponent 'actuator' 的类型 'ActuatorThread' 未声明")));

        // ===== 困难 =====
        cases.add(new AblationCase("E07", "多错误复合", "复合错误", "hard",
                buggyMultipleErrors(), expectedMultipleErrors(),
                Arrays.asList(
                    "第6行: 语法错误 - end PositionData 后缺少分号",
                    "第10行: 语法错误 - 非法的端口方向 'requires data port'",
                    "第16行: 语法错误 - thread implementation 缺少 end 语句"
                )));

        cases.add(new AblationCase("E08", "未定义 data 类型", "引用错误", "hard",
                buggyUndefinedData(), expectedUndefinedData(),
                Arrays.asList(
                    "第6行: 引用错误 - data 类型 'AirData' 未声明",
                    "第7行: 引用错误 - data 类型 'GpsData' 未声明"
                )));

        cases.add(new AblationCase("E09", "复杂架构多错误", "复合错误", "hard",
                buggyComplexArch(), expectedComplexArch(),
                Arrays.asList(
                    "第6行: 语法错误 - end PositionData 后缺少分号",
                    "第18行: 语法错误 - 非法的端口方向 'requires data port'",
                    "第31行: 语法错误 - end ActuatorThread 后缺少分号",
                    "第44行: 连接错误 - data port 连接应使用 -> 而非 <->"
                )));

        return cases;
    }

    // ==================== 用例代码 ====================

    private static String buggyMissingSemicolon() {
        return """
            package TestPkg
            public

            data SensorData
            end SensorData

            end TestPkg;
            """;
    }
    private static String expectedMissingSemicolon() {
        return """
            package TestPkg
            public

            data SensorData
            end SensorData;

            end TestPkg;
            """;
    }

    private static String buggyWrongPortDirection() {
        return """
            package TestPkg
            public

            data SensorData
            end SensorData;

            thread SensorThread
            features
                requires data port sensor_in: SensorData;
                provides data port sensor_out: SensorData;
            end SensorThread;

            end TestPkg;
            """;
    }
    private static String expectedWrongPortDirection() {
        return """
            package TestPkg
            public

            data SensorData
            end SensorData;

            thread SensorThread
            features
                in data port sensor_in: SensorData;
                out data port sensor_out: SensorData;
            end SensorThread;

            end TestPkg;
            """;
    }

    private static String buggyMissingEnd() {
        return """
            package TestPkg
            public

            thread MyThread
            features
                in data port sensor_in: SensorData;
            end MyThread;

            thread implementation MyThread.impl
            properties
                Dispatch_Protocol => Periodic;

            end TestPkg;
            """;
    }
    private static String expectedMissingEnd() {
        return """
            package TestPkg
            public

            thread MyThread
            features
                in data port sensor_in: SensorData;
            end MyThread;

            thread implementation MyThread.impl
            properties
                Dispatch_Protocol => Periodic;
            end MyThread.impl;

            end TestPkg;
            """;
    }

    private static String buggyFeaturesInImpl() {
        return """
            package TestPkg
            public

            data SensorData
            end SensorData;

            thread SensorThread
            end SensorThread;

            thread implementation SensorThread.impl
            features
                in data port sensor_in: SensorData;
                out data port sensor_out: SensorData;
            end SensorThread.impl;

            end TestPkg;
            """;
    }
    private static String expectedFeaturesInImpl() {
        return """
            package TestPkg
            public

            data SensorData
            end SensorData;

            thread SensorThread
            features
                in data port sensor_in: SensorData;
                out data port sensor_out: SensorData;
            end SensorThread;

            thread implementation SensorThread.impl
            end SensorThread.impl;

            end TestPkg;
            """;
    }

    private static String buggyConnectionOperator() {
        return """
            package TestPkg
            public

            data SensorData
            end SensorData;

            thread SensorThread
            features
                in data port in1: SensorData;
                out data port out1: SensorData;
            end SensorThread;

            process ControlProcess
            end ControlProcess;

            process implementation ControlProcess.impl
            subcomponents
                t1: thread SensorThread;
                t2: thread SensorThread;
            connections
                sensor_conn: data port t1.out1 <-> t2.in1;
            end ControlProcess.impl;

            end TestPkg;
            """;
    }
    private static String expectedConnectionOperator() {
        return """
            package TestPkg
            public

            data SensorData
            end SensorData;

            thread SensorThread
            features
                in data port in1: SensorData;
                out data port out1: SensorData;
            end SensorThread;

            process ControlProcess
            end ControlProcess;

            process implementation ControlProcess.impl
            subcomponents
                t1: thread SensorThread;
                t2: thread SensorThread;
            connections
                sensor_conn: data port t1.out1 -> t2.in1;
            end ControlProcess.impl;

            end TestPkg;
            """;
    }

    private static String buggyUndeclaredType() {
        return """
            package TestPkg
            public

            process ControlProcess
            end ControlProcess;

            process implementation ControlProcess.impl
            subcomponents
                sensor: thread SensorThread;
                actuator: thread ActuatorThread;
            end ControlProcess.impl;

            thread SensorThread
            end SensorThread;

            end TestPkg;
            """;
    }
    private static String expectedUndeclaredType() {
        return """
            package TestPkg
            public

            process ControlProcess
            end ControlProcess;

            process implementation ControlProcess.impl
            subcomponents
                sensor: thread SensorThread;
                actuator: thread ActuatorThread;
            end ControlProcess.impl;

            thread SensorThread
            end SensorThread;

            thread ActuatorThread
            end ActuatorThread;

            end TestPkg;
            """;
    }

    private static String buggyMultipleErrors() {
        return """
            package FlightSystem
            public

            data PositionData
            end PositionData

            thread NavigationThread
            features
                requires data port gps_in: PositionData;
                out data port nav_out: PositionData;
            end NavigationThread;

            thread implementation NavigationThread.impl
            properties
                Dispatch_Protocol => Periodic;
                Period => 50 ms;

            process FlightControl
            end FlightControl;

            process implementation FlightControl.impl
            subcomponents
                nav: thread NavigationThread;
            end FlightControl.impl;

            end FlightSystem;
            """;
    }
    private static String expectedMultipleErrors() {
        return """
            package FlightSystem
            public

            data PositionData
            end PositionData;

            thread NavigationThread
            features
                in data port gps_in: PositionData;
                out data port nav_out: PositionData;
            end NavigationThread;

            thread implementation NavigationThread.impl
            properties
                Dispatch_Protocol => Periodic;
                Period => 50 ms;
            end NavigationThread.impl;

            process FlightControl
            end FlightControl;

            process implementation FlightControl.impl
            subcomponents
                nav: thread NavigationThread;
            end FlightControl.impl;

            end FlightSystem;
            """;
    }

    private static String buggyUndefinedData() {
        return """
            package AvionicsPkg
            public

            thread SensorThread
            features
                in data port air_data: AirData;
                in data port gps_data: GpsData;
                out data port fused_data: FusedPosition;
            end SensorThread;

            process AvionicsProcess
            end AvionicsProcess;

            process implementation AvionicsProcess.impl
            subcomponents
                sensor: thread SensorThread;
            end AvionicsProcess.impl;

            end AvionicsPkg;
            """;
    }
    private static String expectedUndefinedData() {
        return """
            package AvionicsPkg
            public

            data AirData
            end AirData;

            data GpsData
            end GpsData;

            data FusedPosition
            end FusedPosition;

            thread SensorThread
            features
                in data port air_data: AirData;
                in data port gps_data: GpsData;
                out data port fused_data: FusedPosition;
            end SensorThread;

            process AvionicsProcess
            end AvionicsProcess;

            process implementation AvionicsProcess.impl
            subcomponents
                sensor: thread SensorThread;
            end AvionicsProcess.impl;

            end AvionicsPkg;
            """;
    }

    private static String buggyComplexArch() {
        return """
            package FlightControl_Arch
            public

            data PositionData
            end PositionData

            data SensorData
            end SensorData;

            data ControlCommand
            end ControlCommand;

            thread SensorAcquisitionThread
            features
                requires data port gps_in: PositionData;
                out data port sensor_out: SensorData;
            end SensorAcquisitionThread;

            thread ControlLawThread
            features
                in data port sensor_in: SensorData;
                out data port cmd_out: ControlCommand;
            end ControlLawThread;

            thread ActuatorThread
            features
                in data port cmd_in: ControlCommand;
            end ActuatorThread

            process FlightControlProcess
            end FlightControlProcess;

            process implementation FlightControlProcess.impl
            subcomponents
                sensor: thread SensorAcquisitionThread;
                control: thread ControlLawThread;
                actuator: thread ActuatorThread;
            connections
                sensor_to_control: data port sensor.sensor_out <-> control.sensor_in;
                control_to_actuator: data port control.cmd_out -> actuator.cmd_in;
            end FlightControlProcess.impl;

            end FlightControl_Arch;
            """;
    }
    private static String expectedComplexArch() {
        return """
            package FlightControl_Arch
            public

            data PositionData
            end PositionData;

            data SensorData
            end SensorData;

            data ControlCommand
            end ControlCommand;

            thread SensorAcquisitionThread
            features
                in data port gps_in: PositionData;
                out data port sensor_out: SensorData;
            end SensorAcquisitionThread;

            thread ControlLawThread
            features
                in data port sensor_in: SensorData;
                out data port cmd_out: ControlCommand;
            end ControlLawThread;

            thread ActuatorThread
            features
                in data port cmd_in: ControlCommand;
            end ActuatorThread;

            process FlightControlProcess
            end FlightControlProcess;

            process implementation FlightControlProcess.impl
            subcomponents
                sensor: thread SensorAcquisitionThread;
                control: thread ControlLawThread;
                actuator: thread ActuatorThread;
            connections
                sensor_to_control: data port sensor.sensor_out -> control.sensor_in;
                control_to_actuator: data port control.cmd_out -> actuator.cmd_in;
            end FlightControlProcess.impl;

            end FlightControl_Arch;
            """;
    }
}
