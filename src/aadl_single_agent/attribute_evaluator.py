"""Language-neutral, dependency-tracked attributes over immutable syntax snapshots.

Handlers belong to the trusted rule package. The evaluator knows no AADL names,
scope rules, or declaration order. A candidate forks the cache; it never mutates
the committed snapshot. Dependencies are verified lazily against the new tree.
"""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, fields, is_dataclass, replace
from decimal import Decimal
from enum import Enum
from types import MappingProxyType
from typing import Any

from .semantic_types import ProjectView, RuleDefinitionError, RuleResult, SemanticReport, Status, SyntaxNode


@dataclass(frozen=True)
class Missing:
    """An absent namespace entry, not a successful resolution or a false value."""

    namespace: str
    key: Any

    def __bool__(self):
        raise RuleDefinitionError("A missing reference requires an explicit Pending/Conflict decision")


def _freeze(value, active=None):
    """Own all containers returned by handlers; reject opaque mutable objects."""
    if value is None or isinstance(value, (str, bytes, bool, int, float, Decimal, Enum)):
        return value
    if isinstance(value, SyntaxNode):
        return value  # SyntaxNode freezes its child and binding containers.
    active = set() if active is None else active
    marker = id(value)
    if marker in active:
        raise RuleDefinitionError("Attribute values must not contain cyclic containers")
    active.add(marker)
    try:
        if isinstance(value, Mapping):
            return MappingProxyType({_freeze(key, active): _freeze(item, active) for key, item in value.items()})
        if isinstance(value, (tuple, list)):
            return tuple(_freeze(item, active) for item in value)
        if isinstance(value, (set, frozenset)):
            return frozenset(_freeze(item, active) for item in value)
        if is_dataclass(value) and value.__dataclass_params__.frozen:
            return replace(value, **{field.name: _freeze(getattr(value, field.name), active)
                                     for field in fields(value) if field.init})
    finally:
        active.remove(marker)
    raise RuleDefinitionError(f"Attribute handler returned mutable/unsupported {type(value).__name__}")


@dataclass(frozen=True)
class _Dependency:
    kind: str
    key: Any
    value: Any


@dataclass(frozen=True)
class _CacheEntry:
    stamp: tuple
    value: Any
    dependencies: tuple[_Dependency, ...]


class AttributeContext:
    """Read-only handler API; every observable snapshot read is a dependency."""

    __slots__ = ("_evaluator", "_key", "_dependencies")

    def __init__(self, evaluator, key):
        object.__setattr__(self, "_evaluator", evaluator)
        object.__setattr__(self, "_key", key)
        object.__setattr__(self, "_dependencies", [])

    def __setattr__(self, name, value):
        raise RuleDefinitionError("Attribute contexts are read-only")

    def _record(self, kind, key, value):
        self._dependencies.append(_Dependency(kind, key, value))

    @property
    def node(self):
        return self._evaluator._nodes[self._key]

    @property
    def root(self):
        self._record("node", (), self._evaluator._stamps[()])
        return self._evaluator.root

    @property
    def project(self):
        self._record("project", None, self._evaluator._project_stamp)
        return self._evaluator.project

    @property
    def inputs(self):
        self._record("inputs", None, self._evaluator.inputs)
        return self._evaluator.inputs

    def parent(self, node=None):
        key = self._key if node is None else self._evaluator._node_key(node)
        if not key:
            return None
        parent_key = key[:-1]
        self._record("node", parent_key, self._evaluator._stamps[parent_key])
        return self._evaluator._nodes[parent_key]

    def get(self, node, attribute):
        key = self._evaluator._node_key(node)
        value = self._evaluator._get(key, attribute)
        self._record("attribute", (key, attribute), value)
        return value

    def lookup(self, namespace, key):
        key = _freeze(key)
        value = self._evaluator._lookup(namespace, key)
        # Depend on this entry rather than the entire symbol map. When an
        # unrelated declaration is appended, equal entries remain reusable.
        self._record("lookup", (namespace, key), value)
        return value


