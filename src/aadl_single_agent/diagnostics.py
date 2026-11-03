"""Explicit offline diagnostics: immutable evidence snapshots, replay and rule cases.

No model, service, OSATE, or deployment is invoked. Replay is a bounded text-prefix
experiment under the CURRENT rules, not reconstruction of model token probabilities.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys
import time


def _hash(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _write_new(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="") as stream:
        stream.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def freeze_result(source: Path, output: Path) -> dict:
    """Freeze exact result bytes and returned partial text before any replay."""
    source = source.resolve()
    raw = source.read_bytes()
    result = json.loads(raw.decode("utf-8-sig"))
    if not isinstance(result, dict):
        raise ValueError("Result must be a JSON object")
    calls, mechanisms = [], []
    for index, call in enumerate(result.get("calls", []), 1):
        text, origin = call.get("response_text"), "response_text"
        if not isinstance(text, str):
            partial = Path(call["partial_output"]).resolve() if call.get("partial_output") else None
            if partial and partial.is_relative_to(source.parent) and partial.is_file():
                text, origin = partial.read_bytes().decode("utf-8"), "partial_output"
            else:
                text, origin = None, "unavailable"
        calls.append({"index": index, "phase": call.get("phase"), "text": text,
                      "text_origin": origin, "text_sha256": _hash(text.encode()) if text is not None else None})
        evidence = call.get("constraint_evidence") or {}
        metadata = call.get("metadata") or call.get("error_response_metadata") or {}
        # Raw mechanism traces remain separate from summary counters and replay.
        mechanisms.append({"call": index, "phase": call.get("phase"),
                           "constraint_evidence": evidence, "response_metadata": metadata})
    snapshot = {"schema_version": 1, "source": str(source), "source_result_sha256": _hash(raw),
                "calls": calls, "original_provenance": result.get("provenance")}
    output.mkdir(parents=True, exist_ok=False)
    (output / "result.json").write_bytes(raw)
    _write_new(output / "snapshot.json", snapshot)
    _write_new(output / "mechanism.json", {"schema_version": 1, "calls": mechanisms,
                                           "interpretation": "original evidence; not replay measurements"})
    return {"snapshot": str(output), "result_sha256": _hash(raw), "calls": len(calls)}


def stopping_regressions() -> dict:
    """Measure the generic stop policy, including a deliberate legal false stop.

    A valid JSON string can contain a long exact repetition. Keeping that witness
    makes the heuristic's loss of valid continuations visible without inventing
    a language-specific exemption or calling the text a semantic conflict.
    """
    from .decoder import RepeatedLineDetector, repetition_stop_configuration
    periodic = "abcdefgh" * 32
    samples = (
        ("completed_lines", "line\n" * 32, True, None),
        ("single_line_cycle", periodic, True, None),
        ("below_span", "x" * 255, False, None),
        ("below_repetitions", "abcdefghijklmnopqrstuvwxyz0123456789" * 7, False, None),
        ("unicode_cycle", "猫" * 256, True, None),
        ("legal_json_string", json.dumps({"description": periodic}), True, True),
    )
    observations = []
    for name, text, expected, legal in samples:
        if legal:
            json.loads(text)  # Independently establish that the full witness is legal JSON.
        detector = RepeatedLineDetector()
        position = None
        # Character boundaries are explicit here; actual tokenizer replay is a
        # separate diagnostic and must not be inferred from these observations.
        for offset in range(1, len(text) + 1):
            if detector.observe(text[:offset]):
                position = offset
                break
        stopped = position is not None
        observations.append({"name": name, "passed": stopped == expected,
                             "stopped": stopped, "expected_stop": expected,
                             "first_observed_character": position,
                             "complete_text_valid_json": legal,
                             "heuristic_false_stop": bool(legal and stopped),
                             "termination": deepcopy(detector.termination),
                             "observation_seconds": detector.observation_seconds})
    return {"policy": repetition_stop_configuration(), "cases": observations,
            "passed": all(item["passed"] for item in observations),
            "legal_witness_count": sum(item["complete_text_valid_json"] is True for item in observations),
            "legal_witness_false_stops": sum(item["heuristic_false_stop"] for item in observations),
            "interpretation": "mechanism witnesses; not an estimated population false-stop rate"}


def rule_regressions(bundle) -> dict:
    cases = getattr(bundle.semantics, "METHOD_REGRESSIONS", None)
    if not isinstance(cases, tuple) or not cases:
        raise ValueError("Rule bundle must provide a nonempty METHOD_REGRESSIONS tuple")
    observations = []
    for case in cases:
        required = {"name", "prefix", "probe", "expected_allowed"}
        if not isinstance(case, dict) or not required <= case.keys() or type(case["expected_allowed"]) is not bool:
            raise ValueError("Malformed METHOD_REGRESSIONS entry")
        engine = bundle.create_engine(project=case.get("project", False))
        started = time.perf_counter()
        base = engine.probe(engine.start(), case["prefix"])
        before = (base.state.text, base.state.complete, base.state.pending)
        trial = engine.probe(base.state, case["probe"]) if base.allowed else None
        repeated = engine.probe(base.state, case["probe"]) if base.allowed else None
        stable = before == (base.state.text, base.state.complete, base.state.pending)
        ok = bool(base.allowed and trial.allowed == case["expected_allowed"]
                  and repeated.allowed == trial.allowed and repeated.reason == trial.reason and stable)
        if case.get("expected_rule"):
            ok = ok and case["expected_rule"] in trial.reason if trial else False
        witness = case.get("complete_source")
        witness_valid = None
        if witness is not None:
            # A complete legal continuation witnesses that a permitted prefix
            # was not accepted only because all remaining text was ignored.
            continuation = engine.probe(base.state, witness[len(case["prefix"]):]) if base.allowed else None
            witness_valid = (witness.startswith(case["prefix"] + case["probe"])
                             and continuation is not None and continuation.allowed
                             and engine.validate(witness).valid)
            ok = ok and witness_valid
        observations.append({"name": case["name"], "passed": bool(ok), "base_allowed": base.allowed,
                             "expected_allowed": case["expected_allowed"], "allowed": trial.allowed if trial else None,
                             "reason": trial.reason if trial else base.reason, "base_unchanged": stable,
                             "complete_witness_valid": witness_valid,
                             "elapsed_seconds": time.perf_counter() - started})
    stopping = stopping_regressions()
    return {"schema_version": 1, "kind": "method_regressions", "rule_bundle_sha256": bundle.digest,
            "passed": all(x["passed"] for x in observations) and stopping["passed"],
            "cases": observations, "stopping_policy": stopping}


def replay_snapshot(snapshot_dir: Path, bundle, *, prefixes: int = 3, max_characters: int = 4096,
                    trials: tuple[str, ...] = (" ", "\n", ";")) -> dict:
    from .decoder import RepeatedLineDetector, repetition_stop_configuration
    frozen = json.loads((snapshot_dir / "snapshot.json").read_text(encoding="utf-8"))
    if _hash((snapshot_dir / "result.json").read_bytes()) != frozen["source_result_sha256"]:
        raise ValueError("Frozen result hash changed")
    calls = []
    for call in frozen["calls"]:
        text = call["text"]
        if text is None:
            calls.append({"call": call["index"], "status": "text_unavailable"})
            continue
        if _hash(text.encode()) != call["text_sha256"]:
            raise ValueError("Frozen call text hash changed")
        detector = RepeatedLineDetector()
        # Sample at most 512 decoded-text boundaries. This locates a witnessed
        # cycle without pretending to recover the original BPE token boundaries
        # or an exact first-stop position from a text-only frozen result.
        stride = max(1, (len(text) + 511) // 512)
        checkpoints = list(range(stride, len(text), stride)) + [len(text)]
        stopped, observed_at, observations_count = False, None, 0
        for position in checkpoints:
            observations_count += 1
            if detector.observe(text[:position]):
                stopped, observed_at = True, position
                break
        observations = []
        # Planning is JSON, so never send its text to the AADL project engine.
        if call["phase"] == "generation":
            limit = min(len(text), max_characters)
            positions = sorted({max(1, limit * step // prefixes) for step in range(1, prefixes + 1)}) if limit else [0]
            for position in positions:
                engine = bundle.create_engine(project=True)
                started = time.perf_counter()
                base = engine.probe(engine.start(), text[:position])
                baseline = (base.state.text, base.state.complete, base.state.pending)
                proposals = []
                for piece in trials if base.allowed else ():
                    before = dict(engine.counters)
                    t0 = time.perf_counter()
                    trial = engine.probe(base.state, piece)
                    proposals.append({"piece": piece, "allowed": trial.allowed, "reason": trial.reason,
                                      "elapsed_seconds": time.perf_counter() - t0,
                                      "counter_delta": {k: v - before.get(k, 0) for k, v in engine.counters.items()}})
                observations.append({"character_offset": position, "prefix_allowed": base.allowed,
                                     "prefix_reason": base.reason, "trials": proposals,
                                     "base_unchanged": baseline == (base.state.text, base.state.complete, base.state.pending),
                                     "elapsed_seconds": time.perf_counter() - started})
        calls.append({"call": call["index"], "phase": call["phase"], "repetition_detected": stopped,
                      "observed_at_character": observed_at, "repetition_sample_stride": stride,
                      "repetition_observations": observations_count,
                      "termination": deepcopy(detector.termination), "prefix_probes": observations})
    return {"schema_version": 1, "kind": "offline_text_replay", "source_result_sha256": frozen["source_result_sha256"],
            "rule_bundle_sha256": bundle.digest, "policy": repetition_stop_configuration(),
            "limits": {"prefixes_per_call": prefixes, "max_prefix_characters": max_characters, "trials": trials},
            "interpretation": "current-policy text probes; no model probabilities, token replay, or efficacy estimate", "calls": calls}


def audit_experiment_batch(output_root: Path, batch: dict) -> dict:
    """Check group policy and shared budgets offline, without rewriting results."""
    from .experiments import CONDITION_SETTINGS, _require_current_protocol
    _require_current_protocol(batch)
    checks = []

    def check(name, value, job=None, detail=None):
        checks.append({"check": name, "job": job, "status": "unavailable" if value is None else "passed" if value else "failed", "detail": detail})

    cases = batch["cases"]
    conditions = batch["config"]["conditions"]
    jobs = batch["jobs"]
    expected_order = []
    for index, case in enumerate(cases):
        offset = index % len(conditions)
        expected_order.extend((case["id"], c) for c in conditions[offset:] + conditions[:offset])
    actual_order = [(j["case_id"], j["condition"]) for j in jobs]
    check("unique_rotating_cross_product", actual_order == expected_order and len(set(actual_order)) == len(actual_order))
    warmup = batch.get("warmup")
    if warmup:
        check("warmup_excluded", warmup.get("included_in_statistics") is False
              and all(j["output"] != warmup["output"] and not j["output"].startswith(warmup["output"] + "/") for j in jobs))
    for job in jobs:
        label = job["case_id"] + "|" + job["condition"]
        path = output_root / job["output"] / "result.json"
        if not path.is_file():
            check("result_available", False if job["state"] in {"finished", "failed"} else None, label)
            continue
        result = json.loads(path.read_text(encoding="utf-8-sig"))
        expected = CONDITION_SETTINGS[job["condition"]]
        settings = result.get("settings") or {}
        check("actual_policy", settings.get("repair_enabled") is expected["repair_enabled"]
              and settings.get("max_attempts") == expected["max_attempts"]
              and settings.get("token_budget") == 8192 and result.get("constraints") == expected["constraints"], label)
        check("single_generated_file_policy", settings.get("generated_file_limit") == 1, label)
        calls = result.get("calls") or []
        retrieval_enabled = batch["config"]["retrieval_enabled"]
        check("retrieval_policy", settings.get("retrieval_enabled") is retrieval_enabled, label)
        if calls:
            case = next(c for c in cases if c["id"] == job["case_id"])
            record = calls[0].get("retrieval") or {}
            check("paired_initial_retrieval", record.get("sha256") == case["retrieval_initial_sha256"]
                  if retrieval_enabled else not record.get("rendered_context"), label)
        for call in calls:
            record = call.get("retrieval") or {}
            check("retrieval_enabled_flag", record.get("enabled") is retrieval_enabled, label)
        check("no_planning_calls", all(c.get("phase") == "generation" for c in calls), label)
        check("group_call_limit", len(calls) <= expected["max_attempts"], label)
        check("group_candidate_limit", len(result.get("attempts") or []) <= expected["max_attempts"], label)
        known = 0
        complete_usage = True
        for index, call in enumerate(calls):
            call_label = label + f"/call-{index + 1}"
            limit = call.get("max_tokens")
            check("remaining_output_budget", type(limit) is int and 0 < limit <= 8192 - known, call_label)
            usage = call.get("usage")
            valid = (isinstance(usage, dict) and all(type(usage.get(k)) is int and usage[k] >= 0
                     for k in ("prompt_tokens", "completion_tokens", "total_tokens"))
                     and usage["total_tokens"] == usage["prompt_tokens"] + usage["completion_tokens"])
            if valid:
                check("call_output_budget", type(limit) is int and usage["completion_tokens"] <= limit, call_label)
                known += usage["completion_tokens"]
            else:
                complete_usage = False
                check("usage_available", None, call_label, "Unknown consumption is never treated as zero")
            if call.get("error_kind") == "generation_timeout":
                check("deadline_not_retried", index == len(calls) - 1, call_label)
        check("shared_output_budget", known <= 8192, label)
        if complete_usage:
            check("usage_charged_once", (result.get("usage") or {}).get("completion_tokens") == known, label)
    failed = any(c["status"] == "failed" for c in checks)
    unavailable = sum(c["status"] == "unavailable" for c in checks)
    return {"schema_version": 1, "kind": "two_group_experiment_audit", "batch": batch["id"],
            "experiment_protocol": batch["experiment_protocol"], "experiment_protocol_version": batch["experiment_protocol_version"],
            "passed": not failed and not unavailable, "failed_checks": sum(c["status"] == "failed" for c in checks),
            "unavailable_checks": unavailable, "checks": checks}


def _compact_json(value) -> str:
    """Match the feedback wire representation when measuring UTF-8 budgets."""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _recorded_feedback(calls: list, attempt_number: int) -> dict:
    """Read the next recorded request, without claiming it reached the model.

    Older runs may have a planning call before generation. Count generation
    requests only, and never mistake the original requirements for feedback.
    A final candidate (including a non-retryable timeout) has no sent feedback
    merely because we can construct a hypothetical message for it offline.
    """
    generation = [call for call in calls if isinstance(call, dict) and call.get("phase") == "generation"]
    if attempt_number >= len(generation):
        return {"status": "not_sent_no_next_generation_call", "utf8_bytes": None,
                "sha256": None, "text": None}
    following = generation[attempt_number]
    messages = following.get("messages")
    users = [m.get("content") for m in messages if isinstance(m, dict) and m.get("role") == "user"] if isinstance(messages, list) else []
    if len(users) < 2 or not isinstance(users[-1], str):
        return {"status": "unavailable_in_recorded_next_call", "utf8_bytes": None,
                "sha256": None, "text": None, "next_call_status": following.get("status")}
    text = users[-1]
    return {"status": "recorded_for_next_generation_call", "utf8_bytes": len(text.encode("utf-8")),
            "sha256": _hash(text.encode("utf-8")), "text": text,
            "next_call_status": following.get("status"),
            "meaning": "Saved request content, not independent proof of model receipt or effect."}


def _remaining_feedback_budget(result: dict, attempt_number: int) -> int | None:
    """Recover the shared remaining budget only from complete known call usage."""
    budget = (result.get("settings") or {}).get("token_budget")
    if type(budget) is not int or budget < 1:
        return None
    generation_count, consumed = 0, 0
    for call in result.get("calls", []):
        if not isinstance(call, dict):
            return None
        usage = call.get("usage")
        completion = usage.get("completion_tokens") if isinstance(usage, dict) else None
        if type(completion) is not int or completion < 0:
            return None
        consumed += completion
        generation_count += call.get("phase") == "generation"
        if generation_count == attempt_number:
            return max(0, budget - consumed)
    return None


def rebuild_feedback(source: Path, bundle) -> dict:
    """Reconstruct CURRENT prompt evidence from historical, unmodified outputs.

    Read only each conventional attempt-N/raw-response.txt beneath this result.
    Stored candidate/raw/source paths are untrusted metadata, not permission to
    read another file. The parser's received sources are the only source input;
    this operation neither revalidates the candidate nor retrieves references.
    """
    from .pipeline import prepare_repair_feedback, build_repair_evidence, build_repair_message
    from .project import parse_project

    source = source.resolve()
    raw_result = source.read_bytes()
    result = json.loads(raw_result.decode("utf-8-sig"))
    if (not isinstance(result, dict) or not isinstance(result.get("attempts", []), list)
            or not isinstance(result.get("calls", []), list)):
        raise ValueError("Result must be a JSON object with attempt and call lists")
    observations, integrity_failures, read_files = [], [], []
    seen_numbers = set()
    previous_hashes = set()
    for position, entry in enumerate(result.get("attempts", [])):
        if not isinstance(entry, dict):
            observations.append({"record_index": position, "status": "unavailable_malformed_attempt"})
            continue
        number = entry.get("attempt")
        observation = {"record_index": position, "attempt": number,
                       "original_feedback": _recorded_feedback(result.get("calls", []), number)
                       if type(number) is int and number > 0 else None}
        observations.append(observation)
        if type(number) is not int or number < 1 or number in seen_numbers:
            observation["status"] = "unavailable_invalid_or_duplicate_attempt_number"
            integrity_failures.append(f"Attempt record {position} has an invalid/duplicate number")
            continue
        seen_numbers.add(number)
        # Legacy assessed candidates can lack final_validation. Rebuild their
        # context, but leave missing validation/history observations unknown.
        if not isinstance(entry.get("final_validation"), dict) and not isinstance(entry.get("osate"), dict):
            observation["status"] = "unavailable_unevaluated_candidate"
            continue
        candidate_path = source.parent / f"attempt-{number}" / "raw-response.txt"
        resolved = candidate_path.resolve()
        if not resolved.is_relative_to(source.parent):
            observation["status"] = "unavailable_raw_path_outside_result"
            integrity_failures.append(f"Attempt {number} raw path escapes the result directory")
            continue
        if not resolved.is_file():
            observation["status"] = "unavailable_raw_response"
            observation["expected_raw_path"] = str(candidate_path)
            continue
        raw = resolved.read_bytes()
        digest = _hash(raw)
        read_files.append((resolved, digest))
        expected = entry.get("candidate_sha256")
        matches = digest == expected if isinstance(expected, str) else None
        observation.update(raw_path=str(resolved), raw_sha256=digest,
                           candidate_sha256=expected, raw_hash_matches_candidate_sha256=matches)
        if matches is False:
            observation["status"] = "unavailable_raw_hash_mismatch"
            integrity_failures.append(f"Attempt {number} raw-response hash does not match candidate_sha256")
            continue
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            observation["status"] = "unavailable_invalid_utf8"
            integrity_failures.append(f"Attempt {number} raw-response is not UTF-8")
            continue
        document = parse_project(text)
        sources = [{"name": item.name, "text": item.text, "complete": item.complete} for item in document.files]
        # The current candidate is included, as on the online repair path. No
        # later candidate may leak into the history of an earlier attempt.
        history = result["attempts"][:position + 1]
        input_before = deepcopy((sources, entry, history))
        prepared = prepare_repair_feedback(bundle, sources, entry, history)
        evidence = build_repair_evidence(prepared)
        remaining = _remaining_feedback_budget(result, number)
        message = (build_repair_message(bundle, prepared, remaining=remaining,
                                       previous_text=text, repeated=digest in previous_hashes)
                   if remaining is not None else None)
        previous_hashes.add(digest)
        unchanged = (sources, entry, history) == input_before
        if not unchanged:
            integrity_failures.append(f"Attempt {number} feedback preparation mutated its input")
        context = prepared.get("repair_source_context")
        short_history = prepared.get("repair_history")
        observation.update(
            status="rebuilt" if matches else "rebuilt_legacy_hash_unknown",
            inputs_unchanged=unchanged,
            stage={"generation_complete": entry.get("generation_complete"),
                   "parsed_container_complete": document.complete,
                   "parsed_container_diagnostics": list(document.diagnostics),
                   "focus_state": prepared.get("repair_focus_state", evidence.get("focus_state")),
                   "termination": entry.get("generation_termination")},
            received_sources=[{"name": item["name"], "complete": item["complete"],
                               "sha256": _hash(item["text"].encode("utf-8")),
                               "utf8_bytes": len(item["text"].encode("utf-8"))} for item in sources],
            source_context_sha256=_hash(_compact_json(context).encode("utf-8")) if context is not None else None,
            source_context_utf8_bytes=len(_compact_json(context).encode("utf-8")) if context is not None else None,
            history_entries=len(short_history.get("entries", [])) if isinstance(short_history, dict) else None,
            history_utf8_bytes=len(_compact_json(short_history).encode("utf-8")) if short_history is not None else None,
            remaining_output_tokens=remaining,
            new_feedback={"status": "offline_reconstruction_not_sent" if message is not None else "evidence_only_remaining_budget_unknown",
                          "utf8_bytes": len(message.encode("utf-8")) if message is not None else None,
                          "sha256": _hash(message.encode("utf-8")) if message is not None else None,
                          "text": message, "evidence": evidence,
                          "evidence_utf8_bytes": len(_compact_json(evidence).encode("utf-8"))})
    original_unchanged = source.read_bytes() == raw_result and all(_hash(path.read_bytes()) == digest for path, digest in read_files)
    if not original_unchanged:
        integrity_failures.append("Result/raw input changed while the offline review was running")
    unavailable = sum(str(row["status"]).startswith("unavailable_") for row in observations)
    unknown_hashes = sum(row.get("status") == "rebuilt_legacy_hash_unknown" for row in observations)
    return {"schema_version": 1, "kind": "offline_feedback_reconstruction",
            "source_result": str(source), "source_result_sha256": _hash(raw_result),
            "current_rule_bundle_sha256": bundle.digest,
            "historical_rule_bundle_sha256": ((result.get("provenance") or {}).get("rule_bundle") or {}).get("sha256"),
            "interpretation": "CURRENT feedback reconstructed offline from saved observations. No model, OSATE, reference retrieval, revalidation, or repair-effect measurement was performed.",
            "raw_path_policy": "Only result-directory/attempt-N/raw-response.txt; stored arbitrary paths are ignored.",
            "original_files_unchanged": original_unchanged,
            "passed": not integrity_failures and not unavailable and not unknown_hashes,
            "integrity_failed": bool(integrity_failures),
            "integrity_failures": integrity_failures,
            "unavailable_candidates": unavailable,
            "unknown_candidate_hashes": unknown_hashes,
            "attempts": observations}


def feedback_regressions(bundle) -> dict:
    """Exercise prompt behavior with synthetic evidence and zero live calls.

    These witnesses test source preservation, bounded serialization, historical
    uncertainty and the distinction between observing a diagnosis and success.
    They do not claim that the synthetic candidate has passed OSATE or that the
    revised feedback improves a model's repair rate.
    """
    from unittest.mock import patch
    from . import pipeline
    from .repair_context import build_source_context, _structure
    from .repair_history import build_repair_history

    observations = []

    def record(name: str, passed: bool, **details) -> None:
        observations.append({"name": name, "passed": bool(passed), **details})

    def size(value) -> int:
        return len(_compact_json(value).encode("utf-8"))

    def restored_source(view: dict) -> str | None:
        # Expand explicitly marked display-only repeats. This independently
        # checks that a compact whole-source view represents every source line.
        lines = {}
        for fragment in view.get("fragments", []):
            for line in fragment.get("lines", []):
                if "line" in line:
                    if line.get("omitted_character_range"):
                        return None
                    lines[line["line"]] = line["text"]
                elif "repeat_of_line" in line:
                    original = lines.get(line["repeat_of_line"])
                    if original is None:
                        return None
                    for number in range(line["start_line"], line["end_line"] + 1):
                        lines[number] = original
        if set(lines) != set(range(1, view["original_lines"] + 1)):
            return None
        return "\n".join(lines[number] for number in range(1, view["original_lines"] + 1))

    short = ("package FeedbackWitness\npublic\ndata Frame\nend Frame;\n"
             "device Probe\nfeatures\n  sample: out data port Frame;\n"
             "end Probe;\nend FeedbackWitness;\n")
    sources = [{"name": "model_001.aadl", "text": short, "complete": True}]
    # The message is deliberately synthetic. The real pipeline must preserve
    # its observation status instead of treating this fixture as a validator run.
    diagnostic = {"severity": "error", "message": "Synthetic diagnostic about Frame",
                  "rule_id": "osate.diagnostic", "source": "model_001.aadl", "line": 7}
    focus = {"source": "model_001.aadl", "line": 7, "origin": "osate", "diagnostic": diagnostic,
             "source_context": [{"line": 7, "text": short.split("\n")[6]}],
             "pattern_id": None, "advice": None}
    entry = {"attempt": 1, "candidate_sha256": "1" * 64, "candidate_changed": None,
             "generation_complete": True, "repair_focus": focus,
             "pending": [], "diagnostics": [diagnostic["message"]], "issues": [],
             "project_protocol": {"valid": True, "complete": True, "diagnostics": []},
             "osate": {"status": "failed", "diagnostics": [diagnostic]},
             "final_validation": {"accepted": False, "generation_complete": True,
                                  "project_protocol_complete": True,
                                  "local_rules_valid": True, "osate_status": "failed"},
             "accepted": False, "repair_cause": "Synthetic validation failure"}
    original_sources, original_entry = deepcopy(sources), deepcopy(entry)
    # Guard the CLI's offline contract even if a future shared helper starts
    # trying to retrieve examples or invoke an independent checker implicitly.
    forbidden = AssertionError("Feedback diagnostics must not call models, OSATE, or retrieval")
    with patch.object(pipeline.ModelClient, "generate", side_effect=forbidden), \
         patch.object(pipeline.OsateRunner, "check", side_effect=forbidden), \
         patch.object(pipeline.RetrievalIndex, "retrieve", side_effect=forbidden):
        context = build_source_context(bundle, sources, focus, max_bytes=6144)
        record("short_complete_candidate_preserved", context.get("selection") == "full_sources"
               and len(context.get("sources", [])) == 1
               and restored_source(context["sources"][0]) == short
               and context["sources"][0]["sha256"] == _hash(short.encode("utf-8")),
               context_bytes=size(context), source_sha256=_hash(short.encode("utf-8")))

        adjacent = "package P\npublic\ndata A\nend A;\ndata B\nend B;\nend P;\n"
        structure, requests = _structure(bundle, adjacent, "model_001.aadl", True, 5)
        focused = [request for request in requests if request["kind"] == "focus_structure"]
        # AST spans are half-open: A ending immediately before line 5 must not
        # become the selected repair structure for B merely by touching it.
        record("half_open_focus_does_not_select_previous_declaration", structure["status"] == "observed"
               and bool(focused) and all(request["last"] >= 5 for request in focused)
               and any(request["first"] == 5 for request in focused),
               focus_line=5, focus_structures=focused)

        repeated_text = "package RepeatWitness\npublic\n" + "-- repeated observation\n" * 40 + "end RepeatWitness;\n"
        repeat_sources = [{"name": "model_001.aadl", "text": repeated_text, "complete": True}]
        repeat_before = deepcopy(repeat_sources)
        repeat_context = build_source_context(bundle, repeat_sources, None, max_bytes=6144)
        repeat_records = [line for source_view in repeat_context["sources"]
                          for fragment in source_view["fragments"] for line in fragment["lines"]
                          if "repeat_of_line" in line]
        record("repetition_is_reversible_display_only", bool(repeat_records)
               and restored_source(repeat_context["sources"][0]) == repeated_text
               and repeat_sources == repeat_before,
               displayed_repeat_records=len(repeat_records), input_sha256=_hash(repeated_text.encode("utf-8")))

        # Large multilingual lines exercise bytes plus JSON escaping and force
        # the bounded fallback; no expensive parse of this long text is needed.
        long_text = "\n".join(f'-- row {i}: ' + '猫"\\\t' * 200 for i in range(180)) + "\n"
        long_sources = [{"name": "model_001.aadl", "text": long_text, "complete": False}]
        long_before = deepcopy(long_sources)
        long_context = build_source_context(bundle, long_sources, {"source": "model_001.aadl", "line": 90}, max_bytes=6144)
        record("long_source_has_explicit_omissions_and_byte_bound", size(long_context) <= 6144
               and long_context["truncation"]["content_truncated"] is True
               and any(view["omitted_ranges"] for view in long_context["sources"])
               and long_context["sources"][0]["sha256"] == _hash(long_text.encode("utf-8"))
               and long_sources == long_before,
               input_utf8_bytes=len(long_text.encode("utf-8")), context_utf8_bytes=size(long_context),
               selection=long_context["selection"])

        missing = {"attempt": 1, "final_validation": {"accepted": False}}
        unknown = build_repair_history([missing, {"attempt": 2}], max_bytes=3072)
        unknown_entry = unknown["entries"][0]
        record("missing_history_evidence_stays_unknown", len(unknown["entries"]) == 1
               and unknown_entry["candidate_changed"] is None
               and unknown_entry["previous_diagnostic"]["reobserved"] is None
               and unknown_entry["validation"]["osate_status"] is None
               and unknown["truncation"]["ignored_unevaluated_attempts"] == 1)
        history = [{"attempt": number, "candidate_sha256": str(number) * 64,
                    "final_validation": {"accepted": False}, "focus_diagnostic_reobserved": number == 5}
                   for number in range(1, 6)]
        history_before = deepcopy(history)
        capped = build_repair_history(history, max_entries=3, max_bytes=8192)
        bounded = build_repair_history(history, max_entries=3, max_bytes=3072)
        record("history_selects_latest_three_and_honors_bytes", [x["attempt"] for x in capped["entries"]] == [3, 4, 5]
               and len(bounded["entries"]) <= 3 and size(bounded) <= 3072
               and history == history_before,
               bounded_entries=len(bounded["entries"]), bounded_utf8_bytes=size(bounded))
        record("diagnostic_absence_and_reappearance_are_not_success", capped["entries"][1]["previous_diagnostic"]["reobserved"] is False
               and capped["entries"][2]["previous_diagnostic"]["reobserved"] is True
               and all(x["validation"]["accepted"] is False for x in capped["entries"]))

        prepared = pipeline.prepare_repair_feedback(bundle, sources, entry, [entry])
        evidence = pipeline.build_repair_evidence(prepared)
        message = pipeline.build_repair_message(bundle, prepared, remaining=8000)
        record("shared_pipeline_embeds_current_source_and_history", isinstance(prepared.get("repair_source_context"), dict)
               and isinstance(prepared.get("repair_history"), dict)
               and "Frame" in message and _hash(short.encode("utf-8")) in message
               and size(evidence) <= 16384,
               evidence_utf8_bytes=size(evidence), message_utf8_bytes=len(message.encode("utf-8")))
        reobserved = deepcopy(entry)
        reobserved.update(focus_diagnostic_reobserved=True,
                          focus_diagnostic_observation={"reobserved": True, "reason": "same_saved_signature"})
        observed_prepared = pipeline.prepare_repair_feedback(bundle, sources, reobserved, [entry, reobserved])
        observed_evidence = pipeline.build_repair_evidence(observed_prepared)
        # Match the actual JSON wire value, not an English keyword which could
        # appear only in boilerplate while the measured observation is omitted.
        wire_values = []
        def visit(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    if key in {"focus_diagnostic_reobserved", "reobserved"}:
                        wire_values.append(child)
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)
        visit(observed_evidence)
        record("diagnostic_reobservation_is_in_actual_prompt_evidence", True in wire_values
               and observed_prepared["final_validation"]["accepted"] is False)
        long_entry = deepcopy(entry)
        long_entry["repair_focus"] = {**deepcopy(focus), "line": 90}
        long_entry["generation_complete"] = False
        long_entry["project_protocol"].update(valid=False, complete=False)
        long_entry["final_validation"].update(generation_complete=False, project_protocol_complete=False)
        long_prepared = pipeline.prepare_repair_feedback(bundle, long_sources, long_entry, [entry, long_entry])
        long_evidence = pipeline.build_repair_evidence(long_prepared)
        record("combined_feedback_respects_wire_budget", size(long_evidence) <= 16384
               and size(long_prepared["repair_source_context"]) <= 6144
               and size(long_prepared["repair_history"]) <= 4096,
               evidence_utf8_bytes=size(long_evidence))
        record("all_shared_builders_leave_inputs_unchanged", sources == original_sources and entry == original_entry
               and long_sources == long_before and history == history_before)
        legacy = deepcopy(entry)
        legacy["repair_focus"].pop("source_context")
        try:
            legacy_prepared = pipeline.prepare_repair_feedback(bundle, sources, legacy, [legacy])
            legacy_evidence = pipeline.build_repair_evidence(legacy_prepared)
            record("legacy_focus_without_snippet_is_supported", isinstance(legacy_evidence.get("candidate_source_context"), dict))
        except (KeyError, TypeError, ValueError) as exc:
            record("legacy_focus_without_snippet_is_supported", False, error=f"{type(exc).__name__}: {exc}")

        # End-to-end CLI helper witnesses use an ephemeral directory only. The
        # saved arbitrary path deliberately contains different text: following
        # it instead of the conventional raw file would fail these assertions.
        from tempfile import TemporaryDirectory
        from .project import serialize_project
        with TemporaryDirectory(prefix="aadl-feedback-") as directory:
            test_root = Path(directory)
            result_root = test_root / "run"
            raw_path = result_root / "attempt-1" / "raw-response.txt"
            raw_path.parent.mkdir(parents=True)
            raw_text = serialize_project({"model_001.aadl": short})
            raw_path.write_bytes(raw_text.encode("utf-8"))
            other = test_root / "not-a-candidate.txt"
            other.write_text("This stored path must never supply candidate source.", encoding="utf-8")
            saved_entry = deepcopy(entry)
            saved_entry.update(candidate_sha256=_hash(raw_text.encode("utf-8")),
                               raw_response=str(other), candidate=str(other), sources=[{"path": str(other)}])
            saved = {"settings": {"token_budget": 8192}, "attempts": [saved_entry],
                     "calls": [{"phase": "generation", "status": "completed", "usage": {"completion_tokens": 20},
                                "messages": [{"role": "user", "content": "Original task"}]},
                               {"phase": "generation", "status": "started", "usage": None,
                                "messages": [{"role": "user", "content": "Original task"},
                                             {"role": "user", "content": "Historical repair feedback"}]}]}
            result_path = result_root / "result.json"
            result_path.write_text(_compact_json(saved), encoding="utf-8")
            reconstruction = rebuild_feedback(result_path, bundle)
            observation = reconstruction["attempts"][0]
            record("rebuild_uses_fixed_raw_path_and_keeps_files_unchanged", reconstruction["passed"]
                   and reconstruction["original_files_unchanged"]
                   and observation["raw_hash_matches_candidate_sha256"] is True
                   and observation["received_sources"][0]["sha256"] == _hash(short.encode("utf-8"))
                   and observation["original_feedback"]["text"] == "Historical repair feedback"
                   and observation["remaining_output_tokens"] == 8172
                   and observation["new_feedback"]["status"] == "offline_reconstruction_not_sent")
            saved["calls"][0]["usage"] = None
            result_path.write_text(_compact_json(saved), encoding="utf-8")
            unknown_reconstruction = rebuild_feedback(result_path, bundle)["attempts"][0]
            record("unknown_usage_still_exposes_evidence_without_invented_message", unknown_reconstruction["remaining_output_tokens"] is None
                   and unknown_reconstruction["new_feedback"]["utf8_bytes"] is None
                   and unknown_reconstruction["new_feedback"]["status"] == "evidence_only_remaining_budget_unknown"
                   and isinstance(unknown_reconstruction["new_feedback"]["evidence"], dict))
            raw_path.write_bytes((raw_text + "changed").encode("utf-8"))
            mismatch = rebuild_feedback(result_path, bundle)
            record("raw_hash_mismatch_prevents_reconstruction", not mismatch["passed"]
                   and mismatch["attempts"][0]["status"] == "unavailable_raw_hash_mismatch"
                   and "new_feedback" not in mismatch["attempts"][0])

    recorded_calls = [{"phase": "generation", "status": "error", "messages": [{"role": "user", "content": "task"}]}]
    not_sent = _recorded_feedback(recorded_calls, 1)
    record("terminal_timeout_does_not_invent_sent_feedback", not_sent["status"] == "not_sent_no_next_generation_call"
           and not_sent["utf8_bytes"] is None)
    record("unknown_token_usage_does_not_become_zero_remaining", _remaining_feedback_budget(
        {"settings": {"token_budget": 8192}, "calls": [{"phase": "generation", "usage": None}]}, 1) is None)
    return {"schema_version": 1, "kind": "offline_feedback_regressions", "rule_bundle_sha256": bundle.digest,
            "interpretation": "Synthetic behavior witnesses for prompt construction; no model/OSATE/retrieval calls or repair-effect estimate.",
            "passed": all(case["passed"] for case in observations), "cases": observations}


def maintenance_regressions(bundle) -> dict:
    """Check current interfaces with synthetic fixtures, never efficacy data.

    All filesystem writes are confined to an automatically removed temporary
    directory. Guards forbid real model/OSATE requests. XML fixtures prove local
    verifier behavior, not an OSATE pass or natural-language requirement coverage.
    """
    from contextlib import ExitStack
    from tempfile import TemporaryDirectory
    from types import SimpleNamespace
    from unittest.mock import patch
    from . import cli, experiments, experiment_metrics, pipeline, rules
    from .grammar import GrammarFrontend
    from .osate import verify_instance_artifact
    from .project import ProjectDocument, ProjectFile

    observations = []

    def run_case(name, operation):
        try:
            details = operation() or {}
            observations.append({"name": name, "passed": True, **details})
        except Exception as exc:
            observations.append({"name": name, "passed": False,
                                 "error": f"{type(exc).__name__}: {exc}"})

    def signature(frontier):
        def node(value):
            return (value.production, value.text, value.start, value.end, value.complete,
                    tuple(node(child) for child in value.children))
        return {node(root) for root in frontier.alternatives}, frontier.complete

    # Independent known continuation witnesses cover fixed and extendable
    # terminals; comparing actual trees also catches checkpoint-state drift.
    regex_cases = (
        ("fixed_word", r"[dD][eE][vV][iI][cC][eE]", (("device", False), ("DEVICE", False))),
        ("bounded_class", r"[ab]{1,3}", (("a", True), ("aba", False))),
        ("prefix_alternatives", r"ab|abc", (("ab", True), ("abc", False))),
        ("identifier", r"[a-z][a-z0-9_]*", (("x", True), ("x1", True))),
        ("unicode", r"\w+", (("μ", True), ("中文", True))),
        ("operator", r"=|=>", (("=", True), ("=>", False))),
        ("comment", r"--[^\r\n]*", (("-- text", True), ("--α", True))),
        ("quoted_string", r'"(?:[^"]|"")*"', (('"x"', True), ('"x""y"', True))),
        ("python_backreference", r"(a)\1+", (("aa", True), ("aaa", True))),
    )
    forbidden = AssertionError("Maintenance regressions cannot call models or OSATE")
    with ExitStack() as stack, TemporaryDirectory(prefix="aadl-maintenance-") as directory:
        for target, method in ((pipeline.ModelClient, "generate"), (pipeline.ModelClient, "discover"),
                               (pipeline.OsateRunner, "check"), (pipeline.OsateRunner, "probe"),
                               (pipeline.RetrievalIndex, "retrieve")):
            stack.enter_context(patch.object(target, method, side_effect=forbidden))
        temporary = Path(directory)
        for label, pattern, examples in regex_cases:
            def check_regex(pattern=pattern, examples=examples):
                frontend = GrammarFrontend("start: T \"!\"\nT: /" + pattern + "/\n")
                boundaries = 0
                for text, expected in examples:
                    assert frontend._terminal_can_extend("T", text) is expected
                    previous = None
                    for end in range(len(text) + 2):
                        prefix = (text + "!")[:end]
                        incremental = frontend.prefix(prefix, "fixture", previous=previous)
                        independent = frontend.prefix(prefix, "fixture")
                        assert signature(incremental) == signature(independent), prefix
                        previous = incremental
                        boundaries += 1
                return {"prefix_boundaries": boundaries,
                        "conservative_terminals": sorted(frontend.extension_unproven_terminals)}
            run_case("regex_" + label, check_regex)

        def no_planning_hook():
            module = SimpleNamespace(**{key: value for key, value in vars(bundle.semantics).items()
                                        if key != "normalize_plan"})
            rules._validate_bindings(module, bundle.frontend)
            assert not hasattr(module, "normalize_plan")
        run_case("rule_bindings_do_not_require_planning", no_planning_hook)

        source = temporary / "source space.aadl"
        source.write_text("package P\npublic\nend P;\n", encoding="utf-8")
        artifact = temporary / "instances" / "fixture.aaxl2"
        artifact.parent.mkdir()
        direct = ('<i:SystemInstance xmlns:i="http://aadl.info/AADL/2.0/instance" '
                  'name="S_i_Instance" category="system">'
                  '<classifier href="../source%20space.aadl#P.S.i"/>'
                  '<componentImplementation href="../source%20space.aadl#P.S.i"/>'
                  '</i:SystemInstance>')
        wrapped = '<xmi:XMI xmlns:xmi="http://www.omg.org/XMI">' + direct + '</xmi:XMI>'

        def artifact_case(text, expected, expected_hash=None):
            artifact.write_text(text, encoding="utf-8")
            observed = verify_instance_artifact(artifact, source=source, requested_root="P::S.i",
                expected_name="S_i_Instance", expected_sha256=expected_hash)
            assert observed["status"] == expected, observed
            return {"verification": observed["status"], "reason": observed.get("reason")}
        for label, text in (("direct", direct), ("xmi", wrapped)):
            run_case("artifact_" + label, lambda text=text: artifact_case(text, "passed"))
        for label, text in (
            ("wrong_name", direct.replace('name="S_i_Instance"', 'name="wrong"')),
            ("wrong_source", direct.replace("source%20space.aadl", "other.aadl")),
            ("wrong_classifier", direct.replace("#P.S.i", "#P.Other.i")),
            ("duplicate_instance", wrapped.replace(direct, direct + direct)),
            ("missing_instance", '<xmi:XMI xmlns:xmi="http://www.omg.org/XMI"/>'),
        ):
            run_case("artifact_" + label, lambda text=text: artifact_case(text, "failed"))
        run_case("artifact_wrong_hash", lambda: artifact_case(direct, "failed", "0" * 64))

        # EMF root numbering differs between a direct root and an XMI object
        # list. Include a sibling data object before the root to exercise /1.
        def instance_xml(prefix, *, wrapped=False, before=False, bad_endpoint=False, categories=True):
            children = ''.join(
                f'<componentInstance name="{category}" category="{category}">'
                f'<classifier href="../source.aadl#P.{category}"/></componentInstance>'
                for category in ("thread", "processor", "memory")) if categories else ""
            endpoint = prefix + '/@componentInstance.' + ("99" if bad_endpoint else "0")
            tree = direct.replace('</i:SystemInstance>', children +
                f'<connectionInstance name="c" source="{endpoint}" '
                f'destination="{prefix}/@componentInstance.2"/></i:SystemInstance>')
            sibling = ('<i:ComponentInstance xmlns:i="http://aadl.info/AADL/2.0/instance" '
                       'name="not-system-memory" category="memory"/>') if before else ""
            return ('<xmi:XMI xmlns:xmi="http://www.omg.org/XMI">' + sibling + tree + '</xmi:XMI>'
                    if wrapped else tree)

        def instance_rules(text, valid, required_rule=None):
            artifact.write_text(text, encoding="utf-8")
            hook = getattr(bundle.semantics, "validate_instance", None)
            if not callable(hook):
                return {"scope": "not_applicable_no_instance_rule_hook"}
            report = hook(artifact)
            assert report.valid is valid, [result.rule_id for result in report.results]
            if required_rule:
                assert any(result.rule_id == required_rule for result in report.results)
            return {"local_rules_valid": report.valid}
        for label, text in (("direct", instance_xml('/')),
                            ("xmi_zero", instance_xml('/0', wrapped=True)),
                            ("xmi_one", instance_xml('/1', wrapped=True, before=True))):
            run_case("instance_rules_" + label, lambda text=text: instance_rules(text, True))
        run_case("instance_rejects_wrong_root_ordinal", lambda: instance_rules(
            instance_xml('/0', wrapped=True, before=True), False, "instance.connection_endpoint"))
        run_case("instance_rejects_missing_endpoint", lambda: instance_rules(
            instance_xml('/0', wrapped=True, bad_endpoint=True), False, "instance.connection_endpoint"))
        run_case("instance_siblings_do_not_satisfy_categories", lambda: instance_rules(
            instance_xml('/1', wrapped=True, before=True, categories=False), False, "instance.required_categories"))

        def cli_scope():
            received = []
            document = ProjectDocument((ProjectFile("A.aadl", "package A\npublic\nend A;"),
                                        ProjectFile("B.aadl", "package B\npublic\nend B;")), True, (), True)
            with patch.object(cli.Config, "load", return_value=SimpleNamespace(osate_cli=source)), \
                 patch.object(cli, "load_bundle", return_value=bundle), \
                 patch.object(cli, "read_project", return_value=document), \
                 patch.object(cli, "_print", side_effect=received.append), \
                 patch.object(cli, "JointEngine", side_effect=AssertionError("Scope must be checked before rules")):
                code = cli.main(["check", "synthetic-directory"])
            assert code == 2 and received[0]["error_kind"] == "input_scope"
            assert received[0]["syntax_semantics"]["rule_error"] is False
            assert received[0]["osate"]["status"] == "not_run"
        run_case("cli_multiple_targets_are_scope_error", cli_scope)

        example = temporary / "example"
        metadata_path = example / "reference" / "manifest.json"
        derived = example / "derived_requirements" / "task.md"
        metadata_path.parent.mkdir(parents=True)
        derived.parent.mkdir(parents=True)
        derived.write_text("Synthetic derived task for scheduling only.", encoding="utf-8")
        digest = _hash(derived.read_bytes())
        metadata = {"requirements": [], "derived_requirements": [{
            "path": "derived_requirements/task.md", "family": "synthetic_derived", "sha256": digest,
            "generation_scope_status": "in_scope", "independent_evaluation_eligible": False,
            "generation_scope_review": {"requirement_sha256": digest}}]}
        metadata_path.write_text(_compact_json(metadata), encoding="utf-8")

        def derived_identity():
            mapped = experiments._metadata_index(metadata_path)[derived.resolve()]
            assert mapped["family"] == "synthetic_derived"
            assert mapped["requirement_origin"] == "derived_example"
            assert mapped["independent_evaluation_eligible"] is False
        run_case("derived_metadata_is_not_unknown", derived_identity)

        def stale_scope():
            stale = deepcopy(metadata["derived_requirements"][0])
            stale["generation_scope_review"]["requirement_sha256"] = "0" * 64
            try:
                experiments._validate_scope_review(stale, actual_sha256=digest)
            except ValueError as exc:
                assert "SHA256" in str(exc)
            else:
                raise AssertionError("Changed input must invalidate its old scope review")
        run_case("scope_review_binds_exact_input", stale_scope)

        def metric_scope():
            batch = {"id": "synthetic", "config": {"max_attempts": 6, "token_budget": 8192}}
            job = {"state": "finished", "condition": "joint-repair", "constraints": "joint",
                   "order": 1, "output": "synthetic"}
            result = {"status": "failed", "failure_kind": "model_invalid", "calls": [], "attempts": []}
            variants = (
                ("core", {"generation_scope_status": "in_scope", "independent_evaluation_eligible": True}, 1),
                ("derived", {"requirement_origin": "derived_example", "generation_scope_status": "in_scope",
                             "independent_evaluation_eligible": False}, 0),
                ("outside", {"generation_scope_status": "out_of_scope", "independent_evaluation_eligible": True}, 0),
                ("legacy", {}, 0),
            )
            rows = []
            for label, fields, count in variants:
                case = {"id": label, "relative_path": label + ".md", "sha256": digest,
                        "metadata": {"family": "synthetic", **fields}}
                with patch.object(experiment_metrics, "_read_result", return_value=(result, None)):
                    row = experiment_metrics.job_metrics(temporary, batch, case, job)
                assert row["evaluable"] == 1 and row["independent_core_evaluable"] == count
                rows.append(row)
            assert rows[-1]["generation_scope_status"] == "unknown"
            assert rows[-1]["independent_evaluation_eligible"] is None
            summaries = experiment_metrics.summary_rows(rows)
            assert sum(row["n_evaluable"] for row in summaries) == 4
            assert sum(row["n_independent_core_evaluable"] for row in summaries) == 1
            return {"descriptive_denominator": 4, "independent_core_denominator": 1}
        run_case("scope_denominators_and_legacy_unknown", metric_scope)

        def empty_batch():
            output = temporary / "Generate"
            output.mkdir()
            manifest = experiments._load(output)
            assert manifest["batches"] == []
            config = {"input": str(derived), "input_root": str(example),
                      "metadata_manifest": str(metadata_path), "conditions": ["plain-once", "joint-repair"],
                      "token_budget": 8192, "max_attempts": 6, "retrieval_enabled": False,
                      "context": [], "warmup": False}
            # Runtime identity acquisition is outside this offline regression;
            # actual scheduling, snapshots and empty summary initialization run.
            with patch.object(experiments, "_local_identity", return_value={"offline_fixture": True}):
                batch = experiments.prepare_batch(output, manifest, config)
            assert len(batch["jobs"]) == 2
            assert [(job["condition"], job["max_attempts"], job["state"]) for job in batch["jobs"]] == [
                ("plain-once", 1, "not_started"), ("joint-repair", 6, "not_started")]
            assert batch["cases"][0]["metadata"]["requirement_origin"] == "derived_example"
            assert experiments._load(output)["batches"][0]["id"] == batch["id"]
            assert (output / "summary.csv").is_file() and (output / "experiments.csv").is_file()
            return {"scheduled_jobs": 2, "model_calls": 0}
        run_case("empty_experiment_directory_initializes", empty_batch)

    return {"schema_version": 1, "kind": "offline_maintenance_regressions",
            "rule_bundle_sha256": bundle.digest,
            "interpretation": "Synthetic interface/verification regressions only; no model, OSATE, or efficacy experiment.",
            "passed": all(case["passed"] for case in observations), "cases": observations}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    freeze = sub.add_parser("freeze")
    freeze.add_argument("result", type=Path)
    freeze.add_argument("--output", type=Path, required=True)
    regress = sub.add_parser("regress")
    feedback = sub.add_parser("feedback", help="Rebuild current feedback from saved candidates; never invoke a model or validator")
    feedback.add_argument("result", type=Path)
    feedback_regress = sub.add_parser("feedback-regress", help="Offline source-context, short-history and prompt regressions")
    maintenance_regress = sub.add_parser("maintenance-regress", help="Offline current-interface, XML, prefix and experiment-metadata regressions")
    audit = sub.add_parser("audit-batch", help="Offline two-group policy, one-shot and shared-budget checks")
    audit.add_argument("output_root", type=Path)
    audit.add_argument("--batch", required=True)
    audit.add_argument("--output", type=Path, help="New JSON file; existing files are never overwritten")
    replay = sub.add_parser("replay")
    replay.add_argument("snapshot", type=Path)
    replay.add_argument("--prefixes", type=int, default=3)
    replay.add_argument("--max-characters", type=int, default=4096)
    replay.add_argument("--trial", action="append", help="Literal candidate text; at most eight trials")
    for p in (regress, replay, feedback, feedback_regress, maintenance_regress):
        p.add_argument("--rules", type=Path)
        p.add_argument("--output", type=Path, help="New JSON file; never overwrites an earlier diagnostic")
    args = parser.parse_args(argv)
    try:
        if args.command == "freeze":
            result = freeze_result(args.result, args.output)
        elif args.command == "audit-batch":
            manifest = json.loads((args.output_root / "manifest.json").read_text(encoding="utf-8-sig"))
            batch = next((b for b in manifest["batches"] if b["id"] == args.batch), None)
            if batch is None:
                raise ValueError("Experiment batch not found")
            result = audit_experiment_batch(args.output_root, batch)
            if args.output:
                _write_new(args.output, result)
        else:
            from .rules import load_bundle
            bundle = load_bundle(args.rules)
            if args.command == "regress":
                result = rule_regressions(bundle)
            elif args.command == "feedback":
                result = rebuild_feedback(args.result, bundle)
            elif args.command == "feedback-regress":
                result = feedback_regressions(bundle)
            elif args.command == "maintenance-regress":
                result = maintenance_regressions(bundle)
            else:
                trials = tuple(args.trial) if args.trial else (" ", "\n", ";")
                if not 1 <= args.prefixes <= 8 or not 1 <= args.max_characters <= 16384 or len(trials) > 8 or any(len(t) > 128 for t in trials):
                    raise ValueError("Replay bounds: 1..8 prefixes, 1..16384 characters, <=8 trials of <=128 characters")
                result = replay_snapshot(args.snapshot, bundle, prefixes=args.prefixes,
                                         max_characters=args.max_characters, trials=trials)
            if args.output:
                _write_new(args.output, result)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if args.command == "audit-batch" and not result["failed_checks"] and result["unavailable_checks"]:
            return 2
        if args.command == "feedback" and not result["integrity_failed"] and (result["unavailable_candidates"] or result["unknown_candidate_hashes"]):
            return 2
        return 1 if result.get("passed") is False else 0
    except (OSError, ValueError, RuntimeError) as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
