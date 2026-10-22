# AADL GBNF 受限解码 — 云端部署与实验指南

## 一、核心概念

### 什么是 GBNF 受限解码？

GBNF（GGML BNF）是 llama.cpp 内置的**语法约束解码**机制：

```
模型生成每个 token 前:
  1. 根据 GBNF 语法规则，计算当前状态下所有合法的下一个 token
  2. 将非法 token 的 logit 设为 -inf（概率为0）
  3. 模型只能从合法 token 集合中采样
  4. 输出 100% 符合语法定义
```

**关键点：**
- ✅ 这是 token 级别的约束，不是后处理
- ✅ 适用于任何 GGUF 格式模型（和模型架构无关）
- ✅ 约束在推理时实时计算，不需要预训练
- ✅ 语法可定制（用 BNF 范式描述）

### 和模型的关系

> **不是只能用于 qwen3:8b！**

GBNF 是 llama.cpp 推理框架的功能，只要模型是 GGUF 格式就能用。支持的模型包括但不限于：
- Qwen 系列（qwen2, qwen3, qwen2.5-coder 等）
- Llama 系列（llama2, llama3, llama3.1 等）
- Mistral / Mixtral 系列
- DeepSeek 系列
- CodeLlama / StarCoder 等代码模型

**唯一要求：GGUF 格式 + llama.cpp 推理**

---

## 二、文件清单

```
constrained_decoding/
├── grammars/
│   └── aadl_core.gbnf          # AADL 核心 GBNF 语法（主要用这个）
│   └── aadl.gbnf               # AADL 完整 GBNF 语法（更详细，可选）
├── aadl_gbnf_demo.py           # 演示/实验脚本（命令行交互式）
├── aadl_gbnf_server.py         # HTTP 服务（供 Java 调用）
└── README.md                   # 本文档
```

### 核心文件说明

| 文件 | 用途 |
|------|------|
| `aadl_core.gbnf` | AADL 核心语法，覆盖 80% 常用场景，建议先用这个 |
| `aadl_gbnf_demo.py` | 做实验用，跑有约束 vs 无约束对比 |
| `aadl_gbnf_server.py` | 部署成服务，供 Java/Spring Boot 调用 |

---

## 三、快速开始（云端实验）

### 3.1 环境准备

```bash
# 1. 安装 llama-cpp-python（CUDA 12.1 版本）
pip install llama-cpp-python flask \
  --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu121

# 2. 下载 GGUF 模型（示例：qwen3-8b）
# 从 HuggingFace 或 ModelScope 下载 .gguf 文件
# 例如：https://huggingface.co/Qwen/Qwen3-8B-GGUF

# 3. 验证安装
python -c "from llama_cpp import Llama; print('OK')"
```

### 3.2 单次生成测试

```bash
python aadl_gbnf_demo.py \
  --model /path/to/qwen3-8b.gguf \
  --prompt "生成一个简单的 AADL 包，包含一个系统组件"
```

### 3.3 对比实验（有约束 vs 无约束）

```bash
python aadl_gbnf_demo.py \
  --model /path/to/qwen3-8b.gguf \
  --experiment
```

输出示例：
```
======================================================================
  实验结果汇总
======================================================================
  实验名称              模式           语法分   错误数  速度(tok/s)    Token数
  -------------------- ------------ -------- -------- ------------ ----------
  简单系统              无约束           75.0        3         28.5        312
                        GBNF约束        100.0        0         25.2        298
                        改进               +100.0%
```

---

## 四、部署为 HTTP 服务

### 4.1 启动服务

```bash
python aadl_gbnf_server.py \
  --model /path/to/model.gguf \
  --grammar grammars/aadl_core.gbnf \
  --host 0.0.0.0 \
  --port 8082 \
  --n-gpu-layers -1
```

### 4.2 服务 API

#### 健康检查
```bash
curl http://localhost:8082/health
```

#### GBNF 约束生成
```bash
curl -X POST http://localhost:8082/api/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "生成一个AADL包...",
    "max_tokens": 2048,
    "temperature": 0.1,
    "grammar_root": "root"
  }'
```

