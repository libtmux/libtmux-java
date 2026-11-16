"""Prove a surviving tmux server fails the run and a clean machine passes it."""

from __future__ import annotations

import os
import pathlib
import stat
import subprocess
import sys

import pytest

import no_tmux_survivors as survivors


def stand_in(directory: pathlib.Path, name: str, body: str) -> None:
    """Write an executable Python program named `name`."""
    path = directory / name
    path.write_text(f"#!{sys.executable}\n{body}", encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


@pytest.fixture
def commands(tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch) -> pathlib.Path:
    """A PATH whose `pgrep` and `ps` answer from files the test writes."""
    stand_in(
        tmp_path,
        "pgrep",
        f"import pathlib, sys\n"
        f"found = pathlib.Path({str(tmp_path / 'pids')!r})\n"
        "if not found.exists() or not found.read_text().strip():\n"
        "    sys.exit(1)\n"
        "print(found.read_text(), end='')\n",
    )
    stand_in(tmp_path, "ps", "import sys\nprint('tmux -S /tmp/x/s new-session ' + sys.argv[-1])\n")
    monkeypatch.setenv("PATH", f"{tmp_path}{os.pathsep}{os.environ['PATH']}")
    return tmp_path


def test_a_machine_with_no_tmux_process_passes(
    commands: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert survivors.main([]) == 0

    assert capsys.readouterr().out == "no tmux server survived the suite\n"


def test_each_survivor_is_named_and_the_run_fails(
    commands: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    (commands / "pids").write_text("11\n12\n", encoding="utf-8")

    assert survivors.main([]) == 1

    assert capsys.readouterr().out.splitlines() == [
        "::error::2 tmux server(s) survived the suite",
        "tmux -S /tmp/x/s new-session 11",
        "tmux -S /tmp/x/s new-session 12",
    ]


def test_a_failing_pgrep_is_an_error_and_not_a_clean_machine(
    commands: pathlib.Path,
) -> None:
    stand_in(commands, "pgrep", "raise SystemExit(3)\n")

    with pytest.raises(RuntimeError, match="pgrep failed with status 3"):
        survivors.survivor_pids()


def test_the_command_runs_as_a_program(commands: pathlib.Path) -> None:
    done = subprocess.run(
        [sys.executable, survivors.__file__], capture_output=True, text=True, check=False
    )

    assert (done.returncode, done.stdout) == (0, "no tmux server survived the suite\n")
