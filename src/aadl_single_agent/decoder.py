"""Tokenizer-aware, single-sequence greedy decoding with joint constraints.

This module deliberately has no Torch/Transformers imports.  The token bridge can
therefore be exercised offline and used with other inference implementations.
"""

from __future__ import annotations

import math
import codecs
import time
from collections import deque
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, Iterable, Sequence


REPEATED_LINE_LIMIT = 32
REPETITION_WINDOW_CHARACTERS = 2048
REPETITION_MAX_PERIOD_CHARACTERS = 256
REPETITION_MIN_REPETITIONS = 8
REPETITION_MIN_SPAN_CHARACTERS = 256
CANDIDATE_TRACE_STEP_LIMIT = 32
CANDIDATE_TRACE_REJECTION_LIMIT = 16
CANDIDATE_TRACE_REASON_CHARACTERS = 512


def repetition_stop_configuration() -> dict:
    return {"schema_version": 2, "threshold": REPEATED_LINE_LIMIT, "unit": "completed_nonempty_lines",
            "comparison": "exact", "whitespace_only_lines_count": True,
            "combination": "any", "observation": "committed_output_after_token_selection",
            "periodic_suffix": {
                "window_characters": REPETITION_WINDOW_CHARACTERS,
                "max_period_characters": REPETITION_MAX_PERIOD_CHARACTERS,
                "minimum_repetitions": REPETITION_MIN_REPETITIONS,
                "minimum_span_characters": REPETITION_MIN_SPAN_CHARACTERS,
                "comparison": "exact", "character_unit": "unicode_codepoints",
            },
            "scope": ["plain", "aadl_gcd", "aadl_joint", "json_schema"]}


