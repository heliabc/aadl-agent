"""Offline, call-accounted experiment metrics; no model or validator requests.

One row is one requirement/condition/batch. Repeated batches are deliberately
never pooled: deterministic reruns and translated requirements are not new
independent scientific observations. Reference models are provenance, not an
automatic oracle for requirement satisfaction.
"""
from __future__ import annotations

from collections import Counter, defaultdict
import csv
import io
import json
import math
from pathlib import Path
from statistics import mean


EXPERIMENT_PROTOCOL = "two-group-repair"
EXPERIMENT_PROTOCOL_VERSION = 3


def experiment_protocol(batch: dict) -> tuple[str, int]:
    """Label legacy data at read time; never backfill its persisted manifest."""
    return (batch.get("experiment_protocol", "legacy-six-condition"),
            batch.get("experiment_protocol_version", 1))


INFRASTRUCTURE_KINDS = frozenset({
    "infrastructure", "rule_mismatch", "unsupported", "invalid_request",
    "model_protocol", "runner_error", "fingerprint_mismatch",
})
TERMINAL_STATES = frozenset({"finished", "failed"})
TOKEN_FIELDS = ("prompt_tokens", "completion_tokens", "total_tokens")
# These measurements overlap. In particular candidate_selection_seconds includes
# sorting/bridge work, and must NOT be added to syntax/semantic times as overhead.
# transport_prefix_seconds times container matcher work for complete text and
# pending UTF-8. It is already included in transport_mask_seconds; candidate
# search/selection and synchronization replay may include the same work too.
EVIDENCE_FIELDS = (
    "mask_steps", "transport_mask_steps", "syntax_mask_steps",
    "semantic_candidates_tested", "semantic_rejections", "candidate_rejections",
    "aadl_cfg_candidate_checks", "aadl_cfg_rejections", "semantic_evaluations",
    "attribute_conflicts", "attribute_computations", "attribute_cache_hits", "attribute_invalidations",
    "transport_mask_seconds", "transport_prefix_seconds", "aadl_syntax_seconds", "semantic_seconds",
    "candidate_selection_seconds", "synchronization_seconds", "generation_seconds",
    "prefix_parse_seconds", "attribute_evaluation_seconds", "attribute_setup_seconds",
    "semantic_equation_seconds", "registered_constraint_seconds", "project_parse_seconds",
    "ranking_seconds", "score_transfer_seconds", "token_decode_seconds", "token_decode_calls",
    "candidate_search_seconds", "candidate_search_steps", "candidate_search_max_seconds",
    "candidate_tests_max_per_step",
    "repetition_detection_seconds", "repetition_observations",
)
MAX_EVIDENCE_FIELDS = frozenset({"candidate_search_max_seconds", "candidate_tests_max_per_step"})


def _aggregate_evidence(field: str, values: list):
    # Maxima describe the slowest/largest step across calls, never a sum of maxima.
    return (max(values) if field in MAX_EVIDENCE_FIELDS else sum(values)) if values else None


def _number(value):
    return value if type(value) in (int, float) and math.isfinite(value) and value >= 0 else None


def _complete_osate_pass(attempt: dict) -> bool:
    """External success does not inherit the local attribute engine's verdict."""
    protocol = attempt.get("project_protocol") or {}
    return (attempt.get("generation_complete") is True
            and protocol.get("complete") is True and protocol.get("valid") is True
            and (attempt.get("osate") or {}).get("status") == "passed")


def _read_result(output_root: Path, job: dict) -> tuple[dict, str | None]:
    if job["state"] == "not_started":
        return {}, None
    path = output_root / job["output"] / "result.json"
    if not path.is_file():
        return {}, "missing" if job["state"] != "not_started" else None
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        if not isinstance(data, dict):
            raise ValueError("result is not an object")
        return data, None
    except (OSError, UnicodeError, ValueError) as exc:
        return {}, "unreadable: " + str(exc)


