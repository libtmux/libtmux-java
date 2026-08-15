# Real-tmux JUnit lifecycle ownership

## Verdict

Hold fixtures in the extension store, release them from a lifecycle callback,
and keep the aggregate idempotent so the framework's own store handling is a
second path rather than the only one.

Nothing signals a process. A detached tmux server is nobody's child, so the only
handle is a pid, and a pid can be reused; cleanup that cannot be completed is
reported rather than aimed at whatever now owns that number.

## Fixed oracle

One set of exit-path cases, shared by every contender so all three are measured
against identical test bodies rather than bodies written to suit them: success,
assertion failure, assumption abort, an exception from the body, a timeout, a
repeated test, and a test taking two fixtures at once.

Most of those are meant to fail, so they are tagged out of ordinary runs and
executed only through the JUnit Platform TestKit, which reports what each case
did. Two things are then asserted, and the second is the one that matters:

- the test's own outcome survives — a deliberate assertion failure is still
  reported as that failure, never replaced by a cleanup error; and
- after the whole run, nothing any fixture created is still alive or on disk.

Cleanliness is judged from outside. Fixtures write what they create to a ledger
at creation time, and the harness reads it after the run, so a resource whose
teardown never ran is still visible. An extension asserting its own teardown
would prove nothing about the paths where teardown does not happen.

Each server is started on a private socket with an explicit empty config, and is
required to agree about which socket it is on before the fixture is handed over.
The socket path is a validated value whose `toString` is redacted, because it
names a live server and reaches logs and failed assertions constantly.

## Contender results

| contender       | sequential | concurrent            | store auto-close off | caller forgets to close |
| --------------- | ---------- | --------------------- | -------------------- | ----------------------- |
| callback-owned  | clean      | 1 pass, 7 fail        | not applicable       | not applicable          |
| store-owned     | clean      | clean                 | 30 leaked resources  | not applicable          |
| parameter-owned | 30 leaked  | not run after failing | not applicable       | clean                   |

Sequential runs are 9 cases: 5 pass, 3 fail with their own reasons, 1 aborts.

### Callback-owned state

Resources in extension instance fields, released from a matching callback. The
simplest thing that works, and it is clean sequentially.

It fails concurrently, and not by leaking. JUnit reuses one
declaratively-registered extension instance for every test in a class, so those
fields are shared mutable state: one test's teardown reaches servers belonging to
tests still running. Four cases that pass sequentially fail under concurrent
execution, and they fail _as the tests themselves_ — which is worse than a leak,
because it presents as a bug in the code under test rather than in the harness.

### Store-owned aggregate

One idempotent aggregate `AutoCloseable` in the extension store, registered
before the first process starts, closing in reverse acquisition order.

Correct sequentially and concurrently: the store is scoped per test context, so
teardown cannot reach across tests. Its single failure is that release happens
only because the framework chooses to close the value. Store auto-close is a
configuration property, and with
`junit.jupiter.extensions.store.close.autocloseable.enabled=false` this design
released nothing and reported nothing — 10 servers, their sockets, and their
directories all survived, silently.

### Parameter-owned fixture

The resolved value is `AutoCloseable` and the caller closes it, so ownership is
visible at the use site.

It is clean when every test cooperates: the disciplined case set, using
try-with-resources on every path including the timeout, leaks nothing. Against
the shared cases, which do not close, it leaked 10 live servers. That is the
contender's real cost — correctness delegated to caller discipline, on every path
including the ones a caller does not write, like a timeout firing mid-body.

## Ranking and synthesis

Correctness decides at the first criterion, and only one contender survives it.
Callback-owned corrupts concurrent runs; parameter-owned depends on the caller;
store-owned is correct on every path measured except one, and that one has a
known graft.

Synthesis rebuilds the store design and adds the explicit release the plan
anticipated: the aggregate is closed from a lifecycle callback _and_ remains
`AutoCloseable` for the framework. It closes exactly once however many times it
is asked, so both paths firing is harmless and either alone suffices. Adding a
fixture to an already-closed scope is refused rather than silently dropped.

The synthesis passes all three gates, including the two that killed contenders:
every exit path, concurrent execution, and store auto-close disabled.

## What cleanup may not do

`kill-server` is the whole mechanism. When it does not succeed, the cleanup
failure is reported and the directory is kept.

