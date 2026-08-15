# Watching output as it happens

Every snippet here is executed by `ExamplesTest`.

A control client stays attached and pushes terminal output as tmux produces it,
rather than being asked:

```java
try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
    List<PaneOutput> seen = new CopyOnWriteArrayList<>();
    client.onOutput(seen::add);

    client.send("send-keys", "-t", session.name(), "echo streamed", "Enter");

    // Listeners run on the reader thread; a slow one delays every reply.
    client.isAlive();                      // → true
}
```

Attaching is what makes tmux push at all. A control client that never attaches
sees no output, however long it waits.

## Requests stay independent

Control-mode requests do not queue behind each other's failures: a failure
discards nothing behind it, and every reply carries the request that produced it.

That matters more than it sounds. tmux frames a reply *per command*, so a line
holding two commands answers with two blocks — which is why a command group is
carried by a process even under [`CONTROL`](execution-modes.md), rather than
being sent down a client that expects one block per request.

## Streaming versus carrying

These are two different uses of the same tmux feature:

| you want                        | use                                          |
| ------------------------------- | -------------------------------------------- |
| output pushed to you as it happens | `ControlClient.attach(…)` and `onOutput` |
| every command to cost less        | `ExecutionMode.CONTROL` on the config        |

The first is a client you drive. The second is a carrier the library drives for
you, and it changes nothing about what your calls return — see
[execution modes](execution-modes.md).

They compose: a server in `CONTROL` mode and a `ControlClient` for streaming are
separate connections, and neither disturbs the other.
