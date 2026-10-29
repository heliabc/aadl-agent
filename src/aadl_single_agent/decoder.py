"""Tokenizer-aware, single-sequence greedy decoding with joint constraints.

This module deliberately has no Torch/Transformers imports.  The token bridge can
therefore be exercised offline and used with other inference implementations.
"""

from __future__ import annotations

import math
import codecs
import time
from dataclasses import dataclass
from typing import Any, Iterable, Sequence


class ConstrainedDecodingError(RuntimeError):
    """No supported legal next token exists, or generation changed its history."""


class NoLegalTokenError(ConstrainedDecodingError):
    """Every available next-token candidate violates the joint constraints."""


@dataclass(frozen=True)
class TokenTrial:
    allowed: bool
    token_ids: tuple[int, ...]
    text: str
    state: Any
    reason: str = ""
    pending_bytes: bytes = b""
    decoded_bytes: bytes = b""


class TokenBridge:
    """Map complete generated token sequences to immutable engine states.

    A token's isolated decoding is not its contribution in context.  Every trial
    decodes the complete generated sequence.  Append-only text uses an incremental
    probe; tokenizer rewrites are checked by replaying the text from an empty state.
    Neither operation commits a trial: only ``synchronize`` observes actual choices.
    """

    def __init__(self, engine: Any, tokenizer: Any, eos_token_ids: Iterable[int], *, byte_tokenizer: Any = None):
        self.engine = engine
        self.tokenizer = tokenizer
        self.byte_tokenizer = byte_tokenizer if byte_tokenizer is not None else tokenizer
        if not callable(getattr(self.byte_tokenizer, "decode_bytes", None)):
            raise ValueError("An exact decode_bytes tokenizer is required; lossy text decoding is unsupported")
        self.eos_token_ids = frozenset(int(t) for t in eos_token_ids if t is not None)
        if not self.eos_token_ids:
            raise ValueError("Constrained generation requires at least one EOS token")
        self.special_token_ids = frozenset(getattr(tokenizer, "all_special_ids", []))
        self.state = engine.start()
        self.token_ids: tuple[int, ...] = ()
        self.text = ""
        self.pending_bytes = b""
        self.decoded_bytes = b""
        self.candidates_tested = 0
        self.rejections = 0
        self._proposal: TokenTrial | None = None

    def decode(self, token_ids: Sequence[int]) -> tuple[str, bytes, bytes]:
        # Use the SAME llguidance byte tokenizer as the CFG mask. Strict UTF-8
        # distinguishes an incomplete byte suffix from a literal U+FFFD character.
        raw = self.byte_tokenizer.decode_bytes(list(token_ids))
        if not isinstance(raw, (bytes, bytearray)):
            raise TypeError("decode_bytes must return bytes")
        decoder = codecs.getincrementaldecoder("utf-8")("strict")
        text = decoder.decode(bytes(raw), final=False)
        return text, decoder.getstate()[0], bytes(raw)

    def _trial_sequence(self, token_ids: tuple[int, ...]) -> TokenTrial:
        try:
            text, pending_bytes, raw = self.decode(token_ids)
        except UnicodeDecodeError as exc:
            return TokenTrial(False, token_ids, self.text, self.state, f"Invalid UTF-8 token bytes: {exc}")
        current_text = self.state.text
        if text.startswith(current_text):
            base = self.state
            piece = text[len(current_text) :]
        else:
            base = self.engine.start()
            piece = text
        if piece:
            result = self.engine.probe(base, piece)
            if not result.allowed:
                return TokenTrial(False, token_ids, text, base, str(result.reason), pending_bytes, raw)
            state = result.state
        else:
            state = base
        return TokenTrial(True, token_ids, text, state, pending_bytes=pending_bytes, decoded_bytes=raw)

    def can_end(self) -> bool:
        if self.pending_bytes:
            return False
        report = self.engine.finish(self.state)
        return bool(report.valid) and not bool(report.pending)

    def probe_token(self, token_id: int) -> TokenTrial:
        """Check a candidate without changing the committed state or token history."""
        token_id = int(token_id)
        ids = self.token_ids + (token_id,)
        if token_id in self.eos_token_ids:
            allowed = self.can_end()
            return TokenTrial(allowed, ids, self.text, self.state,
                              "" if allowed else "EOS requires complete syntax and closed obligations")
        if token_id in self.special_token_ids:
            return TokenTrial(False, ids, self.text, self.state, "Non-EOS special token is unsupported")
        trial = self._trial_sequence(ids)
        if trial.allowed and trial.decoded_bytes == self.decoded_bytes:
            # Invisible non-EOS tokens can otherwise consume an unbounded budget.
            return TokenTrial(False, ids, trial.text, trial.state, "Token contributes no decoded text")
        return trial

    def synchronize(self, generated_token_ids: Sequence[int]) -> None:
        """Commit only the sequence actually selected by the inference backend."""
        ids = tuple(int(t) for t in generated_token_ids)
        if ids == self.token_ids:
            return
        if ids[: len(self.token_ids)] != self.token_ids:
            raise ConstrainedDecodingError("Token history was replaced; beam search and KV rollback are unsupported")
        if any(t in self.eos_token_ids for t in ids):
            raise ConstrainedDecodingError("EOS must terminate the generated sequence")
        if any(t in self.special_token_ids for t in ids):
            raise ConstrainedDecodingError("Selected history contains a non-EOS special token")
        trial = self._proposal if self._proposal and self._proposal.token_ids == ids else self._trial_sequence(ids)
        if not trial.allowed:
            raise ConstrainedDecodingError(f"Actual selected history violates constraints: {trial.reason}")
        self.token_ids, self.text, self.state = ids, trial.text, trial.state
        self.pending_bytes = trial.pending_bytes
        self.decoded_bytes = trial.decoded_bytes
        self._proposal = None

    def choose(self, ranked_token_ids: Iterable[int]) -> int:
        """Choose the first valid candidate from the FULL descending score order."""
        last_reason = "No candidate scores are available"
        attempted = False
        for token_id in ranked_token_ids:
            attempted = True
            self.candidates_tested += 1
            trial = self.probe_token(int(token_id))
            if trial.allowed:
                self._proposal = trial
                return int(token_id)
            last_reason = trial.reason
            self.rejections += 1
        if not attempted:
            # An empty/NaN-only score set is a backend problem, not evidence that
            # the semantic prefix reached a retryable decoding dead end.
            raise ConstrainedDecodingError("No finite candidate token scores are available")
        raise NoLegalTokenError(f"No legal next token: {last_reason}")

    def choose_from_scores(self, scores: Sequence[float]) -> int:
        """Dependency-free greedy selector used by offline tests and other backends."""
        candidates = [i for i, value in enumerate(scores) if math.isfinite(float(value))]
        candidates.sort(key=lambda i: float(scores[i]), reverse=True)
        return self.choose(candidates)

    def finalize(self, generated_token_ids: Sequence[int]) -> Any:
        ids = tuple(int(t) for t in generated_token_ids)
        terminated = bool(ids and ids[-1] in self.eos_token_ids)
        self.synchronize(ids[:-1] if terminated else ids)
        if self.pending_bytes:
            raise ConstrainedDecodingError("Generation ended with an incomplete Unicode byte sequence")
        report = self.engine.finish(self.state)
        if terminated and (not report.valid or report.pending):
            raise ConstrainedDecodingError("Inference backend selected EOS before all obligations were closed")
        return report


