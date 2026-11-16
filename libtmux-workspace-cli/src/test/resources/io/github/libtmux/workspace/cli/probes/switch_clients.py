"""Check that an attached `load` switches only the client that ran it.

ProcessTest runs this with the launcher, the workspace file, the tmux socket, the
tmux binary and a scratch directory. Two clients attach to two sessions; one types
the `load` command, with stdio redirected, into its shell. Only that client may end
up on the loaded session.
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
    clients: list[tuple[int, int, str, subprocess.Popen[bytes]]] = []
    try:
        for name in ("origin", "other"):
            master, slave = pty.openpty()
            os.set_blocking(master, False)

            # Runs in the child before exec, so the pty becomes its controlling terminal.
            def terminal(slave: int = slave) -> None:
                os.setsid()
                fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

            child = subprocess.Popen(
                [*prefix, "attach-session", "-t", name],
                stdin=slave,
                stdout=slave,
                stderr=slave,
                env=env,
                preexec_fn=terminal,  # noqa: PLW1509
            )
            clients.append((master, slave, os.ttyname(slave), child))

        def attached() -> dict[str, str]:
            for master, _slave, _tty, _child in clients:
                with contextlib.suppress(OSError):
                    os.read(master, 65536)
            rows = subprocess.check_output(
                [*prefix, "list-clients", "-F", "#{client_name} #{session_name}"], text=True
            )
            return dict(row.rsplit(" ", 1) for row in rows.splitlines())

        until = time.monotonic() + 10
        while len(attached()) != 2 and time.monotonic() < until:
            time.sleep(0.025)
        assert len(attached()) == 2
        pane = subprocess.check_output(
            [*prefix, "display-message", "-p", "-t", "origin:", "#{pane_id}"], text=True
        ).strip()
        until = time.monotonic() + 10
        while time.monotonic() < until:
            attached()
            screen = subprocess.check_output([*prefix, "capture-pane", "-p", "-t", pane], text=True)
            if "workspace-ready>" in screen:
                break
            time.sleep(0.025)
        assert "workspace-ready>" in screen, screen
        status = Path(scratch) / "exit-status"
        command = shlex.join(
            ["env", "LIBTMUX_TEST_TMUX=" + tmux, launcher, "load", source, "-S", socket]
        )
        command += " </dev/null >" + shlex.quote(str(Path(scratch) / "stdout"))
        command += " 2>" + shlex.quote(str(Path(scratch) / "stderr"))
        command += "; printf %s $? >" + shlex.quote(str(status))
        subprocess.run([*prefix, "send-keys", "-t", pane, "-l", command], check=True)
        subprocess.run([*prefix, "send-keys", "-t", pane, "Enter"], check=True)
        until = time.monotonic() + 20
        while not status.exists() and time.monotonic() < until:
            attached()
            time.sleep(0.025)
        if not status.exists():
            print(
                subprocess.check_output(
                    [
                        *prefix,
                        "display-message",
                        "-p",
                        "-t",
                        pane,
                        "#{pane_current_command}:#{pane_dead}:#{pane_in_mode}:#{cursor_x},#{cursor_y}",
                    ],
                    text=True,
                ),
                file=sys.stderr,
            )
            print(
                subprocess.check_output([*prefix, "capture-pane", "-p", "-t", pane], text=True),
                file=sys.stderr,
            )
            for name in ("stdout", "stderr"):
                path = Path(scratch) / name
                if path.exists():
                    print(name, path.read_text(), file=sys.stderr)
        assert status.read_text() == "0", (Path(scratch) / "stderr").read_text()
        state = attached()
        assert state[clients[0][2]] == "switched", state
        assert state[clients[1][2]] == "other", state
    finally:
        subprocess.run([*prefix, "kill-server"], capture_output=True, check=False)
        for master, slave, _tty, child in clients:
            if child.poll() is None:
                wait_pty(child, master, 2)
            os.close(slave)
            os.close(master)


if __name__ == "__main__":
    main()
