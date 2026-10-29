"""Joint AADL prefix checking with deferred, typed definition obligations.

Candidate probes clone their llguidance checkpoint and replay captured semantic
events into a fresh model. Rejected candidates cannot leak names or obligations.
This package defines a bounded profile, not all of AADL and not a proof of
natural-language requirement fidelity.
"""
from __future__ import annotations

from dataclasses import dataclass, field
import re

from aadl_single_agent.rules import PrefixState, ProbeResult, ValidationReport


class Token(str):
    """Package-local lexeme; model token IDs never enter semantic actions."""
    def __new__(cls, kind, value):
        result = str.__new__(cls, value)
        result.type = kind
        return result


_KEYWORDS = {}  # Filled from this package grammar when an engine is created.
_PUNCT = {"::": "DCOLON", ":": "COLON", ".": "DOT", ";": "SEMI", ",": "COMMA", "=>": "ASSIGN", "->": "ARROW", "<->": "BIARROW", "{": "LBRACE", "}": "RBRACE"}
_IDENT = re.compile(r"[A-Za-z](?:_?[A-Za-z0-9])*")
_REFERENCE = re.compile(_IDENT.pattern + r"(?:::" + _IDENT.pattern + r")*(?:\." + _IDENT.pattern + r")?")
_SECTIONS = {"features", "subcomponents", "connections", "properties", "end"}
_KINDS = {"data port", "event port", "event data port"}


class _Invalid(ValueError):
    pass


class _NeedMore(Exception):
    pass


def _scan(text: str, reserved: set[str], final: bool = False):
    """Tokenize only unambiguously complete lexemes.

    An identifier at a chunk boundary is NOT a completed identifier: ``Pay``
    may become ``Payload``. Quotes, a single colon and comment openers similarly
    require lookahead. Tokenizer chunks are arbitrary strings, not AADL tokens.
    """
    result, i = [], 0
    while i < len(text):
        ch = text[i]
        if ch in " \t\r\n":
            i += 1
            continue
        if text.startswith("--", i):
            # Both CR and LF end an AADL line comment. Treating CR-only input
            # as one long comment could hide real declarations from semantics.
            ending = re.search(r"[\r\n]", text[i + 2:])
            i = len(text) if ending is None else i + 3 + ending.start()
            continue
        start = i
        if ch.isascii() and ch.isalpha():
            i += 1
            while i < len(text) and text[i].isascii() and (text[i].isalnum() or text[i] == "_"):
                i += 1
            word = text[start:i]
            if i == len(text) and not final:
                return result, ("word", word)
            if not _IDENT.fullmatch(word):
                raise _Invalid(f"Invalid identifier: {word}")
            lowered = word.casefold()
            if lowered in reserved and lowered not in _KEYWORDS:
                raise _Invalid(f"Unsupported or reserved keyword: {word}")
            result.append(Token(_KEYWORDS.get(lowered, "IDENT"), word))
            continue
        if ch == '"':
            i += 1
            while i < len(text):
                if text[i] != '"':
                    i += 1
                elif i + 1 < len(text) and text[i + 1] == '"':
                    i += 2
                else:
                    i += 1
                    if i == len(text) and not final:
                        return result, ("string", text[start:i])
                    result.append(Token("STRING", text[start:i]))
                    break
            else:
                if final:
                    raise _Invalid("Unterminated AADL string")
                return result, ("string", text[start:])
            continue
        if ch.isascii() and (ch.isdigit() or ch in "+-" and i + 1 < len(text) and text[i + 1].isdigit()):
            i += 1
            while i < len(text) and text[i].isascii() and text[i].isdigit():
                i += 1
            if i == len(text) and not final:
                return result, ("integer", text[start:i])
            result.append(Token("INT", text[start:i]))
            continue
        remaining = text[i:]
        # A final '-' might open a comment on the next chunk, even where an
        # arithmetic value or connection arrow would not be syntactically legal.
        if not final and remaining in {":", "=", "-", "+", "<", "<-"}:
            return result, ("operator", remaining)
        match = next((p for p in ("<->", "::", "=>", "->", ":", ".", ";", ",", "{", "}") if text.startswith(p, i)), None)
        if match is None:
            raise _Invalid(f"Unsupported character or operator near {text[i:i+16]!r}")
        result.append(Token(_PUNCT[match], match))
        i += len(match)
    return result, None