#### 无约束生成（baseline）
```bash
curl -X POST http://localhost:8082/api/generate/baseline \
  -H "Content-Type: application/json" \
  -d '{"prompt": "...", "max_tokens": 2048}'
```

#### 语法验证
```bash
curl -X POST http://localhost:8082/api/validate \
  -H "Content-Type: application/json" \
  -d '{"code": "package Test..."}'
```

### 4.3 Docker 部署（可选）

```dockerfile
FROM nvidia/cuda:12.1.0-runtime-ubuntu22.04

RUN apt-get update && apt-get install -y python3 python3-pip

RUN pip3 install llama-cpp-python flask \
  --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu121

WORKDIR /app
COPY . /app/

EXPOSE 8082
CMD ["python3", "aadl_gbnf_server.py", "--model", "/models/model.gguf", "--host", "0.0.0.0"]
```

---

## 五、Java 端集成

### 5.1 配置

在 `application.yml` 中：

```yaml
gbnf:
  enabled: true                    # 启用语法质检
  server:
    base-url: http://gbnf-server:8082  # Python 服务地址
    timeout-seconds: 300           # 超时时间
```

### 5.2 使用方式

Java 端提供两种使用模式：

#### 模式一：后置语法质检（推荐先用）
生成完成后，用本地验证器快速检查语法，尝试简单自动修复。

```java
// 已自动集成到 AadlGeneratorAgent 中
// 只需设置 gbnf.enabled: true
```

#### 模式二：直接调用 GBNF 约束生成
通过服务直接生成语法正确的代码。

```java
@Autowired
private GbnfDecodingManager gbnfManager;

public void generateWithConstraint(String prompt) {
    if (gbnfManager.isGbnfAvailable()) {
        GbnfDecodingResult result = gbnfManager.constrainedGenerate(
            GbnfDecodingRequest.builder()
                .prompt(prompt)
                .maxTokens(2048)
                .temperature(0.1)
                .build()
        );
        // result.getText() 是语法保证正确的 AADL 代码
    }
}
```

### 5.3 Java 类说明

| 类 | 职责 |
|----|------|
| `GbnfDecodingManager` | 管理器/门面，统一入口 |
| `GbnfConstrainedDecodingService` | 服务接口 |
| `GbnfConstrainedDecodingServiceImpl` | HTTP 客户端实现 |
| `AadlBnfValidator` | 本地语法验证器（快速检查） |
| `GbnfDecodingRequest` | 请求 DTO |
| `GbnfDecodingResult` | 结果 DTO |
| `GrammarValidationResult` | 语法验证结果 |

---

## 六、GBNF 语法定制

### 6.1 语法规则说明

GBNF 是 BNF 的扩展，语法示例：

```gbnf
# 规则定义
rule-name ::= "固定字符串" [字符类] (可选组)* (重复组)+

# 注释用 # 开头
# 根规则默认叫 root
```

常用语法元素：
- `"text"` — 固定字符串
- `[a-z]` — 字符类（支持范围）
- `\|` — 或（alternation）
- `()` — 分组
- `()?` — 可选
- `()*` — 零次或多次
- `()+` — 一次或多次
- `WS` — 空白（需自定义）

### 6.2 扩展 AADL 语法

编辑 `grammars/aadl_core.gbnf`，添加你需要的规则。例如增加新的组件类型：

```gbnf
component-category ::=
    "abstract"
    | "bus"
    | "data"
    | "my-new-component-type"   # 新增
    ...
```

### 6.3 语法调试技巧

1. **从简单开始**：先用最小语法测试，逐步增加规则
2. **检查生成是否被卡死**：如果模型很快停止，可能是语法约束太死
3. **宽松标识符**：标识符规则不要太严格，让模型有自由度
4. **空白处理**：WS 规则要在适当位置加上，否则会约束失败
5. **验证语法**：llama-cpp-python 加载语法时如果报错，会给出具体位置

---

## 七、性能优化建议

### 7.1 速度优化

| 方法 | 效果 | 说明 |
|------|------|------|
| 增大 batch size | ⭐⭐ | 影响不大，因为是增量生成 |
| 使用小模型 | ⭐⭐⭐ | 7B 比 13B 快很多，语法约束不依赖模型大小 |
| 降低 max_tokens | ⭐⭐ | 只生成需要的长度 |
| 启用 GPU | ⭐⭐⭐⭐⭐ | 必须的，CPU 跑 GBNF 很慢 |

