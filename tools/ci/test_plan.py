"""Tests for the tmux matrix plan.

Each rule is checked twice: once against the real plan, and once against a plan
with that rule removed, which must fail. A check that passes either way proves
nothing about the rule it is named for.
"""

from __future__ import annotations

import json
import pathlib
import tempfile
import unittest
from collections.abc import Callable
from typing import ClassVar
from unittest import mock

from tools.ci import plan

PLUGIN = """\
val lanes = listOf("1.0", "1.1", "1.1a", "2.0")
val previewLanes = listOf("2.1", "main")
val rejectionLanes = listOf("0.9", "0.9a")
val clientLane = "1.0"
"""
FIXTURE = plan.parse_plugin(PLUGIN)
SHA = "a" * 40


def make(**kwargs: str) -> plan.Plan:
    """Plan an event against the fixture."""
    kwargs.setdefault("event", "push")
    return plan.make_plan(FIXTURE, **kwargs)


def tmux_of(result: plan.Plan) -> list[str]:
    """Return the tmux names of the lanes."""
    return [leg["tmux"] for leg in result.lanes]


def lane(result: plan.Plan, tmux: str) -> dict[str, object]:
    """Return the first lane for `tmux`."""
    return next(item for item in result.lanes if item["tmux"] == tmux)


def check_push_runs_edges() -> None:
    """Check that a push runs the oldest and newest supported release only."""
    result = make(event="push", ref_type="branch")
    assert tmux_of(result) == ["1.0", "2.0"]
    assert result.jobs == 2


def check_pull_request_runs_edges() -> None:
    """Check that a pull request runs the same edges as a push."""
    assert tmux_of(make(event="pull_request")) == ["1.0", "2.0"]


def check_tag_runs_everything() -> None:
    """Check that a tag push proves the table, the refusals and the previews."""
    result = make(event="push", ref_type="tag")
    assert tmux_of(result) == [
        "1.0",
        "1.1",
        "1.1a",
        "2.0",
        "0.9",
        "0.9a",
        "2.1",
        "main",
    ]


def check_schedule_runs_everything() -> None:
    """Check that the weekly schedule keeps full scope."""
    result = make(event="schedule")
    assert result.scope == "full"
    assert result.jobs == 8


def check_dispatch_edges() -> None:
    """Check that a dispatch that asks for edges runs only the edges."""
    assert tmux_of(make(event="workflow_dispatch", scope="edges")) == ["1.0", "2.0"]


def check_dispatch_full() -> None:
    """Check that scope full runs the table, the refusals and the previews."""
    result = make(event="workflow_dispatch", scope="full")
    assert tmux_of(result) == [
        "1.0",
        "1.1",
        "1.1a",
        "2.0",
        "0.9",
        "0.9a",
        "2.1",
        "main",
    ]


def check_a_release_calls_the_matrix_with_full_scope() -> None:
    """Check that the scope a caller passes wins over the event that started it."""
    for event, ref_type in (
        ("push", "tag"),
        ("push", "branch"),
        ("workflow_dispatch", ""),
    ):
        result = make(event=event, ref_type=ref_type, scope="full")
        assert result.scope == "full"
        assert tmux_of(result) == [
            "1.0",
            "1.1",
            "1.1a",
            "2.0",
            "0.9",
            "0.9a",
            "2.1",
            "main",
        ]


def check_previews_do_not_block_a_full_run() -> None:
    """Check that only the previews of a full run may fail."""
    result = make(event="workflow_dispatch", scope="full")
    assert [leg["tmux"] for leg in result.lanes if leg["experimental"]] == [
        "2.1",
        "main",
    ]


def check_a_custom_preview_blocks() -> None:
    """Check that a preview someone asked for fails the run when it fails."""
    result = make(event="workflow_dispatch", scope="custom", tmux_ref="2.1,main")
    assert not any(leg["experimental"] for leg in result.lanes)