@dataclass
class _Declaration:
    name: str
    category: str
    implementation: bool
    closed: bool = False
    ports: dict = field(default_factory=dict)
    children: dict = field(default_factory=dict)
    connections: list = field(default_factory=list)
    properties: list = field(default_factory=list)
    names: set = field(default_factory=set)


class _Replay:
    """AADL semantic model helpers and the unfinished-prefix context adapter."""
    def __init__(self, tokens, rules):
        self.tokens, self.rules, self.i = tokens, rules, 0
        self.package, self.closed, self.current = "", False, None
        self.declarations = {}
        self.closing_expected, self.closing_start = None, 0
        # Prefix context distinguishes a package END from a component END.
        # The latter may legally precede definitions of referenced classifiers.
        self.context = "package_header"
        self.package_close_started = False

    def take(self):
        if self.i >= len(self.tokens):
            raise _NeedMore
        value = str(self.tokens[self.i])
        self.i += 1
        return value

    def peek(self):
        if self.i >= len(self.tokens):
            raise _NeedMore
        return str(self.tokens[self.i]).casefold()

    def name(self, qualified=False, implementation=False):
        value = self.take()
        while qualified and self.i < len(self.tokens) and str(self.tokens[self.i]) == "::":
            self.take()
            value += "::" + self.take()
        if implementation:
            self.take()  # DOT is guaranteed by the grammar.
            value += "." + self.take()
        if any(part.casefold() in self.rules["reserved_words"] for part in re.split(r"::|\.", value)):
            raise _Invalid(f"Reserved keyword cannot be a name: {value}")
        return value.casefold()

    def category(self):
        first = self.take().casefold()
        choices = [item for item in self.rules["categories"] if item.startswith(first + " ")]
        for choice in choices:
            suffix = choice.split()[1:]
            available = [str(t).casefold() for t in self.tokens[self.i:self.i + len(suffix)]]
            if available == suffix:
                self.i += len(suffix)
                return choice
        return first

    def statement(self):
        start, depth = self.i, 0
        while self.i < len(self.tokens):
            value = self.take()
            depth += (value == "{") - (value == "}")
            if value == ";" and depth == 0:
                return [str(t) for t in self.tokens[start:self.i - 1]]
        raise _NeedMore

    @staticmethod
    def _join(values):
        return "".join(values).casefold()

    def _unique(self, name):
        if name in self.current.names:
            raise _Invalid(f"Duplicate local name {name} in {self.current.name}")
        self.current.names.add(name)

    def _property(self, tokens, target=None):
        marker = tokens.index("=>")
        name = self._join(tokens[:marker])
        value = tokens[marker + 1:]
        applies = next((i for i, item in enumerate(value) if item.casefold() == "applies"), None)
        targets = [target] if target else []
        if applies is not None:
            if target:
                raise _Invalid("Inline properties cannot contain applies to")
            paths, current = [], []
            for item in value[applies + 2:]:
                if item == ",":
                    paths.append(self._join(current)); current = []
                else:
                    current.append(item)
            targets = paths + [self._join(current)]
            value = value[:applies]
        self.current.properties.append((name, value, targets))

    def _inline(self, tokens, target):
        if "{" not in tokens:
            return tokens
        pos = tokens.index("{")
        items, current = tokens[pos + 1:-1], []
        for item in items:
            if item == ";":
                self._property(current, target); current = []
            else:
                current.append(item)
        return tokens[:pos]

    def _member(self, section, tokens):
        if tokens == ["none"] or [v.casefold() for v in tokens] == ["none"]:
            return
        if section == "properties":
            self._property(tokens)
            return
        name = tokens[0].casefold()
        if name in self.rules["reserved_words"]:
            raise _Invalid(f"Reserved keyword cannot be a member name: {name}")
        self._unique(name)
        if section == "features":
            tokens = self._inline(tokens, name)
            end = next(i for i, item in enumerate(tokens) if item.casefold() == "port")
            words = [s.casefold() for s in tokens[2:end]]
            direction = "in out" if words[:2] == ["in", "out"] else words[0]
            kind = " ".join(words[2:] if direction == "in out" else words[1:]) + " port"
            classifier = self._join(tokens[end + 1:]) or None
            self.current.ports[name] = {"direction": direction, "kind": kind, "classifier": classifier}
        elif section == "subcomponents":
            tokens = self._inline(tokens, name)
            category = next((item for item in sorted(self.rules["categories"], key=len, reverse=True)
                             if [v.casefold() for v in tokens[2:2 + len(item.split())]] == item.split()), None)
            if category is None:
                raise _Invalid("Unknown subcomponent category")
            self.current.children[name] = {"category": category, "classifier": self._join(tokens[2 + len(category.split()):])}
        elif section == "connections":
            pos = next(i for i, item in enumerate(tokens) if item in {"->", "<->"})
            self.current.connections.append((name, self._join(tokens[3:pos]), self._join(tokens[pos + 1:]), tokens[pos] == "<->"))

    def check_closing_prefix(self, tail):
        """Reject an impossible end name before its terminating semicolon.

        Only END enters this context. Ordinary identifiers, including unresolved
        references, remain unconstrained by this check. Completed identifier
        tokens must match exactly: after ``end Ro ``, whitespace has made it
        impossible to extend ``Ro`` into ``Root``. An unfinished tail ``Ro``
        instead needs only to be a prefix of the expected identifier.
        """
        if self.closing_expected is None:
            return
        expected, _ = _scan(self.closing_expected, set(), final=True)
        consumed = self.tokens[self.closing_start:]
        if len(consumed) > len(expected) or any(
                actual.type != wanted.type or str(actual).casefold() != str(wanted).casefold()
                for actual, wanted in zip(consumed, expected)):
            raise _Invalid(f"End-name prefix cannot complete to {self.closing_expected}")
        # A lone '-' can still become an ignored line comment at any boundary.
        # _scan already excludes full comments and strings from name tokens.
        if tail is None or tail == ("operator", "-"):
            return
        kind, value = tail
        following = expected[len(consumed)] if len(consumed) < len(expected) else None
        if following is None or not str(following).casefold().startswith(value.casefold()) or (
                kind == "word" and following.type != "IDENT"):
            raise _Invalid(f"End-name prefix cannot complete to {self.closing_expected}")

    def run(self):
        try:
            self.take()  # package
            self.package = self.name(qualified=True)
            self.take()  # public
            while True:
                self.context = "package_members"
                next_word = self.peek()
                if next_word == "with":
                    self.context = "import"
                    self.take()
                    imported = self.statement()
                    for name in self._join(imported).split(","):
                        if name not in self.rules["property_sets"]:
                            raise _Invalid(f"External package {name} has no actual library index in v1")
                    continue
                if next_word == "end":
                    self.context = "package_end"
                    self.take()
                    self.closing_expected, self.closing_start = self.package, self.i
                    closing = self.name(qualified=True)
                    self.take()  # semicolon
                    if closing != self.package:
                        raise _Invalid(f"Package end name {closing} does not match {self.package}")
                    self.closing_expected = None
                    self.closed = True
                    return self
                self.context = "declaration_header"
                category = self.category()
                implementation = self.peek() == "implementation"
                if implementation:
                    self.take()
                name = self.name(implementation=implementation)
                if name in self.declarations:
                    raise _Invalid(f"Duplicate classifier {name}")
                self.current = _Declaration(name, category, implementation)
                self.declarations[name] = self.current
                section = None
                while True:
                    self.context = "declaration_body"
                    word = self.peek()
                    if word == "end":
                        self.context = "declaration_end"
                        self.take()
                        self.closing_expected, self.closing_start = name, self.i
                        closing = self.name(implementation=implementation)
                        self.take()
                        if closing != name:
                            raise _Invalid(f"End name {closing} does not match {name}")
                        self.closing_expected = None
                        self.current.closed = True
                        break
                    if word in _SECTIONS:
                        section = self.take().casefold()
                    else:
                        self._member(section, self.statement())
        except _NeedMore:
            return self


