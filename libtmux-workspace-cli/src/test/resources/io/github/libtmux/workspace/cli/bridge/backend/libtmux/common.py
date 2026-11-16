"""The constants the bridge reads from Python libtmux, set from `settings.json` beside the package.

ExecutionTest writes `settings.json` with the tmux floor the backend claims and, when
a case needs one, the optional-feature policy version. A key left out of the file
leaves the constant undefined, which is what a backend that predates it looks like.
"""

import json
from pathlib import Path

_settings = json.loads(Path(__file__).parents[1].joinpath("settings.json").read_text())

TMUX_MIN_VERSION = _settings["floor"]
if "policy" in _settings:
    TMUX_OPTIONAL_FEATURE_POLICY_VERSION = _settings["policy"]