def job_metrics(output_root: Path, batch: dict, case: dict, job: dict) -> dict:
    result, result_error = _read_result(output_root, job)
    calls = result.get("calls") or []
    attempts = result.get("attempts") or []
    state = job["state"]
    terminal = state in TERMINAL_STATES
    started = state != "not_started"
    # A scheduler exception after a partial pipeline checkpoint supersedes that
    # checkpoint's provisional model failure (it is not an efficacy outcome).
    kind = (job.get("failure_kind") if job.get("failure_kind") in INFRASTRUCTURE_KINDS
            else result.get("failure_kind") or job.get("failure_kind"))
    if result_error and terminal and kind not in INFRASTRUCTURE_KINDS:
        kind = "runner_error"
    infrastructure = kind in INFRASTRUCTURE_KINDS
    # Scope and provenance are separate from a model's observed validity. Keep
    # all runs in descriptive statistics, but require affirmative evidence for
    # the independent, supported-core subset. Old records remain unknown.
    scope = case["metadata"].get("generation_scope_status", "unknown")
    if scope not in {"in_scope", "out_of_scope", "needs_review"}:
        scope = "unknown"
    eligibility = case["metadata"].get("independent_evaluation_eligible")
    eligibility = eligibility if type(eligibility) is bool else None
    if case["metadata"].get("requirement_origin") == "derived_example":
        eligibility = False
    independent_core = scope == "in_scope" and eligibility is True
    first = next((item for item in attempts if item.get("attempt") == 1), None)
    # Missing attempt 1 must never be replaced with the first *recorded* attempt:
    # the first generation call may have failed without returning any source.
    first_pass = int(_complete_osate_pass(first)) if first is not None else (0 if terminal else None)
    budget_pass = 1 if any(_complete_osate_pass(item) for item in attempts) else (0 if terminal else None)
    generation_calls = [call for call in calls if call.get("phase") == "generation"]
    planning_calls = [call for call in calls if call.get("phase") == "planning"]
    endpoint = (result.get("provenance") or {}).get("endpoint") or {}
    runtime = (batch.get("fingerprint") or {}).get("runtime") or {}
    endpoint = endpoint or runtime.get("endpoint") or {}
    identity = endpoint.get("metadata") or {}
    protocol_id, protocol_version = experiment_protocol(batch)
    settings = result.get("settings") or {}
    retrieval = (result.get("provenance") or {}).get("retrieval") or {}
    retrieval_events = retrieval.get("events") or []
    actual_max_attempts = settings.get("max_attempts", job.get("max_attempts", batch["config"]["max_attempts"]))
    repair_enabled = settings.get("repair_enabled", job.get("repair_enabled", actual_max_attempts > 1))
    call_metadata = [(call.get("metadata") or call.get("error_response_metadata") or {}) for call in calls]
    protocols = sorted({meta["generation_protocol_version"] for meta in call_metadata
                        if type(meta.get("generation_protocol_version")) is int})
    endpoint_protocol = identity.get("generation_protocol_version", endpoint.get("generation_protocol_version"))
    if not protocols and type(endpoint_protocol) is int:
        protocols = [endpoint_protocol]
    terminations = []
    for index, (call, meta) in enumerate(zip(calls, call_metadata), 1):
        termination = meta.get("termination")
        if isinstance(termination, dict):
            terminations.append({"call": index, "call_phase": call.get("phase"), **termination,
                                 "client_error_kind": call.get("error_kind"),
                                 "generation_protocol_version": meta.get("generation_protocol_version")})
    row = {
        "batch": batch["id"], "case_id": case["id"], "input": case["relative_path"],
        "experiment_protocol": protocol_id, "experiment_protocol_version": protocol_version,
        "input_sha256": case["sha256"], "family": case["metadata"].get("family", "unknown"),
        "requirement_origin": case["metadata"].get("requirement_origin", "unknown"),
        "generation_scope_status": scope,
        "independent_evaluation_eligible": eligibility,
        "independent_core_evaluable": int(terminal and not infrastructure and independent_core),
        "source_url": case["metadata"].get("source_url"),
        "selection_role": case.get("role", case["metadata"].get("role", "unspecified")),
        "selection_sha256": ((batch.get("fingerprint") or {}).get("local") or {}).get("selection_sha256"),
        "generation_protocol_version": protocols[0] if len(protocols) == 1 else None,
        "observed_generation_protocol_versions": json.dumps(protocols),
        "termination_events": json.dumps(terminations, ensure_ascii=False, sort_keys=True),
        "termination_kinds": ";".join(sorted({str(t.get("kind")) for t in terminations})),
        "generation_timeout_calls": sum(c.get("error_kind") == "generation_timeout" for c in calls) if result else None,
        "generation_timeout_phases": ";".join(sorted({str(c.get("phase")) for c in calls if c.get("error_kind") == "generation_timeout"})),
        "repetition_stop_calls": sum(t.get("kind") == "repetition" and t.get("client_error_kind") == "truncated" for t in terminations) if result else None,
        "source_requirement_ids": json.dumps(case["metadata"].get("source_requirement_ids", []), ensure_ascii=False),
        "condition": job["condition"], "mode": job.get("mode", "direct"), "constraints": job["constraints"],
        "repair_enabled": repair_enabled, "actual_max_attempts": actual_max_attempts,
        "order": job["order"], "state": state, "pipeline_status": result.get("status"),
        "failure_kind": kind, "infrastructure_failure": int(infrastructure),
        "started": int(started), "terminal": int(terminal),
        "evaluable": int(terminal and not infrastructure),
        "first_osate_pass": first_pass, "budget_osate_pass": budget_pass,
        "first_attempt_present": int(first is not None), "attempts_recorded": len(attempts) if result else None,
        "osate_checks": sum((a.get("osate") or {}).get("status") in {"passed", "failed"} for a in attempts) if result else None,
        "local_pipeline_pass": int(result.get("status") == "passed") if result else None,
        "planning_failure": int(kind == "invalid_plan") if result else None,
        "planning_stopped_before_generation": int(bool(planning_calls and not generation_calls)) if result else None,
        "generation_calls": len(generation_calls) if result else None,
        "planning_calls": len(planning_calls) if result else None,
        "repair_calls": max(0, len(generation_calls) - 1) if result else None,
        "calls": len(calls) if result else None,
        "token_budget": batch["config"]["token_budget"], "max_attempts": actual_max_attempts,
        "model": result.get("model") or endpoint.get("model"),
        "backend": result.get("backend") or endpoint.get("backend"),
        "model_identity": json.dumps(identity.get("model_identity"), ensure_ascii=False, sort_keys=True),
        "tokenizer_identity": json.dumps(identity.get("tokenizer_identity"), ensure_ascii=False, sort_keys=True),
        "validation_scope": result.get("validation_scope"),
        "requirements_verified": False, "requirement_coverage": None,
        "requirement_coverage_status": "not_evaluated",
        "generated_file_limit": settings.get("generated_file_limit", batch["config"].get("generated_file_limit")),
        "retrieval_enabled": settings.get("retrieval_enabled", batch["config"].get("retrieval_enabled")),
        "retrieval_identity": json.dumps({k: v for k, v in retrieval.items() if k != "events"},
                                          ensure_ascii=False, sort_keys=True) if retrieval else None,
        "retrieval_initial_sha256": (generation_calls[0].get("retrieval") or {}).get("sha256") if generation_calls else None,
        "retrieval_operations": sum(e.get("enabled") is True for e in retrieval_events) if retrieval else None,
        "retrieval_selected_entries": sum(len(e.get("entries") or []) for e in retrieval_events) if retrieval else None,
        # Retrieval has no model requests. Prompt tokens already include its
        # evidence, so never add a guessed second token charge to usage totals.
        "retrieval_model_calls": 0 if retrieval else None,
        "retrieval_prompt_characters": sum(len((c.get("retrieval") or {}).get("rendered_context", ""))
                                            for c in generation_calls) if retrieval else None,
        "started_at": job.get("started_at"), "finished_at": job.get("finished_at"),
        "output": job["output"], "result_error": result_error,
        "error": result.get("error") or job.get("error"),
    }
    # Only calls charge the budget. Attempt usage repeats the same calls and is
    # intentionally ignored. Missing usage remains NULL; known_* are lower bounds.
    usage_valid = [isinstance(call.get("usage"), dict)
                   and all(type(call["usage"].get(key)) is int and call["usage"][key] >= 0 for key in TOKEN_FIELDS)
                   and call["usage"]["total_tokens"] == call["usage"]["prompt_tokens"] + call["usage"]["completion_tokens"]
                   for call in calls]
    unknown_calls = sum(not known for known in usage_valid)
    complete_usage = bool(result) and result.get("usage_complete", True) is True and unknown_calls == 0
    # A zero-call completed preflight/planning failure can have known zero cost;
    # a missing/interrupted checkpoint cannot establish that no request occurred.
    if not terminal and not calls:
        complete_usage = False
    row.update(usage_complete=int(complete_usage) if result else None,
               unknown_usage_calls=unknown_calls if result else None)
    review_path = output_root / job["output"] / "requirement-review.json"
    try:
        if case.get("construction_review") and result and not review_path.is_file():
            raise ValueError("Scheduled construction review evidence is missing")
        review = json.loads(review_path.read_text(encoding="utf-8")) if review_path.is_file() else {}
        if not isinstance(review, dict):
            raise ValueError("Construction review must be an object")
        row["construction_review_error"] = review.get("error")
    except (OSError, ValueError) as exc:
        # Losing an independent sidecar must not relabel valid historical model
        # results, or prevent all other batches from being summarized offline.
        review = {"first_status": "unavailable", "accepted_candidate_status": "unavailable"}
        row["construction_review_error"] = str(exc)
    row["construction_review_first"] = review.get("first_status", "not_evaluated")
    row["construction_review_accepted"] = review.get("accepted_candidate_status", "not_evaluated")
    row["construction_review_sha256"] = review.get("checklist_sha256")
    for key in TOKEN_FIELDS:
        known = sum(call["usage"][key] for call, valid in zip(calls, usage_valid) if valid)
        row["known_" + key] = known if result else None
        row[key] = known if complete_usage else None
    for phase, phase_calls in (("generation", generation_calls), ("planning", planning_calls)):
        counts = [(call["usage"]["completion_tokens"] if isinstance(call.get("usage"), dict)
                   and type(call["usage"].get("completion_tokens")) is int
                   and call["usage"]["completion_tokens"] >= 0 else None) for call in phase_calls]
        measured = [value for value in counts if value is not None]
        phase_known = bool(result) and len(counts) == len(measured)
        # Direct mode has zero planning calls by design. A zero-generation plan
        # failure also has known zero generation output, but no per-token ratio.
        row[phase + "_completion_tokens"] = sum(measured) if phase_known else None
        row[phase + "_known_completion_tokens"] = sum(measured) if result else None
        row[phase + "_unknown_usage_calls"] = len(counts) - len(measured) if result else None
    timing = result.get("timing") or {}
    for key in ("elapsed_seconds", "generation_seconds", "static_check_seconds", "osate_seconds", "retrieval_seconds",
                "repair_analysis_seconds", "repair_feedback_seconds"):
        row[key] = _number(timing.get(key))
    # Old results have no feedback version: retain unknown instead of silently
    # assigning the current policy to measurements made with an older prompt.
    row["repair_feedback_version"] = (result.get("settings", {}).get("repair_feedback") or {}).get("schema_version")
    # Sum measured call elapsed time once; retain pipeline wall time separately.
    durations = [_number(call.get("elapsed_seconds")) for call in calls]
    row["call_elapsed_seconds"] = sum(durations) if calls and all(v is not None for v in durations) else None
    for field in EVIDENCE_FIELDS:
        values = [_number((call.get("constraint_evidence") or {}).get(field)) for call in generation_calls]
        available = [v for v in values if v is not None]
        row["decoder_" + field] = _aggregate_evidence(field, available) if values and len(available) == len(values) else None
        row["decoder_known_" + field] = _aggregate_evidence(field, available)
        row["decoder_measured_calls_" + field] = len(available) if result else None
    # HTTP error envelopes can retain useful server measurements without having
    # passed the normal completion/evidence verification. Keep them in a separate
    # namespace: they must never fill decoder_* gaps or enter its overhead means.
    error_calls = [call for call in calls if call.get("status") == "error"
                   and isinstance(call.get("error_response_metadata"), dict)
                   and isinstance(call["error_response_metadata"].get("error"), dict)]
    error_types = sorted({call["error_response_metadata"]["error"]["type"] for call in error_calls
                          if isinstance(call["error_response_metadata"]["error"].get("type"), str)})
    reported = []
    for call in error_calls:
        metadata = call["error_response_metadata"]
        evidence = metadata.get("constraint", metadata.get("applied_constraint"))
        reported.append(evidence if isinstance(evidence, dict) else {})
    row["error_response_type"] = ";".join(error_types) if error_types else None
    row["server_reported_error_calls"] = len(error_calls) if result else None
    row["server_reported_error_evidence_calls"] = sum(bool(item) for item in reported) if result else None
    row["server_reported_error_evidence_status"] = "unverified_error_response" if any(reported) else None
    for field in EVIDENCE_FIELDS:
        measured = [_number(item.get(field)) for item in reported]
        available = [value for value in measured if value is not None]
        row["server_reported_error_" + field] = (_aggregate_evidence(field, available)
            if measured and len(available) == len(measured) else None)
        row["server_reported_error_known_" + field] = _aggregate_evidence(field, available)
        row["server_reported_error_measured_calls_" + field] = len(available) if result else None
    denominator = row["generation_completion_tokens"]
    for field in ("transport_mask_seconds", "aadl_syntax_seconds", "semantic_seconds"):
        seconds = row["decoder_" + field]
        # Count exactly the server's generation completion tokens (including
        # transport records/EOS), never the planning JSON completion tokens.
        row[field.removesuffix("_seconds") + "_ms_per_generation_token"] = (
            1000 * seconds / denominator if seconds is not None and denominator else None)
    truncated = any(call.get("error_kind") == "truncated" or call.get("finish_reason") == "length" for call in calls)
    dead_end = any(call.get("error_kind") == "decoding_dead_end" for call in calls)
    row["had_truncation"] = int(truncated or any(a.get("generation_truncated") for a in attempts)) if result else None
    row["had_decoding_dead_end"] = int(dead_end or any(a.get("decoding_dead_end") for a in attempts)) if result else None
    # Transport timeouts are infrastructure failures, but still have their own
    # descriptive flag. No inferred token amount is imputed for a timed-out call.
    errors = " ".join(str(call.get("error", "")) for call in calls) + " " + str(row["error"] or "")
    row["had_timeout"] = int("timed out" in errors.lower() or "timeout" in errors.lower()) if result or row["error"] else None
    # Only the client-verified protocol-v2 outcome changes efficacy accounting.
    # An old HTTP 504 whose raw error.type happens to have this name stays infra.
    row["had_generation_timeout"] = int(kind == "generation_timeout" or bool(row["generation_timeout_calls"])) if result else None
    paired = [attempt for attempt in attempts if type(attempt.get("syntax_semantics_valid")) is bool
              and (attempt.get("osate") or {}).get("status") in {"passed", "failed"}
              and not attempt.get("rule_error") and attempt.get("generation_complete") is True
              and (attempt.get("project_protocol") or {}).get("valid") is True
              and (attempt.get("project_protocol") or {}).get("complete") is True]
    # Compare only complete candidates. An open envelope with an extractable
    # valid AADL file is not evidence of semantic over-rejection. The local flag
    # still combines syntax/static semantics/plan checks, so these are observed
    # disagreements, not automatically established checker false positives.
    row["local_osate_comparisons"] = len(paired) if result else None
    row["local_pass_osate_fail"] = sum(a["syntax_semantics_valid"] and a["osate"]["status"] == "failed" for a in paired) if result else None
    row["local_fail_osate_pass"] = sum(not a["syntax_semantics_valid"] and a["osate"]["status"] == "passed" for a in paired) if result else None
    # Preserve the distinction between rule outcomes and errors. Satisfied
    # issues are evidence of checks performed, never diagnostics to count.
    issues = [issue for attempt in attempts for issue in attempt.get("issues", [])]
    # A Pending obligation on a truncated/open project is expected deferral.
    # It becomes a closure failure only for a completed generation/container.
    closed_issues = [issue for attempt in attempts
                     if attempt.get("generation_complete") is True
                     and (attempt.get("project_protocol") or {}).get("complete") is True
                     for issue in attempt.get("issues", [])]
    closed_pending = [issue for issue in closed_issues if issue.get("status") == "Pending"]
    plan_conflicts = [issue for issue in issues if issue.get("status") == "Conflict"
                      and str(issue.get("rule_id", "")).startswith("plan.")]
    plan_closure = [issue for issue in closed_pending if str(issue.get("rule_id", "")).startswith("plan.")]
    row["local_conflict_issues"] = sum(issue.get("status") == "Conflict" for issue in issues) if result else None
    row["local_pending_issues"] = sum(issue.get("status") == "Pending" for issue in issues) if result else None
    row["local_closed_pending_issues"] = len(closed_pending) if result else None
    row["invalid_planning_schema"] = int(kind == "invalid_plan") if result else None
    row["plan_promise_failure"] = int(bool(plan_conflicts or plan_closure)) if result else None
    diagnostics = [str(message) for a in attempts for message in a.get("diagnostics", [])]
    categories = {
        "container": any((a.get("project_protocol") or {}).get("valid") is False for a in attempts),
        # These are positively identified diagnostics, not an invented separate
        # syntax pass rate from the combined syntax_semantics_valid flag.
        "syntax": any(message.startswith("syntax:") or message == "Incomplete source syntax" for message in diagnostics),
        "static_semantics": any(issue.get("status") == "Conflict" for issue in issues) or bool(closed_pending)
                            or any(message.startswith("semantic:") for message in diagnostics),
        # The real catalog uses plan.declaration_set/signature, plan.interface*,
        # plan.header_prefix, and plan.fulfilled. Keep invalid planning JSON
        # distinct from a later generated project breaking an accepted plan.
        "planning": kind == "invalid_plan" or bool(plan_conflicts or plan_closure),
        "instance": any((a.get("osate") or {}).get("instance", {}).get("status") == "failed"
                        or (a.get("local_instance_rules") or {}).get("status") == "failed" for a in attempts),
        "unfinished": truncated or any(a.get("generation_complete") is False
                          or (a.get("project_protocol") or {}).get("complete") is False for a in attempts),
        "decoding_dead_end": dead_end,
        "infrastructure": infrastructure,
        "generation_timeout": row["had_generation_timeout"] == 1,
    }
    row["error_categories"] = json.dumps([name for name, present in categories.items() if present]) if started else None
    for name, present in categories.items():
        row["had_" + name + "_failure"] = int(present) if result or terminal else None
    response_texts = [call.get("response_text") for call in generation_calls]
    # Characters/UTF-8 bytes help inspect cross-tokenizer costs, but are not a
    # replacement for measured model tokens or comparable wall-clock budgets.
    row["generated_characters"] = sum(len(s) for s in response_texts) if response_texts and all(isinstance(s, str) for s in response_texts) else None
    row["generated_utf8_bytes"] = sum(len(s.encode("utf-8")) for s in response_texts) if response_texts and all(isinstance(s, str) for s in response_texts) else None
    # A first candidate has no predecessor. Unknown legacy observations stay
    # NULL, rather than claiming that its repair changed or repeated anything.
    for field in ("candidate_changed", "focus_pattern_reobserved", "focus_diagnostic_reobserved"):
        observed = [a[field] for a in attempts if type(a.get(field)) is bool]
        row[field + "_observations"] = len(observed) if result else None
        row[field + "_count"] = sum(observed) if observed else None
        row[field + "_false_count"] = sum(not value for value in observed) if observed else None
    return row


