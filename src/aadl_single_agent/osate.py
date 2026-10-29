"""Isolated, read-only model checking through the official OSATE CLI.

CLI 0.3.0 returns exit code zero for *model* errors. Consequently, a successful
process is necessary but not sufficient: source diagnostics determine validity.
The checker does not edit the input model or claim requirement satisfaction.
"""

from __future__ import annotations

import hashlib
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid


_DIAGNOSTIC = re.compile(
    r"^(?P<path>.+):(?P<line>\d+):(?P<column>\d+):\s*"
    r"(?P<severity>error|warning|info|hint):\s*(?P<message>.*)$", re.IGNORECASE
)
_VERSION = re.compile(r"^osate-cli\s+(\S+)\s*$", re.MULTILINE)
_INSTALL_HELP = (
    "Set osate_cli in the configuration file to an existing OSATE CLI launcher. "
    "If it is not installed, run 'pwsh -File scripts/install_osate.ps1' manually from the project root, "
    "then point osate_cli to .tools/osate-cli-0.3.0/bin/osate-cli.bat. "
    "No download or installation is performed automatically."
)


def _diagnostic(message: str, rule_id: str, *, path: str | None = None) -> dict:
    return {"severity": "error", "message": message, "path": path, "line": None, "rule_id": rule_id}


