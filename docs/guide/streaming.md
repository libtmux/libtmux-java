# Watching output as it happens

Every snippet here is run by `ExamplesTest`, except one marked compile-only,
which says why.

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
// Given: Server server, Pane pane, Path socket
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
// Given: Pane pane
// Start the producer directly and keep its pane open after it prints.
pane.respawn("sh", "-c", "printf 'listening on 8080\\n'; exec cat");

TextOutcome seen = pane.awaitText("listening on", Duration.ofSeconds(10));
boolean ready = seen == TextOutcome.APPEARED
        || seen == TextOutcome.PRESENT_AT_ENTRY;
ready;   // → true
```

The echo of that command is not an answer to it, so a wait for text the command
line itself contains is not satisfied by the line being typed. `PRESENT_AT_ENTRY`
says the first look already showed the text: it may be output that beat the wait
there, or it may have been on the pane for an hour. A screen cannot tell those
apart, which is the reason to prefer a channel whenever the command is yours.

Waiting longer here is less reliable rather than more: tmux frees the oldest
scrollback once `history-limit` is reached, so a long wait on a productive pane
can end up reading past the lines it was watching for.

**You want to keep watching rather than wait once:** that is the control client
below.

Every wait answers with a reason and never with a boolean, because tmux reports
a server that died under the waiter as a successful wake. "Nothing printed it"
and "the server is gone" call for opposite recovery, so they are different
answers. A channel answers with `WakeReason`; a text wait with `TextOutcome`,
which also tells text that appeared from text already there; `Pane.run` with a
`PaneRun` that carries the exit status and output.

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
// Given: Server server, Session session
try (ControlClient client = server.control(session);
        EventSubscription<PaneOutput> output = client.subscribeOutput(32)) {

    client.send("send-keys", "-t", session.name(), "echo streamed", "Enter");

    StringBuilder seen = new StringBuilder();
    while (seen.indexOf("streamed") < 0) {
        seen.append(Delivery.kept(output.next(Duration.ofSeconds(5)).orElseThrow()).data());
    }
    seen.indexOf("streamed") >= 0;  // → true
}
```

Attaching is what makes tmux push at all. A control client that never attaches
sees no output, however long it waits.

tmux decides where one push ends and the next begins, so what a caller wants can
arrive split across several: read until you have it rather than testing the
first one. The loop above is bounded by the timeout each `next` carries.

tmux cuts pushes by byte count, not by character, and a pane written to in large
blocks has some of its characters cut in two. `data()` is decoded per pane, so a
cut character arrives whole with the later push. `bytes()` is exactly what one
push carried: a pane's pushes concatenate to the bytes it wrote, which is what a
recording of the terminal needs. A byte that is not UTF-8 appears in `data()` as
`\xHH`.

`stream()` reads the same steps as a `java.util.stream.Stream`, pulled one at a
time on the consuming thread. It ends when the subscription closes, so a timer
that closes it bounds the whole read, and closing the stream closes the
subscription:

```java
// Given: Server server, Session session
try (ControlClient client = server.control(session);
        EventSubscription<PaneOutput> output = client.subscribeOutput(32)) {

    client.send("send-keys", "-t", session.name(), "echo streamed", "Enter");
    CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(output::close);

    StringBuilder seen = new StringBuilder();
    try (Stream<Delivery<PaneOutput>> steps = output.stream()) {
        Iterator<PaneOutput> outputs = steps.map(Delivery::kept).iterator();
        while (seen.indexOf("streamed") < 0 && outputs.hasNext()) {
            seen.append(outputs.next().data());
        }
    }
    seen.indexOf("streamed") >= 0;  // → true
}
```

Each subscriber chooses a fixed buffer capacity. A full buffer drops its oldest
value. The next read is a `Delivery.Gap` naming how many were lost since the
previous read, and only then the events that remain. `droppedCount()` is the
total. `cause()` is empty when the caller closed the subscription and set when
the control client ended it. Closing discards what is still buffered; a client
that ends leaves it to be read first. A subscription does not reconnect: attach again
with `server.control(session)` and read a snapshot. Nothing already missed is
replayed. The control reader only fills those buffers; caller code runs on the
thread that calls `next()`. `standardError()` is the bounded text the tmux
process wrote to its error stream.

`publisher()` reads the same steps as a `java.util.concurrent.Flow.Publisher`,
for a reactive caller — Reactor, RxJava, Mutiny, Spring — through
`FlowAdapters` or `JdkFlowAdapter`. Delivery is demand-driven: `request`
bounds what reaches the subscriber, `Long.MAX_VALUE` asks for everything, and
only one subscriber is ever accepted — a second gets `onError` rather than
sharing the steps. `cancel()` closes the subscription, the same as `stream()`
closing does. Each read that waits for the next step runs on a fresh virtual
thread; `publisher(Executor)` reads somewhere else instead.

