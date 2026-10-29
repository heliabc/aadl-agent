"""
属性文法约束的 Logits Processor
================================
包裹 outlines/llguidance 的 CFG logits processor，
在语法合法的 token 基础上，进一步过滤掉违反语义约束的 token。

精确实现：
- 使用 ContextDetector（基于 Earley 状态机 + 语义状态机）判断当前上下文
- 替换原有的启发式正则匹配判断
- 支持 4 类语义约束：
  1. feature_data_type: data port 后必须是 data 类别
  2. subcomponent_classifier: 子组件 classifier 必须匹配声明类别
  3. conn_end_first_segment: 连接端点第一段（子组件名）
  4. conn_end_second_segment: 连接端点第二段（端口名）
"""

import torch  # noqa: F401
from typing import List, Optional

from .attribute_env import AttributeEnvironment
from .context_detector import ContextDetector


class AttributeGrammarLogitsProcessor:
    """
    属性文法 Logits Processor
    
    与 outlines 的 CFGLogitsProcessor 配合使用，
    在语法约束之上叠加语义约束。

    核心改进：使用基于 Earley 状态机的精确上下文检测，
    替换了原来的启发式正则匹配。
    """

    def __init__(
        self,
        grammar_path: str,
        tokenizer,
        cfg_logits_processor=None,
        prompt_len: int = 0,
    ):
        """
        Args:
            grammar_path: Lark EBNF 文法文件路径
            tokenizer: 模型的 tokenizer
            cfg_logits_processor: 底层的 CFG logits processor（来自 outlines）
            prompt_len: prompt 的 token 数（生成部分从 prompt_len 之后开始）
        """
        self.tokenizer = tokenizer
        self.cfg_processor = cfg_logits_processor
        self.prompt_len = prompt_len

        # 精确上下文检测器（基于 Earley + 状态机）
        self.detector = ContextDetector(grammar_path)

        # 缓存：当前已生成的文本（不含 prompt）
        self._current_text = ""

    def __call__(self, input_ids: torch.LongTensor, scores: torch.FloatTensor) -> torch.FloatTensor:
        """
        处理 logits：先过 CFG 约束，再过滤语义不合法的 token
        
        Args:
            input_ids: 已生成的 token id
            scores: logits 分数
            
        Returns:
            过滤后的 logits 分数
        """
        # 1. 先过 CFG 约束（如果有）
        if self.cfg_processor is not None:
            scores = self.cfg_processor(input_ids, scores)

        # 2. 获取当前生成的文本（跳过 prompt 部分）
        generated_ids = input_ids[0][self.prompt_len:]
        self._current_text = self.tokenizer.decode(
            generated_ids, skip_special_tokens=True
        )

        # 3. 用精确检测器判断当前语义上下文
        ctx, env = self.detector.detect(self._current_text)

        # 4. 如果在约束位置，计算语义 mask 并过滤
        if ctx.context_type != "none":
            semantic_mask = self._compute_semantic_mask(
                ctx, env, scores.shape[-1], scores.device
            )
            if semantic_mask is not None:
                scores = scores.masked_fill(~semantic_mask, float("-inf"))

        return scores

    def _compute_semantic_mask(
        self,
        ctx,
        env: AttributeEnvironment,
        vocab_size: int,
        device,
    ) -> Optional[torch.Tensor]:
        """
        根据语义上下文计算 mask：合法 token 为 True，非法为 False
        
        返回 None 表示不过滤（所有都合法）
        """
        ctx_type = ctx.context_type
        info = ctx.info

        # ---- 约束 1：feature 的数据类型必须是 data 类别 ----
        if ctx_type == "feature_data_type":
            allowed_names = env.get_available_types_by_category("data")
            if allowed_names:
                return self._build_mask_from_names(allowed_names, vocab_size, device)
            return None  # 没有 data 类型可用，不过滤（避免死锁）

        # ---- 约束 2：subcomponent 的 classifier 必须匹配声明类别 ----
        if ctx_type == "subcomponent_classifier":
            category = info.get("category", "")
            if category:
                allowed_names = env.get_available_types_by_category(category)
                if allowed_names:
                    return self._build_mask_from_names(allowed_names, vocab_size, device)
            return None

        # ---- 约束 3：连接端点第一段（子组件名） ----
        if ctx_type == "conn_end_first_segment":
            subcomp_names = env.get_subcomponent_names()
            if subcomp_names:
                return self._build_mask_from_names(subcomp_names, vocab_size, device)
            return None

        # ---- 约束 4：连接端点第二段（端口名） ----
        if ctx_type == "conn_end_second_segment":
            is_source = info.get("is_source", True)
            direction = "out" if is_source else "in"

            # 从当前文本中提取子组件名（最后一个 . 前面的标识符）
            subcomp_name = self._extract_last_subcomp_name()

            if subcomp_name:
                # 优先获取指定子组件、指定方向的端口
                features = env.get_available_features(
                    subcomp_name, direction=direction
                )
                if features:
                    return self._build_mask_from_names(features, vocab_size, device)

                # 没有指定方向的，试试该子组件的所有端口
                features = env.get_available_features(subcomp_name)
                if features:
                    return self._build_mask_from_names(features, vocab_size, device)

            # 提取不到子组件名时，返回所有子组件的所有端口（兜底）
            all_features = set()
            for sc_name in env.get_subcomponent_names():
                feats = env.get_available_features(sc_name)
                all_features.update(feats)
            if all_features:
                return self._build_mask_from_names(
                    list(all_features), vocab_size, device
                )
            return None

        # 其他上下文类型：不过滤
        return None

    def _extract_last_subcomp_name(self) -> Optional[str]:
        """
        从当前生成文本中提取最后一个 . 前面的子组件名
        用于 conn_end_second_segment 上下文
        """
        last_line = self._current_text.rstrip().split("\n")[-1]
        if "." not in last_line:
            return None

        # 取最后一个 . 前面的部分
        before_dot = last_line.rsplit(".", 1)[0]
        # 提取最后一个标识符
        tokens = before_dot.split()
        if tokens:
            return tokens[-1]
        return None

    def _build_mask_from_names(
        self, names: List[str], vocab_size: int, device
    ) -> Optional[torch.Tensor]:
        """
        根据允许的名字列表构建 mask
        
        注意：这是简化实现，只匹配完整 token 等于名字的情况。
        实际中需要考虑 tokenizer 的分词方式，一个名字可能由多个 token 组成。
        MVP 版本先假设名字是单个 token。
        """
        mask = torch.zeros(vocab_size, dtype=torch.bool, device=device)

        for name in names:
            # 尝试编码名字为单个 token
            token_ids = self.tokenizer.encode(name, add_special_tokens=False)
            for tid in token_ids:
                if tid < vocab_size:
                    mask[tid] = True

        # 如果没有匹配到任何 token，不过滤（避免死锁）
        if not mask.any():
            return None

        return mask.unsqueeze(0)  # 增加 batch 维度

    def reset(self):
        """重置状态，用于新一轮生成"""
        self.detector.reset()
        self._current_text = ""