class _Semantics:
    def __init__(self, replay, rules, plan=None):
        self.model, self.rules = replay, rules
        self.plan = plan
        self.decls, self.pending, self.requirements = replay.declarations, set(), {}
        self.interfaces, self.edges = {}, []

    def local(self, reference):
        if not _REFERENCE.fullmatch(reference) or any(
                part in self.rules["reserved_words"] for part in re.split(r"::|\.", reference)):
            raise _Invalid(f"Invalid classifier reference: {reference}")
        if "::" in reference:
            package, reference = reference.rsplit("::", 1)
            if package != self.model.package:
                raise _Invalid(f"External classifier {package}::{reference} has no actual library index in v1")
        return reference

    def require(self, reference, category):
        key = self.local(reference)
        if self.plan is not None:
            planned = self.plan.get(key)
            if planned is None or planned["category"] != category:
                raise _Invalid(f"Reference {key} is outside the declared plan or has the wrong category")
        previous = self.requirements.get(key)
        if previous and previous != category:
            raise _Invalid(f"Conflicting category obligations for {key}: {previous} versus {category}")
        self.requirements[key] = category
        declaration = self.decls.get(key)
        if declaration and declaration.category != category:
            raise _Invalid(f"Classifier {key} must be {category}, found {declaration.category}")
        if declaration is None or not declaration.closed:
            self.pending.add(f"define {category} {key}")
        if "." in key:
            self.require(key.split(".", 1)[0], category)
        return key

    def interface(self, type_name, port, directions=None):
        key = (type_name, port)
        current = self.interfaces.setdefault(key, {"directions": {"in", "out", "in out"}, "kinds": set(_KINDS)})
        if directions:
            current["directions"] &= directions
        declaration = self.decls.get(type_name)
        if self.plan is not None:
            planned = self.plan.get(type_name, {}).get("ports", {}).get(port)
            if planned is None:
                raise _Invalid(f"Interface {type_name}.{port} is outside the declared plan")
            current["directions"] &= {planned["direction"]}
            current["kinds"] &= {planned["kind"]}
        if declaration:
            feature = declaration.ports.get(port)
            if feature:
                current["directions"] &= {feature["direction"]}
                current["kinds"] &= {feature["kind"]}
            elif declaration.closed:
                raise _Invalid(f"Missing required interface {type_name}.{port}")
            else:
                self.pending.add(f"interface {type_name}.{port}")
        else:
            self.pending.add(f"interface {type_name}.{port}")
        if not current["directions"] or not current["kinds"]:
            raise _Invalid(f"Conflicting interface obligations for {type_name}.{port}")
        return key

    def endpoint(self, owner, value, source, bidirectional):
        parts = value.split(".")
        boundary = len(parts) == 1
        if boundary:
            type_name, port = owner.name.split(".", 1)[0], parts[0]
        else:
            child = owner.children.get(parts[0])
            if child is None:
                raise _Invalid(f"Unknown child instance {parts[0]} in endpoint {value}")
            type_name, port = self.local(child["classifier"]).split(".", 1)[0], parts[1]
            if child["category"] not in self.rules["port_categories"]:
                raise _Invalid(f"{child['category']} cannot provide ordinary port {value}")
        directions = {"in out"} if bidirectional else (
            {"in", "in out"} if source == boundary else {"out", "in out"})
        return self.interface(type_name, port, directions), boundary

    def _feature(self, key):
        decl = self.decls.get(key[0])
        actual = decl.ports.get(key[1]) if decl else None
        # A planned interface can constrain a connection, but only the actual
        # interface discharges the pending obligation in interface().
        return actual or (self.plan.get(key[0], {}).get("ports", {}).get(key[1]) if self.plan is not None else None)

    def check_connections(self):
        destinations = self.rules["port_destinations"]
        # Propagate finite interface domains until stable. Two unknown ports
        # sharing a future declaration must satisfy ALL uses, not separate guesses.
        changed = True
        while changed:
            changed = False
            for left, right, bidirectional in self.edges:
                a, b = self.interfaces[left]["kinds"], self.interfaces[right]["kinds"]
                pairs = {(x, y) for x in a for y in b if y in destinations[x] and (not bidirectional or x == y)}
                if not pairs:
                    raise _Invalid(f"Incompatible port kinds between {left} and {right}")
                new_a, new_b = {x for x, _ in pairs}, {y for _, y in pairs}
                changed |= new_a != a or new_b != b
                self.interfaces[left]["kinds"] &= new_a
                self.interfaces[right]["kinds"] &= new_b
        for left, right, bidirectional in self.edges:
            a, b = self._feature(left), self._feature(right)
            if not a or not b or "event port" in {a["kind"], b["kind"]}:
                continue
            if a["classifier"] and b["classifier"]:
                x, y = self.local(a["classifier"]), self.local(b["classifier"])
                if x != y and not (not bidirectional and "." in x and "." not in y and x.split(".", 1)[0] == y):
                    raise _Invalid(f"Incompatible data classifiers {x} and {y}")

    def check_properties(self, decl):
        seen = set()
        for name, value, targets in decl.properties:
            definitions = self.rules["builtin_properties"]
            key = name if "::" in name else next((k for k in definitions if k.rsplit("::", 1)[1] == name), name)
            definition = definitions.get(key)
            if definition is None:
                raise _Invalid(f"Unsupported property {name}")
            if definition["kind"] == "string":
                if len(value) != 1 or not value[0].startswith('"'):
                    raise _Invalid(f"{name} requires an AADL string")
            else:
                if len(value) != 2 or not re.fullmatch(r"[+-]?[0-9]+", value[0]):
                    raise _Invalid(f"{name} requires an integer with an explicit size unit")
                if int(value[0]) < definition.get("minimum", 0):
                    raise _Invalid(f"{name} is below its minimum")
                if value[1].casefold() not in {u.casefold() for u in definition["units"]}:
                    raise _Invalid(f"Unsupported size unit {value[1]} for {name}")
            for target in targets or [""]:
                identity = key, target
                if identity in seen:
                    raise _Invalid(f"Duplicate property {key} on {target or decl.name}")
                seen.add(identity)
                if not target:
                    category = decl.category
                elif target in decl.ports:
                    category = decl.ports[target]["kind"]
                elif target in decl.children:
                    category = decl.children[target]["category"]
                else:
                    # An implementation may apply a property to an inherited
                    # boundary port whose type will be declared later.
                    if decl.implementation:
                        parts = target.split(".")
                        if len(parts) == 1:
                            type_name, feature = decl.name.split(".", 1)[0], parts[0]
                        else:
                            child = decl.children.get(parts[0])
                            if child is None or child["category"] not in self.rules["port_categories"]:
                                raise _Invalid(f"Unknown child-port property target {target}")
                            type_name = self.local(child["classifier"]).split(".", 1)[0]
                            feature = parts[1]
                        port = self.interface(type_name, feature)
                        actual = self._feature(port)
                        if actual is None:
                            allowed = definition["applies_to"]
                            if not any(kind in allowed or "port" in allowed or "feature" in allowed
                                       for kind in self.interfaces[port]["kinds"]):
                                raise _Invalid(f"Property {name} cannot apply to the future port {target}")
                            continue
                        category = actual["kind"]
                    else:
                        raise _Invalid(f"Unknown or unsupported property target {target}")
                allowed = definition["applies_to"]
                if category not in allowed and not (category in _KINDS and ("port" in allowed or "feature" in allowed)):
                    raise _Invalid(f"Property {name} does not apply to {category}")

    def check_cycles(self):
        edges = {key: [self.local(c["classifier"]) for c in d.children.values() if "." in c["classifier"]]
                 for key, d in self.decls.items() if d.implementation}
        active, done = set(), set()
        def visit(node):
            if node in active:
                raise _Invalid(f"Implementation containment cycle through {node}")
            if node in done:
                return
            active.add(node)
            for child in edges.get(node, []):
                visit(child)
            active.remove(node); done.add(node)
        for node in edges:
            visit(node)

    def run(self):
        for name, decl in self.decls.items():
            if self.plan is not None:
                planned = self.plan.get(name)
                if planned is None or planned["category"] != decl.category or planned["implementation"] != decl.implementation:
                    raise _Invalid(f"Declaration {name} differs from the fixed declaration plan")
                for port, actual in decl.ports.items():
                    expected = planned["ports"].get(port)
                    if expected is None or any(actual[key] != expected[key] for key in ("direction", "kind")):
                        raise _Invalid(f"Interface {name}.{port} differs from the fixed declaration plan")
                    left = self.local(actual["classifier"]) if actual["classifier"] else None
                    right = self.local(expected["classifier"]) if expected["classifier"] else None
                    if left != right:
                        raise _Invalid(f"Classifier of interface {name}.{port} differs from the fixed declaration plan")
                if decl.closed and set(decl.ports) != set(planned["ports"]):
                    raise _Invalid(f"Declaration {name} omits planned interfaces")
            if not decl.closed:
                self.pending.add(f"finish declaration {name}")
            if decl.implementation:
                base = name.split(".", 1)[0]
                self.require(base, decl.category)
                parent = self.decls.get(base)
                if parent and set(parent.ports) & decl.names:
                    raise _Invalid(f"Implementation {name} duplicates an inherited feature name")
            if decl.ports and decl.category not in self.rules["port_categories"]:
                raise _Invalid(f"{decl.category} cannot declare ordinary ports")
            for port in decl.ports.values():
                if port["kind"] == "event port" and port["classifier"]:
                    raise _Invalid("An event port cannot have a data classifier")
                if port["classifier"]:
                    self.require(port["classifier"], "data")
            for child in decl.children.values():
                if child["category"] not in self.rules["containment"][decl.category]:
                    raise _Invalid(f"{decl.category} cannot contain {child['category']}")
                self.require(child["classifier"], child["category"])
            if decl.connections and decl.category not in self.rules["port_connection_categories"]:
                raise _Invalid(f"{decl.category} cannot declare port connections")
            for _, source, target, bidirectional in decl.connections:
                left, lb = self.endpoint(decl, source, True, bidirectional)
                right, rb = self.endpoint(decl, target, False, bidirectional)
                if lb and rb:
                    raise _Invalid("Direct boundary-to-boundary connections are outside this profile")
                self.edges.append((left, right, bidirectional))
            self.check_properties(decl)
        self.check_connections()
        self.check_cycles()
        if self.plan is not None:
            for name in self.plan:
                if name not in self.decls or not self.decls[name].closed:
                    self.pending.add(f"fulfil planned definition {name}")
        if (self.model.closed or self.model.package_close_started) and self.pending:
            raise _Invalid("Package closure leaves obligations: " + "; ".join(sorted(self.pending)))
        return sorted(self.pending)


