#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""End tmux servers this port abandoned, and report what belongs to somebody else.

    uv run scripts/reap-stale-servers/reap_stale_servers.py          # say what would be reaped
    uv run scripts/reap-stale-servers/reap_stale_servers.py --reap   # reap them

The suite reaps for itself, but a server whose test JVM was killed and whose
socket the temporary-file cleaner has since removed is reachable only through
its own argv, and nothing reaps it until the next run. This script reaps it on
demand.

Only sockets under this port's roots are ever touched. Sibling libtmux ports run
on this machine and their leftover servers are reported, never ended;
`.github/CONTRIBUTING.md` explains why.
"""

import argparse
import contextlib
import itertools
import os
import signal
import subprocess
import sys
from collections.abc import Callable, Sequence
from pathlib import Path
from typing import NamedTuple

# Importing a shared module must not leave a __pycache__ in the checkout.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

import bounded

ROOTS = ("/tmp/libtmux-java-test/", "/tmp/libtmux-java-dev/")
# How long `tmux kill-server` gets. A server that does not answer is wedged, and a
# signal is the only way left to end it.
KILL_SERVER_SECONDS = 5.0


class Server(NamedTuple):
    """A tmux process and the socket named on its command line."""

    pid: int
    socket: str


def socket_of(arguments: Sequence[str]) -> str:
    """Return the socket a tmux command line names, or an empty string.

    The last `-S <socket>` is the one the server uses. Arguments arrive as the
    separate words of the command line, so a socket path may hold a space.

    >>> socket_of(["tmux", "-S", "/tmp/libtmux-java-test/a/s", "new-session", "-d"])
    '/tmp/libtmux-java-test/a/s'
    >>> socket_of(["tmux", "new-session", "-d"])
    ''
    >>> socket_of(["tmux", "-S", "/first", "-S", "/second"])
    '/second'
    >>> socket_of(["tmux", "-S", "/tmp/libtmux-java-test/with space/s"])
    '/tmp/libtmux-java-test/with space/s'
    """
    socket = ""
    for flag, value in itertools.pairwise(arguments):
        if flag == "-S":
            socket = value
    return socket


def is_ours(socket: str) -> bool:
    """Return whether a socket lies under one of this port's roots.

    >>> is_ours("/tmp/libtmux-java-test/run-1/s")
    True
    >>> is_ours("/tmp/libtmux-java-testing/s")
    False
    >>> is_ours("/tmp/libtmux-rs-test/s")
    False
    """
    return socket.startswith(ROOTS)


def find_servers(proc: Path = Path("/proc")) -> list[Server]:
    """Return every process whose name contains tmux and whose command line names a socket."""
    found: list[Server] = []
    for entry in sorted(proc.iterdir(), key=lambda path: path.name):
        if not entry.name.isdigit():
            continue
        try:
            name = (entry / "comm").read_text().strip()
            arguments = (entry / "cmdline").read_bytes().decode(errors="replace").rstrip("\0")
        except OSError:
            continue
        if "tmux" not in name:
            continue
        socket = socket_of(arguments.split("\0"))
        if socket:
            found.append(Server(int(entry.name), socket))
    return found


def kill_server(socket: str) -> bool:
    """Ask the server on a socket to exit, and say whether tmux accepted.

    A server that does not answer within `KILL_SERVER_SECONDS` counts as not
    accepting, so the caller signals it instead.
    """
    try:
        done = bounded.run(
            ["tmux", "-S", socket, "kill-server"],
            capture_output=True,
            timeout=KILL_SERVER_SECONDS,
        )
    except subprocess.TimeoutExpired:
        print(
            f"tmux kill-server on {socket} did not answer within {KILL_SERVER_SECONDS:g} seconds;"
            " signalling it",
            file=sys.stderr,
        )
        return False
    return done.returncode == 0


def terminate(pid: int) -> None:
    """Send SIGTERM to a process that may already be gone."""
    with contextlib.suppress(ProcessLookupError, PermissionError):
        os.kill(pid, signal.SIGTERM)


def reap_one(
    server: Server,
    *,
    stop: Callable[[str], bool] = kill_server,
    kill: Callable[[int], None] = terminate,
) -> None:
    """End one server: `kill-server` while its socket exists, a signal when it does not.

    A server whose socket was unlinked cannot be reached any other way.
    """
    if not (Path(server.socket).is_socket() and stop(server.socket)):
        kill(server.pid)


def report(servers: list[Server], *, reap: bool, out: list[str]) -> None:
    """Append what is done to each of our servers, and the totals, to `out`."""
    ours = [server for server in servers if is_ours(server.socket)]
    theirs = len(servers) - len(ours)
    for server in ours:
        verb = "reaped" if reap else "would reap"
        out.append(f"{verb}  {server.pid}  {server.socket}")
    out.append("")
    out.append(f"ours: {len(ours)}")
    out.append(f"other ports': {theirs}  (not this script's to touch)")
    if not reap and ours:
        out.append("")
        out.append("re-run with --reap to end them")


def main(argv: list[str] | None = None) -> int:
    """Report this port's abandoned servers, and end them with `--reap`."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--reap", action="store_true", help="end the servers instead of listing them"
    )
    options = parser.parse_args(argv)
    servers = find_servers()
    if options.reap:
        for server in servers:
            if is_ours(server.socket):
                reap_one(server)
    lines: list[str] = []
    report(servers, reap=options.reap, out=lines)
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
