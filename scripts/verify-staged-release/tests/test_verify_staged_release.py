"""Tests for the staged release check.

Each test stages a small Maven repository, with real signatures from a throwaway
key, and breaks it one way: a guard that cannot fail is not guarding anything.
"""

import hashlib
import os
import shutil
import subprocess
import sys
import zipfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

import pytest

import verify_staged_release as verify

VERSION = "9.9.9"
# Artifact id to module: a Scala artifact's id ends in the binary version.
ARTIFACTS = {"libtmux": "libtmux", "libtmux-scala_3": "libtmux-scala"}
POM = (
    "<project><name>n</name><description>d</description><url>u</url>"
    "<licenses><license></license></licenses><developers><developer></developer></developers>"
    "<scm><connection>c</connection></scm>{extra}</project>"
)


@dataclass(frozen=True)
class Staging:
    """A staged repository and the functions that change it."""

    repository: Path
    stage: Callable[..., None]
    jar: Callable[[Path, dict[str, str]], None]


def changelog(module: str) -> str:
    """Return a changelog that meets the policy for `module`."""
    return f"# Changelog\n\nNotable changes to `{module}`.\n\n## Unreleased\n"


@pytest.fixture(scope="module")
def gnupg(tmp_path_factory: pytest.TempPathFactory) -> dict[str, str]:
    """Make a throwaway signing key and return the environment that uses it."""
    # Skipping where the tools are missing is fine on a laptop. In CI it would
    # quietly stop the check guarding anything, so there it fails.
    if shutil.which("gpg") is None:
        message = "gpg is needed to sign a staging"
        if os.environ.get("CI"):
            pytest.fail(message)
        pytest.skip(message)
    home = tmp_path_factory.mktemp("gnupg")
    home.chmod(0o700)
    environment = {**os.environ, "GNUPGHOME": str(home)}
    made = subprocess.run(
        [
            "gpg",
            "--batch",
            "--pinentry-mode",
            "loopback",
            "--passphrase",
            "",
            "--quick-generate-key",
            "staging <staging@example.invalid>",
            "default",
            "default",
            "never",
        ],
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )
    if made.returncode != 0:
        message = f"gpg cannot make a key here: {made.stderr.strip()}"
        if os.environ.get("CI"):
            pytest.fail(message)
        pytest.skip(message)
    return environment


@pytest.fixture
def staging(tmp_path: Path, gnupg: dict[str, str], monkeypatch: pytest.MonkeyPatch) -> Staging:
    """Stage every artifact into a fresh repository."""
    monkeypatch.setenv("GNUPGHOME", gnupg["GNUPGHOME"])
    repository = tmp_path / "repository"

    def seal(path: Path) -> None:
        """Write the checksums and signature Central wants beside `path`."""
        data = path.read_bytes()
        path.with_name(path.name + ".md5").write_text(hashlib.md5(data).hexdigest() + "\n")
        path.with_name(path.name + ".sha1").write_text(hashlib.sha1(data).hexdigest() + "\n")
        subprocess.run(
            [
                "gpg",
                "--batch",
                "--yes",
                "--pinentry-mode",
                "loopback",
                "--passphrase",
                "",
                "--armor",
                "--detach-sign",
                "--output",
                f"{path}.asc",
                str(path),
            ],
            env=gnupg,
            capture_output=True,
            check=True,
        )

    def jar(path: Path, files: dict[str, str]) -> None:
        """Write a jar of `files` and seal it."""
        with zipfile.ZipFile(path, "w") as archive:
            for name, text in files.items():
                archive.writestr(name, text)
        seal(path)

    def stage(
        omit: str | None = None,
        artifacts: dict[str, str] = ARTIFACTS,
        **texts: str,
    ) -> None:
        """Stage every artifact; `omit` names a module whose jar lacks its changelog."""
        base = repository / "io" / "github" / "libtmux"
        bom = base / "libtmux-bom" / VERSION
        bom.mkdir(parents=True, exist_ok=True)
        ids = "".join(f"<artifactId>{artifact}</artifactId>\n" for artifact in artifacts)
        bom_pom = bom / f"libtmux-bom-{VERSION}.pom"
        bom_pom.write_text(
            POM.format(
                extra=f"<packaging>pom</packaging>\n<artifactId>libtmux-bom</artifactId>\n{ids}"
            )
        )
        seal(bom_pom)
        for artifact, module in artifacts.items():
            directory = base / artifact / VERSION
            directory.mkdir(parents=True, exist_ok=True)
            stem = directory / f"{artifact}-{VERSION}"
            pom = Path(f"{stem}.pom")
            pom.write_text(POM.format(extra=""))
            seal(pom)
            files = {"io/github/libtmux/A.class": "x"}
            if module != omit:
                files[f"META-INF/libtmux/{module}/CHANGELOG.md"] = texts.get(
                    module, changelog(module)
                )
            jar(Path(f"{stem}.jar"), files)
            jar(Path(f"{stem}-sources.jar"), {"io/github/libtmux/A.java": "class A {}"})
            jar(Path(f"{stem}-javadoc.jar"), {"index.html": "<html/>"})

    stage()
    return Staging(repository, stage, jar)