def _event_tokens(payload, engine):
    tokens, _ = _scan(payload.decode("utf-8"), engine._reserved, final=True)
    return tokens


def package_header(model, payload, engine):
    fragment = _Replay(_event_tokens(payload, engine), model.rules)
    fragment.take()
    model.package = fragment.name(qualified=True)


def imports(model, payload, engine):
    values = [str(token) for token in _event_tokens(payload, engine)]
    for name in model._join(values[1:]).rstrip(";").split(","):
        if name not in model.rules["property_sets"]:
            raise _Invalid(f"External package {name} has no actual library index in this profile")


def _open(model, payload, engine, implementation):
    fragment = _Replay(_event_tokens(payload, engine), model.rules)
    category = fragment.category()
    if implementation:
        fragment.take()
    name = fragment.name(implementation=implementation)
    if name in model.declarations:
        raise _Invalid(f"Duplicate classifier {name}")
    model.current = _Declaration(name, category, implementation)
    model.declarations[name] = model.current


def component_header(model, payload, engine):
    _open(model, payload, engine, False)


def implementation_header(model, payload, engine):
    _open(model, payload, engine, True)


def _close(model, payload, engine, package=False, implementation=False):
    fragment = _Replay(_event_tokens(payload, engine), model.rules)
    fragment.take()
    name = fragment.name(qualified=package, implementation=implementation)
    expected = model.package if package else model.current.name
    if name != expected:
        raise _Invalid(f"End name {name} does not match {expected}")
    if package:
        model.closed = True
    else:
        model.current.closed = True


