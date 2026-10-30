"""Optional Transformers service with genuine pre-selection joint constraints.

Install the ``server`` extra to run this module.  Importing it for configuration or
hash helpers does not import Torch, Transformers, FastAPI, or load any model.
"""

import argparse
import asyncio
import codecs
import hashlib
import json
import os
import platform
import threading
import time
import uuid
from contextlib import asynccontextmanager
from dataclasses import asdict, dataclass, is_dataclass
from pathlib import Path
from importlib.metadata import version
from typing import Any

from .decoder import (ConstrainedDecodingError, JointLogitsProcessor, NoLegalTokenError,
                      RepeatedLineDetector, repetition_stop_configuration)
from .semantic_types import RuleDefinitionError, ParseResourceError
from .project import PROJECT_GRAMMAR, coerce_context
from . import __version__


class RuleMismatchError(ValueError):
    """The request was bound to a different rule/runtime deployment."""


class InferenceInfrastructureError(RuntimeError):
    """A stopped worker's failure, with measured partial output when available."""

    def __init__(self, status_code: int, payload: dict):
        super().__init__(payload["error"]["message"])
        self.status_code, self.payload = status_code, payload


def canonical_constraint_hash(spec: dict) -> str:
    """Hash the exact wire constraint, including its kind and profile."""
    canonical = json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def server_code_sha256() -> str:
    """Fingerprint runtime Python sources separately from the external rule bundle."""
    digest = hashlib.sha256()
    for path in sorted(Path(__file__).parent.glob("*.py")):
        digest.update(path.name.encode("utf-8") + b"\0")
        digest.update(path.read_bytes() + b"\0")
    return digest.hexdigest()


def _json_value(value: Any) -> Any:
    if is_dataclass(value):
        return _json_value(asdict(value))
    if isinstance(value, dict):
        return {str(key): _json_value(item) for key, item in value.items()}
    if isinstance(value, (tuple, list, set, frozenset)):
        return [_json_value(item) for item in value]
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    return str(value)


@dataclass(frozen=True)
class ServerConfig:
    model_path: str
    model_name: str | None = None
    context_limit: int | None = None
    generation_timeout: float = 600.0
    device_map: str | None = "auto"
    local_files_only: bool = True
    max_new_tokens: int = 8192
    rules_path: str | None = None

    def __post_init__(self):
        if not self.model_path:
            raise ValueError("Set --model-path or AADL_MODEL_PATH")
        if self.generation_timeout <= 0 or self.max_new_tokens < 1:
            raise ValueError("Timeout and maximum token count must be positive")
        if self.context_limit is not None and self.context_limit < 1:
            raise ValueError("Context limit must be positive")