class AttributeEvaluator:
    """Explicit (production, attribute) handlers with transactional cache forks."""

    def __init__(self, handlers, project, *, constraints=None, inputs=None):
        if not isinstance(handlers, Mapping) or not handlers:
            raise RuleDefinitionError("An attribute evaluator requires registered handlers")
        checked = {}
        for key, handler in handlers.items():
            if not isinstance(key, tuple) or len(key) != 2 or not all(isinstance(v, str) and v for v in key) or not callable(handler):
                raise RuleDefinitionError(f"Invalid attribute handler registration: {key!r}")
            checked[key] = handler
        self.handlers = MappingProxyType(checked)
        constraints = {} if constraints is None else constraints
        if not isinstance(constraints, Mapping):
            raise RuleDefinitionError("CONSTRAINTS must map productions to attribute names")
        registered = {}
        for production, names in constraints.items():
            if (not isinstance(production, str) or not production or
                    not isinstance(names, (tuple, list)) or not names or
                    any(not isinstance(name, str) or not name for name in names) or
                    len(set(names)) != len(names)):
                raise RuleDefinitionError(f"Invalid constraints for {production!r}")
            for name in names:
                if (production, name) not in checked:
                    raise RuleDefinitionError(f"Constraint has no registered handler: {production}.{name}")
            registered[production] = tuple(names)
        self.constraints = MappingProxyType(registered)
        self.inputs = _freeze({} if inputs is None else inputs)
        if not isinstance(self.inputs, Mapping):
            raise RuleDefinitionError("Attribute inputs must be a mapping")
        self._cache = {}
        self._active = []
        self._validated = set()
        self._metrics = {"computed": 0, "cache_hits": 0, "invalidated": 0, "lookups": 0}
        self._install(project)

    def _install(self, project):
        if not isinstance(project, ProjectView):
            raise RuleDefinitionError("Attribute snapshots require ProjectView")
        self.project = project
        self.root = SyntaxNode("__project__", project.text, tuple(project.all_units),
                               0, len(project.text), project.final, "<project>")
        self._nodes, self._node_keys, self._stamps = {}, {}, {}
        self._locations = {}
        self._relocated = {}

        def visit(node, path):
            if not isinstance(node, SyntaxNode):
                raise RuleDefinitionError("Attribute trees must contain SyntaxNode children")
            self._nodes[path] = node
            if id(node) in self._node_keys:
                raise RuleDefinitionError("A syntax node cannot have two parents in one attribute snapshot")
            self._node_keys[id(node)] = path
            location = (node.production, node.source, node.start, node.end, node.text)
            self._locations.setdefault(location, []).append(path)
            children = tuple(visit(child, path + (index,)) for index, child in enumerate(node.children))
            stamp = (node.production, node.text, node.start, node.end, node.complete, node.source,
                     tuple(sorted(node.bindings.items())), children)
            self._stamps[path] = stamp
            return stamp

        visit(self.root, ())
        self._project_stamp = self._stamps[()]

    def fork(self, project, *, inputs=None):
        """Share only immutable cache entries; branch dictionaries are independent."""
        child = AttributeEvaluator(self.handlers, project, constraints=self.constraints,
                                   inputs=self.inputs if inputs is None else inputs)
        child._cache = dict(self._cache)
        return child

    @property
    def metrics(self):
        return MappingProxyType(dict(self._metrics))

    @property
    def dependencies(self):
        """Read-only dependency evidence for tests and experiment diagnostics."""
        return MappingProxyType({key: entry.dependencies for key, entry in self._cache.items()})

    def _node_key(self, node):
        key = self._node_keys.get(id(node))
        if key is not None:
            return key
        # A cached immutable namespace can retain a node from the parent tree.
        # It is usable only when that logical node is still structurally equal.
        # Locate only nodes actually read from cached values. Keeping all prior
        # node ids would grow with every token and permit Python id reuse. These
        # entries hold strong references and are local to this snapshot.
        previous = self._relocated.get(id(node))
        if previous is not None and previous[0] is node:
            return previous[1]
        if isinstance(node, SyntaxNode):
            location = (node.production, node.source, node.start, node.end, node.text)
            def same(left, right):
                return (left == right and dict(left.bindings) == dict(right.bindings)
                        and len(left.children) == len(right.children)
                        and all(same(a, b) for a, b in zip(left.children, right.children)))
            matches = [path for path in self._locations.get(location, ()) if same(self._nodes[path], node)]
            if len(matches) == 1:
                self._relocated[id(node)] = (node, matches[0])
                return matches[0]
        raise RuleDefinitionError("Attribute requested for a node outside this snapshot")

    def get(self, node, attribute):
        if not isinstance(attribute, str) or not attribute:
            raise RuleDefinitionError("Attribute names must be nonempty strings")
        return self._get(self._node_key(node), attribute)

    def lookup(self, namespace, key):
        return self._lookup(namespace, _freeze(key))

    def _lookup(self, namespace, key):
        self._metrics["lookups"] += 1
        mapping = self._get((), namespace)
        if not isinstance(mapping, Mapping):
            raise RuleDefinitionError(f"Project namespace {namespace!r} is not a mapping")
        return mapping[key] if key in mapping else Missing(namespace, key)

    def _unchanged(self, dependency):
        kind, key, previous = dependency.kind, dependency.key, dependency.value
        if kind == "node":
            return self._stamps.get(key) == previous
        if kind == "project":
            return self._project_stamp == previous
        if kind == "inputs":
            return self.inputs == previous
        if kind == "attribute":
            path, attribute = key
            return path in self._nodes and self._get(path, attribute) == previous
        if kind == "lookup":
            return self._lookup(*key) == previous
        raise RuleDefinitionError(f"Unknown attribute dependency kind: {kind}")

    def _get(self, path, attribute):
        node = self._nodes[path]
        registration = (node.production, attribute)
        handler = self.handlers.get(registration)
        if handler is None:
            raise RuleDefinitionError(f"No attribute handler registered for {node.production}.{attribute}")
        key = (path, attribute)
        # The project and inputs never change inside one evaluator. Rechecking
        # the same dependency DAG on every read can otherwise grow exponentially.
        # A fork starts with an empty validated set and rechecks its own inputs.
        if key in self._validated:
            self._metrics["cache_hits"] += 1
            return self._cache[key].value
        if key in self._active:
            cycle = self._active[self._active.index(key):] + [key]
            names = " -> ".join(f"{self._nodes[p].production}.{a}" for p, a in cycle)
            raise RuleDefinitionError(f"Attribute dependency cycle: {names}")
        self._active.append(key)
        try:
            cached = self._cache.get(key)
            if cached is not None:
                if cached.stamp == self._stamps[path] and all(self._unchanged(dep) for dep in cached.dependencies):
                    self._validated.add(key)
                    self._metrics["cache_hits"] += 1
                    return cached.value
                self._metrics["invalidated"] += 1
            context = AttributeContext(self, path)
            value = _freeze(handler(context))
            self._cache[key] = _CacheEntry(self._stamps[path], value, tuple(context._dependencies))
            self._validated.add(key)
            self._metrics["computed"] += 1
            return value
        except RuleDefinitionError:
            raise
        except Exception as error:
            raise RuleDefinitionError(f"Attribute handler {node.production}.{attribute} failed: {error}") from error
        finally:
            self._active.pop()

    def evaluate_constraints(self):
        if not self.constraints:
            raise RuleDefinitionError("No attribute constraints are registered")
        results = []
        executed = 0
        for path, node in self._nodes.items():
            for attribute in self.constraints.get(node.production, ()):
                value = self._get(path, attribute)
                values = (value,) if isinstance(value, RuleResult) else value
                if not isinstance(values, tuple) or not values or any(not isinstance(item, RuleResult) or not isinstance(item.status, Status) for item in values):
                    raise RuleDefinitionError(f"Constraint {node.production}.{attribute} must return explicit nonempty RuleResult values")
                results.extend(values)
                executed += 1
        if not executed:
            # Early prefixes may not contain a constraint-bearing production.
            # That is an unresolved obligation, never fabricated success.
            results.append(RuleResult(Status.PENDING, "attribute.constraints_pending",
                                      "No registered constraint is applicable to this prefix yet"))
        return SemanticReport(tuple(results), {"evaluated_constraints": executed, "cache": dict(self.metrics)})
