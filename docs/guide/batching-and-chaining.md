# Batching and chaining

Every snippet here is executed by `DocumentationSnippetsTest`.

The first request to a daemon also starts a tmux process to check its version;
after that an ordinary request starts one tmux process to run its commands. A
batch and a chain put several commands into that one invocation.

## Which path for which job

| you want | use | tmux processes |
| --- | --- | --- |
| one change or read, typed | a handle's method: `session.rename`, `pane.sendKeys` | one tmux process, after one version probe per daemon |
| a command no method covers | `server.cmd(...)` to inspect the answer, `server.run(...)` to require success | one |
| several independent commands, each answered | `batch()` | one, however many commands |
| steps where each acts on what the last made | `chain()` | one for dispatch, plus any capability reads |
| to know when something in a pane has happened | `Pane.awaitText`, `Channel.await`, or `Pane.run` | [measured](../benchmarks/operation-costs.md#push-against-poll) |
| output as it arrives | `server.control(session)`, then a subscription | one attached client, with startup probes |

Creating twenty windows one at a time costs one process each; as a batch or a
chain it costs one for the lot, whatever the daemon's first request cost.
[`operation-costs.md`](../benchmarks/operation-costs.md#collapsing-round-trips)
has the measured rows.

## A batch: several commands, each with its own outcome

```java
// Given: Server server
BatchResult result = server.batch()
        .add("new-window", "-d", "-n", "one")
        .add("new-window", "-d", "-n", "two")
        .run();

result.succeeded();                               // → true
result.operations().size();                       // → 2
result.operations().get(0).outcome();             // → COMPLETE
```

tmux discards a group after its first failure, so a single exit status cannot say
which command failed or which never ran. Each operation is reported for itself:

| outcome | tmux |
| --- | --- |
| `COMPLETE` | ran it, and it succeeded |
| `FAILED` | ran it, and it failed; the rest were discarded |
| `SKIPPED` | never reached it, because an earlier one failed |
| `UNKNOWN` | may or may not have applied it: the reply was lost |

That distinction is the whole point. A batch of five where the third failed tells
you exactly that — two done, one failed, two never attempted — rather than one
unhelpful nonzero. Each operation keeps its own output, so a batch of reads comes
back already split:

```java
// Given: Server server
Pane pane = server.panes().get(0);
BatchResult read = pane.batch()
        .add("display-message", "-p", "-t", pane.id().value(), "#{pane_id}")
        .add("display-message", "-p", "-t", pane.id().value(), "#{pane_width}")
        .run();

read.operations().get(0).stdout().get(0).equals(pane.id().value());   // → true
read.operations().get(1).stdout().size();                             // → 1
```

A batch taken from a handle, as `pane.batch()` is, runs only on the server that
handle came from: a tmux started since, even on the same pid, refuses the whole
batch with `TargetGoneException` and runs none of it. `server.batch()`
reaches whatever server answers.

tmux takes one group as one command of at most about 16300 bytes, and refuses a
longer one with `command too long`. `Batch.length()` says how far a batch has
got, so a long run of commands can be split before it is sent.

## A chain: each step acts on what the last one made

```java
// Given: Server server
server.chain()
        .newWindow("built")
        .splitLeftRight()
        .sendLine("echo chained")
        .run();

// One request built a window and split it, with no round trip to learn the id.
server.windows().stream().anyMatch(w -> w.name().equals("built"));   // → true
```

No step names a target. tmux moves its own current target as a group runs, so
the split lands in the window just created and the keys land in the pane just
split — with no round trip to learn either id.

That is the difference from a batch: a batch is several commands that happen to
travel together, a chain is several commands that depend on each other. A chain
reports each step the way a batch does, and `then(...)` adds any tmux command
the chain does not name.

## What neither does

Neither makes several threads' commands one unit. Each call is one group; two
threads' groups can land in either order. [Concurrency](concurrency.md) says what
runs at once and what a pane's input hold keeps apart.

## What the guards do not read

The feature guards read each literal command's own words. A command carried
inside a string that tmux parses itself is sent as written: the body of
`if-shell`, the argument of `run-shell -C`, a brace block, a hook, a key binding
or a sourced file. `if-shell -F 1 'break-pane -d'` passes the guard unchanged,
and tmux runs the nested `break-pane`. These are the commands the library
refuses when they are literal, the releases they affect, and what happens there
when they are nested:

- **`break-pane` without `-n`:** exact tmux 3.7 only. The server exits. tmux
  3.7a fixes it ([`CHANGES`][3.7a-changes], [the inverted name test in
  3.7][3.7-break-pane]).
- **`select-layout` with a string tmux cannot parse:** tmux 3.3 and 3.3a. The
  server exits. 3.4 answers "invalid layout".
- **`refresh-client -l`:** below 3.6a, and only with an attached client. The
  server exits on 3.3a when the client's terminal does not answer, and on 3.4 to
  3.6 when the client leaves before it answers.
- **`delete-buffer -b NAME`, and `set-buffer -b NAME -n`:** below 3.4. Nothing
  crashes; a name that does not exist acts on the newest buffer instead, so the
  wrong buffer is deleted or renamed.
- **`split-window -p`:** exactly 3.4. tmux answers "size missing" and changes
  nothing.
- **Names containing `:` or `.`:** exactly 3.7. tmux answers "invalid window
  name" and changes nothing.

On exact tmux 3.7, name every `break-pane`, nested or not, and upgrade to 3.7a.
The [default `prefix !` binding][3.7-binding] is itself a nameless
`break-pane`, which no library check can reach. [Operating a
service](operating-a-service.md#nested-commands) repeats the rule for a
long-running process.

[3.7a-changes]: https://github.com/tmux/tmux/blob/3.7a/CHANGES#L3
[3.7-break-pane]: https://github.com/tmux/tmux/blob/3.7/cmd-break-pane.c#L117-L124
[3.7-binding]: https://github.com/tmux/tmux/blob/3.7/key-bindings.c#L358
