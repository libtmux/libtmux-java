# Security

## Reporting

Report a vulnerability through [GitHub's private advisory
form](https://github.com/libtmux/libtmux-java/security/advisories/new), not a
public issue.

## What this library does with untrusted input

Worth knowing before deciding whether something is a vulnerability here.

**Commands are never shell-parsed.** Every argument crosses to tmux as its own
`argv` element through `ProcessBuilder`, so no shell interprets it and a
semicolon, quote or backslash in a pane title is inert. `CommandRequestTest`
pins this.

**tmux's own command grammar is not inert.** tmux ends a command at a semicolon
ending any argument, so an argument built from untrusted text can add a command.
`ControlClient.isCommandGroup` is the library's reading of that rule, and
`docs/spikes/21-command-group-boundaries.md` measures it. Treat text you did not
author as data: pass it as a single argument, and do not concatenate it into
one.

**tmux expands formats before any shell runs.** tmux expands `#{...}` and
`#(...)` in many argument positions, and `#(...)` runs a command. The expansion
happens before `/bin/sh` sees the string, so shell quoting does not contain it —
measured on tmux 3.7d, a `#(...)` inside single quotes ran through both
`run-shell` and `pipe-pane`. Every argument this library composes itself passes
through `TmuxFormats.literal`, which doubles `#`. The commands a *caller*
composes cannot be neutralized for them, because expansion there is sometimes
the point: `Pane.pipeTo`, `Window.displayPopup`, `Server.runShell`,
`Server.runShellCapturing`, `Server.ifShell` and `Options.setExpanded` take
caller-authored text, and a value interpolated into one of those needs
`TmuxFormats.literal` applied to it. No MCP tool reaches these positions with
model-supplied text.

**Diagnostics are redacted.** `CommandRequest.toString` reports argument counts
and a timeout, never argument values, because argv carries pane content, socket
paths and whatever a caller sent to a shell. A failure message or log line
should not become the disclosure.

**A socket path is an access boundary.** Anyone who can reach a tmux socket can
run commands in every pane on that server. The tests keep each server under a
directory of its own for isolation, not for secrecy.

## Supported versions

Before 1.0, only the latest release is supported.
