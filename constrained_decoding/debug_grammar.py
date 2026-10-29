from lark import Lark

# 验证：去掉 section 前面的 WS，因为 NEWLINE 已经包含了缩进
grammar = r"""
start: data_decl
data_decl: "data" WS IDENT NEWLINE data_type_sections "end" WS IDENT ";" NEWLINE
data_type_sections: [features_section] [props_section]
features_section: "features" NEWLINE feature_decl+
feature_decl: IDENT ";" NEWLINE
props_section: "properties" NEWLINE prop_decl+
prop_decl: IDENT " => " IDENT ";" NEWLINE
IDENT: /[A-Za-z_][A-Za-z0-9_]*/
WS: /[ \t]+/
NEWLINE: /[\n]+[ \t]*/
"""

test1 = """data MyData
    features
      foo;
    properties
      bar => baz;
    end MyData;
"""

print("Test1: features + properties，都有缩进")
parser = Lark(grammar, start="start", parser="earley")
try:
    tree = parser.parse(test1)
    print("  OK")
except Exception as e:
    print(f"  FAIL: {e}")

test2 = """data MyData
    end MyData;
"""

print("\nTest2: 没有 section")
try:
    tree = parser.parse(test2)
    print("  OK")
except Exception as e:
    print(f"  FAIL: {e}")

test3 = """data MyData
    features
      foo;
    end MyData;
"""

print("\nTest3: 只有 features")
try:
    tree = parser.parse(test3)
    print("  OK")
except Exception as e:
    print(f"  FAIL: {e}")