def check_refusals_come_with_a_client() -> None:
    """Check that a rejection lane puts the client lane in front of the daemon."""
    result = make(event="workflow_dispatch", scope="full")
    assert [
        (leg["tmux"], leg["client"]) for leg in result.lanes if leg["rejection"]
    ] == [
        ("0.9", "1.0"),
        ("0.9a", "1.0"),
    ]
    assert not any(leg["client"] for leg in result.lanes if not leg["rejection"])


def check_dispatch_custom() -> None:
    """Check that custom runs the refs it names, in order, once each."""
    result = make(
        event="workflow_dispatch",
        scope="custom",
        tmux_ref=f" 1.1a, main,{SHA},1.1a,feature/x ",
    )
    assert tmux_of(result) == ["1.1a", "main", SHA, "feature/x"]
    assert lane(result, SHA)["slug"] == "a" * 12
    assert lane(result, "feature/x")["slug"] == "feature-x"


def check_listed_names_keep_their_task() -> None:
    """Check that a name the build lists runs its own Gradle task."""
    result = make(event="workflow_dispatch", scope="custom", tmux_ref="1.1,0.9,main")
    assert [(leg["task"], leg["adhoc"]) for leg in result.lanes] == [
        ("test-tmux-1.1", ""),
        ("test-tmux-0.9", ""),
        ("test-tmux-main", ""),
    ]
    assert lane(result, "0.9")["rejection"]


def check_unlisted_names_run_the_adhoc_task() -> None:
    """Check that a release, tag, branch or commit outside the lists gets one lane."""
    result = make(
        event="workflow_dispatch", scope="custom", tmux_ref=f"1.1b,{SHA},feature/x"
    )
    assert [(leg["task"], leg["adhoc"]) for leg in result.lanes] == [
        ("test-tmux-adhoc", "1.1b"),
        ("test-tmux-adhoc", "a" * 12),
        ("test-tmux-adhoc", "feature-x"),
    ]


def check_releases_use_their_tarball() -> None:
    """Check that anything that names a release is built from its tarball."""
    result = make(
        event="workflow_dispatch", scope="custom", tmux_ref=f"1.1b,2.1,main,{SHA}"
    )
    assert [(leg["tmux"], leg["source"]) for leg in result.lanes] == [
        ("1.1b", "release"),
        ("2.1", "release"),
        ("main", "git"),
        (SHA, "git"),
    ]


def check_each_lane_is_verified_against_its_name() -> None:
    """Check that a release must report itself and a development build must say so."""
    result = make(
        event="workflow_dispatch", scope="custom", tmux_ref=f"1.1b,main,{SHA}"
    )
    assert [leg["verify"] for leg in result.lanes] == ["release", "development", "none"]


def check_edges_are_first_and_last_supported_release() -> None:
    """Check that the edges are the ends of `lanes`, never a preview or refusal."""
    result = make(event="push")
    assert result.lanes[0]["tmux"] == FIXTURE.lanes[0]
    assert result.lanes[-1]["tmux"] == FIXTURE.lanes[-1]
    assert not any(leg["tmux"] in ("2.1", "main", "0.9") for leg in result.lanes)


def check_jdk_repeats_each_lane() -> None:
    """Check that each JDK gets every lane, and the default is 25."""
    assert {leg["jdk"] for leg in make(event="push").lanes} == {plan.DEFAULT_JDK}
    result = make(event="workflow_dispatch", scope="edges", jdk="25,27")
    assert [(leg["tmux"], leg["jdk"]) for leg in result.lanes] == [
        ("1.0", "25"),
        ("1.0", "27"),
        ("2.0", "25"),
        ("2.0", "27"),
    ]


def check_gradle_runs_on_the_default_jdk() -> None:
    """Check that a lane on another JDK also installs the one Gradle runs on."""
    result = make(event="workflow_dispatch", scope="custom", jdk="27,25")
    assert [leg["jdks"] for leg in result.lanes][:2] == ["27\n25", "25"]


def check_tmate_takes_the_one_lane() -> None:
    """Check that a tmate session lands on the single lane and lengthens its timeout."""
    result = make(
        event="workflow_dispatch", scope="custom", tmux_ref="1.1", tmate="on-failure"
    )
    assert [(leg["tmate"], leg["timeout"]) for leg in result.lanes] == [
        ("on-failure", 60)
    ]


