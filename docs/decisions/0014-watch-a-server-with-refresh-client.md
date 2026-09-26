# 0014. Watch a server with refresh-client -B, not by polling

Status: Accepted

## Context

`refresh-client -B <name>:<what>:<format>` registers a format tmux re-expands
on its own one-second timer, writing a `%subscription-changed` notification
only when the expanded value differs from what it last sent
(`control.c:857`, `control.c:1051`). A client does nothing between changes;
the comparison happens inside the server. Measured against a real attach,
rename, and window creation and deletion, every actual change produced exactly
one notification and an idle interval produced none.

Polling `capture-pane` works but is strictly worse as a change detector: a
process per pane per interval, unable to distinguish "no change" from "not
looked at yet". Tapping the pty with `pipe-pane` was rejected outright: tmux
keeps one `pipe_fd` per pane, so starting an internal pipe silently steals it
from whatever logging a person started with `pipe-pane`, and a pty tap carries
raw bytes rather than the rendered grid, firing on the echo of a command
someone just sent rather than on the state that command produced. Latency was
not the deciding factor either way — the sibling Python port measured a
control-mode wake at roughly 18 ms against 21 ms for 50 ms polling — the
decisive property is that nothing runs while nothing happens.

Subscribing requires an attached client, and an attached client is a real
change to the server: `#{session_attached}` becomes true, and anything asking
whether a person is looking would say yes. The subscribing client identifies
itself by asking for its own `#{client_name}` and is excluded from what this
library's own client listing reports.

## Decision

Watch a server for change by registering `refresh-client -B` subscriptions
and reading `%subscription-changed` notifications (`ControlClient.subscribeEvents`,
`ServerMirror`), rather than polling or tapping a pane's pty. Quote every
subscription's format argument, since `#` in an unquoted argument starts a
comment to tmux's own control-mode lexer before the format expander ever sees
it.

## Consequences

A `ServerMirror` costs one always-on control connection and reflects a change
within tmux's own one-second comparison timer, not sooner. Anything watched
this way is implicitly reported as having an attached client, which is a
correct side effect of how the mechanism works rather than a bug to suppress.