class JointLogitsProcessor:
    """Apply the real CFG mask, then semantic filtering in the same greedy step.

    Keeping only the highest-scoring legal token is equivalent to masking every
    illegal token followed by greedy argmax.  It is NOT a sampling distribution,
    so the service explicitly rejects sampling, batching, and beam search.
    """

    def __init__(self, engine: Any, tokenizer: Any, prompt_token_ids: Sequence[int],
                 eos_token_ids: Iterable[int], *, syntax_processor: Any,
                 byte_tokenizer: Any = None, num_beams: int = 1):
        if num_beams != 1:
            raise ValueError("Only num_beams=1 is supported")
        self.prompt_token_ids = tuple(int(t) for t in prompt_token_ids)
        if not callable(syntax_processor):
            raise ValueError("A real CFG logits processor is required")
        self.syntax_processor = syntax_processor
        byte_tokenizer = byte_tokenizer if byte_tokenizer is not None else getattr(syntax_processor, "llg_tokenizer", None)
        self.bridge = TokenBridge(engine, tokenizer, eos_token_ids, byte_tokenizer=byte_tokenizer)
        self.steps = 0
        self.syntax_mask_steps = 0
        self.syntax_mask_seconds = 0.0
        self.semantic_filter_seconds = 0.0
        self.synchronization_seconds = 0.0
        self._last_generated_length: int | None = None

    def evidence(self) -> dict:
        """Measured counters, including a final failed step when one occurred."""
        return {"mask_steps": self.steps, "syntax_mask_steps": self.syntax_mask_steps,
                "semantic_candidates_tested": self.bridge.candidates_tested,
                "semantic_rejections": self.bridge.rejections,
                "syntax_mask_seconds": self.syntax_mask_seconds,
                "semantic_filter_seconds": self.semantic_filter_seconds,
                "synchronization_seconds": self.synchronization_seconds}

    def __call__(self, input_ids: Any, scores: Any) -> Any:
        if len(input_ids.shape) != 2 or input_ids.shape[0] != 1 or scores.shape[0] != 1:
            raise ValueError("Joint constraints support exactly one sequence; batches/beams are unsupported")
        actual = tuple(int(t) for t in input_ids[0].tolist())
        prefix_size = len(self.prompt_token_ids)
        if actual[:prefix_size] != self.prompt_token_ids:
            raise ConstrainedDecodingError("Inference backend changed the prompt")
        generated_length = len(actual) - prefix_size
        expected_length = 0 if self._last_generated_length is None else self._last_generated_length + 1
        if generated_length != expected_length:
            raise ConstrainedDecodingError("CFG state requires exactly one new selected token per greedy step")
        started = time.perf_counter()
        self.bridge.synchronize(actual[prefix_size:])
        self.synchronization_seconds += time.perf_counter() - started
        self._last_generated_length = generated_length
        raw_values = scores[0].tolist()
        if not any(math.isfinite(float(value)) for value in raw_values):
            raise ConstrainedDecodingError("No finite candidate token scores are available")
        started = time.perf_counter()
        try:
            # This stateful Outlines call happens ONCE per actual generation step,
            # never while probing candidate tokens. It consumes the selected token.
            syntax_scores = self.syntax_processor(input_ids, scores.clone())
            self.syntax_mask_steps += 1
            # This transfer also waits for an asynchronous GPU mask to complete,
            # so the recorded wall time includes its execution, not just dispatch.
            values = syntax_scores[0].tolist()
            for matcher in getattr(self.syntax_processor, "ll_matchers", []):
                if error := matcher.get_error():
                    raise ConstrainedDecodingError(f"llguidance matcher failed: {error}")
        finally:
            self.syntax_mask_seconds += time.perf_counter() - started
        # Transfer the complete ranking, not a fixed top-k slice: low-ranked legal
        # tokens must remain reachable even when every high-ranked token is invalid.
        started = time.perf_counter()
        try:
            ranked = syntax_scores[0].argsort(descending=True).tolist()
            finite = [token for token in ranked if math.isfinite(float(values[token]))]
            if not finite:
                raise NoLegalTokenError("No legal next token: CFG mask excludes every finite candidate")
            chosen = self.bridge.choose(finite)
        finally:
            self.semantic_filter_seconds += time.perf_counter() - started
        selected_score = syntax_scores[0, chosen].clone()
        masked = syntax_scores.clone()
        masked.fill_(float("-inf"))
        masked[0, chosen] = selected_score
        self.steps += 1
        return masked