def check_tmate_refuses_many_lanes() -> None:
    """Check that edges are two lanes, so tmate on them is an error."""
    try:
        make(event="workflow_dispatch", scope="edges", tmate="before-tests")
    except plan.PlanError as error:
        assert "exactly one" in str(error)
    else:
        message = "tmate on two lanes was accepted"
        raise AssertionError(message)


def check_tmate_needs_a_dispatch() -> None:
    """Check that only a workflow_dispatch may open a session."""
    try:
        make(event="push", scope="custom", tmux_ref="1.1", tmate="on-failure")
    except plan.PlanError as error:
        assert "workflow_dispatch" in str(error)
    else:
        message = "tmate on a push was accepted"
        raise AssertionError(message)


def check_bad_ref_is_refused() -> None:
    """Check that a ref outside the allowed characters never reaches a step."""
    for bad in ("1.0;rm", "$(id)", "a b", "x" * 65, "-oops", "a,b|c"):
        try:
            make(event="workflow_dispatch", scope="custom", tmux_ref=bad)
        except plan.PlanError:
            continue
        message = f"{bad!r} was accepted"
        raise AssertionError(message)


def check_too_many_lanes_is_refused() -> None:
    """Check that a dispatch cannot fan out past the limit."""
    refs = ",".join(f"9.{n}" for n in range(60))
    try:
        make(event="workflow_dispatch", scope="custom", tmux_ref=refs)
    except plan.PlanError:
        return
    message = "an oversized matrix was accepted"
    raise AssertionError(message)


def check_matrix_is_never_empty() -> None:
    """Check that every scope plans at least one lane."""
    for event, scope, ref_type in (
        ("push", "", "branch"),
        ("push", "", "tag"),
        ("pull_request", "", ""),
        ("schedule", "", ""),
        ("workflow_dispatch", "edges", ""),
        ("workflow_dispatch", "full", ""),
    ):
        assert make(event=event, scope=scope, ref_type=ref_type).lanes


def check_aggregate_accepts_success() -> None:
    """Check that an all-success run passes."""
    ok = {"plan": {"result": "success"}, "lanes": {"result": "success"}}
    assert plan.verify_needs(ok) == []


def check_aggregate_refuses_anything_else() -> None:
    """Check that a failed, cancelled or skipped job fails the aggregate."""
    for result in ("failure", "cancelled", "skipped"):
        assert plan.verify_needs({"lanes": {"result": result}})


def always(value: object) -> Callable[..., object]:
    """Return a stand-in rule that ignores its arguments."""
    return lambda *_args, **_kwargs: value


def edges_whatever_the_scope(
    manifest: plan.Manifest, scope: str, refs: list[str]
) -> list[plan.Leg]:
    """Stand in for a plan that ignores the scope and always runs the edges."""
    return [plan.Leg(manifest.oldest, "lane"), plan.Leg(manifest.newest, "lane")]


REAL_CHOSEN = plan.chosen_legs


def second_release_for_newest(
    manifest: plan.Manifest, scope: str, refs: list[str]
) -> list[plan.Leg]:
    """Stand in for an edge rule that picks the wrong newest release."""
    if scope == "edges":
        return [plan.Leg(manifest.oldest, "lane"), plan.Leg(manifest.lanes[1], "lane")]
    return REAL_CHOSEN(manifest, scope, refs)


def everything_is_adhoc(manifest: plan.Manifest, name: str) -> plan.Leg:
    """Stand in for a plan that gives every name the ad hoc task."""
    return plan.Leg(name, "adhoc")


def everything_is_listed(manifest: plan.Manifest, name: str) -> plan.Leg:
    """Stand in for a plan that treats every name as a supported lane."""
    return plan.Leg(name, "lane")


REAL_LEG = plan._leg


