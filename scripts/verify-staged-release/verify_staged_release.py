#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Check a staged release against what the Central Portal refuses.

Every artifact the BOM manages must be staged with its jar, sources and
javadoc. Each file must be signed and checksummed, each jar must carry its
module's changelog, and each POM must carry the metadata Central requires. The
signing key's public half must be in the keyring.

    uv run scripts/verify-staged-release/verify_staged_release.py 0.1.2
"""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import NamedTuple

DEFAULT_REPOSITORY = Path("build/staging-repository")
POM_ELEMENTS = (
    "<name>",
    "<description>",
    "<url>",
    "<license>",
    "<developer>",
    "<scm>",
    "<connection>",
)
SOURCE_FILE = re.compile(r"\.(java|kt|scala|clj)$")
ARTIFACT_ID = re.compile(r"<artifactId>(libtmux[^<]*)</artifactId>")


class Report(NamedTuple):
    """What a check of one staging found."""

    artifacts: tuple[str, ...]
    problems: tuple[str, ...]


def bom_artifacts(bom: str) -> tuple[str, ...]:
    """Return the artifact ids a BOM names, sorted and without repeats.

    >>> bom_artifacts("<artifactId>libtmux-b</artifactId><artifactId>libtmux-a</artifactId>"
    ...               "<artifactId>libtmux-b</artifactId><artifactId>other</artifactId>")
    ('libtmux-a', 'libtmux-b')
    """
    return tuple(sorted(set(ARTIFACT_ID.findall(bom))))


def module_of(artifact: str) -> str:
    """Return the module an artifact is built from.

    A Scala artifact's id ends in the binary version it was compiled for; its
    module's does not.

    >>> module_of("libtmux-scala_3")
    'libtmux-scala'
    >>> module_of("libtmux")
    'libtmux'
    """
    return artifact.removesuffix("_3")


def entries(jar: Path) -> list[str]:
    """Return the names of a jar's entries."""
    with zipfile.ZipFile(jar) as archive:
        return archive.namelist()


def signature_verifies(signature: Path, signed: Path) -> bool:
    """Return whether gpg accepts a detached signature of a file."""
    try:
        done = subprocess.run(
            ["gpg", "--batch", "--verify", str(signature), str(signed)],
            capture_output=True,
            check=False,
        )
    except FileNotFoundError:
        return False
    return done.returncode == 0


def check_files(artifact: str, directory: Path, version: str, pom: Path) -> list[str]:
    """Return what is wrong with the files an artifact is staged as."""
    problems: list[str] = []
    names = [f"{artifact}-{version}.pom"]
    if "<packaging>pom</packaging>" not in pom.read_text():
        stem = f"{artifact}-{version}"
        names += [f"{stem}.jar", f"{stem}-sources.jar", f"{stem}-javadoc.jar"]
    for name in names:
        file = directory / name
        if not file.is_file():
            problems.append(f"{artifact}: missing {name}")
            continue
        problems += [
            f"{artifact}: missing {name}.{suffix}"
            for suffix in ("asc", "md5", "sha1")
            if not (directory / f"{name}.{suffix}").is_file()
        ]
        sha1 = directory / f"{name}.sha1"
        if sha1.is_file():
            recorded = sha1.read_text().split(maxsplit=1)[:1]
            if recorded != [hashlib.sha1(file.read_bytes()).hexdigest()]:
                problems.append(f"{artifact}: {name}.sha1 does not match")
        signature = directory / f"{name}.asc"
        if signature.is_file() and not signature_verifies(signature, file):
            problems.append(f"{artifact}: {name}.asc does not verify")
    return problems


def check_pom(artifact: str, pom: Path) -> list[str]:
    """Return what the Central Portal would refuse in an artifact's POM."""
    text = pom.read_text()
    problems = [
        f"{artifact}: POM has no {element}" for element in POM_ELEMENTS if element not in text
    ]
    if "-SNAPSHOT<" in text:
        problems.append(f"{artifact}: POM names a snapshot")
    return problems


def check_jars(artifact: str, directory: Path, version: str) -> list[str]:
    """Return what is wrong inside an artifact's javadoc, main and sources jars."""
    problems: list[str] = []
    stem = directory / f"{artifact}-{version}"
    javadoc = Path(f"{stem}-javadoc.jar")
    if javadoc.is_file() and "index.html" not in entries(javadoc):
        problems.append(f"{artifact}: javadoc jar has no index.html")
    jar = Path(f"{stem}.jar")
    if jar.is_file():
        module = module_of(artifact)
        changelog = f"META-INF/libtmux/{module}/CHANGELOG.md"
        opening = f"Notable changes to `{module}`."
        if changelog not in entries(jar):
            problems.append(f"{artifact}: jar has no {changelog}")
        else:
            with zipfile.ZipFile(jar) as archive:
                text = archive.read(changelog).decode()
            if opening not in text.splitlines():
                problems.append(f'{artifact}: {changelog} does not open with "{opening}"')
    sources = Path(f"{stem}-sources.jar")
    if sources.is_file() and not any(SOURCE_FILE.search(name) for name in entries(sources)):
        problems.append(f"{artifact}: sources jar holds no source")
    return problems


def check_staging(version: str, repository: Path) -> Report:
    """Check every artifact the staged BOM manages.

    Raises `FileNotFoundError` when the BOM itself is not staged.
    """
    root = repository / "io" / "github" / "libtmux"
    bom = root / "libtmux-bom" / version / f"libtmux-bom-{version}.pom"
    artifacts = bom_artifacts(bom.read_text())
    problems: list[str] = []
    for artifact in artifacts:
        directory = root / artifact / version
        pom = directory / f"{artifact}-{version}.pom"
        if not pom.is_file():
            problems.append(f"{artifact}: not staged")
            continue
        problems += check_files(artifact, directory, version, pom)
        problems += check_pom(artifact, pom)
        problems += check_jars(artifact, directory, version)
    return Report(artifacts, tuple(problems))


def main(argv: list[str] | None = None) -> int:
    """Print every problem as a workflow error and exit nonzero if there is one."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="the release version, such as 0.1.2")
    parser.add_argument(
        "repository",
        nargs="?",
        type=Path,
        default=DEFAULT_REPOSITORY,
        help=f"the staging repository (default {DEFAULT_REPOSITORY})",
    )
    options = parser.parse_args(argv)
    try:
        report = check_staging(options.version, options.repository)
    except FileNotFoundError as error:
        print(f"::error::no staged BOM at {error.filename}")
        return 1
    for problem in report.problems:
        print(f"::error::{problem}")
    if report.problems:
        return 1
    print(f"{len(report.artifacts)} artifacts staged as Central requires them")
    return 0


if __name__ == "__main__":
    sys.exit(main())
