# Watching output as it happens

Every snippet here is executed by `ExamplesTest`.

A control client stays attached and pushes terminal output as tmux produces it,
rather than being asked:

```java
try (ControlClient client = ControlClient.attach(server.config(), session.id());
        EventSubscription<PaneOutput> output = client.subscribeOutput(32)) {

    client.send("send-keys", "-t", session.name(), "echo streamed", "Enter");

    PaneOutput arrived = output.next(Duration.ofSeconds(5)).orElseThrow();
    arrived.data().contains("streamed");  // → true
}
```

Attaching is what makes tmux push at all. A control client that never attaches
sees no output, however long it waits.

Each subscriber chooses a fixed buffer capacity. A full buffer drops its oldest
value, and `droppedCount()` reports the exact loss. The control reader only fills
those buffers; caller code runs on the thread that calls `next()`.

## Requests are serialized

A control client has one reply stream, so `send` calls run one at a time. A
timeout closes the client because the next reply can no longer be attributed
safely. Use `Server` for ordinary commands; use `ControlClient` when the
persistent event stream is the requirement.
