#!/usr/bin/env python3
"""Verify every signed artifact in a local Scala Maven stage."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
GROUP_PATH = Path("io/github/libtmux")
CHECKSUMS = ("md5", "sha1")


def digest(path, algorithm):
    return hashlib.new(algorithm, path.read_bytes()).hexdigest()


def declared(manifest):
    rows = [line for line in manifest.read_text().splitlines() if line]
    coordinate = re.compile(r"io\.github\.libtmux:([A-Za-z0-9_.-]+)")
    modules = []
    for row in rows:
        matched = coordinate.fullmatch(row)
        if not matched:
            raise ValueError("Invalid Scala publication declaration: " + row)
        modules.append(matched.group(1))
    if not modules or len(modules) != len(set(modules)):
        raise ValueError("Scala publication declarations must be unique and nonempty")
    return tuple(sorted(modules))


def artifact_names(module, version):
    stem = module + "-" + version
    return (stem + ".jar", stem + "-sources.jar", stem + "-javadoc.jar", stem + ".pom")


def expected_files(module, version):
    result = set()
    for artifact in artifact_names(module, version):
        result.add(artifact)
        result.add(artifact + ".asc")
        for checksum in CHECKSUMS:
            result.add(artifact + "." + checksum)
            result.add(artifact + ".asc." + checksum)
    return result


def verify_checksum(path, algorithm):
    value = path.with_name(path.name + "." + algorithm).read_text().strip()
    if value != digest(path, algorithm):
        raise ValueError("Wrong " + algorithm + " checksum: " + path.name)


def verify_signature(gpg, signature, artifact):
    completed = subprocess.run(
        [str(gpg), "--batch", "--status-fd", "1", "--verify", str(signature), str(artifact)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode:
        raise ValueError("Invalid detached signature: " + signature.name)
    values = [line.split()[2] for line in completed.stdout.splitlines()
              if line.startswith("[GNUPG:] VALIDSIG ")]
    if len(values) != 1 or not re.fullmatch(r"[A-F0-9]{40,}", values[0]):
        raise ValueError("Missing validated signature fingerprint: " + signature.name)
    return values[0]


def verify(stage, manifest, version, gpg):
    modules = declared(manifest)
    fingerprints = set()
    artifacts = {}
    for module in modules:
        directory = stage / GROUP_PATH / module / version
        if not directory.is_dir():
            raise ValueError("Missing signed Scala artifact directory: " + module)
        actual = {path.name for path in directory.iterdir() if path.is_file()}
        expected = expected_files(module, version)
        if actual != expected:
            raise ValueError("Unexpected/missing signed files for " + module + ": " +
                             repr(sorted(actual ^ expected)))
        digests = {}
        for name in artifact_names(module, version):
            artifact = directory / name
            for algorithm in CHECKSUMS:
                verify_checksum(artifact, algorithm)
                verify_checksum(artifact.with_name(artifact.name + ".asc"), algorithm)
            fingerprints.add(verify_signature(gpg, artifact.with_name(name + ".asc"), artifact))
            digests[name] = digest(artifact, "sha256")
        artifacts[module] = digests
    if len(fingerprints) != 1:
        raise ValueError("Signed artifacts use more than one signing key")
    return {"modules": modules, "sha256": artifacts,
            "signature_fingerprint": next(iter(fingerprints))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--gpg", type=Path, default=Path("gpg"))
    parser.add_argument("--manifest", type=Path, default=ROOT / "libtmux-scala/publications.txt")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.stage.resolve(), args.manifest.resolve(), args.version, args.gpg)
    except (OSError, ValueError) as error:
        raise SystemExit(str(error))
    rendered = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered)
    else:
        sys.stdout.write(rendered)


if __name__ == "__main__":
    main()