class TransformersRuntime:
    """One loaded model, one inference lock, and request-local constraint state."""

    def __init__(self, config: ServerConfig):
        from .rules import load_bundle, RUNTIME_VERSION

        self.config = config
        self.bundle = load_bundle(config.rules_path)
        if self.bundle.runtime_version != RUNTIME_VERSION:
            raise ValueError(f"This server requires rule bundle runtime_version={RUNTIME_VERSION}")
        # Validate predeclared sources before loading a GPU-sized model. This
        # also populates the immutable parse cache shared by request engines.
        self.bundle.create_engine(project=True)
        self.model_name = config.model_name or Path(config.model_path.rstrip("/\\")).name
        self.model = self.tokenizer = self.outlines_model = None
        self.byte_tokenizer = None
        self.context_limit = config.context_limit
        self.lock = threading.Lock()
        self.ready = False
        self.versions: dict = {}
        self.model_identity: dict = {}
        self.tokenizer_identity: dict = {}
        self.source_sha256 = server_code_sha256()
        self._verified_generation_mode: str | None = None

    def load(self) -> None:
        self.versions = {name: version(name) for name in
                         ("outlines", "llguidance", "transformers", "torch", "tokenizers", "lark", "regex")}
        self.versions["python"] = platform.python_version()
        for name, expected in {"outlines": "1.2.6", "llguidance": "1.9.1", "lark": "1.3.1", "regex": "2026.4.4"}.items():
            if self.versions[name] != expected:
                raise RuntimeError(f"This runtime requires {name}=={expected}; found {self.versions[name]}")
        import torch
        import outlines
        import llguidance
        from outlines.backends.llguidance import LLGuidanceBackend
        from transformers import AutoModelForCausalLM, AutoTokenizer

        grammar_spec = llguidance.LLMatcher.grammar_from_lark(self.bundle.grammar)
        if error := llguidance.LLMatcher.validate_grammar(grammar_spec):
            raise ValueError(f"Rule bundle CFG is not accepted by llguidance: {error}")
        self.torch = torch
        self.tokenizer = AutoTokenizer.from_pretrained(
            self.config.model_path, local_files_only=self.config.local_files_only, use_fast=True
        )
        self.model = AutoModelForCausalLM.from_pretrained(
            self.config.model_path,
            torch_dtype="auto",
            device_map=self.config.device_map,
            local_files_only=self.config.local_files_only,
        ).eval()
        if self.context_limit is None:
            limits = [getattr(self.model.config, "max_position_embeddings", None),
                      getattr(self.tokenizer, "model_max_length", None)]
            # Tokenizers sometimes use a huge sentinel to mean "unknown".
            real_limits = [int(n) for n in limits if isinstance(n, int) and 0 < n < 10**9]
            if not real_limits:
                raise ValueError("Cannot infer context size; set --context-limit")
            self.context_limit = min(real_limits)
        self.outlines_model = outlines.from_transformers(self.model, self.tokenizer)
        self.llguidance = llguidance
        # A shared backend caches the expensive byte tokenizer. Each request still
        # gets a NEW stateful logits processor and matcher; no grammar state leaks.
        self.constraint_backend = LLGuidanceBackend(self.outlines_model)
        self.cfg_processor_factory = self.constraint_backend.get_cfg_logits_processor
        self.json_processor_factory = self.constraint_backend.get_json_schema_logits_processor
        cfg = self.cfg_processor_factory(grammar=self.bundle.grammar)
        if not callable(getattr(cfg.llg_tokenizer, "decode_bytes", None)):
            raise RuntimeError("llguidance byte tokenizer does not implement decode_bytes")
        self.byte_tokenizer = cfg.llg_tokenizer
        # Compile both bundled formats at startup. Dependency/grammar failures
        # abort startup instead of advertising an unavailable constraint backend.
        json_processor = self.json_processor_factory(json_schema=json.dumps(self.bundle.planning_schema))
        for processor in (cfg, json_processor):
            matcher = llguidance.LLMatcher(processor.llg_tokenizer, processor.grammar)
            if error := matcher.get_error():
                raise ValueError(f"Rule bundle matcher initialization failed: {error}")
        model_config = _json_value(self.model.config.to_dict())
        self.model_identity = {
            "name": self.model_name, "source": self.config.model_path,
            "config_sha256": canonical_constraint_hash(model_config),
            "revision": getattr(self.model.config, "_commit_hash", None),
            "weight_files": self._weight_manifest(),
            "fingerprint_kind": "configuration-and-file-metadata",
        }
        tokenizer_description = {"backend": json.loads(self.tokenizer.backend_tokenizer.to_str()),
                                 "special_tokens": _json_value(self.tokenizer.special_tokens_map)}
        self.tokenizer_identity = {
            "class": type(self.tokenizer).__name__, "source": self.tokenizer.name_or_path,
            "vocab_size": cfg.llg_tokenizer.vocab_size,
            "sha256": canonical_constraint_hash(tokenizer_description),
        }
        # Verify the installed Transformers merge logic before advertising the
        # decoding mode. Checkpoint defaults must not silently select sampling.
        self._prepare_greedy_config(1)
        self.ready = True

    def _weight_manifest(self) -> list[dict]:
        """Record local artifact provenance without claiming a full weight hash."""
        root = Path(self.config.model_path)
        if not root.is_dir():
            return []  # Hub sources are identified by source/revision instead.
        files = set(root.glob("*.safetensors")) | set(root.glob("pytorch_model*.bin"))
        return [{"path": path.relative_to(root).as_posix(), "size_bytes": path.stat().st_size,
                 "mtime_ns": path.stat().st_mtime_ns} for path in sorted(files)]

    def capabilities(self) -> dict:
        kinds = ["plain", "aadl_joint", "json_schema"] if self.ready else []
        return {
            "model": self.model_name,
            "framework": "outlines",
            "backend": "llguidance+joint-semantics",
            "constraint_types": kinds,
            "joint_profiles": [self.bundle.profile],
            "rule_bundle": {"profile": self.bundle.profile, "sha256": self.bundle.digest,
                            "compiled_grammar_sha256": self.bundle.compiled_grammar_sha256},
            "semantic_engine": {"kind": "production_attribute_grammar", "format": "python",
                                "evaluation": "dependency_driven_partial", "unknown": "deferred"},
            "runtime_version": self.bundle.runtime_version,
            "model_identity": self.model_identity,
            "tokenizer_identity": self.tokenizer_identity,
            "versions": self.versions,
            "context_limit": self.context_limit,
            "max_new_tokens": self.config.max_new_tokens,
            "decoding": {"greedy": self._verified_generation_mode == "greedy_search",
                         "generation_mode": self._verified_generation_mode,
                         "configuration_verified": self._verified_generation_mode is not None,
                         "use_model_defaults": False,
                         "batch_size": 1, "num_beams": 1, "streaming": False,
                         "joint_order": ["outlines_transport_mask", "llguidance_aadl_cfg", "python_attributes", "greedy_argmax"],
                         "repetition_stop": repetition_stop_configuration(),
                         "byte_decoder": "llguidance.decode_bytes", "kv_rollback": False},
            "constraint_scope": f"{self.bundle.profile} supported rules; prefix acceptance is not a completion guarantee",
            "server_version": __version__,
            "server_code_sha256": self.source_sha256,
            "project_transport": "AADL-PROJECT 1",
            "dependency_snapshots": True,
        }

    def _eos_ids(self) -> list[int]:
        ids = getattr(self.model.generation_config, "eos_token_id", None)
        ids = list(ids) if isinstance(ids, (tuple, list)) else [ids]
        ids += [getattr(self.tokenizer, "eos_token_id", None)]
        return list(dict.fromkeys(int(value) for value in ids if value is not None))

    def _prepare_greedy_config(self, max_tokens: int):
        """Verify effective settings using the installed model's merge method.

        Transformers 4.51 can replace global default values (including False)
        with checkpoint defaults. Disable that fallback and pass the generation
        mode explicitly, protecting both selection and hidden logits processors.
        """
        from transformers import GenerationConfig

        eos_ids = self._eos_ids()
        if not eos_ids:
            raise ValueError("Model/tokenizer has no EOS token")
        pad_id = self.tokenizer.pad_token_id
        requested = GenerationConfig(
            do_sample=False, num_beams=1, num_return_sequences=1,
            max_new_tokens=max_tokens, eos_token_id=eos_ids,
            pad_token_id=pad_id if pad_id is not None else eos_ids[0],
            bos_token_id=getattr(self.tokenizer, "bos_token_id", None), use_cache=True,
        )
        kwargs = {"use_model_defaults": False, "do_sample": False,
                  "num_beams": 1, "num_return_sequences": 1,
                  # Also prevent legacy token-only fallback for an absent BOS.
                  "bos_token_id": requested.bos_token_id}
        expected = requested.to_dict()
        effective, remaining = self.model._prepare_generation_config(requested, **kwargs)
        mode = effective.get_generation_mode().value
        if remaining or mode != "greedy_search":
            raise RuntimeError("Transformers did not resolve to single-sequence greedy generation")
        # Every public control must match, including forced tokens, suppression,
        # penalties and stopping settings. Metadata does not affect generation.
        for name, value in expected.items():
            if name.startswith("_") or name == "transformers_version":
                continue
            if getattr(effective, name) != value:
                raise RuntimeError(f"Transformers changed the requested generation setting: {name}")
        self._verified_generation_mode = mode
        return effective, kwargs

    def compile_constraint(self, constraint: dict | None, prompt_ids: list[int], *, context=None):
        spec = dict(constraint) if constraint is not None else {"kind": "plain"}
        kind = spec.get("kind")
        project = spec.get("project", False)
        if type(project) is not bool:
            raise ValueError("project must be boolean")
        evidence = {"kind": kind, "sha256": canonical_constraint_hash(spec), "applied": kind != "plain" or project,
                    "rule_bundle_sha256": self.bundle.digest, "runtime_version": self.bundle.runtime_version,
                    "compiled_grammar_sha256": self.bundle.compiled_grammar_sha256,
                    "transport_mask_applied": project, "aadl_cfg_applied": kind == "aadl_joint",
                    "attributes_applied": kind == "aadl_joint"}
        if kind not in {"plain", "aadl_joint", "json_schema"}:
            raise ValueError(f"Unsupported constraint kind: {kind!r}")
        if not self.ready:
            raise RuntimeError("The required Outlines/llguidance backend is not initialized")
        if kind == "plain":
            if set(spec) - {"kind", "project"}:
                raise ValueError("plain constraint accepts kind and project")
            if project:
                from .attribute_runtime import AttributeEngine
                engine = AttributeEngine(self.bundle, context=context, project=True, joint=False)
                cfg = self.cfg_processor_factory(grammar=PROJECT_GRAMMAR)
                processor = JointLogitsProcessor(engine, self.tokenizer, prompt_ids, self._eos_ids(), syntax_processor=cfg, mask_role="transport")
                return [processor], evidence, processor
            return [], evidence, None
        if kind == "aadl_joint":
            if set(spec) - {"kind", "profile", "declarations", "project"} or spec.get("profile") != self.bundle.profile:
                raise ValueError(f"aadl_joint requires profile={self.bundle.profile} and accepts optional declarations only")
            declarations = spec.get("declarations")
            if "declarations" in spec and not isinstance(declarations, list):
                raise ValueError("aadl_joint declarations must be a list")
            from .constraints import JointEngine

            # The wire protocol owns only the list envelope. The selected rule
            # bundle owns its planning schema, normalization, and semantic checks.
            engine = JointEngine(declarations=declarations, bundle=self.bundle, context=context, project=project)
            cfg = self.cfg_processor_factory(grammar=PROJECT_GRAMMAR if project else self.bundle.grammar)
            processor = JointLogitsProcessor(engine, self.tokenizer, prompt_ids, self._eos_ids(), syntax_processor=cfg,
                                            mask_role="transport" if project else "aadl")
            evidence.update(profile=self.bundle.profile, framework="outlines", backend="llguidance+joint-semantics",
                            scope="supported bundle rules", semantic_engine="production_attribute_grammar")
            return [processor], evidence, processor
        if set(spec) != {"kind", "schema"} or not isinstance(spec["schema"], dict):
            raise ValueError("json_schema requires exactly kind and an object schema")
        processor = self.json_processor_factory(json_schema=json.dumps(spec["schema"], ensure_ascii=False))
        evidence.update(backend="llguidance", framework="outlines")
        return [processor], evidence, None

    def _infrastructure_failure(self, error, *, context, evidence, joint,
                                prompt_count, generated, generation_started):
        """Preserve selected tokens without counting speculative mask probes.

        ``generated=None`` is unknown consumption, not zero. A returned model
        tensor has priority over the last processor call because stopping
        criteria run after selecting the last token, without another mask call.
        """
        timeout = isinstance(error, TimeoutError)
        payload = {
            "id": "chatcmpl-" + uuid.uuid4().hex,
            "object": "chat.completion", "created": int(time.time()),
            "model": self.model_name,
            "error": {"type": "generation_timeout" if timeout else "decoder_infrastructure",
                      "exception": type(error).__name__, "message": str(error), "retryable": False},
            "rule_bundle_sha256": self.bundle.digest,
            "runtime_version": self.bundle.runtime_version,
            "compiled_grammar_sha256": self.bundle.compiled_grammar_sha256,
            "context_sha256": context.sha256,
            "model_identity": self.model_identity, "tokenizer_identity": self.tokenizer_identity,
        }
        measured = dict(evidence)
        if joint is not None:
            measured.update(joint.evidence())
        if generation_started is not None:
            measured["generation_seconds"] = time.perf_counter() - generation_started
        if measured:
            payload["constraint"] = measured
        if generated is not None:
            ids = tuple(int(token) for token in generated)
            payload["selected_token_ids"] = list(ids)
            if prompt_count is not None:
                payload["usage"] = {"prompt_tokens": prompt_count, "completion_tokens": len(ids),
                                    "total_tokens": prompt_count + len(ids)}
            eos_ids = self._eos_ids()
            text_ids = ids[:-1] if ids and ids[-1] in eos_ids else ids
            byte_tokenizer = joint.bridge.byte_tokenizer if joint is not None else self.byte_tokenizer
            if byte_tokenizer is not None:
                raw = byte_tokenizer.decode_bytes(list(text_ids))
                if not isinstance(raw, (bytes, bytearray)):
                    raise TypeError("decode_bytes must return bytes")
                decoder = codecs.getincrementaldecoder("utf-8")("strict")
                try:
                    text = decoder.decode(bytes(raw), final=False)
                    pending = decoder.getstate()[0]
                    if pending:
                        payload["partial_utf8_tail_hex"] = pending.hex()
                except UnicodeDecodeError as decode_error:
                    # Keep the exact valid prefix, never replacement characters.
                    # The remaining bytes are explicit diagnostic evidence.
                    text = bytes(raw[:decode_error.start]).decode("utf-8", "strict")
                    payload["partial_utf8_tail_hex"] = bytes(raw[decode_error.start:]).hex()
                    payload["partial_decode_error"] = str(decode_error)
                payload["choices"] = [{"index": 0, "message": {"role": "assistant", "content": text},
                                       "finish_reason": "error"}]
        # The endpoint keeps HTTP failure semantics. This cannot enter the
        # retryable constraint_failure/dead_end protocol, even with valid usage.
        return InferenceInfrastructureError(504 if timeout else 500, payload)

    def generate(self, request: Any) -> dict:
        """Run synchronously in FastAPI's worker while retaining the model lock.

        A timeout is cooperative, checked between generation steps.  We never
        release the lock because an HTTP waiter stopped waiting: a running CUDA
        operation or worker thread cannot safely be cancelled that way.
        """
        from transformers import StoppingCriteria, StoppingCriteriaList

        if self.model is None:
            raise RuntimeError("Model is not loaded")
        if request.runtime_version != self.bundle.runtime_version:
            raise RuleMismatchError("runtime_version does not match this server")
        if request.rule_bundle_sha256 != self.bundle.digest:
            raise RuleMismatchError("rule_bundle_sha256 does not match this server")
        if request.compiled_grammar_sha256 != self.bundle.compiled_grammar_sha256:
            raise RuleMismatchError("compiled_grammar_sha256 does not match this server")
        context = coerce_context(request.context)
        if request.context_sha256 != context.sha256:
            raise RuleMismatchError("context_sha256 does not match the dependency snapshot")
        if not self.lock.acquire(blocking=False):
            raise BlockingIOError("Model is busy")
        joint, output, prompt_count, generation_started = None, None, None, None
        evidence = {}
        try:
            inputs = self.tokenizer.apply_chat_template(
                request.messages, tokenize=True, add_generation_prompt=True,
                return_dict=True, return_tensors="pt", enable_thinking=False,
            )
            if inputs["input_ids"].shape[0] != 1:
                raise ValueError("Exactly one input sequence is supported")
            prompt_ids = inputs["input_ids"][0].tolist()
            prompt_count = len(prompt_ids)
            if prompt_count + request.max_tokens > self.context_limit:
                raise ValueError(f"Context limit exceeded: {prompt_count}+{request.max_tokens}>{self.context_limit}")
            processors, evidence, joint = self.compile_constraint(request.constraint, prompt_ids, context=context)
            evidence["context_sha256"] = context.sha256
            device = self.model.get_input_embeddings().weight.device
            inputs = {key: value.to(device) for key, value in inputs.items()}
            deadline = time.monotonic() + self.config.generation_timeout
            if joint is not None:
                joint.bridge.deadline = deadline
            generation_started = time.perf_counter()

            class DeadlineStop(StoppingCriteria):
                timed_out = False

                def __call__(self, input_ids, scores, **kwargs):
                    self.timed_out = time.monotonic() >= deadline
                    return self.timed_out

            stopper = DeadlineStop()
            eos_ids = self._eos_ids()
            repetition = RepeatedLineDetector()
            byte_decoder = self.byte_tokenizer

            class RepetitionStop(StoppingCriteria):
                def __call__(self, input_ids, scores, **kwargs):
                    # HF invokes stopping criteria after selection. Inspect the
                    # actual output, including its newest token; never a probe.
                    actual = tuple(int(token) for token in input_ids[0].tolist())
                    if actual[:prompt_count] != tuple(prompt_ids):
                        raise ConstrainedDecodingError("Stopping criterion observed a changed prompt")
                    ids = actual[prompt_count:]
                    if ids and ids[-1] in eos_ids:
                        ids = ids[:-1]
                    raw = byte_decoder.decode_bytes(list(ids))
                    decoder = codecs.getincrementaldecoder("utf-8")("strict")
                    try:
                        text = decoder.decode(raw, final=False)
                    except UnicodeDecodeError:
                        # This heuristic cannot interpret invalid bytes. Their
                        # existing tokenizer/constraint checks remain responsible.
                        return False
                    return repetition.observe(text)

            generation_config, generation_kwargs = self._prepare_greedy_config(request.max_tokens)
            evidence.update(generation_mode=self._verified_generation_mode,
                            generation_config_verified=True, use_model_defaults=False)
            constraint_failure = None
            try:
                with self.torch.inference_mode():
                    output = self.model.generate(
                        **inputs, generation_config=generation_config,
                        **generation_kwargs,
                        logits_processor=processors,
                        stopping_criteria=StoppingCriteriaList([stopper, RepetitionStop()]),
                    )
            except NoLegalTokenError as exc:
                if joint is None:
                    raise
                # The failed processor call has already synchronized actual
                # input_ids. Its candidate probes were never committed, so this
                # bridge history counts selected tokens without charging trials.
                constraint_failure = {"code": "dead_end", "message": str(exc)}
            # The worker has now stopped.  Only now may a timeout release its lock.
            if stopper.timed_out or time.monotonic() >= deadline:
                raise TimeoutError("Generation exceeded its cooperative deadline; worker has now stopped")
            validation = None
            if constraint_failure is not None:
                generated = list(joint.bridge.token_ids)
                text = joint.bridge.text
                report = joint.bridge.engine.finish(joint.bridge.state)
                validation = {"valid": False,
                              "diagnostics": [*_json_value(report.diagnostics), constraint_failure["message"]],
                              "pending": _json_value(report.pending)}
                finish_reason = "constraint_error"
            else:
                generated = output[0][prompt_count:].tolist()
                stopped = bool(generated and generated[-1] in eos_ids)
                text_ids = generated[:-1] if stopped else generated
                finish_reason = "stop" if stopped and repetition.termination is None else "length"
            if joint is not None and constraint_failure is None:
                if stopped:
                    # EOS is a claim that generation completed, so keep its
                    # strict UTF-8 and closed-obligation checks.
                    report = joint.bridge.finalize(generated)
                else:
                    # A token limit can split a Unicode character across byte
                    # tokens. Preserve the actual selected-token count and the
                    # valid text prefix instead of turning truncation into an
                    # unmetered HTTP error. Incomplete bytes cannot be success.
                    joint.bridge.synchronize(generated)
                    report = joint.bridge.engine.finish(joint.bridge.state)
                text = joint.bridge.text
                diagnostics = _json_value(report.diagnostics)
                if joint.bridge.pending_bytes:
                    diagnostics.append("Token limit reached with an incomplete Unicode byte sequence")
                validation = {"valid": bool(report.valid) and not bool(report.pending) and not bool(joint.bridge.pending_bytes),
                              "diagnostics": diagnostics,
                              "pending": _json_value(report.pending)}
            elif joint is None:
                text = self.tokenizer.decode(text_ids, skip_special_tokens=True,
                                             clean_up_tokenization_spaces=False)
            if joint is not None:
                evidence.update(joint.evidence())
            evidence["generation_seconds"] = time.perf_counter() - generation_started
            completion_count = len(generated)
            response = {
                "id": "chatcmpl-" + uuid.uuid4().hex,
                "object": "chat.completion",
                "created": int(time.time()),
                "model": self.model_name,
                "rule_bundle_sha256": self.bundle.digest,
                "runtime_version": self.bundle.runtime_version,
                "compiled_grammar_sha256": self.bundle.compiled_grammar_sha256,
                "context_sha256": context.sha256,
                "model_identity": self.model_identity,
                "tokenizer_identity": self.tokenizer_identity,
                # Re-tokenizing response text may produce a different BPE
                # sequence. Retain actual choices for exact matcher replay,
                # including EOS when selected, just as completion_tokens does.
                "selected_token_ids": [int(token) for token in generated],
                "choices": [{"index": 0, "message": {"role": "assistant", "content": text},
                             "finish_reason": finish_reason}],
                "usage": {"prompt_tokens": prompt_count, "completion_tokens": completion_count,
                          "total_tokens": prompt_count + completion_count},
                "constraint": evidence,
            }
            if validation is not None:
                response["validation"] = validation
            if constraint_failure is None and repetition.termination is not None:
                # Metered truncation uses the same token accounting and repair
                # budget as max_tokens. It is neither a semantic conflict nor a
                # worker fault, and does not change the logits/masks in any mode.
                response["termination"] = repetition.termination
            if constraint_failure is not None:
                # No top-level `error`: this is a metered generation outcome that
                # the same agent may retry within its remaining total budget.
                response["constraint_failure"] = constraint_failure
            return response
        except (RuleDefinitionError, ParseResourceError, TimeoutError, ConstrainedDecodingError) as exc:
            generated = (output[0][prompt_count:].tolist() if output is not None else
                         joint.selected_token_ids if joint is not None else None)
            raise self._infrastructure_failure(exc, context=context, evidence=evidence, joint=joint,
                prompt_count=prompt_count, generated=generated, generation_started=generation_started) from exc
        finally:
            self.lock.release()


