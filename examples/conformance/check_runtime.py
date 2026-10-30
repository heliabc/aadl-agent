"""Offline runtime regressions; these do not claim real-model acceptance.

Run with python examples/conformance/check_runtime.py. No batch generation,
server mutation, test framework, or report files are involved.
"""
from pathlib import Path
import tempfile
from types import SimpleNamespace

from aadl_single_agent.attribute_runtime import AttributeEngine
from aadl_single_agent.attribute_evaluator import AttributeEvaluator, Missing
from aadl_single_agent.decoder import (TokenBridge, JointLogitsProcessor, ConstrainedDecodingError,
                                       RepeatedLineDetector, repetition_stop_configuration)
from aadl_single_agent.server import TransformersRuntime
from aadl_single_agent.project import (parse_project, serialize_project, snapshot_context,
                                       coerce_context, write_project)
from aadl_single_agent.rules import load_bundle, byte_tokenizer
from aadl_single_agent.grammar import GrammarFrontend
from aadl_single_agent.semantic_types import (SemanticReport, RuleResult, Status,
                                               SyntaxNode, ProjectView, RuleDefinitionError, ParseResourceError)
from llguidance import LLMatcher


def check_accepting_word_prefix():
    """An accepting but unclosed word must retain its growing alternative."""
    grammar = 'start: word "\\n"?\nword: /[a-z]+/\n'
    frontend = GrammarFrontend(grammar)

    def word_constraint(ctx):
        word = ctx.node
        status = (Status.SATISFIED if word.text == "ab" else
                  Status.CONFLICT if word.complete else Status.PENDING)
        return RuleResult(status, "word.expected", "Expected word ab")

    bundle = SimpleNamespace(digest="word-prefix-fixture", frontend=frontend,
                             semantics=SimpleNamespace(evaluate=lambda project, **kwargs: SemanticReport(),
                                 HANDLERS={("word", "accepted"): word_constraint},
                                 CONSTRAINTS={"word": ("accepted",)}),
                             new_matcher=lambda: LLMatcher(byte_tokenizer(), grammar, log_level=0))
    engine = AttributeEngine(bundle, project=True)
    state = engine.start()
    for piece in ("AADL-PROJECT 1\nFILE\n|a", "b\nEND_FILE\nEND_PROJECT\n"):
        trial = engine.probe(state, piece)
        assert trial.allowed, trial.reason
        state = trial.state
    assert engine.finish(state).valid
    assert not engine.validate(serialize_project(["a"])).valid


