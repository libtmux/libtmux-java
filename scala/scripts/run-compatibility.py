#!/usr/bin/env python3
"""Run selected Linux cells from the factorized Scala compatibility matrix."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[2]
SCALA = ("2.13.18", "3.3.8")
TMUX = ("3.2a", "3.3", "3.3a", "3.4", "3.5", "3.6", "3.7", "3.7a", "3.7b")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def cell(name, jdk, scala, tmux, primary):
    return {"id": name, "os": "linux", "jdk": jdk, "scala": scala,
            "tmux": tmux, "primary": primary, "status": "UNVERIFIED"}


def cells():
    result = {}
    for index, (jdk, scala) in enumerate(
            ((21, "2.13.18"), (21, "3.3.8"), (25, "2.13.18"), (25, "3.3.8")), 1):
        name = "P%02d" % index
        result[name] = cell(name, jdk, scala, "3.7c", True)
    for index, version in enumerate(TMUX):
        for scala_index, scala in enumerate(SCALA):
            name = "T%02d" % (index * 2 + scala_index + 1)
            result[name] = cell(name, 21, scala, version, False)
    for index, (jdk, scala) in enumerate(
            ((21, "2.13.18"), (21, "3.3.8"), (25, "2.13.18"), (25, "3.3.8")), 5):
        name = "P%02d" % index
        result[name] = {**cell(name, jdk, scala, "3.7c", True), "os": "macos"}
    return result


def source_identity():
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True, text=True,
        stdout=subprocess.PIPE,
    )
    sources = []
    for path in (ROOT / "scala").rglob("*"):
        if path.is_file() and "target" not in path.relative_to(ROOT).parts:
            sources.append([str(path.relative_to(ROOT)), sha256(path)])
    return {"git_head": completed.stdout.strip(), "scala_files": sorted(sources)}


def stage_identity(stage):
    result = {}
    for path in stage.rglob("*"):
        if path.is_file() and path.suffix in (".jar", ".pom"):
            result[str(path.relative_to(stage))] = sha256(path)
    if not result:
        raise ValueError("No staged Scala archives or POMs: " + str(stage))
    return result


def command_for(selected):
    tasks = ["++" + selected["scala"]]
    if selected["primary"]:
        tasks += ["scalafmtSbtCheck", "scalafmtCheckAll", "core/test", "cats/test"]
    tasks += ["integration/test", "examples/test"]
    if selected["primary"]:
        tasks += [
            "core/doc",
            "cats/doc",
            "core/package",
            "cats/package",
            "benchmarks/compile",
        ]
    return [str(ROOT / "scala/sbtw"), *tasks]


def command_output(command, environment, log):
    started = time.monotonic()
    with log.open("w") as output:
        completed = subprocess.run(
            command, cwd=ROOT, env=environment, stdout=output,
            stderr=subprocess.STDOUT, timeout=300,
        )
    return completed.returncode, time.monotonic() - started


def test_totals(log):
    values = [int(value) for value in re.findall(r"(?:Passed|Failed): Total (\d+),", log.read_text())]
    if not values or not all(value > 0 for value in values):
        raise ValueError("No positive test totals in " + str(log))
    return values


def verify_tool(path, expected):
    if not path.is_file() or not os.access(path, os.X_OK):
        raise ValueError("Missing executable: " + str(path))
    actual = subprocess.check_output([str(path), "-V"], text=True).strip()
    if actual != "tmux " + expected:
        raise ValueError("Wrong tmux executable: " + actual)
    return actual


def run(selected, args):
    java_home = args.jdk21 if selected["jdk"] == 21 else args.jdk25
    java = java_home / "bin/java"
    if not java.is_file():
        raise ValueError("Missing selected JDK: " + str(java_home))
    java_version = subprocess.check_output([str(java), "-version"], stderr=subprocess.STDOUT,
                                           text=True).strip()
    if not re.search(r'version "' + str(selected["jdk"]) + r'[."]', java_version):
        raise ValueError("Wrong selected JDK: " + java_version)
    tmux = args.tmux_root / selected["tmux"] / "bin/tmux"
    tmux_version = verify_tool(tmux, selected["tmux"])
    environment = os.environ.copy()
    for key in ("TMUX", "TMUX_PANE", "CLASSPATH"):
        environment.pop(key, None)
    environment.update({
        "JAVA_HOME": str(java_home),
        "LIBTMUX_SCALA_EXPECTED_JAVA_HOME": str(java_home),
        "LIBTMUX_JAVA_VERSION": args.java_version,
        "LIBTMUX_SCALA_VERSION": args.scala_version,
        "LIBTMUX_JAVA_REPOSITORY": str(args.java_stage),
        "LIBTMUX_SCALA_STAGING": str(args.scala_stage),
        "TMUX_TEST_BINARY": str(tmux),
    })
    log = args.output / (selected["id"] + ".log")
    command = command_for(selected)
    exit_code, elapsed = command_output(command, environment, log)
    record = {"command": command, "elapsed_seconds": elapsed, "exit": exit_code,
              "java": java_version, "tmux": tmux_version, "log": log.name}
    if exit_code:
        raise ValueError("Cell failed: " + selected["id"])
    record["test_totals"] = test_totals(log)
    leftovers = sorted(
        path for path in Path("/tmp/libtmux-java-test").iterdir()
        if re.fullmatch(r"scala-\d+-.*", path.name)
    )
    if leftovers:
        raise ValueError("Owned tmux directories survived: " + repr([str(path) for path in leftovers]))
    record["cleanup"] = "PASS"
    return record


def selected_cells(value, matrix):
    if value == "linux":
        return [name for name, cell in matrix.items() if cell["os"] == "linux"]
    result = [name for name in value.split(",") if name]
    unknown = sorted(set(result) - set(matrix))
    if unknown:
        raise ValueError("Unknown matrix cell: " + ", ".join(unknown))
    if any(matrix[name]["os"] != "linux" for name in result):
        raise ValueError("This runner cannot execute macOS cells")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk21", type=Path, required=True)
    parser.add_argument("--jdk25", type=Path, required=True)
    parser.add_argument("--tmux-root", type=Path, required=True)
    parser.add_argument("--scala-stage", type=Path, required=True)
    parser.add_argument("--java-stage", type=Path, required=True)
    parser.add_argument("--scala-version", required=True)
    parser.add_argument("--java-version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cells", default="linux")
    args = parser.parse_args()
    if platform.system() != "Linux":
        raise SystemExit("This runner records Linux cells only")
    for name in ("jdk21", "jdk25", "tmux_root", "scala_stage", "java_stage", "output"):
        setattr(args, name, getattr(args, name).resolve())
    matrix = cells()
    identity = {"source": source_identity(), "scala_stage": stage_identity(args.scala_stage)}
    report_path = args.output / "matrix.json"
    args.output.mkdir(parents=True, exist_ok=True)
    if report_path.exists():
        report = json.loads(report_path.read_text())
        if report.get("identity") != identity:
            raise SystemExit("Matrix identity changed; use a new output directory")
    else:
        report = {"identity": identity, "cells": matrix, "runs": {}}
    failures = []
    for name in selected_cells(args.cells, report["cells"]):
        selected = report["cells"][name]
        selected["status"] = "RUNNING"
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        try:
            report["runs"][name] = run(selected, args)
            selected["status"] = "PASS"
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            selected["status"] = "FAIL"
            report["runs"][name] = {"error": str(error)}
            failures.append(name)
        report_path.write_text(json.dumps(report, indent=2) + "\n")
    if failures:
        raise SystemExit("Failed compatibility cells: " + ", ".join(failures))


if __name__ == "__main__":
    main()
