# Driving tmux from a model

Every Java snippet here is executed by `ExamplesTest`.

[`libtmux-mcp`](../../libtmux-mcp/) serves a tmux server to any MCP client. The
[module README](../../libtmux-mcp/README.md) is how to run it; this page is why
it is shaped the way it is, and what was measured to decide.

Commands here use `libtmux-mcp` as the installed launcher name; the module
README gives its full path.

## The thing an agent actually spends

Not tmux commands. Context, and turns.

A model driving a terminal has two costs nobody bills it for: every line it reads
stays in its context for the rest of the conversation, and every tool call is a
round trip it cannot take back once it has started. Nearly every design decision
here follows from those two, and from one more: MCP gives an agent no sleep
primitive, and Java SDK 2.0.1 does not propagate cancellation into a synchronous
handler after it starts.

So a wait that is not a tool does not disappear. It moves into the agent's turn
loop as a polling cycle, where it costs a call per look and has no ceiling at all.

## Wait, do not poll

Four waits, cheapest first.

**You wrote the command: `run_shell_command`.** It sends the command, waits for it, and
returns the output with an exit status in one call.

**You wrote it but want it composed yourself: `wait_for_channel`.** Append
`; tmux wait-for -S mychannel` to whatever you send, then block on the channel.
This is the only wait that infers nothing — tmux blocks inside the server and
returns on the signal itself.

**You did not write it: `wait_for_text`.** A daemon, a dev server, a build
someone else started. There is no command to append a signal to, so the screen is
all there is to read. This is the only one that is a heuristic.

**You want to keep watching: `capture_since`.** It returns a cursor; pass it
back and you get the lines added since, not the screen again.

### Why every wait is bounded

A wait is capped at 30 seconds by default and 2 minutes absolutely. An over-large
request is clamped rather than refused, and the result reports the ceiling
actually enforced.

The cap protects the agent's turn, not the connection. That is worth stating
because the opposite is intuitive and wrong — a blocked tool call here does not
block anything else. Measured against the MCP Java SDK 2.0.0 over stdio, with one
tool call blocking for six seconds and twenty more sent behind it:

| the blocking handler is written as | interleaved calls served |
| ---------------------------------- | -----------------------: |
| a synchronous handler *(what this ships)* | **20 of 20** |
| `Mono.fromCallable` that blocks | 0 of 20 |
| `Mono.delay`, or `subscribeOn(boundedElastic)` | 20 of 20 |

The SDK runs a synchronous handler on `Schedulers.boundedElastic` rather than on
the thread reading the transport, so blocking there costs a pool thread and
nothing else. Forty concurrent six-second calls all returned in one 6.05-second
wave; the ceiling is around 150, far past anything an agent does.

Writing the same handlers reactively is where it goes wrong: a `Mono` that blocks
pins the single reactor thread, serves nothing at all, and stretched the blocking
call itself from 6.2 to 9.4 seconds. **This server is synchronous on purpose.**

A client's request deadline is separate. The Java SDK 2.0.1 client defaults to
20 seconds, so configure it above a longer wait before requesting one. Cancelling
or timing out abandons the answer but does not stop the synchronous handler or
undo tmux changes it already dispatched. The server ceiling keeps that abandoned
work bounded.

## Telling output apart from the plumbing

`run_shell_command` has to know when a command finished and what it exited with.
An outer subshell therefore arms an exit trap before starting the command. The
trap sends the numeric status marker and signals a private tmux channel; the
wait is tmux's own `wait-for`.

The catch is that a shell echoes everything typed at it, so that plumbing lands on
screen amongst the output. Matching it by its shape does not work: in a narrow
pane the echo wraps across rows, and zsh redraws its prompt with cursor movement
rather than by wrapping, so `capture-pane -J` rejoins some of those rows and not
others.

So the command is framed instead. It is bracketed by two lines that print a random
nonce, and only lines strictly between them are returned:

```
 ( \trap '/usr/bin/tmux -S /tmp/tmux.sock display-message -p lt3fa9-e:"$?"; /usr/bin/tmux -S /tmp/tmux.sock wait-for -S ch_lt3fa9; \exit 0' 0; /usr/bin/tmux -S /tmp/tmux.sock display-message -p lt3fa9-s; ( \eval 'pytest -q' ) )
```

The echo of that whole line *contains* both markers. No echo is ever *equal* to
one. Matching on whole-line equality separates them in about ten lines of code,
with no regular expressions and no wrapping edge cases, and it is checked in a
40-column pane where the echo genuinely does wrap.

Two consequences worth knowing, both pinned by tests:

- The command runs in a **subshell**, so a `cd` or an `export` in it does not
  outlive the call — and neither does an `exit`, which is what keeps `exit 3`
  from closing the pane.
- `run_shell_command` returns on the completion signal, which happens *before*
  the shell redraws its prompt. A following `capture_since` legitimately reports
  that prompt as new output.

The command's inner subshell inherits the pane's ordinary environment, options,
traps, and functions. The outer frame uses one absolute client and the server's
resolved `-S` socket, so output-command aliases and functions, a `tmux` basename
function, pane `PATH`, and pane socket variables do not own completion.
Pre-existing functions named `trap`, `eval`, `exit`, or exactly like that
resolved client are not a supported hostile-shell case. The marker
`display-message` calls still use the trusted server's normal command path,
including configured command aliases and `after-display-message` hooks.

### Pane modes and synchronized input

Key input follows tmux's effective `synchronize-panes` values: a source whose
effective value is off receives input alone; a source whose value is on sends to
all panes whose effective value is on. The window option supplies the inherited
default, and a pane-level override can change an individual pane's value. One
modal or dead member refuses the whole configured key cohort before dispatch,
while paste-buffer input checks and targets only its requested pane.

Framed commands refuse a synchronized cohort because their output, completion,
and status describe one pane. They require one normal live shell at the initial
preflight and again immediately before input. Each preflight is an observation,
not an atomic reservation: membership can change before dispatch, and reported
pane ids prove neither actual recipients nor delivery.

## A cursor, so watching is not re-reading

`capture_since` takes an opaque cursor and returns the lines added since it,
plus the next one. The tenth look at a build log costs the few lines it added,
not the nine screens already read.

It is one look, and it answers two questions at once. The look reaches back past
the line already delivered: if that line still hashes to what it did, everything
after it is new. If it does not, the pane was cleared or its output has outrun the
history tmux keeps — and the answer says `continuous: false` rather than stitching
two unrelated screens together.

Handing back lines that do not follow the ones before them, without saying so, is
worse than handing back nothing. Which is why two things about that look are not
optional, and both were measured after a false `continuous: false` reached CI
([the spike](../spikes/27-torn-reads.md)):

**The capture and the pane's position come from one tmux invocation.** Where a
line sits in a capture depends on how far the pane has scrolled, so two
invocations can describe different moments — measured, they disagreed in 40 of 60
attempts under continuous output, and a pane that merely scrolled then looks
exactly like one that was cleared. Batched, none of 60 did: tmux does not process
pane output between two commands of the same invocation.

**Only finished lines are delivered or anchored to.** A terminal is a grid rather
than a log, so the row the cursor sits on is still being drawn — a capture catches
`line-123` as `line-12` — and a shell redrawing a wrapped command line rewrites
rows that were already handed over. `#{cursor_y}` comes back in the same
invocation, and everything at or below it waits until it is finished.

## Being told, instead of asking

tmux can push. A control client that has attached is told when a window appears
or a session is renamed, and `refresh-client -B` registers a format tmux
re-expands on its own one-second timer and reports **only when the value differs**.

That is a change detector inside the server. The Java library exposes it to
applications directly:

