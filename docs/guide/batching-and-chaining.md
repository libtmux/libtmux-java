# Batching and chaining

Every snippet here is executed by `ExamplesTest`.

Both put several commands into one tmux invocation. They are not
[execution modes](execution-modes.md) — they work under any carrier.

## A batch: several commands, each with its own outcome

```java
BatchResult result = server.batch()
        .add("new-window", "-d", "-n", "one")
        .add("new-window", "-d", "-n", "two")
        .run();

assertTrue(result.succeeded());
assertEquals(2, result.operations().size());
```

tmux discards a group after its first failure, so a single exit status cannot say
which command failed or which never ran. Each operation is reported for itself:
`COMPLETE`, `FAILED`, `SKIPPED` or `UNKNOWN`.

That distinction is the whole point. A batch of five where the third failed tells
you exactly that — two done, one failed, two never attempted — rather than one
unhelpful nonzero.

## A chain: each step acts on what the last one made

```java
server.chain()
        .newWindow("built")
        .splitLeftRight()
        .sendLine("echo chained")
        .run();
```

No step names a target. tmux moves its own current target as a group runs, so
the split lands in the window just created and the keys land in the pane just
split — with no round trip to learn either id.

That is the difference from a batch: a batch is several commands that happen to
travel together, a chain is several commands that depend on each other.

## Under control mode

Both work unchanged. A group is carried by a process even when the server is in
`CONTROL`, because control mode frames a reply per command and a group would
desynchronise the stream. Nothing marks the difference at the call site — see
[execution modes](execution-modes.md) for why that routing is the library's job
rather than yours.
