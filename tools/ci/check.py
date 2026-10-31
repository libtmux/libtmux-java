"""Check that the docs and the workflows follow the build's tmux lanes.

The Gradle plugin's `lanes` are the one list of supported tmux releases. This
fails when a page states a range the plugin does not, when the matrix workflow
carries a list of its own, and when the workflow structure the plan relies on
has been undone: a dispatch input spliced into a script, a tmate step that is
not pinned or not limited to the actor, an aggregate job that forgot a job, a
release that no longer runs the whole table before it publishes.

Run from the repository root:

    python3 -m tools.ci.check
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

from tools.ci.plan import Manifest, PlanError, load_manifest

ROOT = pathlib.Path(__file__).resolve().parents[2]
PLUGIN = "build-logic/conventions/src/main/kotlin/libtmux.tmux-matrix.gradle.kts"
MATRIX = ".github/workflows/tmux-matrix.yml"
RELEASE = ".github/workflows/release.yml"
CONTRIBUTING = ".github/CONTRIBUTING.md"
EDGE_PAGES = ("README.md", CONTRIBUTING)

RANGE_CLAIM = re.compile(r"tmux (\d+\.\d+[a-z]?) through (\d+\.\d+[a-z]?)")
TMATE = re.compile(r"uses:\s*mxschmitt/action-tmate@(\S+)")
SPLICED = re.compile(
    r"\$\{\{[^}]*\b("
    r"inputs\.|github\.event\.inputs|needs\.plan\.outputs"
    r"|matrix\.(?:tmux|slug|source|task|adhoc|verify|client|jdk|jdks|timeout|tmate)"
    r"|steps\.[\w-]+\.outputs"
    r")"
)
RUN_KEY = re.compile(r"^(\s*)(?:- )?run:\s*(.*)$")
MATRIX_KEY = re.compile(r"(?m)^\s*-?\s*(?:tmux|client):")
QUOTED_RELEASE = re.compile(r"'\d+\.\d+[a-z]?'")


def _squash(text: str) -> str:
    return re.sub(r"\s+", " ", text)


def _code(text: str) -> str:
    """Return a workflow without its comment lines."""
    lines = [line for line in text.splitlines() if not line.lstrip().startswith("#")]
    return "\n".join(lines)


def _run_bodies(text: str) -> list[tuple[int, str]]:
    """Return each line of every `run:` script with its line number."""
    lines = text.splitlines()
    bodies: list[tuple[int, str]] = []
    index = 0
    while index < len(lines):
        match = RUN_KEY.match(lines[index])
        index += 1
        if not match:
            continue
        indent, value = len(match[1]), match[2]
        if value and not value.startswith(("|", ">")):
            bodies.append((index, value))
            continue
        while index < len(lines):
            line = lines[index]
            if line.strip() and len(line) - len(line.lstrip()) <= indent:
                break
            bodies.append((index + 1, line))
            index += 1
    return bodies


def _jobs(text: str) -> dict[str, str]:
    """Return each top-level job of a workflow, by id, with its text."""
    section = text.split("\njobs:\n", 1)[-1]
    parts = re.split(r"(?m)^  ([A-Za-z0-9_-]+):\s*$", section)
    return dict(zip(parts[1::2], parts[2::2], strict=True))


def _steps(job: str) -> list[str]:
    return re.split(r"(?m)^      - ", job)[1:]


def check_docs(root: pathlib.Path, manifest: Manifest) -> list[str]:
    """Return where a page disagrees with the plugin's lanes."""
    ends = (manifest.oldest, manifest.newest)
    problems: list[str] = []
    for page in ("README.md", CONTRIBUTING):
        stated = RANGE_CLAIM.findall(_squash((root / page).read_text()))
        if not stated:
            problems.append(f"{page} does not say tmux {ends[0]} through {ends[1]}")
        problems += [
            f"{page} says tmux {first} through {last}, not {ends[0]} through {ends[1]}"
            for first, last in stated
            if (first, last) != ends
        ]
    edges = f"{ends[0]} and {ends[1]}"
    problems += [
        f"{page} does not say a push tests the edges, {edges}"
        for page in EDGE_PAGES
        if edges not in _squash((root / page).read_text())
    ]
    contributing = (root / CONTRIBUTING).read_text()
    if "gh workflow run tmux-matrix.yml" not in contributing:
        problems.append(
            f"{CONTRIBUTING} has no `gh workflow run tmux-matrix.yml` command"
        )
    releasing = _squash((root / "RELEASING.md").read_text())
    if "runs the whole tmux matrix itself" not in releasing:
        problems.append(
            "RELEASING.md does not say the release runs the whole tmux matrix"
        )
    return problems


