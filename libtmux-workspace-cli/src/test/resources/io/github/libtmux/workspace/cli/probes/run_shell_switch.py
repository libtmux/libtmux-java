"""Check that a `run-shell` style `load` switches the session's most recent client.

ProcessTest runs this with the launcher, the workspace file, the tmux socket, the
tmux binary and a file for the command's output. A key binding's `run-shell` sets
TMUX but never TMUX_PANE and gives the command no terminal, which is exactly the
environment the load is started in here, while a real client sits on `keeper`.
"""

from __future__ import annotations

import contextlib
import fcntl
import os
import pty
import subprocess
import sys
import termios
import time
from pathlib import Path

from pty_helper import wait_pty


def main() -> None:
    launcher, source, socket, tmux, destination = sys.argv[1:]
    prefix = [tmux, "-S", socket]
    master, slave = pty.openpty()
    os.set_blocking(master, False)

    # Runs in the child before exec, so the pty becomes its controlling terminal.
    def terminal() -> None:
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    env_client = dict(os.environ, TERM="xterm", LIBTMUX_TEST_TMUX=tmux)
    env_client.pop("TMUX", None)
    env_client.pop("TMUX_PANE", None)
    client = subprocess.Popen(
        [*prefix, "attach-session", "-t", "keeper"],
        stdin=slave,
        stdout=slave,
        stderr=slave,
        env=env_client,
        preexec_fn=terminal,  # noqa: PLW1509
    )

    def sessions() -> dict[str, str]:
        with contextlib.suppress(OSError):
            os.read(master, 65536)
        rows = subprocess.check_output(
            [*prefix, "list-clients", "-F", "#{client_name} #{session_name}"], text=True
        )
        return dict(row.rsplit(" ", 1) for row in rows.splitlines() if row)

    try:
        until = time.monotonic() + 20
        state = sessions()
        while not state and time.monotonic() < until:
            time.sleep(0.025)
            state = sessions()
        assert len(state) == 1, state
        pid = subprocess.check_output([*prefix, "display-message", "-p", "#{pid}"], text=True)
        # Exactly what run-shell hands its command: TMUX resolves, TMUX_PANE and the
        # terminal do not.
        env = dict(os.environ, TMUX=socket + "," + pid.strip() + ",0", LIBTMUX_TEST_TMUX=tmux)
        env.pop("TMUX_PANE", None)
        with Path(destination).open("wb") as output:
            runshell = subprocess.Popen(
                [launcher, "load", source, "-S", socket],
                stdin=subprocess.DEVNULL,
                stdout=output,
                stderr=output,
                env=env,
            )
            code = runshell.wait(timeout=20)
        until = time.monotonic() + 20
        switched = False
        while time.monotonic() < until:
            state = sessions()
            if state and next(iter(state.values())) != "keeper":
                switched = True
                break
            time.sleep(0.025)
        if not (code == 0 and switched):
            print(f"code={code} switched={switched} state={state}", file=sys.stderr)
        sys.exit(0 if code == 0 and switched else 1)
    finally:
        subprocess.run([*prefix, "kill-server"], capture_output=True, check=False)
        if client.poll() is None:
            wait_pty(client, master, 2)
        os.close(slave)
        os.close(master)


if __name__ == "__main__":
    main()
