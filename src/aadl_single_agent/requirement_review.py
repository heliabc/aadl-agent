"""Independent, explicitly authored construction checks over parsed source.

This module never interprets natural language, repairs code, or changes static
acceptance. A reviewer binds a checklist to the exact requirement bytes before
evaluation. Checks describe visible AST declarations, not inherited interfaces,
unit-equivalent quantities, behavior, or overall requirement satisfaction.
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path


def _digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _selector(value):
    if (not isinstance(value, dict) or set(value) - {"production", "fields", "within"}
            or not isinstance(value.get("production"), str) or not value["production"]):
        raise ValueError("Review selector requires a production and accepts fields/within only")
    fields = value.get("fields", {})
    if not isinstance(fields, dict):
        raise ValueError("Review fields must map direct production paths to text matchers")
    for path, matcher in fields.items():
        if not isinstance(path, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*(/[A-Za-z_][A-Za-z_0-9]*)*", path):
            raise ValueError("Review field paths must contain slash-separated production names")
        if (not isinstance(matcher, dict) or set(matcher) - {"text", "case_sensitive"}
                or not isinstance(matcher.get("text"), str)
                or type(matcher.get("case_sensitive", True)) is not bool):
            raise ValueError("Each review field requires {text, optional case_sensitive}")
    if "within" in value:
        _selector(value["within"])


def load_review(path: str | Path, requirements: str | Path) -> dict:
    """Bind the supplied checklist to bytes, never an inferred filename pair."""
    source = Path(path).resolve()
    raw = source.read_bytes()
    spec = json.loads(raw.decode("utf-8-sig"))
    allowed = {"schema_version", "requirements_sha256", "assertions", "description"}
    if not isinstance(spec, dict) or set(spec) - allowed or spec.get("schema_version") != 1:
        raise ValueError("Unsupported explicit construction-review schema")
    requirements_hash = _digest(Path(requirements).read_bytes())
    if spec.get("requirements_sha256") != requirements_hash:
        raise ValueError("Review checklist does not match the exact requirement bytes")
    assertions = spec.get("assertions")
    if not isinstance(assertions, list) or not assertions:
        raise ValueError("Review checklist requires nonempty assertions")
    seen = set()
    for assertion in assertions:
        if (not isinstance(assertion, dict) or set(assertion) - {"id", "selector", "count", "kind"}
                or not isinstance(assertion.get("id"), str) or not assertion["id"]
                or assertion["id"] in seen or type(assertion.get("count")) is not int
                or assertion["count"] < 0):
            raise ValueError("Review assertions require unique ids and nonnegative exact counts")
        seen.add(assertion["id"])
        kind = assertion.get("kind", "node_count")
        if kind == "node_count":
            _selector(assertion.get("selector"))
        elif kind != "file_count" or "selector" in assertion:
            raise ValueError("Review assertion kind must be node_count or file_count")
    # Return an owned JSON value; callers can persist the complete checklist
    # beside its identity without retaining mutable references to user objects.
    return {"spec": spec, "checklist_sha256": _digest(raw), "path": str(source)}


def _normalize(text, sensitive):
    # Token concatenation ignores lexical trivia. Case folding is opt-in per
    # field, so literal strings are not accidentally treated as AADL names.
    # Preserve whitespace inside string literals: "a b" is not "ab".
    value = "".join(re.findall(r'"(?:\\.|[^"\\])*"|[^\s"]+', text))
    return value if sensitive else value.casefold()


def _terminal_text(node):
    if not node.children:
        return node.text
    return "".join(_terminal_text(child) for child in node.children)


def _matches(node, ancestors, selector):
    if node.production != selector["production"]:
        return False
    for path, matcher in selector.get("fields", {}).items():
        frontier = [node]
        for production in path.split("/"):
            frontier = [child for parent in frontier for child in parent.children
                        if child.production == production]
        # Multiple matches are not silently reduced to the first occurrence.
        if len(frontier) != 1:
            return False
        sensitive = matcher.get("case_sensitive", True)
        if _normalize(_terminal_text(frontier[0]), sensitive) != _normalize(matcher["text"], sensitive):
            return False
    within = selector.get("within")
    return within is None or any(_matches(parent, ancestors[:index], within)
                                 for index, parent in enumerate(ancestors))


def _walk(node, ancestors=()):
    yield node, ancestors
    for child in node.children:
        yield from _walk(child, ancestors + (node,))


def _visible_edges(frontend):
    """Follow the frontend's public AST shape through erased EBNF helpers."""
    def visible(symbol, active=frozenset()):
        name = str(symbol.name)
        if symbol.is_term:
            return {name}
        if name in frontend.trivia or name in active:
            return set()
        rules = [frontend.rules[index] for index in frontend.by_origin.get(name, ())]
        if not name.startswith("__"):
            return {str(rule.alias or rule.origin.name) for rule in rules}
        return {child for rule in rules for part in rule.expansion
                for child in visible(part, active | {name})}
    edges = {}
    for rule in frontend.rules:
        name = str(rule.alias or rule.origin.name)
        if name.startswith("__") or name in frontend.trivia:
            continue
        edges.setdefault(name, set()).update(child for symbol in rule.expansion for child in visible(symbol))
    return edges