def _check_tmate_step(step: str) -> str:
    pin = TMATE.search(step)[1]
    if re.fullmatch(r"[0-9a-f]{40}", pin) is None:
        return f"action-tmate is pinned to {pin}, not a full commit"
    if "limit-access-to-actor: true" not in step:
        return "an action-tmate step does not limit access to the actor"
    if "timeout-minutes:" not in step:
        return "an action-tmate step has no timeout-minutes"
    if "github.event_name == 'workflow_dispatch'" not in step:
        return "an action-tmate step can run outside a workflow_dispatch"
    return ""


def check_matrix(text: str) -> list[str]:
    """Return what is wrong with tmux-matrix.yml."""
    code = _code(text)
    problems: list[str] = []
    if MATRIX_KEY.search(code) or QUOTED_RELEASE.search(code):
        problems.append(
            "tmux-matrix.yml lists tmux releases itself; the plan reads the plugin"
        )
    problems += [
        f"tmux-matrix.yml:{number} splices an input into a script; use env:"
        for number, body in _run_bodies(text)
        if SPLICED.search(body)
    ]
    call = re.search(r"(?m)^  workflow_call:\n((?:    .*\n)+)", text)
    if call is None:
        problems.append("tmux-matrix.yml cannot be called, so a release cannot run it")
    elif re.findall(r"(?m)^      ([A-Za-z][\w-]*):", call[1]) != ["scope"]:
        problems.append(
            "tmux-matrix.yml takes more than a scope when called; "
            "a release runs the table as it is"
        )

    jobs = _jobs(text)
    if "matrix: ${{ fromJSON(needs.plan.outputs.lanes) }}" not in jobs.get("lanes", ""):
        problems.append("the lanes job does not take its matrix from the plan")

    aggregate = jobs.get("required", "")
    listed = set(re.findall(r"(?m)^      - ([\w-]+)$", aggregate.split("steps:")[0]))
    wanted = set(jobs) - {"required"}
    problems += [f"required does not need {job}" for job in sorted(wanted - listed)]
    problems += [
        f"required needs {job}, which is not a job" for job in sorted(listed - wanted)
    ]

    group = re.search(r"(?m)^  group: (.*)$", text.split("concurrency:", 1)[-1])
    if (
        not group
        or "github.run_id" not in group[1]
        or "workflow_dispatch" not in group[1]
    ):
        problems.append("the concurrency group does not separate dispatch runs")

    tmate = [step for step in _steps(jobs.get("lanes", "")) if TMATE.search(step)]
    if len(tmate) != 2:
        problems.append(
            "the lanes job needs a before-tests and an on-failure tmate step"
        )
    problems += [_check_tmate_step(step) for step in tmate]
    if len(TMATE.findall(code)) != len(tmate):
        problems.append("action-tmate appears outside the lanes job")
    return [problem for problem in problems if problem]


def check_release(text: str) -> list[str]:
    """Return what is wrong with release.yml."""
    problems = []
    jobs = _jobs(text)
    job = jobs.get("tmux-matrix", "")
    if (
        "uses: ./.github/workflows/tmux-matrix.yml" not in job
        or "scope: full" not in job
    ):
        problems.append("release.yml does not run tmux-matrix.yml at scope full")
    if not re.search(r"needs:\s*\[tmux-matrix]", jobs.get("publish", "")):
        problems.append("the publish job does not wait for the tmux matrix")
    if "--workflow tmux-matrix.yml" in text:
        problems.append(
            "release.yml trusts an earlier tmux matrix run instead of running one"
        )
    return problems


def check(root: pathlib.Path) -> list[str]:
    """Return every disagreement between the plugin, the docs and the workflows."""
    manifest = load_manifest(root / PLUGIN)
    problems = check_docs(root, manifest)
    problems += check_matrix((root / MATRIX).read_text())
    problems += check_release((root / RELEASE).read_text())
    problems += [
        f"{path.name} runs action-tmate; only tmux-matrix.yml may"
        for path in sorted((root / ".github/workflows").glob("*.yml"))
        if path.name != "tmux-matrix.yml" and TMATE.search(path.read_text())
    ]
    return problems


def main(argv: list[str] | None = None) -> int:
    """Print every disagreement and exit nonzero if there is one."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, default=ROOT)
    args = parser.parse_args(argv)
    try:
        problems = check(args.root.resolve())
    except (PlanError, OSError) as error:
        problems = [f"cannot check: {error}"]
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
