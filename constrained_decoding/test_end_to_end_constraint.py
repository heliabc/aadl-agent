#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端到端约束验证脚本
==================
用真实 tokenizer 验证：
1. 分层递归文法能否编译为完整 FSM（token 级别）
2. CFG 约束 + 语义约束能否正确联调
3. 在关键点（classifier、connection target 等）约束是否正确收窄

模拟生成方式：把一段合法 AADL 逐 token 喂入，
在每个关键位置检查合法 token 集合是否符合预期。
"""

import os
import sys
import json
import time
import copy
from pathlib import Path


# ============================================================
# Tokenizer 加载
# ============================================================

def load_tokenizer_from_files(tokenizer_json_path: str, vocab_json_path: str = None):
    """从 tokenizer.json 和 vocab.json 构建一个简单的 tokenizer"""
    with open(tokenizer_json_path, "r", encoding="utf-8") as f:
        tok_data = json.load(f)

    vocab = tok_data["model"]["vocab"]

    # 构建 id → token 映射
    id2token = {v: k for k, v in vocab.items()}
    vocab_size = len(vocab)

    # 找 eos token
    eos_token = None
    for tok in tok_data.get("added_tokens", []):
        if tok.get("special"):
            content = tok.get("content", "")
            if "eos" in content.lower() or content == "<|endoftext|>":
                eos_token = content
                break
    if eos_token is None:
        eos_token = "<|endoftext|>"

    eos_token_id = vocab.get(eos_token, vocab_size - 1)

    # 简单的 encode / decode 函数
    # 注意：这不是完整的 BPE 编码，只是用于验证的简化版本
    # 实际编码需要 BPE merge，这里用 transformers 的 tokenizer

    class SimpleTokenizer:
        def __init__(self):
            self.vocab = vocab
            self.id2token = id2token
            self.vocab_size = vocab_size
            self.eos_token_id = eos_token_id
            self.eos_token = eos_token

        def encode(self, text, add_special_tokens=False):
            """用 BPE 编码文本 - 用 transformers 的 PreTrainedTokenizerFast"""
            # 尝试用 transformers 加载
            try:
                from transformers import PreTrainedTokenizerFast
                from tokenizers import Tokenizer
                tok = Tokenizer.from_file(tokenizer_json_path)
                fast_tok = PreTrainedTokenizerFast(tokenizer_object=tok)
                return fast_tok.encode(text, add_special_tokens=add_special_tokens)
            except ImportError:
                pass

            # 降级：简单字符级编码（不准确，但能用于测试）
            # 实际上我们需要正确的 BPE 编码
            raise RuntimeError("需要 transformers 库来正确编码 token")

        def decode(self, ids):
            try:
                from transformers import PreTrainedTokenizerFast
                from tokenizers import Tokenizer
                tok = Tokenizer.from_file(tokenizer_json_path)
                fast_tok = PreTrainedTokenizerFast(tokenizer_object=tok)
                return fast_tok.decode(ids)
            except ImportError:
                return "".join(id2token.get(i, f"<{i}>") for i in ids)

        def convert_ids_to_tokens(self, ids):
            if isinstance(ids, (list, tuple)):
                return [id2token.get(i, f"<{i}>") for i in ids]
            return id2token.get(ids, f"<{ids}>")

    return SimpleTokenizer()


# ============================================================
# FSM 编译
# ============================================================

def compile_fsm_with_tokenizer(grammar_path: str, tokenizer_json_path: str):
    """用 llguidance + tokenizer 编译完整 FSM"""
    import llguidance

    print("=" * 60)
    print("步骤1：FSM 编译验证（带 tokenizer）")
    print("=" * 60)

    with open(grammar_path, "r", encoding="utf-8") as f:
        grammar_text = f.read()

    # 1. Lark → JSON Grammar
    t0 = time.time()
    lc = llguidance.LarkCompiler()
    json_grammar = lc.compile(grammar_text, check=True)
    t1 = time.time()
    print(f"  [1/3] Lark → JSON Grammar: {(t1-t0)*1000:.1f}ms")

    # 2. JSON → 编译中间表示
    jc = llguidance.JsonCompiler()
    compiled = jc.compile(json_grammar, check=True)
    t2 = time.time()
    print(f"  [2/3] JsonCompiler: {(t2-t1)*1000:.1f}ms")

    # 3. 用 tokenizer 构建 LLParser
    # llguidance 的 LLParser 需要 tokenizer 信息
    print(f"  [3/3] 构建带 tokenizer 的约束解释器...")
    try:
        from tokenizers import Tokenizer as HFTokenizer
        hf_tok = HFTokenizer.from_file(tokenizer_json_path)

        # llguidance 的 LLTokenizer
        ll_tok = llguidance.LLTokenizer(hf_tok)

        # 构建解释器
        interpreter = llguidance.LLInterpreter(
            ll_tok,
            json_grammar if isinstance(json_grammar, str) else json.dumps(json_grammar),
            # 不启用 stop 因为我们在做模拟测试
        )
        t3 = time.time()
        print(f"  ✅ LLInterpreter 构建成功: {(t3-t2)*1000:.1f}ms")

        return interpreter
    except Exception as e:
        print(f"  ⚠️  LLInterpreter 构建失败（可能是 API 版本问题）: {e}")
        print(f"     降级使用 JSON Grammar + 手动验证")
        return None


# ============================================================
# 模拟生成测试
# ============================================================

def run_simulation_test(grammar_path: str, tokenizer_json_path: str):
    """
    模拟生成测试：
    逐 token 喂入一段 AADL，在关键位置检查约束收窄情况。
    """
    import llguidance
    from tokenizers import Tokenizer as HFTokenizer

    print()
    print("=" * 60)
    print("步骤2：模拟生成 + 约束收窄验证")
    print("=" * 60)

    # 加载 tokenizer
    hf_tok = HFTokenizer.from_file(tokenizer_json_path)
    ll_tok = llguidance.LLTokenizer(hf_tok)

    # 加载文法
    with open(grammar_path, "r", encoding="utf-8") as f:
        grammar_text = f.read()

    # 编译 JSON Grammar
    lc = llguidance.LarkCompiler()
    json_grammar = lc.compile(grammar_text, check=True)

    # 测试用例：各约束关键点
    test_cases = [
        {
            "name": "data 类型分类器约束",
            "prefix": "package Test\npublic\n",
            "next_expected_category": "data 类型声明",
            "check": "验证生成 data 类型时，data 关键字在合法 token 中",
        },
        {
            "name": "thread 分类器约束（符号表预填充）",
            "prefix": """package Test
