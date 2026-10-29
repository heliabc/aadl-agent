"""属性文法约束解码 MVP 核心模块"""

from .attribute_env import AttributeEnvironment, CONTAINMENT_RULES
from .attribute_parser import IncrementalAttributeParser

__all__ = [
    "AttributeEnvironment",
    "CONTAINMENT_RULES",
    "IncrementalAttributeParser",
    "AttributeGrammarLogitsProcessor",
]


def __getattr__(name):
    """懒加载 torch 依赖的模块"""
    if name == "AttributeGrammarLogitsProcessor":
        from .logits_processor import AttributeGrammarLogitsProcessor
        return AttributeGrammarLogitsProcessor
    raise AttributeError(f"module 'core' has no attribute {name!r}")
