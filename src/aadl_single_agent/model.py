"""URL-only access to the versioned AADL inference service.

Every request, including an ordinary completion, binds the actual rule bundle.
This prevents experiments from silently mixing local and server rule revisions.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from copy import deepcopy
import hashlib
import json
import math
import re
from typing import Any
from urllib.parse import urlsplit, urlunsplit

import httpx
from .project import coerce_context, GENERATED_FILE_COUNT, PROJECT_GRAMMAR
from .generation_protocol import GENERATION_PROTOCOL_VERSION


class ModelError(RuntimeError):
    """Discovery, transport, or generation failed without a usable result."""

    def __init__(
        self, message: str, *, usage: dict[str, int] | None = None,
        finish_reason: str | None = None, partial_text: str | None = None,
        retryable: bool = False, applied_constraint: dict[str, Any] | None = None,
        error_kind: str = "model_protocol",
        response_metadata: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        # None means unknown consumption, never an assertion of zero cost.
        # ModelClient only attaches counts that passed its strict usage parser.
        self.usage = dict(usage) if usage is not None else None
        self.finish_reason = finish_reason
        self.partial_text = partial_text
        self.retryable = retryable
        self.applied_constraint = dict(applied_constraint) if applied_constraint is not None else None
        self.error_kind = error_kind
        # Error envelopes are retained for diagnosis, not treated as verified
        # sampling evidence or permission to retry an infrastructure failure.
        self.response_metadata = deepcopy(response_metadata or {})


@dataclass(frozen=True)
class EndpointInfo:
    base_url: str
    model: str
    constraint_types: set[str]
    backend: str
    rule_bundle_sha256: str = ""
    runtime_version: str = ""
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class GenerationReply:
    text: str
    usage: dict[str, int]
    finish_reason: str
    applied_constraint: dict[str, Any] | None
    model: str
    metadata: dict[str, Any] = field(default_factory=dict)


def constraint_hash(constraint: dict[str, Any]) -> str:
    """Hash the complete request, using the same canonical JSON as the server."""
    try:
        canonical = json.dumps(
            constraint, sort_keys=True, separators=(",", ":"),
            ensure_ascii=False, allow_nan=False,
        )
    except (TypeError, ValueError) as exc:
        raise ModelError("Constraint must be JSON serializable.") from exc
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class ModelClient:
    """Discover a model automatically; changing the URL invalidates discovery."""

    def __init__(
        self, base_url: str, timeout: float = 660,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if timeout <= 0:
            raise ModelError("timeout must be positive.")
        # The service's cooperative generation deadline is 600 seconds. Allow
        # time for the worker to stop and return its measured failure snapshot.
        self._endpoint: EndpointInfo | None = None
        self._expected_rules: tuple[str, str, str] | None = None
        self._expected_compiled: str | None = None
        self._context = coerce_context()
        self.set_base_url(base_url)
        self._http = httpx.Client(
            timeout=timeout, transport=transport, follow_redirects=False,
            trust_env=False,
        )

    def set_base_url(self, url: str) -> None:
        """Accept a server URL or its /v1 URL, preserving proxy path prefixes."""
        if not isinstance(url, str):
            raise ModelError("base_url must be an HTTP(S) URL.")
        try:
            parsed = urlsplit(url.strip())
            _ = parsed.port  # Access also validates malformed ports.
        except ValueError as exc:
            raise ModelError("base_url is not a valid HTTP(S) URL.") from exc
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                or parsed.query or parsed.fragment or parsed.username
                or parsed.password):
            raise ModelError("base_url must be an HTTP(S) URL without credentials, query, or fragment.")
        path = parsed.path.rstrip("/")
        if path.endswith("/v1"):
            path = path.rsplit("/", 1)[0]
        self.base_url = urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))
        self._endpoint = None

    def bind_rules(self, bundle: Any) -> None:
        """Bind a trusted local bundle; the client never uploads executable rules."""
        self._expected_rules = (bundle.profile, bundle.digest, bundle.runtime_version)
        self._expected_compiled = bundle.compiled_grammar_sha256
        if self._endpoint is not None:
            self._check_rules(self._endpoint)

    def bind_context(self, snapshot=None) -> None:
        """Freeze explicitly supplied dependency text for every call in a run."""
        self._context = coerce_context(snapshot)

    def _check_rules(self, info: EndpointInfo) -> None:
        if self._expected_rules is None:
            return
        limit = info.metadata.get("generated_file_limit")
        if type(limit) is not int or limit != GENERATED_FILE_COUNT:
            raise ModelError("Endpoint does not declare the required single-generated-file transport; deploy the matching server.",
                             error_kind="unsupported")
        # The envelope name is unchanged for old saved outputs. Bind the actual
        # transport grammar so an old multi-file service cannot reuse that name.
        if info.metadata.get("transport_grammar_sha256") != hashlib.sha256(PROJECT_GRAMMAR.encode("utf-8")).hexdigest():
            raise ModelError("Endpoint transport grammar differs from the local single-file profile.",
                             error_kind="model_protocol")
        profile, digest, runtime = self._expected_rules
        actual_profile = info.metadata.get("rule_bundle", {}).get("profile")
        if (actual_profile, info.rule_bundle_sha256, info.runtime_version) != (profile, digest, runtime):
            raise ModelError(
                "Rule bundle or runtime differs from the server. Deploy the matching rule bundle first.",
                error_kind="rule_mismatch",
            )
        if info.metadata.get("rule_bundle", {}).get("compiled_grammar_sha256") != self._expected_compiled:
            raise ModelError("Compiled grammar differs from the local compiler snapshot.", error_kind="rule_mismatch")

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> ModelClient:
        return self

    def __exit__(self, *args: object) -> None:
        self.close()

    def _request(
        self, method: str, path: str, payload: dict[str, Any] | None = None,
        *, optional_statuses: tuple[int, ...] = (),
    ) -> dict[str, Any] | None:
        try:
            response = self._http.request(method, self.base_url + path, json=payload)
        except httpx.TimeoutException as exc:
            raise ModelError(f"Model endpoint timed out: {method} {path}.", error_kind="infrastructure") from exc
        except httpx.RequestError as exc:
            raise ModelError(f"Cannot reach model endpoint: {method} {path}: {exc}", error_kind="infrastructure") from exc
        if response.status_code in optional_statuses:
            return None
        if not response.is_success:
            metadata = {}
            kind = "invalid_request" if response.status_code in {400, 413, 422} else "infrastructure"
            if path == "/v1/chat/completions":
                try:
                    error_response = response.json()
                    metadata = self._error_metadata(error_response)
                    # A rule reload can race discovery. Use the explicit server
                    # code so this is still a version failure, not a retryable
                    # outage or a guess based on human-readable error wording.
                    detail = error_response.get("detail") if isinstance(error_response, dict) else None
                    if (response.status_code == 409 and isinstance(detail, dict)
                            and detail.get("code") == "rule_mismatch"):
                        kind = "rule_mismatch"
                    elif (response.status_code == 409 and isinstance(detail, dict)
                          and detail.get("code") == "generation_protocol_mismatch"):
                        kind = "model_protocol"
                except ValueError:
                    pass
            raise ModelError(
                f"Model endpoint HTTP {response.status_code} for {method} {path}: "
                f"{response.text[:300]}",
                error_kind=kind, **metadata,
            )
        try:
            data = response.json()
        except ValueError as exc:
            raise ModelError(f"Model endpoint returned invalid JSON for {path}.") from exc
        if not isinstance(data, dict):
            raise ModelError(f"Model endpoint must return a JSON object for {path}.")
        if data.get("error"):
            raise ModelError(f"Model endpoint error for {path}: {data['error']}",
                             **self._error_metadata(data))
        return data

    @staticmethod
    def _copy_info(info: EndpointInfo) -> EndpointInfo:
        # Do not let a caller accidentally mutate the cached capability set.
        return EndpointInfo(info.base_url, info.model, set(info.constraint_types), info.backend,
                            info.rule_bundle_sha256, info.runtime_version, deepcopy(info.metadata))

    def discover(self, force: bool = False) -> EndpointInfo:
        if self._endpoint is not None and not force:
            self._check_rules(self._endpoint)
            return self._copy_info(self._endpoint)
        self._endpoint = None
        capabilities = self._request("GET", "/v1/capabilities")
        assert capabilities is not None
        model = capabilities.get("model")
        kinds = capabilities.get("constraint_types")
        backend = capabilities.get("backend")
        bundle = capabilities.get("rule_bundle")
        runtime = capabilities.get("runtime_version")
        model_identity = capabilities.get("model_identity")
        tokenizer_identity = capabilities.get("tokenizer_identity")
        if capabilities.get("generation_protocol_version") != GENERATION_PROTOCOL_VERSION:
            raise ModelError("Generation protocol differs from this client; deploy a matching service.",
                             error_kind="model_protocol")
        policy = capabilities.get("generation_policy")
        if (not isinstance(policy, dict) or not isinstance(policy.get("repetition_stop"), dict)
                or type(policy.get("deadline_seconds")) not in (int, float)
                or not math.isfinite(policy["deadline_seconds"])
                or policy["deadline_seconds"] <= 0 or policy.get("deadline_retryable") is not False):
            raise ModelError("Missing or invalid generation policy in capabilities.")
        if (not isinstance(model, str) or not model.strip()
                or not isinstance(kinds, list)
                or not all(isinstance(kind, str) and kind for kind in kinds)
                or not isinstance(backend, str) or not backend
                or not isinstance(bundle, dict)
                or not isinstance(bundle.get("profile"), str)
                or not re.fullmatch(r"[0-9a-f]{64}", str(bundle.get("sha256", "")))
                or not isinstance(model_identity, dict) or not model_identity
                or not isinstance(tokenizer_identity, dict) or not tokenizer_identity
                or not isinstance(runtime, str) or not runtime):
            raise ModelError("Invalid /v1/capabilities: model/tokenizer identities, capabilities and rule bundle are required.")
        # The bound rule bundle supplies the required runtime version. Keeping
        # an old format number here would reject a valid new deployment before
        # the explicit version-mismatch diagnostic below can run.
        info = EndpointInfo(self.base_url, model, set(kinds), backend, bundle["sha256"],
                            runtime, deepcopy(capabilities))
        self._check_rules(info)
        self._endpoint = info
        return self._copy_info(info)

    @staticmethod
    def _validate_constraint(constraint: dict[str, Any] | None) -> None:
        if constraint is None:
            return
        if not isinstance(constraint, dict):
            raise ModelError("constraint must be an object or None.")
        kind = constraint.get("kind")
        if kind == "json_schema":
            if not isinstance(constraint.get("schema"), dict):
                raise ModelError("json_schema constraint requires a schema object.")
        elif kind in {"aadl_joint", "aadl_gcd"}:
            if not isinstance(constraint.get("profile"), str) or not constraint["profile"]:
                raise ModelError(f"{kind} constraint requires a profile.")
            if set(constraint) - {"kind", "profile", "project"}:
                raise ModelError(f"{kind} accepts only kind, profile and project; declaration planning is retired.")
        elif kind == "plain":
            if set(constraint) != {"kind", "project"} or constraint.get("project") is not True:
                raise ModelError("Explicit plain constraint requires project=true; omit constraint for raw text.")
        else:
            raise ModelError(f"Unknown constraint kind: {kind!r}.")
        constraint_hash(constraint)

    @staticmethod
    def _usage(data: dict[str, Any]) -> dict[str, int]:
        raw = data.get("usage")
        keys = ("prompt_tokens", "completion_tokens", "total_tokens")
        if not isinstance(raw, dict) or any(
            type(raw.get(key)) is not int or raw[key] < 0 for key in keys
        ):
            raise ModelError("Missing or invalid usage; all three token counts are required.")
        if raw["total_tokens"] != raw["prompt_tokens"] + raw["completion_tokens"]:
            raise ModelError("Invalid usage: total_tokens does not equal prompt + completion tokens.")
        return {key: raw[key] for key in keys}

    @staticmethod
    def _error_metadata(data: Any) -> dict[str, Any]:
        """Preserve measured cost even when the candidate cannot be accepted."""
        if not isinstance(data, dict):
            return {}
        metadata: dict[str, Any] = {}
        metadata["response_metadata"] = {key: deepcopy(value) for key, value in data.items()
                                         if key not in {"choices", "usage"}}
        try:
            metadata["usage"] = ModelClient._usage(data)
        except ModelError:
            # Incomplete or inconsistent usage must remain unknown.
            pass
        choices = data.get("choices")
        if isinstance(choices, list) and choices and isinstance(choices[0], dict):
            choice = choices[0]
            if isinstance(choice.get("finish_reason"), str):
                metadata["finish_reason"] = choice["finish_reason"]
            message = choice.get("message")
            if isinstance(message, dict) and isinstance(message.get("content"), str):
                metadata["partial_text"] = message["content"]
        return metadata

    @staticmethod
    def _verify_evidence(
        data: dict[str, Any], constraint: dict[str, Any],
    ) -> dict[str, Any]:
        evidence = data.get("constraint", data.get("applied_constraint"))
        if (not isinstance(evidence, dict)
                or evidence.get("kind") != constraint["kind"]
                or evidence.get("sha256") != constraint_hash(constraint)
                or evidence.get("applied") is not True):
            raise ModelError("Constraint evidence missing or mismatched: expected exact kind, sha256, and applied=true.")
        if constraint.get("project"):
            limit = evidence.get("generated_file_limit")
            if (type(limit) is not int or limit != GENERATED_FILE_COUNT
                    or evidence.get("transport_grammar_sha256") != hashlib.sha256(PROJECT_GRAMMAR.encode("utf-8")).hexdigest()):
                raise ModelError("Response lacks matching single-file transport evidence.")
            joint = constraint["kind"] == "aadl_joint"
            syntax = constraint["kind"] in {"aadl_joint", "aadl_gcd"}
            if (evidence.get("transport_mask_applied") is not True
                    or evidence.get("aadl_cfg_applied") is not syntax
                    or evidence.get("attributes_applied") is not joint):
                raise ModelError("Transport/AADL/attribute mask evidence does not match the requested path.")
            if not joint and (evidence.get("semantic_evaluations", 0) != 0
                              or evidence.get("attribute_conflicts", 0) != 0):
                raise ModelError("A non-semantic baseline unexpectedly executed semantic rules.")
        return dict(evidence)

    @staticmethod
    def _verify_termination(termination, data, constraint, usage, info) -> None:
        """Accept metered early stops only with an explicit, matching policy.

        Unknown HTTP errors never reach this path. Token IDs are the actual
        selected sequence, not a re-tokenization of a partially decoded string.
        """
        phase = "planning" if constraint and constraint["kind"] == "json_schema" else "generation"
        ids = data.get("selected_token_ids")
        if (not isinstance(termination, dict) or termination.get("phase") != phase
                or termination.get("worker_stopped") is not True
                or type(termination.get("selected_token_count")) is not int
                or termination["selected_token_count"] != usage["completion_tokens"]
                or not isinstance(ids, list) or len(ids) != usage["completion_tokens"]
                or any(type(token) is not int or token < 0 for token in ids)):
            raise ModelError("Early termination lacks a stopped worker and exact selected-token accounting.")
        policy = info.metadata["generation_policy"]
        if termination.get("kind") == "generation_timeout":
            elapsed = termination.get("elapsed_seconds")
            if (termination.get("limit_seconds") != policy["deadline_seconds"]
                    or type(elapsed) not in (int, float) or not math.isfinite(elapsed)
                    or elapsed + 0.001 < policy["deadline_seconds"]
                    or termination.get("stage") not in {"candidate_search", "generation"}):
                raise ModelError("Computation deadline evidence is incomplete or inconsistent.")
            return
        if termination.get("kind") != "repetition":
            raise ModelError("Unrecognized early termination reason.")
        repeat_policy = policy["repetition_stop"]
        if termination.get("unit") == "completed_nonempty_lines":
            count = termination.get("repeated_lines")
            if (type(count) is not int or count < repeat_policy.get("threshold", 32)
                    or termination.get("threshold") != repeat_policy.get("threshold", 32)):
                raise ModelError("Repeated-line stop does not match the discovered policy.")
            text = data["choices"][0]["message"]["content"]
            previous, observed, witness = None, 0, None
            for index, line in enumerate(text.split("\n")[:-1], 1):
                if line in ("", "\r"):
                    previous, observed = None, 0
                    continue
                observed = observed + 1 if line == previous else 1
                previous = line
                if observed >= repeat_policy.get("threshold", 32):
                    witness = (observed, index, line)
                    break
            if witness != (count, termination.get("completed_line_index"), termination.get("line")):
                raise ModelError("Repeated-line evidence does not describe the actual partial text.")
        elif termination.get("unit") == "periodic_suffix":
            config = repeat_policy.get("periodic_suffix", {})
            for field in ("window_characters", "minimum_repetitions", "minimum_span_characters", "max_period_characters"):
                if termination.get(field) != config.get(field) or type(termination.get(field)) is not int:
                    raise ModelError("Periodic stop does not match the discovered policy.")
            start, end = termination.get("start_character"), termination.get("end_character")
            period, count = termination.get("period_characters"), termination.get("repetitions")
            fragment = termination.get("fragment")
            text = data["choices"][0]["message"]["content"]
            if (any(type(n) is not int for n in (start, end, period, count))
                    or not 0 <= start < end == len(text)
                    or not 1 <= period <= config["max_period_characters"]
                    or count < config["minimum_repetitions"]
                    or not config["minimum_span_characters"] <= end - start <= config["window_characters"]
                    or end - start != count * period
                    or termination.get("repeated_characters") != end - start
                    or not isinstance(fragment, str) or len(fragment) != period
                    or text[start:end] != fragment * count):
                raise ModelError("Periodic stop span does not describe the returned partial text.")
        else:
            raise ModelError("Unsupported repetition unit.")

    def generate(
        self, messages: list[dict[str, Any]], *, max_tokens: int,
        constraint: dict[str, Any] | None = None,
    ) -> GenerationReply:
        if type(max_tokens) is not int or max_tokens <= 0:
            raise ModelError("max_tokens must be a positive integer.")
        if not isinstance(messages, list) or not messages:
            raise ModelError("messages must be a nonempty list.")
        self._validate_constraint(constraint)
        info = self.discover()
        if self._expected_rules is None:
            raise ModelError("Bind a local rule bundle before generation.", error_kind="rule_mismatch")
        if constraint is not None and constraint["kind"] not in info.constraint_types:
            # Fail before /chat/completions: never silently replace a requested
            # joint decoder with ordinary generation or a JSON-only constraint.
            raise ModelError(
                f"Backend {info.backend!r} does not support constraint {constraint['kind']!r}.",
                error_kind="unsupported",
            )
        payload: dict[str, Any] = {
            "model": info.model, "messages": messages,
            "generation_protocol_version": GENERATION_PROTOCOL_VERSION,
            "generation_policy": deepcopy(info.metadata["generation_policy"]),
            "max_tokens": max_tokens, "stream": False, "temperature": 0,
            "rule_bundle_sha256": info.rule_bundle_sha256, "runtime_version": info.runtime_version,
            "context": self._context.as_dict(), "context_sha256": self._context.sha256,
            "compiled_grammar_sha256": self._expected_compiled,
        }
        if constraint is not None:
            payload["constraint"] = constraint
        data = self._request("POST", "/v1/chat/completions", payload)
        assert data is not None
        metadata = self._error_metadata(data)

        def failure(message: str, kind: str = "model_protocol") -> ModelError:
            return ModelError(message, error_kind=kind, **metadata)

        if (data.get("generation_protocol_version") != GENERATION_PROTOCOL_VERSION
                or data.get("generation_policy") != info.metadata["generation_policy"]):
            raise failure("Response generation protocol or stop policy differs from discovery.")
        # Bind ordinary completions too: they are evaluated with the same rules.
        if (data.get("rule_bundle_sha256") != info.rule_bundle_sha256
                or data.get("runtime_version") != info.runtime_version):
            raise failure("Response rule bundle/runtime evidence is missing or mismatched.", "rule_mismatch")
        if data.get("compiled_grammar_sha256") != self._expected_compiled:
            raise failure("Response compiled grammar differs from this run.", "rule_mismatch")
        if data.get("context_sha256") != self._context.sha256:
            raise failure("Response dependency snapshot differs from this run.", "rule_mismatch")
        if data.get("model") != info.model:
            raise failure("Response model differs from the model discovered for this run.")
        for key in ("model_identity", "tokenizer_identity"):
            expected = info.metadata.get(key)
            if expected is not None and data.get(key) != expected:
                raise failure(f"Response {key} differs from discovery; the run cannot mix model revisions.")

        choices = data.get("choices")
        if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
            raise failure("Expected exactly one completion choice.")
        choice = choices[0]
        finish_reason = choice.get("finish_reason")
        if not isinstance(finish_reason, str):
            raise failure("Completion is missing a valid finish_reason.")
        # Contradictory envelopes cannot promote an interrupted candidate to
        # successful generation, even when its extracted source happens to parse.
        if (data.get("partial_decode_error")
                or (finish_reason == "stop" and (data.get("termination") is not None
                    or data.get("constraint_failure") is not None or data.get("partial_utf8_tail_hex")))
                or (finish_reason in {"length", "max_tokens"} and data.get("constraint_failure") is not None)
                or (finish_reason == "constraint_error" and data.get("termination") is not None)):
            raise failure("Completion contains contradictory termination or byte-decoding evidence.")
        if finish_reason in {"length", "max_tokens"}:
            partial = choice.get("message")
            if (not isinstance(partial, dict) or partial.get("refusal")
                    or not isinstance(partial.get("content"), str)):
                raise failure("Truncated completion is missing usable partial text.")
            # A measured length cutoff is model feedback, not a transport error.
            # Verify the same mask acknowledgement as a completed response before
            # permitting another attempt against the shared remaining budget.
            try:
                usage = self._usage(data)
                if constraint is not None:
                    evidence = self._verify_evidence(data, constraint)
                else:
                    evidence = data.get("constraint")
                    if (not isinstance(evidence, dict) or evidence.get("kind") != "plain"
                            or evidence.get("sha256") != constraint_hash({"kind": "plain"})
                            or evidence.get("applied") is not False):
                        raise ModelError("Plain generation evidence is missing or claims an applied constraint.")
                self._verify_rule_evidence(evidence, info)
            except ModelError as exc:
                raise failure(str(exc)) from exc
            if usage["completion_tokens"] > max_tokens:
                raise failure("Server exceeded the requested completion-token budget.")
            termination = data.get("termination")
            message = "Generation truncated at the token limit; regenerate a complete concise model."
            if termination is not None:
                try:
                    self._verify_termination(termination, data, constraint, usage, info)
                except ModelError as exc:
                    raise failure(str(exc)) from exc
                if termination["kind"] == "generation_timeout":
                    # The worker has stopped and exact usage/identity/mask evidence
                    # passed the same checks as other outcomes. This is an effect
                    # failure, but never an automatic extra 600-second request.
                    raise ModelError(
                        "Generation reached its measured computation deadline; project unfinished.",
                        usage=usage, finish_reason=finish_reason, partial_text=partial["content"],
                        retryable=False, applied_constraint=evidence, error_kind="generation_timeout",
                        response_metadata=metadata["response_metadata"],
                    )
                description = (f"{termination['repeated_lines']} completed lines" if
                               termination["unit"] == "completed_nonempty_lines" else
                               f"a periodic text suffix ({termination['repetitions']} repetitions)")
                message = (f"Generation stopped after repeating {description}. "
                           "The project is unfinished; use validator diagnostics and regenerate it.")
            raise ModelError(
                message,
                usage=usage, finish_reason=finish_reason, partial_text=partial["content"],
                retryable=True, applied_constraint=evidence, error_kind="truncated",
                response_metadata=metadata["response_metadata"],
            )
        if finish_reason == "constraint_error":
            issue = data.get("constraint_failure")
            partial = choice.get("message")
            # A verified joint-decoder dead end is model feedback, unlike HTTP
            # failures or missing proof. Only this explicit protocol permits the
            # caller to spend the remaining budget on another generation.
            if (constraint is None
                    or constraint["kind"] not in {"aadl_joint", "aadl_gcd", "plain"}
                    or not isinstance(issue, dict) or issue.get("code") != "dead_end"
                    or not isinstance(issue.get("message"), str) or not issue["message"].strip()
                    or not isinstance(partial, dict) or partial.get("refusal")
                    or not isinstance(partial.get("content"), str)
                    or not isinstance(data.get("model"), str) or not data["model"]):
                raise failure("Unrecognized or ineligible constraint-failure response.")
            try:
                usage = self._usage(data)
                evidence = self._verify_evidence(data, constraint)
                self._verify_rule_evidence(evidence, info)
            except ModelError as exc:
                raise failure(str(exc)) from exc
            if usage["completion_tokens"] > max_tokens:
                raise failure("Server exceeded the requested completion-token budget.")
            raise ModelError(
                "Constrained decoder dead end: " + issue["message"], usage=usage,
                finish_reason=finish_reason, partial_text=partial["content"],
                retryable=True, applied_constraint=evidence,
                error_kind="decoding_dead_end",
                response_metadata=metadata["response_metadata"],
            )
        if finish_reason != "stop":
            raise failure(f"Generation did not finish normally: {finish_reason!r}.")
        message = choice.get("message")
        if (not isinstance(message, dict) or message.get("refusal")
                or not isinstance(message.get("content"), str) or not message["content"].strip()):
            raise failure("Completion is missing nonempty text or contains a refusal.")
        model = data.get("model")
        if not isinstance(model, str) or not model:
            raise failure("Completion is missing its model identifier.")
        try:
            usage = self._usage(data)
        except ModelError as exc:
            raise failure(str(exc)) from exc
        applied = None
        if constraint is not None:
            try:
                applied = self._verify_evidence(data, constraint)
                self._verify_rule_evidence(applied, info)
            except ModelError as exc:
                raise failure(str(exc)) from exc
        else:
            # A baseline cannot silently receive constrained output. Require the
            # same exact request acknowledgement, with applied=false this time.
            ordinary = data.get("constraint")
            if (not isinstance(ordinary, dict) or ordinary.get("kind") != "plain"
                    or ordinary.get("sha256") != constraint_hash({"kind": "plain"})
                    or ordinary.get("applied") is not False):
                raise failure("Plain generation evidence is missing or claims an applied constraint.")
            try:
                self._verify_rule_evidence(ordinary, info)
            except ModelError as exc:
                raise failure(str(exc)) from exc
            # Preserve the verified baseline acknowledgement and server timing
            # too. applied=false remains explicit; it never claims a mask ran.
            applied = deepcopy(ordinary)
        if usage["completion_tokens"] > max_tokens:
            raise failure("Server exceeded the requested completion-token budget.")
        response_metadata = {key: deepcopy(value) for key, value in data.items()
                             if key not in {"choices", "usage"}}
        return GenerationReply(message["content"], usage, finish_reason, applied, model, response_metadata)

    @staticmethod
    def _verify_rule_evidence(evidence: dict, info: EndpointInfo) -> None:
        if (evidence.get("rule_bundle_sha256") != info.rule_bundle_sha256
                or evidence.get("runtime_version") != info.runtime_version):
            raise ModelError("Constraint was not applied with the expected rule bundle/runtime.")
