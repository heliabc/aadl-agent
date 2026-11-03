"""Run the research CLI from a checkout without requiring an editable install."""
from pathlib import Path
import sys

# Prefer this checkout over any older installed aadl-single-agent package.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from aadl_single_agent.experiments import main


if __name__ == "__main__":
    raise SystemExit(main())
