"""Load the two trusted rule sources as one immutable, versioned snapshot."""
from __future__ import annotations

from dataclasses import dataclass, field
from copy import deepcopy
from functools import lru_cache
import hashlib
from importlib.resources import files
from importlib.metadata import version
import json
from pathlib import Path
import sys
from types import MappingProxyType, ModuleType
from typing import Any

from llguidance import LLMatcher, LLTokenizer, TokenizerWrapper

from .grammar import GrammarFrontend
from .semantic_types import RuleDefinitionError

RUNTIME_VERSION = "4"


@dataclass(frozen=True)
class PrefixState:
    text: str = ""
    complete: bool = False
    pending: tuple[str, ...] = ()
    checkpoint: Any = field(default=None, repr=False, compare=False)
    bundle_digest: str = ""


@dataclass(frozen=True)
class ProbeResult:
    allowed: bool
    state: PrefixState
    reason: str = ""
    pending: tuple[str, ...] = ()
    issues: tuple[dict, ...] = ()


@dataclass
class ValidationReport:
    valid: bool
    diagnostics: list[str]
    pending: list[str]
    issues: list[dict] = field(default_factory=list)


class _Bytes:
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
    system_prompt: str
    generation_prompt: str
    semantics: Any = field(repr=False, compare=False)
    frontend: GrammarFrontend = field(repr=False, compare=False)
    resources: dict[str, bytes] = field(repr=False, compare=False)
    runtime_version: str = RUNTIME_VERSION

    @property
    def compiled_grammar_sha256(self):
        # Hash the compiled production/terminal IR as well as the llguidance
        # input and compiler versions. A source-only JSON wrapper would not
        # identify a change in EBNF expansion or regular-expression compilation.
        payload = {
            "llguidance": LLMatcher.grammar_from_lark(self.grammar),
            "productions": [{"origin": str(rule.origin.name),
                             "expansion": [(str(symbol.name), bool(symbol.is_term)) for symbol in rule.expansion],
                             "alias": str(rule.alias) if rule.alias is not None else None}
                            for rule in self.frontend.rules],
            "terminals": {name: terminal.pattern.to_regexp() for name, terminal in self.frontend.terminals.items()},
            "versions": {name: version(name) for name in ("lark", "llguidance", "regex")},
        }
        encoded = json.dumps(payload, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    def read_text(self, name):
        return self.resources[name].decode("utf-8-sig")

    def read_json(self, name):
        return json.loads(self.read_text(name))

    def create_engine(self, declarations=None, *, context=None, project=False):
        from .attribute_runtime import AttributeEngine
        return AttributeEngine(self, declarations, context=context, project=project)

    def normalize_plan(self, plan):
        # Retained only for old declaration-constrained engine snapshots/replay.
        # Active generation has no declaration-planning request or schema.
        hook = getattr(self.semantics, 'normalize_plan', None)
        if not callable(hook):
            raise RuleDefinitionError('This rule bundle does not support legacy declaration plans')
        return hook(plan)

    @property
    def repair_prompt(self):
        # Repair is a separate task from initial generation. Older independent
        # rule bundles keep a language-neutral fallback without changing decoding.
        return self.semantics.METADATA.get('repair_prompt',
            'Correct the rejected project using the supplied diagnostics. Preserve '
            'the requirements. Return the entire corrected project.')

    def repair_advice(self, sources, diagnostics, declarations=None):
        """Obtain explanatory advice without letting a hook mutate a run's inputs.

        Diagnostics may include located validator objects as well as legacy
        strings. Only the rule package interprets language-specific constructs;
        advice does not change source files, validation results, or token masks.
        """
        hook = getattr(self.semantics, 'repair_advice', None)
        if hook is None:
            return []
        try:
            advice = hook(deepcopy(sources), deepcopy(diagnostics), deepcopy(declarations))
        except Exception as exc:
            raise RuleDefinitionError(f'repair_advice() failed: {exc}') from exc
        fields = {'id', 'source', 'line', 'observed', 'explanation', 'suggestion'}
        if not isinstance(advice, list):
            raise RuleDefinitionError('repair_advice() must return a list of located advice objects')
        for item in advice:
            if (not isinstance(item, dict) or set(item) != fields
                    or type(item['line']) is not int or item['line'] < 1
                    or any(not isinstance(item[key], str) or not item[key].strip()
                           for key in fields - {'line'})):
                raise RuleDefinitionError('repair_advice() returned an invalid located advice object')
        return deepcopy(advice)

    def reobserve_repair_focus(self, focus, sources):
        """Return pattern re-observation, never a verdict that a repair worked.

        Unsupported language patterns or missing source correspondence return
        None. The independent checker still decides project validity after a
        changed candidate; these observations only describe feedback outcomes.
        """
        hook = getattr(self.semantics, 'reobserve_repair_focus', None)
        if hook is None:
            return None
        try:
            observed = hook(deepcopy(focus), deepcopy(sources))
        except Exception as exc:
            raise RuleDefinitionError(f'reobserve_repair_focus() failed: {exc}') from exc
        if observed is not None and type(observed) is not bool:
            raise RuleDefinitionError('reobserve_repair_focus() must return bool or None')
        return observed

    def new_matcher(self):
        return LLMatcher(byte_tokenizer(), self.grammar, log_level=0)


def _default_path():
    source = Path(__file__).resolve().parents[2] / "resources" / "aadl"
    return source if source.is_dir() else Path(str(files("aadl_single_agent").joinpath("_resources", "aadl")))


def _validate_bindings(module, frontend):
    bindings = getattr(module, "BINDINGS", None)
    structural = getattr(module, "STRUCTURAL", None)
    if not isinstance(bindings, dict) or not isinstance(structural, (set, frozenset, tuple, list)):
        raise RuleDefinitionError("semantics.py must explicitly export BINDINGS and STRUCTURAL")
    structural = set(structural)
    if not frontend.trivia <= structural:
        raise RuleDefinitionError("Lexical trivia must be explicitly structural, never an attributed production")
    if structural & bindings.keys():
        raise RuleDefinitionError("A production cannot be both semantic and structural")
    declared = structural | bindings.keys()
    if declared != frontend.productions:
        raise RuleDefinitionError(f"Production bindings differ: unbound={sorted(frontend.productions-declared)}, unknown={sorted(declared-frontend.productions)}")
    for name, fields in bindings.items():
        if not isinstance(fields, dict):
            raise RuleDefinitionError(f"{name}: field bindings must be a dictionary")
        for field_name, target in fields.items():
            targets = (target,) if isinstance(target, str) else target
            if not isinstance(targets, (tuple, list)) or any(t not in frontend.productions | frontend.terminals.keys() for t in targets):
                raise RuleDefinitionError(f"{name}.{field_name}: unknown grammar field target {target!r}")
    # Generation no longer performs declaration planning. A legacy normalizer
    # is checked only when explicitly used, not imposed on every new rule pack.
    for function in ("evaluate",):
        if not callable(getattr(module, function, None)):
            raise RuleDefinitionError(f"semantics.py is missing {function}()")
    if hasattr(module, 'repair_advice') and not callable(module.repair_advice):
        raise RuleDefinitionError('semantics.py repair_advice must be callable when provided')
    if hasattr(module, 'reobserve_repair_focus') and not callable(module.reobserve_repair_focus):
        raise RuleDefinitionError('semantics.py reobserve_repair_focus must be callable when provided')
    repair_prompt = module.METADATA.get('repair_prompt')
    if repair_prompt is not None and (not isinstance(repair_prompt, str) or not repair_prompt.strip()):
        raise RuleDefinitionError('semantics.py repair_prompt must be nonempty text when provided')
    handlers = getattr(module, "HANDLERS", None)
    constraints = getattr(module, "CONSTRAINTS", None)
    required_attributes = getattr(module, "REQUIRED_ATTRIBUTES", None)
    if not isinstance(handlers, dict) or not isinstance(constraints, dict):
        raise RuleDefinitionError("semantics.py must register HANDLERS and CONSTRAINTS explicitly")
    for key, handler in handlers.items():
        if (not isinstance(key, tuple) or len(key) != 2
                or key[0] not in frontend.productions | {"__project__"}
                or not isinstance(key[1], str) or not key[1] or not callable(handler)):
            raise RuleDefinitionError(f"Invalid production/attribute handler: {key!r}")
    covered = {production for production, attribute in handlers}
    if bindings.keys() - covered:
        raise RuleDefinitionError(f"Missing semantic handlers: {sorted(bindings.keys()-covered)}")
    # The obligation inventory is explicit in the rule package. Automatically
    # deriving it from BINDINGS would let a new field declaration pass loading
    # without anyone registering its required semantic computation.
    if not isinstance(required_attributes, dict) or required_attributes.keys() != bindings.keys():
        raise RuleDefinitionError("REQUIRED_ATTRIBUTES must explicitly cover every bound production")
    for production, attributes in required_attributes.items():
        if (not isinstance(attributes, (tuple, list)) or not attributes
                or any((production, attribute) not in handlers for attribute in attributes)):
            raise RuleDefinitionError(f"Missing required attribute handler: {production!r}: {attributes!r}")
    if not constraints:
        raise RuleDefinitionError("At least one semantic constraint must be registered")
    for production, attributes in constraints.items():
        if (not isinstance(attributes, (tuple, list)) or not attributes
                or any((production, attribute) not in handlers for attribute in attributes)):
            raise RuleDefinitionError(f"Missing constraint handler for {production!r}: {attributes!r}")


def load_bundle(path: str | Path | None = None) -> RuleBundle:
    root = (Path(path) if path is not None else _default_path()).resolve()
    if not root.is_dir():
        raise ValueError(f"Rule directory does not exist: {root}")
    snapshot = {}
    for item in sorted(root.rglob("*")):
        if not item.is_file() or "__pycache__" in item.parts or item.suffix == ".pyc":
            continue
        if not item.resolve().is_relative_to(root):
            raise ValueError(f"Rule resource escapes its package: {item}")
        snapshot[item.relative_to(root).as_posix()] = item.read_bytes()
    required = {"aadl.lark", "semantics.py"}
    if set(snapshot) != required:
        raise RuleDefinitionError(f"Rule package requires exactly {sorted(required)}; missing={sorted(required-snapshot.keys())}, unexpected={sorted(snapshot.keys()-required)}")
    digest = hashlib.sha256()
    for name, content in sorted(snapshot.items()):
        digest.update(name.encode("utf-8") + b"\0" + content + b"\0")
    fingerprint = digest.hexdigest()
    grammar = snapshot["aadl.lark"].decode("utf-8-sig")
    # Only trusted installed/local bundles execute here. HTTP requests and
    # generated projects cannot upload or select executable rule code.
    module_name = "aadl_rules_" + fingerprint
    module = ModuleType(module_name)
    module.__file__ = str(root / "semantics.py")
    sys.modules[module_name] = module
    try:
        exec(compile(snapshot["semantics.py"], module.__file__, "exec"), module.__dict__)
        metadata = module.METADATA
        if str(metadata.get("runtime_version")) != RUNTIME_VERSION:
            raise RuleDefinitionError(f"Rule runtime must be {RUNTIME_VERSION}")
        frontend = GrammarFrontend(grammar)
        _validate_bindings(module, frontend)
        frontend.bindings = MappingProxyType({name: MappingProxyType(dict(fields)) for name, fields in module.BINDINGS.items()})
        # Prompts describe source generation and feedback repair only. A bundle
        # must not require a JSON planning schema just to load its AADL rules.
        bundle = RuleBundle(root, metadata["profile"], fingerprint, grammar,
                            metadata["system_prompt"], metadata["generation_prompt"],
                            module, frontend, MappingProxyType(snapshot))
        matcher = bundle.new_matcher()
        if matcher.is_error():
            raise RuleDefinitionError(matcher.get_error())
        return bundle
    except BaseException:
        sys.modules.pop(module_name, None)
        raise
