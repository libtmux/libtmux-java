#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Build every tmux release the matrix tests into a tree the matrix can use.

    uv run scripts/build-tmux-matrix/build_tmux_matrix.py ~/tmux-builds

Then:

    ./gradlew testTmuxMatrix -PlibtmuxMatrix=~/tmux-builds

The releases are read from `gradle/tmux-lanes.json`, so this cannot drift from
what the matrix runs. CI builds the same releases one per runner; this is the
same thing on one machine. A release that already has `bin/tmux` is kept.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "gradle/tmux-lanes.json"
RELEASES = "https://github.com/tmux/tmux/releases/download"
DEFAULT_DESTINATION = Path("~/tmux-builds")


class ManifestError(Exception):
    """The manifest names no release to build."""


def read_releases(manifest: Path) -> list[str]:
    """Return the releases the matrix runs: the supported range, then the refusal lanes.

    `testTmuxMatrix` depends on both, so a tree without the second is not a
    matrix. Previews are branches and development builds, which are not built
    from a release archive.
    """
    try:
        document = json.loads(manifest.read_text())
        releases = [*document["lanes"], *document["rejectionLanes"]]
    except (json.JSONDecodeError, KeyError, TypeError) as error:
        message = f"{manifest.name} lists no lanes: {error}"
        raise ManifestError(message) from error
    if not releases or not all(isinstance(release, str) for release in releases):
        message = f"{manifest.name} needs lists of release names"
        raise ManifestError(message)
    return releases


def archive_url(base: str, release: str) -> str:
    """Return where a release's source archive is published.

    >>> archive_url("https://example.test/dl", "3.3a")
    'https://example.test/dl/3.3a/tmux-3.3a.tar.gz'
    """
    return f"{base}/{release}/tmux-{release}.tar.gz"


def run(command: list[str], cwd: Path) -> None:
    """Run a build step, hiding its output unless it fails."""
    done = subprocess.run(command, cwd=cwd, capture_output=True, text=True, check=False)
    if done.returncode != 0:
        sys.stderr.write(done.stdout + done.stderr)
        message = f"{' '.join(command)} failed with status {done.returncode}"
        raise RuntimeError(message)


def build(release: str, destination: Path, base: str = RELEASES) -> Path:
    """Build one release under `destination/<release>` and return its tmux."""
    prefix = destination / release
    with tempfile.TemporaryDirectory() as scratch:
        work = Path(scratch)
        archive = work / "tmux.tar.gz"
        urllib.request.urlretrieve(archive_url(base, release), archive)
        with tarfile.open(archive) as tar:
            tar.extractall(work, filter="data")
        source = work / f"tmux-{release}"
        jobs = str(os.cpu_count() or 2)
        run(["./configure", f"--prefix={prefix}"], source)
        run(["make", f"-j{jobs}"], source)
        run(["make", "install"], source)
    return prefix / "bin" / "tmux"


def version_of(binary: Path) -> str:
    """Return what a built tmux reports for `-V`."""
    done = subprocess.run([str(binary), "-V"], capture_output=True, text=True, check=True)
    return done.stdout.strip()


def main(argv: list[str] | None = None) -> int:
    """Build the releases that are missing and print each one's version."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "destination",
        nargs="?",
        type=Path,
        default=DEFAULT_DESTINATION,
        help=f"the tree to build into (default {DEFAULT_DESTINATION})",
    )
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--base-url", default=RELEASES, help="where release archives are published")
    options = parser.parse_args(argv)
    destination = options.destination.expanduser().absolute()
    try:
        releases = read_releases(options.manifest)
    except ManifestError as error:
        print(error, file=sys.stderr)
        return 1
    print(f"lanes: {' '.join(releases)}")
    print(f"destination: {destination}")
    for release in releases:
        binary = destination / release / "bin" / "tmux"
        if os.access(binary, os.X_OK):
            print(f"== tmux {release} already built")
            continue
        print(f"== building tmux {release}")
        build(release, destination, options.base_url)
    print()
    for release in releases:
        print(f"{release:<6} {version_of(destination / release / 'bin' / 'tmux')}")
    print()
    print(f"./gradlew testTmuxMatrix -PlibtmuxMatrix={destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
