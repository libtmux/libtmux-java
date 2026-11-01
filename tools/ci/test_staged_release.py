"""Tests for the changelog rule in tools/verify-staged-release.sh.

The script checks a staged Maven repository against what the Central Portal
refuses. These tests stage a small one, with real signatures from a throwaway
key, and break its jars one way at a time: a guard that cannot fail is not
guarding anything.
"""

from __future__ import annotations

import hashlib
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "verify-staged-release.sh"
VERSION = "9.9.9"
# Artifact id to module: a Scala artifact's id ends in the binary version.
ARTIFACTS = {"libtmux": "libtmux", "libtmux-scala_3": "libtmux-scala"}
POM = (
    "<project><name>n</name><description>d</description><url>u</url>"
    "<licenses><license></license></licenses><developers><developer></developer></developers>"
    "<scm><connection>c</connection></scm>{extra}</project>"
)


def changelog(module: str) -> str:
    """Return a changelog that meets the policy for `module`."""
    return f"# Changelog\n\nNotable changes to `{module}`.\n\n## Unreleased\n"


class StagedReleaseTest(unittest.TestCase):
    """Stage a repository, break one jar, and run the script on it."""

    @classmethod
    def setUpClass(cls) -> None:
        """Make a throwaway signing key, or skip when gpg cannot."""
        # Skipping where the tools are missing is fine on a laptop. In CI it would
        # quietly stop the check guarding anything, so there it fails.
        missing = shutil.which("gpg") is None or shutil.which("unzip") is None
        if missing and os.environ.get("CI"):
            raise AssertionError("gpg and unzip are needed to sign and read a staging")
        if missing:
            raise unittest.SkipTest("gpg and unzip are needed to sign and read a staging")
        cls.home = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.home.cleanup)
        os.chmod(cls.home.name, 0o700)
        cls.env = {**os.environ, "GNUPGHOME": cls.home.name}
        made = subprocess.run(
            ["gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "",
             "--quick-generate-key", "staging <staging@example.invalid>", "default", "default", "never"],
            env=cls.env, capture_output=True, text=True, check=False,
        )
        if made.returncode != 0 and os.environ.get("CI"):
            raise AssertionError(f"gpg cannot make a key here: {made.stderr.strip()}")
        if made.returncode != 0:
            raise unittest.SkipTest(f"gpg cannot make a key here: {made.stderr.strip()}")

    def setUp(self) -> None:
        """Stage a repository that meets every rule."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = pathlib.Path(self.temporary.name)
        self.stage()

    def seal(self, path: pathlib.Path) -> None:
        """Write the checksums and signature Central wants beside `path`."""
        data = path.read_bytes()
        path.with_name(path.name + ".md5").write_text(hashlib.md5(data).hexdigest() + "\n")
        path.with_name(path.name + ".sha1").write_text(hashlib.sha1(data).hexdigest() + "\n")
        subprocess.run(
            ["gpg", "--batch", "--yes", "--pinentry-mode", "loopback", "--passphrase", "",
             "--armor", "--detach-sign", "--output", str(path) + ".asc", str(path)],
            env=self.env, capture_output=True, check=True,
        )

    def jar(self, path: pathlib.Path, files: dict[str, str]) -> None:
        """Write a jar of `files` and seal it."""
        with zipfile.ZipFile(path, "w") as archive:
            for name, text in files.items():
                archive.writestr(name, text)
        self.seal(path)

    def stage(self, omit: str | None = None, **texts: str) -> None:
        """Stage every artifact; `omit` names a module whose jar lacks its changelog."""
        base = self.repo / "io" / "github" / "libtmux"
        bom = base / "libtmux-bom" / VERSION
        bom.mkdir(parents=True, exist_ok=True)
        (bom / f"libtmux-bom-{VERSION}.pom").write_text(
            POM.format(extra="<packaging>pom</packaging>\n" + "".join(
                f"<artifactId>{artifact}</artifactId>\n" for artifact in ("libtmux-bom", *ARTIFACTS)))
        )
        self.seal(bom / f"libtmux-bom-{VERSION}.pom")
        for artifact, module in ARTIFACTS.items():
            directory = base / artifact / VERSION
            directory.mkdir(parents=True, exist_ok=True)
            stem = directory / f"{artifact}-{VERSION}"
            pom = pathlib.Path(f"{stem}.pom")
            pom.write_text(POM.format(extra=""))
            self.seal(pom)
            files = {"io/github/libtmux/A.class": "x"}
            if module != omit:
                files[f"META-INF/libtmux/{module}/CHANGELOG.md"] = texts.get(module, changelog(module))
            self.jar(pathlib.Path(f"{stem}.jar"), files)
            self.jar(pathlib.Path(f"{stem}-sources.jar"), {"io/github/libtmux/A.java": "class A {}"})
            self.jar(pathlib.Path(f"{stem}-javadoc.jar"), {"index.html": "<html/>"})

    def verify(self) -> subprocess.CompletedProcess[str]:
        """Run the script on the staged repository."""
        return subprocess.run(
            [str(SCRIPT), VERSION, str(self.repo)],
            env=self.env, capture_output=True, text=True, check=False,
        )

    def test_a_staging_that_meets_every_rule_passes(self) -> None:
        """The fixture is clean, so each failure below is the break's."""
        done = self.verify()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)

    def test_a_jar_without_its_changelog_is_refused(self) -> None:
        """Dropping the file from a Scala jar names the module, not the artifact id."""
        self.stage(omit="libtmux-scala")
        done = self.verify()
        self.assertEqual(done.returncode, 1, done.stdout)
        self.assertIn("libtmux-scala_3: jar has no META-INF/libtmux/libtmux-scala/CHANGELOG.md", done.stdout)
        self.assertNotIn("libtmux: jar has no", done.stdout)

    def test_another_modules_changelog_is_refused(self) -> None:
        """A changelog copied over another module's fails where it was copied."""
        self.stage(**{"libtmux": changelog("libtmux-mcp")})
        done = self.verify()
        self.assertEqual(done.returncode, 1, done.stdout)
        self.assertIn("does not open with", done.stdout)


if __name__ == "__main__":
    unittest.main()