def all_experimental(
    leg: plan.Leg, jdk: str, scope: str, manifest: plan.Manifest
) -> dict[str, object]:
    """Stand in for a plan that lets every lane fail without failing the job."""
    item = REAL_LEG(leg, jdk, scope, manifest)
    item["experimental"] = True
    return item


def none_experimental(
    leg: plan.Leg, jdk: str, scope: str, manifest: plan.Manifest
) -> dict[str, object]:
    """Stand in for a plan that makes a preview blocking in a full run."""
    item = REAL_LEG(leg, jdk, scope, manifest)
    item["experimental"] = False
    return item


def previews_experimental_everywhere(
    leg: plan.Leg, jdk: str, scope: str, manifest: plan.Manifest
) -> dict[str, object]:
    """Stand in for a plan that lets a requested preview fail silently."""
    return REAL_LEG(leg, jdk, "full", manifest)


def no_client(
    leg: plan.Leg, jdk: str, scope: str, manifest: plan.Manifest
) -> dict[str, object]:
    """Stand in for a plan that forgets the client of a rejection lane."""
    item = REAL_LEG(leg, jdk, scope, manifest)
    item["client"] = ""
    return item


def tarball_never(
    leg: plan.Leg, jdk: str, scope: str, manifest: plan.Manifest
) -> dict[str, object]:
    """Stand in for a plan that builds every release from a clone."""
    item = REAL_LEG(leg, jdk, scope, manifest)
    item["source"] = "git"
    return item


def verify_nothing(
    leg: plan.Leg, jdk: str, scope: str, manifest: plan.Manifest
) -> dict[str, object]:
    """Stand in for a plan that never checks which tmux a lane ran."""
    item = REAL_LEG(leg, jdk, scope, manifest)
    item["verify"] = "none"
    return item


def only_the_lane_jdk(
    leg: plan.Leg, jdk: str, scope: str, manifest: plan.Manifest
) -> dict[str, object]:
    """Stand in for a plan that installs only the lane's JDK."""
    item = REAL_LEG(leg, jdk, scope, manifest)
    item["jdks"] = jdk
    return item


def one_jdk(text: str) -> list[str]:
    """Stand in for a plan that ignores the jdk input."""
    return [plan.DEFAULT_JDK]


# Each entry pairs a check with a rule removed from the plan. The check must
# pass on the real plan and fail on the broken one.
MUTANTS: dict[Callable[[], None], tuple[str, object]] = {
    check_push_runs_edges: ("scope_for", always("full")),
    check_pull_request_runs_edges: ("scope_for", always("full")),
    check_tag_runs_everything: ("scope_for", always("edges")),
    check_schedule_runs_everything: ("scope_for", always("edges")),
    check_dispatch_edges: ("scope_for", always("full")),
    check_dispatch_full: ("chosen_legs", edges_whatever_the_scope),
    check_a_release_calls_the_matrix_with_full_scope: ("scope_for", always("edges")),
    check_previews_do_not_block_a_full_run: ("_leg", none_experimental),
    check_a_custom_preview_blocks: ("_leg", previews_experimental_everywhere),
    check_refusals_come_with_a_client: ("_leg", no_client),
    check_dispatch_custom: ("chosen_legs", edges_whatever_the_scope),
    check_listed_names_keep_their_task: ("classify", everything_is_adhoc),
    check_unlisted_names_run_the_adhoc_task: ("classify", everything_is_listed),
    check_releases_use_their_tarball: ("_leg", tarball_never),
    check_each_lane_is_verified_against_its_name: ("_leg", verify_nothing),
    check_edges_are_first_and_last_supported_release: (
        "chosen_legs",
        second_release_for_newest,
    ),
    check_jdk_repeats_each_lane: ("_jdks", one_jdk),
    check_gradle_runs_on_the_default_jdk: ("_leg", only_the_lane_jdk),
    check_tmate_takes_the_one_lane: ("assign_tmate", always(None)),
    check_tmate_refuses_many_lanes: ("assign_tmate", always(None)),
    check_tmate_needs_a_dispatch: ("assign_tmate", always(None)),
    check_bad_ref_is_refused: ("_refs", lambda text: plan._split(text)),
    check_too_many_lanes_is_refused: ("MAX_LEGS", 10_000),
    check_matrix_is_never_empty: ("chosen_legs", always([])),
    check_aggregate_accepts_success: ("expected_result", always("failure")),
    check_aggregate_refuses_anything_else: ("expected_result", always("skipped")),
}


