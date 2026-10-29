"""超详细调试：打印 token id、gen_ids 长度"""
import sys
sys.path.insert(0, '/root/sj-tmp/attribute_grammar')
import torch
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

# 简单 prompt（不用 chat template，直接 AADL 开头）
prompt = "package Test is\npublic\n  "
inputs = tokenizer(prompt, return_tensors='pt').to(model.device)
input_len = inputs.input_ids.shape[1]
print(f'Prompt: {repr(prompt)}')
print(f'Prompt token 数: {input_len}')
print(f'Prompt ids: {inputs.input_ids[0].tolist()}')

# CFG 约束
with open(grammar_path) as f:
    grammar_text = f.read()

outlines_model = outlines.from_transformers(model, tokenizer)
cfg_lp = get_cfg_logits_processor(backend_name="llguidance", model=outlines_model, grammar=grammar_text)

detector = ContextDetector(grammar_path)

generated = inputs.input_ids.clone()

for i in range(20):
    with torch.no_grad():
        outputs = model(generated)
        logits = outputs.logits[:, -1, :]

    logits = cfg_lp(generated, logits)
    next_token = torch.argmax(logits, dim=-1, keepdim=True)
    generated = torch.cat([generated, next_token], dim=-1)

    gen_ids = generated[0][input_len:]
    gen_text_skip = tokenizer.decode(gen_ids, skip_special_tokens=True)
    gen_text_noskip = tokenizer.decode(gen_ids, skip_special_tokens=False)

    next_id = next_token[0].item()
    next_text_skip = tokenizer.decode([next_id], skip_special_tokens=True)
    next_text_noskip = tokenizer.decode([next_id], skip_special_tokens=False)

    ctx, _ = detector.detect(gen_text_skip)

    print(f'{i+1:2d} | id={next_id:6d} | skip={repr(next_text_skip):12s} | noskip={repr(next_text_noskip):12s} | gen_len={len(gen_ids):2d} | ctx={ctx.context_type:20s} | gen_skip={repr(gen_text_skip[-30:])}')

    if next_id == tokenizer.eos_token_id:
        break

print()
print('最终 gen_text_skip:')
print(tokenizer.decode(generated[0][input_len:], skip_special_tokens=True))
