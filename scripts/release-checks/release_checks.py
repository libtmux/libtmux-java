#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""The release workflow's decisions, as commands the workflow calls one at a time.

- `release-version`: the version a tag or a manual run publishes, validated.
- `staged-version`: the version a release would publish from `gradle.properties`.
- `ci-passed`: this commit's CI has a successful run.
- `carry-passphrase`: the signing key's passphrase reaches Gradle only if it has one.

    uv run scripts/release-checks/release_checks.py release-version --ref-name v0.1.2
"""

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import uuid
from collections.abc import Sequence

# Importing a shared module must not leave a __pycache__ in the checkout.
sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "lib"))

import bounded

ROOT = pathlib.Path(__file__).resolve().parents[2]
PUBLISHABLE = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|milestone|rc)\.[0-9]+)?")
PASSPHRASE_PROPERTY = "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword"
# Seconds `gh run list` may take: it is one API request.
NETWORK_SECONDS = 60


class ReleaseCheckError(Exception):
    """A release check failed; the message is the annotation to print."""


def release_version(input_version: str, ref_name: str) -> str:
    """Return the version to publish, or refuse one this project does not publish.

    A tag names the version and a manual run states it. The value reaches a
    command line, and a tag name is whatever the person pushing it typed, so
    anything outside the publishable shape is refused. That also catches the
    spellings Maven orders wrongly; RELEASING.md says why 0.0.1-a.1 sorts after
    0.0.1.

    >>> release_version("", "v0.1.2")
    '0.1.2'
    >>> release_version("0.2.0-rc.1", "main")
    '0.2.0-rc.1'
    """
    version = input_version or ref_name.removeprefix("v")
    if version.endswith("-SNAPSHOT"):
        message = f"::error::refusing to release a snapshot: {version}"
        raise ReleaseCheckError(message)
    if PUBLISHABLE.fullmatch(version) is None:
        message = f"::error::not a version this project publishes: {version}"
        raise ReleaseCheckError(message)
    return version


def staged_version(properties: pathlib.Path) -> str:
    """Return the `libtmuxVersion` of a properties file without its `-SNAPSHOT`.

    >>> import tempfile
    >>> with tempfile.TemporaryDirectory() as directory:
    ...     path = pathlib.Path(directory) / "gradle.properties"
    ...     _ = path.write_text("a=b\\nlibtmuxVersion=0.2.0-SNAPSHOT\\n")
    ...     staged_version(path)
    '0.2.0'
    """
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("libtmuxVersion="):
            return line.removeprefix("libtmuxVersion=").removesuffix("-SNAPSHOT")
    message = f"{properties} has no libtmuxVersion"
    raise ReleaseCheckError(message)


def ci_passed(workflow: str, commit: str) -> None:
    """Fail unless the workflow has a successful run for a commit.

    CI is not re-run for a release: a run still in progress, or never made for
    this commit, refuses it, and the release is started again once it has passed.
    """
    try:
        listed = bounded.run(
            [
                "gh",
                "run",
                "list",
                "--workflow",
                workflow,
                "--commit",
                commit,
                "--limit",
                "50",
                "--json",
                "conclusion",
            ],
            capture_output=True,
            text=True,
            check=True,
            timeout=NETWORK_SECONDS,
        )
    except (OSError, subprocess.SubprocessError) as error:
        message = f"::error::cannot list the runs of {workflow} for {commit}: {error}"
        raise ReleaseCheckError(message) from error
    passed = sum(1 for run in json.loads(listed.stdout) if run.get("conclusion") == "success")
    if passed < 1:
        message = (
            f"::error::{workflow} has no successful run for {commit}; "
            "let it pass, then release again"
        )
        raise ReleaseCheckError(message)


def append_environment(path: pathlib.Path, name: str, value: str) -> None:
    """Append `name=value` to a GitHub environment file, safely for any value."""
    delimiter = f"ghadelimiter_{uuid.uuid4().hex}"
    with path.open("a", encoding="utf-8") as handle:
        handle.write(f"{name}<<{delimiter}\n{value}\n{delimiter}\n")


def main(argv: Sequence[str] | None = None) -> int:
    """Run the requested check and answer the process status."""
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    release = commands.add_parser("release-version", help="validate the version to publish")
    release.add_argument("--input", default=os.environ.get("INPUT_VERSION", ""))
    release.add_argument("--ref-name", default=os.environ.get("REF_NAME", ""))
    commands.add_parser("staged-version", help="export the version a release would publish")
    passed = commands.add_parser("ci-passed", help="require a successful CI run for the commit")
    passed.add_argument("--workflow", default="ci.yml")
    passed.add_argument("--commit", default=os.environ.get("GITHUB_SHA", ""))
    commands.add_parser("carry-passphrase", help="pass the signing passphrase on if there is one")
    options = parser.parse_args(argv)
    try:
        if options.command == "release-version":
            version = release_version(options.input, options.ref_name)
            with pathlib.Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as handle:
                handle.write(f"version={version}\n")
            print(f"releasing {version}")
        elif options.command == "staged-version":
            version = staged_version(ROOT / "gradle.properties")
            append_environment(pathlib.Path(os.environ["GITHUB_ENV"]), "VERSION", version)
        elif options.command == "ci-passed":
            ci_passed(options.workflow, options.commit)
            print(f"{options.workflow} passed on {options.commit}")
        else:
            # A signing key may or may not carry a passphrase, and an unset secret arrives
            # as an empty string rather than as nothing. The publisher does not treat blank
            # as absent, so an empty value is offered as the passphrase and decryption fails.
            passphrase = os.environ.get("PASSPHRASE", "")
            if passphrase:
                append_environment(
                    pathlib.Path(os.environ["GITHUB_ENV"]), PASSPHRASE_PROPERTY, passphrase
                )
                print("signing key has a passphrase")
            else:
                print("signing key has no passphrase")
    except ReleaseCheckError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