class PlanRulesTest(unittest.TestCase):
    """Run every check against the real plan."""

    def test_checks_pass_on_the_real_plan(self) -> None:
        """Every rule holds."""
        for check in MUTANTS:
            with self.subTest(check=check.__name__):
                check()

    def test_every_check_has_a_mutant(self) -> None:
        """No rule is left without a negative test."""
        checks = {
            value
            for name, value in globals().items()
            if name.startswith("check_") and callable(value)
        }
        self.assertEqual(checks, set(MUTANTS))


class NegativeTest(unittest.TestCase):
    """Remove each rule and watch its check fail."""

    def test_removing_a_rule_fails_its_check(self) -> None:
        """A check that survives its rule's removal is not testing it."""
        for check, (name, broken) in MUTANTS.items():
            with self.subTest(check=check.__name__, removed=name):
                with (
                    mock.patch.object(plan, name, broken),
                    self.assertRaises((AssertionError, plan.PlanError)),
                ):
                    check()
                check()

    def test_the_edge_rule_fails_without_the_first_release(self) -> None:
        """Taking the second release for the floor breaks the edge check."""
        with (
            mock.patch.object(
                plan.Manifest, "oldest", property(lambda self: self.lanes[1])
            ),
            self.assertRaises(AssertionError),
        ):
            check_push_runs_edges()
        check_push_runs_edges()


class InputTest(unittest.TestCase):
    """Cases that are errors rather than rules."""

    def test_an_unknown_scope_is_refused(self) -> None:
        """Only edges, full and custom exist."""
        with self.assertRaises(plan.PlanError):
            make(event="workflow_dispatch", scope="everything")

    def test_tmux_ref_without_custom_is_refused(self) -> None:
        """A forgotten scope must not silently run the edges."""
        with self.assertRaises(plan.PlanError):
            make(event="workflow_dispatch", scope="edges", tmux_ref="1.1")

    def test_custom_needs_something_to_customize(self) -> None:
        """An empty custom dispatch is a mistake."""
        with self.assertRaises(plan.PlanError):
            make(event="workflow_dispatch", scope="custom")

    def test_a_bad_jdk_is_refused(self) -> None:
        """A JDK is a two-digit feature release."""
        for bad in ("latest", "25;id", "8", "250"):
            with self.subTest(bad=bad), self.assertRaises(plan.PlanError):
                make(event="workflow_dispatch", scope="custom", jdk=bad)

    def test_jdk_alone_uses_the_edges(self) -> None:
        """A JDK without refs runs the edges under it."""
        result = make(event="workflow_dispatch", scope="custom", jdk="27")
        self.assertEqual(
            [(leg["tmux"], leg["jdk"]) for leg in result.lanes],
            [("1.0", "27"), ("2.0", "27")],
        )

    def test_timeouts_follow_the_build(self) -> None:
        """A clone and autogen take longer than a tarball."""
        result = make(
            event="workflow_dispatch", scope="custom", tmux_ref=f"1.1,main,{SHA}"
        )
        self.assertEqual([leg["timeout"] for leg in result.lanes], [15, 25, 25])

    def test_outputs_are_single_line_json(self) -> None:
        """GITHUB_OUTPUT takes one line per value."""
        outputs = make(event="push", ref_type="tag").outputs()
        for value in outputs.values():
            self.assertNotIn("\n", value)
        self.assertEqual(json.loads(outputs["lanes"])["include"][0]["tmux"], "1.0")
        self.assertEqual(outputs["jobs"], "8")


