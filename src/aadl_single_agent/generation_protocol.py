"""Generation-outcome contract, independent of the language rule runtime.

Protocol 2 distinguishes a measured, cooperative computation deadline from an
unknown transport failure. Its policy is discovered, echoed and fingerprinted;
the user still configures only the inference URL.
"""

GENERATION_PROTOCOL_VERSION = 2


class GenerationProtocolMismatch(ValueError):
    """Client and server disagree before any inference may consume tokens."""


def generation_policy(repetition_configuration: dict, deadline_seconds: float) -> dict:
    return {"repetition_stop": repetition_configuration,
            "deadline_seconds": float(deadline_seconds), "deadline_retryable": False}
