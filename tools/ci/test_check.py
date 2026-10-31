"""Tests for the CI drift guards.

The first test runs the guards on this repository. Every other test breaks one
thing in a copy of it and expects the guard for that thing to say so, because a
guard that cannot fail is not guarding anything.
"""

from __future__ import annotations

import pathlib
import shutil
import tempfile
import unittest

from tools.ci import check

ROOT = pathlib.Path(__file__).resolve().parents[2]
COPIED = (
    "README.md",
    "RELEASING.md",
    check.CONTRIBUTING,
    check.PLUGIN,
    check.MATRIX,
    check.RELEASE,
    ".github/workflows/ci.yml",
    ".github/workflows/docs.yml",
)
SHA = "35b54afac29c97fb54faba5b513f8fbd1882f113"


class CheckTest(unittest.TestCase):
    """Break a copy of the repository and watch the guard notice."""

    def setUp(self) -> None:
        """Copy the files the guards read."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        for name in COPIED:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / name, target)

    def edit(self, name: str, old: str, new: str, count: int = 1) -> None:
        """Replace `old` with `new` in a copied file, which must contain it."""
        path = self.root / name
        text = path.read_text()
        self.assertEqual(text.count(old), count, f"{old!r} in {name}")
        path.write_text(text.replace(old, new))

    def append(self, name: str, text: str) -> None:
        """Add `text` to the end of a copied file."""
        path = self.root / name
        path.write_text(path.read_text() + text)

    def problems(self) -> list[str]:
        """Return what the guards say about the copy."""
        return check.check(self.root)

    def assertFlags(self, fragment: str) -> None:
        """Assert some problem mentions `fragment`."""
        problems = self.problems()
        self.assertTrue(
            any(fragment in problem for problem in problems),
            f"no problem mentions {fragment!r}: {problems}",
        )

    def test_the_repository_agrees_with_its_plugin(self) -> None:
        """The pages and workflows in this checkout pass every guard."""
        self.assertEqual(check.check(ROOT), [])
        self.assertEqual(self.problems(), [])

    def test_a_new_lane_in_the_plugin_reaches_every_page(self) -> None:
        """Adding a release to `lanes` without editing the docs names each page."""
        self.edit(check.PLUGIN, '"3.7c")', '"3.7c", "3.9")')
        problems = self.problems()
        for page in ("README.md", check.CONTRIBUTING):
            self.assertTrue(any(page in p and "3.9" in p for p in problems), page)

    def test_a_page_with_another_range_is_flagged(self) -> None:
        """A README that still says 3.7b is the newest is caught."""
        self.edit("README.md", "tmux 3.3a through 3.7c", "tmux 3.3a through 3.7b")
        self.assertFlags("README.md says tmux 3.3a through 3.7b")

    def test_a_page_without_the_range_is_flagged(self) -> None:
        """A page must state the range at all."""
        self.edit(
            check.CONTRIBUTING,
            "**tmux 3.3a through 3.7c is the supported range**",
            "**tmux 3.3a and newer is the supported range**",
        )
        self.assertFlags(".github/CONTRIBUTING.md does not say tmux 3.3a through 3.7c")

    def test_a_page_without_the_edges_is_flagged(self) -> None:
        """The page must say what a push tests."""
        self.edit("README.md", "3.3a and 3.7c on every push", "3.3a on every push")
        self.assertFlags("README.md does not say a push tests the edges")

    def test_contributing_must_show_the_dispatch_command(self) -> None:
        """The section needs the exact command."""
        self.edit(
            check.CONTRIBUTING,
            "gh workflow run tmux-matrix.yml",
            "gh workflow run x.yml",
            3,
        )
        self.assertFlags("gh workflow run tmux-matrix.yml")

    def test_releasing_must_say_the_release_runs_the_table(self) -> None:
        """RELEASING.md names what the release does, so nobody dispatches it by hand."""
        self.edit(
            "RELEASING.md",
            "runs the whole tmux\n   matrix itself",
            "runs the tmux matrix",
        )
        self.assertFlags(
            "RELEASING.md does not say the release runs the whole tmux matrix"
        )

    def test_a_list_in_the_workflow_is_flagged(self) -> None:
        """A hard-coded second list would drift from the plugin."""
        self.edit(
            check.MATRIX,
            "  lanes:\n",
            "  lanes:\n    env:\n      OLD: '3.4'\n",
        )
        self.assertFlags("lists tmux releases itself")

    def test_a_matrix_key_in_the_workflow_is_flagged(self) -> None:
        """A `tmux:` axis belongs to the plan."""
        self.edit(
            check.MATRIX,
            "      matrix: ${{ fromJSON(needs.plan.outputs.lanes) }}\n",
            "      matrix:\n        tmux: [x]\n",
        )
        self.assertFlags("lists tmux releases itself")
        self.assertFlags("does not take its matrix from the plan")

    def test_an_input_spliced_into_a_script_is_flagged(self) -> None:
        """Inputs and the values derived from them reach scripts through env only."""
        for spliced in (
            "${{ inputs.tmux-ref }}",
            "${{ matrix.tmux }}",
            "${{ matrix.slug }}",
        ):
            with self.subTest(spliced=spliced):
                self.edit(
                    check.MATRIX,
                    '          ./gradlew "$TASK"',
                    f'          echo {spliced}\n          ./gradlew "$TASK"',
                )
                self.assertFlags("splices an input into a script")
                self.edit(
                    check.MATRIX,
                    f'          echo {spliced}\n          ./gradlew "$TASK"',
                    '          ./gradlew "$TASK"',
                )

    def test_a_matrix_that_cannot_be_called_is_flagged(self) -> None:
        """A release runs the matrix through workflow_call."""
        self.edit(check.MATRIX, "  workflow_call:\n", "  workflow_dispatch_again:\n")
        self.assertFlags("cannot be called")

    def test_a_called_matrix_that_takes_a_tmate_session_is_flagged(self) -> None:
        """A release cannot open a session, or narrow the table, through its call."""
        self.edit(
            check.MATRIX,
            "        default: edges\n  workflow_dispatch:",
            "        default: edges\n      tmate:\n        type: string\n  workflow_dispatch:",
        )
        self.assertFlags("takes more than a scope when called")

    def test_a_bare_off_in_the_tmate_input_is_flagged(self) -> None:
        """A bare off is false to GitHub, which breaks every default dispatch."""
        self.edit(check.MATRIX, "options: ['off',", "options: [off,")
        self.assertFlags("must quote 'off'")

    def test_a_bare_off_default_is_flagged(self) -> None:
        """The default has to be the same string as the option it names."""
        self.edit(check.MATRIX, "default: 'off'", "default: off")
        self.assertFlags("must quote 'off'")

    def test_an_unpinned_tmate_is_flagged(self) -> None:
        """The action is pinned by commit."""
        self.edit(
            check.MATRIX, f"action-tmate@{SHA} # v3.24", "action-tmate@v3 # v3", 2
        )
        self.assertFlags("not a full commit")

    def test_a_tmate_open_to_everyone_is_flagged(self) -> None:
        """Access is limited to the dispatching account."""
        self.edit(
            check.MATRIX,
            "limit-access-to-actor: true",
            "limit-access-to-actor: false",
            2,
        )
        self.assertFlags("does not limit access")

    def test_a_tmate_without_a_timeout_is_flagged(self) -> None:
        """A session cannot hold a runner forever."""
        self.edit(
            check.MATRIX,
            "        timeout-minutes: 30\n        with:\n          limit",
            "        with:\n          limit",
            2,
        )
        self.assertFlags("no timeout-minutes")

    def test_a_tmate_outside_a_dispatch_is_flagged(self) -> None:
        """A push can never open a session."""
        self.edit(
            check.MATRIX,
            "if: github.event_name == 'workflow_dispatch' && matrix.tmate == 'before",
            "if: matrix.tmate == 'before",
        )
        self.assertFlags("outside a workflow_dispatch")

    def test_a_third_tmate_step_is_flagged(self) -> None:
        """A session outside the lanes job is not allowed."""
        self.append(
            check.MATRIX,
            "\n  extra:\n    runs-on: ubuntu-latest\n    steps:\n"
            f"      - uses: mxschmitt/action-tmate@{SHA}\n",
        )
        self.assertFlags("outside the lanes job")

    def test_tmate_in_another_workflow_is_flagged(self) -> None:
        """Release, docs and CI jobs never open a session."""
        for name in ("release.yml", "docs.yml", "ci.yml"):
            with self.subTest(name=name):
                path = f".github/workflows/{name}"
                original = (self.root / path).read_text()
                self.append(path, f"      - uses: mxschmitt/action-tmate@{SHA}\n")
                self.assertFlags(f"{name} runs action-tmate")
                (self.root / path).write_text(original)

    def test_a_job_missing_from_the_aggregate_is_flagged(self) -> None:
        """A new job that nobody gated would pass unnoticed."""
        self.edit(check.MATRIX, "      - plan\n      - lanes\n", "      - lanes\n")
        self.assertFlags("required does not need plan")

    def test_a_stale_name_in_the_aggregate_is_flagged(self) -> None:
        """A renamed job left in `needs` would fail every run."""
        self.edit(
            check.MATRIX,
            "      - plan\n      - lanes\n",
            "      - plan\n      - lane\n      - lanes\n",
        )
        self.assertFlags("required needs lane, which is not a job")

    def test_a_shared_concurrency_group_is_flagged(self) -> None:
        """Dispatch runs must not cancel or queue behind one another."""
        self.edit(
            check.MATRIX,
            "github.event_name == 'workflow_dispatch' && github.run_id || ",
            "",
        )
        self.assertFlags("concurrency group")

    def test_a_release_that_runs_the_edges_is_flagged(self) -> None:
        """A release must prove the whole table."""
        self.edit(check.RELEASE, "scope: full", "scope: edges")
        self.assertFlags("does not run tmux-matrix.yml at scope full")

    def test_a_release_that_does_not_call_the_matrix_is_flagged(self) -> None:
        """Without the call there is no full run to wait for."""
        self.edit(
            check.RELEASE,
            "uses: ./.github/workflows/tmux-matrix.yml",
            "uses: ./.github/workflows/other.yml",
        )
        self.assertFlags("does not run tmux-matrix.yml at scope full")

    def test_a_publish_that_does_not_wait_is_flagged(self) -> None:
        """Publishing before the matrix has passed defeats the call."""
        self.edit(check.RELEASE, "needs: [tmux-matrix]", "needs: []")
        self.assertFlags("does not wait for the tmux matrix")

    def test_a_release_that_trusts_an_old_run_is_flagged(self) -> None:
        """Run history proves only what that run covered."""
        self.append(
            check.RELEASE,
            "# gh run list --workflow tmux-matrix.yml --commit x\n",
        )
        self.assertFlags("trusts an earlier tmux matrix run")


if __name__ == "__main__":
    unittest.main()
