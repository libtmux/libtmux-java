"""Read from and wait on a child that writes to a pseudo-terminal.

The Java tests drive terminal programs with the two functions below. The
programs `ProcessTest` runs import this module. `NativeClient` puts the text of
this file ahead of its program, so the imports below are the ones that program
relies on.
"""

import errno
import os
import select
import subprocess
import time


def drain_pty(fd: int) -> bytes:
    """Return everything ready on a pty master, without waiting for more.

    Reading a master whose slave side is closed fails with `EIO`, which means
    the child is gone and is not an error.
    """
    chunks = []
    while select.select([fd], [], [], 0)[0]:
        try:
            chunk = os.read(fd, 65536)
        except OSError as error:
            if error.errno == errno.EIO:
                break
            raise
        if not chunk:
            break
        chunks.append(chunk)
    return b"".join(chunks)


def wait_pty(child: subprocess.Popen[bytes], master: int, timeout: float) -> int:
    """Wait for a child to exit while draining its pty, and return its exit status.

    Raises `subprocess.TimeoutExpired` when the child outlives `timeout` seconds.
    A child that fills the pty buffer blocks until something reads it, which is
    why waiting alone would hang.
    """
    until = time.monotonic() + timeout
    while child.poll() is None:
        drain_pty(master)
        remaining = until - time.monotonic()
        if remaining <= 0:
            raise subprocess.TimeoutExpired(child.args, timeout)
        select.select([master], [], [], min(remaining, 0.025))
    drain_pty(master)
    return child.returncode