def check_attribute_evaluator():
    """Exercise dependency reuse and branch invalidation, not just final booleans."""
    counts = {"symbols": 0, "target": 0, "input": 0, "final": 0}

    def symbols(ctx):
        counts["symbols"] += 1
        return {node.text.split(":")[0]: {"value": node.text.split(":")[1], "node": node}
                for node in ctx.project.units if node.production == "decl"}

    def target(ctx):
        counts["target"] += 1
        return ctx.lookup("symbols", ctx.node.text)

    def input_value(ctx):
        counts["input"] += 1
        return ctx.inputs["plan"]

    def final_value(ctx):
        counts["final"] += 1
        return ctx.project.final

    def verdict(ctx):
        value = ctx.get(ctx.node, "target")
        return RuleResult(Status.PENDING if isinstance(value, Missing) else Status.SATISFIED, "resolved")

    handlers = {("__project__", "symbols"): symbols, ("ref", "target"): target,
                ("ref", "constraint"): verdict, ("ref", "input"): input_value,
                ("ref", "final"): final_value, ("decl", "text"): lambda ctx: ctx.node.text}
    ref = SyntaxNode("ref", "A", source="ref")
    declaration = SyntaxNode("decl", "A:one", source="A")
    project = ProjectView((ref, declaration))
    evaluator = AttributeEvaluator(handlers, project, constraints={"ref": ("constraint",)},
                                   inputs={"plan": {"names": ["A"]}})
    assert not evaluator.evaluate_constraints().pending
    signature = evaluator.get(ref, "target")
    assert signature["value"] == "one"
    assert evaluator.get(signature["node"], "text") == "A:one"
    assert counts["target"] == 1
    frozen = evaluator.get(ref, "input")
    assert frozen["names"] == ("A",)
    assert not evaluator.get(ref, "final")
    try:
        frozen["names"] = ()
    except TypeError:
        pass
    else:
        raise AssertionError("Attribute values must own immutable nested containers")

    # Adding an unrelated symbol changes Σ but not lookup(Σ, A). The target
    # handler must be reused, while the namespace itself is recomputed once.
    branch = evaluator.fork(ProjectView((SyntaxNode("ref", "A", source="ref"),
        SyntaxNode("decl", "A:one", source="A"), SyntaxNode("decl", "B:two", source="B"))))
    assert branch.get(branch.project.units[0], "target")["value"] == "one"
    assert counts["target"] == 1 and counts["symbols"] == 2
    old_node = branch.get(branch.project.units[0], "target")["node"]
    assert branch.get(old_node, "text") == "A:one"
    saved_metrics = dict(evaluator.metrics)
    changed = evaluator.fork(ProjectView((ref, SyntaxNode("decl", "A:changed", source="A"))),
                             inputs={"plan": {"names": ["B"]}})
    assert changed.get(ref, "target")["value"] == "changed"
    assert changed.get(ref, "input")["names"] == ("B",)
    assert evaluator.get(ref, "target")["value"] == "one"
    assert evaluator.metrics["computed"] == saved_metrics["computed"]
    absent = evaluator.fork(ProjectView((ref,)))
    assert isinstance(absent.get(ref, "target"), Missing)
    assert absent.evaluate_constraints().pending
    restored = absent.fork(project)
    assert restored.get(ref, "target")["value"] == "one"
    closed = evaluator.fork(ProjectView(project.units, final=True))
    assert closed.get(ref, "final") and counts["final"] == 2

    # Inherited attributes track the parent instead of retaining the old scope.
    leaf = SyntaxNode("leaf", "x")
    inherited = {("leaf", "scope"): lambda ctx: ctx.get(ctx.parent(), "scope"),
                 ("scope", "scope"): lambda ctx: ctx.node.text}
    scoped = AttributeEvaluator(inherited, ProjectView((SyntaxNode("scope", "one", (leaf,)),)))
    assert scoped.get(leaf, "scope") == "one"
    moved = scoped.fork(ProjectView((SyntaxNode("scope", "two", (leaf,)),)))
    assert moved.get(leaf, "scope") == "two"

    def must_fail(action, fragment):
        try:
            action()
        except RuleDefinitionError as error:
            assert fragment in str(error), str(error)
        else:
            raise AssertionError("A malformed attribute rule was accepted")

    must_fail(lambda: evaluator.get(ref, "unregistered"), "No attribute handler")
    must_fail(lambda: bool(Missing("symbols", "Unknown")), "explicit")
    cycle = AttributeEvaluator({("ref", "a"): lambda ctx: ctx.get(ctx.node, "b"),
                                ("ref", "b"): lambda ctx: ctx.get(ctx.node, "a")}, ProjectView((ref,)))
    must_fail(lambda: cycle.get(ref, "a"), "dependency cycle")
    must_fail(lambda: cycle.evaluate_constraints(), "No attribute constraints")
    for invalid in (True, (), object()):
        broken = AttributeEvaluator({("ref", "check"): lambda ctx, value=invalid: value},
            ProjectView((ref,)), constraints={"ref": ("check",)})
        must_fail(broken.evaluate_constraints, "Attribute" if type(invalid) is object else "Constraint")
    early = AttributeEvaluator(handlers, ProjectView(()), constraints={"ref": ("constraint",)})
    assert early.evaluate_constraints().pending


