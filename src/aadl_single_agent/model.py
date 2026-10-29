"""URL-only access to the versioned AADL inference service.

Every request, including an ordinary completion, binds the actual rule bundle.
This prevents experiments from silently mixing local and server rule revisions.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from copy import deepcopy
import hashlib
import json
import re
from typing import Any
from urllib.parse import urlsplit, urlunsplit

import httpx


class ModelError(RuntimeError):
    """Discovery, transport, or generation failed without a usable result."""

    def __init__(
        self, message: str, *, usage: dict[str, int] | None = None,
        finish_reason: str | None = None, partial_text: str | None = None,
        retryable: bool = False, applied_constraint: dict[str, Any] | None = None,
        error_kind: str = "model_protocol",
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
        self, base_url: str, timeout: float = 600,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if timeout <= 0:
            raise ModelError("timeout must be positive.")
        self._endpoint: EndpointInfo | None = None
        self._expected_rules: tuple[str, str, str] | None = None
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
        if self._endpoint is not None:
            self._check_rules(self._endpoint)

    def _check_rules(self, info: EndpointInfo) -> None:
        if self._expected_rules is None:
            return
        profile, digest, runtime = self._expected_rules
        actual_profile = info.metadata.get("rule_bundle", {}).get("profile")
        if (actual_profile, info.rule_bundle_sha256, info.runtime_version) != (profile, digest, runtime):
            raise ModelError(
                "Rule bundle or runtime differs from the server. Deploy the matching rule bundle first.",
                error_kind="rule_mismatch",
            )

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
        if (not isinstance(model, str) or not model.strip()
                or not isinstance(kinds, list)
                or not all(isinstance(kind, str) and kind for kind in kinds)
                or not isinstance(backend, str) or not backend
                or not isinstance(bundle, dict)
                or not isinstance(bundle.get("profile"), str)
                or not re.fullmatch(r"[0-9a-f]{64}", str(bundle.get("sha256", "")))
                or not isinstance(model_identity, dict) or not model_identity
                or not isinstance(tokenizer_identity, dict) or not tokenizer_identity
                or runtime != "2"):
            raise ModelError("Invalid /v1/capabilities: model/tokenizer identities, capabilities and rule bundle are required.")
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
        elif kind == "aadl_joint":
            if not isinstance(constraint.get("profile"), str) or not constraint["profile"]:
                raise ModelError("aadl_joint constraint requires a profile.")
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
        return dict(evidence)

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
            "max_tokens": max_tokens, "stream": False, "temperature": 0,
            "rule_bundle_sha256": info.rule_bundle_sha256, "runtime_version": info.runtime_version,
        }
        if constraint is not None:
            payload["constraint"] = constraint
        data = self._request("POST", "/v1/chat/completions", payload)
        assert data is not None
        metadata = self._error_metadata(data)

        def failure(message: str, kind: str = "model_protocol") -> ModelError:
            return ModelError(message, error_kind=kind, **metadata)

        # Bind ordinary completions too: they are evaluated with the same rules.
        if (data.get("rule_bundle_sha256") != info.rule_bundle_sha256
                or data.get("runtime_version") != info.runtime_version):
            raise failure("Response rule bundle/runtime evidence is missing or mismatched.", "rule_mismatch")
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
            raise ModelError(
                "Generation truncated at the token limit; regenerate a complete concise model.",
                usage=usage, finish_reason=finish_reason, partial_text=partial["content"],
                retryable=True, applied_constraint=evidence, error_kind="truncated",
            )
        if finish_reason == "constraint_error":
            issue = data.get("constraint_failure")
            partial = choice.get("message")
            # A verified joint-decoder dead end is model feedback, unlike HTTP
            # failures or missing proof. Only this explicit protocol permits the
            # caller to spend the remaining budget on another generation.
            if (constraint is None
                    or constraint["kind"] != "aadl_joint"
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
                "Joint decoder dead end: " + issue["message"], usage=usage,
                finish_reason=finish_reason, partial_text=partial["content"],
                retryable=True, applied_constraint=evidence,
                error_kind="decoding_dead_end",
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
