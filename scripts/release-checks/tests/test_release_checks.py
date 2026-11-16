"""Prove the release decisions accept what the project publishes and refuse the rest."""

import os
import pathlib
import stat
import sys

import pytest

import release_checks as checks


@pytest.mark.parametrize(
    ("input_version", "ref_name", "expected"),
    [
        ("", "v0.1.2", "0.1.2"),
        ("0.2.0", "main", "0.2.0"),
        ("1.0.0-rc.1", "", "1.0.0-rc.1"),
        ("1.0.0-alpha.3", "", "1.0.0-alpha.3"),
    ],
)
def test_a_tag_or_a_manual_run_names_the_version(
    input_version: str, ref_name: str, expected: str
) -> None:
    assert checks.release_version(input_version, ref_name) == expected


def test_a_snapshot_is_refused() -> None:
    with pytest.raises(
        checks.ReleaseCheckError, match=r"refusing to release a snapshot: 0\.2\.0-SNAPSHOT"
    ):
        checks.release_version("", "v0.2.0-SNAPSHOT")


@pytest.mark.parametrize("version", ["0.1", "0.0.1-a.1", "v1.2.3", "1.2.3-rc", "1.2.3 ", "1.2.3\n"])
def test_a_spelling_the_project_does_not_publish_is_refused(version: str) -> None:
    with pytest.raises(checks.ReleaseCheckError, match="not a version this project publishes"):
        checks.release_version(version, "")


def test_the_staged_version_drops_the_snapshot_suffix(tmp_path: pathlib.Path) -> None:
    properties = tmp_path / "gradle.properties"
    properties.write_text(
        "org.gradle.jvmargs=-Xmx1g\nlibtmuxVersion=0.2.0-SNAPSHOT\n", encoding="utf-8"
    )

    assert checks.staged_version(properties) == "0.2.0"


def test_a_properties_file_with_no_version_is_an_error(tmp_path: pathlib.Path) -> None:
    properties = tmp_path / "gradle.properties"
    properties.write_text("a=b\n", encoding="utf-8")

    with pytest.raises(checks.ReleaseCheckError, match="has no libtmuxVersion"):
        checks.staged_version(properties)


def gh(directory: pathlib.Path, conclusions: list[str]) -> None:
    """Put a `gh` on PATH that answers `run list` with these conclusions."""
    program = directory / "gh"
    runs = [{"conclusion": conclusion} for conclusion in conclusions]
    program.write_text(
        f"#!{sys.executable}\nimport json\nprint(json.dumps({runs!r}))\n", encoding="utf-8"
    )
    program.chmod(program.stat().st_mode | stat.S_IXUSR)


def test_one_successful_run_for_the_commit_passes(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    gh(tmp_path, ["failure", "success"])
    monkeypatch.setenv("PATH", f"{tmp_path}{os.pathsep}{os.environ['PATH']}")

    checks.ci_passed("ci.yml", "abc")


def test_no_successful_run_for_the_commit_refuses_the_release(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    gh(tmp_path, ["failure", "cancelled"])
    monkeypatch.setenv("PATH", f"{tmp_path}{os.pathsep}{os.environ['PATH']}")

    with pytest.raises(checks.ReleaseCheckError, match=r"ci\.yml has no successful run for abc"):
        checks.ci_passed("ci.yml", "abc")


def test_the_passphrase_is_carried_only_when_there_is_one(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    environment = tmp_path / "env"
    monkeypatch.setenv("GITHUB_ENV", str(environment))

    monkeypatch.setenv("PASSPHRASE", "")
    assert checks.main(["carry-passphrase"]) == 0
    assert not environment.exists()
    assert "signing key has no passphrase" in capsys.readouterr().out

    monkeypatch.setenv("PASSPHRASE", "line one\nline two")
    assert checks.main(["carry-passphrase"]) == 0
    written = environment.read_text(encoding="utf-8").splitlines()
    assert written[0].startswith(f"{checks.PASSPHRASE_PROPERTY}<<ghadelimiter_")
    assert written[1:3] == ["line one", "line two"]
    assert "signing key has a passphrase" in capsys.readouterr().out


def test_the_command_writes_the_validated_version_to_the_output_file(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    output = tmp_path / "output"
    monkeypatch.setenv("GITHUB_OUTPUT", str(output))

    assert checks.main(["release-version", "--ref-name", "v0.3.0"]) == 0

    assert output.read_text(encoding="utf-8") == "version=0.3.0\n"
    assert capsys.readouterr().out == "releasing 0.3.0\n"


def test_a_refused_version_is_status_1_and_writes_nothing(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    output = tmp_path / "output"
    monkeypatch.setenv("GITHUB_OUTPUT", str(output))

    assert checks.main(["release-version", "--ref-name", "vnext"]) == 1

    assert not output.exists()
    assert "::error::not a version this project publishes: next" in capsys.readouterr().err


def test_a_gh_that_hangs_refuses_the_release_instead_of_holding_the_job(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    gh = tmp_path / "gh"
    gh.write_text(f"#!{sys.executable}\nimport time\ntime.sleep(30)\n", encoding="utf-8")
    gh.chmod(0o755)
    monkeypatch.setenv("PATH", f"{tmp_path}{os.pathsep}{os.environ['PATH']}")
    monkeypatch.setattr(checks, "NETWORK_SECONDS", 0.3)
    with pytest.raises(checks.ReleaseCheckError, match="cannot list the runs"):
        checks.ci_passed("ci.yml", "abc123")