def _check_grammar(selector, frontend, edges):
    """A misspelled checker must fail, including a would-be zero-count pass."""
    available = frontend.productions | frontend.terminals.keys()
    referenced = {selector["production"]}
    referenced.update(part for path in selector.get("fields", {}) for part in path.split("/"))
    if referenced - available:
        raise ValueError(f"Review references unknown grammar nodes: {sorted(referenced - available)}")
    for path in selector.get("fields", {}):
        parent = selector["production"]
        for child in path.split("/"):
            if child not in edges.get(parent, set()):
                raise ValueError(f"Review field path has no direct AST edge: {parent}/{child}")
            parent = child
    if "within" in selector:
        _check_grammar(selector["within"], frontend, edges)


def evaluate_review(bundle, sources: list[dict], review: dict) -> dict:
    """Evaluate complete sources only; ambiguous parses remain unevaluable.

    The caller supplies generated source files only. Neither retrieval snippets
    nor real external libraries can satisfy a requirement to generate a node.
    """
    result = {"schema_version": 1, "kind": "explicit_construction_review",
              "scope": "authored AST construction assertions; not full requirement satisfaction",
              "affects_static_acceptance": False,
              "checklist_sha256": review["checklist_sha256"],
              "requirements_sha256": review["spec"]["requirements_sha256"],
              "rule_bundle_sha256": bundle.digest, "status": "not_evaluable",
              "sources": [], "assertions": [], "diagnostics": []}
    edges = _visible_edges(bundle.frontend)
    for assertion in review["spec"]["assertions"]:
        if assertion.get("kind", "node_count") == "node_count":
            _selector(assertion["selector"])
            _check_grammar(assertion["selector"], bundle.frontend, edges)
    nodes = []
    names = set()
    if not sources:
        result["diagnostics"].append("No generated source files")
        return result
    for source in sources:
        name, text = source["name"], source["text"]
        if name.casefold() in names:
            raise ValueError("Duplicate construction-review source name")
        names.add(name.casefold())
        result["sources"].append({"name": name, "sha256": _digest(text.encode("utf-8"))})
        parsed = bundle.frontend.complete(text, name)
        if not parsed.complete or len(parsed.alternatives) != 1:
            result["diagnostics"].append(f"{name}: incomplete, invalid or ambiguous syntax")
        else:
            nodes.extend(_walk(parsed.alternatives[0]))
    if result["diagnostics"]:
        return result
    for assertion in review["spec"]["assertions"]:
        matches = []
        if assertion.get("kind", "node_count") == "file_count":
            actual = len(sources)
        else:
            matches = [node for node, parents in nodes if _matches(node, parents, assertion["selector"])]
            actual = len(matches)
        result["assertions"].append({"id": assertion["id"], "expected_count": assertion["count"],
            "observed_count": actual, "status": "met" if actual == assertion["count"] else "not_met",
            "locations": [{"source": node.source, "start": node.start, "end": node.end}
                          for node in matches]})
    result["status"] = "met" if all(item["status"] == "met" for item in result["assertions"]) else "not_met"
    return result
