"""One conversation plans, generates, and repairs; deterministic tools assess it."""
from __future__ import annotations

from copy import deepcopy
from dataclasses import asdict, dataclass, field, is_dataclass
from datetime import datetime, timezone
import hashlib
from importlib import metadata
import json
from pathlib import Path
import platform
import re
import time
from typing import Any, Callable
import jsonschema

from .constraints import JointEngine
from .model import ModelClient, ModelError
from .osate import OsateRunner
from .rules import RuleBundle, load_bundle

DEFAULT_TOKEN_BUDGET = 8192
PLANNING_TOKEN_LIMIT = 2048
GENERATION_TOKEN_LIMIT = 4096
DEFAULT_MAX_ATTEMPTS = 3
# Resolve legacy imports lazily: selecting an external --rules package must not
# require loading the bundled default language at CLI import time.
def __getattr__(name: str):
    if name == "DECLARATION_SCHEMA":
        return load_bundle().planning_schema
    if name == "SYSTEM_PROMPT":
        return load_bundle().system_prompt
    raise AttributeError(name)

def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()

def json_value(value: Any) -> Any:
    if is_dataclass(value):
        value = asdict(value)
    if isinstance(value, dict):
        return {str(key): json_value(item) for key, item in value.items()}
    if isinstance(value, (set, frozenset)):
        return sorted(json_value(item) for item in value)
    if isinstance(value, (list, tuple)):
        return [json_value(item) for item in value]
    return str(value) if isinstance(value, Path) else value

def endpoint_snapshot(endpoint: Any) -> dict:
    return json_value({key: getattr(endpoint, key, None) for key in (
        "base_url", "model", "backend", "constraint_types", "rule_bundle_sha256",
        "runtime_version", "metadata",
    )})

def workflow_identity() -> str:
    """Record executable workflow identity so saved runs remain traceable."""
    digest = hashlib.sha256()
    for source in sorted(Path(__file__).parent.glob("*.py")):
        digest.update(source.name.encode("utf-8") + b"\0" + source.read_bytes() + b"\0")
    return digest.hexdigest()

def local_versions() -> dict:
    versions = {"python": platform.python_version(), "platform": platform.platform()}
    for package in ("aadl-single-agent", "httpx", "lark", "llguidance", "jsonschema", "PyYAML"):
        try:
            versions[package] = metadata.version(package)
        except metadata.PackageNotFoundError:
            versions[package] = None
    return versions

@dataclass
class RunResult:
    status: str
    mode: str
    constraints: str
    model: str = ""
    backend: str = ""
    failure_kind: str | None = None
    validation_scope: str = "syntax_and_static_semantics"
    requirements_verified: bool = False
    requirements: str = ""
    settings: dict = field(default_factory=dict)
    provenance: dict = field(default_factory=dict)
    usage: dict = field(default_factory=lambda: {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0})
    usage_complete: bool = True
    calls: list[dict] = field(default_factory=list)
    attempts: list[dict] = field(default_factory=list)
    planning: dict | None = None
    timing: dict = field(default_factory=lambda: {
        "elapsed_seconds": 0.0, "generation_seconds": 0.0,
        "static_check_seconds": 0.0, "osate_seconds": 0.0,
    })
    started_at: str = field(default_factory=_utc_now)
    finished_at: str | None = None
    completed: bool = False
    output: str | None = None
    error: str | None = None

def _write_json(path: Path, data: dict) -> None:
    # Atomic replacement preserves the previous checkpoint if writing is interrupted.
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(json_value(data), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)

def _unwrap_code(text: str) -> str:
    match = re.fullmatch(r"\s*```(?:aadl)?\s*\n(.*?)\n```\s*", text, re.DOTALL | re.IGNORECASE)
    return (match.group(1) if match else text).strip() + "\n"