def check_trivia_prefix_paths():
    """Equivalent comment spellings must not multiply hidden EBNF paths."""
    lexical = ('gap: (WS | LINE_COMMENT | COMMENT)*\n'
               'WS: /[ \\r\\n]+/\n'
               'LINE_COMMENT: /--[^\\r\\n]*[\\r\\n]/\n'
               'COMMENT: /--[^\\r\\n]*/\n')
    grammar = '// @trivia gap\nstart: "a" gap "b"\n' + lexical
    frontend = GrammarFrontend(grammar)
    small = frontend.prefix('a' + '-- c\n' * 16)
    large = frontend.prefix('a' + '-- c\n' * 100)
    # Previously 16 comments exceeded 250,000 states although only one public
    # AST existed. Bound useful work rather than increasing the resource limit.
    assert small.states < 300 and large.states < 1500
    assert len(large.alternatives) == 1
    text = 'a' + '-- c\n' * 8 + 'b'
    previous = None
    for end in range(len(text) + 1):
        actual = frontend.prefix(text[:end], previous=previous)
        fresh = frontend.prefix(text[:end])
        assert set(actual.alternatives) == set(fresh.alternatives)
        assert actual.complete == fresh.complete
        previous = actual
    assert set(previous.alternatives) == set(frontend.complete(text).alternatives)

    # Lark shares this generated repetition helper between gap and payload.
    # Its tokens are meaningful through payload, so all four derivations stay.
    shared = GrammarFrontend('// @trivia gap\nstart: "a" gap "b" | "c" payload "d"\n'
        + lexical + 'payload: (WS | LINE_COMMENT | COMMENT)*\n')
    source = 'c--x\n--y\nd'
    complete, prefix = shared.complete(source), shared.prefix(source)
    assert len(complete.alternatives) == len(prefix.alternatives) == 4
    assert set(complete.alternatives) == set(prefix.alternatives)


def check_infrastructure_partial_output():
    """Offline protocol fixtures: these do not stand in for real inference."""
    class Row:
        def __init__(self, values):
            self.values = values
        def tolist(self):
            return self.values
    class Matrix:
        shape = (1, 1)
        def __init__(self, values):
            self.values = values
        def __getitem__(self, key):
            return Row(self.values)
        def clone(self):
            return self
    def broken(*args):
        raise ParseResourceError("offline resource-limit fixture")
    engine = SimpleNamespace(start=lambda: SimpleNamespace(text=""), probe=broken)
    processor = JointLogitsProcessor(engine, SimpleNamespace(all_special_ids=[256]), [900], [256],
        syntax_processor=broken, byte_tokenizer=byte_tokenizer())
    for history in ([900], [900, 65]):
        try:
            processor(Matrix(history), Matrix([1.0]))
        except ParseResourceError:
            pass
        else:
            raise AssertionError("Expected explicit offline parser failure")
    # The second callback failed during synchronize; committed bridge history
    # is empty, but the backend actually selected A and must be billed for it.
    assert processor.bridge.token_ids == () and processor.selected_token_ids == (65,)
    runtime = TransformersRuntime.__new__(TransformersRuntime)
    runtime.bundle = SimpleNamespace(digest="rules", runtime_version="4", compiled_grammar_sha256="grammar")
    runtime.model_name = "offline-fixture"
    runtime.model_identity, runtime.tokenizer_identity = {"id": "model"}, {"id": "tokenizer"}
    runtime.byte_tokenizer = byte_tokenizer()
    runtime._eos_ids = lambda: [256]
    arguments = dict(context=SimpleNamespace(sha256="context"), evidence={"kind": "aadl_joint"},
                     joint=processor, prompt_count=10, generated=processor.selected_token_ids,
                     generation_started=None)
    failure = runtime._infrastructure_failure(ParseResourceError("budget"), **arguments)
    assert failure.status_code == 500 and failure.payload["error"]["retryable"] is False
    assert failure.payload["usage"] == {"prompt_tokens": 10, "completion_tokens": 1, "total_tokens": 11}
    assert failure.payload["choices"][0]["message"]["content"] == "A"
    assert failure.payload["choices"][0]["finish_reason"] == "error"
    assert "constraint_failure" not in failure.payload
    # A normal output tensor can include one token beyond the last callback.
    arguments["generated"] = [65, 66, 256]
    timeout = runtime._infrastructure_failure(TimeoutError("deadline"), **arguments)
    assert timeout.status_code == 504 and timeout.payload["usage"]["completion_tokens"] == 3
    assert timeout.payload["choices"][0]["message"]["content"] == "AB"
    arguments["generated"] = [65, 0xe4]
    partial = runtime._infrastructure_failure(TimeoutError("deadline"), **arguments).payload
    assert partial["choices"][0]["message"]["content"] == "A"
    assert partial["partial_utf8_tail_hex"] == "e4"
    arguments.update(joint=None, generated=None)
    unknown = runtime._infrastructure_failure(TimeoutError("schema"), **arguments).payload
    assert "usage" not in unknown and "choices" not in unknown


