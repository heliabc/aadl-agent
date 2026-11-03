"""Sequential plain-once versus joint-repair experiments over UTF-8 requirements.

Historical six-condition batches remain readable for offline summaries, but
cannot execute under the new protocol. Generation protocol 2 is independent.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import asdict, is_dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import uuid

from .experiment_metrics import (EXPERIMENT_PROTOCOL, EXPERIMENT_PROTOCOL_VERSION,
                                 INFRASTRUCTURE_KINDS, export_metrics)
from .pipeline import DEFAULT_MAX_ATTEMPTS, repair_feedback_policy


CONDITIONS = ("plain-once", "joint-repair")
CONDITION_SETTINGS = {
    "plain-once": {"constraints": "plain", "repair_enabled": False, "max_attempts": 1},
    "joint-repair": {"constraints": "joint", "repair_enabled": True, "max_attempts": DEFAULT_MAX_ATTEMPTS},
}
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT_ROOT = PROJECT_ROOT / "example" / "requirements"
DEFAULT_METADATA = PROJECT_ROOT / "example" / "reference" / "manifest.json"
SCHEMA_VERSION = 1


def _require_current_protocol(batch: dict) -> None:
    """Reject old execution before recovering states or writing a checkpoint."""
    if (batch.get("experiment_protocol") != EXPERIMENT_PROTOCOL
            or batch.get("experiment_protocol_version") != EXPERIMENT_PROTOCOL_VERSION):
        raise ValueError(f"Batch {batch.get('id')} uses a legacy/unsupported experiment protocol. "
                         f"This runner executes only {EXPERIMENT_PROTOCOL} v{EXPERIMENT_PROTOCOL_VERSION}; "
                         "use summarize for historical batches or run to create a new batch.")
    config = batch["config"]
    if (config.get("experiment_protocol") != EXPERIMENT_PROTOCOL
            or config.get("experiment_protocol_version") != EXPERIMENT_PROTOCOL_VERSION
            or config.get("token_budget") != 8192 or config.get("max_attempts") != DEFAULT_MAX_ATTEMPTS):
        raise ValueError("New experiment protocol/configuration is inconsistent")
    if type(config.get("retrieval_enabled")) is not bool:
        raise ValueError("The current protocol requires an explicit retrieval_enabled flag")
    if config.get("generated_file_limit") != 1:
        raise ValueError("The current experiment protocol generates exactly one AADL source file")
    conditions = config.get("conditions") or []
    if len(set(conditions)) != len(conditions) or not conditions or set(conditions) - set(CONDITIONS):
        raise ValueError("New experiment conditions must select only plain-once and joint-repair")
    pairs = []
    for index, case in enumerate(batch["cases"]):
        offset = index % len(conditions)
        pairs.extend((case["id"], c) for c in conditions[offset:] + conditions[:offset])
    if ([(j["case_id"], j["condition"]) for j in batch["jobs"]] != pairs
            or len(set(pairs)) != len(pairs)
            or [j["order"] for j in batch["jobs"]] != list(range(1, len(pairs) + 1))):
        raise ValueError("Experiment jobs differ from the registered case/condition schedule")
    for job in batch["jobs"]:
        expected = CONDITION_SETTINGS.get(job.get("condition"))
        if (expected is None or job.get("mode") != "direct"
                or any(job.get(key) != value for key, value in expected.items())):
            raise ValueError(f"New experiment job must be plain-once (1 attempt) or joint-repair ({DEFAULT_MAX_ATTEMPTS} attempts), without planning")


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _json_hash(value: dict) -> str:
    return _digest(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def _atomic_json(path: Path, data: dict) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="")
    temporary.replace(path)


def _save(output_root: Path, manifest: dict) -> None:
    manifest["updated_at"] = _now()
    _atomic_json(output_root / "manifest.json", manifest)


def _load(output_root: Path) -> dict:
    path = output_root / "manifest.json"
    if not path.exists():
        return {"schema_version": SCHEMA_VERSION, "created_at": _now(), "batches": []}
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if data.get("schema_version") != SCHEMA_VERSION or not isinstance(data.get("batches"), list):
        raise ValueError(f"Unrecognized experiment manifest: {path}")
    return data


@contextmanager
def _output_lock(output_root: Path):
    """An OS-held lock disappears on process death, unlike a stale PID file.

    The tiny lock file itself may remain. It is not evidence that a process is
    active. A second scheduler/summarizer must not replace a running manifest.
    """
    output_root.mkdir(parents=True, exist_ok=True)
    stream = (output_root / ".experiments.lock").open("a+b")
    locked = False
    try:
        if stream.seek(0, 2) == 0:
            stream.write(b"\0")
            stream.flush()
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            locked = True
        except OSError as exc:
            raise ValueError(f"Another experiment process is using {output_root}") from exc
        yield
    finally:
        if locked:
            stream.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
        stream.close()


def _inside(path: Path, root: Path) -> bool:
    return path.is_relative_to(root)


def discover_inputs(selection: Path, input_root: Path | None = None) -> tuple[Path, list[Path]]:
    selection = selection.expanduser().resolve()
    if not selection.exists():
        raise ValueError(f"Input does not exist: {selection}")
    if input_root is None:
        input_root = (DEFAULT_INPUT_ROOT if _inside(selection, DEFAULT_INPUT_ROOT.resolve()) else
                      selection.parent if selection.is_file() else selection)
    root = input_root.expanduser().resolve()
    if not root.is_dir() or not _inside(selection, root):
        raise ValueError("--input-root must be a directory containing the selected input")
    candidates = [selection] if selection.is_file() else sorted(
        (path for path in selection.rglob("*") if path.is_file() and path.suffix.lower() in {".txt", ".md"}),
        key=lambda path: path.relative_to(root).as_posix().casefold())
    if not candidates:
        raise ValueError("No .txt or .md requirements were found")
    seen = {}
    for path in candidates:
        if path.suffix.lower() not in {".txt", ".md"}:
            raise ValueError(f"Requirements must be UTF-8 .txt or .md: {path}")
        if not _inside(path.resolve(), root):
            raise ValueError(f"An input symlink escapes --input-root: {path}")
        relative = path.relative_to(root)
        destination = (relative.parent / relative.stem).as_posix().casefold()
        if destination in seen:
            raise ValueError(f"Output collision: {seen[destination]} and {path}; same parent/stem cannot share a case directory")
        seen[destination] = path
        # Decode every file before scheduling the first job. Invalid input must
        # not produce a partly executed comparison with silently skipped cases.
        text = path.read_bytes().decode("utf-8-sig")
        if not text.strip():
            raise ValueError(f"Empty requirement document: {path}")
    return root, candidates


def _validate_scope_review(entry: dict, *, actual_sha256: str | None = None) -> None:
    """Bind a human scope judgment to the exact requirement bytes it reviewed.

    Older, unreviewed metadata remains usable; it does not acquire a scope label
    implicitly. Once a review is supplied, an edited requirement must be reviewed
    again instead of inheriting its former independent-core eligibility.
    """
    review = entry.get("generation_scope_review")
    if review is None:
        return
    digest = review.get("requirement_sha256") if isinstance(review, dict) else None
    if (not isinstance(digest, str) or len(digest) != 64
            or any(character not in "0123456789abcdef" for character in digest)):
        raise ValueError(f"Malformed generation scope review hash: {entry.get('path', '<input>')}")
    for expected in (entry.get("sha256"), actual_sha256):
        if expected is not None and digest != expected:
            raise ValueError(f"Generation scope review SHA256 differs from requirement: {entry.get('path', '<input>')}")


def _metadata_index(path: Path | None) -> dict[Path, dict]:
    if path is None:
        return {}
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    originals = data.get("requirements")
    derived = data.get("derived_requirements", [])
    if not isinstance(originals, list) or not isinstance(derived, list):
        raise ValueError(f"Metadata manifest requires a requirements list: {path}")
    # Research paths are relative to example/, not reference/ or the selected
    # subtree. This also makes single-file and directory invocation agree.
    root = path.parent.parent
    mapped = {}
    for entry, is_derived in [(item, False) for item in originals] + [(item, True) for item in derived]:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
            raise ValueError(f"Malformed requirement entry in {path}")
        target = (root / entry["path"]).resolve()
        if not _inside(target, root.resolve()):
            raise ValueError(f"Metadata path escapes its root: {entry['path']}")
        if target in mapped:
            raise ValueError(f"Duplicate requirement metadata: {entry['path']}")
        entry = dict(entry)
        scope = entry.get("generation_scope_status")
        if scope is not None and scope not in {"in_scope", "out_of_scope", "needs_review"}:
            raise ValueError(f"Invalid generation_scope_status: {entry['path']}")
        eligibility = entry.get("independent_evaluation_eligible")
        if eligibility is not None and type(eligibility) is not bool:
            raise ValueError(f"Independent evaluation eligibility must be boolean or null: {entry['path']}")
        _validate_scope_review(entry)
        if is_derived:
            # The collection itself establishes derived provenance. A missing
            # label must not turn a model-derived demonstration into a benchmark.
            if entry.get("requirement_origin") not in (None, "derived_example") or eligibility is True:
                raise ValueError(f"Derived requirement has conflicting evaluation provenance: {entry['path']}")
            entry.update(requirement_origin="derived_example", independent_evaluation_eligible=False)
        mapped[target] = entry
    return mapped


def _selected_inputs(config: dict) -> tuple[Path, list[Path], dict[Path, dict]]:
    """Validate a frozen selection before scheduling, without copying its corpus."""
    if not config.get("selection"):
        if config.get("selection_role"):
            raise ValueError("--selection-role requires --selection")
        root, paths = discover_inputs(Path(config["input"]), Path(config["input_root"]) if config.get("input_root") else None)
        return root, paths, {}
    selection = Path(config["selection"])
    data = json.loads(selection.read_text(encoding="utf-8-sig"))
    if data.get("schema_version") != 1 or not isinstance(data.get("cases"), list):
        raise ValueError("Frozen selection requires schema_version=1 and cases")
    root = Path(config.get("input_root") or DEFAULT_INPUT_ROOT).resolve()
    metadata_path = config.get("metadata_manifest")
    if data.get("metadata_sha256") and (not metadata_path or
            _digest(Path(metadata_path).read_bytes()) != data["metadata_sha256"]):
        raise ValueError("Frozen selection metadata SHA256 differs from the supplied manifest")
    paths, selected, destinations = [], {}, set()
    # Validate ALL frozen cases, including held-out cases excluded from development.
    # This prevents an edited subset from masquerading as the original selection.
    for item in data["cases"]:
        if not isinstance(item, dict) or any(not isinstance(item.get(k), str) or not item[k]
                for k in ("path", "relative_path", "sha256", "family", "role")):
            raise ValueError("Malformed frozen selection case")
        relative = Path(item["relative_path"])
        path = (root / relative).resolve()
        source = (root.parent / item["path"]).resolve()
        if relative.is_absolute() or ".." in relative.parts or not _inside(path, root) or path != source:
            raise ValueError(f"Frozen selection paths disagree or escape the input root: {item['path']}")
        discover_inputs(path, root)
        if _digest(path.read_bytes()) != item["sha256"]:
            raise ValueError(f"Frozen selection input SHA256 differs: {path}")
        destination = relative.with_suffix("").as_posix().casefold()
        if path in selected or destination in destinations:
            raise ValueError(f"Duplicate/colliding frozen selection path: {path}")
        selected[path] = dict(item)
        destinations.add(destination)
        if not config.get("selection_role") or item["role"] == config["selection_role"]:
            paths.append(path)
    if not paths:
        raise ValueError("No frozen cases match --selection-role")
    return root, paths, selected


def _source_hash() -> str:
    # Include the scheduling script as well as the full workflow package. The
    # checkpoint must not resume under a different local implementation.
    paths = list(Path(__file__).parent.glob("*.py"))
    paths += [PROJECT_ROOT / "scripts" / "run_experiments.py", PROJECT_ROOT / "pyproject.toml"]
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda p: p.as_posix()):
        if path.is_file():
            digest.update(path.relative_to(PROJECT_ROOT).as_posix().encode("utf-8") + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def _local_identity(batch: dict) -> dict:
    from .project import snapshot_context
    from .rules import load_bundle
    from .retrieval import RetrievalIndex
    config = batch["config"]
    # Re-enumeration detects additions/removals when the original selection was
    # a directory, rather than quietly changing a pre-registered study's sample.
    root, paths, selected = _selected_inputs(config)
    inputs = [{"path": p.relative_to(root).as_posix(), "sha256": _digest(p.read_bytes())} for p in paths]
    bundle = load_bundle(Path(config["rules"]) if config.get("rules") else None)
    context = snapshot_context(config["context"])
    metadata = config.get("metadata_manifest")
    reviews = []
    if config.get("review_root"):
        from .requirement_review import load_review, evaluate_review
        review_root = Path(config["review_root"]).resolve()
        for path in paths:
            checklist = (review_root / path.relative_to(root).with_suffix(".json")).resolve()
            if not checklist.is_relative_to(review_root):
                raise ValueError("Review checklist escapes --review-root")
            loaded = load_review(checklist, path)
            # Resolve production/field bindings before any paid model call;
            # an empty source set validates the checklist without supplying an answer.
            evaluate_review(bundle, [], loaded)
            reviews.append({"path": str(checklist), "sha256": loaded["checklist_sha256"]})
    return {
        "config": config, "config_file_sha256": _digest(Path(config["config_file"]).read_bytes()),
        "inputs": inputs, "code_sha256": _source_hash(),
        "repair_feedback": repair_feedback_policy(),
        "metadata_sha256": _digest(Path(metadata).read_bytes()) if metadata else None,
        "selection_sha256": _digest(Path(config["selection"]).read_bytes()) if config.get("selection") else None,
        "selection_cases": [selected[p] for p in paths] if selected else None,
        "rule_bundle": {"path": str(bundle.path), "sha256": bundle.digest,
                        "compiled_grammar_sha256": bundle.compiled_grammar_sha256,
                        "runtime_version": bundle.runtime_version},
        "context_sha256": context.sha256,
        # Retrieved examples are prompt evidence, never part of context_sha256
        # or the engine's symbol environment. Freeze their own content identity.
        "retrieval": {"enabled": config["retrieval_enabled"], **RetrievalIndex().identity()},
        "construction_reviews": reviews,
    }


def _runtime_identity(agent, batch: dict) -> dict:
    from .pipeline import endpoint_snapshot, local_versions, workflow_identity
    from .project import snapshot_context
    # Probe the actual OSATE installation before making any model request.
    availability = agent.osate.probe()
    if availability.get("status") != "passed":
        details = "; ".join(item.get("message", "") for item in availability.get("diagnostics", []))
        raise RuntimeError("OSATE preflight failed: " + details)
    agent.client.bind_rules(agent.bundle)
    agent.client.bind_context(snapshot_context(batch["config"]["context"]))
    endpoint = agent.client.discover(force=True)
    required = {"plain"}
    if any(job["constraints"] == "joint" for job in batch["jobs"]):
        required.add("aadl_joint")
    missing = required - set(endpoint.constraint_types)
    if missing:
        raise RuntimeError("Endpoint lacks required experiment capabilities: " + ", ".join(sorted(missing)))
    return {"endpoint": endpoint_snapshot(endpoint), "versions": local_versions(),
            "workflow_sha256": workflow_identity(),
            "osate": {key: availability.get(key) for key in ("status", "version", "executable", "binary_provenance")}}


@contextmanager
def _agent(config: dict):
    from .config import Config
    from .model import ModelClient
    from .osate import OsateRunner
    from .pipeline import SingleAgent
    from .rules import load_bundle
    settings = Config.load(config["config_file"])
    bundle = load_bundle(config.get("rules"))
    with ModelClient(settings.base_url) as client:
        yield SingleAgent(client, osate=OsateRunner(settings.osate_cli), bundle=bundle)


def prepare_batch(output_root: Path, manifest: dict, config: dict) -> dict:
    """Register every intended job and freeze input bytes before any inference."""
    root, paths, selected = _selected_inputs(config)
    selection = Path(config["input"]).resolve()
    if selection.is_dir() and _inside(output_root.resolve(), selection):
        raise ValueError("--output-root must be outside the selected requirement directory; otherwise generated text would become new input")
    config = dict(config, retrieval_enabled=config.get("retrieval_enabled", True), generated_file_limit=1,
                  input_root=str(root), experiment_protocol=EXPERIMENT_PROTOCOL,
                  experiment_protocol_version=EXPERIMENT_PROTOCOL_VERSION)
    if config["token_budget"] != 8192 or config["max_attempts"] != DEFAULT_MAX_ATTEMPTS:
        raise ValueError(f"This experiment protocol fixes 8192 shared output tokens; plain-once allows 1 attempt and joint-repair allows {DEFAULT_MAX_ATTEMPTS}")
    conditions = config["conditions"]
    if not conditions or len(set(conditions)) != len(conditions) or set(conditions) - set(CONDITIONS):
        raise ValueError("Conditions must be a nonempty, unique subset of plain-once and joint-repair")
    metadata_path = Path(config["metadata_manifest"]) if config.get("metadata_manifest") else None
    metadata = _metadata_index(metadata_path)
    batch_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + uuid.uuid4().hex[:8]
    batch = {"id": batch_id, "created_at": _now(), "status": "prepared", "config": config,
             "experiment_protocol": EXPERIMENT_PROTOCOL, "experiment_protocol_version": EXPERIMENT_PROTOCOL_VERSION,
             "cases": [], "jobs": [], "fingerprint": {"local": None, "runtime": None},
             "metric_definitions": {
                 "first_osate_pass": "attempt == 1, generation complete, valid complete container, OSATE passed",
                 "budget_osate_pass": "any such attempt within the shared initial-generation/repair output-token budget",
                 "effect_denominator": "terminal runs excluding infrastructure failures; model failures included",
                 "independent_core_denominator": "evaluable runs explicitly eligible for independent evaluation and reviewed in_scope; derived and unknown eligibility excluded",
                 "comparison": "plain-once versus joint-repair changes both constraints and repair; not a pure semantic-constraint ablation",
                 "conservative_denominator": "all started jobs; unobserved outcomes do not contribute passes",
                 "requirement_coverage": "not evaluated; references are not automatic correctness oracles",
                 "repeated_batches": "reported separately, never additional independent requirements"}}
    from .retrieval import RetrievalIndex
    retrieval_index = RetrievalIndex()
    for index, path in enumerate(paths):
        relative = path.relative_to(root)
        output = (relative.parent / relative.stem).as_posix()
        raw = path.read_bytes()
        mapped = metadata.get(path.resolve(), {})
        frozen = selected.get(path.resolve(), {})
        for key in ("family", "requirement_origin", "variant_group", "generation_scope_status",
                    "independent_evaluation_eligible"):
            if frozen.get(key) is not None and mapped.get(key) is not None and frozen[key] != mapped[key]:
                raise ValueError(f"Frozen selection and provenance disagree on {key}: {path}")
        mapped = {**mapped, **frozen}
        if mapped.get("requirement_origin") == "derived_example":
            # Frozen selections cannot override the corpus's exclusion policy.
            mapped["independent_evaluation_eligible"] = False
        sha = _digest(raw)
        if mapped.get("sha256") and mapped["sha256"] != sha:
            raise ValueError(f"Requirement hash differs from the provenance manifest: {path}")
        _validate_scope_review(mapped, actual_sha256=sha)
        # Stable IDs are full relative paths, not stems (which can repeat under
        # different project subdirectories). Keep the original corpus ID too.
        case = {"id": relative.as_posix(), "relative_path": relative.as_posix(),
                "source": str(path.resolve()), "sha256": sha, "output": output,
                "role": frozen.get("role", "unspecified"),
                "snapshot": f"{output}/{batch_id}/requirements{path.suffix.lower()}",
                "metadata": {**mapped, "family": mapped.get("family", "unknown"),
                             "requirement_origin": mapped.get("requirement_origin", "unknown")}}
        # Both arms must see byte-identical initial retrieval. This precomputed
        # digest is checked against the actual call record, not merely promised.
        case["retrieval_initial_sha256"] = (retrieval_index.retrieve(
            raw.decode("utf-8-sig"))["sha256"] if config["retrieval_enabled"] else None)
        if config.get("review_root"):
            from .requirement_review import load_review
            spec_path = Path(config["review_root"]) / relative.with_suffix(".json")
            case["construction_review"] = load_review(spec_path, path)
        batch["cases"].append(case)
        # Rotate the sequential order across cases. There are no concurrent model
        # calls, but each condition gets different positions over the corpus.
        offset = index % len(conditions)
        order = conditions[offset:] + conditions[:offset]
        for condition in order:
            batch["jobs"].append({"case_id": case["id"], "condition": condition,
                                  "mode": "direct", **CONDITION_SETTINGS[condition],
                                  "order": len(batch["jobs"]) + 1, "state": "not_started",
                                  "output": f"{output}/{batch_id}/{condition}"})
    # Catch same-stem different-extension collisions with earlier batches too.
    old_outputs = {case["output"].casefold(): case["source"]
                   for old in manifest["batches"] for case in old["cases"]}
    for case in batch["cases"]:
        previous = old_outputs.get(case["output"].casefold())
        if previous and Path(previous).resolve() != Path(case["source"]).resolve():
            raise ValueError(f"Case directory is already owned by another input: {previous} versus {case['source']}")
    batch["fingerprint"]["local"] = _local_identity(batch)
    _require_current_protocol(batch)
    if config.get("warmup", True):
        batch["warmup"] = {"state": "not_started", "output": f"_warmup/{batch_id}",
                           "included_in_statistics": False}
    for case in batch["cases"]:
        snapshot = output_root / case["snapshot"]
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        snapshot.write_bytes(Path(case["source"]).read_bytes())
        if _digest(snapshot.read_bytes()) != case["sha256"]:
            raise ValueError(f"Input changed while taking its snapshot: {case['source']}")
    manifest["batches"].append(batch)
    _save(output_root, manifest)
    export_metrics(output_root, manifest)
    return batch


def _review_candidates(destination: Path, data: dict, case: dict, bundle) -> None:
    """Evaluate frozen assertions after inference, without feedback to the model.

    Read the received container, not the candidate directory: that directory
    also contains real external dependencies, which are not generated evidence.
    Keep this sidecar separate from the generation result and its success flag.
    """
    review = case.get("construction_review")
    if review is None:
        return
    from .project import parse_project
    from .requirement_review import evaluate_review
    observations = []
    for attempt in data.get("attempts") or []:
        record = {"attempt": attempt["attempt"], "static_accepted": attempt.get("accepted", False)}
        raw = Path(attempt["raw_response"]).read_bytes().decode("utf-8")
        if _digest(raw.encode("utf-8")) != attempt.get("candidate_sha256"):
            raise ValueError("Candidate changed before independent construction review")
        document = parse_project(raw)
        if not document.valid or not document.complete or not attempt.get("generation_complete"):
            record.update(status="not_evaluable", reason="Generated project is incomplete or has an invalid container")
        else:
            record.update(evaluate_review(bundle, [{"name": f.name, "text": f.text} for f in document.files], review))
        observations.append(record)
    accepted = next((r for r in observations if r["static_accepted"]), None)
    # A call can fail before returning any recoverable source. Attempt 2 must
    # never become the first-candidate result just because attempt 1 is absent.
    first = next((r for r in observations if r["attempt"] == 1), None)
    _atomic_json(destination / "requirement-review.json", {
        "schema_version": 1, "scope": "explicit_visible_construction_assertions_only",
        "used_for_generation_or_repair": False, "checklist_sha256": review["checklist_sha256"],
        "checklist": review["spec"], "attempts": observations,
        "first_status": first["status"] if first else "not_evaluable",
        "accepted_candidate_status": accepted["status"] if accepted else "no_accepted_candidate"})


def _verify_local(output_root: Path, batch: dict) -> None:
    observed = _local_identity(batch)
    if observed != batch["fingerprint"]["local"]:
        expected = batch["fingerprint"]["local"]
        changed = [key for key in observed if observed[key] != expected.get(key)]
        raise ValueError("Experiment fingerprint changed (" + ", ".join(changed)
                         + "); restore the exact input/config/code/rules/context or start a new batch")
    for case in batch["cases"]:
        snapshot = output_root / case["snapshot"]
        if not snapshot.is_file() or _digest(snapshot.read_bytes()) != case["sha256"]:
            raise ValueError(f"Input snapshot is missing or changed: {snapshot}")


def _print_event(event: dict) -> None:
    # Keep events short enough to make long runs observable without dumping raw
    # prompts, model responses, or private source text to a terminal.
    details = " ".join(f"{key}={event[key]}" for key in
                       ("event", "stage", "phase", "attempt", "call", "status", "remaining_tokens", "message")
                       if key in event)
    print("  " + (details or json.dumps(event, ensure_ascii=False, default=str)), flush=True)


def _check_runtime(current: dict, expected: dict) -> None:
    if current != expected:
        changed = [key for key in set(current) | set(expected) if current.get(key) != expected.get(key)]
        raise ValueError("Runtime fingerprint changed (" + ", ".join(sorted(changed))
                         + "); model/tokenizer/OSATE/server identity must match the original batch")


def _warmup(output_root: Path, manifest: dict, batch: dict, agent, on_progress) -> int:
    """One synthetic warmup per batch, with no held-out document exposure.

    Checkpoint before calling the agent. A crashed warmup is never silently
    repeated, and its calls/tokens do not enter the formal jobs or summaries.
    """
    record = batch.get("warmup")
    if not record:
        return 0
    if record["state"] == "running":
        record.update(state="interrupted", finished_at=_now(), error="Warmup interrupted; not replayed")
        recovery_path = output_root / record["output"]
        recovery_path.mkdir(parents=True, exist_ok=True)
        _atomic_json(recovery_path / "warmup.json", record)
        _save(output_root, manifest)
    if record["state"] != "not_started":
        return 2 if record.get("failure_kind") in INFRASTRUCTURE_KINDS else 0
    destination = output_root / record["output"]
    if destination.exists():
        raise ValueError(f"Unregistered warmup output exists: {destination}")
    constraints = next((kind for kind in ("joint", "plain")
                        if any(j["constraints"] == kind for j in batch["jobs"])), "plain")
    budget = min(512, batch["config"]["token_budget"], batch["config"].get("warmup_token_budget", 512))
    record.update(state="running", started_at=_now(), mode="direct", constraints=constraints,
                  repair_enabled=False, token_budget=budget, max_attempts=1,
                  result_path=record["output"] + "/run/result.json")
    _save(output_root, manifest)
    destination.mkdir(parents=True)
    _atomic_json(destination / "warmup.json", record)
    run_destination = destination / "run"
    print("Warmup (synthetic input; excluded from formal statistics)", flush=True)
    try:
        result = agent.run("Produce one minimal complete project using the loaded language rules.",
                           run_destination, constraints=constraints, repair_enabled=False, token_budget=budget,
                           max_attempts=1, context=[], instance_root=None,
                           retrieval=batch["config"]["retrieval_enabled"], on_progress=on_progress)
        data = asdict(result) if is_dataclass(result) else dict(result)
        if not (run_destination / "result.json").exists():
            run_destination.mkdir(parents=True, exist_ok=True)
            _atomic_json(run_destination / "result.json", data)
        record.update(state="finished" if data.get("status") == "passed" else "failed",
                      failure_kind=data.get("failure_kind"), error=data.get("error"))
    except KeyboardInterrupt:
        record.update(state="interrupted", error="Warmup interrupted; not replayed")
        raise
    except Exception as exc:
        record.update(state="failed", failure_kind="runner_error", error=str(exc))
    finally:
        record["finished_at"] = _now()
        destination.mkdir(parents=True, exist_ok=True)
        _atomic_json(destination / "warmup.json", record)
        _save(output_root, manifest)
    return 2 if record.get("failure_kind") in INFRASTRUCTURE_KINDS else 0


def execute_batch(output_root: Path, manifest: dict, batch: dict, agent, *,
                  on_progress=_print_event, local_verifier=_verify_local,
                  runtime_identity=_runtime_identity) -> int:
    """Run only not_started jobs; injectable seams support offline smoke tests.

    A previously running job is never repeated: the server may have consumed
    tokens before the client died. Resuming it would silently exceed the budget.
    """
    _require_current_protocol(batch)
    for job in batch["jobs"]:
        if job["state"] == "running":
            job.update(state="interrupted", failure_kind="interrupted", finished_at=_now(),
                       error="Previous process stopped while this job was running; not replayed")
    _save(output_root, manifest)
    export_metrics(output_root, manifest)
    if not any(job["state"] == "not_started" for job in batch["jobs"]):
        batch["status"] = "finished"
        _save(output_root, manifest)
        print(f"Batch {batch['id']} has no not_started jobs; use run for a new batch.")
        return 0
    try:
        local_verifier(output_root, batch)
        current = runtime_identity(agent, batch)
        previous = batch["fingerprint"].get("runtime")
        if previous is not None:
            _check_runtime(current, previous)
        batch["fingerprint"]["runtime"] = current
        batch["fingerprint"]["sha256"] = _json_hash({"local": batch["fingerprint"]["local"], "runtime": current})
        batch.update(status="running", last_resumed_at=_now())
        batch.pop("error", None)
        _save(output_root, manifest)
    except KeyboardInterrupt:
        batch.update(status="interrupted", error="Interrupted during preflight")
        _save(output_root, manifest)
        return 130
    except Exception as exc:
        batch.update(status="stopped_infrastructure", error=str(exc))
        _save(output_root, manifest)
        export_metrics(output_root, manifest)
        print(str(exc), file=sys.stderr)
        return 2
    cases = {case["id"]: case for case in batch["cases"]}
    settings = batch["config"]
    try:
        if _warmup(output_root, manifest, batch, agent, on_progress):
            batch.update(status="stopped_infrastructure", error=batch["warmup"].get("error"))
            _save(output_root, manifest)
            return 2
    except KeyboardInterrupt:
        batch.update(status="interrupted", error="Interrupted during warmup")
        _save(output_root, manifest)
        return 130
    for job in batch["jobs"]:
        if job["state"] != "not_started":
            continue
        case = cases[job["case_id"]]
        try:
            # Check local identities immediately before every request sequence.
            # A researcher editing a rule/input during a long batch must not
            # accidentally create a mixed-version condition comparison.
            local_verifier(output_root, batch)
            # Force discovery between jobs: a restarted remote service can
            # retain the same model name/rules while executing different code.
            before = runtime_identity(agent, batch)
            _check_runtime(before, batch["fingerprint"]["runtime"])
            job["runtime_before_sha256"] = _json_hash(before)
            destination = output_root / job["output"]
            if destination.exists():
                raise ValueError(f"Unregistered output already exists; refusing to overwrite: {destination}")
        except KeyboardInterrupt:
            batch.update(status="interrupted", error="Interrupted before starting the next job")
            _save(output_root, manifest)
            export_metrics(output_root, manifest)
            return 130
        except Exception as exc:
            batch.update(status="stopped_infrastructure", error=str(exc))
            _save(output_root, manifest)
            export_metrics(output_root, manifest)
            print(str(exc), file=sys.stderr)
            return 2
        job.update(state="running", started_at=_now())
        # This checkpoint precedes .run(): even a process kill inside the first
        # network request leaves an explicit started job in the manifest.
        _save(output_root, manifest)
        print(f"[{job['order']}/{len(batch['jobs'])}] {case['relative_path']} | {job['condition']}", flush=True)
        try:
            result = agent.run((output_root / case["snapshot"]).read_bytes().decode("utf-8-sig"), destination,
                               constraints=job["constraints"], repair_enabled=job["repair_enabled"],
                               token_budget=settings["token_budget"], max_attempts=job["max_attempts"],
                               context=settings["context"], instance_root=settings.get("instance_root"),
                               retrieval=settings["retrieval_enabled"],
                               on_progress=on_progress)
            data = asdict(result) if is_dataclass(result) else dict(result)
            # Real SingleAgent owns raw/attempt/result artifacts. The fallback
            # only supports injected agents which return the same public result.
            if not (destination / "result.json").exists():
                destination.mkdir(parents=True, exist_ok=True)
                _atomic_json(destination / "result.json", data)
            job.update(state="finished" if data.get("status") == "passed" else "failed",
                       finished_at=_now(), failure_kind=data.get("failure_kind"), error=data.get("error"))
            try:
                _review_candidates(destination, data, case, agent.bundle)
            except Exception as exc:
                # Assessment availability is independent of model/static validity.
                # Preserve a successful generation even if its optional reviewer
                # cannot read an artifact or evaluate a newly introduced construct.
                job["construction_review_error"] = str(exc)
                _atomic_json(destination / "requirement-review.json", {
                    "schema_version": 1, "used_for_generation_or_repair": False,
                    "first_status": "unavailable", "accepted_candidate_status": "unavailable",
                    "error": str(exc), "attempts": []})
            try:
                calls = data.get("calls") or []
                if calls and settings["retrieval_enabled"]:
                    actual = (calls[0].get("retrieval") or {}).get("sha256")
                    if actual != case["retrieval_initial_sha256"]:
                        raise ValueError("Initial retrieved evidence differs from the frozen paired-arm snapshot")
                after = runtime_identity(agent, batch)
                job["runtime_after_sha256"] = _json_hash(after)
                _check_runtime(after, batch["fingerprint"]["runtime"])
                local_verifier(output_root, batch)
            except Exception as exc:
                # Preserve the actual candidate/result but exclude this job from
                # efficacy comparisons: its runtime version cannot be certified.
                job.update(state="failed", failure_kind="fingerprint_mismatch", error=str(exc))
        except KeyboardInterrupt:
            job.update(state="interrupted", failure_kind="interrupted", finished_at=_now(), error="Interrupted by user")
            batch["status"] = "interrupted"
            _save(output_root, manifest)
            export_metrics(output_root, manifest)
            print("Interrupted job saved; resume will only run jobs that never started.", file=sys.stderr)
            return 130
        except Exception as exc:
            # An unexpected client fault is infrastructure evidence, not proof
            # that an LLM failed the task. Preserve any pipeline checkpoint.
            job.update(state="failed", failure_kind="runner_error", finished_at=_now(), error=str(exc))
        _save(output_root, manifest)
        export_metrics(output_root, manifest)
        print(f"  {job['state']}: {job.get('failure_kind') or 'passed'}", flush=True)
        if job.get("failure_kind") in INFRASTRUCTURE_KINDS:
            batch.update(status="stopped_infrastructure", error=job.get("error"))
            _save(output_root, manifest)
            return 2
    batch.update(status="finished", finished_at=_now())
    _save(output_root, manifest)
    export_metrics(output_root, manifest)
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run", help="Start a new batch for one file or a recursive directory")
    run.add_argument("input", type=Path, nargs="?", help="UTF-8 .txt/.md file or directory")
    run.add_argument("--selection", type=Path, help="Frozen case selection JSON; preserves its relative paths and roles")
    run.add_argument("--selection-role", help="Only schedule this role from the frozen selection")
    run.add_argument("--no-warmup", action="store_true", help="Explicitly disable the separate synthetic warmup")
    run.add_argument("--warmup-token-budget", type=int, default=512)
    run.add_argument("--input-root", type=Path, help="Stable root for relative output mapping")
    run.add_argument("--config", type=Path, default=Path("config.yaml"))
    run.add_argument("--rules", type=Path)
    run.add_argument("--metadata-manifest", type=Path, help="Research metadata; paths are relative to its grandparent")
    run.add_argument("--review-root", type=Path,
                     help="Independent frozen checklists matching relative requirement paths with .json suffix; never sent to the model")
    run.add_argument("--conditions", nargs="+", choices=CONDITIONS, default=list(CONDITIONS))
    run.add_argument("--token-budget", type=int, choices=(8192,), default=8192, help="Shared initial-generation/repair output tokens per job")
    run.add_argument("--max-attempts", type=int, choices=(DEFAULT_MAX_ATTEMPTS,), default=DEFAULT_MAX_ATTEMPTS,
                     help="Joint-repair limit, including initial generation; plain-once is always one attempt")
    run.add_argument("--context", type=Path, action="append", default=[])
    run.add_argument("--retrieval", choices=("on", "off"), default="on",
                     help="Deterministic local RAG for both arms; off is for controlled development comparisons")
    run.add_argument("--instance-root")
    resume = commands.add_parser("resume", help="Run only not_started jobs under the original fingerprints")
    resume.add_argument("--batch", help="Batch ID; defaults to latest batch with pending/running jobs")
    summarize = commands.add_parser("summarize", help="Regenerate CSV and per-case result.txt offline")
    diagnose = commands.add_parser("diagnose", help="Offline result freeze/replay and rule regressions", add_help=False)
    diagnose.add_argument("diagnostic_args", nargs=argparse.REMAINDER)
    for command in (run, resume, summarize):
        command.add_argument("--output-root", type=Path, default=Path("Generate"))
    return parser


def _interactive() -> list[str]:
    print("AADL experiments: [1] new run  [2] resume  [3] summarize")
    action = input("Select [1]: ").strip() or "1"
    if action not in {"1", "2", "3"}:
        raise ValueError("Select 1, 2, or 3")
    command = {"1": "run", "2": "resume", "3": "summarize"}[action]
    argv = [command]
    if command == "run":
        selected = input("Requirements file or directory [example/requirements]: ").strip().strip('"') or "example/requirements"
        argv.append(selected)
    output = input("Output root [Generate]: ").strip().strip('"') or "Generate"
    argv += ["--output-root", output]
    if command == "run":
        requested = input("Conditions [both; or plain-once/joint-repair]: ").strip()
        if requested and requested.lower() not in {"all", "both"}:
            argv += ["--conditions", *requested.split()]
        config = input("Configuration [config.yaml]: ").strip().strip('"') or "config.yaml"
        argv += ["--config", config]
        retrieval = input("Local retrieval [on/off; default on]: ").strip().lower() or "on"
        if retrieval not in {"on", "off"}:
            raise ValueError("Retrieval must be on or off")
        argv += ["--retrieval", retrieval]
    elif command == "resume":
        batch = input("Batch ID [latest unfinished]: ").strip()
        if batch:
            argv += ["--batch", batch]
    return argv


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    argv = list(sys.argv[1:] if argv is None else argv)
    try:
        if not argv:
            if not sys.stdin.isatty():
                parser.print_help()
                return 2
            argv = _interactive()
        # Route before parsing so diagnostic --help/options belong to its own
        # parser rather than being rejected by the scheduling subparser.
        if argv and argv[0] == "diagnose":
            from .diagnostics import main as diagnostic_main
            return diagnostic_main(argv[1:])
        args = parser.parse_args(argv)
        if args.command == "run" and (bool(args.input) == bool(args.selection)):
            raise ValueError("Provide exactly one requirements input or --selection")
        if args.command == "run" and not 1 <= args.warmup_token_budget <= 512:
            raise ValueError("--warmup-token-budget must be in 1..512")
        output_root = args.output_root.expanduser().resolve()
        with _output_lock(output_root):
            manifest = _load(output_root)
            if args.command == "summarize":
                if not (output_root / "manifest.json").is_file():
                    raise ValueError(f"No experiment manifest in {output_root}")
                rows, summaries = export_metrics(output_root, manifest)
                print(f"Exported {len(rows)} jobs and {len(summaries)} summary groups to {output_root}")
                return 0
            if args.command == "run":
                selection = (args.input or args.input_root or DEFAULT_INPUT_ROOT).expanduser().resolve()
                metadata = args.metadata_manifest
                if metadata is None and _inside(selection, DEFAULT_INPUT_ROOT.resolve()) and DEFAULT_METADATA.is_file():
                    metadata = DEFAULT_METADATA
                config = {
                    "input": str(selection), "input_root": str(args.input_root.expanduser().resolve()) if args.input_root else None,
                    "config_file": str(args.config.expanduser().resolve()),
                    "rules": str(args.rules.expanduser().resolve()) if args.rules else None,
                    "metadata_manifest": str(metadata.expanduser().resolve()) if metadata else None,
                    "review_root": str(args.review_root.expanduser().resolve()) if args.review_root else None,
                    "conditions": list(args.conditions), "token_budget": args.token_budget,
                    "max_attempts": args.max_attempts, "context": [str(p.expanduser().resolve()) for p in args.context],
                    "instance_root": args.instance_root,
                    "retrieval_enabled": args.retrieval == "on",
                    "selection": str(args.selection.expanduser().resolve()) if args.selection else None,
                    "selection_role": args.selection_role,
                    "warmup": not args.no_warmup, "warmup_token_budget": args.warmup_token_budget,
                    "decoding": {"temperature": 0, "thinking": False, "batch_size": 1, "num_beams": 1},
                }
                batch = prepare_batch(output_root, manifest, config)
                print(f"Registered batch {batch['id']}: {len(batch['cases'])} requirements, {len(batch['jobs'])} jobs", flush=True)
            else:
                candidates = [batch for batch in manifest["batches"] if
                              (batch["id"] == args.batch if args.batch else
                               any(job["state"] in {"not_started", "running"} for job in batch["jobs"]))]
                if not candidates:
                    raise ValueError("No matching unfinished batch; use run to create a new batch")
                batch = candidates[-1]
                _require_current_protocol(batch)
            # Normalize crash history before constructing a client: even a
            # broken configuration must not leave an old job labelled running.
            recovered = False
            for job in batch["jobs"]:
                if job["state"] == "running":
                    job.update(state="interrupted", failure_kind="interrupted", finished_at=_now(),
                               error="Previous process stopped while this job was running; not replayed")
                    recovered = True
            if recovered:
                _save(output_root, manifest)
                export_metrics(output_root, manifest)
            try:
                with _agent(batch["config"]) as agent:
                    return execute_batch(output_root, manifest, batch, agent)
            except KeyboardInterrupt:
                batch.update(status="interrupted", error="Interrupted during agent initialization")
                _save(output_root, manifest)
                return 130
            except Exception as exc:
                batch.update(status="stopped_infrastructure", error=str(exc))
                _save(output_root, manifest)
                export_metrics(output_root, manifest)
                raise
    except KeyboardInterrupt:
        return 130
    except (OSError, ValueError, RuntimeError) as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
