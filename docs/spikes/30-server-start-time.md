# A server is its pid and when it started

## Verdict

A capture names its server by `#{pid}` and `#{start_time}` together, and every
fence compares both: handle commands, the capture group, and a control attach.

A pid alone is reusable. A tmux restarted in a container, where it is one of the
first processes, is often given its old pid back, and a handle from before the
restart passed a pid-only fence. The version, which the capture fence used to add,
does not tell a restarted server of the same release apart.

## Measured

Every build from 3.2a to master: start a server, read the fields, kill it, wait
over a second, start another on the same socket, read them again.

| tmux | `#{start_time}` | after a restart | in a `-f` filter |
| --- | --- | --- | --- |
| 3.2a, 3.3, 3.3a, 3.4, 3.5, 3.5a, 3.6, 3.6a, 3.6b, 3.7, 3.7a, 3.7b, 3.7c, 3.8-rc, master | seconds since the epoch | changed, except once | evaluates |

The exception was 3.7c: the second server reported the same second as the first,
on a machine whose wall clock drifts. That is why the start time is compared with
the pid rather than alone: a collision needs the same pid in the same second.

## What checks it

`ServerTest.snapshotRefusesAServerThatReusedThePid`,
`HandleTest.aHandleIsRefusedByAServerStartedLaterOnItsPid`, and
`PaneInputTest.aHoldDoesNotReachTheSamePaneIdOnARestartedServer` use a double that
keeps the pid and moves the start time. `ServerControlIntegrationTest.controlDetachesFromAServerStartedAtAnotherTime`
attaches to real tmux, on every lane, with the live pid and version and a start
time one second off. Removing the start time from the fence fails each of them.
