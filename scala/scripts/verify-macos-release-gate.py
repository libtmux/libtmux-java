#!/usr/bin/env python3
"""Verify the dispatch-only macOS primary release gate."""

import argparse
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {
    "P05": ("21", "2.13.18"),
    "P07": ("25", "2.13.18"),
    "P08": ("25", "3.3.8"),
}
TASKS = (
    "scalafmtSbtCheck",
    "scalafmtCheckAll",
    "core/test",
    "cats/test",
    "integration/test",
    "examples/test",
    "core/doc",
    "cats/doc",
    "core/package",
    "cats/package",
    "benchmarks/compile",
)


def require(value, message):
    if not value:
        raise ValueError(message)


def cell_blocks(text):
    return {
        matched.group("id"): matched.group("body")
        for matched in re.finditer(
            r"^\s+- id: (?P<id>P\d+)\n(?P<body>(?:^\s{12}.+\n)+)",
            text,
            re.MULTILINE,
        )
    }


def verify(path):
    text = path.read_text()
    trigger = re.search(
        r"^  workflow_dispatch:\n    inputs:\n      include_macos_release:\n"
        r"(?P<body>(?:^        .+\n)+)",
        text,
        re.MULTILINE,
    )
    require(trigger, "Missing manual macOS release input")
    require(
        "default: false" in trigger.group("body") and
        "type: boolean" in trigger.group("body"),
        "Manual macOS release input must default to false",
    )
    manual = re.search(
        r"^  manual-macos-primary:\n(?P<body>(?:^    .+\n)+)",
        text,
        re.MULTILINE,
    )
    require(manual, "Missing manual macOS release job")
    require(
        "github.event_name == 'workflow_dispatch' && inputs.include_macos_release" in
        manual.group("body"),
        "macOS release job must be dispatch-only",
    )
    require(
        "max-parallel: 1" in text[text.index("  manual-macos-primary:"):],
        "macOS release cells must run one at a time",
    )
    require("runs-on: macos-latest" in text, "Missing macOS runner")
    require("TMUX_LANE: 3.7c" in text, "Missing tmux 3.7c selection")
    require('java-version: ${{ matrix.java }}' in text, "Missing selected JDK")
    require('"++${{ matrix.scala }}"' in text, "Missing selected Scala producer")
    require("TMUX_TEST_BINARY:" in text, "Missing explicit tmux executable")
    require("No tmux server outlived" in text, "Missing cleanup check")
    require(
        "python3 scala/scripts/verify-macos-release-gate.py" in text,
        "Pull-request smoke job does not enforce the manual gate contract",
    )
    smoke_condition = (
        "if: github.event_name != 'workflow_dispatch' || !inputs.include_macos_release"
    )
    for job in ("artifact-stage", "producer-runtime", "installed-consumers"):
        require(
            re.search(
                r"^  " + job + r":\n    " + re.escape(smoke_condition) + r"\n",
                text,
                re.MULTILINE,
            ),
            "Manual release dispatch must skip " + job,
        )
    for task in TASKS:
        require(task in text, "Missing primary task: " + task)
    cells = cell_blocks(text)
    require(set(cells) == set(EXPECTED), "Wrong release cells: " + repr(sorted(cells)))
    for cell, (java, scala) in EXPECTED.items():
        body = cells[cell]
        require("java: '" + java + "'" in body, "Wrong JDK for " + cell)
        require("scala: " + scala in body, "Wrong Scala producer for " + cell)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "workflow",
        nargs="?",
        type=Path,
        default=ROOT / ".github/workflows/scala-matrix.yml",
    )
    args = parser.parse_args()
    try:
        verify(args.workflow)
    except (OSError, ValueError) as error:
        raise SystemExit(str(error))


if __name__ == "__main__":
    main()
