"""Language-neutral, immutable values shared by grammars and Python attributes.

Rule packages are trusted installed Python, not model-supplied code. They only
receive frozen syntax snapshots; speculative evaluation cannot edit a committed
parser state. A Pending result is an obligation, never a completed validation.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from types import MappingProxyType
from typing import Mapping, Any


class RuleDefinitionError(RuntimeError):
    """A faulty rule package must be reported as infrastructure, not masked."""


class ParseResourceError(RuntimeError):
    """An explicit parsing resource limit, never an assertion of invalid input."""


class Status(str, Enum):
    SATISFIED = "Satisfied"
    PENDING = "Pending"
    CONFLICT = "Conflict"


@dataclass(frozen=True)
class SyntaxNode:
    production: str
    text: str
    children: tuple["SyntaxNode", ...] = ()
    start: int = 0
    end: int = 0
    complete: bool = True
    source: str = ""
    bindings: Mapping[str, Any] = field(default_factory=dict, hash=False, repr=False)

    def __post_init__(self):
        # A frozen dataclass must also own its nested containers: speculative
        # attributes must not mutate a previous snapshot through a child list.
        object.__setattr__(self, "children", tuple(self.children))
        bindings = {}
        for key, value in self.bindings.items():
            if not isinstance(key, str) or not key:
                raise RuleDefinitionError("Syntax field names must be nonempty strings")
            if isinstance(value, (tuple, list)):
                value = tuple(value)
                if not value or any(not isinstance(item, str) or not item for item in value):
                    raise RuleDefinitionError(f"Invalid syntax field binding: {key}")
            elif not isinstance(value, str) or not value:
                raise RuleDefinitionError(f"Invalid syntax field binding: {key}")
            bindings[key] = value
        # Bindings participate in equality, so a cached reference cannot regard
        # two differently bound syntax nodes as the same immutable value.
        object.__setattr__(self, "bindings", MappingProxyType(bindings))

    def field(self, name: str, *, recursive: bool = False):
        """Resolve a declared field without language-specific AST adapters."""
        if name not in self.bindings:
            raise RuleDefinitionError(f"{self.production} has no declared field {name!r}")
        targets = self.bindings[name]
        targets = (targets,) if isinstance(targets, str) else tuple(targets)
        # Stop at a matching node, so a declaration's own name does not also
        # capture names nested inside the selected field's subtree.
        def collect(node):
            for child in node.children:
                if child.production in targets:
                    yield child
                elif recursive:
                    yield from collect(child)
        return tuple(collect(self))

    def walk(self):
        yield self
        for child in self.children:
            yield from child.walk()

    def find(self, production: str, recursive: bool = True):
        nodes = (node for child in self.children for node in child.walk()) if recursive else iter(self.children)
        return tuple(node for node in nodes if node.production == production)

    def first(self, production: str):
        return next(iter(self.find(production)), None)


@dataclass(frozen=True)
class ProjectView:
    units: tuple[SyntaxNode, ...]
    text: str = ""
    dependencies: tuple[SyntaxNode, ...] = ()
    final: bool = False

    def __post_init__(self):
        object.__setattr__(self, "units", tuple(self.units))
        object.__setattr__(self, "dependencies", tuple(self.dependencies))

    @property
    def all_units(self):
        return self.dependencies + self.units


@dataclass(frozen=True)
class RuleResult:
    status: Status
    rule_id: str
    message: str = ""
    source: str = ""
    start: int = 0
    end: int = 0
    standard: str = ""
    osate: str = ""


def satisfied(rule_id: str, message: str = "", **location):
    return RuleResult(Status.SATISFIED, rule_id, message, **location)


def pending(rule_id: str, message: str = "", **location):
    return RuleResult(Status.PENDING, rule_id, message, **location)


def conflict(rule_id: str, message: str = "", **location):
    return RuleResult(Status.CONFLICT, rule_id, message, **location)


@dataclass(frozen=True)
class SemanticReport:
    results: tuple[RuleResult, ...] = ()
    attributes: Mapping[str, Any] = field(default_factory=dict, compare=False)

    def __post_init__(self):
        object.__setattr__(self, "results", tuple(self.results))
        object.__setattr__(self, "attributes", MappingProxyType(dict(self.attributes)))

    @property
    def valid(self):
        return not any(result.status == Status.CONFLICT for result in self.results)

    @property
    def pending(self):
        return tuple(result for result in self.results if result.status == Status.PENDING)

