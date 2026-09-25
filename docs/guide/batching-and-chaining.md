# Batching and chaining

Every snippet here is executed by `ExamplesTest`.

Each call starts one tmux process, and that process is most of what a call costs.
A batch and a chain both put several commands into one invocation.

## Which path for which job

| you want | use | tmux processes |
| --- | --- | --- |
| one change or read, typed | a handle's method: `session.rename`, `pane.sendKeys` | one |
| a command no method covers | `server.cmd(...)` to inspect the answer, `server.run(...)` to require success | one |
| several independent commands, each answered | `batch()` | one, however many |
| steps where each acts on what the last made | `chain()` | one |
| to know when something in a pane has happened | `Pane.awaitText`, `Channel.await`, or `Pane.run` | [measured](../benchmarks/operations.md#push-against-poll) |
| output as it arrives | `server.control(session)`, then a subscription | one attached client |

The same twenty windows cost 64 processes made one at a time and 5 as a batch or
a chain: [`operations.md`](../benchmarks/operations.md#collapsing-round-trips)
has the rows.

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