def _rate(numerator: int, denominator: int):
    return numerator / denominator if denominator else None


def summary_rows(rows: list[dict]) -> list[dict]:
    groups = defaultdict(list)
    for row in rows:
        groups[(row.get("experiment_protocol", "legacy-six-condition"), row.get("experiment_protocol_version", 1),
                row["batch"], row["family"], row["requirement_origin"], row.get("source_url") or "",
                row.get("selection_role", "unspecified"), row["condition"],
                row.get("generation_scope_status", "unknown"),
                ("eligible" if row.get("independent_evaluation_eligible") is True else
                 "ineligible" if row.get("independent_evaluation_eligible") is False else "unknown"))].append(row)
    summaries = []
    for (protocol_id, protocol_version, batch, family, origin, source_url, role, condition, scope, eligibility), group in sorted(groups.items()):
        evaluable = [row for row in group if row["evaluable"]]
        independent = [row for row in group if row.get("independent_core_evaluable") == 1]
        started = [row for row in group if row["started"]]
        terminal = [row for row in group if row["terminal"]]
        first = sum(row["first_osate_pass"] == 1 for row in evaluable)
        budget = sum(row["budget_osate_pass"] == 1 for row in evaluable)
        summary = {
            "batch": batch, "family": family, "requirement_origin": origin, "condition": condition,
            "experiment_protocol": protocol_id, "experiment_protocol_version": protocol_version,
            "generation_scope_status": scope,
            "independent_evaluation_eligibility": eligibility,
            "n_independent_core_evaluable": len(independent),
            "independent_core_first_osate_passes": sum(row["first_osate_pass"] == 1 for row in independent),
            "independent_core_budget_osate_passes": sum(row["budget_osate_pass"] == 1 for row in independent),
            "independent_core_first_osate_rate": _rate(sum(row["first_osate_pass"] == 1 for row in independent), len(independent)),
            "independent_core_budget_osate_rate": _rate(sum(row["budget_osate_pass"] == 1 for row in independent), len(independent)),
            "source_url": source_url or None,
            "selection_role": role,
            "retrieval_enabled": group[0].get("retrieval_enabled"),
            "repair_feedback_versions": json.dumps(sorted({r["repair_feedback_version"] for r in group
                                                           if r.get("repair_feedback_version") is not None})),
            "generation_protocol_versions": json.dumps(sorted({r["generation_protocol_version"] for r in group if r.get("generation_protocol_version") is not None})),
            "generation_timeout_calls": sum(r.get("generation_timeout_calls") or 0 for r in group),
            "repetition_stop_calls": sum(r.get("repetition_stop_calls") or 0 for r in group),
            "n_scheduled": len(group), "n_unique_input_hashes": len({row["input_sha256"] for row in group}),
            "n_started": len(started), "n_terminal": len(terminal), "n_evaluable": len(evaluable),
            "n_not_started": sum(row["state"] == "not_started" for row in group),
            "n_running": sum(row["state"] == "running" for row in group),
            "n_interrupted": sum(row["state"] == "interrupted" for row in group),
            "n_pipeline_finished": sum(row["state"] == "finished" for row in group),
            "n_pipeline_failed": sum(row["state"] == "failed" for row in group),
            "infrastructure_count": sum(row["infrastructure_failure"] for row in group),
            "completion_coverage": _rate(len(terminal), len(group)),
            "evaluation_coverage": _rate(len(evaluable), len(group)),
            "first_osate_passes": first, "budget_osate_passes": budget,
            "first_osate_rate": _rate(first, len(evaluable)),
            "budget_osate_rate": _rate(budget, len(evaluable)),
            # Conservative rates include infrastructure/interruption in the
            # denominator. Only observed passes contribute to the numerator.
            "first_osate_rate_all_started": _rate(sum(row["first_osate_pass"] == 1 for row in started), len(started)),
            "budget_osate_rate_all_started": _rate(sum(row["budget_osate_pass"] == 1 for row in started), len(started)),
            "n_usage_complete": sum(row["usage_complete"] == 1 for row in started),
            "n_usage_unknown": sum(row["usage_complete"] != 1 for row in started),
            "requirements_verified": False, "requirement_coverage": None,
            "construction_review_first_counts": json.dumps(dict(Counter(r.get("construction_review_first", "not_evaluated") for r in group)), sort_keys=True),
            "construction_review_accepted_counts": json.dumps(dict(Counter(r.get("construction_review_accepted", "not_evaluated") for r in group)), sort_keys=True),
            "failure_counts": json.dumps(dict(Counter(row["failure_kind"] for row in started if row["failure_kind"])), sort_keys=True),
        }
        # Means include successful AND failed started runs with a measurement;
        # each mean has its denominator so missing costs cannot become zeros.
        for field in (*TOKEN_FIELDS, "generation_completion_tokens", "planning_completion_tokens",
                      "elapsed_seconds", "call_elapsed_seconds", "repair_calls", "retrieval_seconds",
                      "repair_analysis_seconds", "repair_feedback_seconds",
                      "retrieval_operations", "retrieval_selected_entries", "retrieval_prompt_characters",
                      "decoder_transport_mask_seconds", "decoder_aadl_syntax_seconds", "decoder_semantic_seconds",
                      "transport_mask_ms_per_generation_token", "aadl_syntax_ms_per_generation_token", "semantic_ms_per_generation_token"):
            values = [row[field] for row in started if _number(row.get(field)) is not None]
            summary["mean_" + field] = mean(values) if values else None
            summary["n_measured_" + field] = len(values)
        for field in ("local_osate_comparisons", "local_pass_osate_fail", "local_fail_osate_pass",
                      "local_conflict_issues", "local_pending_issues", "local_closed_pending_issues",
                      "invalid_planning_schema", "plan_promise_failure"):
            values = [row[field] for row in started if row[field] is not None]
            summary[field] = sum(values) if values else None
        for field in ("candidate_changed", "focus_pattern_reobserved", "focus_diagnostic_reobserved"):
            for suffix in ("_observations", "_count", "_false_count"):
                values = [row.get(field + suffix) for row in started if row.get(field + suffix) is not None]
                summary[field + suffix] = sum(values) if values else None
        for category in ("container", "syntax", "static_semantics", "planning", "instance", "unfinished", "decoding_dead_end", "infrastructure", "generation_timeout"):
            summary["runs_with_" + category + "_failure"] = sum(row["had_" + category + "_failure"] == 1 for row in started)
        error_runs = [row for row in started if (row["server_reported_error_calls"] or 0) > 0]
        error_call_count = sum(row["server_reported_error_calls"] for row in error_runs)
        summary["n_error_response_runs"] = len(error_runs)
        summary["server_reported_error_calls"] = error_call_count
        summary["n_server_reported_error_evidence_runs"] = sum(
            (row["server_reported_error_evidence_calls"] or 0) > 0 for row in error_runs)
        summary["server_reported_error_evidence_status"] = (
            "unverified_error_response" if summary["n_server_reported_error_evidence_runs"] else None)
        summary["error_response_type_run_counts"] = json.dumps(dict(Counter(
            kind for row in error_runs for kind in (row["error_response_type"] or "").split(";") if kind)), sort_keys=True)
        for field in EVIDENCE_FIELDS:
            measured_values = [row["decoder_" + field] for row in started if row["decoder_" + field] is not None]
            summary["mean_decoder_" + field] = mean(measured_values) if measured_values else None
            # Report verified-measurement coverage against all started jobs,
            # including slow failures; the raw error measurements remain separate.
            verified_runs = sum(row["decoder_" + field] is not None for row in started)
            summary["n_measured_decoder_" + field] = verified_runs
            summary["decoder_" + field + "_measurement_coverage_started"] = _rate(verified_runs, len(started))
            measured_calls = sum(row["server_reported_error_measured_calls_" + field] or 0 for row in error_runs)
            measured_runs = sum(row["server_reported_error_" + field] is not None for row in error_runs)
            known = [row["server_reported_error_known_" + field] for row in error_runs
                     if row["server_reported_error_known_" + field] is not None]
            summary["server_reported_error_known_" + field] = _aggregate_evidence(field, known)
            summary["server_reported_error_" + field] = (
                _aggregate_evidence(field, known) if error_call_count and measured_calls == error_call_count else None)
            summary["n_measured_server_reported_error_" + field] = measured_runs
            summary["server_reported_error_measured_calls_" + field] = measured_calls
            summary["server_reported_error_" + field + "_measurement_coverage_calls"] = _rate(measured_calls, error_call_count)
        summaries.append(summary)
    return summaries


