"""Offline regression entry point for the two rule sources.

Run with the project's Python interpreter. These representative examples check
syntax/attribute behavior; they are not a claim of exhaustive AS5506D coverage
or an OSATE certification. No model, server, or installation is started.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import tempfile
import time

from llguidance import LLMatcher

from aadl_single_agent.grammar import GrammarFrontend
from aadl_single_agent.project import serialize_project
from aadl_single_agent.rules import load_bundle


def package(body: str) -> str:
    return "package Example public " + body + " end Example;"


# Syntax cases intentionally include unresolved names: their purpose is to
# isolate CFG coverage from reference resolution, which is tested separately.
POSITIVE = {
    "visibility": "package A::B public with Other, My_Props; Alias renames package Other; Renamed renames system Other::S; renames Other::all; private data Hidden end Hidden; properties none; end A::B;",
    "prototypes": package("system Generic prototypes D: data; F: in feature; G: feature group; Many: system []; end Generic; system Specialized extends Generic (D => data Payload, F => in data port Payload, G => feature group Ports, Many => (system One, system Two)) end Specialized;"),
    "implementation_extension": package("system implementation Derived.impl extends Base.impl (D => data Payload) prototypes D: refined to data Payload; end Derived.impl;"),
    "features": package("system S features p: in out data port Payload; e: out event port; ep: in event data port Payload; a: requires data access Payload; b: provides virtual bus access VBus; f: in feature Payload; g: out feature group inverse of Ports; proto: in prototype FeatureProto; end S;"),
    "feature_refinement": package("system Child extends Parent features p: refined to out data port Payload; end Child;"),
    "parameters_access": package("subprogram S features arg: in out parameter Payload; call: requires subprogram access Other; group_access: provides subprogram group access GroupType; bus_access: requires bus access BusType; end S;"),
    "feature_group": package("feature group Ports prototypes D: data; features p: out data port D; end Ports; feature group Reverse extends Ports features p: refined to out data port Payload; inverse of Ports properties none; end Reverse;"),
    "arrays": package("system implementation S.impl subcomponents x: system T.impl[2][Limits::Size](T.a,T.b); y: refined to data Payload[]; end S.impl;"),
    "connections": package("system implementation S.impl connections c: port src.p -> dst.p; d: data access x <-> y; e: feature group src.g <-> dst.g; f: refined to feature; g: parameter call_a.arg -> call_b.arg; end S.impl;"),
    "calls": package("thread implementation T.impl calls seq: { first: subprogram Library::Run.impl; second: subprogram processor.execute; } in modes (active); end T.impl;"),
    "flow_specs": package("system S flows src: flow source output_p; snk: flow sink input_p; path_f: flow path input_p -> output_p; end S;"),
    "flow_implementations": package("system implementation S.impl flows path_f: flow path input_p -> c1 -> child.path_f -> c2 -> output_p; source_f: flow source child.src -> c3 -> output_p; sink_f: flow sink input_p -> c4 -> child.snk; e2e: end to end flow left.src -> wire -> right.snk; end S.impl;"),
    "modes": package("thread implementation T.impl internal features tick: event; msg: event data Payload; processor features alarm: port; run: subprogram Work; modes active: initial mode; idle: mode; switch: active -[self.tick,processor.alarm]-> idle; end T.impl;"),
    "requires_modes": package("system S requires modes active: mode; idle: mode; end S;"),
    "properties": package('data D properties P::Text => "say ""hello"""; P::Count => -16#F#; P::Latency => 1.0 ms .. 2.5 ms delta 0.1 ms; P::Items +=> (1, (2, 3)); P::Record_Value => [field_a => true; field_b => classifier(Payload.impl);]; P::Ref => reference(child[1..2].p); P::Computed => compute(derive); P::Negative => -P::Limit; end D;'),
    "modal_properties": package("system implementation S.impl properties P::Limit => 1 in modes (active), 2 in modes (idle) applies to child[1].p in binding (Platform::CPU.impl); end S.impl;"),
    "property_set": "property set My_Props is with Other_Props; Unit_T: type units (base_u, kilo_u => base_u * 1000); Count: type aadlinteger -10 .. +10; Duration: type aadlreal units (us, ms => us * 1000); Choice: type enumeration (alpha, beta); Span: type range of Count; Ref: type reference (data, system implementation); Cls: type classifier (data, system); Record_T: type record (x: aadlinteger; y: list of aadlstring;); Limit: constant Count => 2; Matrix: list of list of Count => ((1,2)) applies to (all); Flag: inherit aadlboolean => true applies to (system, Other::TypeName); end My_Props;",
    "annex_opaque": package('annex custom{** core keywords end package; "unterminated annex text is opaque **}; system S annex custom none; end S;'),
    "record_type_keyword_boundary": "property set P is X:record(a:aadlinteger;)applies to(all); end P;",
    "delimited_type_boundaries": "property set P is E:enumeration(alpha,beta)applies to(all); R:reference(data)applies to(all); C:classifier(system)applies to(all); N:aadlinteger units(base_u,kilo_u=>base_u*1000)applies to(all); L:list of record(v:aadlstring;)applies to(all); end P;",
    "default_value_keyword_boundary": 'property set P is X:record(a:aadlinteger;) => [a=>1;]applies to(all); Text:aadlstring=>"hello"applies to(all); Items:list of aadlinteger=>(1,2)applies to(all); end P;',
    "modal_delimiter_boundary": package('system S properties P::X=>[a=>1;]in modes(active)applies to p; P::Text=>"hello"applies to p; P::Items=>(1,2)in modes(active); end S;'),
    "unicode_identifier": "package Garçon public data État end État; end Garçon;",
    "comments": package("data D properties Data_Size => 8-- separator comment\nBytes; end D;"),
}
for category in ("abstract", "data", "subprogram", "subprogram group", "thread", "thread group", "process", "processor", "virtual processor", "memory", "bus", "virtual bus", "device", "system"):
    POSITIVE["category_" + category.replace(" ", "_")] = package(f"{category} Item end Item; {category} implementation Item.impl end Item.impl;")

