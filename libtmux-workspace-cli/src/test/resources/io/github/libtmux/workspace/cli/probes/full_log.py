"""Run a command whose file size limit is zero and pass its output through.

ProcessTest runs this with the launcher command line as arguments. A zero RLIMIT_FSIZE
makes every write to a log file fail, which is how a full disk looks to the command.
SIGXFSZ is ignored so the write fails with an error instead of killing the process.
"""

from __future__ import annotations

import resource
import signal
import subprocess
import sys


def limited() -> None:
    signal.signal(signal.SIGXFSZ, signal.SIG_IGN)
    resource.setrlimit(resource.RLIMIT_FSIZE, (0, 0))


def main() -> None:
    child = subprocess.run(sys.argv[1:], capture_output=True, preexec_fn=limited, check=False)
    sys.stdout.buffer.write(child.stdout)
    sys.stderr.buffer.write(child.stderr)
    sys.exit(child.returncode)


if __name__ == "__main__":
    main()