Signalling is deliberately absent, and the identity check is the reason it stays
absent rather than becoming a backstop. A pid alone proves nothing: the server
may have exited and the number been reused, so signalling on pid equality is how
a test harness kills an unrelated process. The fixture records the server's start
instant beside its pid, and treats an absent start instant as unverified
identity. Even a matching start instant leaves a check-then-signal race — the
process can exit between the check and the signal — so the comparison narrows the
window without closing it. That residue is a safety cost, not something the
comparison removes, which is why it gates a diagnostic rather than a kill.

## Not covered

- Fixtures run on the matrix's newest lane only. The forcible-cleanup variants
  across every released tmux version were not baked off; the design avoids
  signalling entirely, so the variant that retains a foreground process was not
  built.
- Concurrency is exercised through the platform's parallel execution at the
  default level, not at a repetition count chosen to force store or namespace
  collisions.
- The disciplined parameter-owned case set was not run concurrently, its
  sequential leak having already decided the criterion.

## Result record

| gate                 | oracle                                                    | callback-owned         | store-owned         | parameter-owned     | synthesis      | verdict                           | artifact digest                                                           |
| -------------------- | --------------------------------------------------------- | ---------------------- | ------------------- | ------------------- | -------------- | --------------------------------- | ------------------------------------------------------------------------- |
| exit paths           | outcomes preserved, nothing left behind, 9 cases          | pass                   | pass                | hard fail, 10 leaks | pass           | rejects parameter ownership       | `sha256:a9dfe5da2ac84f360eb5b276c649fadf2ae314613f21b0f67c2995427a904d21` |
| concurrent execution | passing cases must not become failures, and must not leak | hard fail, 4 corrupted | pass                | not run             | pass           | rejects callback-owned state      | `sha256:f446e07078820fce5bc9eb6837686ba7ebf20737553c7a0cea3cda3ffee09238` |
| store auto-close off | release must not depend on a configuration property       | not applicable         | hard fail, 30 leaks | not applicable      | pass           | forces the explicit-release graft | `sha256:c762de953542ce66e831af9f9a77f88f9327df3e5c4336ef627572c6056441c6` |
| caller discipline    | clean when every test closes on every path                | not applicable         | not applicable      | pass                | not applicable | records the cost, not a win       | `sha256:37e541893bd21d04ed7d6665bc9f076a67616cfb2ba4273a631d56c3cb891a05` |
| synthesis            | all three gates together                                  | not applicable         | not applicable      | not applicable      | pass, 3 cases  | pass                              | `sha256:4c93172e81f0063170756fbdbffd97bed47bf9907955e4be4539c7145df23dd3` |
| process cleanup      | locked owned-process verifier over 3441 ledger rows       | pass                   | pass                | pass                | pass           | pass                              | `sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |

The cleanup verifier produces no output on success, so its row carries the digest
of the empty file.

## Commands

Each contender runs the shared matrix in its own scope:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        junit-callback-owned \
        reuse \
        product \
        "$root/junit" \
        -PspikeRoot="$root" \
        -PtmuxMatrix="$root/tools/tmux" \
        --rerun-tasks \
        :callback-owned:test \
        > "$root/artifacts/junit-callback-owned.log" 2>&1'
```

The synthesis faces all three gates together:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        junit-synthesis \
        reuse \
        product \
        "$root/junit" \
        -PspikeRoot="$root" \
        -PtmuxMatrix="$root/tools/tmux" \
        --rerun-tasks \
        :synthesis:test \
        > "$root/artifacts/junit-synthesis.log" 2>&1'
```

## Evidence digests

Artifact labels are relative to the disposable root. No nonce, PID, hostname,
username, or local absolute path is durable.

| artifact                              | SHA-256                                                            |
| ------------------------------------- | ------------------------------------------------------------------ |
| `artifacts/junit-callback-owned.log`  | `f446e07078820fce5bc9eb6837686ba7ebf20737553c7a0cea3cda3ffee09238` |
| `artifacts/junit-store-owned.log`     | `c762de953542ce66e831af9f9a77f88f9327df3e5c4336ef627572c6056441c6` |
| `artifacts/junit-parameter-owned.log` | `37e541893bd21d04ed7d6665bc9f076a67616cfb2ba4273a631d56c3cb891a05` |
| `artifacts/junit-synthesis.log`       | `4c93172e81f0063170756fbdbffd97bed47bf9907955e4be4539c7145df23dd3` |
| `artifacts/junit-results.tsv`         | `a9dfe5da2ac84f360eb5b276c649fadf2ae314613f21b0f67c2995427a904d21` |
| `artifacts/junit-cleanup.log`         | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `artifacts/task-7-evidence.sha256`    | `777ad4a8a018a91be352fc4899eec0457617e4ce01509d733e494a72089b3cd8` |