class OsateRunner:
    """Run each check in a private workspace, preserving artifacts for inspection.

    ``workspace`` is an output directory, not an existing OSATE workspace to take
    over. The target and sibling ``.aadl`` files are copied to a fresh child;
    callers should place any required companion packages beside the target.
    The timeout bounds each CLI operation. A bounded server idle timeout also
    handles startup failures before the CLI has returned a usable port.
    """

    def __init__(self, executable: str | Path | None = None, timeout_seconds: float = 120):
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be finite and positive")
        self.timeout_seconds = timeout_seconds
        self.requested_executable = str(executable) if executable is not None else None
        self.executable = self._find_executable(executable)

    @staticmethod
    def _find_executable(explicit: str | Path | None) -> Path | None:
        # An explicitly configured file is authoritative. A typo must never
        # silently select a different validator from PATH or another project.
        if explicit is not None:
            candidate = Path(explicit).expanduser()
            return candidate.resolve() if candidate.is_file() else None
        configured = os.environ.get("OSATE_CLI")
        if configured:
            candidate = Path(configured).expanduser()
            if candidate.is_file():
                return candidate.resolve()
            found = shutil.which(str(configured))
            return Path(found).resolve() if found else None
        project_root = Path(__file__).resolve().parents[2]
        # Programmatic callers may use the local default; the CLI always passes
        # the path resolved from its selected YAML configuration instead.
        install_roots = [project_root / ".tools" / "osate-cli-0.3.0"]
        for install_root in install_roots:
            for name in ("osate-cli.bat", "osate-cli.exe", "osate-cli"):
                candidate = install_root / "bin" / name
                if candidate.is_file():
                    return candidate.resolve()
        found = shutil.which("osate-cli")
        return Path(found).resolve() if found else None

    def _run(self, arguments: list[str], *, cwd: Path, env: dict) -> subprocess.CompletedProcess:
        prefix = [str(self.executable)]
        if os.name == "nt" and self.executable.suffix.lower() in {".bat", ".cmd"}:
            # The official portable launcher is exactly runtime/java -jar. Call
            # that executable directly: cmd.exe would expand '%' or '&' in
            # otherwise legitimate user paths, even with shell=False in Python.
            package = self.executable.parent.parent
            java = package / "runtime" / "bin" / "java.exe"
            jar = package / "osate-cli.jar"
            if not java.is_file() or not jar.is_file():
                raise FileNotFoundError("The OSATE portable launcher requires its bundled runtime and CLI jar.")
            prefix = [str(java), "-jar", str(jar)]
        # No shell expression is constructed from model contents or paths.
        return subprocess.run(
            [*prefix, *arguments], cwd=cwd, env=env,
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=self.timeout_seconds, check=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0,
        )

    def probe(self) -> dict:
        """Report actual CLI availability/version; never substitute a fake checker."""
        result = {"status": "unavailable", "diagnostics": [], "version": None, "stdout": ""}
        if self.executable is None:
            selected = f" at configured path {self.requested_executable!r}" if self.requested_executable else ""
            result["diagnostics"] = [_diagnostic(
                "OSATE CLI not found" + selected + ". " + _INSTALL_HELP, "osate.unavailable"
            )]
            return result
        try:
            completed = self._run(["--version"], cwd=self.executable.parent, env=os.environ.copy())
            result["stdout"] = completed.stdout + completed.stderr
            match = _VERSION.search(completed.stdout)
            if completed.returncode != 0 or not match:
                result.update(status="error", diagnostics=[_diagnostic(
                    "OSATE CLI version probe failed or returned an unrecognized banner. " + _INSTALL_HELP,
                    "osate.execution"
                )])
            else:
                result.update(status="passed", version=match.group(1), executable=str(self.executable))
                # Record the actual portable binaries independently of the rule
                # catalog's source revision; those versions need not be identical.
                package = self.executable.parent.parent
                binary_paths = [package / "osate-cli.jar"]
                binary_paths += list((package / "lib").glob("org.osate.xtext.aadl2_*.jar"))
                binary_paths += list((package / "lib").glob("osate-workspace-server-*.jar"))
                result["binary_provenance"] = [
                    {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                    for path in binary_paths if path.is_file()]
        except FileNotFoundError as exc:
            result["diagnostics"] = [_diagnostic(
                f"OSATE CLI installation is unavailable or incomplete: {exc}. " + _INSTALL_HELP,
                "osate.unavailable")]
        except subprocess.TimeoutExpired:
            result.update(status="error", diagnostics=[_diagnostic("OSATE version probe timed out.", "osate.timeout")])
        except OSError as exc:
            result.update(status="error", diagnostics=[_diagnostic(str(exc), "osate.execution")])
        return result

    @staticmethod
    def _parse_diagnostics(output: str) -> list[dict]:
        diagnostics = []
        for line in output.splitlines():
            if not line.strip():
                continue
            match = _DIAGNOSTIC.fullmatch(line.strip())
            if match:
                diagnostics.append({
                    "severity": match["severity"].lower(), "message": match["message"],
                    "path": match["path"], "line": int(match["line"]),
                    # The text protocol does not expose Xtext issue codes. This
                    # adapter identifier must not be mistaken for an OSATE rule.
                    "rule_id": "osate.diagnostic",
                })
            else:
                diagnostics.append(_diagnostic(f"Unrecognized OSATE diagnostic output: {line}", "osate.protocol"))
        return diagnostics

    def check(self, aadl_path: Path, workspace: Path) -> dict:
        """Check a copied model with init/update barriers and owned-server cleanup."""
        result = self.probe()
        if result["status"] != "passed":
            return result
        result["diagnostics"] = []
        source = Path(aadl_path).resolve()
        if not source.is_file() or source.suffix.lower() != ".aadl":
            result.update(status="error", diagnostics=[_diagnostic(
                "Input must be an existing .aadl file.", "osate.input", path=str(source)
            )])
            return result
        client_id = f"aadl-single-agent-{uuid.uuid4().hex}"
        run_root = Path(workspace).resolve() / f"osate-run-{uuid.uuid4().hex}"
        port = None
        logs = [result["stdout"]]
        env = os.environ.copy()
        # The same private home is inherited by the server JVM. It prevents
        # session discovery/cleanup from touching a user's other OSATE sessions.
        env["JAVA_TOOL_OPTIONS"] = (
            env.get("JAVA_TOOL_OPTIONS", "") + f' -Dosate.cli.home="{run_root / "session-home"}"'
        ).strip()
        env["OSATE_CLI_SERVER_LAUNCH"] = "direct"
        result["workspace"] = str(run_root)

        def invoke(arguments: list[str]) -> subprocess.CompletedProcess:
            completed = self._run(arguments, cwd=run_root, env=env)
            logs.extend([completed.stdout, completed.stderr])
            if completed.returncode != 0:
                raise RuntimeError(f"OSATE command {arguments!r} exited {completed.returncode}.")
            return completed

        try:
            run_root.mkdir(parents=True, exist_ok=False)
            (run_root / "session-home").mkdir()
            invoke(["project", "create", "model"])
            project = run_root / "model"
            project.mkdir(exist_ok=True)
            for companion in source.parent.iterdir():
                if companion.is_file() and companion.suffix.lower() == ".aadl":
                    shutil.copy2(companion, project / companion.name)
            target = project / source.name
            # init waits for initial diagnostics; update additionally waits for
            # the settled rebuild rather than relying on a fixed sleep interval.
            startup = invoke([
                client_id, "init", "--timeout", str(max(1, math.ceil(self.timeout_seconds))),
                "--server-timeout", str(max(30, math.ceil(self.timeout_seconds))), str(run_root),
            ])
            port_text = startup.stdout.strip()
            if not port_text.isdecimal() or not 1 <= int(port_text) <= 65535:
                raise RuntimeError("OSATE init did not return a valid listening port.")
            port = port_text
            for command in ([client_id, "-p", port, "update"], [client_id, "-p", port, "check", str(target)]):
                completed = invoke(command)
                for issue in self._parse_diagnostics(completed.stdout):
                    if issue not in result["diagnostics"]:
                        result["diagnostics"].append(issue)
            if any(d["rule_id"] == "osate.protocol" for d in result["diagnostics"]):
                result["status"] = "error"
            elif any(d["severity"] == "error" for d in result["diagnostics"]):
                result["status"] = "failed"
            else:
                result["status"] = "passed"
        except subprocess.TimeoutExpired as exc:
            result["status"] = "error"
            result["diagnostics"].append(_diagnostic("OSATE operation timed out.", "osate.timeout"))
            # TimeoutExpired may retain bytes even when text=True was requested.
            for output in (exc.stdout, exc.stderr):
                if output:
                    logs.append(output.decode("utf-8", errors="replace") if isinstance(output, bytes) else output)
        except (OSError, RuntimeError) as exc:
            result["status"] = "error"
            result["diagnostics"].append(_diagnostic(str(exc), "osate.execution"))
        finally:
            if port is not None:
                try:
                    # Never scan for or kill Java processes. The fresh workspace
                    # and sticky client ID restrict exit to this invocation's server.
                    invoke([client_id, "-p", port, "exit"])
                except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
                    result["status"] = "error"
                    result["diagnostics"].append(_diagnostic(
                        f"Owned OSATE server cleanup failed; idle timeout remains active: {exc}", "osate.cleanup"
                    ))
            result["stdout"] = "\n".join(part.rstrip() for part in logs if part.strip())
        return result
