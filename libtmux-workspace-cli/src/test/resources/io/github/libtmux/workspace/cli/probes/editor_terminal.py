"""Run `edit` with stdout redirected and stdin and stderr on a pty.

ProcessTest runs this with the launcher, the workspace file and the file to send
the command's stdout to. The editor is a shell that succeeds only when its own
stdin and stdout are terminals, so a pass shows the editor got the controlling
terminal even though the JSON result went to a file.
"""

from __future__ import annotations

import fcntl
import os
import pty
import subprocess
import sys
import termios
from pathlib import Path

from pty_helper import wait_pty


def main() -> None:
    master, slave = pty.openpty()

    # Runs in the child before exec, so the pty becomes its controlling terminal.
    def terminal() -> None:
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    env = dict(os.environ, EDITOR="/bin/sh -c '[ -t 0 ] && [ -t 1 ] && printf EDITOR_TTY' editor")
    with Path(sys.argv[3]).open("wb") as output:
        child = subprocess.Popen(
            [sys.argv[1], "edit", sys.argv[2], "--json"],
            stdin=slave,
            stdout=output,
            stderr=slave,
            env=env,
            preexec_fn=terminal,  # noqa: PLW1509
        )
        code = wait_pty(child, master, 20)
    os.close(slave)
    os.close(master)
    sys.exit(code)


if __name__ == "__main__":
    main()
