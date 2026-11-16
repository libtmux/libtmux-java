#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Fail when a tmux server outlived the suite.

A real-tmux suite that leaves servers running goes unnoticed in CI, because the
runner is discarded after the job, and costs memory on a developer's machine.
Run this after the suite:

    uv run scripts/no-tmux-survivors/no_tmux_survivors.py

It lists each survivor's command line as a `::error::` annotation and exits 1.
"""

import subprocess
import sys
from collections.abc import Sequence
from pathlib import Path

# Importing a shared module must not leave a __pycache__ in the checkout.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

import bounded


def survivor_pids() -> list[int]:
    """Return the pid of every process whose name matches `tmux`.

    `pgrep` exits 1 when nothing matches, and anything higher is a real failure.
    """
    found = bounded.run(["pgrep", "tmux"], capture_output=True, text=True, timeout=bounded.PROBE)
    if found.returncode not in (0, 1):
        message = f"pgrep failed with status {found.returncode}: {found.stderr.strip()}"
        raise RuntimeError(message)
    return [int(line) for line in found.stdout.split()]


def command_line(pid: int) -> str:
    """Return the command line of a process, or an empty string when it has gone."""
    shown = bounded.run(
        ["ps", "-o", "command=", "-p", str(pid)],
        capture_output=True,
        text=True,
        timeout=bounded.PROBE,
    )
    return shown.stdout.strip()


def report(pids: Sequence[int], lines: Sequence[str]) -> str:
    """Return the annotation for the survivors, or the all-clear.

    >>> report([], [])
    'no tmux server survived the suite'
    >>> report([7], ["tmux new-session"])
    '::error::1 tmux server(s) survived the suite\\ntmux new-session'
    """
    if not pids:
        return "no tmux server survived the suite"
    return "\n".join(
        [
            f"::error::{len(pids)} tmux server(s) survived the suite",
            *(line for line in lines if line),
        ]
    )


def main(argv: Sequence[str] | None = None) -> int:
    """Look for survivors and answer the process status."""
    if argv:
        print("usage: no_tmux_survivors.py", file=sys.stderr)
        return 2
    try:
        pids = survivor_pids()
        print(report(pids, [command_line(pid) for pid in pids]))
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"::error::cannot look for tmux servers: {error}", file=sys.stderr)
        return 1
    return 1 if pids else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
