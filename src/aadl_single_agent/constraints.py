"""Language-independent facade for a selected, validated rule package."""
from __future__ import annotations

from .rules import PrefixState, ProbeResult, ValidationReport, RuleBundle, load_bundle


class JointEngine:
    """Keep the public decoding protocol stable while language rules evolve."""
    def __init__(self, declarations=None, bundle: RuleBundle | None = None, *, context=None, project=False):
        self.bundle = bundle if bundle is not None else load_bundle()
        self._engine = self.bundle.create_engine(declarations, context=context, project=project)

    @property
    def counters(self):
        return self._engine.counters

    def start(self) -> PrefixState:
        return self._engine.start()

    def probe(self, state: PrefixState, piece: str) -> ProbeResult:
        return self._engine.probe(state, piece)

    def commit(self, state: PrefixState, piece: str) -> PrefixState:
        # Keeping an allowed probe's returned state is equivalent to commit.
        # Neither path mutates the supplied checkpoint on rejected candidates.
        result = self.probe(state, piece)
        if not result.allowed:
            raise ValueError(result.reason)
        return result.state

    def finish(self, state: PrefixState) -> ValidationReport:
        return self._engine.finish(state)

    def validate(self, text: str) -> ValidationReport:
        return self._engine.validate(text)
