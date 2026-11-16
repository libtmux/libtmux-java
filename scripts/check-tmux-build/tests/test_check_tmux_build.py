"""Prove a lane fails when the tmux it built is not the one it is named after."""

import pathlib
import stat
import sys

import pytest

import check_tmux_build as build


@pytest.fixture
def builds(tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch) -> pathlib.Path:
    """A `~/tmux-builds` that holds whatever the test installs."""
    monkeypatch.setattr(build, "BUILDS", tmp_path)
    return tmp_path


def install(builds: pathlib.Path, slug: str, reported: str) -> None:
    """Install a tmux under `slug` that prints `reported` for `-V`."""
    binary = builds / slug / "bin" / "tmux"
    binary.parent.mkdir(parents=True)
    binary.write_text(f"#!{sys.executable}\nprint({reported!r})\n", encoding="utf-8")
    binary.chmod(binary.stat().st_mode | stat.S_IXUSR)


def test_a_release_build_that_reports_its_release_passes(
    builds: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    install(builds, "3.8", "tmux 3.8")

    assert build.main(["--slug", "3.8", "--ref", "3.8", "--verify", "release"]) == 0

    assert capsys.readouterr().out == "3.8: tmux 3.8\n"


def test_a_release_build_that_reports_another_release_fails(
    builds: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    install(builds, "3.8", "tmux 3.8a")

    assert build.main(["--slug", "3.8", "--ref", "3.8", "--verify", "release"]) == 1

    assert "::error::the 3.8 build reports tmux 3.8a" in capsys.readouterr().out


def test_a_development_build_must_report_next(
    builds: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    install(builds, "master", "tmux next-3.9")
    assert build.main(["--slug", "master", "--ref", "master", "--verify", "development"]) == 0

    install(builds, "main", "tmux 3.8")
    assert build.main(["--slug", "main", "--ref", "main", "--verify", "development"]) == 1
    assert "::error::main reports tmux 3.8" in capsys.readouterr().out


def test_a_ref_with_nothing_to_verify_is_only_printed(
    builds: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    install(builds, "abc123", "tmux 3.8-dirty")

    assert build.main(["--slug", "abc123", "--ref", "abc123"]) == 0

    assert capsys.readouterr().out == "abc123: tmux 3.8-dirty\n"


def test_the_client_build_is_checked_as_a_release_of_its_own(
    builds: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    install(builds, "3.1c", "tmux 3.1c")
    install(builds, "3.3a", "tmux 3.3")

    status = build.main(
        ["--slug", "3.1c", "--ref", "3.1c", "--verify", "release", "--client", "3.3a"]
    )

    assert status == 1
    assert "::error::the 3.3a build reports tmux 3.3" in capsys.readouterr().out


def test_an_empty_verify_mode_only_prints(
    builds: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """A matrix entry with no verify mode names no expectation."""
    install(builds, "abc123", "tmux 3.9-rc")

    assert build.main(["--slug", "abc123", "--ref", "abc123", "--verify", "", "--client", ""]) == 0

    assert capsys.readouterr().out == "abc123: tmux 3.9-rc\n"


def test_a_tmux_that_hangs_on_its_version_fails_the_lane(
    builds: pathlib.Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    binary = builds / "3.8" / "bin" / "tmux"
    binary.parent.mkdir(parents=True)
    binary.write_text(f"#!{sys.executable}\nimport time\ntime.sleep(30)\n", encoding="utf-8")
    binary.chmod(binary.stat().st_mode | stat.S_IXUSR)
    monkeypatch.setattr(build.bounded, "PROBE", 0.3)
    assert build.check("3.8", "3.8", "release") is False
    assert "::error::cannot read the version of the 3.8 build" in capsys.readouterr().out