def check_repeated_line_stop():
    """The stop condition preserves exact text and requires completed lines."""
    detector = RepeatedLineDetector()
    line = "|  -- receive --> child.input;  "
    assert not detector.observe((line + "\n") * 31)
    assert not detector.observe((line + "\n") * 31 + line)
    original = (line + "\n") * 32
    assert detector.observe(original)
    assert detector.termination["kind"] == "repetition"
    assert detector.termination["line"] == line
    assert detector.termination["repeated_lines"] == 32
    assert original == (line + "\n") * 32
    # Empty lines reset a run; changes in trailing whitespace remain distinct.
    assert not detector.observe((line + "\n") * 16 + "\n" + (line + "\n") * 16)
    assert not detector.observe((line + "\n" + line + " \n") * 32)
    assert not detector.observe((line + "\r\n" + line + "\n") * 32)
    assert detector.observe((line + "\r\n") * 32)
    assert detector.observe("|\n" * 32)  # Raw container framing is not stripped.
    assert detector.observe("  \n" * 32)
    assert not detector.observe("\n" * 64)
    assert detector.termination is None
    config = repetition_stop_configuration()
    assert config["threshold"] == 32 and config["scope"] == ["plain", "aadl_joint", "json_schema"]


def check_incremental_frontiers(bundle):
    """Compare every retained tree with a fresh full-prefix parse, not just accept/reject."""
    fixtures = [
        ('start: word ":" word "\\n"?\nword: /[a-z]+/\n', "abc:def\n"),
        # The long literal crosses the short branch's restart position. Both
        # derivations must survive; copying only the restart column loses one.
        ('start: long | short\nlong: "abcde" "e"*\nshort: "a" /bcde+/\n', "abcdeeee"),
        ('start: item*\nitem: "(" item* ")" | /[a-z]+/\n', "(ab(cd))ef"),
        ('start: expr\nexpr: expr "+" atom | atom\natom: /[0-9]+/\n', "123+45+678"),
        ('start: /a(?!.*z)/ /[a-z]+/?\n', "abz"),
        (bundle.grammar, 'package Demo\npublic\n-- split comment\ndata Café\nend Café;\nend Demo;\n'),
    ]
    for grammar, text in fixtures:
        frontend = GrammarFrontend(grammar)
        for width in (1, 2, 7):
            previous = None
            for end in [*range(0, len(text), width), len(text)]:
                actual = frontend.prefix(text[:end], "fixture", previous=previous)
                independent = frontend.prefix(text[:end], "fixture")
                assert set(actual.alternatives) == set(independent.alternatives), (width, end, text[:end])
                assert (actual.complete, actual.states) == (independent.complete, independent.states)
                if previous is not None:
                    # A different speculative continuation and even an invalid
                    # continuation must leave the selected checkpoint unchanged.
                    saved = previous.checkpoint
                    branch = frontend.prefix(saved.text + "!", "fixture", previous=previous)
                    assert set(branch.alternatives) == set(frontend.prefix(saved.text + "!", "fixture").alternatives)
                    assert previous.checkpoint is saved
                    try:
                        saved.charts[0][("unexpected", 0, 0)] = frozenset()
                    except TypeError:
                        pass
                    else:
                        raise AssertionError("A candidate can mutate a committed Earley column")
                previous = actual
        if "(?!" in grammar:
            assert not frontend.incremental_safe, "Unproved assertions must use the full parser"


