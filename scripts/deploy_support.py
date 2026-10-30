"""Trusted SSH deployment, with immutable releases and rule-only updates.

Executable rule bundles are installed through SSH, never submitted over the
inference HTTP API. Only this project's symlinks and systemd unit are managed.
"""
from __future__ import annotations
import argparse
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import tarfile
import time
import uuid
import urllib.error
import urllib.request

UNIT = "aadl-single-agent.service"


def pack(args):
    from aadl_single_agent.rules import load_bundle
    project, rules = Path(args.project).resolve(), Path(args.rules).resolve()
    bundle = load_bundle(rules)
    target = Path(args.archive).resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    metadata = {"action": args.action, "rule_bundle_sha256": bundle.digest,
                "compiled_grammar_sha256": bundle.compiled_grammar_sha256,
                "runtime_version": bundle.runtime_version, "profile": bundle.profile}
    def filtered(info):
        if any(part in {"__pycache__", ".pytest_cache", ".git", ".venv", ".runs"}
               for part in Path(info.name).parts) or info.name.endswith((".pyc", ".pyo")):
            return None
        return info
    with tarfile.open(target, "w:gz") as archive:
        if args.action == "Deploy":
            for name in ["src", "resources", "scripts", "examples", "pyproject.toml", "README.md"]:
                path = project / name
                if path.exists():
                    archive.add(path, arcname="project/" + name, filter=filtered)
        archive.add(rules, arcname="bundle", filter=filtered)
        content = json.dumps(metadata).encode()
        entry = tarfile.TarInfo("deployment.json")
        entry.size = len(content)
        archive.addfile(entry, io.BytesIO(content))
    print(json.dumps({"archive": str(target), **metadata}), flush=True)


def command(*args, check=True):
    return subprocess.run([str(v) for v in args], check=check)


def link(path, target):
    """Atomically replace only our own symlink, never an ordinary user path."""
    if path.exists() and not path.is_symlink():
        raise RuntimeError(f"Refusing to replace a non-symlink: {path}")
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".new")
    temporary.symlink_to(target, target_is_directory=True)
    os.replace(temporary, path)


def listening(port):
    with socket.socket() as connection:
        connection.settimeout(1)
        return connection.connect_ex(("127.0.0.1", port)) == 0


