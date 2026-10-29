"""测试 outlines CFG logits processor 是否会调用列表中的其他 processor"""
import torch
import sys
sys.path.insert(0, '/root/sj-tmp/attribute_grammar')
from transformers import AutoModelForCausalLM, AutoTokenizer
import outlines
from outlines.generator import get_cfg_logits_processor

model_path = '/root/sj-fs/qwen3-14b'
grammar_path = '/root/sj-tmp/attribute_grammar/grammars/aadl_mvp.lark'

print('加载模型...')
tokenizer = AutoTokenizer.from_pretrained(model_path)
model = AutoModelForCausalLM.from_pretrained(model_path, device_map='auto', dtype=torch.float16)
print('模型加载完成')

with open(grammar_path) as f:
    grammar_text = f.read()

outlines_model = outlines.from_transformers(model, tokenizer)
cfg_lp = get_cfg_logits_processor(backend_name="llguidance", model=outlines_model, grammar=grammar_text)
print(f'CFG LP 类型: {type(cfg_lp)}')
print(f'CFG LP 是列表? {isinstance(cfg_lp, (list, tuple))}')

# 自定义一个计数 processor
class CountLP:
    def __init__(self):
        self.count = 0
    def __call__(self, input_ids, scores):
        self.count += 1
        if self.count <= 5:
            text = tokenizer.decode(input_ids[0], skip_special_tokens=True)
            print(f'  LP 调用 #{self.count}, input_len={len(input_ids[0])}, text_tail={repr(text[-40:])}')
        return scores

count_lp = CountLP()

# 组合
if isinstance(cfg_lp, (list, tuple)):
    lp_list = list(cfg_lp) + [count_lp]
else:
    lp_list = [cfg_lp, count_lp]

print(f'\nLP 列表: {len(lp_list)} 个')
for i, lp in enumerate(lp_list):
    print(f'  [{i}] {type(lp).__name__}')

prompt = 'package test is\npublic\n  data Foo\n  end Foo;\n  '
inputs = tokenizer(prompt, return_tensors='pt').to(model.device)

print('\n开始生成 10 tokens...')
with torch.no_grad():
    outputs = model.generate(
        **inputs,
        max_new_tokens=10,
        logits_processor=lp_list,
        pad_token_id=tokenizer.eos_token_id,
        do_sample=False,
    )

result = tokenizer.decode(outputs[0][inputs.input_ids.shape[1]:], skip_special_tokens=True)
print(f'\n生成结果: {repr(result)}')
print(f'CountLP 被调用次数: {count_lp.count}')