### 7.2 质量优化

| 方法 | 效果 | 说明 |
|------|------|------|
| 降低 temperature | ⭐⭐⭐ | 0.1 左右适合结构化输出 |
| 提供更多上下文 | ⭐⭐⭐ | 详细的 prompt 能提高语义正确性 |
| 语法不要过度约束 | ⭐⭐⭐ | 太严格的语法会导致生成质量下降 |
| 从 package 级开始 | ⭐⭐ | 从根规则开始，模型有整体结构感知 |

---

## 八、常见问题

### Q1: GBNF 约束会降低生成质量吗？

**不会明显降低。** 语法约束只限制"能生成什么 token"，不限制"生成哪个合法 token"。模型的语义能力仍然发挥作用，只是输出被框在语法框架内。

但如果语法约束过于严格（比如限制了标识符的字符范围太小），可能导致模型"表达受限"。建议语法约束聚焦在**结构层面**，把标识符名称等留给模型自由发挥。

### Q2: 为什么不直接用 Ollama 的 grammar 参数？

Ollama 截至目前（2025）**不支持 grammar 参数**。Ollama 内部虽然用 llama.cpp，但 API 层没有暴露 grammar 接口。

如果你看到网上说 Ollama 支持 grammar，那可能是：
1. 旧版文档/传言
2. 特定版本的 fork
3. 混淆了 format 参数（只支持 JSON 格式约束）

所以要做 GBNF 约束，必须直接用 llama-cpp-python 或 llama.cpp 原生。

### Q3: GBNF 和 JSON mode 有什么区别？

| 特性 | JSON mode | GBNF |
|------|-----------|------|
| 约束范围 | 只能输出 JSON | 任意语法 |
| 灵活性 | 低（JSON Schema 子集） | 高（任意 BNF） |
| 适用场景 | 结构化数据输出 | 编程语言、DSL 等 |
| AADL 支持 | ❌ 不支持 | ✅ 完全支持 |

### Q4: 一个 GPU 能同时跑 Ollama 和 GBNF 服务吗？

**不建议。** 两者都要加载模型，会占两份显存。

推荐方案：
- 开发/实验阶段：用 llama-cpp-python 直接跑 GBNF
- 生产环境：部署独立的 GBNF 服务实例，Java 端通过 HTTP 调用
- 多任务场景：用同一个 llama-cpp-python 实例，既做约束生成也做普通生成

### Q5: 生成速度比无约束慢多少？

通常慢 **10%-30%**，取决于：
- 语法复杂度（规则越多越慢）
- 词汇表大小
- 模型大小

对于 AADL 这种规模的语法，速度损失通常在 15% 以内。

---

## 九、实验设计建议

### 对比实验模板

1. **准备测试用例**：5-10 个不同复杂度的 AADL 生成任务
2. **每个任务跑 3 次**：取平均（消除随机性）
3. **对比指标**：
   - 语法正确率（语法错误数 / 100分制）
   - 生成速度（token/s）
   - 生成 token 数
   - 语义正确性（人工评估或用更强的模型评估）
4. **变量控制**：相同模型、相同温度、相同 max_tokens

### 进阶实验方向

1. **不同模型对比**：Qwen vs Llama vs DeepSeek 对 GBNF 的适应度
2. **温度影响**：temperature 从 0 到 1.0，语法质量变化曲线
3. **语法粒度**：粗粒度语法 vs 细粒度语法的质量/速度权衡
4. **增量生成**：逐块生成 + 逐块验证 vs 一次性生成

---

## 十、相关资源

- [llama-cpp-python 文档](https://llama-cpp-python.readthedocs.io/)
- [llama.cpp GBNF 语法说明](https://github.com/ggerganov/llama.cpp/blob/master/grammars/README.md)
- [AADL 标准 AS5506C](https://www.sae.org/standards/content/as5506c/)
- [GBNF 语法示例集合](https://github.com/ggerganov/llama.cpp/tree/master/grammars)
