"""Build bounded repair context from saved observations, without inspecting files."""

from __future__ import annotations

import json
from typing import Any


def _json_bytes(value: dict) -> int:
    """Use the same compact, UTF-8 JSON representation as repair prompts."""
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def _boolean(value: Any) -> bool | None:
    # Missing evidence, integers such as 0, and strings such as "false" do not
    # establish either outcome of an observation.
    return value if isinstance(value, bool) else None


def _number(value: Any) -> int | None:
    return value if type(value) is int and 0 <= value <= 2**31 - 1 else None


def build_repair_history(
    attempts: list[dict], *, max_entries: int = 3, max_bytes: int = 4096
) -> dict:
    """Return recent evaluated candidates, preserving unknown results as null.

    ``max_bytes`` bounds ``json.dumps(result, ensure_ascii=False,
    separators=(",", ":")).encode("utf-8")``. It includes all metadata and
    JSON escaping, rather than counting only the visible source characters.
    An impossibly small budget raises ``ValueError`` instead of silently
    returning a result without its schema or truncation declaration.

    Input order is the recorded attempt order. A final-validation observation
    marks an evaluated candidate; a partially saved generation alone does not.
    This function never reads source paths, reruns checks, or mutates inputs.
    """
    if type(max_entries) is not int or max_entries < 0:
        raise ValueError("max_entries must be a non-negative integer")
    if type(max_bytes) is not int or max_bytes < 1:
        raise ValueError("max_bytes must be a positive integer")

    evaluated = []
    for entry in attempts:
        if not isinstance(entry, dict):
            continue
        validation = entry.get("final_validation")
        if not isinstance(validation, dict):
            continue
        # An empty placeholder is not evidence that evaluation has happened.
        if (isinstance(validation.get("accepted"), bool)
                or isinstance(validation.get("local_rules_valid"), bool)
                or validation.get("osate_status") in ("passed", "failed")):
            evaluated.append(entry)

    selected = evaluated[-max_entries:] if max_entries else []
    compression = {
        "max_entries": max_entries,
        "max_bytes": max_bytes,
        "evaluated_attempts": len(evaluated),
        "ignored_unevaluated_attempts": len(attempts) - len(evaluated),
        "omitted_attempts": len(evaluated) - len(selected),
        "fields_truncated": 0,
        "source_lines_omitted": 0,
        "byte_budget_applied": False,
    }
    result = {
        "schema_version": 1,
        "scope": "saved_evaluated_candidates",
        "meaning": (
            "candidate_changed reports saved text-hash change only; diagnostic absence "
            "does not establish repair or correctness; null means unknown; accepted "
            "is the saved complete-validation outcome."
        ),
        "entries": [],
        "truncation": compression,
    }

    def bounded(value: Any, limit: int) -> str | None:
        if not isinstance(value, str):
            return None
        # Escape isolated surrogate code points so malformed saved text cannot
        # make the UTF-8 budget check fail. The escape preserves their identity.
        safe = value.encode("utf-8", errors="backslashreplace").decode("utf-8")
        if len(safe) > limit:
            compression["fields_truncated"] += 1
            return safe[:limit] + "…"
        return safe

    for saved in selected:
        focus = saved.get("repair_focus")
        focus = focus if isinstance(focus, dict) else {}
        diagnostic = focus.get("diagnostic")
        diagnostic = diagnostic if isinstance(diagnostic, dict) else {}
        previous = saved.get("previous_repair_focus")
        previous = previous if isinstance(previous, dict) else {}
        observation = saved.get("focus_diagnostic_observation")
        observation = observation if isinstance(observation, dict) else {}
        termination = saved.get("generation_termination")
        termination = termination if isinstance(termination, dict) else {}
        validation = saved["final_validation"]

        # Preserve the recorded tri-state observation. Do not compare the
        # shortened messages here: equal snippets need not be equal diagnoses.
        reobserved = _boolean(saved.get("focus_diagnostic_reobserved"))
        if "focus_diagnostic_reobserved" not in saved:
            reobserved = _boolean(observation.get("reobserved"))

        source_context = []
        raw_context = focus.get("source_context")
        if isinstance(raw_context, list):
            contexts = [line for line in raw_context if isinstance(line, dict)]
            compression["source_lines_omitted"] += len(contexts[3:])
            for line in contexts[:3]:
                original = line.get("text")
                text = bounded(original, 192)
                source_context.append({
                    "line": _number(line.get("line")),
                    "text": text,
                    "text_truncated": (
                        line.get("text_truncated") is True
                        or isinstance(original, str) and text != original
                    ),
                })

        result["entries"].append({
            "attempt": _number(saved.get("attempt")),
            "candidate_sha256": bounded(saved.get("candidate_sha256"), 128),
            "candidate_changed": _boolean(saved.get("candidate_changed")),
            "error": {
                "source": bounded(focus.get("source"), 160),
                "line": _number(focus.get("line")),
                "origin": bounded(focus.get("origin"), 48),
                "diagnostic": {
                    "severity": bounded(diagnostic.get("severity"), 32),
                    "rule_id": bounded(diagnostic.get("rule_id"), 96),
                    "code": bounded(diagnostic.get("code"), 96),
                    "message": bounded(diagnostic.get("message"), 320),
                },
                "source_context": source_context,
            },
            "previous_focus": {
                "source": bounded(previous.get("source"), 160),
                "line": _number(previous.get("line")),
                "pattern_id": bounded(previous.get("pattern_id"), 96),
            },
            "previous_diagnostic": {
                "reobserved": reobserved,
                "reason": bounded(observation.get("reason"), 128),
                "match_basis": bounded(observation.get("match_basis"), 48),
                "signature": bounded(observation.get("signature"), 160),
            },
            "termination": {
                key: bounded(termination.get(key), 48) for key in ("kind", "stage", "phase")
            },
            "validation": {
                "accepted": _boolean(validation.get("accepted")),
                "generation_complete": _boolean(validation.get("generation_complete")),
                "local_rules_valid": _boolean(validation.get("local_rules_valid")),
                "osate_status": bounded(validation.get("osate_status"), 32),
                "project_protocol_complete": _boolean(validation.get("project_protocol_complete")),
                "instance_rules_status": bounded(validation.get("instance_rules_status"), 32),
            },
        })

    # Keep the newest evidence useful. Before discarding an older entry, remove
    # its optional surrounding lines; the selected error and observed outcomes
    # remain. Removed attempts are counted, never silently presented as absent.
    while _json_bytes(result) > max_bytes and result["entries"]:
        compression["byte_budget_applied"] = True
        oldest = result["entries"][0]
        context = oldest["error"]["source_context"]
        if context:
            context.pop()
            compression["source_lines_omitted"] += 1
        else:
            result["entries"].pop(0)
            compression["omitted_attempts"] += 1

    if _json_bytes(result) > max_bytes:
        raise ValueError("max_bytes is too small for repair-history schema and truncation metadata")
    return result
