"""Interrupt a command whose stdout pipe nobody reads, and expect it to exit.

ProcessTest runs this with the launcher command line as arguments. The pipe is held
full by never reading it, so the command can only exit if its interrupt handling
does not wait on stdout.
"""

from __future__ import annotations

import array
import fcntl
import os
import select
import signal
import subprocess
import sys
import termios
import time


def main() -> None:
    reader, writer = os.pipe()
    child = subprocess.Popen(
        sys.argv[1:], stdout=writer, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL
    )
    try:
        size = array.array("i", [0])
        until = time.monotonic() + 4
        while child.poll() is None and time.monotonic() < until:
            fcntl.ioctl(reader, termios.FIONREAD, size, True)
            if size[0] > 0 and not select.select([], [writer], [], 0)[1]:
                break
            time.sleep(0.01)
        assert size[0] > 0, size[0]
        assert not select.select([], [writer], [], 0)[1], size[0]
        assert child.poll() is None
        child.send_signal(signal.SIGINT)
        # Main's own shutdown hook gives the interrupted run up to 3s to unwind before
        # abandoning it; this margin must clear that, not just the common case.
        assert child.wait(timeout=4) == 130
    finally:
        if child.poll() is None:
            child.kill()
            child.wait()
        os.close(reader)
        os.close(writer)
        if child.stderr is not None:
            child.stderr.close()


if __name__ == "__main__":
    main()