class SingleAgent:
    def __init__(self, client: ModelClient, osate: OsateRunner | None = None,
                 engine_factory: Callable = JointEngine, bundle: RuleBundle | None = None):
        self.client = client
        self.osate = osate if osate is not None else OsateRunner()
        self.engine_factory = engine_factory
        self.bundle = bundle if bundle is not None else load_bundle()

    def run(self, requirements: str, output_dir: str | Path, *, mode: str = "direct",
            constraints: str = "joint", token_budget: int = DEFAULT_TOKEN_BUDGET,
            max_attempts: int = DEFAULT_MAX_ATTEMPTS) -> RunResult:
        if mode not in {"direct", "planned"} or constraints not in {"plain", "joint"}:
            raise ValueError("mode must be direct/planned and constraints must be plain/joint")
        if (type(token_budget) is not int or token_budget < 1
                or type(max_attempts) is not int or not 1 <= max_attempts <= 3
                or not isinstance(requirements, str) or not requirements.strip()):
            raise ValueError("Require nonempty requirements, a positive integer budget, and 1..3 attempts")
        root = Path(output_dir).resolve()
        root.mkdir(parents=True, exist_ok=False)
        started = time.perf_counter()
        result = RunResult(status="failed", mode=mode, constraints=constraints, requirements=requirements)
        result.settings = {
            "token_budget": token_budget, "max_attempts": max_attempts,
            "planning_token_limit": PLANNING_TOKEN_LIMIT, "generation_token_limit": GENERATION_TOKEN_LIMIT,
            "decoding": {"temperature": 0, "thinking": False, "batch_size": 1, "num_beams": 1},
        }
        result.provenance = {
            "rule_bundle": {"path": str(self.bundle.path), "profile": self.bundle.profile,
                            "sha256": self.bundle.digest, "runtime_version": self.bundle.runtime_version},
            "workflow_sha256": workflow_identity(), "versions": local_versions(),
        }
        messages = [{"role": "system", "content": self.bundle.system_prompt},
                    {"role": "user", "content": requirements}]
        declarations = None
        phase = "preflight"
        interrupted = False

        def save() -> None:
            result.timing["elapsed_seconds"] = time.perf_counter() - started
            _write_json(root / "result.json", asdict(result))

        def charge(usage: dict) -> None:
            for key in result.usage:
                result.usage[key] += usage[key]

        def generate(call_phase: str, limit: int, constraint):
            call = {"phase": call_phase, "max_tokens": limit, "status": "started",
                    "messages": deepcopy(messages), "constraint": deepcopy(constraint), "started_at": _utc_now()}
            result.calls.append(call)
            save()
            call_started = time.perf_counter()
            try:
                reply = self.client.generate(messages, max_tokens=limit, constraint=constraint)
            except ModelError as exc:
                call.update(status="error", error=str(exc), error_kind=getattr(exc, "error_kind", "model_protocol"),
                            usage=exc.usage, finish_reason=exc.finish_reason,
                            constraint_evidence=exc.applied_constraint)
                if exc.usage is not None:
                    charge(exc.usage)
                    if exc.usage["completion_tokens"] > limit:
                        raise ModelError("Server exceeded the requested completion-token budget") from exc
                else:
                    result.usage_complete = False
                if exc.partial_text is not None:
                    partial_path = root / f"call-{len(result.calls)}-partial.txt"
                    # Disable platform newline translation: CRLF returned by a
                    # model must not become CRCRLF when written on Windows.
                    partial_path.write_text(exc.partial_text, encoding="utf-8", newline="")
                    call["partial_output"] = str(partial_path)
                raise
            else:
                charge(reply.usage)
                call.update(status="completed", usage=reply.usage,
                            finish_reason=getattr(reply, "finish_reason", "stop"),
                            model=getattr(reply, "model", result.model), metadata=json_value(getattr(reply, "metadata", {})),
                            constraint_evidence=reply.applied_constraint, response_text=reply.text)
                if reply.usage["completion_tokens"] > limit:
                    call["status"] = "error"
                    raise ModelError("Server exceeded the requested completion-token budget")
                return reply
            finally:
                duration = time.perf_counter() - call_started
                call["elapsed_seconds"] = duration
                result.timing["generation_seconds"] += duration
                save()

        def assess(text: str, number: int, usage: dict | None, evidence: dict | None,
                   *, generation_complete: bool) -> dict:
            # Run the independent checker even when our rules reject a candidate.
            # Otherwise false rejections would disappear from experimental evidence.
            attempt_root = root / f"attempt-{number}"
            attempt_root.mkdir()
            candidate_path = attempt_root / "candidate.aadl"
            candidate = _unwrap_code(text)
            candidate_path.write_text(candidate, encoding="utf-8", newline="")
            check_started = time.perf_counter()
            # Check the same text written to disk; read_text's universal newline
            # handling could otherwise hide an actual serialization difference.
            checked = engine.validate(candidate)
            static_seconds = time.perf_counter() - check_started
            result.timing["static_check_seconds"] += static_seconds
            entry = {"attempt": number, "usage": usage, "constraint_evidence": evidence,
                     "generation_complete": generation_complete,
                     "syntax_semantics_valid": checked.valid, "pending": list(checked.pending),
                     "diagnostics": list(checked.diagnostics), "candidate": str(candidate_path),
                     "static_check_seconds": static_seconds, "accepted": False}
            result.attempts.append(entry)
            save()
            check_started = time.perf_counter()
            independent = self.osate.check(candidate_path, attempt_root / "osate")
            osate_seconds = time.perf_counter() - check_started
            result.timing["osate_seconds"] += osate_seconds
            entry.update(osate=independent, osate_seconds=osate_seconds)
            entry["diagnostics"].extend(d["message"] for d in independent.get("diagnostics", []))
            entry["accepted"] = generation_complete and checked.valid and independent["status"] == "passed"
            save()
            return entry

        def infrastructure_failed(entry: dict) -> bool:
            if entry["osate"]["status"] in {"passed", "failed"}:
                return False
            result.failure_kind = "infrastructure"
            result.error = "OSATE infrastructure failure: " + "; ".join(entry["diagnostics"])
            return True

        try:
            self.client.bind_rules(self.bundle)
            # Resolve local infrastructure before contacting the model service;
            # a configured missing CLI cannot be repaired by model inference.
            availability = self.osate.probe()
            result.provenance["osate"] = availability
            if availability["status"] != "passed":
                result.failure_kind = "infrastructure"
                details = "; ".join(issue["message"] for issue in availability.get("diagnostics", []))
                result.error = "OSATE is unavailable; independent model validation is required." + (
                    " " + details if details else "")
                return result
            endpoint = self.client.discover()
            result.model, result.backend = endpoint.model, endpoint.backend
            result.provenance["endpoint"] = endpoint_snapshot(endpoint)
            if constraints == "joint" and "aadl_joint" not in endpoint.constraint_types:
                result.failure_kind = "unsupported"
                raise ModelError("Endpoint does not support aadl_joint. Use explicit plain mode for baseline testing.")
            if mode == "planned" and "json_schema" not in endpoint.constraint_types:
                result.failure_kind = "unsupported"
                raise ModelError("Planned mode requires JSON Schema support.")
            if mode == "planned":
                phase = "planning"
                messages.append({"role": "user", "content": self.bundle.planning_prompt})
                limit = min(PLANNING_TOKEN_LIMIT, max(1, token_budget // 3))
                reply = generate("planning", limit, {"kind": "json_schema", "schema": self.bundle.planning_schema})
                plan = json.loads(reply.text)
                jsonschema.validate(plan, self.bundle.planning_schema)
                # Language-specific planning shapes belong to the rule package.
                # Normalized promises still need the engine's consistency checks.
                declarations = self.bundle.normalize_plan(plan)
                result.planning = {"declarations": declarations, "usage": reply.usage, "raw": plan}
                messages.extend([{"role": "assistant", "content": reply.text},
                                 {"role": "user", "content": self.bundle.generation_prompt}])
            engine = self.engine_factory(declarations=declarations, bundle=self.bundle)
            phase = "generation"
            joint = {"kind": "aadl_joint", "profile": self.bundle.profile}
            if declarations is not None:
                joint["declarations"] = declarations
            for number in range(1, max_attempts + 1):
                remaining = token_budget - result.usage["completion_tokens"]
                if remaining <= 0:
                    result.failure_kind, result.error = "budget_exhausted", "The shared completion-token budget is exhausted."
                    break
                try:
                    reply = generate("generation", min(GENERATION_TOKEN_LIMIT, remaining), joint if constraints == "joint" else None)
                except ModelError as exc:
                    if exc.partial_text is not None:
                        entry = assess(exc.partial_text, number, exc.usage, exc.applied_constraint, generation_complete=False)
                        entry["diagnostics"].append(str(exc))
                        entry["decoding_dead_end"] = exc.error_kind == "decoding_dead_end"
                        entry["generation_truncated"] = exc.error_kind == "truncated"
                        if infrastructure_failed(entry):
                            return result
                    if (not exc.retryable or exc.usage is None
                            or exc.error_kind not in {"decoding_dead_end", "truncated"}):
                        raise
                    result.failure_kind, result.error = exc.error_kind, str(exc)
                    remaining = token_budget - result.usage["completion_tokens"]
                    if remaining <= 0 or number == max_attempts:
                        # Preserve the actual model failure when the shared
                        # budget/attempt limit leaves no further repair request.
                        save()
                        break
                    if exc.partial_text:
                        messages.append({"role": "assistant", "content": exc.partial_text})
                    cause = ("The previous response reached its output token limit before completion."
                             if exc.error_kind == "truncated"
                             else "The previous generation reached a semantic dead end.")
                    messages.append({"role": "user", "content": self.bundle.generation_prompt +
                        "\n" + cause + f" There are {remaining} output tokens left in the shared budget. "
                        "Generate a new complete model within the next response limit. "
                        "Keep the requirements and declaration plan unchanged. Tool diagnostics:\n" + str(exc)})
                    save()
                    continue
                entry = assess(reply.text, number, reply.usage, reply.applied_constraint, generation_complete=True)
                if infrastructure_failed(entry):
                    return result
                if entry["accepted"]:
                    accepted = root / "model.aadl"
                    # The delivered artifact is exactly what OSATE assessed.
                    accepted.write_bytes(Path(entry["candidate"]).read_bytes())
                    result.status, result.output = "passed", str(accepted)
                    result.failure_kind = result.error = None
                    return result
                result.failure_kind = "model_invalid"
                result.error = "No candidate passed both the supported static checks and OSATE."
                messages.extend([
                    {"role": "assistant", "content": reply.text},
                    {"role": "user", "content": self.bundle.generation_prompt +
                     "\nKeep the requirements and declaration plan unchanged. Correct the complete model using these tool diagnostics:\n" +
                     "\n".join(entry["diagnostics"] + ["Unfulfilled: " + p for p in entry["pending"]])},
                ])
        except ModelError as exc:
            if result.failure_kind != "unsupported":
                result.failure_kind = getattr(exc, "error_kind", "model_protocol")
            result.error = str(exc)
        except (ValueError, jsonschema.ValidationError) as exc:
            result.failure_kind = "invalid_plan" if phase == "planning" else "validation_error"
            result.error = str(exc)
        except OSError as exc:
            result.failure_kind, result.error = "infrastructure", str(exc)
        except BaseException:
            # Preserve incomplete-run evidence before propagating cancellation or
            # a programming fault. A started request has unknown consumption.
            interrupted = True
            result.failure_kind, result.error = "interrupted", "Run interrupted before completing its validation workflow"
            if any(call["status"] == "started" for call in result.calls):
                result.usage_complete = False
            raise
        finally:
            result.completed = not interrupted
            result.finished_at = _utc_now()
            save()
        return result