def package_end(model, payload, engine):
    _close(model, payload, engine, package=True)


def component_end(model, payload, engine):
    _close(model, payload, engine)


def implementation_end(model, payload, engine):
    _close(model, payload, engine, implementation=True)


def _member_event(model, payload, engine, section):
    tokens = _event_tokens(payload, engine)
    model._member(section, [str(token) for token in tokens[:-1]])


def feature(model, payload, engine):
    _member_event(model, payload, engine, "features")


def subcomponent(model, payload, engine):
    _member_event(model, payload, engine, "subcomponents")


def connection(model, payload, engine):
    _member_event(model, payload, engine, "connections")


def property_event(model, payload, engine):
    _member_event(model, payload, engine, "properties")


def check_prefix(engine, text, model):
    """Captures cannot expose unfinished names; this adapter supplies context.

    Completed captures are the source of the semantic model. Replay supplies
    partial end-name checks and the scope of an impending END keyword. Therefore
    an incomplete classifier such as Pay never becomes a reference obligation.
    """
    tokens, tail = _scan(text, engine._reserved)
    partial = _Replay(tokens, engine.rules).run()
    partial.check_closing_prefix(tail)
    # At a package-member boundary, core-v1's e/en/end can only start END.
    # Waiting for the complete closing name or semicolon admits an irreversible
    # dead prefix: it can add whitespace/comments forever, but can no longer
    # discharge a missing definition. Reject the first exclusive END prefix.
    # If a later package adds a category with the same prefix, keep that valid
    # alternative until it diverges; never apply this rule to a declaration
    # header, member identifier, comment/string, or component-ending clause.
    exclusive_end_prefix = False
    if partial.context == "package_members" and tail and tail[0] == "word":
        word = tail[1].casefold()
        category_starts = {category.split()[0] for category in engine.rules["categories"]}
        exclusive_end_prefix = "end".startswith(word) and not any(
            category.startswith(word) for category in category_starts)
    model.package_close_started = partial.context == "package_end" or exclusive_end_prefix


