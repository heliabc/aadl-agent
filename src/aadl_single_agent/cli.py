"""Use the configured API URL and OSATE CLI path for every workflow."""

from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import uuid

from .config import Config
from .constraints import JointEngine
from .model import ModelClient, ModelError
from .osate import OsateRunner
from .pipeline import SingleAgent, check_instance_rules
from .project import read_project, serialize_project, snapshot_context
from .rules import load_bundle
from .semantic_types import ParseResourceError, RuleDefinitionError


def _print(data) -> None:
    print(json.dumps(data, ensure_ascii=False, indent=2, default=lambda x: sorted(x) if isinstance(x, set) else str(x)))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Single-agent AADL generation with URL-only model discovery")
    parser.add_argument("--config", default="config.yaml")
    parser.add_argument("--rules", type=Path, help="Rule bundle directory; defaults to the packaged bundle")
    commands = parser.add_subparsers(dest="command", required=True)
    doctor = commands.add_parser("doctor", help="Verify model, rule identity, and OSATE availability")
    generate = commands.add_parser("generate", help="Generate and independently validate one AADL project")
    inputs = generate.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--requirements", type=Path)
    inputs.add_argument("--prompt")
    generate.add_argument("--mode", choices=["direct", "planned"], default="direct")
    generate.add_argument("--constraints", choices=["plain", "joint"], default="joint")
    generate.add_argument("--token-budget", type=int, default=8192, help="Shared output-token budget, including planning and repairs")
    generate.add_argument("--max-attempts", type=int, choices=[1, 2, 3], default=3)
    generate.add_argument("--out", type=Path)
    check = commands.add_parser("check", help="Validate one AADL file or recursive project directory offline")
    check.add_argument("model", type=Path)
    check.add_argument("--workspace", type=Path, default=Path(".runs/checks"))
    for command in (generate, check):
        command.add_argument("--instance-root", help="Instantiate only this explicit qualified root, e.g. Pkg::Root.impl")
    # Accept common options before or after the subcommand without a default
    # accidentally overwriting a value supplied to the parent parser.
    for command in (doctor, generate, check):
        command.add_argument("--rules", type=Path, default=argparse.SUPPRESS)
        command.add_argument("--config", default=argparse.SUPPRESS)
        command.add_argument("--context", type=Path, action="append", default=[],
                             help="Explicit read-only dependency directory; repeat for multiple directories")
    args = parser.parse_args(argv)
    try:
        settings = Config.load(args.config)
        bundle = load_bundle(args.rules)
        checker = OsateRunner(executable=settings.osate_cli)
        if args.command == "check":
            context = snapshot_context(args.context)
            document = read_project(args.model)
            try:
                report = asdict(JointEngine(bundle=bundle, context=context, project=True).validate(serialize_project(document.files)))
            except (ValueError, RuleDefinitionError, ParseResourceError) as exc:
                # Independent checking remains available when a local rule
                # rejects the source or its specification cannot be evaluated.
                report = {"valid": False, "diagnostics": [str(exc)], "pending": [], "rule_error": True}
            independent = checker.check(args.model, args.workspace, context=context, instance_root=args.instance_root)
            instance_rules = check_instance_rules(bundle, independent)
            _print({"syntax_semantics": report, "osate": independent,
                    "local_instance_rules": instance_rules,
                    "context_sha256": context.sha256, "instance_root": args.instance_root,
                    "validation_scope": ("syntax_and_static_semantics_and_osate_instantiation_checks"
                                         if args.instance_root else "syntax_and_static_semantics"),
                    "rule_bundle_sha256": bundle.digest, "runtime_version": bundle.runtime_version,
                    "compiled_grammar_sha256": bundle.compiled_grammar_sha256,
                    "standard": bundle.semantics.METADATA.get("standard"),
                    "osate_revision": bundle.semantics.METADATA.get("osate_revision")})
            return 0 if (report["valid"] and independent["status"] == "passed"
                         and instance_rules["status"] in {"passed", "not_requested"}) else 1
        # A missing local validator should produce its installation guidance
        # even when the remote model server happens to be offline.
        if args.command == "doctor":
            availability = checker.probe()
            if availability["status"] != "passed":
                _print({"osate": availability})
                return 1
        with ModelClient(settings.base_url) as client:
            client.bind_rules(bundle)
            if args.command == "doctor":
                context = snapshot_context(args.context)
                client.bind_context(context)
                endpoint = client.discover(force=True)
                _print({"endpoint": asdict(endpoint), "osate": availability, "context_sha256": context.sha256})
                return 0
            requirements = args.requirements.read_bytes().decode("utf-8-sig") if args.requirements else args.prompt
            output = args.out or Path(".runs") / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
            result = SingleAgent(client, osate=checker, bundle=bundle).run(requirements, output, mode=args.mode,
                                             constraints=args.constraints, token_budget=args.token_budget,
                                             max_attempts=args.max_attempts, context=args.context,
                                             instance_root=args.instance_root)
        _print(asdict(result))
        return 0 if result.status == "passed" else 1
    except (OSError, ValueError, ModelError, RuleDefinitionError, ParseResourceError) as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

