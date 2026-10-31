"""Tests for resolving and building a tmux ref, against local fixtures.

A local repository stands in for github.com/tmux/tmux: it has a lightweight
tag, an annotated tag, a branch, a name shared by a tag and a branch (where
"tag first" decides what a person gets) and a commit no ref names. A local
directory stands in for the releases page.
"""

from __future__ import annotations

import pathlib
import subprocess
import tarfile
import tempfile
import unittest

from tools.ci import tmux_ref

AUTOGEN = "cp config.template configure && chmod +x configure\n"
CONFIGURE = """#!/bin/sh
for arg in "$@"; do
  case "$arg" in --prefix=*) prefix="${arg#--prefix=}" ;; esac
done
sed "s|@PREFIX@|$prefix|" Makefile.in > Makefile
"""
MAKEFILE = (
    "all:\n\t@:\n"
    "install:\n\tmkdir -p @PREFIX@/bin\n\tcp tmux.sh @PREFIX@/bin/tmux\n"
    "\tchmod +x @PREFIX@/bin/tmux\n"
)


def git(root: pathlib.Path, *args: str) -> str:
    """Run git in `root` and return its trimmed output."""
    done = subprocess.run(
        [
            "git",
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@example.invalid",
            "-C",
            str(root),
            *args,
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    return done.stdout.strip()


def installed(prefix: pathlib.Path) -> str:
    """Run the fake tmux a build installed and return what it printed."""
    return subprocess.run(
        [str(prefix / "bin" / "tmux")], capture_output=True, text=True, check=True
    ).stdout.strip()


class TmuxRefTest(unittest.TestCase):
    """Resolve and build against a repository with known refs."""

    def setUp(self) -> None:
        """Create the stand-in upstream and releases page."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.remote = self.root / "upstream"
        self.remote.mkdir()
        git(self.remote, "init", "-q", "-b", "master")
        for name, text in (
            ("autogen.sh", AUTOGEN),
            ("config.template", CONFIGURE),
            ("Makefile.in", MAKEFILE),
        ):
            (self.remote / name).write_text(text)
        self.one = self.commit("one")
        git(self.remote, "tag", "1.0")
        self.two = self.commit("two")
        git(self.remote, "tag", "-a", "2.0", "-m", "annotated")
        self.three = self.commit("three")
        git(self.remote, "branch", "topic", self.two)
        git(self.remote, "tag", "dual", self.one)
        git(self.remote, "branch", "dual", self.three)

        source = self.root / "source" / "tmux-1.0"
        source.mkdir(parents=True)
        (source / "configure").write_text(CONFIGURE)
        (source / "configure").chmod(0o755)
        (source / "Makefile.in").write_text(MAKEFILE)
        (source / "tmux.sh").write_text("#!/bin/sh\necho release\n")
        page = self.root / "page" / "1.0"
        page.mkdir(parents=True)
        with tarfile.open(page / "tmux-1.0.tar.gz", "w:gz") as tar:
            tar.add(source, arcname="tmux-1.0")
        self.releases = f"file://{self.root / 'page'}"

    def commit(self, label: str) -> str:
        """Commit a fake tmux that reports `label` and return the commit."""
        (self.remote / "tmux.sh").write_text(f"#!/bin/sh\necho {label}\n")
        git(self.remote, "add", "-A")
        git(self.remote, "commit", "-q", "-m", label)
        return git(self.remote, "rev-parse", "HEAD")

    def resolve(self, ref: str) -> str:
        """Resolve `ref` against the stand-in upstream."""
        return tmux_ref.resolve(ref, str(self.remote))

    def test_a_tag_names_its_commit(self) -> None:
        """A release tag resolves to the commit it marks."""
        self.assertEqual(self.resolve("1.0"), self.one)

    def test_an_annotated_tag_resolves_to_the_commit(self) -> None:
        """The tag object's own id is not a commit to build."""
        tag_object = git(self.remote, "rev-parse", "2.0")
        self.assertNotEqual(tag_object, self.two)
        self.assertEqual(self.resolve("2.0"), self.two)

    def test_a_branch_names_its_tip(self) -> None:
        """A branch resolves to where it points now."""
        self.assertEqual(self.resolve("topic"), self.two)
        self.assertEqual(self.resolve("master"), self.three)

    def test_a_tag_beats_a_branch_of_the_same_name(self) -> None:
        """The released tag is what a version number means."""
        self.assertNotEqual(self.one, self.three)
        self.assertEqual(self.resolve("dual"), self.one)

    def test_a_full_commit_names_itself(self) -> None:
        """No network is needed to resolve a 40-hex commit."""
        self.assertEqual(tmux_ref.resolve(self.two, "/nonexistent"), self.two)

    def test_an_unknown_or_abbreviated_ref_is_an_error(self) -> None:
        """A ref that matches nothing never yields an empty cache key."""
        for ref in ("9.9", self.two[:7]):
            with self.subTest(ref=ref), self.assertRaises(tmux_ref.RefError):
                self.resolve(ref)

    def test_a_malformed_ref_is_an_error(self) -> None:
        """Nothing outside the allowed characters reaches git."""
        for bad in ("--upload-pack=x", "a b", "x;id", "", "y" * 65):
            with self.subTest(ref=bad), self.assertRaises(tmux_ref.RefError):
                self.resolve(bad)

    def test_a_release_is_keyed_by_its_version(self) -> None:
        """A tarball never changes, so the version is the whole key."""
        self.assertEqual(tmux_ref.cache_key("release", "3.7c"), {"key": "3.7c"})

    def test_only_a_release_name_is_a_release(self) -> None:
        """A path or a branch cannot be smuggled in as a release."""
        for ref in ("master", "../3.8", "3.8/x", "v3.8"):
            with self.subTest(ref=ref), self.assertRaises(tmux_ref.RefError):
                tmux_ref.cache_key("release", ref)

    def test_anything_else_is_keyed_by_its_commit(self) -> None:
        """A branch name in the key would freeze the lane."""
        self.assertEqual(
            tmux_ref.cache_key("git", "master", str(self.remote)),
            {"key": f"sha-{self.three}", "sha": self.three},
        )

    def test_build_release_installs_the_tarball(self) -> None:
        """A release builds from its tarball."""
        prefix = self.root / "prefix"
        tmux_ref.build_release("1.0", prefix, self.root / "work", self.releases, jobs=1)
        self.assertEqual(installed(prefix), "release")

    def test_build_release_refuses_a_missing_release(self) -> None:
        """A release that does not exist fails the build."""
        with self.assertRaises(subprocess.CalledProcessError):
            tmux_ref.build_release(
                "9.9", self.root / "p", self.root / "work", self.releases, jobs=1
            )

    def test_build_git_installs_the_tagged_commit(self) -> None:
        """A commit builds its own tree even though master has moved on."""
        prefix = self.root / "prefix"
        tmux_ref.build_git(
            self.one, prefix, self.root / "work", str(self.remote), jobs=1
        )
        self.assertEqual(installed(prefix), "one")

    def test_build_git_installs_a_commit_no_ref_names(self) -> None:
        """A bare commit builds the same way as a tag."""
        git(self.remote, "branch", "-D", "topic")
        git(self.remote, "tag", "-d", "2.0")
        prefix = self.root / "prefix"
        tmux_ref.build_git(
            self.two, prefix, self.root / "work", str(self.remote), jobs=1
        )
        self.assertEqual(installed(prefix), "two")

    def test_build_git_takes_only_a_resolved_commit(self) -> None:
        """A name must be resolved first, so the cache key and the build agree."""
        with self.assertRaises(tmux_ref.RefError):
            tmux_ref.build_git(
                "1.0", self.root / "p", self.root / "w", str(self.remote)
            )

    def test_resolve_writes_github_output(self) -> None:
        """The workflow reads the key and the commit from the step output."""
        output = self.root / "out"
        status = tmux_ref.main(
            [
                "--remote",
                str(self.remote),
                "resolve",
                "--source",
                "git",
                "1.0",
                "--github-output",
                str(output),
            ]
        )
        self.assertEqual(status, 0)
        self.assertEqual(output.read_text(), f"key=sha-{self.one}\nsha={self.one}\n")

    def test_resolve_failure_exits_nonzero(self) -> None:
        """An unresolvable ref fails the step."""
        arguments = ["--remote", str(self.remote), "resolve", "--source", "git", "9.9"]
        self.assertEqual(tmux_ref.main(arguments), 1)


if __name__ == "__main__":
    unittest.main()
