"""Check each line bash completes from the generated script, in a real pty.

ProcessTest runs this with the work directory and the completion script. Bash runs
under a pseudo-terminal so readline, not a stub, does the completing; a key binding
copies each edited line into a file, where the checks read it back.
"""

from __future__ import annotations

import contextlib
import errno
import os
import pty
import select
import shlex
import shutil
import signal
import sys
import time
from collections.abc import Callable
from pathlib import Path

CASES = [
    ("tmux-workspace imp", "tmux-workspace import "),
    ("tmux-workspace import team", "tmux-workspace import teamocil "),
    ("tmux-workspace --color al", "tmux-workspace --color always "),
    ("tmux-workspace --color n", "tmux-workspace --color never "),
    ("tmux-workspace --log-level de", "tmux-workspace --log-level debug "),
    ("tmux-workspace --generate ba", "tmux-workspace --generate bash "),
    ("tmux-workspace load --color al", "tmux-workspace load --color always "),
    ("tmux-workspace load --log-level de", "tmux-workspace load --log-level debug "),
    ("tmux-workspace freeze -f j", "tmux-workspace freeze -f json "),
    (
        "tmux-workspace freeze --workspace-format y",
        "tmux-workspace freeze --workspace-format yaml ",
    ),
    ("tmux-workspace --color b", "tmux-workspace --color b"),
    ("tmux-workspace --generate al", "tmux-workspace --generate al"),
]


def main() -> None:
    root, completion = sys.argv[1:]
    buffers, ready = root + "/buffers", root + "/ready"
    setup = "source " + shlex.quote(completion) + "; bind 'set keyseq-timeout 1'; "
    setup += """_capture() { printf '%s\\0' "$READLINE_LINE" >> """ + shlex.quote(buffers)
    setup += """; READLINE_LINE=; READLINE_POINT=0; }; bind -x '"\\C-x":_capture'; """
    setup += "PS1='prompt> '; : > " + shlex.quote(ready)
    bash = shutil.which("bash")
    if bash is None:
        sys.exit("bash is not on PATH")
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(root)
        os.execve(
            bash,
            ["bash", "--noprofile", "--norc"],
            dict(os.environ, TERM="xterm", HISTFILE="/dev/null"),
        )
    trace = bytearray()
    joined = False
    exit_status = 0

    def until(predicate: Callable[[], object]) -> None:
        end = time.monotonic() + 4
        while time.monotonic() < end:
            if predicate():
                return
            if select.select([fd], [], [], 0.01)[0]:
                try:
                    trace.extend(os.read(fd, 65536))
                except OSError as error:
                    if error.errno != errno.EIO:
                        raise
                    time.sleep(0.01)
        raise AssertionError("completion observation timed out: " + repr(trace[-2000:]))

    def values() -> list[bytes]:
        try:
            return Path(buffers).read_bytes().split(b"\0")[:-1]
        except FileNotFoundError:
            return []

    def exited() -> bool:
        nonlocal joined, exit_status
        child, current = os.waitpid(pid, os.WNOHANG)
        if child:
            joined, exit_status = True, current
        return joined

    try:
        os.write(fd, (setup + "\n").encode())
        until(Path(ready).exists)
        for i, (line, expected) in enumerate(CASES):
            os.write(fd, (line + "\t\x18").encode())
            until(lambda i=i: len(values()) > i)
            actual = values()[i].decode()
            assert actual == expected, (line, actual, expected)
        os.write(fd, b"exit\n")
        until(exited)
        assert os.waitstatus_to_exitcode(exit_status) == 0
    finally:
        if not joined:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
        os.close(fd)
        Path(root + "/terminal.raw").write_bytes(trace)


if __name__ == "__main__":
    main()
