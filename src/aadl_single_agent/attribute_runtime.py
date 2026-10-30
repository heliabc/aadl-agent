"""Transactional syntax and Python attributes over a complete project prefix."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from itertools import product

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
    def __init__(self, bundle, declarations=None, *, context=None, project=False, joint=True):
        self.bundle, self.plan = bundle, declarations
        self.project, self.joint = project, joint
        self.context = coerce_context(context)
        self.counters = {"aadl_cfg_candidate_checks": 0, "aadl_cfg_rejections": 0,
                         "semantic_evaluations": 0, "attribute_conflicts": 0,
                         "attribute_computations": 0, "attribute_cache_hits": 0,
                         "attribute_invalidations": 0}
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
        self.counters["semantic_evaluations"] += 1
        project = ProjectView(units, text, dependencies, final)
        inputs = {"plan": self.plan}
        attributes = (previous.fork(project, inputs=inputs) if previous is not None else
                      AttributeEvaluator(self.bundle.semantics.HANDLERS, project,
                                         constraints=self.bundle.semantics.CONSTRAINTS, inputs=inputs))
        # A candidate owns its dependency/cache view. Only the selected view is
        # retained, so invalidation in a rejected branch cannot affect its parent.
        report = self.bundle.semantics.evaluate(project, plan=self.plan, final=final, attributes=attributes)
        if not isinstance(report, SemanticReport):
            raise RuleDefinitionError("evaluate() must return SemanticReport")
        registered = attributes.evaluate_constraints()
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
            if not transport.consume_tokens(list(piece.encode("utf-8"))):
                return ProbeResult(False, state, "container: no legal continuation")
            document = parse_project(text)
            if document.diagnostics:
                return ProbeResult(False, state, "container: " + "; ".join(document.diagnostics))
            complete = document.complete
        else:
            document = ProjectDocument((ProjectFile("model.aadl", text, False),), False, (), True)
            complete = False
        if not self.joint:
            checkpoint = Checkpoint(transport=transport, document=document)
            return ProbeResult(True, PrefixState(text, complete, (), checkpoint, self.bundle.digest))
        parse_options, checkpoints = [], []
        for index, file in enumerate(document.files):
            self.counters["aadl_cfg_candidate_checks"] += 1
            previous_frontier = None
            if index < len(old.files) and file.text.startswith(old.files[index][0]):
                prior_text, prior_matcher, previous_frontier = old.files[index]
                matcher = prior_matcher.deep_copy()
                addition = file.text[len(prior_text):]
            else:
                matcher, addition = self.bundle.new_matcher(), file.text
            if not matcher.consume_tokens(list(addition.encode("utf-8"))):
                self.counters["aadl_cfg_rejections"] += 1
                return ProbeResult(False, state, f"syntax: {file.name}: no grammatical continuation")
            accepting = matcher.is_accepting()
            if file.complete and not accepting:
                self.counters["aadl_cfg_rejections"] += 1
                return ProbeResult(False, state, f"syntax: {file.name} closed before its syntax was complete")
            if not self.project:
                complete = accepting
            # Being accepting at this instant does not freeze an open lexeme:
            # e.g. grammar /[a-z]+/ accepts 'a', yet 'ab' remains a continuation.
            # Only explicit file closure/EOS may discard partial derivations.
            parsed = (self.bundle.frontend.complete(file.text, file.name) if file.complete or force_final else
                      self.bundle.frontend.prefix(file.text, file.name, previous=previous_frontier))
            if not parsed.alternatives:
                # Different frontend acceptance is a rule/compiler error. It
                # must not be relabelled as a model error and consume retries.
                raise RuleDefinitionError(f"llguidance/Earley disagreement on {file.name}: {file.text[-120:]!r}")
            parse_options.append(parsed.alternatives)
            checkpoints.append((file.text, matcher, parsed))
        final = (document.complete if self.project else force_final) or force_final
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
