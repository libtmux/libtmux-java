"""Tests for the driver that gives NativeClient's tmux client a terminal.

The driver is a test resource of `integration-tests`; these run it against
Python programs on a real pseudo-terminal, so they start no tmux.
"""

import os
import subprocess
import sys
from pathlib import Path

DRIVER = (
    Path(__file__).parents[3]
    / "integration-tests/src/test/resources/io/github/libtmux/it/ordinary_client.py"
)
HELPER_DIRECTORY = Path(__file__).parents[1]


def start(*command: str) -> subprocess.Popen[bytes]:
    """Start the driver on a command, with `pty_helper` importable as it is beside the driver."""
    environment = dict(os.environ, PYTHONPATH=str(HELPER_DIRECTORY))
    return subprocess.Popen(
        [sys.executable, str(DRIVER), *command],
        stdin=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=environment,
    )


def test_input_reaches_the_command_and_a_failure_shows_what_it_drew() -> None:
    """The command reads a line from its terminal; its exit status 4 makes the driver print the trace."""
    with start(sys.executable, "-c", "print('got:' + input()); raise SystemExit(4)") as driver:
        assert driver.stdin is not None
        assert driver.stderr is not None
        driver.stdin.write(b"ping\n")
        driver.stdin.flush()
        assert driver.wait(timeout=10) == 0
        trace = driver.stderr.read()
    assert b"got:ping" in trace


def test_a_command_that_succeeds_leaves_standard_error_empty() -> None:
    """Only a failure prints the trace."""
    with start(sys.executable, "-c", "print('drawn')") as driver:
        assert driver.stderr is not None
        assert driver.wait(timeout=10) == 0
        assert driver.stderr.read() == b""


def test_closing_input_ends_a_command_that_would_not_exit() -> None:
    """NativeClient closes the driver's input to stop the client; the command is terminated, not failed."""
    with start(sys.executable, "-c", "import time; time.sleep(60)") as driver:
        assert driver.stdin is not None
        assert driver.stderr is not None
        driver.stdin.close()
        assert driver.wait(timeout=10) == 0
        assert driver.stderr.read() == b""
