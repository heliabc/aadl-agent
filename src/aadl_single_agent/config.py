"""Model connection and local validator configuration, without provider names."""

from dataclasses import dataclass
from pathlib import Path

import yaml


@dataclass(frozen=True)
class Config:
    # The configuration file is the single source of the service address; a
    # missing setting must not silently fall back to a different local backend.
    base_url: str
    osate_cli: Path = Path(".tools/osate-cli-0.3.0/bin/osate-cli.bat")

    @classmethod
    def load(cls, path: str | Path = "config.yaml") -> "Config":
        # Reject misspellings rather than silently connecting to the default host.
        config_path = Path(path).expanduser().resolve()
        try:
            data = yaml.safe_load(config_path.read_text(encoding="utf-8-sig"))
        except yaml.YAMLError as exc:
            raise ValueError(f"Invalid YAML configuration: {exc}") from exc
        if (not isinstance(data, dict) or "base_url" not in data
                or set(data) - {"base_url", "osate_cli"}):
            raise ValueError("Configuration requires base_url and accepts only base_url and osate_cli.")
        value = data["base_url"]
        if not isinstance(value, str) or not value.strip():
            raise ValueError("base_url must be a nonempty HTTP(S) API URL.")
        executable = data.get("osate_cli", ".tools/osate-cli-0.3.0/bin/osate-cli.bat")
        if not isinstance(executable, str) or not executable.strip():
            raise ValueError("osate_cli must be a nonempty path to the OSATE CLI launcher file.")
        executable = Path(executable.strip()).expanduser()
        # Moving the shell's working directory must not change which validator
        # a configuration selects. Existing installations need no copying.
        if not executable.is_absolute():
            executable = config_path.parent / executable
        return cls(base_url=value.strip(), osate_cli=executable.resolve())
