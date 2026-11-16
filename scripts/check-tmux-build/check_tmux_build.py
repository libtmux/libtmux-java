#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Check that the tmux a lane built is the one it is named after.

The `build-tmux` action can restore a cached build, so a lane that silently runs
another release proves nothing about the one it names. The matrix builds each
release under `~/tmux-builds/<slug>`; this reads the version that build reports:

    uv run scripts/check-tmux-build/check_tmux_build.py \
        --slug 3.8 --ref 3.8 --verify release

`--verify release` requires `tmux <ref>` exactly, `development` requires a
`tmux next-X.Y` build, and `none` only prints what the build reports. A client
build, such as the one that reaches the daemons of a release below the floor, is
checked as a release of its own with `--client`.
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys
from collections.abc import Sequence

BUILDS = pathlib.Path.home() / "tmux-builds"
MODES = ("release", "development", "none")


def reported_version(slug: str, builds: pathlib.Path | None = None) -> str:
    """Return what the tmux built under `slug` prints for `-V`."""
    binary = (builds or BUILDS) / slug / "bin" / "tmux"
    done = subprocess.run([str(binary), "-V"], capture_output=True, text=True, check=True)
    return done.stdout.strip()


def mismatch(reference: str, reported: str, mode: str) -> str | None:
    """Return the annotation for a build that is not what its lane names, or None.

    >>> mismatch("3.8", "tmux 3.8", "release")
    >>> mismatch("3.8", "tmux 3.8a", "release")
    '::error::the 3.8 build reports tmux 3.8a'
    >>> mismatch("master", "tmux next-3.9", "development")
    >>> mismatch("master", "tmux 3.8", "development")
    '::error::master reports tmux 3.8'
    """
    if mode == "release" and reported.partition(" ")[2] != reference:
        return f"::error::the {reference} build reports {reported}"
    if mode == "development" and not reported.startswith("tmux next-"):
        return f"::error::{reference} reports {reported}"
    return None


def check(slug: str, reference: str, mode: str, builds: pathlib.Path | None = None) -> bool:
    """Print what a build reports, and say whether it is the one its lane names."""
    reported = reported_version(slug, builds)
    print(f"{reference}: {reported}")
    problem = mismatch(reference, reported, mode)
    if problem is not None:
        print(problem)
    return problem is None


def main(argv: Sequence[str] | None = None) -> int:
    """Check the lane's build, and its client's when it has one."""
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("--slug", required=True, help="the directory under ~/tmux-builds")
    parser.add_argument("--ref", required=True, help="the release or ref the lane names")
    parser.add_argument("--verify", choices=(*MODES, ""), default="none", help="empty means none")
    parser.add_argument("--client", default="", help="the release of the client build, if any")
    options = parser.parse_args(argv)
    ok = check(options.slug, options.ref, options.verify or "none")
    if options.client:
        ok = check(options.client, options.client, "release") and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
