"""Validate preserved A3D reference projects without rewriting upstream sources.

The staged selection is a whole example plus its declaration-based dependencies.
OSATE, rather than this dependency selector, decides language correctness. Annex
plugin availability and proof status are reported separately from core checks.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import uuid
from zipfile import ZipFile
import xml.etree.ElementTree as ET

PROJECT_ROOT = Path(__file__).resolve().parents[1]
VALIDATOR_SCRIPT_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
sys.path.insert(0, str(PROJECT_ROOT / "src"))
from aadl_single_agent.osate import OsateRunner  # noqa: E402
from aadl_single_agent.config import Config  # noqa: E402

CASE_SPECS = {
    "a3d_aps": ("aps", "aps", ["Overall::APSCommunicationArchitecture.impl"]),
    "a3d_qfcs_v2": ("smaccm/models/QFCS_V2", "smaccm/models/QFCS_V2", [
        "FCS::FCS.impl", "ISAS_RC_Dual_Channel_Inputs::ISAS_RC_Dual_Channel_Inputs.impl"]),
    "a3d_smaccm_phase2": ("smaccm/models/SmaccmPhase2", "smaccm/models/SmaccmPhase2", [
        "UAS::UAS.Impl", "Mission_Software::Software.Impl"]),
    "a3d_paparazzi": ("aadlib/examples/paparazzi", "aadlib", [
        "paparazzi_system::paparazzi.basic_archi",
        "paparazzi_system::paparazzi.PnP_tasks_interruptions",
        "paparazzi_system::paparazzi.PP_tasks_interruptions",
        "paparazzi_system::paparazzi.PP_tasks_AP_interruptions"]),
    "a3d_adiru": ("aadlib/examples/adiru", "aadlib", [
        "ADIRU::ADIRU.impl", "ADIRU::ADIRU.DeOS", "ADIRU::ADIRU.VxWorks653", "ADIRU::ADIRU.EMV2"]),
    "a3d_aocs": ("aadlib/examples/aocs", "aadlib", ["AOCS::AOCS_Subsystem.impl"]),
    "a3d_fms": ("aadlib/examples/fms", "aadlib", ["fms::fms.impl"]),
}

IDENT = r"[A-Za-z][A-Za-z0-9_]*"
QUALIFIED = rf"{IDENT}(?:::{IDENT})*"
DECLARATIONS = re.compile(rf"^\s*(?:package\s+({QUALIFIED})|property\s+set\s+({IDENT})\s+is)\b", re.I | re.M)
ANNEXES = re.compile(rf"\bannex\s+({IDENT})\s*\{{\*\*", re.I)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def lexical_text(text: str) -> str:
    """Mask comments and strings before inspecting declarations, retaining lines."""
    return re.sub(r'--[^\r\n]*|"(?:""|[^"])*"',
                  lambda match: re.sub(r"[^\r\n]", " ", match.group()), text)


def read_source(path: Path) -> dict:
    data = path.read_bytes()
    # Encoding fallback is for discovery only: staging always copies exact bytes.
    try:
        text = data.decode("utf-8-sig")
        encoding = "utf-8-sig"
    except UnicodeDecodeError:
        text = data.decode("cp1252")
        encoding = "cp1252"
    code = lexical_text(text)
    return {"path": path, "sha256": sha256(data), "size": len(data), "encoding": encoding,
            "declarations": [next(value for value in match.groups() if value).casefold()
                             for match in DECLARATIONS.finditer(code)],
            "annexes": sorted({m.group(1).casefold() for m in ANNEXES.finditer(code)}),
            "code": code}


def inspect_distribution(executable: Path) -> tuple[dict, dict]:
    """Inspect actual jar contributions, including named annex parser registrations.

    A wildcard/default annex parser preserves unknown bodies but cannot establish
    their semantics. In particular, a jar with 'resolute' in its filename alone
    does not establish that the Resolute annex parser is installed.
    """
    package = executable.parent.parent
    resources = {}
    plugins, annex_parsers = [], []
    for jar in sorted((package / "lib").glob("*.jar")):
        plugins.append({"file": jar.name, "sha256": sha256(jar.read_bytes())})
        with ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith("resources/") and name.lower().endswith(".aadl"):
                    raw = archive.read(name)
                    for match in DECLARATIONS.finditer(lexical_text(raw.decode("utf-8-sig"))):
                        declaration = next(v for v in match.groups() if v).casefold()
                        resources[declaration] = {"declaration": declaration, "jar": jar.name,
                                                  "resource": name, "sha256": sha256(raw)}
            if "plugin.xml" not in archive.namelist():
                continue
            root = ET.fromstring(archive.read("plugin.xml"))
            for extension in root.findall("extension"):
                if extension.get("point") == "org.osate.annexsupport.parser":
                    for parser in extension.findall("parser"):
                        annex_parsers.append({"name": parser.get("annexName"), "jar": jar.name,
                                              "class": parser.get("class")})
    return {"package": str(package), "plugins": plugins, "annex_parser_registrations": annex_parsers,
            "contributed_library_resources": list(resources.values())}, resources


def dependency_names(source: dict, known: set[str]) -> set[str]:
    code = source["code"]
    # Imported namespace identity comes from the AADL declarations, never from
    # filenames (for example, QFCS's CCDL.aadl declares package CLAW).
    dependencies = set()
    for match in re.finditer(r"\bwith\s+([^;]+);", code, re.I):
        for name in match.group(1).split(","):
            compact = re.sub(r"\s+", "", name).casefold()
            if re.fullmatch(QUALIFIED, compact, re.I):
                dependencies.add(compact)
    for match in re.finditer(rf"\b({IDENT}(?:::{IDENT})+)\b", code):
        token = match.group(1).casefold()
        if token in known:
            # 'with Buses::CAN' names a complete package, not a classifier in
            # a missing package Buses. Nested package names are atomic here.
            dependencies.add(token)
            continue
        parts = token.split("::")
        candidates = ["::".join(parts[:i]) for i in range(len(parts) - 1, 0, -1)]
        dependencies.add(next((name for name in candidates if name in known), candidates[-1]))
    # Older AGREE libraries use Package.node syntax without AADL 'with'. Only
    # known declared packages are added; record/port field names are not guessed.
    for match in re.finditer(rf"\b({IDENT})\s*\.\s*{IDENT}", code):
        if match.group(1).casefold() in known:
            dependencies.add(match.group(1).casefold())
    return dependencies - set(source["declarations"])


def select_sources(sources: Path, case_id: str, builtins: dict) -> tuple[list[dict], dict]:
    relative, pool_relative, _ = CASE_SPECS[case_id]
    primary = sources / relative
    pool_root = sources / pool_relative
    if not primary.is_dir():
        raise FileNotFoundError(f"Reference source directory is missing: {primary}")
    # AADLib examples may use shared libraries, but must not import a different
    # example merely because it declares a coincidentally matching name.
    pool_paths = set(primary.rglob("*.aadl"))
    if pool_relative == "aadlib":
        for library in (pool_root / "src" / "aadl", pool_root / "src" / "property_set"):
            pool_paths.update(library.rglob("*.aadl"))
    records = [read_source(path) for path in sorted(pool_paths)]
    by_name = defaultdict(list)
    excluded, selection_issues = [], []
    eligible = []
    for source in records:
        overlaps = set(source["declarations"]) & set(builtins)
        if overlaps:
            # A historical Plugin_Resources copy must not shadow the same OSATE
            # library in the running distribution. Keep it in sources, not stage.
            if set(source["declarations"]) - set(builtins):
                selection_issues.append({"reason": "mixed_builtin_and_custom_declarations",
                                         "path": str(source["path"].relative_to(sources))})
            excluded.append({"path": str(source["path"].relative_to(sources)),
                             "sha256": source["sha256"], "reason": "provided_by_osate_distribution",
                             "declarations": sorted(overlaps)})
            continue
        eligible.append(source)
        for name in source["declarations"]:
            by_name[name].append(source)
    selected = {record["path"]: record for record in eligible
                if record["path"].is_relative_to(primary)
                and "plugin_resources" not in [part.casefold() for part in record["path"].parts]}
    queue = list(selected.values())
    missing = set()
    used_builtin = set()
    known = set(by_name) | set(builtins)
    while queue:
        record = queue.pop()
        for dependency in sorted(dependency_names(record, known)):
            if dependency in builtins:
                used_builtin.add(dependency)
                continue
            candidates = by_name.get(dependency, [])
            if not candidates:
                missing.add(dependency)
                continue
            if len(candidates) != 1:
                selection_issues.append({"reason": "ambiguous_declaration", "declaration": dependency,
                                         "paths": [str(s["path"].relative_to(sources)) for s in candidates]})
                continue
            candidate = candidates[0]
            if candidate["path"] not in selected:
                selected[candidate["path"]] = candidate
                queue.append(candidate)
    return sorted(selected.values(), key=lambda r: r["path"]), {
        "primary_directory": relative, "dependency_pool": pool_relative,
        "preserved_primary_aadl_count": sum(path.is_relative_to(primary) for path in pool_paths),
        "selected_count": len(selected), "excluded_builtin_copies": excluded,
        "static_unresolved_names": sorted(missing), "selection_issues": selection_issues,
        "builtin_dependencies": [builtins[name] for name in sorted(used_builtin)],
        "note": "Static namespace closure selects files; OSATE diagnostics are authoritative for linking."}


def write_json(path: Path, payload: dict) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    content = (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    temporary.write_bytes(content)
    temporary.replace(path)
    return sha256(content)


def annotate_diagnostics(result: dict, source_records: list[dict], sources: Path) -> None:
    for diagnostic in result.get("diagnostics", []):
        path = (diagnostic.get("path") or "").replace("\\", "/")
        for record in source_records:
            relative = record["path"].relative_to(sources).as_posix()
            if (path.endswith("/" + relative) or path == relative
                    or path.endswith("/" + record.get("staged_path", "__not_staged__"))):
                diagnostic["original_source"] = relative
                break


def validate_case(case_id: str, args, runner: OsateRunner, distribution: dict, builtins: dict) -> dict:
    _, _, roots = CASE_SPECS[case_id]
    case = {"case_id": case_id, "validated_at_utc": datetime.now(timezone.utc).isoformat(),
            "validator_script_sha256": VALIDATOR_SCRIPT_SHA256,
            "environment_sha256": args.environment_sha256,
            "environment_file": args.environment_file,
            "roots": [], "proof": {"status": "not_run", "reason": "No annex theorem prover was invoked."}}
    records, closure = select_sources(args.sources, case_id, builtins)
    case["selection"] = closure
    case["sources"] = [{k: (str(v.relative_to(args.sources)) if k == "path" else v)
                         for k, v in record.items() if k != "code"} for record in records]
    parser_names = {str(p["name"]).casefold() for p in distribution["annex_parser_registrations"]}
    annex_names = sorted({name for record in records for name in record["annexes"]})
    case["annexes"] = [{"name": name,
                         "parser_status": "registered_in_distribution" if name in parser_names else "unsupported",
                         "semantic_validation": "not_run" if name not in parser_names else "subject_to_osate_diagnostics",
                         "proof_status": "not_run"} for name in annex_names]
    case["interpretation"] = (
        "Core syntax/linking and requested instantiation only. Unsupported annex bodies remain unchanged "
        "and may be accepted by the default parser; acceptance is not annex validation or proof.")
    # Short cache and staged filenames avoid Windows MAX_PATH failures for
    # generated instance filenames; the original project paths remain recorded.
    stage = args.cache / uuid.uuid4().hex[:8] / "s"
    stage.mkdir(parents=True)
    for index, record in enumerate(records):
        record["staged_path"] = f"m{index:03d}.aadl"
        case["sources"][index]["staged_path"] = record["staged_path"]
        target = stage / record["staged_path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(record["path"], target)
        if sha256(target.read_bytes()) != record["sha256"]:
            raise RuntimeError(f"Staging changed source bytes: {record['path']}")
    case["staging"] = {"directory": str(stage), "source_bytes_preserved": True}
    # First check the same full project without instantiation. A failed core
    # check is an explicit prerequisite failure for every requested root.
    print(f"{case_id}: checking {len(records)} source files", flush=True)
    core = runner.check(stage, stage.parent / "r")
    annotate_diagnostics(core, records, args.sources)
    case["core"] = core
    for index, root in enumerate(roots):
        role = "primary_architecture" if index == 0 else "additional_entry_or_variant"
        if core["status"] != "passed":
            case["roots"].append({"root": root, "role": role, "status": "not_run",
                                  "reason": "Core syntax/linking check did not pass.",
                                  "prerequisite_status": core["status"]})
            continue
        print(f"{case_id}: instantiating {root}", flush=True)
        result = runner.check(stage, stage.parent / "r", instance_root=root)
        # The shared runner verifies both official XML representations; this
        # script never turns an adapter failure into a reference-model pass.
        annotate_diagnostics(result, records, args.sources)
        instance = result.get("instance", {"status": "not_run", "root": root})
        # The runner can discover a cleanup error after the instance was created.
        # Therefore a successful artifact alone must not mask an execution error.
        status = ("passed" if result["status"] == instance["status"] == "passed"
                  else result["status"] if result["status"] in {"failed", "error"}
                  else instance["status"])
        case["roots"].append({"root": root, "role": role, "status": status,
                              "instance_status": instance["status"], "result": result})
    # Hash again after all runs, proving that validation did not alter originals.
    case["original_sources_unchanged"] = all(sha256(r["path"].read_bytes()) == r["sha256"] for r in records)
    write_json(args.output / f"{case_id}.json", case)
    (args.output / f"{case_id}.log").write_text(
        "CORE\n" + core.get("stdout", "") + "\n" + "\n".join(
            item["root"] + "\n" + item.get("result", {}).get("stdout", "") for item in case["roots"]),
        encoding="utf-8")
    print(f"{case_id}: core={core['status']}; roots=" + ", ".join(r["status"] for r in case["roots"]), flush=True)
    return case


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    base = PROJECT_ROOT / "example" / "reference" / "public" / "a3d"
    parser.add_argument("--sources", type=Path, default=base / "sources")
    parser.add_argument("--output", type=Path, default=base / "validation")
    parser.add_argument("--cache", type=Path, default=PROJECT_ROOT / ".cache" / "a3d" / "validation")
    parser.add_argument("--config", type=Path, default=PROJECT_ROOT / "config.yaml",
                        help="Shared client configuration; resolves the default OSATE path")
    parser.add_argument("--osate-cli", type=Path, help="Explicit OSATE launcher override; otherwise use --config")
    parser.add_argument("--case", action="append", choices=CASE_SPECS)
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
    # An explicit override is usable without loading an unrelated model URL;
    # otherwise use the same path resolution as generate/check.
    args.osate_cli = (args.osate_cli.expanduser().resolve() if args.osate_cli is not None
                      else Config.load(args.config).osate_cli)
    args.sources, args.output, args.cache = args.sources.resolve(), args.output.resolve(), args.cache.resolve()
    distribution, builtins = inspect_distribution(args.osate_cli.resolve())
    runner = OsateRunner(args.osate_cli, timeout_seconds=args.timeout)
    distribution["probe"] = runner.probe()
    args.environment_sha256 = write_json(args.output / "environment.json", distribution)
    args.environment_file = f"environment-{args.environment_sha256[:12]}.json"
    write_json(args.output / args.environment_file, distribution)
    cases = [validate_case(case_id, args, runner, distribution, builtins) for case_id in (args.case or CASE_SPECS)]
    # Preserve independent case files when running a diagnostic subset; aggregate
    # only current on-disk results and expose their individual timestamps/hashes.
    aggregated = [json.loads((args.output / f"{case_id}.json").read_text(encoding="utf-8"))
                  for case_id in CASE_SPECS if (args.output / f"{case_id}.json").is_file()]
    write_json(args.output / "validation.json", {"schema_version": 1, "environment_file": "environment.json", "cases": aggregated})
    return 0 if all(case["original_sources_unchanged"] for case in cases) else 1


if __name__ == "__main__":
    raise SystemExit(main())
