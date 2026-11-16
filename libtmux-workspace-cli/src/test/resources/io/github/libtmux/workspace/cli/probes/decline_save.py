"""Decline the save prompt of `convert` on a real terminal.

ProcessTest runs this with the launcher, a workspace file, the path to save to, and
files to record the exit status and the terminal transcript in. The command runs
under a pty, receives "n", and reads until the terminal closes.
"""

from __future__ import annotations

import os
import pty
import sys
from pathlib import Path


def main() -> None:
    launcher, workspace, destination, status, transcript = sys.argv[1:]
    pid, fd = pty.fork()
    if pid == 0:
        os.execve(
            launcher,
            [launcher, "convert", workspace, "--save-to", destination],
            dict(os.environ, TERM="xterm"),
        )
    os.write(fd, b"n\n")
    seen = bytearray()
    while True:
        try:
            chunk = os.read(fd, 65536)
        except OSError:
            break
        if not chunk:
            break
        seen.extend(chunk)
    os.close(fd)
    code = os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1])
    Path(status).write_text(str(code))
    Path(transcript).write_bytes(bytes(seen))


if __name__ == "__main__":
    main()