NEGATIVE = {
    "glued_numeric_mode": package("system S properties P::X=>1in modes(active); end S;"),
    "glued_scalar_applies": "property set P is X:aadlintegerapplies to(all); end P;",
    "glued_default_applies": "property set P is X:aadlinteger=>1applies to(all); end P;",
    "glued_reference_mode": package("system S properties P::X=>P::Valuein modes(active); end S;"),
    "unit_separator": package("data D properties Data_Size => 8Bytes; end D;"),
    "missing_keyword_separator": "packageExample public end Example;",
    "reserved_identifier": package("data system end system;"),
    "double_underscore": package("data Bad__Name end Bad__Name;"),
    "trailing_underscore": package("data Bad_ end Bad_;"),
    "leading_underscore": package("data _Bad end _Bad;"),
    "missing_end_semicolon": "package Example public end Example",
    "typed_event_port": package("system S features p: in event port D; end S;"),
    "negative_integer_exponent": package("data D properties P::Number => 1E-2; end D;"),
    "partial_real": package("data D properties P::Number => 1.; end D;"),
    "newline_string": package('data D properties P::Text => "a\nb"; end D;'),
    "unclosed_annex": package("annex custom {** no closing marker;"),
    "implementation_without_type": package("system implementation Lone end Lone;"),
    "empty_array_selection": package("system implementation S.impl connections c: port a[].p -> b.p; end S.impl;"),
}