def main():
    check_attribute_evaluator()
    check_trivia_prefix_paths()
    check_infrastructure_partial_output()
    check_repeated_line_stop()
    # Alternatives sharing a source production retain their distinct semantic
    # aliases in both full and incomplete trees, including genuine ambiguity.
    aliases = GrammarFrontend('start: item\nitem: "a" "b" -> first\n    | "a" /b/ -> second\n')
    assert {"item", "first", "second"} <= aliases.productions
    for frontier in (aliases.complete("ab"), aliases.prefix("ab"), aliases.prefix("a")):
        names = {node.production for root in frontier.alternatives for node in root.walk()}
        assert {"first", "second"} <= names, names
    check_accepting_word_prefix()
    bundle = load_bundle()
    check_incremental_frontiers(bundle)
    # Delimiter-looking source lines must remain source, including original '|'.
    text = serialize_project(["not AADL\nEND_FILE\n|END_PROJECT\n"])
    document = parse_project(text)
    assert document.valid and document.files[0].text == "not AADL\nEND_FILE\n|END_PROJECT\n"
    for index in range(len(text) + 1):
        assert not parse_project(text[:index]).diagnostics
    assert not parse_project(text + "extra").valid

    class Tokenizer:
        all_special_ids = [256]

    engine = AttributeEngine(bundle, project=True, joint=False)
    bridge = TokenBridge(engine, Tokenizer(), [256], byte_tokenizer=byte_tokenizer())
    assert not bridge.can_end()
    for token in text.encode("utf-8"):
        trial = bridge.probe_token(token)
        assert trial.allowed, trial.reason
        committed = bridge.state
        bridge.probe_token(0)
        assert bridge.state is committed, "A candidate trial changed committed state"
        bridge.synchronize(bridge.token_ids + (token,))
    assert bridge.can_end() and bridge.probe_token(256).allowed
    assert engine.counters["aadl_cfg_candidate_checks"] == 0
    assert engine.counters["semantic_evaluations"] == 0
    try:
        bridge.choose([])
    except ConstrainedDecodingError:
        pass
    else:
        raise AssertionError("Missing finite scores must remain an infrastructure error")

    with tempfile.TemporaryDirectory(prefix="aadl-runtime-check-") as temporary:
        root = Path(temporary)
        context = root / "input"
        context.mkdir()
        (context / "Library.aadl").write_bytes(b"package Library\r\npublic\r\nend Library;\r\n")
        snapshot = snapshot_context([context])
        assert "\r\n" in snapshot.files[0].text
        assert coerce_context(snapshot.as_dict()) == snapshot
        changed = snapshot.as_dict()
        changed["files"][0]["text"] += "-- changed\n"
        try:
            coerce_context(changed)
        except ValueError:
            pass
        else:
            raise AssertionError("Changed dependency bytes must invalidate their snapshot")
        outputs = write_project(document, root / "output", snapshot)
        assert len(outputs) == 2
        assert outputs[1].read_bytes().endswith(b"\r\n")
    print("PASS: project framing, byte-token baseline, EOS, trial isolation and dependency hashes")


if __name__ == "__main__":
    main()
