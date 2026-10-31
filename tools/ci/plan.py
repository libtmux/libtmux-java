r"""Plan the tmux lanes of the tmux matrix from the build's own table and the event.

The Gradle plugin `libtmux.tmux-matrix.gradle.kts` is the only list of tmux
releases this project tests: `lanes` is the supported range, `previewLanes` runs
ahead of it, `rejectionLanes` are the releases below the floor, and `clientLane`
is the release that reaches their daemons. A push or pull request runs the first
and last of `lanes`; the weekly schedule and a dispatch with `scope: full` run
all of it; a dispatch with `scope: custom` runs the refs it names. Nothing here
touches the network, so the same plan can be reproduced locally:

    python3 -m tools.ci.plan matrix --event push
    python3 -m tools.ci.plan matrix --event workflow_dispatch --scope custom \
        --tmux-ref 3.6b,master --tmate on-failure
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from typing import NamedTuple

ROOT = pathlib.Path(__file__).resolve().parents[2]
PLUGIN = ROOT / "build-logic/conventions/src/main/kotlin/libtmux.tmux-matrix.gradle.kts"

REF_PATTERN = re.compile(r"[A-Za-z0-9._/-]{1,64}")
SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
VERSION_PATTERN = re.compile(r"(\d+)\.(\d+)([a-z]?)")
JDK_PATTERN = re.compile(r"\d{2}")
LIST_DECLARATION = re.compile(
    r"^val (lanes|previewLanes|rejectionLanes) = listOf\((.*)\)$", re.M
)
CLIENT_DECLARATION = re.compile(r'^val clientLane = "([^"]+)"$', re.M)

SCOPES = ("edges", "full", "custom")
TMATE_MODES = ("off", "before-tests", "on-failure")

# The JDK the tests run on when a dispatch names none: the one the library compiles against.
DEFAULT_JDK = "25"
# The full table is 15 lanes, and a second JDK doubles it. A dispatch that asks for more than this
# is a typo or a loop.
MAX_LEGS = 32


class PlanError(Exception):
    """The inputs describe a run CI will not make."""


class Manifest(NamedTuple):
    """The tmux releases the build tests, oldest supported release first."""

    lanes: tuple[str, ...]
    preview: tuple[str, ...]
    rejection: tuple[str, ...]
    client: str

    @property
    def oldest(self) -> str:
        """Return the oldest supported release, the floor."""
        return self.lanes[0]

    @property
    def newest(self) -> str:
        """Return the newest supported release."""
        return self.lanes[-1]


class Leg(NamedTuple):
    """One tmux to test: what to call it, what to resolve and how to run it."""

    name: str
    kind: str


class Plan(NamedTuple):
    """What one run builds."""

    scope: str
    lanes: tuple[dict[str, object], ...]

    @property
    def jobs(self) -> int:
        """Return how many jobs the matrix expands to."""
        return len(self.lanes)

    def outputs(self) -> dict[str, str]:
        """Return the step outputs, each on one line."""
        return {
            "scope": self.scope,
            "lanes": json.dumps({"include": list(self.lanes)}, separators=(",", ":")),
            "jobs": str(self.jobs),
        }


def version_key(version: str) -> tuple[int, int, str]:
    """Order a tmux release the way the library does: 3.7 < 3.7a < 3.7b."""
    match = VERSION_PATTERN.fullmatch(version)
    if match is None:
        message = f"{version!r} is not a tmux release such as 3.6 or 3.6b"
        raise PlanError(message)
    return int(match[1]), int(match[2]), match[3]


def parse_plugin(text: str) -> Manifest:
    """Read the lane lists out of the Gradle plugin and check them."""
    lists = {
        name: tuple(re.findall(r'"([^"]+)"', body))
        for name, body in LIST_DECLARATION.findall(text)
    }
    client = CLIENT_DECLARATION.search(text)
    for name in ("lanes", "previewLanes", "rejectionLanes"):
        if not lists.get(name):
            message = f"the plugin declares no `val {name} = listOf(...)` on one line"
            raise PlanError(message)
    if client is None:
        message = 'the plugin declares no `val clientLane = "..."` on one line'
        raise PlanError(message)
    lanes, preview, rejection = (
        lists["lanes"],
        lists["previewLanes"],
        lists["rejectionLanes"],
    )
    keys = [version_key(version) for version in lanes]
    if keys != sorted(set(keys)):
        message = "lanes must be unique and oldest first"
        raise PlanError(message)
    for version in rejection:
        if version_key(version) >= keys[0]:
            message = f"rejection lane {version} is not below the floor {lanes[0]}"
            raise PlanError(message)
    if client[1] not in lanes:
        message = f"the client {client[1]!r} must be one of the lanes"
        raise PlanError(message)
    for ref in preview:
        if REF_PATTERN.fullmatch(ref) is None or ref in (*lanes, *rejection):
            message = f"preview lane {ref!r} is malformed or already a lane"
            raise PlanError(message)
    return Manifest(lanes, preview, rejection, client[1])


def load_manifest(path: pathlib.Path = PLUGIN) -> Manifest:
    """Read and check the plugin at `path`."""
    return parse_plugin(path.read_text())


def scope_for(event: str, ref_type: str, requested: str) -> str:
    """Return the scope an event runs at.

    An explicit request wins. Otherwise a tag and the weekly `schedule` run the
    whole table, because a release must prove all of it, and everything else
    runs the edges.
    """
    if requested:
        if requested not in SCOPES:
            message = f"scope must be one of {', '.join(SCOPES)}, not {requested!r}"
            raise PlanError(message)
        return requested
    if event == "schedule" or (event == "push" and ref_type == "tag"):
        return "full"
    return "edges"


def _split(text: str) -> list[str]:
    items: list[str] = []
    for raw in text.split(","):
        item = raw.strip()
        if item and item not in items:
            items.append(item)
    return items


def _refs(text: str) -> list[str]:
    refs = _split(text)
    for ref in refs:
        if REF_PATTERN.fullmatch(ref) is None or ref.startswith("-"):
            message = f"tmux-ref {ref!r} must match [A-Za-z0-9._/-]{{1,64}}"
            raise PlanError(message)
    return refs


def _jdks(text: str) -> list[str]:
    versions = _split(text)
    for version in versions:
        if JDK_PATTERN.fullmatch(version) is None:
            message = f"jdk {version!r} must be a two-digit feature release such as 25"
            raise PlanError(message)
    return versions or [DEFAULT_JDK]


def classify(manifest: Manifest, name: str) -> Leg:
    """Say which kind of lane a name is: one the build lists, or one it does not."""
    if name in manifest.lanes:
        return Leg(name, "lane")
    if name in manifest.rejection:
        return Leg(name, "rejection")
    if name in manifest.preview:
        return Leg(name, "preview")
    return Leg(name, "adhoc")


def chosen_legs(manifest: Manifest, scope: str, refs: list[str]) -> list[Leg]:
    """Return the tmux lanes a scope runs."""
    if scope == "full":
        return [
            *(Leg(name, "lane") for name in manifest.lanes),
            *(Leg(name, "rejection") for name in manifest.rejection),
            *(Leg(name, "preview") for name in manifest.preview),
        ]
    if scope == "custom" and refs:
        return [classify(manifest, name) for name in refs]
    return [Leg(manifest.oldest, "lane"), Leg(manifest.newest, "lane")]


def _slug(name: str) -> str:
    if SHA_PATTERN.fullmatch(name):
        return name[:12]
    return re.sub(r"[^A-Za-z0-9._-]", "-", name)


def _leg(leg: Leg, jdk: str, scope: str, manifest: Manifest) -> dict[str, object]:
    release = VERSION_PATTERN.fullmatch(leg.name) is not None
    adhoc = leg.kind == "adhoc"
    slug = _slug(leg.name)
    if release:
        verify = "release"
    elif leg.kind == "preview":
        verify = "development"
    else:
        verify = "none"
    return {
        "tmux": leg.name,
        "slug": slug,
        "source": "release" if release else "git",
        "task": "test-tmux-adhoc" if adhoc else f"test-tmux-{leg.name}",
        "adhoc": slug if adhoc else "",
        "verify": verify,
        "experimental": leg.kind == "preview" and scope == "full",
        "rejection": leg.kind == "rejection",
        "client": manifest.client if leg.kind == "rejection" else "",
        "jdk": jdk,
        # setup-java installs every version on its own line; Gradle itself runs on the default.
        "jdks": jdk if jdk == DEFAULT_JDK else f"{jdk}\n{DEFAULT_JDK}",
        "timeout": 15 if release else 25,
        "tmate": "off",
    }


def assign_tmate(legs: list[dict[str, object]], tmate: str, event: str) -> None:
    """Give the one lane a tmate session, or refuse the run."""
    if tmate in ("", "off"):
        return
    if tmate not in TMATE_MODES:
        message = f"tmate must be one of {', '.join(TMATE_MODES)}, not {tmate!r}"
        raise PlanError(message)
    if event != "workflow_dispatch":
        message = "tmate only runs from a workflow_dispatch"
        raise PlanError(message)
    if len(legs) != 1:
        message = (
            f"tmate needs exactly one lane and this plan has {len(legs)}: "
            "use scope custom with one tmux-ref and at most one jdk"
        )
        raise PlanError(message)
    legs[0]["tmate"] = tmate
    # A person debugging needs longer than a suite takes.
    legs[0]["timeout"] = 60


def make_plan(
    manifest: Manifest,
    *,
    event: str,
    ref_type: str = "",
    scope: str = "",
    tmux_ref: str = "",
    jdk: str = "",
    tmate: str = "",
) -> Plan:
    """Return the plan for one event and its dispatch inputs."""
    effective = scope_for(event, ref_type, scope)
    refs = _refs(tmux_ref)
    jdks = _jdks(jdk)
    if refs and effective != "custom":
        message = "tmux-ref only applies with scope custom"
        raise PlanError(message)
    if effective == "custom" and not refs and not jdk.strip():
        message = "scope custom needs a tmux-ref or a jdk"
        raise PlanError(message)

    legs = [
        _leg(leg, version, effective, manifest)
        for leg in chosen_legs(manifest, effective, refs)
        for version in jdks
    ]
    if len(legs) > MAX_LEGS:
        message = f"{len(legs)} lanes is over the limit of {MAX_LEGS}"
        raise PlanError(message)
    assign_tmate(legs, tmate, event)
    if not legs:
        message = "the plan has no lane"
        raise PlanError(message)
    return Plan(effective, tuple(legs))


def expected_result(job: str) -> str:
    """Return the result a job must report for the aggregate to pass."""
    return "success" if job else "failure"


def verify_needs(needs: dict[str, dict[str, object]]) -> list[str]:
    """Return what is wrong with the results the aggregate job saw.

    Every job must have succeeded. The preview lanes are allowed to fail
    without failing `lanes`, so they cannot fail this either.
    """
    problems = []
    for name, info in sorted(needs.items()):
        expected = expected_result(name)
        result = info.get("result")
        if result != expected:
            problems.append(f"{name} reported {result}, expected {expected}")
    return problems


def _summary(plan: Plan) -> str:
    legs = ", ".join(f"{leg['tmux']} (jdk {leg['jdk']})" for leg in plan.lanes)
    return "\n".join([f"scope: {plan.scope}", f"lanes: {legs}", f"jobs: {plan.jobs}"])


def _run_matrix(args: argparse.Namespace) -> int:
    plan = make_plan(
        load_manifest(args.plugin),
        event=args.event,
        ref_type=args.ref_type,
        scope=args.scope,
        tmux_ref=args.tmux_ref,
        jdk=args.jdk,
        tmate=args.tmate,
    )
    print(_summary(plan))
    if args.github_output:
        with pathlib.Path(args.github_output).open("a") as output:
            output.writelines(f"{k}={v}\n" for k, v in plan.outputs().items())
    return 0


def _run_verify(args: argparse.Namespace) -> int:
    problems = verify_needs(json.loads(args.needs))
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


def main(argv: list[str] | None = None) -> int:
    """Plan the matrix, or check the results of the jobs it ran."""
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    matrix = subparsers.add_parser("matrix", help="print the plan for an event")
    matrix.add_argument("--event", required=True)
    matrix.add_argument("--ref-type", default="")
    matrix.add_argument("--scope", default="")
    matrix.add_argument("--tmux-ref", default="")
    matrix.add_argument("--jdk", default="")
    matrix.add_argument("--tmate", default="")
    matrix.add_argument("--github-output", default="")
    matrix.add_argument("--plugin", type=pathlib.Path, default=PLUGIN)
    matrix.set_defaults(handler=_run_matrix)

    verify = subparsers.add_parser("verify", help="check the aggregate job's needs")
    verify.add_argument("--needs", required=True, help="the JSON of `needs`")
    verify.set_defaults(handler=_run_verify)

    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except PlanError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
