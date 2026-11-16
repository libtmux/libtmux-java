"""Run a command on a pseudo-terminal and forward standard input to it.

NativeClient runs this with a tmux endpoint and an `attach-session` command as
the arguments. The command sees a controlling terminal, which makes it an
ordinary tmux client. Whatever NativeClient writes to this program's standard
input goes to that terminal. The first 64 KiB the command draws is kept and
written to standard error when the command fails, which is where NativeClient
looks for the reason.

It imports `pty_helper`, which the `processTestResources` task copies beside it.
"""

from __future__ import annotations

import fcntl
import os
import pty
import select
import signal
import subprocess
import sys
import termios

from pty_helper import drain_pty, wait_pty

TRACE_LIMIT = 65536
POLL_SECONDS = 0.025
STOP_SECONDS = 2


def main() -> None:
    master, slave = pty.openpty()
    os.set_blocking(master, False)

    # Runs in the child before exec, so the pty becomes its controlling terminal.
    def terminal() -> None:
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    child: subprocess.Popen[bytes] | None = None
    slave_open = True
    trace = bytearray()
    try:
        child = subprocess.Popen(
            sys.argv[1:],
            stdin=slave,
            stdout=slave,
            stderr=slave,
            env=dict(os.environ, TERM="xterm"),
            preexec_fn=terminal,  # noqa: PLW1509
        )
        os.close(slave)
        slave_open = False
        while child.poll() is None:
            chunk = drain_pty(master)
            trace.extend(chunk[: max(0, TRACE_LIMIT - len(trace))])
            if 0 in select.select([0, master], [], [], POLL_SECONDS)[0]:
                data = os.read(0, 65536)
                if not data:
                    break
                os.write(master, data)
    finally:
        if child is not None:
            if child.poll() is None:
                child.terminate()
            try:
                wait_pty(child, master, STOP_SECONDS)
            except subprocess.TimeoutExpired:
                child.kill()
                wait_pty(child, master, STOP_SECONDS)
            if child.returncode not in (0, -signal.SIGTERM):
                sys.stderr.buffer.write(trace)
        if slave_open:
            os.close(slave)
        os.close(master)


if __name__ == "__main__":
    main()
