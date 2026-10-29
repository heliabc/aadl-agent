"""详细调试：每一步都记录上下文检测结果"""
import sys
sys.path.insert(0, '/root/sj-tmp/attribute_grammar')
import torch
import time
from transformers import AutoModelForCausalLM, AutoTokenizer
import outlines
from outlines.generator import get_cfg_logits_processor
from core.context_detector import ContextDetector

model_path = '/root/sj-fs/qwen3-14b'
grammar_path = '/root/sj-tmp/attribute_grammar/grammars/aadl_mvp.lark'

print('加载模型...')
tokenizer = AutoTokenizer.from_pretrained(model_path)
model = AutoModelForCausalLM.from_pretrained(model_path, device_map='auto', dtype=torch.float16)
print('模型加载完成')

# chat prompt
messages = [
    {"role": "system", "content": "你是 AADL 代码生成专家，严格按语法输出完整的 AADL 代码。"},
    {"role": "user", "content": "设计一个简单的飞行控制系统"},
]
prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
inputs = tokenizer(prompt, return_tensors='pt').to(model.device)
input_len = inputs.input_ids.shape[1]
print(f'Prompt token 数: {input_len}')
print(f'Prompt 末尾: {repr(prompt[-50:])}')

# CFG 约束
with open(grammar_path) as f:
    grammar_text = f.read()

outlines_model = outlines.from_transformers(model, tokenizer)
cfg_lp = get_cfg_logits_processor(backend_name="llguidance", model=outlines_model, grammar=grammar_text)
print('CFG LP 已初始化')

# 检测器
detector = ContextDetector(grammar_path)

# 逐 token 生成 + 检测
generated = inputs.input_ids.clone()

for i in range(30):
    with torch.no_grad():
        outputs = model(generated)
        logits = outputs.logits[:, -1, :]

    # 应用 CFG 约束
    logits = cfg_lp(generated, logits)

    # 取下一个 token
    next_token = torch.argmax(logits, dim=-1, keepdim=True)
    generated = torch.cat([generated, next_token], dim=-1)

    # 解码生成部分（跳过 prompt）
    gen_ids = generated[0][input_len:]
    gen_text = tokenizer.decode(gen_ids, skip_special_tokens=True)

    # 检测上下文
    ctx, env = detector.detect(gen_text)

    next_token_text = tokenizer.decode(next_token[0].tolist(), skip_special_tokens=True)

    print(f'step {i+1:2d} | token={repr(next_token_text):12s} | ctx={ctx.context_type:25s} | gen={repr(gen_text[:70])}')

    if next_token.item() == tokenizer.eos_token_id:
        print('碰到 EOS，停止')
        break

print()
print('最终生成:')
gen_ids = generated[0][input_len:]
print(tokenizer.decode(gen_ids, skip_special_tokens=True))
