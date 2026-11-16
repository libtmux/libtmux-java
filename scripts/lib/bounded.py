"""Run external commands under a time limit that also stops the processes they start."""

import contextlib
import os
import signal
import subprocess
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

# Seconds a command that only reads local state may run: `tmux -V`, `pgrep`, `ps`,
# `gpg --verify`, `brew --prefix`. README.md records what it sits above.
PROBE = 10

type Command = Sequence[str | os.PathLike[str]]


def stop_group(pid: int) -> None:
    """Kill every process in the process group a bounded command leads."""
    with contextlib.suppress(ProcessLookupError):
        os.killpg(pid, signal.SIGKILL)


def run(
    args: Command,
    *,
    timeout: float,
    check: bool = False,
    capture_output: bool = False,
    text: bool = False,
    cwd: Path | None = None,
    env: Mapping[str, str] | None = None,
    stdout: int | None = None,
    stderr: int | None = None,
    pass_fds: Sequence[int] = (),
) -> subprocess.CompletedProcess[Any]:
    """Run ``args`` like ``subprocess.run``, in its own process group.

    A timeout, an interrupt or any other error kills the whole group, so a compiler
    below ``make`` cannot outlive the limit. ``subprocess.run`` kills only the direct
    child.
    """
    if capture_output:
        stdout = stderr = subprocess.PIPE
    with subprocess.Popen(
        args,
        start_new_session=True,
        text=text,
        cwd=cwd,
        env=env,
        stdout=stdout,
        stderr=stderr,
        pass_fds=pass_fds,
    ) as process:
        try:
            out, err = process.communicate(timeout=timeout)
        except BaseException:
            stop_group(process.pid)
            raise
    result = subprocess.CompletedProcess(args, process.returncode, out, err)
    if check:
        result.check_returncode()
    return result
