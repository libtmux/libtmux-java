"""Exit 0 when another process holds the POSIX record lock on a file, 1 when it is free.

SwapServiceTest runs this with the swap lock's path while the Java code holds the
lock, to show that a client outside the JVM cannot take it.
"""

import fcntl
import os
import sys


def main() -> None:
    handle = os.fdopen(os.open(sys.argv[1], os.O_RDWR), "r+")
    try:
        fcntl.lockf(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        sys.exit(0)
    sys.exit(1)


if __name__ == "__main__":
    main()
