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

Each archive is checked against the SHA256 that the `build-tmux` action of
`libtmux/.github` pins for that release, read at the commit `tmux-matrix.yml`
pins, so a build here starts from the bytes CI starts from.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "gradle/tmux-lanes.json"
WORKFLOW = ROOT / ".github/workflows/tmux-matrix.yml"
RELEASES = "https://github.com/tmux/tmux/releases/download"
DIGESTS = (
    "https://raw.githubusercontent.com/libtmux/.github/{commit}"
    "/actions/build-tmux/tmux-releases.json"
)
SHARED_ACTION_PIN = re.compile(r"libtmux/\.github/actions/build-tmux@([0-9a-f]{40})")
DEFAULT_DESTINATION = Path("~/tmux-builds")
# A stalled connection ends the download; the limit is per socket operation.
DOWNLOAD_TIMEOUT_SECONDS = 60.0
# One configure or make step. Building tmux 3.3a took 8.5 s to configure and 6.3 s
# to make here, so this only ends a step that has stopped making progress.
STEP_TIMEOUT_SECONDS = 300.0


class ManifestError(Exception):
    """The manifest names no release to build."""


class DigestError(Exception):
    """The pinned SHA256 of a release cannot be read, or the archive does not match it."""


class BuildError(Exception):
    """A release could not be downloaded, unpacked or built."""


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


def pinned_commit(workflow: Path) -> str:
    """Return the commit of `libtmux/.github` that the workflow pins its tmux builds to.

    >>> import tempfile
    >>> with tempfile.TemporaryDirectory() as directory:
    ...     path = Path(directory) / "w.yml"
    ...     _ = path.write_text("uses: libtmux/.github/actions/build-tmux@" + "a" * 40)
    ...     pinned_commit(path) == "a" * 40
    True
    """
    found = SHARED_ACTION_PIN.search(workflow.read_text())
    if found is None:
        message = f"{workflow.name} does not pin the build-tmux action of libtmux/.github"
        raise DigestError(message)
    return found[1]


def read_digests(url: str) -> dict[str, str]:
    """Return each release's pinned SHA256 from the shared action's `tmux-releases.json`."""
    try:
        with urllib.request.urlopen(url, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response:
            document = json.load(response)
    except (OSError, ValueError) as error:
        message = f"cannot read the pinned tmux digests from {url}: {error}"
        raise DigestError(message) from error
    if not isinstance(document, dict):
        message = f"{url} is not a table of release digests"
        raise DigestError(message)
    return {str(release): str(digest) for release, digest in document.items()}


def verify_archive(archive: Path, release: str, digests: dict[str, str]) -> None:
    """Refuse an archive whose SHA256 is not the one pinned for its release."""
    expected = digests.get(release)
    if expected is None:
        message = f"the shared action pins no SHA256 for tmux {release}"
        raise DigestError(message)
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual != expected:
        message = f"tmux {release}: the archive's SHA256 is {actual}, not the pinned {expected}"
        raise DigestError(message)


def download(url: str, archive: Path) -> None:
    """Fetch `url` into `archive`, giving up on a connection that stops answering."""
    try:
        with (
            urllib.request.urlopen(url, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response,
            archive.open("wb") as output,
        ):
            shutil.copyfileobj(response, output)
    except OSError as error:
        message = f"cannot download {url}: {error}"
        raise BuildError(message) from error


def run(command: list[str], cwd: Path) -> None:
    """Run a build step, hiding its output unless it fails."""
    try:
        done = subprocess.run(
            command,
            cwd=cwd,
            capture_output=True,
            text=True,
            check=False,
            timeout=STEP_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as error:
        message = f"{' '.join(command)} did not finish within {STEP_TIMEOUT_SECONDS:g} seconds"
        raise BuildError(message) from error
    if done.returncode != 0:
        sys.stderr.write(done.stdout + done.stderr)
        message = f"{' '.join(command)} failed with status {done.returncode}"
        raise BuildError(message)


def build(release: str, destination: Path, digests: dict[str, str], base: str = RELEASES) -> Path:
    """Build one release under `destination/<release>` and return its tmux."""
    prefix = destination / release
    with tempfile.TemporaryDirectory() as scratch:
        work = Path(scratch)
        archive = work / "tmux.tar.gz"
        download(archive_url(base, release), archive)
        verify_archive(archive, release, digests)
        try:
            with tarfile.open(archive) as tar:
                tar.extractall(work, filter="data")
        except tarfile.TarError as error:
            message = f"tmux {release}: cannot unpack the archive: {error}"
            raise BuildError(message) from error
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
    parser.add_argument(
        "--digests-url",
        help="the tmux-releases.json to verify archives against "
        "(default: the one at the commit tmux-matrix.yml pins)",
    )
    options = parser.parse_args(argv)
    destination = options.destination.expanduser().absolute()
    try:
        releases = read_releases(options.manifest)
    except ManifestError as error:
        print(error, file=sys.stderr)
        return 1
    print(f"lanes: {' '.join(releases)}")
    print(f"destination: {destination}")
    digests: dict[str, str] | None = None
    for release in releases:
        binary = destination / release / "bin" / "tmux"
        if os.access(binary, os.X_OK):
            print(f"== tmux {release} already built")
            continue
        print(f"== building tmux {release}")
        try:
            if digests is None:
                url = options.digests_url or DIGESTS.format(commit=pinned_commit(WORKFLOW))
                digests = read_digests(url)
            build(release, destination, digests, options.base_url)
        except (BuildError, DigestError) as error:
            print(error, file=sys.stderr)
            return 1
    print()
    for release in releases:
        print(f"{release:<6} {version_of(destination / release / 'bin' / 'tmux')}")
    print()
    print(f"./gradlew testTmuxMatrix -PlibtmuxMatrix={destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
