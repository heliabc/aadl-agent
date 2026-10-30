"""Compile one EBNF into an llguidance grammar and generic prefix syntax trees.

The semantic frontend uses Lark's compiled production IR, including its EBNF
expansions. It knows no language-specific keywords or sections. Earley items
retain distinct derivations; an ambiguous prefix is never resolved by silently
choosing one tree. Explicit limits raise infrastructure errors, not model errors.
"""
from __future__ import annotations

from collections import defaultdict, deque
from dataclasses import dataclass, field
from functools import lru_cache
from types import MappingProxyType
import time
import re

import regex
from lark import Lark
from lark.exceptions import UnexpectedInput

from .semantic_types import SyntaxNode, ParseResourceError, RuleDefinitionError


@dataclass(frozen=True)
class PrefixCheckpoint:
    """Read-only Earley graph owned by one compiler and one source prefix.

    Scanner edges crossing a restart boundary must survive too: a different
    derivation may consume a long stable token across a still-growing short one.
    Dropping those edges would silently remove valid alternatives.
    """
    owner: object = field(repr=False, compare=False)
    text: str
    source: str
    charts: tuple = field(repr=False, compare=False)
    scans: tuple = field(repr=False, compare=False)
    cumulative_states: tuple[int, ...] = field(repr=False, compare=False)
    restart: int = 0


@dataclass(frozen=True)
class SyntaxFrontier:
    alternatives: tuple[SyntaxNode, ...]
    complete: bool = False
    states: int = 0
    checkpoint: PrefixCheckpoint | None = field(default=None, repr=False, compare=False)
    reused_characters: int = field(default=0, compare=False)


def _append_stable_regex(pattern: str) -> bool:
    """Conservatively recognize regular expressions without contextual assertions.

    For these expressions, a scan before EOF can change after appending text
    only if the old suffix is itself a full or partial match. Assertions,
    backreferences, recursion, fuzzy matching, and unknown extensions fall back
    to a full parse. This is an optimization gate, never a grammar restriction.
    """
    index, in_class = 0, False
    while index < len(pattern):
        char = pattern[index]
        if char == "\\":
            index += 1
            if index >= len(pattern):
                return False
            escaped = pattern[index]
            if escaped.isalnum() and escaped not in "nrtfvdswDSWuUx":
                return False
            if escaped in "uUx":
                width = {"u": 4, "U": 8, "x": 2}[escaped]
                digits = pattern[index + 1:index + 1 + width]
                if len(digits) != width or any(c not in "0123456789abcdefABCDEF" for c in digits):
                    return False
                index += width
        elif in_class:
            if char == "[":  # Nested sets/POSIX classes need their own proof.
                return False
            if char == "]":
                in_class = False
        elif char == "[":
            in_class = True
        elif char in "^$":
            return False
        elif pattern.startswith("(?", index):
            # Noncapturing groups and scoped ordinary flags do not inspect text
            # outside the matched span; all other extension groups are rejected.
            group = re.match(r"\(\?(?::|[aimsu]+(?:-[ims]+)?:)", pattern[index:])
            if group is None:
                return False
            index += len(group.group()) - 1
        elif char == "{":
            quantifier = re.match(r"\{[0-9]+(?:,[0-9]*)?\}", pattern[index:])
            if quantifier is None:
                return False
            index += len(quantifier.group()) - 1
        index += 1
    return not in_class


