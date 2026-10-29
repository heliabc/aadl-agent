"""验证 llguidance 对生成代码的接受度"""
import sys
sys.path.insert(0, '/root/sj-tmp/attribute_grammar')

code = """package FlightControlSystem is
public
  system FlightControlSystem
    features
      input_port : in out data port oftypeReal;
      output_port : in out data port oftypeReal;
      control_algorithm : in out data port oftypeReal;
    end features;
  end system;
end FlightControlSystem;"""

grammar_path = '/root/sj-tmp/attribute_grammar/grammars/aadl_mvp.lark'

# 1. Lark 解析
print("=== 1. Lark 解析 ===")
from lark import Lark
with open(grammar_path) as f:
    grammar = f.read()
try:
    parser = Lark(grammar, start='start')
    tree = parser.parse(code)
    print("✅ 成功")
except Exception as e:
    print(f"❌ 失败: {e}")

# 2. Outlines CFG FSM 验证（回放 token）
print()
print("=== 2. Outlines CFG FSM 回放验证 ===")
import outlines
from outlines.generator import get_cfg_logits_processor
from transformers import AutoTokenizer
import torch

tokenizer = AutoTokenizer.from_pretrained('/root/sj-fs/qwen3-14b')

# 创建一个 mock 模型对象来包装 tokenizer
class MockModel:
    def __init__(self, tokenizer):
        self.tokenizer = tokenizer
        self.device = torch.device('cpu')

mock = MockModel(tokenizer)
outlines_model = outlines.from_transformers(mock, tokenizer)

cfg_lp = get_cfg_logits_processor(
    backend_name="llguidance",
    model=outlines_model,
    grammar=grammar,
)

# 编码代码
input_ids = tokenizer.encode(code, return_tensors='pt')
print(f"代码 token 数: {input_ids.shape[1]}")

# 逐步回放
num_tokens = input_ids.shape[1]
passed = 0
for i in range(1, num_tokens + 1):
    prefix = input_ids[:, :i]
    scores = torch.zeros(1, tokenizer.vocab_size)
    
    try:
        filtered = cfg_lp(prefix, scores)
        next_id = input_ids[0, i-1].item()
        if filtered[0, next_id] > float('-inf') or filtered[0, next_id] == 0:
            passed += 1
        else:
            t = tokenizer.decode([next_id])
            print(f"  ❌ step {i}: token '{t}' (id={next_id}) 被拒绝")
            break
    except Exception as e:
        print(f"  ⚠️ step {i}: 异常 {type(e).__name__}: {e}")
        break

print(f"通过: {passed}/{num_tokens} tokens")
if passed == num_tokens:
    print("✅ 全部通过（CFG 认为合法）")
else:
    print("❌ 有 token 被拒绝")

# 3. 也看看生成的代码去掉 end features 之后
print()
print("=== 3. 去掉 'end features;' 后再验证 ===")
code2 = code.replace("    end features;\n", "")
print(f"修改后代码长度: {len(code2)}")
try:
    tree = parser.parse(code2)
    print("✅ Lark 解析成功")
except Exception as e:
    print(f"❌ Lark 解析失败: {e}")
