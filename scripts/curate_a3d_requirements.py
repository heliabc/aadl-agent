"""Render and index explicitly derived A3D examples, outside evaluation inputs.

The per-case JSON is the editable source of the prose and its line-level evidence.
Rendering never edits vendor files or treats model-derived text as author intent.
Run this script after editing those records; --check verifies without writing.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path, PurePosixPath
import re


ROOT = Path(__file__).resolve().parents[1]
EXAMPLE = ROOT / "example"
RECORDS = EXAMPLE / "reference/public/a3d/requirements"
OUTPUT_PREFIX = "derived_requirements/a3d/"
ORIGINS = {"model_derived", "embedded_contract", "author_requirement", "document_derived"}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def local_path(relative: str) -> Path:
    """Keep editable provenance paths within example/, including on Windows."""
    if not relative or "\\" in relative or ":" in relative or relative.startswith("/"):
        raise ValueError(f"Invalid example-relative path: {relative!r}")
    if any(part in {"", ".", ".."} for part in relative.split("/")):
        raise ValueError(f"Invalid relative path component: {relative!r}")
    path = EXAMPLE.joinpath(*PurePosixPath(relative).parts)
    if not path.resolve().is_relative_to(EXAMPLE.resolve()):
        raise ValueError(f"Path leaves example directory: {relative!r}")
    return path


def validate_records(manifest: dict) -> list[tuple[Path, dict]]:
    """Bind every numbered requirement to preserved source bytes and line spans.

    These mechanical checks establish traceability, not semantic entailment.
    Human/agent review of each requirement against the cited span is still needed.
    """
    if not RECORDS.exists():
        if manifest.get("a3d", {}).get("requirement_examples"):
            raise ValueError("Indexed derived requirement records are missing")
        return []
    cases = set(manifest["a3d"]["case_ids"])
    sources = {item["family"]: item for item in manifest["public_sources"]}
    artifacts = {item["path"]: item for item in manifest["source_artifacts"]}
    result, seen_cases, seen_ids = [], set(), set()
    for path in sorted(RECORDS.glob("*.json")):
        record = read_json(path)
        case_id = record["case_id"]
        if case_id not in cases or case_id in seen_cases or path.stem != case_id:
            raise ValueError(f"Unexpected/repeated case record: {path}")
        seen_cases.add(case_id)
        source_info = sources[case_id]
        if (record.get("source_repository") != source_info["repository"]
                or record.get("source_ref") != source_info["source_ref"]
                or record.get("root_classifiers") != source_info["root_classifiers"]):
            raise ValueError(f"Requirement record disagrees with the indexed source identity: {case_id}")
        if record.get("requirement_origin") != "derived_example" or record.get("independent_evaluation_eligible") is not False:
            raise ValueError(f"Derived example must be excluded from independent evaluation: {case_id}")
        if record.get("is_gold_reference") is not False:
            raise ValueError(f"Derived example cannot declare a gold reference: {case_id}")
        if not record.get("title") or not record.get("coverage") or not record.get("limitations"):
            raise ValueError(f"Missing scope or limitations: {case_id}")
        paragraphs(record["coverage"])
        paragraphs(record["limitations"])
        original_status_text(record["original_requirement_status"])
        requirements = record.get("requirements", [])
        if not requirements:
            raise ValueError(f"Empty requirement set: {case_id}")
        # Excluded observations are still provenance records. Check their source
        # identity too, while keeping them out of the rendered normative text.
        for requirement in requirements + record.get("excluded_source_statements", []):
            item_id = requirement["id"]
            if not re.fullmatch(r"[A-Z][A-Z0-9_-]+", item_id) or item_id in seen_ids:
                raise ValueError(f"Invalid/repeated requirement ID: {item_id}")
            seen_ids.add(item_id)
            if not requirement.get("text", "").strip() or requirement["origin_kind"] not in ORIGINS:
                raise ValueError(f"Missing text or unknown origin: {item_id}")
            if not requirement.get("evidence"):
                raise ValueError(f"Requirement has no source evidence: {item_id}")
            for evidence in requirement["evidence"]:
                source = local_path(evidence["local_source"])
                artifact = artifacts.get(evidence["local_source"])
                data = source.read_bytes()
                if artifact is None or digest(data) != evidence["sha256"] or evidence["sha256"] != artifact["sha256"]:
                    raise ValueError(f"Evidence does not match the pinned source: {item_id}: {source}")
                try:
                    text = data.decode("utf-8-sig")
                except UnicodeDecodeError:
                    text = data.decode("cp1252")
                lines = text.splitlines()
                first, last = evidence["line_start"], evidence["line_end"]
                if type(first) is not int or type(last) is not int or not 1 <= first <= last <= len(lines):
                    raise ValueError(f"Invalid evidence line range: {item_id}: {source}")
                span = "\n".join(lines[first - 1:last])
                if not span.strip() or digest(span.encode("utf-8")) != evidence["span_sha256"]:
                    raise ValueError(f"Evidence span differs from the reviewed span: {item_id}: {source}")
                expected_url = artifact["source_url"] + f"#L{first}-L{last}"
                for key, expected in (("source_repository", artifact["source_repository"]),
                                      ("source_ref", artifact["source_ref"]),
                                      ("source_url", expected_url)):
                    if key in evidence and evidence[key] != expected:
                        raise ValueError(f"Evidence {key} disagrees with the pinned artifact: {item_id}")
        result.append((path, record))
    if seen_cases != cases:
        raise ValueError(f"Missing requirement cases: {sorted(cases - seen_cases)}")
    return result


def paragraphs(value: str | list[str]) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return value
    raise ValueError("Scope and limitations must be text or lists of text")


def original_status_text(value: str | dict) -> str:
    """Keep structured search status in metadata, render its explanatory prose."""
    text = value.get("explanation") if isinstance(value, dict) else value
    if not isinstance(text, str) or not text.strip():
        raise ValueError("Original-requirement status needs an explanation")
    return text


def render(record: dict, source: dict) -> bytes:
    """Produce a self-contained example prompt with a visible provenance label."""
    content = [f"# {record['title']}", "",
        "> Provenance: model-derived example, curated with AI assistance and checked against the pinned source.",
        "> This is not an original stakeholder specification. Use it for architecture reconstruction and tool demonstrations; do not use it for independent evaluation or as a gold answer.", "",
        "Model the following selected architecture in AADL. Requirements identified below are normative for this example; scope limitations are explanatory and do not request reproduction of source defects.", "",
        "## Scope", ""]
    for paragraph in paragraphs(record["coverage"]):
        content.extend([paragraph, ""])
    content.extend(["## Requirements", ""])
    for requirement in record["requirements"]:
        content.extend([f"### {requirement['id']}", "", requirement["text"].strip(), ""])
        if requirement.get("scope"):
            content.extend([f"Applies to: `{requirement['scope']}`.", ""])
        if requirement.get("note"):
            content.extend(["Qualification: " + requirement["note"], ""])
    content.extend(["## Scope limitations", ""])
    for limitation in paragraphs(record["limitations"]):
        content.extend([f"- {limitation}", ""])
    content.extend(["## Provenance", "",
        f"Source project: [{source['repository']}]({source['source_url']}).",
        f"Pinned source commit: `{source['source_ref']}`.", "",
        original_status_text(record["original_requirement_status"]), "",
        source["license_notice"], "",
        "The original mining-time commit of the A3D instance is unknown. The requirement-to-source mapping describes the pinned upstream snapshot, not a recovered historical stakeholder specification.", "",
        f"Line-level traceability: `example/reference/public/a3d/requirements/{record['case_id']}.json`.", ""])
    return "\n".join(content).encode("utf-8")


def integrate(manifest: dict, *, render_outputs: bool = False) -> dict:
    """Add a separate example collection without changing evaluation requirements.

    Called by both this command and the reference indexer so that refreshing the
    imported references cannot silently discard the derived requirement links.
    """
    records = validate_records(manifest)
    if not records:
        return manifest
    result = deepcopy(manifest)
    existing_outputs = {item["path"]: item["sha256"] for item in manifest.get("derived_requirements", [])}
    existing_metadata = {item["path"]: item for item in manifest.get("derived_requirements", [])}
    sources = {item["family"]: item for item in result["public_sources"]}
    previous = manifest.get("a3d", {}).get("requirement_examples", {}).get("previous_manifest")
    if previous is None:
        # Whole-manifest hashes are frozen by existing experiments. Keep the old
        # bytes beside the active manifest so its relative paths still resolve.
        current = EXAMPLE / "reference/manifest.json"
        data = current.read_bytes()
        snapshot = current.with_name(f"manifest.pre-a3d-requirements-{digest(data)[:12]}.json")
        previous = {"path": snapshot.relative_to(EXAMPLE).as_posix(), "sha256": digest(data)}
        if render_outputs:
            if snapshot.exists() and snapshot.read_bytes() != data:
                raise ValueError("Pre-requirement manifest snapshot has conflicting bytes")
            if not snapshot.exists():
                snapshot.write_bytes(data)
    elif digest(local_path(previous["path"]).read_bytes()) != previous["sha256"]:
        raise ValueError("Pre-requirement manifest snapshot has changed")
    result["derived_requirements"] = [item for item in result.get("derived_requirements", [])
                                      if not item["path"].startswith(OUTPUT_PREFIX)]
    total = 0
    pending_outputs = []
    for trace_path, record in records:
        case_id = record["case_id"]
        source = sources[case_id]
        document_id = f"derived__{case_id}__system"
        relative = f"{OUTPUT_PREFIX}{case_id}/system.md"
        target = local_path(relative)
        data = render(record, source)
        if render_outputs and (not target.exists() or target.read_bytes() != data):
            # Markdown is rendered from the reviewed JSON. Preserve manual edits
            # instead of silently replacing them during a later refresh.
            if target.exists() and digest(target.read_bytes()) != existing_outputs.get(relative):
                raise ValueError(f"Derived output has unindexed edits; preserve them before rendering: {relative}")
            pending_outputs.append((target, data))
        elif not target.exists() or target.read_bytes() != data:
            raise ValueError(f"Derived requirement text is stale: run curate_a3d_requirements.py: {relative}")
        item = {"id": document_id, "path": relative, "sha256": digest(data), "language": "en",
                "family": case_id, "variant_group": f"public/a3d/{case_id}",
                "requirement_origin": "derived_example", "independent_evaluation_eligible": False,
                "is_gold_reference": False, "reference_project": source["reference_project"],
                "root_classifiers": source["root_classifiers"], "source_ref": source["source_ref"],
                "source_url": source["source_url"], "requirement_count": len(record["requirements"]),
                "requirement_ids": [r["id"] for r in record["requirements"]],
                "traceability_path": trace_path.relative_to(EXAMPLE).as_posix(),
                "traceability_sha256": digest(trace_path.read_bytes()),
                "coverage": record["coverage"], "original_requirement_status": record["original_requirement_status"],
                "evaluation_scope": "Source-informed reconstruction and demonstrations only; no independent requirements-to-model evaluation."}
        # Scope review is independent of rendering. Retain it only while it
        # describes these exact bytes; a changed derived document needs review.
        old = existing_metadata.get(relative, {})
        review = old.get("generation_scope_review") or {}
        if review.get("requirement_sha256") == item["sha256"]:
            for key in ("generation_scope_status", "generation_scope_review"):
                if key in old:
                    item[key] = deepcopy(old[key])
        result["derived_requirements"].append(item)
        source["derived_requirement_ids"] = [document_id]
        source["derived_requirement_paths"] = [relative]
        source["requirements_coverage"] = "selected_architectural_intent_derived_from_source"
        for reference in result["references"]:
            if reference.get("family") == case_id:
                reference["derived_requirement_ids"] = [document_id]
        total += len(record["requirements"])
    result["counts"]["a3d_derived_requirement_files"] = len(records)
    result["counts"]["a3d_derived_requirement_statements"] = total
    result["a3d"]["requirement_examples"] = {
        "path": OUTPUT_PREFIX.rstrip("/"), "traceability": RECORDS.relative_to(EXAMPLE).as_posix(),
        "files": len(records), "statements": total, "origin": "derived_example",
        "independent_evaluation_eligible": False, "included_in_standard_requirements": False,
        "previous_manifest": previous,
        "authorization": "User explicitly authorized model-derived examples, separately labeled and excluded from independent evaluation.",
        "scope": "Selected architecture, interface, deployment and contract intent; full functional completeness is not established."}
    # Validate and render the entire collection before writing any prose; a bad
    # later record must not leave earlier outputs updated against an old index.
    for target, data in pending_outputs:
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(".md.tmp")
        temporary.write_bytes(data)
        temporary.replace(target)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check prose, evidence, and manifest without writing.")
    args = parser.parse_args()
    path = EXAMPLE / "reference/manifest.json"
    before = read_json(path)
    after = integrate(before, render_outputs=not args.check)
    if args.check and before != after:
        raise SystemExit("Derived requirement manifest is stale; run without --check.")
    if not args.check and before != after:
        temporary = path.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(after, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        temporary.replace(path)
    summary = after.get("a3d", {}).get("requirement_examples", {})
    print(json.dumps({"files": summary.get("files", 0), "statements": summary.get("statements", 0),
                      "standard_requirement_files": after["counts"]["requirements"],
                      "independent_evaluation_eligible": False}))


if __name__ == "__main__":
    main()