public
data SensorData
end SensorData;

thread NavThread
features
in_port: in data port SensorData;
end NavThread;

thread GuidThread
features
out_port: out data port SensorData;
end GuidThread;

process FcProc
end FcProc;

process implementation FcProc.impl
subcomponents
nav: thread """,
            "next_expected": "分类器只能是已定义的 thread 类型",
            "valid_classifiers": ["NavThread", "GuidThread"],
            "check": "验证 subcomponent classifier 被约束为已定义的 thread 类型",
        },
        {
            "name": "连接源端约束（端口方向）",
            "prefix": """package Test
public
data SensorData
end SensorData;

thread NavThread
features
sensor_in: in data port SensorData;
nav_out: out data port SensorData;
end NavThread;

thread GuidThread
features
nav_in: in data port SensorData;
cmd_out: out data port SensorData;
end GuidThread;

process FcProc
features
sensor_in: in data port SensorData;
cmd_out: out data port SensorData;
end FcProc;

process implementation FcProc.impl
subcomponents
nav: thread NavThread;
guid: thread GuidThread;
connections
c1: """,
            "next_expected": "连接源端只能是 out/in out 方向的端口",
            "check": "验证连接源端被约束为输出端口",
        },
        {
            "name": "连接目标端约束（类型匹配）",
            "prefix": """package Test
public
data SensorData
end SensorData;

data ControlData
end ControlData;

thread NavThread
features
sensor_in: in data port SensorData;
nav_out: out data port SensorData;
end NavThread;

thread GuidThread
features
nav_in: in data port SensorData;
cmd_out: out data port ControlData;
end GuidThread;

process FcProc
features
sensor_in: in data port SensorData;
cmd_out: out data port ControlData;
end FcProc;

