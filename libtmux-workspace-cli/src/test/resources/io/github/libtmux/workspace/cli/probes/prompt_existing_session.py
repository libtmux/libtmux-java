"""Answer the existing-session prompt of `load` with "n", from inside tmux.

ProcessTest runs this with the launcher, the workspace file, the tmux socket, the
tmux binary and a scratch directory. A client is attached to `keeper`, whose shell
runs the `load` with neither stream redirected. A session that already exists asks
once whether to attach; "n" must change nothing and never ask the new-session
question as well.
"""

from __future__ import annotations

import contextlib
import fcntl
import os
import pty
import shlex
import subprocess
import sys
import termios
import time
from pathlib import Path

from pty_helper import wait_pty


def main() -> None:
    launcher, source, socket, tmux, scratch = sys.argv[1:]
    env = dict(os.environ, TERM="xterm", LIBTMUX_TEST_TMUX=tmux)
    env.pop("TMUX", None)
    env.pop("TMUX_PANE", None)
    prefix = [tmux, "-S", socket]
    master, slave = pty.openpty()
    os.set_blocking(master, False)

    # Runs in the child before exec, so the pty becomes its controlling terminal.
    def terminal() -> None:
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    child = subprocess.Popen(
        [*prefix, "attach-session", "-t", "keeper"],
        stdin=slave,
        stdout=slave,
        stderr=slave,
        env=env,
        preexec_fn=terminal,  # noqa: PLW1509
    )

    def screen() -> str:
        with contextlib.suppress(OSError):
            os.read(master, 65536)
        return subprocess.check_output([*prefix, "capture-pane", "-p", "-t", "keeper"], text=True)

    def wait_for(needle: str, budget: float) -> str:
        until = time.monotonic() + budget
        text = screen()
        while needle not in text and time.monotonic() < until:
            time.sleep(0.025)
            text = screen()
        return text

    try:
        text = wait_for("workspace-ready>", 10)
        assert "workspace-ready>" in text, text
        status = Path(scratch) / "exit-status"
        # Neither stream is redirected: the prompt (on stderr) must stay visible on the
        # pane to be read and answered by a real keystroke.
        command = shlex.join(["env", "LIBTMUX_TEST_TMUX=" + tmux, launcher, "load", source])
        command += "; printf %s $? >" + shlex.quote(str(status))
        subprocess.run([*prefix, "send-keys", "-t", "keeper", "-l", command], check=True)
        subprocess.run([*prefix, "send-keys", "-t", "keeper", "Enter"], check=True)
        text = wait_for("already running", 10)
        assert "already running" in text, text
        assert "switch (y)" not in text, (
            "the existing-session question must not double as the new-session one"
        )
        subprocess.run([*prefix, "send-keys", "-t", "keeper", "-l", "n"], check=True)
        subprocess.run([*prefix, "send-keys", "-t", "keeper", "Enter"], check=True)
        until = time.monotonic() + 20
        text = screen()
        while not status.exists() and time.monotonic() < until:
            time.sleep(0.025)
            text = screen()
        assert status.exists(), text
        assert status.read_text() == "0", text
        windows = subprocess.check_output(
            [*prefix, "list-windows", "-t", "home"], text=True
        ).splitlines()
        assert len(windows) == 1, windows
        final = subprocess.check_output(
            [*prefix, "list-clients", "-F", "#{session_name}"], text=True
        ).strip()
        assert final == "keeper", final
    finally:
        subprocess.run([*prefix, "kill-server"], capture_output=True, check=False)
        if child.poll() is None:
            wait_pty(child, master, 2)
        os.close(slave)
        os.close(master)


if __name__ == "__main__":
    main()
