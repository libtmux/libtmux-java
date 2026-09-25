# Failures, telemetry, and pane input

Every snippet here is run by `ExamplesTest`.

This guide is for a service that drives tmux on behalf of something else: it has
to decide whether a failed command may be sent again, see what every command
cost, and keep two threads from typing into the same pane.

## Whether a failed command may run again

A command that throws `DispatchException` says how far it got.
`NOT_DISPATCHED` means tmux never started, so nothing changed and sending it
again is safe. `UNKNOWN` means tmux may already have applied it; read the state
back before deciding, and never send a mutation again blindly:

```java
// Given: Server server
try {
    server.globalOptions().set("@deployed", "yes");
} catch (DispatchException failure) {
    if (failure.outcome() == DispatchOutcome.NOT_DISPATCHED) {
        server.globalOptions().set("@deployed", "yes");
    } else {
        server.globalOptions().get("@deployed");
    }
}
```

A timeout is the same exception with the same answer. A group of commands
reports each one separately, through `OperationOutcome` on its batch result:
tmux stops a group at the first failure, so the commands after it did not run.

## One report per command

An `OperationObserver` on the configuration hears about every command after it
ends, on the thread that ran it:

```java
// Given: ServerConfig config
List<OperationReport> reports = new CopyOnWriteArrayList<>();
try (Server observed = Server.open(config.toBuilder().observer(reports::add).build())) {
    observed.cmd("display-message", "-p", "hi");
}
OperationReport last = reports.get(reports.size() - 1);

last.verbs();                                     // → [display-message]
last.certainty();                                 // → COMPLETE
last.exitCode();                                  // → OptionalInt[0]
```

A report carries the command verbs, not their arguments, since arguments hold
session names, socket paths, and whatever a caller typed. It also carries how
long the call waited for a free slot (`queued`), how long it ran (`elapsed`),
line counts, and at most 240 characters of stderr in `boundedError`, which
`toString` leaves out. An observer that throws is logged and ignored; the
command's own result is unaffected, and nothing is retried.

A report's `id` counts calls on one transport, so two reports can be told
apart; it is not a request id for tracing. None is needed: the observer runs on
the thread that made the call, so whatever that thread carries - a logging MDC,
an OpenTelemetry context, a thread local - is there to read:

```java
// Given: ServerConfig config
ThreadLocal<String> request = new ThreadLocal<>();
Map<String, String> seen = new ConcurrentHashMap<>();
ServerConfig traced =
        config.toBuilder().observer(report -> seen.put(report.verbs().get(0), request.get())).build();
try (Server server = Server.open(traced)) {
    request.set("req-42");
    server.cmd("display-message", "-p", "hi");
}

seen.get("display-message");                      // → req-42
```

This is also where a canceled call shows up. A Scala Cats caller that cancels a
command after it reached tmux gets a plain cancellation; the report for that
command says `UNKNOWN`.

## One writer per pane

Keys, a paste, and a shell run share one screen. `sendKeys`, `paste`, and `run`
each take the pane for the length of the call, and a second thread trying at the
same time gets an `IllegalStateException` instead of interleaved input. To keep
the pane across several calls, hold it:

```java
// Given: Pane pane
try (PaneInput.Lease held = PaneInput.hold(pane)) {
    pane.sendKeys(List.of("echo", "Space", "one", "Enter"));
    pane.sendKeys(List.of("echo", "Space", "two", "Enter"));
}
```

The same thread may hold a pane it already holds; the pane is free when the
outermost lease closes. `holdInterruptible` is the hold for a long-running
command that something else may need to stop: another thread calls
`enterInterrupt` to send the stop without taking the pane. The lease is
per JVM. Two processes driving one tmux server do not see each other's holds.

## Where a control client starts

A custom `TmuxTransport` decides where tmux runs: a container, another user, a
remote host. Commands go through `execute`, and a control client, which stays
attached and so cannot borrow a command's slot, is started by the transport's
`ControlCarrier`. `Server.control` uses that carrier, so the attachment lands in
the same place as the commands. A transport that offers none makes
`Server.control` throw rather than attach to whatever this machine calls tmux.
