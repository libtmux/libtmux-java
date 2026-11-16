"""Run `load` under a pty and capture what it draws on stderr.

ProcessTest runs this with the launcher, the workspace file, the tmux socket, the
files to write stdout, stderr and the terminal capture to, then four flags: whether
stderr is the terminal, whether progress is disabled, how many panel lines to draw,
and whether to interrupt the load once the panel has drawn.
"""

from __future__ import annotations

import fcntl
import os
import pty
import select
import signal
import struct
import subprocess
import sys
import termios
import time
from pathlib import Path

from pty_helper import drain_pty


def main() -> None:
    (
        launcher,
        source,
        socket,
        output,
        error,
        capture,
        terminal_error,
        disabled,
        panel_lines,
        cancelled,
    ) = sys.argv[1:]
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 100, 0, 0))

    # Runs in the child before exec, so the pty becomes its controlling terminal.
    def terminal() -> None:
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    args = [
        launcher,
        "load",
        source,
        "-d",
        "-S",
        socket,
        "-f",
        "/dev/null",
        "--progress-format",
        "PROGRESS_{session}_{window_total}_{session_pane_total}",
        "--progress-lines",
        panel_lines,
    ]
    if disabled == "true":
        args.append("--no-progress")
    chunks: list[bytes] = []
    sent = False
    with Path(output).open("wb") as out, Path(error).open("wb") as err:
        child = subprocess.Popen(
            args,
            stdin=subprocess.DEVNULL,
            stdout=out,
            stderr=slave if terminal_error == "true" else err,
            preexec_fn=terminal,  # noqa: PLW1509
            pass_fds=(slave,),
        )
        try:
            until = time.monotonic() + 20
            while child.poll() is None and time.monotonic() < until:
                if select.select([master], [], [], 0.025)[0]:
                    chunks.append(drain_pty(master))
                if (
                    cancelled == "true"
                    and not sent
                    and b"".join(chunks).count(b"PROGRESS_progress_1_2") >= 3
                ):
                    child.send_signal(signal.SIGINT)
                    sent = True
            assert child.wait(timeout=5) == (130 if cancelled == "true" else 0)
            chunks.append(drain_pty(master))
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()
            os.close(slave)
            os.close(master)
    Path(capture).write_bytes(b"".join(chunks))


if __name__ == "__main__":
    main()