class PluginTest(unittest.TestCase):
    """The plan reads the build's lists, and refuses what it cannot read."""

    base: ClassVar[dict[str, str]] = {
        "lanes": 'val lanes = listOf("1.0", "2.0")',
        "preview": 'val previewLanes = listOf("main")',
        "rejection": 'val rejectionLanes = listOf("0.9")',
        "client": 'val clientLane = "1.0"',
    }

    def parse(self, **changes: str) -> plan.Manifest:
        """Parse the base declarations with some replaced."""
        return plan.parse_plugin("\n".join({**self.base, **changes}.values()))

    def test_the_shipped_plugin_loads(self) -> None:
        """The real build declares lists the plan can read."""
        manifest = plan.load_manifest()
        self.assertEqual(manifest.oldest, manifest.lanes[0])
        self.assertIn(manifest.client, manifest.lanes)
        full = plan.make_plan(manifest, event="schedule")
        self.assertEqual(
            tmux_of(full),
            [*manifest.lanes, *manifest.rejection, *manifest.preview],
        )

    def test_a_declaration_that_spans_lines_is_refused(self) -> None:
        """The plan reads one-line declarations, as tmux-matrix.sh does."""
        with self.assertRaises(plan.PlanError):
            self.parse(lanes='val lanes = listOf(\n    "1.0", "2.0"\n)')

    def test_a_missing_declaration_is_refused(self) -> None:
        """Each of the four is required."""
        for key in self.base:
            with self.subTest(key=key), self.assertRaises(plan.PlanError):
                plan.parse_plugin(
                    "\n".join(v for k, v in self.base.items() if k != key)
                )

    def test_lanes_must_be_oldest_first(self) -> None:
        """An out-of-order list would make the last entry the wrong edge."""
        with self.assertRaises(plan.PlanError):
            self.parse(lanes='val lanes = listOf("2.0", "1.0")')

    def test_suffix_letters_order_after_the_release(self) -> None:
        """3.7 < 3.7a < 3.7b, as the library orders them."""
        self.parse(lanes='val lanes = listOf("1.0", "1.0a", "1.0b", "1.1")')
        with self.assertRaises(plan.PlanError):
            self.parse(lanes='val lanes = listOf("1.0a", "1.0")')

    def test_a_rejection_lane_must_sit_below_the_floor(self) -> None:
        """A refused release at or above the floor contradicts the table."""
        with self.assertRaises(plan.PlanError):
            self.parse(rejection='val rejectionLanes = listOf("1.0")')

    def test_the_client_must_be_a_lane(self) -> None:
        """The client reaches the old daemons, so it must be a release the build runs."""
        with self.assertRaises(plan.PlanError):
            self.parse(client='val clientLane = "1.5"')

    def test_a_preview_cannot_repeat_a_lane(self) -> None:
        """A name is a lane or a preview, not both."""
        with self.assertRaises(plan.PlanError):
            self.parse(preview='val previewLanes = listOf("2.0")')


class CommandLineTest(unittest.TestCase):
    """The step the workflow runs."""

    def test_matrix_writes_github_output(self) -> None:
        """Each output lands as `name=value`."""
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "out"
            status = plan.main(
                ["matrix", "--event", "push", "--github-output", str(output)]
            )
            lines = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual(status, 0)
        self.assertEqual(lines["scope"], "edges")
        self.assertEqual(len(json.loads(lines["lanes"])["include"]), 2)

    def test_a_refused_plan_exits_nonzero(self) -> None:
        """Exit nonzero when tmate has two lanes to attach to."""
        status = plan.main(
            [
                "matrix",
                "--event",
                "workflow_dispatch",
                "--scope",
                "edges",
                "--tmate",
                "on-failure",
            ]
        )
        self.assertEqual(status, 1)

    def test_verify_reports_a_failed_job(self) -> None:
        """The aggregate step exits nonzero on any non-success."""
        good = json.dumps({"lanes": {"result": "success"}})
        bad = json.dumps({"lanes": {"result": "failure"}})
        self.assertEqual(plan.main(["verify", "--needs", good]), 0)
        self.assertEqual(plan.main(["verify", "--needs", bad]), 1)


if __name__ == "__main__":
    unittest.main()
