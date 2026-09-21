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
CONSUMER_CELLS = {
    "C02": ("21", "2.13.18"),
    "C03": ("21", "3.3.8"),
    "C04": ("21", "3.9.0"),
    "C05": ("25", "2.13.18"),
    "C06": ("25", "3.3.8"),
    "C07": ("25", "3.9.0"),
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


def cell_blocks(text, prefix):
    return {
        matched.group("id"): matched.group("body")
        for matched in re.finditer(
            r"^\s+- id: (?P<id>" + prefix + r"\d+)\n"
            r"(?P<body>(?:^\s{12}.+\n)+)",
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
    consumer_trigger = re.search(
        r"^      include_macos_consumers:\n(?P<body>(?:^        .+\n)+)",
        text,
        re.MULTILINE,
    )
    require(consumer_trigger, "Missing manual macOS consumer input")
    require(
        "default: false" in consumer_trigger.group("body") and
        "type: boolean" in consumer_trigger.group("body"),
        "Manual macOS consumer input must default to false",
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
        "python3 libtmux-scala/scripts/verify-macos-release-gate.py" in text,
        "Pull-request smoke job does not enforce the manual gate contract",
    )
    smoke_condition = (
        "if: github.event_name != 'workflow_dispatch' || "
        "(!inputs.include_macos_release && !inputs.include_macos_consumers)"
    )
    for job in ("producer-runtime", "installed-consumers"):
        require(
            re.search(
                r"^  " + job + r":\n    " + re.escape(smoke_condition) + r"\n",
                text,
                re.MULTILINE,
            ),
            "Manual release dispatch must skip " + job,
        )
    stage_condition = (
        "if: github.event_name != 'workflow_dispatch' || "
        "!inputs.include_macos_release || inputs.include_macos_consumers"
    )
    require(
        re.search(
            r"^  artifact-stage:\n    " + re.escape(stage_condition) + r"\n",
            text,
            re.MULTILINE,
        ),
        "Manual macOS consumer dispatch must retain the Linux artifact stage",
    )
    for task in TASKS:
        require(task in text, "Missing primary task: " + task)
    cells = cell_blocks(text, "P")
    require(set(cells) == set(EXPECTED), "Wrong release cells: " + repr(sorted(cells)))
    for cell, (java, scala) in EXPECTED.items():
        body = cells[cell]
        require("java: '" + java + "'" in body, "Wrong JDK for " + cell)
        require("scala: " + scala in body, "Wrong Scala producer for " + cell)
    consumers = re.search(
        r"^  manual-macos-consumers:\n(?P<body>(?:^    .+\n)+)",
        text,
        re.MULTILINE,
    )
    require(consumers, "Missing manual macOS consumer job")
    require(
        "github.event_name == 'workflow_dispatch' && inputs.include_macos_consumers" in
        consumers.group("body"),
        "macOS consumer job must be dispatch-only",
    )
    require("needs: artifact-stage" in consumers.group("body"),
            "macOS consumers must use the Linux artifact stage")
    consumer_start = text.index("  manual-macos-consumers:")
    following_job = re.search(
        r"^  [a-z][a-z-]+:\n", text[consumer_start + 1:], re.MULTILINE
    )
    require(following_job, "macOS consumer job has no closing boundary")
    consumer_end = consumer_start + 1 + following_job.start()
    consumer_text = text[consumer_start:consumer_end]
    require("max-parallel: 1" in consumer_text,
            "macOS consumer cells must run one at a time")
    require("scala-consumer-stage-${{ github.sha }}" in consumer_text,
            "macOS consumers must download staged artifacts")
    for value in ("--scala-stage", "--java-stage", "--scala-version",
                  "--jdk \"$JAVA_HOME\"", "--tmux", "--output"):
        require(value in consumer_text, "Missing consumer invocation argument: " + value)
    require("No tmux server outlived" in consumer_text,
            "Missing macOS consumer cleanup check")
    consumer_cells = cell_blocks(text, "C")
    require(set(consumer_cells) == set(CONSUMER_CELLS),
            "Wrong consumer release cells: " + repr(sorted(consumer_cells)))
    for cell, (java, scala) in CONSUMER_CELLS.items():
        body = consumer_cells[cell]
        require("java: '" + java + "'" in body, "Wrong consumer JDK for " + cell)
        require("scala: " + scala in body, "Wrong consumer Scala for " + cell)


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