def create_app(config: ServerConfig, runtime: TransformersRuntime | None = None):
    """Build the optional HTTP app; all heavyweight imports stay behind this call."""
    from fastapi import FastAPI, HTTPException
    from fastapi.responses import JSONResponse
    from pydantic import BaseModel, ConfigDict, Field

    runtime = runtime or TransformersRuntime(config)

    class ChatRequest(BaseModel):
        model_config = ConfigDict(extra="forbid")
        model: str | None = None
        messages: list[dict[str, str]] = Field(min_length=1)
        max_tokens: int = Field(default=2048, ge=1)
        stream: bool = False
        temperature: float = 0.0
        n: int = 1
        num_beams: int = 1
        constraint: dict | None = None
        rule_bundle_sha256: str = Field(pattern="^[0-9a-f]{64}$")
        runtime_version: str
        compiled_grammar_sha256: str = Field(pattern="^[0-9a-f]{64}$")
        context: dict | None = None
        context_sha256: str = Field(pattern="^[0-9a-f]{64}$")

    @asynccontextmanager
    async def lifespan(app):
        if not runtime.ready:
            await asyncio.to_thread(runtime.load)
        yield

    app = FastAPI(title="AADL single-agent constrained generation", version=__version__, lifespan=lifespan)
    app.state.runtime = runtime

    @app.get("/health")
    def health():
        return {"status": "ok" if runtime.ready else "loading",
                "model": runtime.model_name, "context_limit": runtime.context_limit,
                "busy": runtime.lock.locked(), "runtime_version": runtime.bundle.runtime_version,
                "rule_bundle_sha256": runtime.bundle.digest}

    @app.get("/v1/models")
    def models():
        return {"object": "list", "data": [{"id": runtime.model_name, "object": "model",
                                             "owned_by": "local"}]}

    @app.get("/v1/capabilities")
    def capabilities():
        return runtime.capabilities()

    @app.post("/v1/chat/completions")
    def chat(request: ChatRequest):
        if not runtime.ready:
            raise HTTPException(503, "Model is loading")
        if request.model is not None and request.model != runtime.model_name:
            raise HTTPException(404, f"This deployment serves {runtime.model_name}")
        if request.stream or request.temperature != 0 or request.n != 1 or request.num_beams != 1:
            raise HTTPException(422, "Only non-streaming, single-sequence greedy decoding is supported")
        if request.max_tokens > config.max_new_tokens:
            raise HTTPException(422, f"max_tokens exceeds server limit {config.max_new_tokens}")
        if any(set(message) != {"role", "content"} or
               message["role"] not in {"system", "user", "assistant"} for message in request.messages):
            raise HTTPException(422, "Messages require role and textual content only")
        try:
            return runtime.generate(request)
        except InferenceInfrastructureError as exc:
            return JSONResponse(status_code=exc.status_code, content=exc.payload)
        except RuleMismatchError as exc:
            # Discovery and generation are separate requests: a deployment may
            # change between them. Preserve this distinction with a stable code
            # instead of requiring clients to interpret human-readable wording.
            raise HTTPException(409, {"code": "rule_mismatch", "message": str(exc)}) from exc
        except BlockingIOError as exc:
            raise HTTPException(429, str(exc)) from exc
        except TimeoutError as exc:
            raise HTTPException(504, str(exc)) from exc
        except (RuleDefinitionError, ParseResourceError) as exc:
            # A broken attribute equation is an implementation/rule fault, not
            # an invalid model that another LLM attempt can repair.
            raise HTTPException(500, {"type": "attribute_grammar_error", "message": str(exc)}) from exc
        except ConstrainedDecodingError as exc:
            raise HTTPException(500, {"type": "decoder_infrastructure", "message": str(exc)}) from exc
        except ValueError as exc:
            raise HTTPException(422, {"type": type(exc).__name__, "message": str(exc)}) from exc

    return app


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Serve one local Transformers model with joint AADL constraints")
    parser.add_argument("--model-path", default=os.environ.get("AADL_MODEL_PATH"))
    parser.add_argument("--model-name", default=os.environ.get("AADL_MODEL_NAME"))
    parser.add_argument("--rules", default=os.environ.get("AADL_RULES_PATH"), help="Validated rule bundle directory")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--context-limit", type=int, default=None)
    parser.add_argument("--timeout", type=float, default=600.0)
    parser.add_argument("--max-new-tokens", type=int, default=8192)
    parser.add_argument("--device-map", default="auto", help="Transformers device map, e.g. auto or cpu")
    parser.add_argument("--allow-download", action="store_true", help="Allow downloads for an explicitly named model")
    args = parser.parse_args(argv)
    if not args.model_path:
        parser.error("--model-path or AADL_MODEL_PATH is required")
    config = ServerConfig(model_path=args.model_path, model_name=args.model_name,
                          context_limit=args.context_limit, generation_timeout=args.timeout,
                          device_map=args.device_map, local_files_only=not args.allow_download,
                          max_new_tokens=args.max_new_tokens, rules_path=args.rules)
    try:
        import uvicorn
        app = create_app(config)
    except ImportError as exc:
        parser.error(f"Install the server extra first: {exc}")
    # One process owns one model. Multiple uvicorn workers would duplicate memory
    # and bypass the single shared inference lock promised by this deployment.
    uvicorn.run(app, host=args.host, port=args.port, workers=1)


if __name__ == "__main__":
    main()