def _atomic_text(path: Path, content: str, *, encoding: str = "utf-8") -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(content, encoding=encoding, newline="")
    temporary.replace(path)


def _write_csv(path: Path, rows: list[dict]) -> None:
    fields = list(dict.fromkeys(key for row in rows for key in row)) or ["batch", "case_id", "condition", "state"]
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=fields)
    writer.writeheader()
    writer.writerows(rows)
    # Excel on Windows needs a BOM to reliably recognize Chinese source names.
    _atomic_text(path, stream.getvalue(), encoding="utf-8-sig")


def export_metrics(output_root: Path, manifest: dict) -> tuple[list[dict], list[dict]]:
    rows = []
    case_rows = defaultdict(list)
    for batch in manifest.get("batches", []):
        cases = {case["id"]: case for case in batch["cases"]}
        for job in batch["jobs"]:
            case = cases[job["case_id"]]
            row = job_metrics(output_root, batch, case, job)
            rows.append(row)
            case_rows[case["output"]].append(row)
    summaries = summary_rows(rows)
    _write_csv(output_root / "experiments.csv", rows)
    _write_csv(output_root / "summary.csv", summaries)
    _atomic_text(output_root / "summary.json", json.dumps({
        "schema_version": 3, "unit": "requirement-condition-batch",
        "experiment_protocols": "two-group-repair v3 (single generated source; plain once, joint up to six attempts), read-only v2/v1 and legacy-six-condition v1; grouped separately",
        "comparison_scope": "plain-once versus joint-repair changes constraints and repair together; not a pure constraint ablation",
        "warmup": "excluded; recorded separately in each batch warmup record",
        "protocol_transition": "client-verified generation_timeout is an efficacy failure; historical infrastructure/raw-error classifications remain unchanged",
        "effect_denominator": "n_evaluable: terminal runs excluding infrastructure failures",
        "independent_core_denominator": "n_independent_core_evaluable: evaluable, reviewed in_scope, explicitly independent_evaluation_eligible; derived and unknown eligibility excluded",
        "conservative_denominator": "n_started: includes infrastructure and interrupted runs",
        "generation_token_denominator": "generation calls only, including project transport/EOS; excludes planning JSON",
        "server_reported_error_evidence": "unverified error envelopes; separate from decoder evidence and normal overhead means",
        "measurement_coverage": "decoder coverage uses all started runs; server-reported-error coverage uses structured error-response calls",
        "repeated_batches": "separate groups, not additional independent tasks",
        "requirement_coverage": "not_evaluated", "groups": summaries,
    }, ensure_ascii=False, indent=2) + "\n")
    for relative, items in case_rows.items():
        target = output_root / relative
        target.mkdir(parents=True, exist_ok=True)
        lines = ["AADL experiment results (all batches; repeated batches are not independent tasks).",
                 "OSATE checks static validity. Optional frozen construction assertions do not prove full requirement satisfaction.",
                 "Main rates exclude infrastructure/interrupted/not-started jobs; see summary.csv denominators.", ""]
        for row in items:
            lines.append(f"{row['batch']} | {row['experiment_protocol']} v{row['experiment_protocol_version']} | {row['condition']} | {row['state']} | "
                         f"first_osate={row['first_osate_pass']} | budget_osate={row['budget_osate_pass']} | "
                         f"failure={row['failure_kind']} | completion_tokens={row['completion_tokens']} | "
                         f"elapsed_seconds={row['elapsed_seconds']} | max_attempts={row['max_attempts']} | "
                         f"repair_calls={row['repair_calls']} | {row['output']}")
            if row.get("repair_feedback_version") is not None:
                lines.append(f"  repair_feedback_version={row['repair_feedback_version']} | "
                             f"repair_feedback_seconds={row['repair_feedback_seconds']} (included in elapsed_seconds)")
            lines.append(f"  generation_scope={row['generation_scope_status']} | "
                         f"independent_evaluation_eligible={row['independent_evaluation_eligible']} | "
                         f"independent_core_evaluable={row['independent_core_evaluable']}")
            if row.get("retrieval_enabled") is not None:
                lines.append(f"  retrieval={row['retrieval_enabled']} | retrieval_seconds={row['retrieval_seconds']} | "
                             f"initial_evidence_sha256={row['retrieval_initial_sha256']}")
            if row["construction_review_accepted"] != "not_evaluated":
                lines.append(f"  construction_review: first={row['construction_review_first']} | accepted={row['construction_review_accepted']}")
        _atomic_text(target / "result.txt", "\n".join(lines) + "\n")
    return rows, summaries