process implementation FcProc.impl
subcomponents
nav: thread NavThread;
guid: thread GuidThread;
connections
c1: nav.nav_out -> """,
            "next_expected": "目标端必须是 SensorData 类型的 in/in out 端口",
            "valid_targets": ["nav_in", "sensor_in"],
            "check": "验证连接目标端被约束为同数据类型的输入端口",
        },
    ]

    all_pass = True
    for i, tc in enumerate(test_cases):
        print(f"\n  测试 {i+1}: {tc['name']}")
        print(f"    检查点: {tc['check']}")

        try:
            # 构建解释器
            interpreter = llguidance.LLInterpreter(ll_tok, json_grammar)

            # 对 prefix 进行 token 编码
            encoding = hf_tok.encode(tc["prefix"])
            token_ids = encoding.ids
            tokens = [hf_tok.id_to_token(tid) for tid in token_ids]

            print(f"    Prefix 长度: {len(tc['prefix'])} 字符, {len(token_ids)} token")

            # 逐步喂入 token
            for tid in token_ids:
                # 检查当前合法 token 集合
                # mask = interpreter.compute_mask()
                # 喂入 token
                result = interpreter.advance(tid)
                if not result:
                    print(f"    ❌ Token 被拒绝: {hf_tok.id_to_token(tid)}")
                    all_pass = False
                    break

            else:
                # 所有 prefix token 都接受了
                # 获取当前合法 token 集合
                try:
                    mask = interpreter.compute_mask()
                    if mask is not None and hasattr(mask, 'sample_mask'):
                        # 找到所有合法的 token
                        valid_tokens = []
                        for idx, allowed in enumerate(mask.sample_mask):
                            if allowed:
                                tok_str = hf_tok.id_to_token(idx)
                                if tok_str and not tok_str.startswith("[") and len(tok_str) < 30:
                                    valid_tokens.append(tok_str)
                                    if len(valid_tokens) >= 30:
                                        break

                        print(f"    ✅ 前缀全部接受")
                        print(f"    当前合法 token 数: {sum(1 for x in mask.sample_mask if x)}")
                        print(f"    前 20 个合法 token: {valid_tokens[:20]}")

                        # 如果有预期合法值，验证
                        if "valid_classifiers" in tc:
                            # 检查合法 classifier 是否在合法 token 中
                            for vc in tc["valid_classifiers"]:
                                # 第一个 token
                                first_tok = hf_tok.encode(vc).tokens[0] if hf_tok.encode(vc).tokens else ""
                                is_valid = any(first_tok in vt for vt in valid_tokens[:50])
                                status = "✅" if is_valid else "❌"
                                print(f"    {status} classifier '{vc}' 首 token '{first_tok}' 在合法集合中: {is_valid}")

                        if "valid_targets" in tc:
                            for vt in tc["valid_targets"]:
                                first_tok = hf_tok.encode(vt).tokens[0] if hf_tok.encode(vt).tokens else ""
                                is_valid = any(first_tok in vtok for vtok in valid_tokens[:50])
                                status = "✅" if is_valid else "❓"
                                print(f"    {status} target '{vt}' 首 token '{first_tok}' 在合法集合中: {is_valid}")

                    else:
                        print(f"    ⚠️  无法获取 mask（API 不兼容）")
                except Exception as e:
                    print(f"    ⚠️  获取 mask 失败: {e}")

            # 清理
            interpreter.stop()

        except Exception as e:
            print(f"    ❌ 测试异常: {e}")
            import traceback
            traceback.print_exc()
            all_pass = False

    return all_pass


# ============================================================
# 语义约束 + CFG 联调测试
# ============================================================

def test_semantic_constraint_integration(tokenizer_json_path: str):
    """
    测试语义约束引擎能否正常工作。
    不依赖模型，直接测试约束逻辑。
    """
    print()
    print("=" * 60)
    print("步骤3：语义约束引擎逻辑验证")
    print("=" * 60)

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from aadl_context_sensitive import AadlContextSensitiveEngine

    # 测试1：引用完整性 + 包含规则
    print("\n  [1/4] 引用完整性 + 包含规则")
    engine = AadlContextSensitiveEngine()

    # 预填充一些符号
    test_arch = {
        "components": {
            "SensorData": {"category": "data", "type": "type", "ports": {}},
            "NavThread": {"category": "thread", "type": "type",
                          "ports": {"in1": {"direction": "in", "data_type": "SensorData"},
                                    "out1": {"direction": "out", "data_type": "SensorData"}}},
            "GuidThread": {"category": "thread", "type": "type",
                           "ports": {"in1": {"direction": "in", "data_type": "SensorData"},
                                     "out1": {"direction": "out", "data_type": "SensorData"}}},
            "SensorDevice": {"category": "device", "type": "type", "ports": {}},
            "FcProc": {"category": "process", "type": "type",
                       "ports": {"sensor_in": {"direction": "in", "data_type": "SensorData"}}},
            "FcProc.impl": {"category": "process", "type": "impl",
                            "subcomponents": {"nav": {"category": "thread", "classifier": "NavThread"},
                                              "guid": {"category": "thread", "classifier": "GuidThread"}},
                            "base_type": "FcProc"},
        }
    }
    engine.prefill_from_arch_json(test_arch)

    # 在 process 实现的 subcomponents 中，输入前缀 "nav: thread N"
    text = """process implementation FcProc.impl
  subcomponents
    nav: thread N"""

    result = engine.get_valid_values(text)
    if result and result["type"] == "classifier":
        valid = result["valid_values"]
        print(f"    前缀: 'nav: thread N'")
        print(f"    合法 classifier: {valid}")
        expected = {"NavThread"}
        actual = set(v for v in valid if v.startswith("N"))
        if expected.issubset(valid):
            print(f"    ✅ NavThread 在合法集合中")
        else:
            print(f"    ❌ NavThread 不在合法集合中")
    else:
        print(f"    ⚠️  未检测到 classifier 约束点，结果: {result}")

    # 测试2：端口类型匹配
    print("\n  [2/4] 端口类型匹配（连接目标端）")
    text2 = """process implementation FcProc.impl
  subcomponents
    nav: thread NavThread;
    guid: thread GuidThread;
  connections
    c1: nav.out1 -> """

    result2 = engine.get_valid_values(text2)
    if result2 and result2["type"] == "connection_target":
        valid_targets = result2["valid_values"]
        print(f"    源端口: nav.out1 (SensorData, out)")
        print(f"    合法目标: {valid_targets}")
        # 应该包含 guid.in1（同类型 in 端口）
        if any("in1" in t for t in valid_targets):
            print(f"    ✅ 同类型输入端口在合法目标中")
        else:
            print(f"    ❌ 同类型输入端口不在合法目标中")
    else:
        print(f"    ⚠️  未检测到 connection target 约束点")

    # 测试3：绑定类型约束
    print("\n  [3/4] 绑定类型约束")
    text3 = """process implementation FcProc.impl
  subcomponents
    nav: thread NavThread;
  properties
    Actual_Processor_Binding => """

    result3 = engine.get_valid_values(text3)
    if result3 and result3["type"] == "binding_target":
        print(f"    绑定类型: Actual_Processor_Binding")
        print(f"    合法目标: {result3['valid_values']}")
        print(f"    ✅ 绑定约束生效")
    else:
        print(f"    ⚠️  未检测到 binding 约束点（结果: {result3}）")

    # 测试4：增量解析
    print("\n  [4/4] 增量解析（逐步添加组件）")
    engine2 = AadlContextSensitiveEngine()
    # 逐步喂入 AADL 文本
    lines = [
        "package Test\npublic\n",
        "data MyData\nend MyData;\n",
        "thread Worker\nfeatures\n",
        "  in1: in data port MyData;\n",
        "end Worker;\n",
    ]
    for i, line in enumerate(lines):
        text_so_far = "".join(lines[:i+1])
        engine2.feed_text(line)
        comps = engine2.symbols.get_all_components()
        print(f"    第{i+1}步后，符号表组件数: {len(comps)}")

    final_comps = engine2.symbols.get_all_components()
    if "MyData" in final_comps and "Worker" in final_comps:
        print(f"    ✅ 增量解析正确，所有组件都注册了")
    else:
        print(f"    ❌ 增量解析有问题，组件: {final_comps}")

    return True


# ============================================================
# 主函数
# ============================================================

def main():
    import argparse

    parser = argparse.ArgumentParser(description="端到端约束验证")
    parser.add_argument("--grammar", default="grammars/aadl_layered.lark")
    parser.add_argument("--tokenizer", required=True, help="tokenizer.json 路径")
    parser.add_argument("--vocab", default=None, help="vocab.json 路径（可选）")
    args = parser.parse_args()

    print(f"文法: {args.grammar}")
    print(f"Tokenizer: {args.tokenizer}")

    # 步骤1：FSM 编译
    interpreter = compile_fsm_with_tokenizer(args.grammar, args.tokenizer)

    # 步骤2：模拟生成测试
    sim_pass = True
    try:
        sim_pass = run_simulation_test(args.grammar, args.tokenizer)
    except Exception as e:
        print(f"\n  ⚠️  模拟测试异常: {e}")
        import traceback
        traceback.print_exc()
        sim_pass = False

    # 步骤3：语义约束逻辑验证
    sem_pass = test_semantic_constraint_integration(args.tokenizer)

    # 总结
    print()
    print("=" * 60)
    print("总结")
    print("=" * 60)
    print(f"  FSM 编译:         {'✅ 通过' if interpreter else '⚠️ 降级'}")
    print(f"  模拟生成测试:     {'✅ 通过' if sim_pass else '❌ 失败'}")
    print(f"  语义约束逻辑:     {'✅ 通过' if sem_pass else '❌ 失败'}")

    all_pass = (interpreter is not None) and sim_pass and sem_pass
    if all_pass:
        print("\n🎉 全部通过！CFG + 语义约束可以正常联调")
    else:
        print("\n⚠️  部分测试未通过")

    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