class RepeatedLineDetector:
    """A generation stop heuristic, independent of token legality or ranking.

    Retain the original completed-line rule and also inspect an exact periodic
    suffix, including a still-open line/identifier. Neither rule establishes a
    syntax error: this is a common stopping policy for all decoding conditions.
    Original whitespace is preserved. Recomputing from the actual decoded text
    makes tokenizer rewrites safe and never examines speculative candidates.
    """

    def __init__(self):
        self.termination: dict | None = None
        self.observations = 0
        self.observation_seconds = 0.0

    def observe(self, text: str) -> bool:
        started = time.perf_counter()
        self.observations += 1
        try:
            return self._observe(text)
        finally:
            self.observation_seconds += time.perf_counter() - started

    def _observe(self, text: str) -> bool:
        previous, count = None, 0
        for index, line in enumerate(text.split("\n")[:-1]):
            if line in ("", "\r"):
                previous, count = None, 0
                continue
            count = count + 1 if line == previous else 1
            previous = line
            if count >= REPEATED_LINE_LIMIT:
                self.termination = {"kind": "repetition", "unit": "completed_nonempty_lines", "repeated_lines": count,
                    "threshold": REPEATED_LINE_LIMIT, "line": line,
                    "completed_line_index": index + 1, "comparison": "exact"}
                return True
        # Bound only diagnostic look-back, never the model's candidate set.
        # The shortest qualifying period wins. Unicode character thresholds
        # are independent of BPE segmentation and do not wait for a newline.
        window = text[-REPETITION_WINDOW_CHARACTERS:]
        largest_period = min(REPETITION_MAX_PERIOD_CHARACTERS, len(window) // REPETITION_MIN_REPETITIONS)
        for period in range(1, largest_period + 1):
            minimum = max(REPETITION_MIN_REPETITIONS,
                          (REPETITION_MIN_SPAN_CHARACTERS + period - 1) // period)
            span = minimum * period
            if span > len(window):
                continue
            fragment = window[-period:]
            if window[-span:] != fragment * minimum:
                continue
            count = minimum
            while (count + 1) * period <= len(window):
                if window[-(count + 1) * period:-count * period] != fragment:
                    break
                count += 1
            span = count * period
            self.termination = {
                "kind": "repetition", "unit": "periodic_suffix", "comparison": "exact",
                "period_characters": period, "repetitions": count,
                "repeated_characters": span, "fragment": fragment,
                "start_character": len(text) - span, "end_character": len(text),
                "character_offsets": "zero_based_half_open_unicode_codepoints",
                "window_characters": REPETITION_WINDOW_CHARACTERS,
                "observed_window_characters": len(window),
                "minimum_repetitions": REPETITION_MIN_REPETITIONS,
                "minimum_span_characters": REPETITION_MIN_SPAN_CHARACTERS,
                "max_period_characters": REPETITION_MAX_PERIOD_CHARACTERS,
            }
            return True
        self.termination = None
        return False


class GenerationDeadlineExceeded(TimeoutError):
    """A cooperative generation-budget stop, distinct from backend failures.

    The worker must unwind before a server releases its inference lock. A budget
    stop never proves that the remaining token candidates are all illegal.
    """
    def __init__(self, phase: str, message: str | None = None, *, details: dict | None = None):
        self.phase = phase
        self.details = deepcopy(details or {})
        super().__init__(message or f"Generation deadline reached during {phase}")


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
        self.deadline: float | None = None
        self.token_decode_seconds = 0.0
        self.token_decode_calls = 0
        self.candidate_search_seconds = 0.0
        self.candidate_search_steps = 0
        self.candidate_search_max_seconds = 0.0
        self.candidate_tests_max_per_step = 0
        # Every attempted search retains its lightweight measurements. Only
        # verbose rejection reasons use the bounded recent-step buffer.
        self._candidate_step_metrics: list[dict] = []
        self._candidate_steps: deque[dict] = deque(maxlen=CANDIDATE_TRACE_STEP_LIMIT)
        # JointEngine is a facade over AttributeEngine. Inspect only these
        # existing mode flags: plain/GCD must not claim semantic observations.
        semantic_engine = getattr(engine, "_engine", engine)
        self._trace_enabled = (getattr(semantic_engine, "joint", False) is True
                               and getattr(semantic_engine, "project", False) is True)
        self._trace_disabled_reason = (None if self._trace_enabled else
                                       "attributes_disabled" if not getattr(semantic_engine, "joint", False)
                                       else "project_trace_only")
        self._initial_pending_reports = sorted(set(getattr(self.state, "pending", ()))) if self._trace_enabled else []
        self._mechanism_events: list[dict] = []
        self._closed_token_ids: tuple[int, ...] | None = None

    def mechanism_trace(self) -> dict:
        """Export committed observations without promoting missing reports to proofs.

        Token offsets refer to output tokens, excluding the prompt; character
        offsets count Unicode code points in the generated project container.
        A failed synchronization may leave actual output beyond this boundary.
        Never reconstruct that unseen tail merely to make the log look complete.
        """
        return {
            "schema_version": 1, "enabled": self._trace_enabled,
            "disabled_reason": self._trace_disabled_reason,
            "scope": "committed_project_prefix",
            "token_offsets": "zero_based_half_open_output_tokens",
            "character_offsets": "unicode_codepoints_in_generated_container",
            "committed_token_count": len(self.token_ids),
            "committed_character_offset": len(self.text),
            "pending_utf8_byte_count": len(self.pending_bytes),
            "initial_pending_reports": list(self._initial_pending_reports),
            "current_pending_reports": sorted(set(getattr(self.state, "pending", ()))) if self._trace_enabled else None,
            "project_obligations_closed": ("disabled" if not self._trace_enabled else
                                            "verified" if self._closed_token_ids is not None else "unverified"),
            # State tokens exclude EOS; this terminal boundary includes its
            # actual selection, so it can be joined to selected_token_ids.
            "closed_after_token_count": len(self._closed_token_ids) if self._closed_token_ids is not None else None,
            "events": deepcopy(self._mechanism_events),
        }

    def decode(self, token_ids: Sequence[int]) -> tuple[str, bytes, bytes]:
        # Use the SAME llguidance byte tokenizer as the CFG mask. Strict UTF-8
        # distinguishes an incomplete byte suffix from a literal U+FFFD character.
        started = time.perf_counter()
        self.token_decode_calls += 1
        try:
            raw = self.byte_tokenizer.decode_bytes(list(token_ids))
            if not isinstance(raw, (bytes, bytearray)):
                raise TypeError("decode_bytes must return bytes")
            decoder = codecs.getincrementaldecoder("utf-8")("strict")
            text = decoder.decode(bytes(raw), final=False)
            return text, decoder.getstate()[0], bytes(raw)
        finally:
            self.token_decode_seconds += time.perf_counter() - started

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
        if pending_bytes:
            # A byte token can commit a prefix of a Unicode character without
            # adding visible text. Check its actual grammar position now: an
            # impossible lead byte cannot be repaired by a later continuation.
            # Each check is speculative; only the completed character is parsed
            # and attributed by the normal text path on a subsequent token.
            check_bytes = getattr(self.engine, "probe_pending_bytes", None)
            if not callable(check_bytes):
                raise ConstrainedDecodingError("The engine cannot verify an incomplete UTF-8 byte prefix")
            result = check_bytes(state, pending_bytes)
            if not result.allowed:
                return TokenTrial(False, token_ids, text, base, str(result.reason), pending_bytes, raw)
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
        old_count, old_text = len(self.token_ids), self.text
        old_pending = set(getattr(self.state, "pending", ())) if self._trace_enabled else set()
        self.token_ids, self.text, self.state = ids, trial.text, trial.state
        self.pending_bytes = trial.pending_bytes
        self.decoded_bytes = trial.decoded_bytes
        self._proposal = None
        self._closed_token_ids = None
        if self._trace_enabled:
            current = set(getattr(self.state, "pending", ()))
            added, absent = sorted(current - old_pending), sorted(old_pending - current)
            if added or absent:
                # This is a difference of diagnostic texts at two committed
                # boundaries, NOT a matching of stable logical obligations.
                # Rewording, changed parse witnesses and reclassification can
                # remove a report without discharging any obligation.
                self._mechanism_events.append({
                    "event": "pending_reports_changed", "token_start": old_count, "token_end": len(ids),
                    "previous_character_offset": len(old_text), "character_offset": len(self.text),
                    "text_rewritten": not self.text.startswith(old_text),
                    "pending_added": added, "pending_no_longer_reported": absent,
                    "pending_report_count": len(current),
                })

    def choose(self, ranked_token_ids: Iterable[int]) -> int:
        """Choose the first valid candidate from the FULL descending score order."""
        last_reason = "No candidate scores are available"
        started = time.perf_counter()
        record = {"step": self.candidate_search_steps + 1, "generated_length": len(self.token_ids),
                  "candidates_tested": 0, "candidate_rejections": 0,
                  "last_candidate_token_id": None, "last_probe_seconds": None,
                  "max_probe_seconds": 0.0, "candidate_probe_seconds": 0.0,
                  "selected_candidate_token_id": None, "outcome": "error", "rejections": []}

        def check_deadline() -> None:
            if self.deadline is not None and time.monotonic() >= self.deadline:
                record["outcome"] = "deadline"
                raise GenerationDeadlineExceeded("candidate_search", details={
                    "generated_length": record["generated_length"],
                    "candidates_tested": record["candidates_tested"],
                    "candidate_rejections": record["candidate_rejections"],
                    "last_candidate_token_id": record["last_candidate_token_id"],
                })

        try:
            for token_id in ranked_token_ids:
                # Logging limits never truncate this full ranked search. Check
                # cooperatively around a probe, never interrupt an evaluator
                # while it owns a speculative transaction.
                check_deadline()
                token_id = int(token_id)
                record["last_candidate_token_id"] = token_id
                record["candidates_tested"] += 1
                self.candidates_tested += 1
                probe_started = time.perf_counter()
                try:
                    trial = self.probe_token(token_id)
                finally:
                    duration = time.perf_counter() - probe_started
                    record["last_probe_seconds"] = duration
                    record["max_probe_seconds"] = max(record["max_probe_seconds"], duration)
                    record["candidate_probe_seconds"] += duration
                if not trial.allowed:
                    last_reason = str(trial.reason)
                    self.rejections += 1
                    record["candidate_rejections"] += 1
                    if len(record["rejections"]) < CANDIDATE_TRACE_REJECTION_LIMIT:
                        record["rejections"].append({
                            "candidate_rank": record["candidates_tested"], "token_id": token_id,
                            "reason": last_reason[:CANDIDATE_TRACE_REASON_CHARACTERS],
                            "reason_characters": len(last_reason),
                            "reason_truncated": len(last_reason) > CANDIDATE_TRACE_REASON_CHARACTERS,
                        })
                check_deadline()
                if trial.allowed:
                    record.update(outcome="selected", selected_candidate_token_id=token_id)
                    self._proposal = trial
                    return token_id
            if not record["candidates_tested"]:
                # An empty/NaN-only ranking is a backend problem, not proof of
                # an exhausted set of finite legal candidates.
                raise ConstrainedDecodingError("No finite candidate token scores are available")
            record["outcome"] = "dead_end"
            raise NoLegalTokenError(f"No legal next token: {last_reason}")
        finally:
            duration = time.perf_counter() - started
            record["elapsed_seconds"] = duration
            record["rejection_records_truncated"] = max(0, record["candidate_rejections"] - len(record["rejections"]))
            self.candidate_search_steps += 1
            self.candidate_search_seconds += duration
            self.candidate_search_max_seconds = max(self.candidate_search_max_seconds, duration)
            self.candidate_tests_max_per_step = max(self.candidate_tests_max_per_step, record["candidates_tested"])
            # Store scalars separately so retaining early-step work does not
            # retain its rejection strings or alter the ranked search. This
            # finally block also records dead ends, deadlines and exceptions.
            self._candidate_step_metrics.append({key: record[key] for key in (
                "step", "generated_length", "candidates_tested", "candidate_rejections",
                "last_candidate_token_id", "selected_candidate_token_id", "outcome",
                "last_probe_seconds", "max_probe_seconds", "candidate_probe_seconds",
                "elapsed_seconds",
            )})
            self._candidate_steps.append(record)

    def candidate_search_trace(self) -> dict:
        """Retain all step measurements and bound only detailed reason logs."""
        return {
            "schema_version": 2, "step_limit": CANDIDATE_TRACE_STEP_LIMIT,
            "rejections_per_step_limit": CANDIDATE_TRACE_REJECTION_LIMIT,
            "reason_character_limit": CANDIDATE_TRACE_REASON_CHARACTERS,
            "step_count": self.candidate_search_steps,
            # The legacy steps fields still describe the recent detailed
            # records; they do not describe measurement coverage.
            "steps_truncated": max(0, self.candidate_search_steps - len(self._candidate_steps)),
            "truncation_scope": "steps/rejections detail only; step_metrics covers every attempted search",
            "step_metrics_truncated": 0,
            "step_metrics": deepcopy(self._candidate_step_metrics),
            "token_offsets": "generated_length excludes prompt and unselected proposals",
            "steps": deepcopy(list(self._candidate_steps)),
        }

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
        if terminated and self._trace_enabled and self._closed_token_ids != ids:
            # can_end/probe_token also call finish speculatively. Only this
            # actual EOS path may attest global closure. Reuse the existing
            # result; telemetry must not add semantic evaluations or masks.
            self._mechanism_events.append({
                "event": "project_obligations_closed", "token_start": len(ids) - 1, "token_end": len(ids),
                "character_offset": len(self.text), "pending_report_count": 0,
                "verification_scope": "implemented_bundle_obligations",
            })
            self._closed_token_ids = ids
        return report


class JointLogitsProcessor:
    """Apply the real CFG mask, then semantic filtering in the same greedy step.

    Keeping only the highest-scoring legal token is equivalent to masking every
    illegal token followed by greedy argmax.  It is NOT a sampling distribution,
    so the service explicitly rejects sampling, batching, and beam search.
    """

    def __init__(self, engine: Any, tokenizer: Any, prompt_token_ids: Sequence[int],
                 eos_token_ids: Iterable[int], *, syntax_processor: Any,
                 byte_tokenizer: Any = None, num_beams: int = 1, mask_role: str = "aadl"):
        if num_beams != 1:
            raise ValueError("Only num_beams=1 is supported")
        self.prompt_token_ids = tuple(int(t) for t in prompt_token_ids)
        if not callable(syntax_processor):
            raise ValueError("A real CFG logits processor is required")
        self.syntax_processor = syntax_processor
        self.mask_role = mask_role
        byte_tokenizer = byte_tokenizer if byte_tokenizer is not None else getattr(syntax_processor, "llg_tokenizer", None)
        self.bridge = TokenBridge(engine, tokenizer, eos_token_ids, byte_tokenizer=byte_tokenizer)
        self.steps = 0
        self.syntax_mask_steps = 0
        self.syntax_mask_seconds = 0.0
        self.semantic_filter_seconds = 0.0
        self.synchronization_seconds = 0.0
        self.ranking_seconds = 0.0
        self.score_transfer_seconds = 0.0
        self._last_generated_length: int | None = None
        # None means no backend history has been observed. This is separate
        # from bridge.token_ids: synchronizing an actual token can itself raise
        # a parser/resource error before the bridge commits it.
        self.selected_token_ids: tuple[int, ...] | None = None
        # Preserve the final attempted mask step, including a dead end. Null
        # counts mean that stage was not reached; they must not imply zero.
        self.last_step: dict[str, int | None] | None = None

    def evidence(self) -> dict:
        """Measured counters, including a final failed step when one occurred."""
        evidence = {"mask_steps": self.steps, "syntax_mask_steps": self.syntax_mask_steps if self.mask_role == "aadl" else 0,
                "transport_mask_steps": self.syntax_mask_steps if self.mask_role == "transport" else 0,
                "semantic_candidates_tested": self.bridge.candidates_tested,
                "semantic_rejections": self.bridge.rejections,
                "syntax_mask_seconds": self.syntax_mask_seconds,
                "semantic_filter_seconds": self.semantic_filter_seconds,
                "synchronization_seconds": self.synchronization_seconds,
                "ranking_seconds": self.ranking_seconds,
                "score_transfer_seconds": self.score_transfer_seconds,
                "token_decode_seconds": self.bridge.token_decode_seconds,
                "token_decode_calls": self.bridge.token_decode_calls,
                "candidate_search_seconds": self.bridge.candidate_search_seconds,
                "candidate_search_steps": self.bridge.candidate_search_steps,
                "candidate_search_max_seconds": self.bridge.candidate_search_max_seconds,
                "candidate_tests_max_per_step": self.bridge.candidate_tests_max_per_step}
        evidence["last_step"] = dict(self.last_step) if self.last_step is not None else None
        evidence["mechanism_trace"] = self.bridge.mechanism_trace()
        evidence["candidate_search_trace"] = self.bridge.candidate_search_trace()
        evidence["timing_scope"] = {
            "schema_version": 1,
            "candidate_selection_seconds": "inclusive of ranking_seconds and candidate_search_seconds",
            "semantic_seconds": "prefix_parse_seconds plus attribute_evaluation_seconds",
            "attribute_evaluation_seconds": "includes attribute_setup_seconds, semantic_equation_seconds, registered_constraint_seconds",
            "synchronization_seconds": "may include token decoding, parsing and attribute work when replay is required",
            "token_decode_seconds": "all bridge decodes, including candidate probes and synchronization",
            "score_transfer_seconds": "raw-score host transfer/finite-count scan; separate from CFG-mask transfer",
            "transport_prefix_seconds": "engine container probes, including incomplete UTF-8; candidate probes overlap search, replay/finalization may also contribute",
        }
        counters = getattr(self.bridge.engine, "counters", {})
        evidence.update(counters)
        # The historic semantic_filter_seconds includes sorting, byte decoding,
        # envelope checks and CFG probes. Keep it for compatibility, but expose
        # disjoint measurements so GCD/plain cannot report fake semantic work.
        evidence["candidate_selection_seconds"] = self.semantic_filter_seconds
        evidence["transport_mask_seconds"] = (self.syntax_mask_seconds if self.mask_role == "transport" else 0.0) + counters.get("transport_prefix_seconds", 0.0)
        evidence["aadl_syntax_seconds"] = counters.get("aadl_cfg_seconds", 0.0) + (
            self.syntax_mask_seconds if self.mask_role == "aadl" else 0.0)
        evidence["semantic_seconds"] = counters.get("semantic_seconds", 0.0)
        if self.mask_role == "transport":
            # Envelope rejections and CFG rejections are not semantic rejections.
            evidence["candidate_rejections"] = self.bridge.rejections
            evidence["semantic_rejections"] = counters.get("attribute_conflicts", 0)
            evidence["semantic_candidates_tested"] = counters.get("semantic_evaluations", 0)
        return evidence

    def __call__(self, input_ids: Any, scores: Any) -> Any:
        if len(input_ids.shape) != 2 or input_ids.shape[0] != 1 or scores.shape[0] != 1:
            raise ValueError("Joint constraints support exactly one sequence; batches/beams are unsupported")
        actual = tuple(int(t) for t in input_ids[0].tolist())
        prefix_size = len(self.prompt_token_ids)
        if actual[:prefix_size] != self.prompt_token_ids:
            raise ConstrainedDecodingError("Inference backend changed the prompt")
        generated_length = len(actual) - prefix_size
        self.last_step = {"generated_length": generated_length,
                          "raw_finite_count": None, "syntax_finite_count": None}
        expected_length = 0 if self._last_generated_length is None else self._last_generated_length + 1
        if generated_length != expected_length:
            raise ConstrainedDecodingError("CFG state requires exactly one new selected token per greedy step")
        selected = actual[prefix_size:]
        if self.selected_token_ids is not None and selected[:len(self.selected_token_ids)] != self.selected_token_ids:
            raise ConstrainedDecodingError("Inference backend replaced selected token history")
        self.selected_token_ids = selected
        started = time.perf_counter()
        try:
            self.bridge.synchronize(selected)
        finally:
            self.synchronization_seconds += time.perf_counter() - started
        self._last_generated_length = generated_length
        started = time.perf_counter()
        try:
            raw_values = scores[0].tolist()
            self.last_step["raw_finite_count"] = sum(math.isfinite(float(value)) for value in raw_values)
            if not self.last_step["raw_finite_count"]:
                raise ConstrainedDecodingError("No finite candidate token scores are available")
        finally:
            self.score_transfer_seconds += time.perf_counter() - started
        started = time.perf_counter()
        try:
            # This stateful Outlines call happens ONCE per actual generation step,
            # never while probing candidate tokens. It consumes the selected token.
            syntax_scores = self.syntax_processor(input_ids, scores.clone())
            self.syntax_mask_steps += 1
            # This transfer also waits for an asynchronous GPU mask to complete,
            # so the recorded wall time includes its execution, not just dispatch.
            values = syntax_scores[0].tolist()
            self.last_step["syntax_finite_count"] = sum(math.isfinite(float(value)) for value in values)
            for matcher in getattr(self.syntax_processor, "ll_matchers", []):
                if error := matcher.get_error():
                    raise ConstrainedDecodingError(f"llguidance matcher failed: {error}")
        finally:
            self.syntax_mask_seconds += time.perf_counter() - started
        # Transfer the complete ranking, not a fixed top-k slice: low-ranked legal
        # tokens must remain reachable even when every high-ranked token is invalid.
        started = time.perf_counter()
        try:
            ranking_started = time.perf_counter()
            try:
                ranked = syntax_scores[0].argsort(descending=True).tolist()
                finite = [token for token in ranked if math.isfinite(float(values[token]))]
            finally:
                self.ranking_seconds += time.perf_counter() - ranking_started
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
