"""Attach a human `load` to a pty, or to redirected files, and measure what tmux draws.

ProcessTest runs this with the launcher, the workspace file, the tmux socket, the
tmux binary, a file for the command's output and `true` or `false` for whether the
command's stdio is redirected. Redirected stdio carries no terminal, so tmux itself
must refuse to attach. A real terminal must both connect and render: a few hundred
bytes written to the client is the blank-screen bug.
"""

from __future__ import annotations

import fcntl
import os
import pty
import subprocess
import sys
import termios
import time
from pathlib import Path

from pty_helper import drain_pty, wait_pty


def main() -> None:
    launcher, source, socket, tmux, destination, redirected_flag = sys.argv[1:]
    redirected = redirected_flag == "true"
    master, slave = pty.openpty()

    # Runs in the child before exec, so the pty becomes its controlling terminal.
    def terminal() -> None:
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    env = dict(os.environ, TERM="xterm", LIBTMUX_TEST_TMUX=tmux)
    env.pop("TMUX", None)
    env.pop("TMUX_PANE", None)
    connected = False
    written = 0
    with Path(destination).open("wb") as output:
        child = subprocess.Popen(
            [launcher, "load", source, "-S", socket, "-f", "/dev/null"],
            stdin=subprocess.DEVNULL if redirected else slave,
            stdout=output,
            stderr=output if redirected else slave,
            env=env,
            preexec_fn=terminal,  # noqa: PLW1509
            pass_fds=(slave,),
        )
        try:
            until = time.monotonic() + 20
            name = ""
            while child.poll() is None and time.monotonic() < until:
                drain_pty(master)
                clients = subprocess.run(
                    [tmux, "-S", socket, "list-clients", "-F", "#{client_name}"],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                if clients.returncode == 0 and clients.stdout.strip():
                    connected = True
                    name = clients.stdout.strip().splitlines()[0]
                    break
                time.sleep(0.025)
            if connected:
                # Give a working client time to draw before measuring what it wrote.
                time.sleep(0.3)
                drain_pty(master)
                report = subprocess.run(
                    [tmux, "-S", socket, "display-message", "-p", "-t", name, "#{client_written}"],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                written = int(report.stdout.strip() or "0")
                subprocess.run([tmux, "-S", socket, "detach-client", "-t", name], check=True)
            code = wait_pty(child, master, 10)
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()
    os.close(slave)
    os.close(master)
    ok = (
        (not connected and code != 0) if redirected else (connected and code == 0 and written > 450)
    )
    if not ok:
        print(f"connected={connected} code={code} written={written}", file=sys.stderr)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
