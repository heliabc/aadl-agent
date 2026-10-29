"""Load trusted, self-contained language rule packages.

The runtime knows bytes, grammar captures and callables. Language categories,
name resolution, obligations and prompts belong to the selected package. Python
actions are deliberately trusted local code, not a sandboxed plugin format.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from functools import lru_cache
import hashlib
from importlib.resources import files
import json
from pathlib import Path
import re
import sys
from types import MappingProxyType, ModuleType
from typing import Any

import jsonschema
from llguidance import LLMatcher, LLTokenizer, TokenizerWrapper


RUNTIME_VERSION = "2"


@dataclass(frozen=True)
class PrefixState:
    text: str = ""
    complete: bool = False
    pending: tuple[str, ...] = ()
    # Opaque checkpoints are cloned before probing. They never participate in
    # equality, serialization, or the rule-package content fingerprint.
    checkpoint: Any = field(default=None, repr=False, compare=False)
    bundle_digest: str = ""


@dataclass(frozen=True)
class ProbeResult:
    allowed: bool
    state: PrefixState
    reason: str = ""
    pending: tuple[str, ...] = ()


@dataclass
class ValidationReport:
    valid: bool
    diagnostics: list[str]
    pending: list[str]


class _Bytes:
    """A model-free tokenizer: every UTF-8 byte is exactly one token."""
    eos_token_id = 256
    bos_token_id = None
    special_token_ids = [256]
    tokens = [bytes([index]) for index in range(256)] + [b"<eos>"]

    def __call__(self, value):
        return list(value if isinstance(value, bytes) else value.encode("utf-8"))


@lru_cache(maxsize=1)
def byte_tokenizer():
    return LLTokenizer(TokenizerWrapper(_Bytes()), n_vocab=257, eos_token=256, slices=[])


@dataclass(frozen=True)
class RuleBundle:
    path: Path
    profile: str
    digest: str
    grammar: str
    planning_schema: dict
    system_prompt: str
    planning_prompt: str
    generation_prompt: str
    bindings: dict
    module: ModuleType = field(repr=False, compare=False)
    resources: dict[str, bytes] = field(repr=False, compare=False)
    runtime_version: str = RUNTIME_VERSION

    def read_text(self, name: str) -> str:
        # Read the immutable load-time snapshot, not a later on-disk edit. Thus
        # advertised fingerprints identify the exact rules used by this engine.
        return self.resources[name].decode("utf-8-sig")

    def read_json(self, name: str):
        return json.loads(self.read_text(name))

    def create_engine(self, declarations=None):
        return getattr(self.module, self.bindings["engine_factory"])(self, declarations)

    def normalize_plan(self, plan):
        # Schema field names and their translation into semantic declarations
        # are owned by the package, not by the generation workflow.
        return getattr(self.module, self.bindings["plan_normalizer"])(plan)

    def new_matcher(self):
        # The fixed-byte tokenizer is for local validation; inference supplies
        # the same grammar to Outlines using the actual model tokenizer.
        return LLMatcher(byte_tokenizer(), self.grammar, log_level=0)


def _default_path() -> Path:
    source = Path(__file__).resolve().parents[2] / "resources" / "aadl"
    if source.is_dir():
        return source
    return Path(str(files("aadl_single_agent").joinpath("_resources", "aadl")))


def _grammar_rule_headers(grammar: str):
    """Read llguidance rule headers without confusing literals with grammar.

    The pinned parser accepts indentation, ?, ! and !? rule prefixes, and a
    space before attributes. Strings/regular expressions can span lines and
    contain text that resembles a production, including a capture's ']' name.
    Mask their contents while preserving offsets, then inspect actual headers.
    llguidance itself remains responsible for complete grammar validation.
    """
    masked = list(grammar)
    index = 0
    while index < len(grammar):
        start = index
        if grammar.startswith("//", index):
            index = grammar.find("\n", index)
            if index < 0:
                index = len(grammar)
        elif grammar[index] in {'"', '/'}:
            delimiter = grammar[index]
            index += 1
            while index < len(grammar):
                if grammar[index] == "\\":
                    index += 2  # Escaped delimiters do not terminate a literal.
                elif grammar[index] == delimiter:
                    index += 1
                    break
                else:
                    index += 1
            index = min(index, len(grammar))
        else:
            index += 1
            continue
        for position in range(start, index):
            if masked[position] not in "\r\n":
                masked[position] = " "

    text = "".join(masked)
    header = re.compile(
        r"(?m)^[ \t]*(?:!\??|\?)?([a-z_][a-z_0-9]*)[ \t]*"
        r"(?:\[([^\]\r\n]*)\][ \t]*)?:")
    for match in header.finditer(text):
        name, attributes = match.group(1), match.group(2)
        capture = re.search(r"\bcapture\b([ \t]*=)?", attributes or "")
        if capture is None:
            yield name, None
        elif capture.group(1) is None:
            yield name, name
        else:
            # Decode the original JSON string, not a quoted-string regex: names
            # may legitimately contain escaped quotes or closing brackets.
            offset = match.start(2) + capture.end()
            alias, _ = json.JSONDecoder().raw_decode(grammar[offset:].lstrip(" \t"))
            if not isinstance(alias, str):
                raise ValueError(f"Capture name must be a string: {name}")
            yield name, alias


def _binding_check(grammar: str, bindings: dict, module: ModuleType) -> None:
    """Fail closed when a grammar unit has no declared semantic disposition.

    Captured productions must map to actions. All other lowercase productions
    must be explicitly structural. A new construct cannot accidentally acquire
    syntax support merely because its semantic binding was forgotten.
    """
    captures = {}
    structural = set()
    for name, capture in _grammar_rule_headers(grammar):
        if capture is not None:
            if capture in captures:
                raise ValueError(f"Duplicate grammar capture: {capture}")
            captures[capture] = name
        else:
            structural.add(name)
    declared = bindings.get("captures", {})
    if set(declared) != set(captures):
        raise ValueError("Capture/action mismatch: "
                         f"unbound={sorted(set(captures) - set(declared))}; "
                         f"unknown={sorted(set(declared) - set(captures))}")
    if set(bindings.get("structural_rules", [])) != structural:
        raise ValueError("Every noncaptured grammar rule must be explicitly listed in structural_rules")
    actions = list(declared.values()) + [bindings.get(key) for key in
              ("prefix_hook", "semantic_check", "engine_factory", "plan_normalizer")]
    for action in actions:
        if not isinstance(action, str) or not callable(getattr(module, action, None)):
            raise ValueError(f"Missing callable rule binding: {action}")


def load_bundle(path: str | Path | None = None) -> RuleBundle:
    root = (Path(path) if path is not None else _default_path()).resolve()
    if not root.is_dir():
        raise ValueError(f"Rule package directory does not exist: {root}")
    snapshot = {}
    for item in sorted(root.rglob("*")):
        if not item.is_file() or "__pycache__" in item.parts or item.suffix == ".pyc":
            continue
        if not item.resolve().is_relative_to(root):
            raise ValueError(f"Rule resource escapes its package: {item}")
        snapshot[item.relative_to(root).as_posix()] = item.read_bytes()
    if "manifest.json" not in snapshot:
        raise ValueError("Rule package requires manifest.json")
    manifest = json.loads(snapshot["manifest.json"].decode("utf-8-sig"))
    if str(manifest.get("runtime_version")) != RUNTIME_VERSION:
        raise ValueError(f"Unsupported rule runtime version: {manifest.get('runtime_version')}")

    def read(key):
        name = manifest[key]
        if name not in snapshot:
            raise ValueError(f"Missing rule resource {key}: {name}")
        return snapshot[name].decode("utf-8-sig")

    digest = hashlib.sha256()
    for name, content in sorted(snapshot.items()):
        digest.update(name.encode("utf-8") + b"\0" + content + b"\0")
    fingerprint = digest.hexdigest()
    # Each content version gets its own module namespace; two profiles may be
    # used in one process without sharing mutable language-specific globals.
    module_name = "_rule_package_" + fingerprint
    module = ModuleType(module_name)
    module.__file__ = str(root / manifest["semantic_module"])
    module.__path__ = [str(root)]
    sys.modules[module_name] = module
    try:
        exec(compile(read("semantic_module"), module.__file__, "exec"), module.__dict__)
        grammar = read("grammar")
        bindings = json.loads(read("bindings"))
        schema = json.loads(read("planning_schema"))
        jsonschema.Draft202012Validator.check_schema(schema)
        _binding_check(grammar, bindings, module)
        bundle = RuleBundle(root, manifest.get("profile", "core-v1"), fingerprint,
                            grammar, schema, read("system_prompt"), read("planning_prompt"),
                            read("generation_prompt"), bindings, module, MappingProxyType(snapshot))
        # Compile immediately. A broken package must fail at loading, before
        # a server advertises support or a model consumes any generation budget.
        matcher = bundle.new_matcher()
        if matcher.is_error():
            raise ValueError(matcher.get_error())
        return bundle
    except BaseException:
        sys.modules.pop(module_name, None)
        raise
