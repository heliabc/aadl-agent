"""Import the seven approved A3D reference projects without executing their code.

All upstream revisions are immutable commits. Files are copied as bytes and checked
against Git blob IDs, so line endings, annexes and historical errors remain intact.
The durable import receipt is provenance input to the project manifest. Downloads
remain disposable caches; deleting them must not break offline index checks.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import csv
from hashlib import sha1, sha256
from http.client import HTTPException
import io
import json
from pathlib import Path, PurePosixPath
import re
import time
from threading import Lock
from urllib.parse import quote, unquote
from urllib.request import Request, urlopen


PROJECT = Path(__file__).resolve().parents[1]
EXAMPLE = PROJECT / "example"
DESTINATION = EXAMPLE / "reference" / "public" / "a3d"
CACHE = PROJECT / ".cache" / "a3d"
FILE_LOCK = Lock()
REPOSITORIES = {
    "aps": ("ahmed-amro/APS-Communication_Architecture", "2cb6cfc34b2b7c5c0fc5b76b7a5ab0822e60368e"),
    "smaccm": ("smaccm/smaccm", "7393c423b5aca1a272a88cbb89b5dcfd08a5a47c"),
    "aadlib": ("OpenAADL/AADLib", "c8a5c7e310b37761bb5ecc7b5ff3fc6cbd42716a"),
    "dataset": ("dinhtranthi/aadl-architectural-models-dataset", "a57fbf6d21cfdcd1bd16443e73f69f8785c48d94"),
}
CASES = {
    "a3d_aps": ("aps", ""),
    "a3d_qfcs_v2": ("smaccm", "models/QFCS_V2"),
    "a3d_smaccm_phase2": ("smaccm", "models/SmaccmPhase2"),
    **{f"a3d_{name}": ("aadlib", f"examples/{name}")
       for name in ("paparazzi", "adiru", "aocs", "fms")},
}


def safe_path(base: Path, relative: str) -> Path:
    """Reject archive-style traversal, Windows drive syntax, and existing links."""
    path = PurePosixPath(relative)
    if not relative or path.is_absolute() or "\\" in relative or ":" in relative:
        raise ValueError(f"Unsafe relative path: {relative!r}")
    if any(piece in {"", ".", ".."} for piece in relative.split("/")):
        raise ValueError(f"Unsafe path component: {relative!r}")
    # Check the lexical base before resolving it: otherwise a link used as the
    # destination directory would disappear from the later ancestor check.
    for candidate in [base, *base.parents]:
        if candidate.is_symlink() or (hasattr(candidate, "is_junction") and candidate.is_junction()):
            raise ValueError(f"Refusing linked destination: {candidate}")
    base = base.resolve()
    target = base.joinpath(*path.parts)
    if not target.resolve().is_relative_to(base):
        raise ValueError(f"Path escapes destination: {relative!r}")
    for candidate in [target, *target.parents]:
        if candidate.is_symlink() or (hasattr(candidate, "is_junction") and candidate.is_junction()):
            raise ValueError(f"Refusing symlink: {candidate}")
        if candidate == base:
            break
    return target


def put_identical(path: Path, data: bytes) -> None:
    """A rerun may reuse identical files, but never overwrite user edits."""
    # Identical Git blobs may occur in several projects. Serializing the short
    # cache write prevents one worker from observing another's partial file.
    with FILE_LOCK:
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            if path.read_bytes() != data:
                raise ValueError(f"Existing file has different bytes: {path}")
            return
        with path.open("xb") as stream:
            stream.write(data)


def fetch(url: str) -> bytes:
    # No credentials are collected or emitted. Only fixed public GitHub inputs
    # are fetched; downloaded Python, shell, Makefile and C sources never run.
    for attempt in range(4):
        try:
            request = Request(url, headers={"User-Agent": "aadl-reference-curation/1"})
            with urlopen(request, timeout=90) as response:
                return response.read()
        except (OSError, TimeoutError, HTTPException):
            if attempt == 3:
                raise
            time.sleep(1 + attempt)
    raise AssertionError("unreachable")


def tree(repo_key: str) -> dict[str, dict]:
    repo, ref = REPOSITORIES[repo_key]
    cache = safe_path(CACHE, f"trees/{repo_key}-{ref}.json")
    if not cache.exists():
        put_identical(cache, fetch(f"https://api.github.com/repos/{repo}/git/trees/{ref}?recursive=1"))
    payload = json.loads(cache.read_bytes())
    if payload.get("truncated") or not isinstance(payload.get("tree"), list):
        raise ValueError(f"Incomplete Git tree for {repo}")
    result = {}
    for entry in payload["tree"]:
        if entry["type"] != "blob":
            continue
        # Git symlinks are blobs too; importing their bytes as ordinary files
        # would silently change semantics, so they are not accepted.
        safe_path(CACHE, entry["path"])
        result[entry["path"]] = entry
    return result


def blob(repo_key: str, entry: dict) -> bytes:
    if entry.get("mode") not in {"100644", "100755"}:
        raise ValueError(f"Unsupported Git entry mode: {entry['path']} ({entry.get('mode')})")
    repo, ref = REPOSITORIES[repo_key]
    cached = safe_path(CACHE, "blobs/" + entry["sha"])
    with FILE_LOCK:
        data = cached.read_bytes() if cached.exists() else None
    if data is None:
        data = fetch(f"https://raw.githubusercontent.com/{repo}/{ref}/{quote(entry['path'], safe='/')}")
    actual = sha1(f"blob {len(data)}\0".encode("ascii") + data).hexdigest()
    if actual != entry["sha"] or len(data) != entry["size"]:
        raise ValueError(f"Git blob mismatch: {repo}:{entry['path']}")
    put_identical(cached, data)
    return data


def source_selected(repo_key: str, path: str) -> bool:
    if repo_key == "aps":
        # The root is the architecture project. RPNMI and TRA are independent
        # research material and intentionally outside this approved extraction.
        return "/" not in path and path != ".gitignore"
    if path in {"README.md", "README", "LICENSE", "LICENSE.txt"}:
        return True
    if repo_key == "smaccm":
        return path.startswith(("models/QFCS_V2/", "models/SmaccmPhase2/"))
    # Keep project configuration and build explanations without claiming that
    # this selected subset is a complete checkout for the repository's build.
    # Every selected example's Makefile includes the shared Makefile.common.
    return path in {".project", ".aadlsettings", "AUTHORS", "INSTALL", "NEWS",
                    "Makefile.am", "configure.ac", "examples/README",
                    "examples/Makefile.common"} or path.startswith((
        "examples/paparazzi/", "examples/adiru/", "examples/aocs/", "examples/fms/",
        "src/aadl/", "src/property_set/"))


def copy_blob(repo_key: str, entry: dict) -> dict:
    base = DESTINATION / ("dataset" if repo_key == "dataset" else f"sources/{repo_key}")
    target = safe_path(base, entry["path"])
    data = blob(repo_key, entry)
    put_identical(target, data)
    repo, ref = REPOSITORIES[repo_key]
    return {
        "path": target.relative_to(EXAMPLE).as_posix(),
        "source_path": entry["path"], "source_repository": repo,
        "source_ref": ref,
        "source_url": f"https://github.com/{repo}/blob/{ref}/{quote(entry['path'], safe='/')}",
        "sha256": sha256(data).hexdigest(), "git_blob_sha1": entry["sha"],
        "git_mode": entry["mode"], "size_bytes": len(data),
    }


def resolve_root(payload: dict) -> tuple[str, str] | None:
    # CSV paths can identify a different same-named classifier. Use only the
    # instance root, never a nested dependency's classifier, to assign a case.
    href = None
    for key in ("componentImplementation", "classifier"):
        value = payload.get(key)
        if isinstance(value, dict) and isinstance(value.get("href"), str):
            href = value["href"]
            break
    if not href:
        return None
    before_fragment = unquote(href.split("#", 1)[0]).replace("\\", "/")
    match = re.search(r"(?:^|/)github/([^/]+)/(.+)$", before_fragment)
    if not match:
        return None
    repo_name, source_path = match.groups()
    if ".." in PurePosixPath(source_path).parts:
        raise ValueError(f"Unsafe source href: {href}")
    for case_id, (repo_key, prefix) in CASES.items():
        if REPOSITORIES[repo_key][0].split("/", 1)[1].lower() != repo_name.lower():
            continue
        if source_path.startswith(prefix + "/") if prefix else "/" not in source_path:
            return case_id, href
    return None


def inspect_model(entry: dict, rows: dict[str, dict], source_trees: dict) -> dict | None:
    payload = json.loads(blob("dataset", entry))
    resolved = resolve_root(payload)
    if not resolved:
        return None
    case_id, href = resolved
    repo_key, _ = CASES[case_id]
    source_path = unquote(href.split("#", 1)[0]).replace("\\", "/").split(
        "/github/", 1)[1].split("/", 1)[1]
    if source_path not in source_trees[repo_key] or not source_selected(repo_key, source_path):
        raise ValueError(f"Instance source is absent from pinned extraction: {href}")
    model_id = PurePosixPath(entry["path"]).stem + ".aaxl2"
    if model_id not in rows:
        raise ValueError(f"Instance lacks metadata row: {model_id}")
    source_bytes = blob(repo_key, source_trees[repo_key][source_path])
    declaration = verify_root_declaration(source_bytes, href)
    implementation_href = (payload.get("componentImplementation") or {}).get("href")
    classifier_href = (payload.get("classifier") or {}).get("href")
    hrefs_agree = (implementation_href == classifier_href
                  if implementation_href is not None and classifier_href is not None else None)
    return {"model_id": model_id, "case_id": case_id,
            "original_csv_row": rows[model_id], "instance_source_href": href,
            "resolved_source_path": source_path,
            "source_repository": REPOSITORIES[repo_key][0],
            "source_ref": REPOSITORIES[repo_key][1],
            **declaration,
            "component_implementation_href": implementation_href,
            "classifier_href": classifier_href,
            "root_hrefs_agree": hrefs_agree,
            "source_match_level": ("root_href_disagreement" if hrefs_agree is False else
                                   "path_and_declaration_verified_current_snapshot"
                                   if declaration["source_declaration_verified"] else
                                   "path_verified_declaration_unresolved_current_snapshot"),
            "source_match_scope": "Pinned current upstream snapshot; original A3D collection commit is unknown.",
            "json_source_path": entry["path"],
            "xmi_source_path": f"dataset/models/xmi/{model_id}"}


def verify_root_declaration(source: bytes, href: str) -> dict:
    """Confirm a named root in source, without treating instantiation as tested.

    Strip comments and string literals together: a '--' inside a property string
    must not hide later declarations, and tutorial text is not a declaration.
    """
    fragment = unquote(href.partition("#")[2])
    result = {"resolved_root_classifier": None, "source_declaration_verified": False}
    try:
        text = source.decode("utf-8-sig")
    except UnicodeDecodeError:
        return {**result, "source_declaration_note": "Source is not UTF-8; no text decoding guessed."}
    clean = re.sub(r'"(?:[^"\\]|\\.|"")*"|--[^\r\n]*',
                   lambda match: "\n" * match.group().count("\n"), text)
    package = re.search(r"(?im)^\s*package\s+([A-Za-z][\w]*(?:::[A-Za-z][\w]*)*)\b", clean)
    if not package:
        return {**result, "source_declaration_note": "No package declaration found."}
    implementations = re.findall(
        r"(?im)^\s*system\s+implementation\s+([A-Za-z]\w*\.[A-Za-z]\w*)\b", clean)
    for implementation in implementations:
        if fragment.casefold() == f"{package[1]}.{implementation}".casefold():
            return {"resolved_root_classifier": f"{package[1]}::{implementation}",
                    "source_declaration_verified": True,
                    "source_declaration_note": "Named system implementation found in the pinned source; no semantic or instance validation implied."}
    return {**result, "source_declaration_note":
            f"Root fragment {fragment!r} does not match a named system implementation in package {package[1]!r}."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--sources-only", action="store_true",
                        help="Import upstream projects first so independent validation can start")
    args = parser.parse_args()
    if not 1 <= args.workers <= 16:
        parser.error("--workers must be between 1 and 16")
    source_trees = {}
    artifacts = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for repo_key in ("aps", "smaccm", "aadlib"):
            source_trees[repo_key] = tree(repo_key)
            selected = [entry for path, entry in source_trees[repo_key].items()
                        if source_selected(repo_key, path)]
            artifacts.extend(pool.map(lambda entry: copy_blob(repo_key, entry), selected))
            print(f"SOURCE_READY {repo_key}: {len(selected)} byte-verified files", flush=True)
        matches = []
        if not args.sources_only:
            dataset_tree = tree("dataset")
            accompanying = [entry for path, entry in dataset_tree.items() if
                path in {"README.md", "LICENSE", "CITATION.cff", "dataset/metadata.csv"}
                or path.startswith(("schema/", "experiment_protocol/"))
                or (path.startswith("dataset/annotations/") and path.endswith(".csv"))]
            artifacts.extend(pool.map(lambda entry: copy_blob("dataset", entry), accompanying))
            metadata = blob("dataset", dataset_tree["dataset/metadata.csv"])
            rows = {row["model_id"]: row for row in csv.DictReader(io.StringIO(metadata.decode("utf-8-sig")))}
            # Prefixes limit network reads only. Final inclusion is decided by
            # root classifier href, not filename, CSV repository URL or counts.
            candidates = [entry for path, entry in dataset_tree.items()
                          if path.startswith("dataset/models/json/") and
                          PurePosixPath(path).name.startswith(("APS-Communication_Architecture_", "smaccm_", "AADLib_"))]
            matches = [match for match in pool.map(
                lambda entry: inspect_model(entry, rows, source_trees), candidates) if match]
            selected_paths = sorted({match[key] for match in matches
                                     for key in ("json_source_path", "xmi_source_path")})
            missing = set(selected_paths) - set(dataset_tree)
            if missing:
                raise ValueError(f"Missing paired dataset files: {sorted(missing)}")
            artifacts.extend(pool.map(lambda path: copy_blob("dataset", dataset_tree[path]), selected_paths))
            if sum(match["case_id"] == "a3d_aps" for match in matches) != 43:
                raise ValueError("Pinned APS selection no longer contains the expected 43 instances")
            if {match["case_id"] for match in matches} != set(CASES):
                raise ValueError("At least one approved project has no matching A3D instance")
    receipt = {"schema_version": 1, "sources_only": args.sources_only,
               "repositories": {key: {"repository": repo, "ref": ref}
                                for key, (repo, ref) in REPOSITORIES.items()},
               "cases": [{"case_id": key, "source_repository": REPOSITORIES[repo_key][0],
                          "source_ref": REPOSITORIES[repo_key][1],
                          "source_project": f"reference/public/a3d/sources/{repo_key}" + (f"/{prefix}" if prefix else "")}
                         for key, (repo_key, prefix) in CASES.items()],
               "artifacts": sorted(artifacts, key=lambda item: item["path"]),
               "matches": sorted(matches, key=lambda item: item["model_id"])}
    # This generated provenance receipt may change from a sources-only run to a full
    # run. Original upstream copies above always retain no-overwrite semantics.
    DESTINATION.mkdir(parents=True, exist_ok=True)
    (DESTINATION / "import-records.json").write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"COMPLETE {len(artifacts)} artifacts; {len(matches)} matched A3D instances; {DESTINATION / 'import-records.json'}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