def run(rule_path: str | None = None) -> None:
    bundle = load_bundle(rule_path)
    checks = 0

    def expect(condition, message):
        nonlocal checks
        checks += 1
        if not condition:
            raise AssertionError(message)

    def syntax(text):
        matcher = bundle.new_matcher()
        accepted = matcher.consume_tokens(list(text.encode("utf-8"))) and matcher.is_accepting()
        parsed = bundle.frontend.complete(text)
        expect(bool(accepted) == bool(parsed.complete), "Compiler disagreement: " + text)
        return bool(accepted)

    for name, text in POSITIVE.items():
        expect(syntax(text), f"Positive syntax case rejected: {name}")
    for name, text in NEGATIVE.items():
        expect(not syntax(text), f"Negative syntax case accepted: {name}")
    for name, text in bundle.semantics.BUILTIN_SOURCES.items():
        parsed = bundle.frontend.complete(text, name)
        expect(parsed.complete and len(parsed.alternatives) == 1, f"Built-in parse incomplete/ambiguous: {name}")

    # Every character boundary matters to decoding, including comments and an
    # unfinished number/identifier. Complete-source parsing alone misses these.
    prefixes = [package("system S end S;"), POSITIVE["comments"],
                package("system implementation S.impl subcomponents x: data D[2]; end S.impl; data D end D;")]
    for text in prefixes:
        matcher = bundle.new_matcher()
        for index, char in enumerate(text, 1):
            expect(matcher.consume_tokens(list(char.encode("utf-8"))), f"CFG rejected legal prefix at {index}")
            expect(bool(bundle.frontend.prefix(text[:index]).alternatives), f"Earley rejected legal prefix at {index}")

    # Two aliases of the same source production must remain two distinct
    # semantic possibilities, both in a complete parse and a partial token.
    alias_frontend = GrammarFrontend('start: choice\nchoice: branch_a -> left\n      | branch_b -> right\nbranch_a: "xy"\nbranch_b: "xy"\n')
    alias_frontend.bindings = {"left": {"value": "branch_a"}, "right": {"value": "branch_b"}}
    for parsed in (alias_frontend.complete("xy"), alias_frontend.prefix("x")):
        nodes = [node for root in parsed.alternatives for node in root.walk() if node.production in {"left", "right"}]
        expect({node.production for node in nodes} == {"left", "right"}, "Ambiguous semantic aliases were collapsed")
        expect(all(node.field("value") for node in nodes), "Alias field bindings were lost")

    boundary = 'package P public system S properties X=>[a=>1;]in modes(m)applies to p[1]in binding(Cpu); end S; end P;'
    parsed = bundle.frontend.complete(boundary)
    expect(parsed.complete and len(parsed.alternatives) == 1, "Delimiter branches introduced semantic ambiguity")
    association = parsed.alternatives[0].first("property_association")
    expect(bool(association.field("assignment") and association.field("targets") and association.field("binding")),
           "Lexical wrappers hid property association fields from attributes")
    for index in range(1, len(boundary) + 1):
        if boundary[index - 1] in ']);' or boundary[:index].endswith(("applies", "modes", "binding")):
            expect(bool(bundle.frontend.prefix(boundary[:index]).alternatives), "A legal punctuation boundary was rejected in a prefix")

    engine = bundle.create_engine()
    refinement_prefix = "package P public system Root features implementations : "
    for suffix in ("r", "re"):
        expect(engine.probe(engine.start(), refinement_prefix + suffix).allowed,
               "An unfinished requires/refined prefix must remain viable")
    for suffix in ("ref", "refined", "RefineD\n"):
        expect(not engine.probe(engine.start(), refinement_prefix + suffix).allowed,
               "A definite refinement without extends must be rejected before a delimiter")
    base = "system Base features p: out data port; end Base;"
    child = "system Child extends Base features p: refined to out data port; end Child;"
    expect(engine.validate(package(base + child)).valid, "Refinement against an earlier base should pass")
    expect(engine.validate(package(child + base)).valid, "A forward-defined base must remain legal")

    # Implementation inheritance must follow type ancestry, not merely share
    # a category. Reversing declaration order remains a legal forward reference.
    ancestry = "system A end A; system implementation A.i end A.i; system B extends A end B; system implementation B.i extends A.i end B.i;"
    expect(engine.validate(package(ancestry)).valid, "Implementation extension may follow a type extension")
    expect(not engine.validate(package(ancestry.replace("system B extends A", "system B"))).valid,
           "Unrelated component types cannot share implementation inheritance (4.4 N6)")
    forward_ancestry = "system implementation B.i extends A.i end B.i; system B extends A end B; system implementation A.i end A.i; system A end A;"
    expect(engine.validate(package(forward_ancestry)).valid, "A future type ancestry must remain resolvable")
    expect(engine.validate(package("system A end A; system implementation A.i end A.i; system implementation A.j extends A.i end A.j;")).valid,
           "Two implementations of the same type may extend one another")

    inline = "data D end D; system S features p: out data port D {Data_Size => 8 Bytes;}; q: out data port D {Data_Size => 4 Bytes;}; end S;"
    expect(engine.validate(package(inline)).valid, "Different inline property lists have separate association scopes")
    expect(not engine.validate(package(inline.replace("Data_Size => 8 Bytes;", "Data_Size => 8 Bytes; Memory_Properties::Data_Size => 4 Bytes;"))).valid,
           "Qualified and predefined spellings of the same property cannot repeat in one inline list")
    type_modes = "system S modes active: initial mode; end S; system implementation S.i modes idle: mode; end S.i;"
    expect(not engine.validate(package(type_modes)).valid, "An implementation cannot add modes to its type's mode declarations (4.4 L7)")
    requires_modes = "system S requires modes active: mode; end S; system implementation S.i modes none; end S.i;"
    expect(syntax(package(requires_modes)), "Requires-modes regression must reach semantics, not fail parsing")
    expect(not engine.validate(package(requires_modes)).valid, "A requires-modes type forbids any implementation modes subclause (4.4 L6)")

    valid = package("system S end S; system implementation S.impl subcomponents x: data Payload; end S.impl; data Payload end Payload;")
    expect(engine.validate(valid).valid, "Forward reference should resolve before final validation")
    expect(not engine.validate(valid.replace("data Payload end Payload;", "")).valid, "Final missing classifier was accepted")
    expect(not engine.validate(package("system S end Wrong;")).valid, "Mismatching end name was accepted")

    # These pairs isolate connection semantics: the CFG accepts both members,
    # so a direction/category regression cannot be hidden by syntax rejection.
    access = package("data D end D; system Client features req: requires data access D; end Client; system Top end Top; system implementation Top.impl subcomponents storage: data D; c: system Client; connections wire: data access storage -> c.req; end Top.impl;")
    group = package("data D end D; feature group G features p: out data port D; end G; system Producer features g: feature group G; end Producer; system Consumer features g: feature group inverse of G; end Consumer; system Top end Top; system implementation Top.impl subcomponents a: system Producer; b: system Consumer; connections wire: feature group a.g -> b.g; end Top.impl;")
    expect(engine.validate(access).valid, "Data-subcomponent to child requires access should pass")
    expect(not engine.validate(access.replace("requires data access D", "provides data access D")).valid,
           "Data-subcomponent to child provides access must fail")
    expect(engine.validate(group).valid, "Inverse feature-group sibling connection should pass")
    expect(not engine.validate(group.replace("inverse of G", "G")).valid,
           "Sibling feature groups with two outgoing ports must fail")
    expect(engine.validate(group.replace("a.g -> b.g", "a.g <-> b.g")).valid, "Bidirectional group connection should preserve compatible fixed directions")
    expect(not engine.validate(group.replace("a.g -> b.g", "b.g -> a.g")).valid, "Reversing a directional group connection must conflict")
    inverse = package("data D end D; feature group G features p: out data port D; end G; feature group Reversed inverse of G end Reversed;")
    expect(engine.validate(inverse).valid, "An implicit inverse feature-group type should pass")
    chained = inverse.replace("end Example;", "feature group Chained inverse of Reversed end Chained; end Example;")
    expect(not engine.validate(chained).valid, "Chained inverses are forbidden by 8.2 L2")

    parameter_calls = Path(__file__).with_name("parameter_calls.aadl").read_text(encoding="utf-8")
    expect(engine.validate(parameter_calls).valid, "Thread ports and sequential call parameters should match")
    expect(not engine.validate(parameter_calls.replace("input_p -> first.input_p", "output_p -> first.input_p")).valid,
           "An outgoing thread port cannot supply a called input parameter (9.3 L1)")
    reversed_calls = parameter_calls.replace("first: subprogram Step;\n      second: subprogram Step;",
                                             "second: subprogram Step;\n      first: subprogram Step;")
    expect(not engine.validate(reversed_calls).valid, "Parameter flow cannot run backward through the call sequence (9.3 N2)")
    mismatch = parameter_calls.replace("  subprogram Step", "  data Different end Different;\n  subprogram Step").replace("input_p: in parameter Sample", "input_p: in parameter Different")
    expect(not engine.validate(mismatch).valid, "Called parameter data classifiers must match")
    fanin = parameter_calls.replace("    c3: parameter", "    extra: parameter input_p -> second.input_p;\n    c3: parameter")
    expect(not engine.validate(fanin).valid, "A parameter cannot have two incoming connections in overlapping modes")
    local_call = parameter_calls.replace("  calls\n", "  subcomponents helper: subprogram Step;\n  calls\n").replace("first: subprogram Step", "first: subprogram helper").replace("second: subprogram Step", "second: subprogram helper")
    expect(engine.validate(local_call).valid, "Calls through a declared subprogram instance should resolve")
    access_call = parameter_calls.replace("    input_p: in data port Sample;", "    svc: requires subprogram access Step;\n    input_p: in data port Sample;").replace("first: subprogram Step", "first: subprogram svc")
    expect(engine.validate(access_call).valid, "Calls through requires subprogram access should resolve")
    implementation_call = parameter_calls.replace("  thread Worker", "  subprogram implementation Step.impl end Step.impl;\n  thread Worker", 1).replace("first: subprogram Step;", "first: subprogram Step.impl;")
    expect(engine.validate(implementation_call).valid, "A dotted local subprogram implementation is a classifier, not a member path")
    expect(engine.validate(parameter_calls.replace(": parameter ", ": feature ")).valid,
           "Feature connections with parameter endpoints use the parameter rules")
    two_sequences = package("subprogram Leaf end Leaf; subprogram Parent end Parent; subprogram implementation Parent.impl calls alpha: {a: subprogram Leaf;}; beta: {b: subprogram Leaf;}; end Parent.impl;")
    expect(not engine.validate(two_sequences).valid, "A nonmodal subprogram permits one call sequence")
    modal_sequences = two_sequences.replace("a: subprogram Leaf;};", "a: subprogram Leaf;} in modes (active);").replace("b: subprogram Leaf;};", "b: subprogram Leaf;} in modes (idle);").replace("end Parent.impl;", "modes active: initial mode; idle: mode; end Parent.impl;")
    expect(engine.validate(modal_sequences).valid, "Subprogram call sequences may apply to disjoint modes")

    files = ["package A public with B; system S end S; system implementation S.impl subcomponents x: data B::Payload; end S.impl; end A;",
             "package B public data Payload end Payload; end B;"]
    expect(bundle.create_engine(project=True).validate(serialize_project(files)).valid,
           "Cross-file forward reference should resolve at END_PROJECT")

    # AS5506D §4.2 N5: visibility is fixed when a declaration starts. A later
    # package may supply B::D, but no appended text can insert a missing public
    # import before an already generated public declaration.
    public_use = "package A public system S features p: in data port B::D; "
    private_use = "package A public system S end S; private system T features p: in data port B::D; "
    for source in (public_use, private_use):
        trial = engine.probe(engine.start(), source)
        expect(not trial.allowed and "name.package_visibility" in trial.reason,
               "A missing import must conflict before component/package/file closure")
    public_import = public_use.replace("public system", "public with B; system", 1)
    private_import = private_use.replace("private system", "private with B; system", 1)
    inherited_import = private_use.replace("public system", "public with B; system", 1)
    for source in (public_import, private_import, inherited_import):
        trial = engine.probe(engine.start(), source)
        expect(trial.allowed and bool(trial.pending),
               "A visible import must keep a later classifier/package definition pending")
    expect(engine.probe(engine.start(), "package A public with B").allowed,
           "An unfinished import itself must remain a valid prefix")
    late_private = public_use + "end S; private with B; system T end T; "
    trial = engine.probe(engine.start(), late_private)
    expect(not trial.allowed and "name.package_visibility" in trial.reason,
           "A private import cannot rescue a public reference, even in a whole-prefix probe")
    library = "package B public data D end D; end B;"
    for source in (public_import + "end S; end A;", private_import + "end T; end A;",
                   inherited_import + "end T; end A;"):
        expect(bundle.create_engine(project=True).validate(serialize_project([source, library])).valid,
               "Correct section imports must resolve a subsequent project file")
    expect(not engine.validate(late_private + "end A; " + library).valid,
           "Public/private visibility must also hold at complete validation")

    # This disposable extension changes only the two rule resources. Adding a
    # syntax spelling and a property definition must not require runtime edits.
    # It is a test dialect, never copied over the installed AS5506D rule bundle.
    with tempfile.TemporaryDirectory(prefix="aadl-rule-conformance-") as folder:
        destination = Path(folder)
        grammar = bundle.grammar.replace('package_spec: "package"i', 'package_spec: ("package"i | "module"i)', 1)
        semantics = bundle.read_text("semantics.py") + '\nBUILTIN_SOURCES["Conformance_Props.aadl"] = "property set Conformance_Props is Calibration: aadlinteger applies to (data); end Conformance_Props;"\n'
        (destination / "aadl.lark").write_text(grammar, encoding="utf-8", newline="")
        (destination / "semantics.py").write_text(semantics, encoding="utf-8", newline="")
        extended = load_bundle(destination)
        source = "module Example public with Conformance_Props; data D properties Conformance_Props::Calibration => 7; end D; end Example;"
        expect(not syntax(source), "Default grammar unexpectedly accepted the extension")
        expect(extended.create_engine().validate(source).valid, "Rule-only extension failed")
        expect(extended.digest != bundle.digest, "Rule changes failed to change the fingerprint")
        # A field declaration cannot substitute for an explicitly required
        # semantic function. These defects must fail before any generation.
        for defect in ("HANDLERS.pop(('component_type', 'category'))",
                       "REQUIRED_ATTRIBUTES.pop('component_type')"):
            (destination / "semantics.py").write_text(semantics + '\n' + defect + '\n',
                                                       encoding="utf-8", newline="")
            try:
                load_bundle(destination)
            except Exception as error:
                from aadl_single_agent.semantic_types import RuleDefinitionError
                expect(isinstance(error, RuleDefinitionError), "Missing handlers must be a rule specification error")
            else:
                expect(False, "A missing required semantic handler was silently accepted")
    print(f"PASS: {checks} offline assertions; {len(POSITIVE)} positive and {len(NEGATIVE)} negative syntax cases")
    print(f"Rule SHA256: {bundle.digest}")


if __name__ == "__main__":
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument("--rules", help="Trusted directory containing aadl.lark and semantics.py")
    options = arguments.parse_args()
    started = time.monotonic()
    run(options.rules)
    print(f"Elapsed: {time.monotonic() - started:.2f} s")
