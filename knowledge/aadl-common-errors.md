# AADL 常见错误及修复方法

## 错误类型分类

### 1. 语法错误

#### 错误：缺少分号
**问题描述**：语句末尾缺少分号
```aadl
thread MyThread is
    features
        Port1: in data port  -- 缺少分号
    end MyThread
```

**修复方法**：在每个语句末尾添加分号
```aadl
thread MyThread is
    features
        Port1: in data port;
    end MyThread;
```

#### 错误：缺少 end 语句
**问题描述**：组件声明缺少对应的 end 语句
```aadl
package MyPackage
public
    thread MyThread is
        features
            Port1: in data port;
    -- 缺少 end MyThread;
end MyPackage;
```

**修复方法**：为每个组件添加正确的 end 语句
```aadl
package MyPackage
public
    thread MyThread is
        features
            Port1: in data port;
        end MyThread;
end MyPackage;
```

#### 错误：end 语句不匹配
**问题描述**：end 语句中的组件名称与声明不匹配
```aadl
package MyPackage
public
    thread SensorThread is
        features
            DataIn: in data port;
        end MyThread;  -- 错误：应为 end SensorThread;
end MyPackage;
```

**修复方法**：确保 end 语句中的名称与组件声明一致
```aadl
package MyPackage
public
    thread SensorThread is
        features
            DataIn: in data port;
        end SensorThread;
end MyPackage;
```

### 2. 结构错误

#### 错误：嵌套组件未正确闭合
**问题描述**：子组件或连接未正确闭合
```aadl
process MyProcess is
    subcomponents
        Thread1: thread MyThread;
        Thread2: thread MyThread;
    connections
        port Thread1.Output -> Thread2.Input;
    -- 缺少 end MyProcess;
```

**修复方法**：确保每个组件都有对应的 end 语句

#### 错误：连接语法错误
**问题描述**：连接语句格式不正确
```aadl
connections
    Thread1.Output -> Thread2.Input;  -- 缺少 port 关键字
```

**修复方法**：使用正确的连接语法
```aadl
connections
    port Thread1.Output -> Thread2.Input;
```

#### 错误：绑定语法错误
**问题描述**：组件绑定语句格式不正确
```aadl
connections
    Process1 -> Processor1;  -- 缺少 binding 关键字
```

**修复方法**：使用正确的绑定语法
```aadl
connections
    binding Process1 -> Processor1;
```

### 3. 语义错误

#### 错误：特性类型不匹配
**问题描述**：连接的端口类型不兼容
```aadl
thread Producer is
    features
        Output: out data port;
    end Producer;

thread Consumer is
    features
        Input: in event port;  -- 类型不匹配
    end Consumer;

connections
    port Producer.Output -> Consumer.Input;  -- 错误：data port 不能连接到 event port
```

**修复方法**：确保连接的端口类型一致
```aadl
thread Consumer is
    features
        Input: in data port;  -- 修改为 data port
    end Consumer;
```

#### 错误：组件未声明
**问题描述**：使用了未声明的组件类型
```aadl
process MyProcess is
    subcomponents
        Thread1: thread NonExistentThread;  -- 未声明的类型
    end MyProcess;
```

**修复方法**：先声明组件类型，再使用

#### 错误：端口未定义
**问题描述**：连接了未在特性中定义的端口
```aadl
thread MyThread is
    features
        Input: in data port;
    end MyThread;

connections
    port MyThread.NonexistentPort -> ...;  -- 端口不存在
```

**修复方法**：确保连接的端口已在组件特性中定义

### 4. 格式错误

#### 错误：包声明缺失
**问题描述**：文件缺少 package 声明
```aadl
-- 缺少 package 声明
thread MyThread is
    features
        Port1: in data port;
    end MyThread;
```

**修复方法**：添加正确的包声明
```aadl
package MyPackage
public
    thread MyThread is
        features
            Port1: in data port;
        end MyThread;
end MyPackage;
```

#### 错误：公共区域缺失
**问题描述**：缺少 public 关键字
```aadl
package MyPackage
    -- 缺少 public
    thread MyThread is
        features
            Port1: in data port;
        end MyThread;
end MyPackage;
```

**修复方法**：添加 public 关键字
```aadl
package MyPackage
public
    thread MyThread is
        features
            Port1: in data port;
        end MyThread;
end MyPackage;
```

## 验证检查清单

在生成 AADL 后，检查以下项目：

1. [ ] 每个组件声明都有对应的 `end 组件名;`
2. [ ] 包末尾有 `end 包名;`
3. [ ] 每个语句都以分号结尾
4. [ ] 连接语句使用了正确的关键字（port, event port, binding）
5. [ ] 端口类型在连接两端匹配
6. [ ] 使用的组件类型已声明
7. [ ] 引用的端口已在组件特性中定义
8. [ ] 组件名称拼写一致