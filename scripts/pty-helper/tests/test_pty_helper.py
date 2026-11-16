"""Tests for the pty helper, against a real pseudo-terminal and real children."""

import os
import subprocess
import sys

import pytest

import pty_helper


def test_drain_returns_what_is_ready_and_nothing_when_idle() -> None:
    """A pty with nothing written returns at once, empty."""
    master, slave = os.openpty()
    try:
        assert pty_helper.drain_pty(master) == b""
        os.write(slave, b"first")
        os.write(slave, b"second")
        assert pty_helper.drain_pty(master) == b"firstsecond"
        assert pty_helper.drain_pty(master) == b""
    finally:
        os.close(master)
        os.close(slave)


def test_drain_treats_a_closed_slave_as_the_end_of_output() -> None:
    """The EIO of a master whose child is gone ends the read; it does not raise."""
    master, slave = os.openpty()
    try:
        os.write(slave, b"last words")
        os.close(slave)
        assert pty_helper.drain_pty(master) == b"last words"
        assert pty_helper.drain_pty(master) == b""
    finally:
        os.close(master)


def test_wait_returns_the_exit_status_of_a_child_that_fills_the_pty() -> None:
    """A child that writes more than the pty buffer holds still exits, because the wait reads."""
    master, slave = os.openpty()
    try:
        child = subprocess.Popen(
            [sys.executable, "-c", "import sys; sys.stdout.write('x' * 300000); sys.exit(3)"],
            stdout=slave,
        )
        os.close(slave)
        assert pty_helper.wait_pty(child, master, 20) == 3
    finally:
        os.close(master)


def test_wait_raises_when_the_child_outlives_the_timeout() -> None:
    """A child that never exits fails the wait instead of hanging it."""
    master, slave = os.openpty()
    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], stdout=slave)
    try:
        os.close(slave)
        with pytest.raises(subprocess.TimeoutExpired):
            pty_helper.wait_pty(child, master, 0.2)
    finally:
        child.kill()
        child.wait()
        os.close(master)
