# reap-stale-servers

Lists, and with `--reap` ends, the tmux servers this port abandoned. A maintainer
runs it when a real-tmux test fails intermittently, before blaming the change
under test. [CONTRIBUTING](../../.github/CONTRIBUTING.md#running-the-tests)
explains why leftover servers cause that.

```console
$ uv run scripts/reap-stale-servers/reap_stale_servers.py
```

```console
$ uv run scripts/reap-stale-servers/reap_stale_servers.py --reap
```

The first command lists each server it would end. The second ends them with
`tmux kill-server` while the socket exists, and with `SIGTERM` when the system's
temporary-file cleaner has removed it, which leaves the server reachable only
through its own command line. A server that does not answer `kill-server` in
time is reported and signalled too.

The test suite reaps abandoned servers for itself. This is for a server whose
test JVM was killed, which nothing reaps until the next run.

`tmux kill-server` gets 5 s, and a server that does not answer is signalled.

It only ever touches sockets under `/tmp/libtmux-java-test` and
`/tmp/libtmux-java-dev`. Servers under the roots of other libtmux ports are
counted and reported, never ended.

The tests read a fake `/proc` and signal a stand-in process named `tmux`, so they
start no real server:

```console
$ uv run pytest scripts/reap-stale-servers
```