class GrammarFrontend:
    """A pinned compiler API, shared by complete and speculative parsing."""
    def __init__(self, grammar: str, *, max_states: int = 250000, max_alternatives: int = 1024):
        self.grammar = grammar
        self.bindings = {}
        self.max_states, self.max_alternatives = max_states, max_alternatives
        self.parser = Lark(grammar, parser="earley", lexer="dynamic", ambiguity="forest",
                           keep_all_tokens=True, propagate_positions=True, start="start")
        if self.parser.lexer_conf.ignore:
            raise RuleDefinitionError("Use explicit lexical separators; ignored tokens hide attribute boundaries")
        self.rules = tuple(self.parser.rules)
        self.by_origin = defaultdict(list)
        for index, rule in enumerate(self.rules):
            self.by_origin[str(rule.origin.name)].append(index)
        # Aliases are public AST node names too. Keep source productions in the
        # inventory so their structural status must still be declared explicitly.
        self.productions = frozenset(
            name for rule in self.rules
            for name in (str(rule.origin.name), str(rule.alias or rule.origin.name))
            if not name.startswith("__"))
        self.trivia = frozenset(name for line in re.findall(r"(?m)^//\s*@trivia\s+(.+)$", grammar) for name in line.split())
        # Lark expands trivia repetition into helper rules (for example
        # __gap_star_0). Waiting until the outer gap completes is too late:
        # COMMENT + WS and LINE_COMMENT can already create 2**N child paths in
        # that helper. Erase only helpers unreachable from meaningful rules
        # without crossing an explicitly declared trivia boundary. A helper
        # shared with a semantic production must retain all of its derivations.
        origins = set(self.by_origin)
        generated = {name for name in origins if name.startswith("__")}
        def reachable(roots, boundaries):
            seen, waiting = set(), list(roots)
            while waiting:
                name = waiting.pop()
                if name in seen or name in boundaries:
                    continue
                seen.add(name)
                for index in self.by_origin.get(name, ()):
                    waiting.extend(str(symbol.name) for symbol in self.rules[index].expansion
                                   if not symbol.is_term)
            return seen
        meaningful_helpers = reachable(origins - generated - self.trivia, self.trivia) & generated
        trivia_helpers = reachable(self.trivia, set()) & generated
        self._erased_origins = self.trivia | (trivia_helpers - meaningful_helpers)
        self.terminals = {term.name: term for term in self.parser.terminals}
        self.regexps = {name: regex.compile(term.pattern.to_regexp(), regex.POSIX)
                        for name, term in self.terminals.items()}
        self.incremental_safe = all(_append_stable_regex(term.pattern.to_regexp())
                                    for term in self.terminals.values())
        self._checkpoint_owner = object()

    def _node(self, rule_index, children, text, start, end, source, complete):
        rule = self.rules[rule_index]
        name = str(rule.alias or rule.origin.name)
        # EBNF-generated helper productions must not become a second public
        # binding vocabulary. Their children belong to the source production.
        flat = tuple(grandchild for child in children if child.production not in self.trivia for grandchild in
                     (child.children if child.production.startswith("__") else (child,)))
        return SyntaxNode(name, text[start:end], flat, start, end, complete, source, self.bindings.get(name, {}))

    @lru_cache(maxsize=256)
    def complete(self, text: str, source: str = "") -> SyntaxFrontier:
        try:
            tree = self.parser.parse(text)
        except UnexpectedInput:
            return SyntaxFrontier(())

        memo, active = {}, set()

        def expand(value):
            if value is None:
                return ((),)
            identity = id(value)
            if identity in memo:
                return memo[identity]
            if identity in active:
                return ()
            if hasattr(value, "token"):
                token = value.token
                return ((SyntaxNode(token.type, str(token), (), token.start_pos or 0,
                                    token.end_pos or 0, True, source),),)
            active.add(identity)
            if hasattr(value, "is_intermediate"):
                name = None if value.is_intermediate else str(value.s.name)
                if name in self.trivia:
                    result = ((),)
                else:
                    # Each packed alternative can have a different alias. Wrap
                    # it before deduplication; assigning the symbol's origin to
                    # all alternatives would silently lose semantic bindings.
                    paths = set()
                    for packed in value.children:
                        alias = str(packed.rule.alias or name) if name is not None else None
                        for path in expand(packed):
                            if name is None or name.startswith("__"):
                                paths.add(path)
                            else:
                                paths.add((SyntaxNode(alias, text[value.start:value.end], path,
                                    value.start, value.end, True, source, self.bindings.get(alias, {})),))
                    if len(paths) > self.max_alternatives:
                        raise ParseResourceError("Complete parse exceeds its meaningful ambiguity budget")
                    result = tuple(paths)
            else:
                paths = {()}
                for child in value.children:
                    paths = {prefix + suffix for prefix in paths for suffix in expand(child)}
                    if len(paths) > self.max_alternatives:
                        raise ParseResourceError("Complete parse exceeds its meaningful ambiguity budget")
                result = tuple(paths)
            active.remove(identity)
            memo[identity] = result
            return result

        roots = tuple(path[0] for path in expand(tree) if path)
        return SyntaxFrontier(roots, bool(roots))

    def prefix(self, text: str, source: str = "", *, previous: SyntaxFrontier | None = None) -> SyntaxFrontier:
        """Extend a selected immutable frontier, or parse independently.

        Passing a speculative candidate's frontier never commits it elsewhere.
        A rewrite, another compiler/source, or an unproven regex uses the exact
        full-prefix path instead of approximating the set of viable trees.
        """
        prior = previous.checkpoint if previous is not None else None
        if (prior is not None and prior.owner is self._checkpoint_owner and prior.source == source
                and text.startswith(prior.text)):
            if text == prior.text:
                return previous
            if self.incremental_safe:
                return self._prefix(text, source, prior)
        return self._full_prefix(text, source)

    @lru_cache(maxsize=32)
    def _full_prefix(self, text: str, source: str) -> SyntaxFrontier:
        return self._prefix(text, source)

    def _prefix(self, text: str, source: str, prior: PrefixCheckpoint | None = None) -> SyntaxFrontier:
        """Build the viable frontier, including a token still being spelled.

        Each chart entry stores alternative child paths. The caller evaluates
        all resulting partial attribute trees existentially: reject only when
        every viable derivation conflicts. Completed files are separately cached.
        """
        size = len(text)
        restart = prior.restart if prior is not None else 0
        # Earlier columns are immutable and shared. Only the boundary and suffix
        # are copied, so a rejected token cannot alter its parent's graph.
        charts = (list(prior.charts[:restart]) if prior is not None else [])
        charts += [defaultdict(set) for _ in range(size + 1 - restart)]
        queues = [deque() for _ in range(size + 1)]
        counts = prior.cumulative_states[restart - 1] if prior is not None and restart else 0
        started = time.monotonic()
        incomplete = []
        scans = [edge for edge in prior.scans if edge[0] < restart] if prior is not None else []
        next_restart = size

        def canonical_path(key, path):
            if str(self.rules[key[0]].origin.name) in self._erased_origins:
                return ()
            return tuple(child for child in path if child.production not in self.trivia)

        def add(position, key, path):
            nonlocal counts
            path = canonical_path(key, path)
            if path not in charts[position][key]:
                charts[position][key].add(path)
                queues[position].append((key, path))
                counts += 1
                if counts > self.max_states:
                    raise ParseResourceError("Prefix parser exceeded its Earley state budget")
                if counts % 2048 == 0 and time.monotonic() - started > 15:
                    raise ParseResourceError("Prefix parser exceeded its per-probe time budget")

        if prior is None:
            for rule in self.by_origin["start"]:
                add(0, (rule, 0, 0), ())
        else:
            for key, paths in prior.charts[restart].items():
                for path in paths:
                    add(restart, key, path)
            for _, end, key, path in scans:
                if end > restart:
                    add(end, key, path)
        for pos in range(restart, size + 1):
            while queues[pos]:
                (index, dot, origin), path = queues[pos].popleft()
                rule = self.rules[index]
                if dot == len(rule.expansion):
                    node = self._node(index, path, text, origin, pos, source, True)
                    # Snapshot iteration is necessary for nullable completion:
                    # advancing a waiting item can add another item in this set.
                    for (pi, pd, ps), parents in tuple(charts[origin].items()):
                        expansion = self.rules[pi].expansion
                        if pd < len(expansion) and not expansion[pd].is_term and expansion[pd].name == rule.origin.name:
                            for parent in tuple(parents):
                                add(pos, (pi, pd + 1, ps), parent + (node,))
                    continue
                symbol = rule.expansion[dot]
                if not symbol.is_term:
                    for predicted in self.by_origin[str(symbol.name)]:
                        add(pos, (predicted, 0, pos), ())
                    # A nullable child may have completed before this newly
                    # arrived parent. Include it instead of depending on order.
                    for (ci, cd, cs), paths in tuple(charts[pos].items()):
                        child_rule = self.rules[ci]
                        if cs == pos and cd == len(child_rule.expansion) and child_rule.origin.name == symbol.name:
                            for child in tuple(paths):
                                node = self._node(ci, child, text, pos, pos, source, True)
                                add(pos, (index, dot + 1, origin), path + (node,))
                    continue
                name = str(symbol.name)
                pattern = self.regexps[name]
                match = pattern.match(text, pos)
                if match and match.end() > pos:
                    end = match.end()
                    token = SyntaxNode(name, text[pos:end], (), pos, end, True, source)
                    next_key, next_path = (index, dot + 1, origin), path + (token,)
                    next_path = canonical_path(next_key, next_path)
                    scans.append((pos, end, next_key, next_path))
                    add(end, next_key, next_path)
                # A literal can be unfinished ("syst"); an identifier matching
                # at EOF can also grow ("B.im" -> "B.impl"). Preserve that
                # alternative until a real separator makes its value immutable.
                tail = pattern.fullmatch(text[pos:], partial=True)
                if tail is not None and (tail.partial or self.terminals[name].pattern.type == "re"):
                    next_restart = min(next_restart, pos)
                    child = SyntaxNode(name, text[pos:], (), pos, size, False, source)
                    key = (index, dot, origin)
                    incomplete.append((key, canonical_path(key, path + ((child,) if pos < size else ()))))
                elif pos == size:
                    incomplete.append(((index, dot, origin), path))

        roots = set()
        for (index, dot, origin), paths in charts[size].items():
            rule = self.rules[index]
            if origin == 0 and rule.origin.name == "start" and dot == len(rule.expansion):
                roots.update(self._node(index, path, text, 0, size, source, True) for path in paths)
        accepting = bool(roots)

        wrapped = set()
        def wrap(key, path, active):
            path = canonical_path(key, path)
            signature = (key, path)
            if signature in wrapped:
                return
            wrapped.add(signature)
            index, dot, origin = key
            rule = self.rules[index]
            marker = (index, origin)
            if marker in active:
                # Left-recursive prediction at one offset consumes no text; its
                # productive paths are represented by the advanced chart items.
                return
            node = self._node(index, path, text, origin, size, source, False)
            if rule.origin.name == "start" and origin == 0:
                roots.add(node)
                if len(roots) > self.max_alternatives:
                    raise ParseResourceError("Prefix parser exceeded its viable alternative budget")
                return
            for (pi, pd, ps), parents in tuple(charts[origin].items()):
                expansion = self.rules[pi].expansion
                if pd < len(expansion) and not expansion[pd].is_term and expansion[pd].name == rule.origin.name:
                    for parent in parents:
                        # Empty predictions add no source facts. Omitting them
                        # merges equivalent empty branches without losing text.
                        child = (node,) if node.text or node.children else ()
                        wrap((pi, pd, ps), parent + child, active | {marker})

        for key, path in set(incomplete):
            wrap(key, path, frozenset())
        frozen = tuple(charts[:restart]) + tuple(MappingProxyType({key: frozenset(paths) for key, paths in column.items()})
                                                 for column in charts[restart:])
        cumulative = list(prior.cumulative_states[:restart]) if prior is not None else []
        total = cumulative[-1] if cumulative else 0
        for column in frozen[restart:]:
            total += sum(len(paths) for paths in column.values())
            cumulative.append(total)
        checkpoint = PrefixCheckpoint(self._checkpoint_owner, text, source, frozen, tuple(scans), tuple(cumulative), next_restart)
        return SyntaxFrontier(tuple(sorted(roots, key=lambda node: (not node.complete, repr(node)))), accepting,
                              counts, checkpoint, restart)
