"""Unambiguous project transport and explicit dependency snapshots.

Control records never occur inside source lines: every source line has one '|'
prefix removed on extraction. The model cannot choose filesystem paths. Neither
an incomplete container nor a missing source terminator is silently repaired.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
from typing import Iterable, Mapping


PROJECT_HEADER = "AADL-PROJECT 1\n"
PROJECT_GRAMMAR = r'''start: "AADL-PROJECT 1\n" file+ "END_PROJECT\n"
file: "FILE\n" line* "END_FILE\n"
line: "|" /[^\r\n]+/? "\n"
'''
PROJECT_INSTRUCTIONS = (
    "Return one complete project without Markdown. The first line must be exactly AADL-PROJECT 1. "
    "Start each source file with a line containing exactly FILE (no filename). "
    "Prefix EVERY source line, including blank lines, with one extra | character. "
    "After the complete source of a file, write END_FILE on its own line without |. "
    "Repeat FILE, prefixed source lines, END_FILE for additional files. "
    "Finally write END_PROJECT on its own line without | and stop. "
    "Use actual LF newlines, never escaped newline strings or placeholder text. "
    "All required source definitions must be present before END_PROJECT."
)


@dataclass(frozen=True)
class ProjectFile:
    name: str
    text: str
    complete: bool = True


@dataclass(frozen=True)
class ProjectDocument:
    files: tuple[ProjectFile, ...] = ()
    complete: bool = False
    diagnostics: tuple[str, ...] = ()
    started: bool = False

    @property
    def valid(self):
        return self.complete and not self.diagnostics


def parse_project(text: str) -> ProjectDocument:
    """Read both complete transports and prefixes, retaining received source."""
    if not isinstance(text, str):
        raise TypeError("Project transport must be text")
    if not text.startswith(PROJECT_HEADER):
        return ProjectDocument(diagnostics=() if PROJECT_HEADER.startswith(text) else ("Expected AADL-PROJECT 1 header",))
    files, body, errors = [], None, []
    rest = text[len(PROJECT_HEADER):]
    records = rest.splitlines(keepends=True)
    closed = False
    for record in records:
        if closed:
            errors.append("Content follows END_PROJECT")
            break
        if "\r" in record:
            errors.append("Project control and source records must use LF line endings")
            break
        terminated = record.endswith("\n")
        if body is not None:
            if record.startswith("|"):
                body.append(record[1:])
            elif record == "END_FILE\n":
                files.append(ProjectFile(f"model_{len(files)+1:03d}.aadl", "".join(body)))
                body = None
            elif not terminated and "END_FILE\n".startswith(record):
                break
            else:
                errors.append("Expected a '|' source record or END_FILE")
                break
        elif record == "FILE\n":
            body = []
        elif record == "END_PROJECT\n" and files:
            closed = True
        elif not terminated and any(marker.startswith(record) for marker in ("FILE\n", "END_PROJECT\n")):
            break
        else:
            errors.append("Expected FILE, or END_PROJECT after at least one file")
            break
    if body is not None:
        files.append(ProjectFile(f"model_{len(files)+1:03d}.aadl", "".join(body), False))
    return ProjectDocument(tuple(files), closed and not errors, tuple(errors), True)


def serialize_project(files: Mapping[str, str] | Iterable[ProjectFile | str]) -> str:
    """Encode source records; names are deliberately absent from the wire format."""
    values = files.values() if isinstance(files, Mapping) else files
    pieces = [PROJECT_HEADER]
    for value in values:
        text = value if isinstance(value, str) else value.text
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        pieces.append("FILE\n")
        for line in text.splitlines(keepends=True):
            pieces.append("|" + line + ("" if line.endswith("\n") else "\n"))
        pieces.append("END_FILE\n")
    pieces.append("END_PROJECT\n")
    return "".join(pieces)


def read_project(path: str | Path) -> ProjectDocument:
    source = Path(path).resolve()
    paths = sorted(source.rglob("*.aadl")) if source.is_dir() else [source]
    if not paths or any(not item.is_file() or item.suffix.lower() != ".aadl" for item in paths):
        raise ValueError("Input must contain at least one existing .aadl source")
    return ProjectDocument(tuple(ProjectFile(item.relative_to(source).as_posix() if source.is_dir() else item.name,
                                            item.read_bytes().decode("utf-8-sig")) for item in paths), True, (), True)


@dataclass(frozen=True)
class ContextFile:
    name: str
    text: str


def _safe_name(name: str) -> str:
    path = PurePosixPath(name)
    if (not name or "\\" in name or ":" in name or path.is_absolute()
            or any(part in {".", ".."} for part in path.parts) or path.suffix.lower() != ".aadl"):
        raise ValueError(f"Unsafe source name: {name!r}")
    return path.as_posix()


def context_hash(files: Iterable[ContextFile]) -> str:
    payload = [{"name": item.name, "text": item.text} for item in files]
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class ContextSnapshot:
    files: tuple[ContextFile, ...] = ()
    sha256: str = ""

    def __post_init__(self):
        items = tuple(sorted(self.files, key=lambda item: item.name))
        names = [_safe_name(item.name) for item in items]
        if len({name.casefold() for name in names}) != len(names):
            raise ValueError("Dependency names must be unique on all supported filesystems")
        expected = context_hash(items)
        if self.sha256 and self.sha256 != expected:
            raise ValueError("Context snapshot hash does not match its contents")
        object.__setattr__(self, "files", items)
        object.__setattr__(self, "sha256", expected)

    def as_dict(self):
        return {"sha256": self.sha256, "files": [{"name": item.name, "text": item.text} for item in self.files]}

    def render_prompt(self):
        if not self.files:
            return ""
        return "Read-only dependency sources (already present; do not regenerate):\n" + "\n".join(
            f"DEPENDENCY {item.name}\n{item.text}" for item in self.files)


def coerce_context(value=None) -> ContextSnapshot:
    if value is None:
        return ContextSnapshot()
    if isinstance(value, ContextSnapshot):
        return value
    if not isinstance(value, dict) or set(value) != {"sha256", "files"} or not isinstance(value["files"], list):
        raise ValueError("Context must contain sha256 and a file list")
    if not isinstance(value["sha256"], str) or len(value["sha256"]) != 64:
        raise ValueError("An explicit context snapshot requires its SHA256")
    items = []
    for item in value["files"]:
        if not isinstance(item, dict) or set(item) != {"name", "text"} or not all(isinstance(v, str) for v in item.values()):
            raise ValueError("Each dependency requires a name and source text")
        items.append(ContextFile(**item))
    return ContextSnapshot(tuple(items), value["sha256"])


def snapshot_context(paths: Iterable[str | Path] = ()) -> ContextSnapshot:
    files = []
    for index, value in enumerate(paths, 1):
        root = Path(value).resolve()
        if not root.is_dir():
            raise ValueError(f"Dependency directory does not exist: {root}")
        for path in sorted(root.rglob("*.aadl")):
            if not path.resolve().is_relative_to(root):
                raise ValueError(f"Dependency escapes its supplied directory: {path}")
            files.append(ContextFile(f"context/dir_{index:03d}/{path.relative_to(root).as_posix()}", path.read_bytes().decode("utf-8-sig")))
    return ContextSnapshot(tuple(files))


def write_project(document: ProjectDocument, target: str | Path, context=None) -> list[Path]:
    root = Path(target).resolve()
    root.mkdir(parents=True, exist_ok=True)
    result, seen = [], set()
    for item in (*document.files, *coerce_context(context).files):
        name = _safe_name(item.name)
        if name.casefold() in seen:
            raise ValueError(f"Duplicate project source: {name}")
        seen.add(name.casefold())
        path = root / name
        if not path.resolve().is_relative_to(root):
            raise ValueError("Project output escapes its destination")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(item.text, encoding="utf-8", newline="")
        result.append(path)
    return result