def check_model(engine, model):
    semantics = _Semantics(model, engine.rules, engine._plan)
    try:
        return semantics.run()
    except _Invalid as exc:
        exc.pending = tuple(sorted(semantics.pending))
        raise


def create_engine(bundle, declarations=None):
    return AadlEngine(bundle, declarations)


def normalize_plan(plan):
    """Convert the package's planning schema to typed definition obligations."""
    declarations = [dict(item, kind="type") for item in plan["component_types"]]
    declarations += [dict(item, kind="implementation", ports=[])
                     for item in plan["implementations"]]
    return declarations


class AadlEngine:
    """Rule-package implementation; the public facade has no AADL branches."""
    def __init__(self, bundle, declarations=None):
        global _KEYWORDS
        self.bundle = bundle
        self.grammar = bundle.grammar
        self.rules = bundle.read_json("semantic_rules.json")
        self._reserved = set(self.rules["reserved_words"])
        # Keyword terminals are declared in the grammar, including extension
        # words such as GROUP. No generic-runtime changes are needed for them.
        _KEYWORDS = {word.casefold(): name for name, word in re.findall(
            r'(?m)^([A-Z_]+):\s*"([A-Za-z]+)"i\s*$', self.grammar)}
        self.declarations = declarations
        self._plan = self._normalize_plan(declarations)
        self._initial = bundle.new_matcher()

    def _normalize_plan(self, declarations):
        if declarations is None:
            return None
        if not isinstance(declarations, list):
            raise ValueError("Declaration plan must be a list")

        def record(value, fields, context):
            # Plans also arrive through the HTTP declaration protocol, bypassing
            # the model's JSON-schema generation. Validate their shape here,
            # before case folding, iteration, or set membership can raise an
            # accidental KeyError/TypeError and become an HTTP 500 response.
            if not isinstance(value, dict):
                raise ValueError(f"{context} must be an object")
            missing = fields - value.keys()
            if missing:
                raise ValueError(f"{context} is missing required fields: {', '.join(sorted(missing))}")
            if value.keys() - fields:
                raise ValueError(f"{context} contains unsupported fields")

        def string_fields(value, fields, context):
            for key in fields:
                if not isinstance(value[key], str):
                    raise ValueError(f"{context}.{key} must be a string")

        plan = {}
        for index, item in enumerate(declarations):
            context = f"Declaration plan item {index}"
            record(item, {"name", "category", "kind", "ports"}, context)
            string_fields(item, ("name", "category", "kind"), context)
            if not isinstance(item["ports"], list):
                raise ValueError(f"{context}.ports must be a list")
            name = item["name"].casefold()
            implementation = item["kind"] == "implementation"
            if item["kind"] not in {"type", "implementation"} or item["category"] not in self.rules["categories"]:
                raise ValueError("Invalid declaration kind/category in plan")
            pieces = name.split(".")
            if len(pieces) != (2 if implementation else 1) or any(
                    not _IDENT.fullmatch(p) or p in self._reserved for p in pieces):
                raise ValueError(f"Invalid planned classifier name {name}")
            if name in plan:
                raise ValueError(f"Duplicate planned classifier {name}")
            ports = {}
            for port_index, port in enumerate(item["ports"]):
                port_context = f"{context}.ports[{port_index}]"
                record(port, {"name", "kind", "direction", "classifier"}, port_context)
                string_fields(port, ("name", "kind", "direction", "classifier"), port_context)
                key = port["name"].casefold()
                kind = port["kind"] if port["kind"].endswith(" port") else port["kind"] + " port"
                if key in ports or not _IDENT.fullmatch(key) or key in self._reserved:
                    raise ValueError(f"Invalid or duplicate planned port {key}")
                if kind not in _KINDS or port["direction"] not in {"in", "out", "in out"}:
                    raise ValueError(f"Invalid planned port signature {key}")
                ports[key] = {"kind": kind, "direction": port["direction"],
                              "classifier": port["classifier"].casefold() or None}
            if ports and (implementation or item["category"] not in self.rules["port_categories"]):
                raise ValueError(f"Planned declaration {name} cannot own ordinary ports")
            plan[name] = {"category": item["category"], "implementation": implementation, "ports": ports}
        for name, item in plan.items():
            if item["implementation"]:
                base = plan.get(name.split(".", 1)[0])
                if not base or base["implementation"] or base["category"] != item["category"]:
                    raise ValueError(f"Planned implementation {name} lacks a matching planned type")
            for port_name, port in item["ports"].items():
                reference = port["classifier"]
                if not reference:
                    continue
                if port["kind"] == "event port":
                    raise ValueError(f"Planned event port {name}.{port_name} cannot have a data classifier")
                # The plan has no package-name field. Resolve its relative key
                # now; when source supplies the package, local() also verifies
                # that any explicit qualifier names that very same package.
                relative = reference.rsplit("::", 1)[-1]
                segments = reference.replace("::", ".").split(".")
                if not _REFERENCE.fullmatch(reference) or any(segment in self._reserved for segment in segments):
                    raise ValueError(f"Invalid planned classifier reference {reference}")
                target = plan.get(relative)
                if target is None or target["category"] != "data":
                    raise ValueError(f"Planned port {name}.{port_name} requires a planned data classifier: {reference}")
        return plan

    def start(self) -> PrefixState:
        return PrefixState(checkpoint=self._initial.deep_copy(), bundle_digest=self.bundle.digest)

    def _inspect(self, matcher, text):
        model = _Replay([], self.rules)
        # llguidance returns ordered, repeated captures; retaining every event
        # matters when multiple declarations use the same captured production.
        for name, payload in matcher.get_captures():
            action = self.bundle.bindings["captures"].get(name)
            if action is None:
                raise _Invalid(f"No semantic action bound to capture {name}")
            getattr(self.bundle.module, action)(model, payload, self)
        getattr(self.bundle.module, self.bundle.bindings["prefix_hook"])(self, text, model)
        pending = getattr(self.bundle.module, self.bundle.bindings["semantic_check"])(self, model)
        return tuple(pending)

    def probe(self, state: PrefixState, piece: str) -> ProbeResult:
        if not isinstance(piece, str):
            raise TypeError("piece must be decoded text, not token IDs or bytes")
        if state.bundle_digest and state.bundle_digest != self.bundle.digest:
            return ProbeResult(False, state, "Checkpoint belongs to a different rule package")
        text = state.text + piece
        matcher = (state.checkpoint or self._initial).deep_copy()
        # Compatibility with callers constructing a plain PrefixState manually.
        prefix = state.text if state.checkpoint is None else ""
        try:
            if not matcher.consume_tokens(list((prefix + piece).encode("utf-8"))):
                return ProbeResult(False, state, "No grammatical continuation: " + matcher.get_error().split("\n", 1)[0])
            pending = self._inspect(matcher, text)
            complete = matcher.is_accepting()
            return ProbeResult(True, PrefixState(text, complete, pending, matcher, self.bundle.digest))
        except (_Invalid, ValueError, UnicodeError) as exc:
            return ProbeResult(False, state, str(exc), tuple(getattr(exc, "pending", ())))

    def commit(self, state, piece):
        result = self.probe(state, piece)
        if not result.allowed:
            raise ValueError(result.reason)
        return result.state

    def validate(self, text: str) -> ValidationReport:
        result = self.probe(self.start(), text)
        if not result.allowed:
            return ValidationReport(False, [result.reason], list(result.pending))
        state = result.state
        if not state.complete:
            return ValidationReport(False, ["Incomplete AADL package or statement"], list(state.pending))
        return ValidationReport(not state.pending, [], list(state.pending))

    def finish(self, state: PrefixState) -> ValidationReport:
        return self.validate(state.text)
