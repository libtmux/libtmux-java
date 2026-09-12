# Watching output as it happens

Every snippet here is executed by `ExamplesTest`.

## Waiting for one thing, cheapest first

Streaming is for watching continuously. To wait for a single thing and carry on,
reach for these in order — the first is exact, the last is a guess.

**You wrote the command: signal a channel.** Append `; tmux wait-for -S name` to
it and block on that channel. tmux blocks server-side and returns on the signal
itself, so the answer is a fact rather than an inference from the screen, and one
tmux process covers the whole wait however long it takes. The `;` fires the
signal whether the command succeeded or failed, so the wait cannot deadlock on
failure.

<!-- snippet: compile-only: the signal comes from a shell inside a pane, whose readiness this fixture cannot establish; it timed out on the 3.6 through 3.7c matrix lanes and passed on the rest -->
```java
Channel done = server.channel("build-finished");
done.drain();

// Keys sent to a shell that is not reading yet are swallowed, and tmux hands
// back a pane id the moment it forks the pane — before the prompt is drawn.
pane.await(ready -> !ready.expand("#{cursor_x},#{cursor_y}").equals("0,0"), Duration.ofSeconds(10));

// Name the server's own tmux and socket rather than trusting the pane's PATH: a
// client from a different release than the server is dropped, not served.
String tmux = server.config().binaryPath();
pane.sendLine("sleep 1; " + tmux + " -S " + socket + " wait-for -S build-finished");

done.await(Duration.ofSeconds(20));   // SIGNALLED, once the command reaches it
```

That block is compiled but not executed here: the signal comes from a shell
inside a pane, and this fixture cannot establish that the shell is reading yet.
Which is the honest caveat for the idiom too — see the note at the end of this
section.

`drain()` first, always. tmux remembers a signal sent while nobody was waiting
and hands it to the next waiter — possibly one in a later run of a different
program — so a wait that has not drained can return immediately having waited
for nothing.

**You did not write the command: poll the pane's text.** A daemon announcing it
is ready, a build somebody else started. This is the only case where reading the
screen is the right answer, and it is a heuristic:

```java
pane.sendLine("echo listening on 8080");

pane.awaitText("listening on", Duration.ofSeconds(10));   // → SIGNALLED
```

Waiting longer here is less reliable rather than more: tmux frees the oldest
scrollback once `history-limit` is reached, so a long wait on a productive pane
can end up reading past the lines it was watching for.

**You want to keep watching rather than wait once:** that is the control client
below.

Every wait answers with a `WakeReason` — `SIGNALLED`, `TIMED_OUT` or
`SERVER_GONE` — and never with a boolean, because tmux reports a server that
died under the waiter as a successful wake. "Nothing printed it" and "the server
is gone" call for opposite recovery, so they are different answers.

### The rung this ladder does not name

Every wait above assumes the pane is already reading. tmux returns a pane id the
moment it *forks* the pane, well before the shell inside has drawn a prompt, and
keys sent before that are swallowed with no error anywhere — so the command that
was supposed to signal the channel never runs, and the wait reports a timeout for
a reason that has nothing to do with waiting.

`pane.await(ready -> !ready.expand("#{cursor_x},#{cursor_y}").equals("0,0"), …)`
is the check, and the first snippet above uses it. It is a heuristic: a pane
running a full-screen program, or a bare `cat`, may never move the cursor off the
origin. Treat exhaustion as "carry on" rather than as an error.

## Pushed output

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