<!-- snippet: compile-only: a watch reports a format when its value changes -->
```java
try (ControlClient client = ControlClient.attach(server.config(), session.id());
        EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {
    client.watch("names", "@*", "#{window_name}");

    ControlEvent event = events.next(Duration.ofSeconds(2)).orElseThrow();
    event.subscription();   // which watch this came from
    event.windowId();        // which window, when the watch is over windows
    event.value();           // what the format expanded to
}
```

Watching costs one attached client, which is a real change to a server somebody
may be looking at. The MCP process therefore does not attach one implicitly or
turn it into dynamic resource notifications. An agent uses `wait_for_text`,
`wait_for_channel`, and cursor-based `capture_since`; an embedding application
that chooses the control client owns its lifetime explicitly.

For a sibling design that was measured and rejected: tapping the pty with
`pipe-pane` gives an event source too, but tmux keeps a single pipe per pane, so
starting one silently destroys whatever logging a person had running — and the
pipe carries raw pty bytes rather than the rendered grid.

## What a model may do

Four unordered toolsets decide which tools exist rather than which are refused.

```console
$ LIBTMUX_TOOLSETS=inspect,execute \
    LIBTMUX_EXCLUDE_TOOLS=run_shell_command \
    libtmux-mcp --socket-name project
```

`inspect`, `manage`, `execute`, and `teardown` are independent capabilities, not
increasing trust levels. Exact names can add tools, exclusions remove them last,
and an empty toolset selection starts with none. A newly created dedicated
minimal daemon defaults to all four; existing or operator-selected daemons omit
teardown unless it is requested explicitly.

The selection filters the catalog; it does not confine effects. `execute`
includes authored shell commands, key input, and pasted text, so it can run
programs or delete data in a pane. Use a separate OS account, socket permissions,
or a container when effects must be contained.

A hidden tool is never listed and is not callable. Every visible tool also
publishes process reach, tmux effects, output classes, one
`inputLiteralization` map, schemas, nested authority, and conservative MCP
effect hints in one capability row. Detailed interpreter-sink tables remain
internal validation data. `tmux://capabilities` reports those same rows and why
this process selected them.

### The pane you are speaking through

When an MCP client launches this server from inside tmux, one pane is different
from every other: typing into it types into the conversation, and killing it ends
the model's ability to act at all.

tmux says which one in `TMUX_PANE`, but a pane id is only unique within a single
server — so the socket is checked too, by resolving both paths, before that pane
is believed to be the caller's own. Unprovable means not the caller's: a wrong
"yes" disarms a guard, while a wrong "no" merely declines to help.

`list_panes` marks it as the caller. `kill_pane`, `kill_window`, and
`kill_session` refuse it and its containers unless `confirm_self` is passed.

## Reading costs context

Every read is capped, keeps the **newest** lines, and reports how many it dropped.
The tail is what matters: the reason to look at a terminal is almost always what
it just did.

There is a character budget as well as a line budget, because a line has no length
limit — a pane showing minified JavaScript is one line of half a megabyte, and a
line budget alone lets it through.

## Answers are objects

Every tool answers with a named record, sent both as `structuredContent` and as
the same JSON in text. Named fields rather than an array so a model does not count
positions to find out how many panes it got, and a `note` field wherever an answer
needs something the other fields cannot say — what to do about a timeout, which
tool finds a working target, that a filter matched none of the forty panes that
exist.

Errors work the same way. A failure comes back as a tool error rather than an
exception, because a transport-level exception never reaches the model — and the
model is the one participant able to choose a different pane. Each one names the
recovery: `no pane %9 on this server; call list_panes for the 3 that exist`.

## Further reading

- [`libtmux-mcp` README](../../libtmux-mcp/README.md) — running it, and the tool list
- [Filtering](filtering.md) — the expression model Java applications can use outside MCP
- [Watching output as it happens](streaming.md) — the control client directly
- [Control-mode subscriptions](../spikes/23-control-subscriptions.md) — what was measured
