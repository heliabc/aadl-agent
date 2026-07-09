# AADL 示例集合

## 示例 1：简单的传感器系统

```aadl
package Sensor_System
public
    data SensorData is
        properties
            Data_Size => 128 bytes;
        end SensorData;
    
    thread SensorReader is
        features
            Read: in event port;
            DataOut: out data port SensorData;
        end SensorReader;
    
    thread DataProcessor is
        features
            DataIn: in data port SensorData;
            ResultOut: out data port;
        end DataProcessor;
    
    process MonitoringProcess is
        subcomponents
            Reader: thread SensorReader;
            Processor: thread DataProcessor;
        connections
            port Reader.DataOut -> Processor.DataIn;
        end MonitoringProcess;
    
    processor MCU is
        properties
            Execution_Speed => 80 MHz;
        end MCU;
    
    device SensorDevice is
        features
            Trigger: out event port;
        end SensorDevice;
    
    system MonitoringSystem is
        subcomponents
            Process: process MonitoringProcess;
            CPU: processor MCU;
            Sensor: device SensorDevice;
        connections
            binding Process -> CPU;
            event port Sensor.Trigger -> Process.Reader.Read;
        end MonitoringSystem;
end Sensor_System;
```

## 示例 2：实时操作系统架构

```aadl
package RTOS_Architecture
public
    data Message is
        properties
            Data_Size => 256 bytes;
        end Message;
    
    thread Task_A is
        features
            Input: in data port Message;
            Output: out data port Message;
        properties
            Execution_Time => 5 ms;
            Period => 100 ms;
        end Task_A;
    
    thread Task_B is
        features
            Input: in data port Message;
            Output: out data port Message;
        properties
            Execution_Time => 8 ms;
            Period => 200 ms;
        end Task_B;
    
    thread Task_C is
        features
            Input: in data port Message;
        properties
            Execution_Time => 12 ms;
            Period => 500 ms;
        end Task_C;
    
    process Kernel is
        subcomponents
            Thread1: thread Task_A;
            Thread2: thread Task_B;
            Thread3: thread Task_C;
        connections
            port Thread1.Output -> Thread2.Input;
            port Thread2.Output -> Thread3.Input;
        end Kernel;
    
    processor ARM_Cortex_M4 is
        properties
            Execution_Speed => 168 MHz;
            Cache_Size => 64 KB;
        end ARM_Cortex_M4;
    
    memory RAM is
        properties
            Memory_Size => 512 KB;
            Access_Time => 10 ns;
        end RAM;
    
    memory Flash is
        properties
            Memory_Size => 2 MB;
            Access_Time => 100 ns;
        end Flash;
    
    system RTOS_System is
        subcomponents
            KernelProcess: process Kernel;
            MainProcessor: processor ARM_Cortex_M4;
            MainMemory: memory RAM;
            ProgramMemory: memory Flash;
        connections
            binding KernelProcess -> MainProcessor;
            binding KernelProcess -> MainMemory;
            binding KernelProcess -> ProgramMemory;
        end RTOS_System;
end RTOS_Architecture;
```

## 示例 3：通信系统

```aadl
package Communication_System
public
    data NetworkPacket is
        properties
            Data_Size => 1024 bytes;
        end NetworkPacket;
    
    thread Transmitter is
        features
            DataIn: in data port NetworkPacket;
            Send: out event port;
        end Transmitter;
    
    thread Receiver is
        features
            Receive: in event port;
            DataOut: out data port NetworkPacket;
        end Receiver;
    
    bus Ethernet is
        properties
            Bandwidth => 100 Mbps;
        end Ethernet;
    
    device NetworkInterface is
        features
            Tx: in event port;
            Rx: out event port;
        end NetworkInterface;
    
    system CommunicationNode is
        subcomponents
            TxThread: thread Transmitter;
            RxThread: thread Receiver;
            NIC: device NetworkInterface;
            Bus: bus Ethernet;
        connections
            event port TxThread.Send -> NIC.Tx;
            event port NIC.Rx -> RxThread.Receive;
            binding NIC -> Bus;
        end CommunicationNode;
end Communication_System;
```

## 示例 4：带实现的组件

```aadl
package Component_Implementation
public
    thread DataConsumer is
        features
            Input: in data port;
            Output: out data port;
        end DataConsumer;
    
    thread DataConsumer implementation simple is
        subcomponents
            Handler: subprogram DataHandler;
        connections
            port self.Input -> Handler.Input;
            port Handler.Output -> self.Output;
        end simple;
    
    subprogram DataHandler is
        features
            Input: in data port;
            Output: out data port;
        end DataHandler;
    
    subprogram DataHandler implementation default is
        end default;
end Component_Implementation;
```