def run(repository: Path, capsys: pytest.CaptureFixture[str]) -> tuple[int, str]:
    """Run the script on the staged repository and return its status and output."""
    status = verify.main([VERSION, str(repository)])
    return status, capsys.readouterr().out


def test_a_staging_that_meets_every_rule_passes(
    staging: Staging, capsys: pytest.CaptureFixture[str]
) -> None:
    """The fixture is clean, so each failure below is the break's."""
    status, out = run(staging.repository, capsys)
    assert status == 0, out
    assert "3 artifacts staged as Central requires them" in out


def test_clojure_source_archives_are_supported(
    staging: Staging, capsys: pytest.CaptureFixture[str]
) -> None:
    """A source-only Clojure module needs no Java stub in its sources jar."""
    staging.stage(artifacts={**ARTIFACTS, "libtmux-clojure": "libtmux-clojure"})
    source = (
        staging.repository
        / "io/github/libtmux/libtmux-clojure"
        / VERSION
        / f"libtmux-clojure-{VERSION}-sources.jar"
    )
    staging.jar(source, {"libtmux/core.clj": "(ns libtmux.core)"})
    status, out = run(staging.repository, capsys)
    assert status == 0, out


def test_a_source_archive_with_only_resources_is_refused(
    staging: Staging, capsys: pytest.CaptureFixture[str]
) -> None:
    """Accepting Clojure source must not make an empty classifier pass."""
    source = (
        staging.repository
        / "io/github/libtmux/libtmux"
        / VERSION
        / f"libtmux-{VERSION}-sources.jar"
    )
    staging.jar(source, {"README.md": "No source."})
    status, out = run(staging.repository, capsys)
    assert status == 1
    assert "libtmux: sources jar holds no source" in out


def test_a_jar_without_its_changelog_is_refused(
    staging: Staging, capsys: pytest.CaptureFixture[str]
) -> None:
    """Dropping the file from a Scala jar names the module, not the artifact id."""
    staging.stage(omit="libtmux-scala")
    status, out = run(staging.repository, capsys)
    assert status == 1
    assert "libtmux-scala_3: jar has no META-INF/libtmux/libtmux-scala/CHANGELOG.md" in out
    assert "libtmux: jar has no" not in out


def test_another_modules_changelog_is_refused(
    staging: Staging, capsys: pytest.CaptureFixture[str]
) -> None:
    """A changelog copied over another module's fails where it was copied."""
    staging.stage(libtmux=changelog("libtmux-mcp"))
    status, out = run(staging.repository, capsys)
    assert status == 1
    assert "does not open with" in out


def test_a_checksum_that_does_not_match_is_refused(
    staging: Staging, capsys: pytest.CaptureFixture[str]
) -> None:
    """A jar changed after it was sealed no longer matches its sha1."""
    jar = staging.repository / "io/github/libtmux/libtmux" / VERSION / f"libtmux-{VERSION}.jar"
    jar.write_bytes(jar.read_bytes() + b"x")
    status, out = run(staging.repository, capsys)
    assert status == 1
    assert f"libtmux: libtmux-{VERSION}.jar.sha1 does not match" in out
    assert f"libtmux: libtmux-{VERSION}.jar.asc does not verify" in out


def test_a_missing_signature_is_refused(
    staging: Staging, capsys: pytest.CaptureFixture[str]
) -> None:
    """Central takes no unsigned file."""
    (
        staging.repository / "io/github/libtmux/libtmux" / VERSION / f"libtmux-{VERSION}.pom.asc"
    ).unlink()
    status, out = run(staging.repository, capsys)
    assert status == 1
    assert f"libtmux: missing libtmux-{VERSION}.pom.asc" in out


def test_a_snapshot_pom_is_refused(staging: Staging, capsys: pytest.CaptureFixture[str]) -> None:
    """A release names no snapshot."""
    pom = staging.repository / "io/github/libtmux/libtmux" / VERSION / f"libtmux-{VERSION}.pom"
    pom.write_text(POM.format(extra="<version>1-SNAPSHOT</version>"))
    status, out = run(staging.repository, capsys)
    assert status == 1
    assert "libtmux: POM names a snapshot" in out


def test_a_missing_bom_is_refused(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    """Nothing is staged without the BOM that names the artifacts."""
    status, out = run(tmp_path, capsys)
    assert status == 1
    assert "no staged BOM at" in out


def test_a_gpg_that_hangs_does_not_verify(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    gpg = tmp_path / "gpg"
    gpg.write_text(f"#!{sys.executable}\nimport time\ntime.sleep(30)\n", encoding="utf-8")
    gpg.chmod(0o755)
    monkeypatch.setenv("PATH", f"{tmp_path}{os.pathsep}{os.environ['PATH']}")
    monkeypatch.setattr(verify.bounded, "PROBE", 0.3)
    assert verify.signature_verifies(tmp_path / "a.asc", tmp_path / "a") is False