Demand only bounds what reaches the subscriber, not what tmux sends: this
subscription's own bounded buffer keeps filling regardless, and a full buffer
still reports its loss as a `Delivery.Gap`, delivered like any other element.
Pausing tmux's own push is `pause-after`, further down.

```java
// Given: Server server, Session session
try (ControlClient client = server.control(session);
        EventSubscription<PaneOutput> output = client.subscribeOutput(32)) {

    client.send("send-keys", "-t", session.name(), "echo streamed", "Enter");

    StringBuilder seen = new StringBuilder();
    CountDownLatch done = new CountDownLatch(1);
    output.publisher().subscribe(new Flow.Subscriber<Delivery<PaneOutput>>() {
        Flow.Subscription subscription;

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        public void onNext(Delivery<PaneOutput> step) {
            seen.append(Delivery.kept(step).data());
            if (seen.indexOf("streamed") >= 0) {
                subscription.cancel();
                done.countDown();
            } else {
                subscription.request(1);
            }
        }

        public void onError(Throwable failure) {
            done.countDown();
        }

        public void onComplete() {
            done.countDown();
        }
    });

    done.await(5, TimeUnit.SECONDS);
    seen.indexOf("streamed") >= 0;  // → true
}
```

## Pushed changes

The same client is told about changes as they happen — a window created or
renamed, a session switched, a layout moved — without asking. Each arrives as a
`ControlEvent`, and `notification()` is its typed reading, for matching on:

```java
// Given: Server server, Session session
try (ControlClient client = server.control(session);
        EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {

    var unused = session.windows().get(0).rename("build logs");

    String renamed = null;
    while (renamed == null) {
        Notification seen = Delivery.kept(events.next(Duration.ofSeconds(5)).orElseThrow()).notification();
        renamed = switch (seen) {
            case Notification.WindowRenamed(var window, var name, var attached) -> name;
            default -> null;
        };
    }
    renamed;                                      // → build logs
}
```

The set is sealed with an `Unknown` case: tmux adds notifications between
releases, and one this library does not model yet still arrives, as `Unknown`,
with `kind()` and `fields()` carrying what tmux wrote.

## Pausing and muting a pane

There is no typed `pause()`/`resume()` here — `refresh-client -A pane:state` is
reached through the raw escape hatch, `client.send("refresh-client", "-A",
"<pane>:<state>")`. Pass the pair unquoted: `send` single-quotes every argument
itself, which a control-mode line needs, since tmux answers a bare `parse error`
for an unquoted `%0:off` typed there directly.

The four state words are **two independent pairs**, with different loss
behaviour, measured against tmux 3.2a, 3.7c and next-3.9:

| stop → resume | recovers? | output produced while stopped |
| --- | --- | --- |
| `off` → `on` | yes | lost before tmux 3.7; delivered as a backlog on 3.7+ |
| `pause` → `continue` | yes | lost, on every version |
| `off` → `continue` | **no** | stuck, and tmux answers success with no error |
| `pause` → `on` | **no** | stuck, same silent success |

Resume with the word that stopped it — `on` after `off`, `continue` after
`pause`. Crossing the pair leaves the pane stopped with nothing in the reply
to say so.

On tmux 3.7 and later, `off` also stops tmux reading that pane's pty **for
every client**, not only the one that asked: a human attached to the same pane
sees it freeze too, and the pane's own program can block on `write()` once the
kernel's pty buffer fills behind it. Before 3.7, `off` only withheld delivery
from the asking client — the pane kept updating everywhere else, and what was
withheld was lost outright rather than queued. `pause`/`continue` never
reaches other clients at all; it drops output for the pausing client only, on
every version.

tmux can also pause a pane on its own. After `client.send("refresh-client",
"-f", "pause-after=5")`, a pane whose output this client is more than five
seconds behind on is paused and announced as `Notification.Pause`; output keeps
reaching `subscribeOutput` until then, and `refresh-client -A <pane>:continue`
resumes it, announced as `Notification.Continue`. tmux 3.2 and later.

## Requests are serialized

A control client has one reply stream, so `send` calls run one at a time. A
timeout closes the client because the next reply can no longer be attributed
safely. Use `Server` for ordinary commands; use `ControlClient` when the
persistent event stream is the requirement.

One command answers faster over an attached control client than as its own
process, but a reply is an acknowledgement rather than a completion, and one
client serializes every caller: [measured, with what that number does and
does not justify](../benchmarks/operations.md#one-command-two-transports).
