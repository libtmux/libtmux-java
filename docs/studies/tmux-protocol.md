# tmux protocol and command-queue study

This study separates the released tmux 3.7b command-line contract from
implementation observations at the same release. Public Java behavior relies on
the former. Internal source explains edge cases and supplies regression oracles;
it is not treated as a stable tmux API.

## Released CLI contracts

| Area              | tmux 3.7b contract                                                                                                                                                                    | Java consequence                                                                                                                      | Released source                                                                                                                                                  |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Process result    | The client exits with the server-provided exit value; control clients emit `%exit` before returning.                                                                                  | Preserve the real exit code and stdout/stderr channels in `CommandResult`; do not infer success from empty output.                    | [client exit path](https://github.com/tmux/tmux/blob/3.7b/client.c#L400-L445)                                                                                    |
| Socket selection  | `-L` selects a name below tmux's private per-user directory; `-S` supplies a full path and overrides `-L`. The socket directory must not be accessible by other users.                | Model default, named, and path endpoints as distinct values; emit one endpoint form; preserve exact argv boundaries.                  | [`-L` and `-S`](https://github.com/tmux/tmux/blob/3.7b/tmux.1#L161-L209)                                                                                         |
| Command sequences | Newline or semicolon terminates a command. Semicolon-separated commands form one sequence, and an error prevents later commands in that sequence from running.                        | A group is an execution dependency chain, not merely a list whose size equals the lexical separator count.                            | [parsing syntax](https://github.com/tmux/tmux/blob/3.7b/tmux.1#L518-L590)                                                                                        |
| Target resolution | Session names use ID, exact, prefix, then glob resolution; ambiguous matches fail. Window and pane targets have their own ordered rules, stable sigils, and qualification behavior.   | Preserve `$`, `@`, and `%`; prefer fully qualified IDs for internal calls; differential-test ambiguity and current-target fallback.   | [target rules](https://github.com/tmux/tmux/blob/3.7b/tmux.1#L722-L970)                                                                                          |
| Linked windows    | One window may be linked into multiple sessions.                                                                                                                                      | Server-wide window listings and snapshots retain winlink occurrences while point lookup resolves canonical identity.                  | [clients and sessions](https://github.com/tmux/tmux/blob/3.7b/tmux.1#L1048-L1063)                                                                                |
| Formats           | Formats expose typed string fields such as `session_attached`, stable IDs, socket paths, and linked-session counts. Numeric and comparison operators are part of the format language. | Hydrate from documented format tokens, version-gate tokens, distinguish unsupported from empty, and parse attached count numerically. | [numeric operators](https://github.com/tmux/tmux/blob/3.7b/tmux.1#L6192-L6238) and [format variables](https://github.com/tmux/tmux/blob/3.7b/tmux.1#L6460-L6485) |
| Control blocks    | Each command sent as a newline-terminated control input produces a `%begin` block ending with `%end` or `%error`; notifications do not occur inside a block.                          | Correlate blocks by protocol guards and command number, not terminal text or timing. Treat notifications as out-of-band events.       | [control mode](https://github.com/tmux/tmux/blob/3.7b/tmux.1#L8113-L8155)                                                                                        |

The documented phrase “each command” applies to submitted control input. A
semicolon sequence is itself governed by the earlier abort contract, so it does
not promise one block for every lexical command after an error.

## Internal observations at 3.7b

| Observation                                                                                                     | Consequence                                                                                                                 | Pinned implementation source                                                                                                                                          |
| --------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Queue items carry a group number, and the failure path removes every subsequent item in the same nonzero group. | The transport must complete undispatched logical operations as `SKIPPED`; waiting for absent guards is a protocol deadlock. | [`cmdq_remove_group`](https://github.com/tmux/tmux/blob/3.7b/cmd-queue.c#L468-L483) and [error removal](https://github.com/tmux/tmux/blob/3.7b/cmd-queue.c#L730-L798) |
| A fired command emits a begin guard before lookup/dispatch and emits exactly one end or error guard afterward.  | Blocks identify commands that actually reached `cmdq_fire_command`; removed group members never reach the guard calls.      | [`cmdq_fire_command`](https://github.com/tmux/tmux/blob/3.7b/cmd-queue.c#L595-L680)                                                                                   |
| The client restores blocking streams, writes its control exit marker, flushes, and returns `client_exitval`.    | Shutdown handling must drain through the terminal marker and still retain the process exit status.                          | [client shutdown](https://github.com/tmux/tmux/blob/3.7b/client.c#L400-L445)                                                                                          |

These observations may change in later tmux releases. The Java contract is
therefore stated in outcome terms—complete, failed, skipped, or unknown—and is
verified against every supported tmux binary.

## Grouped-control probe

A disposable real-tmux fixture submitted the same three-command sequence with
the failing `select-pane -t =missing` command in the first, middle, and last
position. Each fixture used a unique `tmux -L` server and the Task 1 locked
owned-process ledger writer before the process could escape the harness. The
initial control attach response was excluded from the group-block count.

| Error position | Logical operations |        Group response blocks | Outcomes required from a future engine | Process exit |
| -------------- | -----------------: | ---------------------------: | -------------------------------------- | -----------: |
| first          |                  3 |                 1 (`%error`) | `FAILED`, `SKIPPED`, `SKIPPED`         |            1 |
| middle         |                  3 |         2 (`%end`, `%error`) | `COMPLETE`, `FAILED`, `SKIPPED`        |            1 |
| last           |                  3 | 3 (`%end`, `%end`, `%error`) | `COMPLETE`, `COMPLETE`, `FAILED`       |            1 |

The transport-critical invariant is therefore empirical as well as
source-backed: lexical separator counts do not prove how many response blocks
will arrive. After the probes, the owned-process verifier found no live owned
processes.

## Contract gates

- Run first-, middle-, and last-error fixtures for every supported tmux version.
- Require a terminal outcome for every logical operation without inventing a
  control block for skipped work.
- Fail boundedly as `UNKNOWN` on lost or malformed guards; never wait forever.
- Preserve literal semicolon arguments separately from explicit command
  separators.
- Differential-test ambiguous targets, stable sigils, linked windows, socket
  precedence, format absence, and process exit behavior against tmux itself.
