"""Transactional syntax and Python attributes over a complete project prefix."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from itertools import product
import time

from llguidance import LLMatcher

from .project import PROJECT_GRAMMAR, ProjectDocument, ProjectFile, coerce_context, parse_project
from .rules import PrefixState, ProbeResult, ValidationReport, byte_tokenizer
from .semantic_types import ProjectView, SemanticReport, Status, RuleDefinitionError, ParseResourceError
from .attribute_evaluator import AttributeEvaluator


@dataclass(frozen=True)
class Checkpoint:
    files: tuple = ()
    transport: object = None
    report: SemanticReport | None = None
    document: ProjectDocument | None = None
    attributes: object = None


def _message(result):
    location = f"{result.source}:{result.start}: " if result.source else ""
    return f"{location}{result.rule_id}: {result.message}"


class AttributeEngine:
    """Only committed snapshots persist; all candidate matchers are cloned.

    The raw-source llguidance matcher runs before attribute evaluation. Project
    envelopes have a separate matcher and separate evidence, so a plain run's
    formatting mask cannot be mistaken for an AADL syntax/semantics mask.
    """
    def __init__(self, bundle, declarations=None, *, context=None, project=False, joint=True, syntax_only=False):
        self.bundle, self.plan = bundle, declarations
        self.project, self.joint = project, joint
        # GCD reuses the exact per-file CFG transition below, but never builds
        # attributes or evaluates promises. Plain mode checks only the envelope.
        self.syntax = joint or syntax_only
        self.context = coerce_context(context)
        self.counters = {"aadl_cfg_candidate_checks": 0, "aadl_cfg_rejections": 0,
                         "semantic_evaluations": 0, "attribute_conflicts": 0,
                         "attribute_computations": 0, "attribute_cache_hits": 0,
                         "attribute_invalidations": 0,
                         "aadl_cfg_seconds": 0.0, "semantic_seconds": 0.0,
                         "transport_prefix_seconds": 0.0,
                         "project_parse_seconds": 0.0, "prefix_parse_seconds": 0.0,
                         "attribute_evaluation_seconds": 0.0, "attribute_setup_seconds": 0.0,
                         "semantic_equation_seconds": 0.0, "registered_constraint_seconds": 0.0}
        self.dependencies = ()
        if joint:
            alternatives = []
            sources = tuple(getattr(bundle.semantics, "BUILTIN_SOURCES", {}).items())
            sources += tuple((item.name, item.text) for item in self.context.files)
            for name, text in sources:
                parsed = bundle.frontend.complete(text, name)
                if not parsed.complete or not parsed.alternatives:
                    raise RuleDefinitionError(f"Dependency or built-in source cannot be parsed: {name}")
                alternatives.append(parsed.alternatives)
            # Do not silently choose a parse of an ambiguous dependency either.
            count = 1
            for choices in alternatives:
                count *= len(choices)
            if count > 1024:
                raise ParseResourceError("Dependency parse ambiguity exceeds the project budget")
            self.dependencies = tuple(product(*alternatives)) if alternatives else ((),)

    def start(self):
        transport = LLMatcher(byte_tokenizer(), PROJECT_GRAMMAR, log_level=0) if self.project else None
        return PrefixState(checkpoint=Checkpoint(transport=transport), bundle_digest=self.bundle.digest)

    def _evaluate(self, units, dependencies, text, final, previous=None):
        started = time.perf_counter()
        try:
            return self._evaluate_attributes(units, dependencies, text, final, previous)
        finally:
            duration = time.perf_counter() - started
            # Preserve the established inclusive semantic timer. Its new child
            # timer separates evaluator work from the semantic frontend parser.
            self.counters["semantic_seconds"] += duration
            self.counters["attribute_evaluation_seconds"] += duration

    def _evaluate_attributes(self, units, dependencies, text, final, previous=None):
        self.counters["semantic_evaluations"] += 1
        started = time.perf_counter()
        try:
            project = ProjectView(units, text, dependencies, final)
            inputs = {"plan": self.plan}
            attributes = (previous.fork(project, inputs=inputs) if previous is not None else
                          AttributeEvaluator(self.bundle.semantics.HANDLERS, project,
                                             constraints=self.bundle.semantics.CONSTRAINTS, inputs=inputs))
        finally:
            self.counters["attribute_setup_seconds"] += time.perf_counter() - started
        # A candidate owns its dependency/cache view. Only the selected view is
        # retained, so invalidation in a rejected branch cannot affect its parent.
        started = time.perf_counter()
        try:
            report = self.bundle.semantics.evaluate(project, plan=self.plan, final=final, attributes=attributes)
        finally:
            self.counters["semantic_equation_seconds"] += time.perf_counter() - started
        if not isinstance(report, SemanticReport):
            raise RuleDefinitionError("evaluate() must return SemanticReport")
        started = time.perf_counter()
        try:
            registered = attributes.evaluate_constraints()
        finally:
            self.counters["registered_constraint_seconds"] += time.perf_counter() - started
        for counter, metric in (("attribute_computations", "computed"),
                                ("attribute_cache_hits", "cache_hits"),
                                ("attribute_invalidations", "invalidated")):
            self.counters[counter] += attributes.metrics[metric]
        report = SemanticReport(report.results + registered.results,
                                {**report.attributes, **registered.attributes})
        if any(not isinstance(result.status, Status) for result in report.results):
            raise RuleDefinitionError("Rule results must use Satisfied/Pending/Conflict statuses")
        return report, attributes

    def _probe(self, state, piece, *, force_final=False):
        if not isinstance(piece, str):
            raise TypeError("Decoded source contribution must be text")
        if state.bundle_digest and state.bundle_digest != self.bundle.digest:
            raise RuleDefinitionError("Checkpoint belongs to a different rule snapshot")
        text = state.text + piece
        old = state.checkpoint or self.start().checkpoint
        transport = old.transport.deep_copy() if old.transport is not None else None
        if self.project:
            started = time.perf_counter()
            try:
                transport_allowed = transport.consume_tokens(list(piece.encode("utf-8")))
            finally:
                self.counters["transport_prefix_seconds"] += time.perf_counter() - started
            if not transport_allowed:
                return ProbeResult(False, state, "container: no legal continuation")
            started = time.perf_counter()
            try:
                document = parse_project(text)
            finally:
                self.counters["project_parse_seconds"] += time.perf_counter() - started
            if document.diagnostics:
                return ProbeResult(False, state, "container: " + "; ".join(document.diagnostics))
            complete = document.complete
        else:
            document = ProjectDocument((ProjectFile("model.aadl", text, False),), False, (), True)
            complete = False
        if not self.syntax:
            checkpoint = Checkpoint(transport=transport, document=document)
            return ProbeResult(True, PrefixState(text, complete, (), checkpoint, self.bundle.digest))
        parse_options, checkpoints = [], []
        for index, file in enumerate(document.files):
            self.counters["aadl_cfg_candidate_checks"] += 1
            previous_frontier = None
            started = time.perf_counter()
            try:
                if index < len(old.files) and file.text.startswith(old.files[index][0]):
                    prior_text, prior_matcher, previous_frontier = old.files[index]
                    matcher = prior_matcher.deep_copy()
                    addition = file.text[len(prior_text):]
                else:
                    matcher, addition = self.bundle.new_matcher(), file.text
                grammatical = matcher.consume_tokens(list(addition.encode("utf-8")))
                accepting = matcher.is_accepting() if grammatical else False
            finally:
                self.counters["aadl_cfg_seconds"] += time.perf_counter() - started
            if not grammatical:
                self.counters["aadl_cfg_rejections"] += 1
                return ProbeResult(False, state, f"syntax: {file.name}: no grammatical continuation")
            file_sealed = file.complete or file.closure_started
            if file_sealed and not accepting:
                self.counters["aadl_cfg_rejections"] += 1
                return ProbeResult(False, state, f"syntax: {file.name} closed before its syntax was complete")
            if not self.project:
                complete = accepting
            if not self.joint:
                checkpoints.append((file.text, matcher, None))
                continue
            # Being accepting at this instant does not freeze an open lexeme:
            # e.g. grammar /[a-z]+/ accepts 'a', yet 'ab' remains a continuation.
            # A control record's first E also seals this file: no continuation
            # can return to its source, even while END_FILE is still being spelt.
            started = time.perf_counter()
            try:
                parsed = (self.bundle.frontend.complete(file.text, file.name) if file_sealed or force_final else
                          self.bundle.frontend.prefix(file.text, file.name, previous=previous_frontier))
            finally:
                # This AST/frontier work exists solely for semantic evaluation;
                # count it with semantic cost, not the shared llguidance CFG.
                duration = time.perf_counter() - started
                self.counters["semantic_seconds"] += duration
                self.counters["prefix_parse_seconds"] += duration
            if not parsed.alternatives:
                # Different frontend acceptance is a rule/compiler error. It
                # must not be relabelled as a model error and consume retries.
                raise RuleDefinitionError(f"llguidance/Earley disagreement on {file.name}: {file.text[-120:]!r}")
            parse_options.append(parsed.alternatives)
            checkpoints.append((file.text, matcher, parsed))
        if not self.joint:
            checkpoint = Checkpoint(tuple(checkpoints), transport, document=document)
            return ProbeResult(True, PrefixState(text, complete, (), checkpoint, self.bundle.digest))
        final = ((document.complete or document.closure_started) if self.project else force_final) or force_final
        count = len(self.dependencies)
        for choices in parse_options:
            count *= len(choices)
        if count > 1024:
            raise ParseResourceError("Project viable parse combinations exceed the evaluation budget")
        reports = []
        for dependencies in self.dependencies:
            for units in product(*parse_options):
                report, attributes = self._evaluate(tuple(units), dependencies, text, final, old.attributes)
                reports.append(report)
                if report.valid and (not final or not report.pending):
                    pending = tuple(_message(result) for result in report.pending)
                    checkpoint = Checkpoint(tuple(checkpoints), transport, report, document, attributes)
                    return ProbeResult(True, PrefixState(text, complete, pending, checkpoint, self.bundle.digest), pending=pending)
        self.counters["attribute_conflicts"] += 1
        report = min(reports, key=lambda value: len(value.results)) if reports else SemanticReport()
        reasons = [_message(result) for result in report.results if result.status == Status.CONFLICT or (final and result.status == Status.PENDING)]
        return ProbeResult(False, state, "semantic: " + "; ".join(reasons),
                           tuple(_message(result) for result in report.pending),
                           tuple(asdict(result) for result in report.results))

    def probe(self, state, piece):
        return self._probe(state, piece)

    def probe_pending_bytes(self, state, pending):
        """Check an incomplete UTF-8 suffix against cloned byte-level matchers.

        No bytes enter the committed checkpoint. The next completed Unicode
        character still takes the ordinary text/attribute path exactly once.
        Deferral of attributes does not mean every byte prefix is grammatical.
        """
        if not isinstance(pending, bytes) or not pending:
            raise TypeError("An incomplete UTF-8 suffix must be nonempty bytes")
        if state.bundle_digest and state.bundle_digest != self.bundle.digest:
            raise RuleDefinitionError("Checkpoint belongs to a different rule snapshot")
        old = state.checkpoint or self.start().checkpoint
        if self.project:
            started = time.perf_counter()
            try:
                allowed = old.transport.deep_copy().consume_tokens(list(pending))
            finally:
                self.counters["transport_prefix_seconds"] += time.perf_counter() - started
            if not allowed:
                return ProbeResult(False, state, "container: incomplete UTF-8 bytes have no legal continuation")
        if self.syntax:
            self.counters["aadl_cfg_candidate_checks"] += 1
            started = time.perf_counter()
            try:
                # In a project, the container check above permits non-ASCII
                # bytes only inside a |source record. Its last file matcher is
                # therefore the corresponding language position, not a control.
                matcher = old.files[-1][1].deep_copy() if old.files else self.bundle.new_matcher()
                allowed = matcher.consume_tokens(list(pending))
            finally:
                self.counters["aadl_cfg_seconds"] += time.perf_counter() - started
            if not allowed:
                self.counters["aadl_cfg_rejections"] += 1
                return ProbeResult(False, state, "syntax: incomplete UTF-8 bytes have no grammatical continuation")
        return ProbeResult(True, state, pending=state.pending)

    def commit(self, state, piece):
        result = self.probe(state, piece)
        if not result.allowed:
            raise ValueError(result.reason)
        return result.state

    def finish(self, state):
        if not state.complete:
            return ValidationReport(False, ["Incomplete project container" if self.project else "Incomplete source syntax"], list(state.pending))
        trial = self._probe(state, "", force_final=True)
        if not trial.allowed:
            return ValidationReport(False, [trial.reason], list(trial.pending), list(trial.issues))
        report = trial.state.checkpoint.report
        issues = [asdict(result) for result in report.results] if report is not None else []
        return ValidationReport(True, [], [], issues)

    def validate(self, text):
        trial = self.probe(self.start(), text)
        if not trial.allowed:
            return ValidationReport(False, [trial.reason], list(trial.pending), list(trial.issues))
        return self.finish(trial.state)
