"""测试 tokenizer 解码行为"""
import sys
sys.path.insert(0, '/root/sj-tmp/attribute_grammar')
from transformers import AutoTokenizer

tokenizer = AutoTokenizer.from_pretrained("/root/sj-fs/qwen3-14b")

# 看看 features 这个词
text = "features"
ids = tokenizer.encode(text)
print(f"'features' -> {len(ids)} tokens: {ids}")
for tid in ids:
    t_skip = tokenizer.decode([tid], skip_special_tokens=True)
    t_noskip = tokenizer.decode([tid], skip_special_tokens=False)
    print(f"  id={tid:6d}  skip={repr(t_skip):15s}  noskip={repr(t_noskip):15s}")

# 看看空格 + features
text2 = " features"
ids2 = tokenizer.encode(text2)
print(f"\n' features' -> {len(ids2)} tokens: {ids2}")
for tid in ids2:
    t_skip = tokenizer.decode([tid], skip_special_tokens=True)
    t_noskip = tokenizer.decode([tid], skip_special_tokens=False)
    print(f"  id={tid:6d}  skip={repr(t_skip):15s}  noskip={repr(t_noskip):15s}")

# 看看完整句子的编码
full = "package FlightControlSystem is\npublic\n  system FlightControlSystem\n    features"
ids_full = tokenizer.encode(full)
print(f"\n完整句子: {len(ids_full)} tokens")
decoded_skip = tokenizer.decode(ids_full, skip_special_tokens=True)
decoded_noskip = tokenizer.decode(ids_full, skip_special_tokens=False)
print(f"  skip_special_tokens=True:  {len(decoded_skip)} chars, 匹配={decoded_skip == full}")
print(f"  skip_special_tokens=False: {len(decoded_noskip)} chars, 匹配={decoded_noskip == full}")
if decoded_skip != full:
    print(f"  skip 差异: {repr(decoded_skip)}")
