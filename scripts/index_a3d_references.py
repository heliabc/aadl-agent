"""Index byte-verified A3D reference projects without creating generation tasks.

Run after import_a3d_references.py and validate_a3d_references.py. Only A3D-owned
records are replaced; existing requirements, families and source records survive.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re

from curate_a3d_requirements import integrate as integrate_derived_requirements


ROOT = Path(__file__).resolve().parents[1]
EXAMPLE = ROOT / "example"
PREFIX = "reference/public/a3d/"
DATASET_REF = "a57fbf6d21cfdcd1bd16443e73f69f8785c48d94"
AADLIB_REF = "c8a5c7e310b37761bb5ecc7b5ff3fc6cbd42716a"
SMACCM_REF = "7393c423b5aca1a272a88cbb89b5dcfd08a5a47c"

# These are complete source projects, not seven independent benchmark tasks.
# A3D sometimes indexes only a subsystem; the whole-project scope is deliberate.
CASES = [
    {"id": "a3d_aps", "title": "APS 自主客船通信架构", "repository": "ahmed-amro/APS-Communication_Architecture",
     "source_ref": "2cb6cfc34b2b7c5c0fc5b76b7a5ab0822e60368e", "source_path": "", "project": "sources/aps",
     "roots": ["Overall::APSCommunicationArchitecture.impl"],
     "value": "System-of-systems decomposition, ship/shore/cloud communications, end-to-end flows and security properties.",
     "license_notice": "Upstream GPLv3 LICENSE retained; no relicensing under the A3D collection license.",
     "limitations": ["An architectural research model, not a complete ship implementation."]},
    {"id": "a3d_qfcs_v2", "title": "QFCS_V2 冗余飞控", "repository": "smaccm/smaccm",
     "source_ref": SMACCM_REF, "source_path": "models/QFCS_V2", "project": "sources/smaccm/models/QFCS_V2",
     "roots": ["FCS::FCS.impl"],
     "value": "Four flight-control computers, redundant sensor channels, input selection and AGREE contracts.",
     "license_notice": "Original SMACCM BSD 3-Clause LICENSE retained.",
     "limitations": ["AGREE_CONSTANTS.AGREE_CONSTANTS.EGI_TAT_tol appears in ISAS_S_190.aadl; original text retained.",
                     "Some implementation contracts are commented out or empty; contract proofs are not assumed."]},
    {"id": "a3d_smaccm_phase2", "title": "SMACCM Phase2 无人机系统", "repository": "smaccm/smaccm",
     "source_ref": SMACCM_REF, "source_path": "models/SmaccmPhase2", "project": "sources/smaccm/models/SmaccmPhase2",
     "roots": ["UAS::UAS.Impl"],
     "value": "UAV/ground-station architecture, PX4/ODROID hardware, flight/mission software and memory bindings.",
     "license_notice": "Original SMACCM BSD 3-Clause LICENSE retained.",
     "limitations": ["Original todo.txt describes unfinished hardware/software details.",
                     "Source_Text references external generated C/header artifacts; executable completeness is not established."]},
    {"id": "a3d_paparazzi", "title": "Paparazzi 无人机架构", "repository": "OpenAADL/AADLib",
     "source_ref": AADLIB_REF, "source_path": "examples/paparazzi", "project": "sources/aadlib/examples/paparazzi",
     "roots": ["paparazzi_system::paparazzi.basic_archi", "paparazzi_system::paparazzi.PnP_tasks_interruptions",
               "paparazzi_system::paparazzi.PP_tasks_interruptions", "paparazzi_system::paparazzi.PP_tasks_AP_interruptions"],
     "value": "Autopilot/fly-by-wire decomposition, SPI communication and progressive task/interrupt scheduling variants.",
     "license_notice": "AADLib root LICENSE retained together with the model's GNU-GPL/PapaBench origin notice; no blanket BSD relabeling.",
     "limitations": ["Original flight-control C sources referenced by the model are not all supplied."]},
    {"id": "a3d_adiru", "title": "ADIRU 航空惯性参考系统", "repository": "OpenAADL/AADLib",
     "source_ref": AADLIB_REF, "source_path": "examples/adiru", "project": "sources/aadlib/examples/adiru",
     "roots": ["ADIRU::ADIRU.impl", "ADIRU::ADIRU.DeOS", "ADIRU::ADIRU.VxWorks653", "ADIRU::ADIRU.EMV2"],
     "value": "ARINC653 partitions, processor/memory bindings, health monitoring and EMV2 error modeling.",
     "license_notice": "Original AADLib BSD 3-Clause LICENSE and per-file notices retained.",
     "limitations": ["DeOS/VxWorks653 deployment and error-analysis variants require their respective tool support."]},
    {"id": "a3d_aocs", "title": "AOCS 卫星姿态与轨道控制", "repository": "OpenAADL/AADLib",
     "source_ref": AADLIB_REF, "source_path": "examples/aocs", "project": "sources/aadlib/examples/aocs",
     "roots": ["AOCS::AOCS_Subsystem.impl"],
     "value": "Sensor/actuator/watchdog integration with control processing and DMA/OBDH bindings.",
     "license_notice": "Original AADLib BSD 3-Clause LICENSE and per-file notices retained.",
     "limitations": ["Original TODO comments identify sensor inputs that remain to be added."]},
    {"id": "a3d_fms", "title": "AADLib FMS 飞行管理系统", "repository": "OpenAADL/AADLib",
     "source_ref": AADLIB_REF, "source_path": "examples/fms", "project": "sources/aadlib/examples/fms",
     "roots": ["fms::fms.impl"],
     "value": "Four-module integration, CAN/AFDX networks and logical-to-virtual/physical connection bindings.",
     "license_notice": "Original AADLib BSD 3-Clause LICENSE and per-file notices retained.",
     "limitations": ["ARINC653, paths_specification and real_specification support is tool-dependent."]},
]

DEFERRED = [
    {"id": "6ucubesat", "repository": "AFRL-VVCAS/6UCubeSat", "source_ref": "99136d60c707cc79f8d0dd770532d8838b0158ca",
     "status": "index_only", "reason": "High-value RTA architecture; redistribution terms not established by a public-release statement."},
    {"id": "obeo_cruise_control", "repository": "ObeoNetwork/AADL-Designer", "source_ref": "6a726a8c83f2892cfc3aa21a1bebebafe1212b3f",
     "status": "index_only", "reason": "Main connections, scheduling and processor bindings are commented out; incomplete control-system example."},
    {"id": "a3d_security_system", "repository": "GaloisInc/CASE-AADL-Tutorial",
     "status": "unresolved_upstream", "reason": "The A3D syst.aadl path was not found in the claimed repository; do not inherit its license or substitute another tutorial."},
    {"id": "polyorb", "repository": "OpenAADL/polyorb-hi-c", "source_ref": "fb0069ede7def7ebe863782c2ab5b4055fd85c41",
     "status": "second_batch", "reason": "Recover actual application projects from scenario wrappers and resolve runtime/library license applicability before inclusion."},
]


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verified_artifacts(records: list[dict]) -> list[dict]:
    """Treat every downloaded byte and its pinned Git blob as an invariant."""
    seen = set()
    for record in records:
        relative = record["path"]
        path = (EXAMPLE / relative).resolve()
        if not relative.startswith(PREFIX) or not path.is_relative_to(EXAMPLE.resolve()) or relative in seen:
            raise ValueError(f"Invalid or repeated artifact path: {relative}")
        seen.add(relative)
        payload = path.read_bytes()
        blob = hashlib.sha1(b"blob " + str(len(payload)).encode("ascii") + b"\0" + payload).hexdigest()
        if hashlib.sha256(payload).hexdigest() != record["sha256"] or blob != record["git_blob_sha1"]:
            raise ValueError(f"Source bytes differ from pinned artifact: {relative}")
    return sorted(records, key=lambda r: r["path"])


def duplicate_observations(references: list[dict], artifacts: list[dict]) -> dict:
    """Report exact source overlap and bounded structural candidates, not independence.

    Whitespace/comment normalization only finds conservative textual candidates.
    Instance signatures discard source hrefs but keep hierarchy, names, endpoints
    and properties. Neither procedure proves semantic equivalence or its absence.
    """
    raw, normalized, instances = defaultdict(list), defaultdict(list), defaultdict(list)
    for record in references:
        path = EXAMPLE / record["path"]
        data = path.read_bytes()
        raw[hashlib.sha256(data).hexdigest()].append(record["path"])
        text = data.decode("utf-8-sig", errors="replace")
        # Remove comments while preserving quoted strings containing '--'.
        tokens = re.findall(r'"(?:""|[^"])*"|--[^\r\n]*|[^\s]+', text)
        norm = " ".join(t for t in tokens if not t.startswith("--"))
        normalized[hashlib.sha256(norm.encode()).hexdigest()].append(record["path"])

    def without_hrefs(value):
        if isinstance(value, dict):
            return {k: without_hrefs(v) for k, v in value.items() if k != "href"}
        if isinstance(value, list):
            return [without_hrefs(v) for v in value]
        return value

    for record in artifacts:
        if "/dataset/models/json/" not in record["path"]:
            continue
        value = without_hrefs(read_json(EXAMPLE / record["path"]))
        key = hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        instances[key].append(record["path"])

    def groups(values):
        return [sorted(paths) for paths in values.values() if len(paths) > 1 and any(p.startswith(PREFIX) for p in paths)]

    return {"scope": "All indexed AADL sources versus existing references; selected A3D JSON instances only.",
            "source_exact_groups": groups(raw), "source_text_candidate_groups": groups(normalized),
            "instance_href_ignored_candidate_groups": groups(instances),
            "semantic_near_duplicate_review": "not_completed", "independent_benchmark_cases": "not_established"}


def build_manifest(manifest: dict, receipts: dict, previous_snapshot: dict) -> dict:
    result = deepcopy(manifest)
    if receipts.get("sources_only"):
        raise ValueError("Run the full importer before indexing A3D instances")
    artifacts = verified_artifacts(receipts["artifacts"])
    by_path = {item["path"]: item for item in artifacts}
    matches = receipts["matches"]
    case_ids = {c["id"] for c in CASES}
    if any(m["case_id"] not in case_ids for m in matches):
        raise ValueError("Import receipt contains an unknown case")
    # Idempotent replacement is restricted to this import's namespace.
    for key in ("references", "source_artifacts"):
        result[key] = [r for r in result.get(key, []) if not r["path"].startswith(PREFIX)]
    result["public_sources"] = [r for r in result.get("public_sources", []) if r.get("family") not in case_ids]
    result["source_artifacts"].extend(artifacts)

    for artifact in artifacts:
        if not artifact["path"].endswith(".aadl"):
            continue
        owners = [c["id"] for c in CASES if artifact["path"].startswith(PREFIX + c["project"] + "/")]
        shared = not owners
        result["references"].append({**artifact, "family": owners[0] if owners else "a3d_aadlib_shared",
            "case_ids": owners, "variant_group": "public/a3d/" + (owners[0] if owners else "aadlib_shared"),
            "requirement_ids": [], "role": "reference_dependency" if shared else "architecture_reference",
            "is_gold_reference": False})

    for case in CASES:
        linked = [m for m in matches if m["case_id"] == case["id"]]
        if not linked:
            raise ValueError(f"No A3D instance matched {case['id']}")
        validation_path = EXAMPLE / PREFIX / "validation" / (case["id"] + ".json")
        validation = read_json(validation_path) if validation_path.exists() else None
        summary = {"status": "not_run", "is_gold_reference": False}
        if validation:
            if validation.get("case_id") != case["id"] or not validation.get("original_sources_unchanged"):
                raise ValueError(f"Invalid validation identity or altered source: {case['id']}")
            environment = validation_path.parent / validation["environment_file"]
            if environment.parent.resolve() != validation_path.parent.resolve() or digest(environment) != validation["environment_sha256"]:
                raise ValueError(f"Validation environment snapshot has changed: {case['id']}")
            # A cached check only describes the exact selected bytes. Do not
            # silently reuse it after a source snapshot or selection changes.
            checked = set()
            for source in validation.get("sources", []):
                relative = PREFIX + "sources/" + source["path"].replace("\\", "/")
                artifact = by_path.get(relative)
                if artifact is None or artifact["sha256"] != source["sha256"]:
                    raise ValueError(f"Validation input differs from imported source: {relative}")
                checked.add(relative)
            excluded = {PREFIX + "sources/" + item["path"].replace("\\", "/")
                        for item in validation.get("selection", {}).get("excluded_builtin_copies", [])}
            primary = {p for p in by_path if p.startswith(PREFIX + case["project"] + "/") and p.endswith(".aadl")}
            if not checked or not primary.issubset(checked | excluded):
                raise ValueError(f"Validation omits preserved project files: {case['id']}")
            core = validation.get("core", {})
            instances = [{"root": r["root"], "status": r["status"]} for r in validation.get("roots", [])]
            if not set(case["roots"]).issubset({r["root"] for r in instances}):
                raise ValueError(f"Validation omits a requested root: {case['id']}")
            core_status = core.get("status", "not_run")
            # 'passed' means both core checks and every requested root passed;
            # annex proof status remains separate even when this is successful.
            overall = core_status if core_status != "passed" else (
                "passed" if all(r["status"] == "passed" for r in instances) else "incomplete_or_failed")
            summary.update({"path": validation_path.relative_to(EXAMPLE).as_posix(), "sha256": digest(validation_path),
                            "status": overall, "core_status": core_status, "validator": "osate-cli",
                            "version": core.get("version"),
                            "errors": sum(d.get("severity") == "error" for d in core.get("diagnostics", [])),
                            "warnings": sum(d.get("severity") == "warning" for d in core.get("diagnostics", [])),
                            "instances": instances, "annexes": validation.get("annexes", []),
                            "proof_status": validation.get("proof", {}).get("status", "not_run"),
                            "environment_path": environment.relative_to(EXAMPLE).as_posix(),
                            "environment_sha256": validation["environment_sha256"],
                            "validator_script_sha256": validation["validator_script_sha256"],
                            "scope": "See per-case validation artifact; annex proofs are separate from core checks."})
        result["public_sources"].append({
            "family": case["id"], "title": case["title"], "repository": case["repository"],
            "source_url": f"https://github.com/{case['repository']}/tree/{case['source_ref']}/{case['source_path']}".rstrip("/"),
            "source_ref": case["source_ref"], "reference_project": PREFIX + case["project"],
            "reference_projects": [PREFIX + case["project"]], "root_classifiers": case["roots"],
            "reference_validation": summary, "is_gold_reference": False,
            "requirements_coverage": "not_established", "license_notice": case["license_notice"],
            "architectural_value": case["value"], "known_limitations": case["limitations"],
            "a3d_model_ids": sorted(m["model_id"] for m in linked),
            "a3d_source_matches": linked,
            "source_relation": "Recovered current-upstream project snapshot extending the linked A3D instance scope; original mining commit unknown.",
            "a3d_original_source_commit": None,
            "shared_dependency_roots": [PREFIX + "sources/aadlib/src/aadl", PREFIX + "sources/aadlib/src/property_set"] if case["repository"] == "OpenAADL/AADLib" else [],
        })

    counts = result["counts"]
    counts["references"] = len(result["references"])
    counts["references_without_requirements"] = sum(not r.get("requirement_ids") for r in result["references"])
    counts["public_source_artifacts"] = len(result["source_artifacts"])
    # Existing requirement-linked core/annex counts retain their original scope.
    counts["a3d_cases"] = len(CASES)
    counts["a3d_instances"] = len({m["model_id"] for m in matches})
    counts["a3d_architecture_reference_aadl"] = sum(r.get("role") == "architecture_reference" for r in result["references"])
    counts["a3d_shared_dependency_aadl"] = sum(r.get("role") == "reference_dependency" for r in result["references"])
    counts["a3d_source_artifacts"] = len(artifacts)
    result["a3d"] = {
        "repository": "dinhtranthi/aadl-architectural-models-dataset", "source_ref": DATASET_REF,
        "source_url": f"https://github.com/dinhtranthi/aadl-architectural-models-dataset/tree/{DATASET_REF}",
        "doi": "10.5281/zenodo.19819886", "snapshot": PREFIX + "dataset",
        "case_ids": [c["id"] for c in CASES], "deferred": DEFERRED,
        "selection_basis": "Instance classifier href plus upstream declarations; CSV source path retained even when incorrect.",
        "requirement_tasks_added": 0, "rag_enabled": False,
        "previous_manifest": previous_snapshot,
        "validation_artifacts": [{"path": path.relative_to(EXAMPLE).as_posix(), "sha256": digest(path),
                                  "size_bytes": path.stat().st_size}
                                 for path in sorted((EXAMPLE / PREFIX / "validation").rglob("*"))
                                 if path.is_file() and path.suffix in {".json", ".log", ".aaxl2"}],
        "duplicates": duplicate_observations(result["references"], artifacts),
    }
    # Derived examples remain a separate collection. Refreshing upstream records
    # must keep their provenance links without adding evaluation requirements.
    if "requirement_examples" in manifest.get("a3d", {}):
        result["a3d"]["requirement_examples"] = deepcopy(manifest["a3d"]["requirement_examples"])
    return integrate_derived_requirements(result)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify bytes and require the manifest to already match; do not write.")
    # Import receipts describe preserved source bytes; they are not disposable
    # download caches. A fresh checkout can run --check without recreating .cache.
    parser.add_argument("--records", type=Path, default=EXAMPLE / PREFIX / "import-records.json")
    args = parser.parse_args()
    manifest_path = EXAMPLE / "reference/manifest.json"
    before = read_json(manifest_path)
    previous_snapshot = before.get("a3d", {}).get("previous_manifest")
    if previous_snapshot is None:
        raw_manifest = manifest_path.read_bytes()
        old_hash = hashlib.sha256(raw_manifest).hexdigest()
        # Frozen experiments hash the whole manifest and resolve paths from its
        # parent.parent. Preserve the original beside it, not in a deeper folder.
        snapshot = manifest_path.with_name(f"manifest.pre-a3d-{old_hash[:12]}.json")
        previous_snapshot = {"path": snapshot.relative_to(EXAMPLE).as_posix(), "sha256": old_hash}
        if not args.check:
            if snapshot.exists() and snapshot.read_bytes() != raw_manifest:
                raise ValueError("Existing pre-A3D manifest snapshot has different bytes")
            if not snapshot.exists():
                snapshot.write_bytes(raw_manifest)
    elif digest(EXAMPLE / previous_snapshot["path"]) != previous_snapshot["sha256"]:
        raise ValueError("The frozen pre-A3D manifest snapshot has changed")
    after = build_manifest(before, read_json(args.records), previous_snapshot)
    if args.check:
        if before != after:
            raise SystemExit("A3D manifest is stale; run this script without --check.")
    elif before != after:
        # Atomic replacement prevents a partial write while preserving all non-A3D records.
        temp = manifest_path.with_suffix(".json.tmp")
        temp.write_text(json.dumps(after, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        temp.replace(manifest_path)
    print(json.dumps({"cases": after["counts"]["a3d_cases"], "instances": after["counts"]["a3d_instances"],
                      "source_artifacts": after["counts"]["a3d_source_artifacts"], "requirements_added": 0}))


if __name__ == "__main__":
    main()
