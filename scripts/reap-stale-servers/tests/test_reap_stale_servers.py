"""Tests for the stale server reaper.

A fake `/proc` and a stand-in process named `tmux` replace real servers, so the
tests start no tmux and cannot reap one that belongs to somebody else.
"""

from __future__ import annotations

import os
import signal
import socket
import stat
import subprocess
import sys
from pathlib import Path

import pytest

import reap_stale_servers as reaper

OURS = "/tmp/libtmux-java-test/run-1/s"
DEV = "/tmp/libtmux-java-dev/api.1/tmux.sock"
THEIRS = "/tmp/libtmux-rs-test/run-1/s"
SPACED = "/tmp/libtmux-java-test/a directory/s"


def fake_proc(root: Path, entries: dict[int, tuple[str, str | list[str]]]) -> Path:
    """Write a `/proc` with a process name and a command line for each pid.

    A command line given as a string splits at spaces; one given as a list keeps
    each item whole, as the kernel does.
    """
    for pid, (name, command_line) in entries.items():
        directory = root / str(pid)
        directory.mkdir(parents=True)
        (directory / "comm").write_text(name + "\n")
        words = command_line.split(" ") if isinstance(command_line, str) else command_line
        (directory / "cmdline").write_bytes("\0".join(words).encode() + b"\0")
    (root / "self").mkdir()
    (root / "version").write_text("fake\n")
    return root


def test_find_servers_reads_the_socket_of_each_tmux_process(tmp_path: Path) -> None:
    """Only processes named tmux with a socket on the command line are servers."""
    proc = fake_proc(
        tmp_path,
        {
            11: ("tmux: server", f"tmux -S {OURS} new-session -d"),
            12: ("tmux", f"tmux -S {THEIRS} new-session -d"),
            13: ("tmux", "tmux new-session -d"),
            14: ("bash", f"bash -c tmux -S {OURS}"),
            15: ("tmux", f"tmux -S {DEV} -f /dev/null new-session"),
            16: ("tmux", ["tmux", "-S", SPACED, "new-session"]),
        },
    )
    assert reaper.find_servers(proc) == [
        reaper.Server(11, OURS),
        reaper.Server(12, THEIRS),
        reaper.Server(15, DEV),
        reaper.Server(16, SPACED),
    ]


def test_only_this_ports_roots_are_ours() -> None:
    """A sibling port's root, or a lookalike prefix, is not ours to touch."""
    assert reaper.is_ours(OURS)
    assert reaper.is_ours(DEV)
    assert not reaper.is_ours(THEIRS)
    assert not reaper.is_ours("/tmp/libtmux-java-testing/s")
    assert not reaper.is_ours("/tmp/libtmux-/s")


def report(servers: list[reaper.Server], *, reap: bool) -> str:
    """Return the text the script prints for these servers."""
    lines: list[str] = []
    reaper.report(servers, reap=reap, out=lines)
    return "\n".join(lines)


def test_the_default_run_lists_what_it_would_reap() -> None:
    """Without --reap nothing is named as done, and the way to do it is."""
    servers = [reaper.Server(11, OURS), reaper.Server(12, THEIRS)]
    assert report(servers, reap=False) == (
        f"would reap  11  {OURS}\n\nours: 1\nother ports': 1  (not this script's to touch)\n\n"
        "re-run with --reap to end them"
    )


def test_a_reap_run_says_what_it_did_and_does_not_offer_another() -> None:
    """With --reap the verbs are past tense and there is nothing to re-run."""
    servers = [reaper.Server(11, OURS)]
    assert report(servers, reap=True) == (
        f"reaped  11  {OURS}\n\nours: 1\nother ports': 0  (not this script's to touch)"
    )


def test_nothing_of_ours_means_no_offer_to_reap() -> None:
    """A run that finds only other ports' servers has nothing to re-run."""
    assert "re-run" not in report([reaper.Server(12, THEIRS)], reap=False)


def test_a_live_socket_is_asked_to_stop_before_any_signal(tmp_path: Path) -> None:
    """kill-server is tried first, and a signal follows only when it fails."""
    path = tmp_path / "s"
    listener = socket.socket(socket.AF_UNIX)
    listener.bind(str(path))
    try:
        stopped: list[str] = []
        signalled: list[int] = []
        reaper.reap_one(
            reaper.Server(11, str(path)),
            stop=lambda target: stopped.append(target) is None,
            kill=signalled.append,
        )
        assert stopped == [str(path)]
        assert signalled == []
        reaper.reap_one(
            reaper.Server(12, str(path)),
            stop=lambda _: False,
            kill=signalled.append,
        )
        assert signalled == [12]
    finally:
        listener.close()


def test_a_server_whose_socket_is_gone_is_signalled(tmp_path: Path) -> None:
    """With the socket unlinked a signal is the only way left."""
    stopped: list[str] = []
    signalled: list[int] = []
    reaper.reap_one(
        reaper.Server(11, str(tmp_path / "gone")),
        stop=lambda target: stopped.append(target) is None,
        kill=signalled.append,
    )
    assert stopped == []
    assert signalled == [11]


def test_terminate_ignores_a_process_that_is_already_gone() -> None:
    """A race with the server exiting is not an error."""
    done = subprocess.Popen(["true"])
    done.wait()
    reaper.terminate(done.pid)


def stand_in_tmux(directory: Path, body: str) -> Path:
    """Write an executable Python program named `tmux`, whose process name is tmux's."""
    program = directory / "tmux"
    program.write_text(f"#!{sys.executable}\n{body}", encoding="utf-8")
    program.chmod(program.stat().st_mode | stat.S_IXUSR)
    return program


@pytest.mark.skipif(not Path("/proc/self/comm").exists(), reason="needs /proc")
def test_a_running_stand_in_is_found_and_ended(tmp_path: Path) -> None:
    """A process named tmux whose socket is under our root is found and signalled.

    The stand-in is a script called `tmux`, so its process name is the one a real
    server has. Its socket path lies under this port's root without existing.
    `Popen` returns once the program has been executed, so its name and command
    line are already in `/proc` and the lookup needs no retry.
    """
    stand_in = stand_in_tmux(tmp_path, "import time\ntime.sleep(30)\n")
    root = Path("/tmp/libtmux-java-test")
    root.mkdir(exist_ok=True)
    unreachable = root / f"reap-test-{os.getpid()}" / "s"
    child = subprocess.Popen([str(stand_in), "-S", str(unreachable)])
    try:
        found = [server for server in reaper.find_servers() if server.pid == child.pid]
        assert found == [reaper.Server(child.pid, str(unreachable))]
        reaper.reap_one(found[0])
        assert child.wait(timeout=5) == -signal.SIGTERM
    finally:
        child.kill()
        child.wait()


def test_a_server_that_never_answers_kill_server_is_given_up_on(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    """A wedged server must not hold the reaper; it is reported and left to the signal."""
    stand_in_tmux(tmp_path, "import time\ntime.sleep(30)\n")
    monkeypatch.setenv("PATH", f"{tmp_path}{os.pathsep}{os.environ['PATH']}")
    monkeypatch.setattr(reaper, "KILL_SERVER_SECONDS", 0.5)

    assert reaper.kill_server("/tmp/libtmux-java-test/wedged/s") is False

    assert "did not answer within 0.5 seconds" in capsys.readouterr().err