def validate_model_path(value):
    """Reject an obvious bad path before stopping a working GPU service."""
    model = Path(value).resolve()
    config = model / "config.json"
    if not model.is_dir() or not config.is_file():
        raise RuntimeError(f"Model directory with config.json does not exist: {model}")
    try:
        configuration = json.loads(config.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise RuntimeError(f"Model config.json is unreadable or invalid: {model}") from exc
    if not isinstance(configuration, dict) or not configuration:
        raise RuntimeError(f"Model config.json must be a nonempty object: {model}")
    if not any(path.is_file() for pattern in ("*.safetensors", "*.bin") for path in model.glob(pattern)):
        raise RuntimeError(f"Model directory has no local weight files: {model}")
    return model


def _unit_path():
    return Path.home() / ".config" / "systemd" / "user" / UNIT


def _is_active():
    return subprocess.run(["systemctl", "--user", "is-active", "--quiet", UNIT],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def _is_enabled():
    return subprocess.run(["systemctl", "--user", "is-enabled", "--quiet", UNIT],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def _service_status():
    result = subprocess.run(
        ["systemctl", "--user", "show", UNIT, "--property=ActiveState,SubState,NRestarts,ExecMainStatus"],
        capture_output=True, text=True, check=True,
    )
    return dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)


def _snapshot_link(path):
    if path.is_symlink():
        return path.resolve()
    if path.exists():
        raise RuntimeError(f"Refusing to replace a non-symlink: {path}")
    return None


def _write_unit(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    with temporary.open("xb") as handle:
        handle.write(content)
    os.replace(temporary, path)


def _wait_ready(port, metadata, release):
    """Allow slow loading, but fail promptly when systemd restarts a crashed model."""
    deadline, last_error = time.monotonic() + 600, ""
    while time.monotonic() < deadline:
        state = _service_status()
        if (state.get("ActiveState") in {"failed", "inactive"}
                or int(state.get("NRestarts", "0")) > 0):
            raise RuntimeError("Inference service exited or restarted during startup: " + json.dumps(state))
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/capabilities", timeout=5) as response:
                actual = json.load(response)
        except (urllib.error.URLError, OSError, ValueError) as exc:
            last_error = str(exc)
            time.sleep(2)
            continue
        if not isinstance(actual, dict) or actual.get("rule_bundle", {}).get("sha256") != metadata["rule_bundle_sha256"]:
            raise RuntimeError("Started service uses a different rule bundle.")
        if actual.get("runtime_version") != metadata["runtime_version"]:
            raise RuntimeError("Started service uses a different runtime.")
        if actual.get("rule_bundle", {}).get("compiled_grammar_sha256") != metadata["compiled_grammar_sha256"]:
            raise RuntimeError("Started service compiled the rule grammar differently.")
        if not {"plain", "aadl_joint", "json_schema"} <= set(actual.get("constraint_types", [])):
            raise RuntimeError("Started service is missing a required capability.")
        return actual
    raise RuntimeError("Service did not become ready: " + last_error)


def _rollback(root, previous, unit_path, previous_unit, new_unit, *, active, enabled, start_attempted):
    """Restore the complete activation state; immutable releases remain available."""
    errors = []
    def attempt(label, operation):
        try:
            operation()
        except Exception as exc:
            errors.append(f"{label}: {exc}")
    if active or start_attempted:
        attempt("stop new service", lambda: command("systemctl", "--user", "stop", UNIT))
    if not enabled:
        attempt("restore disabled state", lambda: command("systemctl", "--user", "disable", UNIT, check=False))
    for name, target in previous.items():
        path = root / name
        if target is not None:
            attempt(f"restore {name}", lambda p=path, t=target: link(p, t))
        elif path.is_symlink():
            # Only remove links created by this failed activation, never releases.
            attempt(f"remove new {name} link", path.unlink)
        elif path.exists():
            errors.append(f"Unexpected non-symlink at {path}; preserved")
    if previous_unit is not None:
        attempt("restore unit contents", lambda: _write_unit(unit_path, previous_unit))
    elif unit_path.exists():
        if unit_path.read_bytes() == new_unit:
            attempt("remove newly created unit", unit_path.unlink)
        else:
            errors.append("Unit contents changed unexpectedly; preserved")
    attempt("reload restored unit", lambda: command("systemctl", "--user", "daemon-reload"))
    if active and not errors:
        attempt("restart previous service", lambda: command("systemctl", "--user", "start", UNIT))
    if errors:
        raise RuntimeError("Rollback could not fully restore the previous service: " + "; ".join(errors))


def install(args):
    root = Path(args.root).resolve()
    if root == Path("/") or root == Path.home() or root.name != "aadl-single-agent":
        raise RuntimeError("Deployment must target the named project directory.")
    model = validate_model_path(args.model)
    archive_path = Path(args.archive).resolve()
    if not archive_path.is_relative_to(root / "uploads"):
        raise RuntimeError("Archive must be inside the project upload directory.")
    release = root / "releases" / archive_path.name.removesuffix(".tar.gz")
    release.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive_path, "r:gz") as archive:
        for member in archive.getmembers():
            if member.issym() or member.islnk() or not (release / member.name).resolve().is_relative_to(release):
                raise RuntimeError("Unsafe deployment archive member.")
        archive.extractall(release, filter="data")
    metadata = json.loads((release / "deployment.json").read_text())
    active, enabled = _is_active(), _is_enabled()
    if listening(args.port) and not active:
        raise RuntimeError(f"Port {args.port} belongs to another service; no process was stopped.")
    previous = {name: _snapshot_link(root / name) for name in ("current", "current-rules")}
    unit_path = _unit_path()
    if unit_path.is_symlink():
        raise RuntimeError("Refusing to overwrite a symlinked systemd unit.")
    previous_unit = unit_path.read_bytes() if unit_path.exists() else None
    environment = root / ".venv"
    python = environment / "bin" / "python"
    if metadata["action"] == "Deploy":
        if not python.is_file():
            print("Creating isolated environment with existing model libraries.", flush=True)
            command(args.base_python, "-m", "venv", "--system-site-packages", environment)
        print("Installing this release and required dependencies.", flush=True)
        command(python, "-m", "pip", "install", "hatchling==1.33.0")
        # The unit selects source via PYTHONPATH=current/src; a normal wheel
        # supplies metadata/dependencies without an editable-build dependency.
        command(python, "-m", "pip", "install", "--no-build-isolation",
                str(release / "project") + "[server]")
    elif not python.is_file() or previous["current"] is None or previous["current-rules"] is None:
        raise RuntimeError("Run Deploy once before updating only rules.")
    bundle_path = release / "bundle"
    check_code = ("from aadl_single_agent.rules import load_bundle; import sys; "
                  "b=load_bundle(sys.argv[1]); assert b.digest==sys.argv[2]; "
                  "assert b.runtime_version==sys.argv[3]; print('Rule bundle verified:',b.digest)")
    command(python, "-c", check_code, bundle_path, metadata["rule_bundle_sha256"], metadata["runtime_version"])
    new_unit = (
        "[Unit]\nDescription=AADL single-agent joint decoding service\nAfter=network.target\n\n"
        "[Service]\nType=simple\n"
        f"WorkingDirectory={root}/current\nEnvironment=PYTHONPATH={root}/current/src\n"
        "Environment=PYTHONUNBUFFERED=1\n"
        f"ExecStart={python} -m aadl_single_agent.server --model-path {model} "
        f"--host 0.0.0.0 --port {args.port} --rules {root}/current-rules\n"
        "Restart=on-failure\nRestartSec=5\nTimeoutStopSec=900\n\n"
        "[Install]\nWantedBy=default.target\n").encode("utf-8")
    start_attempted = False
    try:
        # Every activation step, including the initial stop, shares one recovery
        # boundary. Link/write/systemctl failures cannot bypass rollback.
        if active:
            print("Stopping the managed inference service.", flush=True)
            command("systemctl", "--user", "stop", UNIT)
        if listening(args.port):
            raise RuntimeError(f"Port {args.port} is still occupied; refusing to start.")
        if metadata["action"] == "Deploy":
            link(root / "current", release / "project")
        link(root / "current-rules", bundle_path)
        _write_unit(unit_path, new_unit)
        command("systemctl", "--user", "daemon-reload")
        command("systemctl", "--user", "enable", UNIT)
        # NRestarts must describe this activation, not crashes of an older unit.
        # Reset failures inside the same rollback boundary as all other steps.
        command("systemctl", "--user", "reset-failed", UNIT)
        start_attempted = True
        command("systemctl", "--user", "start", UNIT)
        print("Waiting for model loading and capability verification.", flush=True)
        actual = _wait_ready(args.port, metadata, release)
    except BaseException as exc:
        try:
            _rollback(root, previous, unit_path, previous_unit, new_unit,
                      active=active, enabled=enabled, start_attempted=start_attempted)
        except Exception as recovery:
            raise RuntimeError(f"Deployment failed: {exc}. {recovery}") from exc
        raise
    print(json.dumps({"status": "ready", "release": str(release), "capabilities": actual}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    packing = sub.add_parser("pack")
    for flag in ["project", "rules", "archive", "action"]:
        packing.add_argument("--" + flag, required=True)
    installing = sub.add_parser("install")
    for flag in ["root", "archive", "base-python", "model"]:
        installing.add_argument("--" + flag, required=True)
    installing.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    (pack if args.command == "pack" else install)(args)


if __name__ == "__main__":
    main()
