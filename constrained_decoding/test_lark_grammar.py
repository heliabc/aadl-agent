"""验证 Lark 语法"""
from lark import Lark

with open('grammars/aadl_core.lark', 'r', encoding='utf-8') as f:
    grammar = f.read()

try:
    parser = Lark(grammar, start='start')
    print('语法解析成功!')
except Exception as e:
    print(f'语法错误: {e}')
    import traceback
    traceback.print_exc()
