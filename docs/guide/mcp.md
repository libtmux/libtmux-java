# Driving tmux from a model

Every Java snippet here is executed by `ExamplesTest`.

[`libtmux-mcp`](../../libtmux-mcp/) serves a tmux server to any MCP client. The
[module README](../../libtmux-mcp/README.md) is how to run it; this page is why
it is shaped the way it is, and what was measured to decide.

## The thing an agent actually spends

Not tmux commands. Context, and turns.

A model driving a terminal has two costs nobody bills it for: every line it reads
stays in its context for the rest of the conversation, and every tool call is a
round trip it cannot take back once it has started. Nearly every design decision
here follows from those two, and from one more: MCP gives an agent no way to
sleep and no way to cancel a call it is inside.

So a wait that is not a tool does not disappear. It moves into the agent's turn
loop as a polling cycle, where it costs a call per look and has no ceiling at all.

## Wait, do not poll

Four waits, cheapest first.

**You wrote the command: `tmux_run`.** It sends the command, waits for it, and
returns the output with an exit status in one call.

**You wrote it but want it composed yourself: `tmux_wait_for_channel`.** Append
`; tmux wait-for -S mychannel` to whatever you send, then block on the channel.
This is the only wait that infers nothing — tmux blocks inside the server and
returns on the signal itself.

**You did not write it: `tmux_wait_for_text`.** A daemon, a dev server, a build
someone else started. There is no command to append a signal to, so the screen is
all there is to read. This is the only one that is a heuristic.

**You want to keep watching: `tmux_capture_since`.** It returns a cursor; pass it
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

What an unbounded wait really costs is the turn: the agent picks the wrong thing
to wait for once, and has no way to change its mind mid-call. The ceiling makes
that mistake cheap and repeatable instead of terminal.

## Telling output apart from the plumbing

`tmux_run` has to know when a command finished and what it exited with. The shell
in a pane will not tell anyone, so the command is followed by two things it runs
afterwards — one recording the status in a pane option, one signalling a private
tmux channel — and the wait is tmux's own `wait-for`.

The catch is that a shell echoes everything typed at it, so that plumbing lands on
screen amongst the output. Matching it by its shape does not work: in a narrow
pane the echo wraps across rows, and zsh redraws its prompt with cursor movement
rather than by wrapping, so `capture-pane -J` rejoins some of those rows and not
others.

So the command is framed instead. It is bracketed by two lines that print a random
nonce, and only lines strictly between them are returned:

```
 echo lt3fa9-s; ( pytest -q ); lt3fa9=$?; echo lt3fa9-e; tmux … wait-for -S ch_lt3fa9
```

The echo of that whole line *contains* both markers. No echo is ever *equal* to
one. Matching on whole-line equality separates them in about ten lines of code,
with no regular expressions and no wrapping edge cases, and it is checked in a
40-column pane where the echo genuinely does wrap.

Two consequences worth knowing, both pinned by tests:

- The command runs in a **subshell**, so a `cd` or an `export` in it does not
  outlive the call — and neither does an `exit`, which is what keeps `exit 3`
  from closing the pane.
- `tmux_run` returns on the completion signal, which happens *before* the shell
  redraws its prompt. A following `tmux_capture_since` legitimately reports that
  prompt as new output.

## A cursor, so watching is not re-reading

`tmux_capture_since` takes an opaque cursor and returns the lines added since it,
plus the next one. The tenth look at a build log costs the few lines it added,
not the nine screens already read.

It is one capture, and it answers two questions at once. The capture starts one
line *before* the cursor, so the line already delivered comes back first: if that
line still hashes to what it did, everything after it is new. If it does not, the
pane was cleared or its output has outrun the history tmux keeps — and the answer
says `continuous: false` rather than stitching two unrelated screens together.

Handing back lines that do not follow the ones before them, without saying so, is
worse than handing back nothing.

## Being told, instead of asking

tmux can push. A control client that has attached is told when a window appears
or a session is renamed, and `refresh-client -B` registers a format tmux
re-expands on its own one-second timer and reports **only when the value differs**.

That is a change detector inside the server. With `--watch`, this server turns
those into MCP `notifications/resources/updated`, so a client holding
`tmux://panes/%1/content` refreshes when there is a reason to and never otherwise.

The same mechanism is available to any Java caller:

<!-- snippet: compile-only: a watch reports a format when its value changes -->
```java
try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
    client.onEvent(event -> {
        event.subscription();   // which watch this came from
        event.paneId();         // which pane, when the watch is over panes
        event.value();          // what the format expanded to
    });

    client.watch("names", "@*", "#{window_name}");
}
```

Watching costs one attached client, which is a real change to a server somebody
may be looking at — so it is off unless asked for, and the client it attaches is
hidden from `tmux_list_clients` so it cannot be mistaken for a person.

For a sibling design that was measured and rejected: tapping the pty with
`pipe-pane` gives an event source too, but tmux keeps a single pipe per pane, so
starting one silently destroys whatever logging a person had running — and the
pipe carries raw pty bytes rather than the rendered grid.

## What a model may do

Three tiers, and they decide which tools exist rather than which are refused.

```java
Safety.READONLY.allows(Safety.MUTATING);      // → false
Safety.DESTRUCTIVE.allows(Safety.MUTATING);   // → true
Safety.ofWireName("readonly");                // → READONLY
```

A tool above the ceiling is never listed. A model cannot be tempted by a tool it
never saw, and an error it can do nothing about is context spent for nothing. The
server's instructions say plainly what is absent and how an operator would enable
it, so the model does not spend a turn hunting for another way.

### The pane you are speaking through

When an MCP client launches this server from inside tmux, one pane is different
from every other: typing into it types into the conversation, and killing it ends
the model's ability to act at all.

tmux says which one in `TMUX_PANE`, but a pane id is only unique within a single
server — so the socket is checked too, by resolving both paths, before that pane
is believed to be the caller's own. Unprovable means not the caller's: a wrong
"yes" disarms a guard, while a wrong "no" merely declines to help.

`tmux_whoami` names it. `tmux_kill` refuses it, and the window and session holding
it, unless `confirm_self` is passed.

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
recovery: `no pane %9 on this server; call tmux_list_panes for the 3 that exist`.

## Further reading

- [`libtmux-mcp` README](../../libtmux-mcp/README.md) — running it, and the tool list
- [Filtering](filtering.md) — the expression model a `filter` argument carries
- [Execution modes](execution-modes.md) — how commands reach tmux underneath
- [Watching output as it happens](streaming.md) — the control client directly
- [Control-mode subscriptions](../spikes/23-control-subscriptions.md) — what was measured
