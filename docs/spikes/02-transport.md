# Blocking process transport

## Verdict

Use admission-bounded prestarted platform pumps and a gated launch section:

- a caller acquires one process permit before launch, and the pool holds
  exactly two workers per permit, so holding a permit means both drains are
  free;
- starting the child, registering it, and publishing it for destruction happen
  inside a section that `close()` cannot interleave with;
- the caller then blocks in `Process.waitFor`, and that caller may be a virtual
  thread; and
- the request deadline governs admission, the process wait, and the drain
  joins, while destruction runs on its own fixed budgets.

Draining on virtual threads is rejected. Redirecting both channels to
temporary files survives every gate but is not selected.

The architecture's design and its stated reason both hold, and are now measured
rather than assumed. Its acceptance gate does not, and is corrected below.

## Fixed oracle

All contenders implement the same unsealed blocking interface over immutable
values and share one decoder, deadline, argv validator, launch gate,
destruction escalation, and ownership wrapper. Nothing that is not a drain
strategy is allowed to differ. The oracle requires:

- exact channel preservation, nonzero exits as data, launch failure as
  `NOT_DISPATCHED`, timeout and interruption as `UNKNOWN` with the caller's
  interrupt status restored, use after close, idempotent close, literal
  semicolons inside one argv element, and rejection of an embedded NUL before
  launch;
- the decoding vectors executed through a real child process, not against the
  decoder in isolation;
- both pipes flooded past capacity by one more concurrent caller than the
  admission bound;
- `execute()` raced against `close()` across the whole request lifetime;
- no child outliving the call that started it, across success, nonzero exit,
  timeout, close with work in flight, and an interrupt storm during cleanup
  against a child that ignores `SIGTERM`;
- real tmux through a private `-S` socket and an explicitly empty config, with
  a grouped command failing in first, middle, and last position; and
- carrier release under a one-carrier virtual-thread scheduler, both
  cooperatively and against a starved carrier.

Every fixture registers its child with the protocol's locked owned-process
ledger writer before the child can escape the harness.

Two gates carry their own red proofs, because a gate that has never failed is
not evidence. `CheckThenActRedProofTest` shows the launch-gate contract
rejecting the flag it replaced, and `ChildTerminationRedProofTest` shows the
escalation contract rejecting the abandon-on-interrupt shape it replaced.

## Contender results

The three implementations are independent. Each has its own Gradle project,
source, output, and runner scope, and none reads another's state.

### Virtual-thread pipe drains

One virtual thread per pipe, no platform-thread pool, the caller waiting in
`waitFor`.

Result: passes every shared gate under an unconstrained scheduler and under a
one-carrier scheduler whose callers all release their carriers. Hard fail
against a starved carrier: with the single carrier held by an unrelated virtual
thread blocked inside a monitor, the drains never mount, the child's pipe fills,
the child stops instead of exiting, and the request dies as `child exceeded its
deadline`. The same flood in the same task without the carrier hog passes, so
the failure is starvation rather than the fixture. Independently, each of its
drains pins its carrier for the duration of the read, because a process pipe
read is monitor-locked.

### Admission-bounded platform pumps

A fixed pool of two prestarted platform workers per permit, reserved before the
process starts.

Result: passes every shared gate, including the starved carrier.

### Temporary-file redirection

Both channels redirected to private files below a per-transport spool, decoded
after the child exits.

Result: passes every shared gate, including the starved carrier, because it
needs no drain thread at all. Not selected: it writes captured pane content to
the filesystem, it adds disk exhaustion as a failure mode, its spool must be
correct across seven termination paths rather than one pool lifecycle, and it
cannot extend to the streaming control-mode transport the architecture requires
later.

## Ranking and synthesis

The protocol ranks lexicographically. Later criteria cannot compensate for an
earlier loss.

| criterion        | virtual-thread drains                  | platform pumps                   | file redirection                                |
| ---------------- | -------------------------------------- | -------------------------------- | ----------------------------------------------- |
| correctness      | loss: stalls against a starved carrier | pass                             | pass                                            |
| downstream API   | not ranked after correctness loss      | pass                             | pass                                            |
| maintenance      | not ranked                             | win: one pool lifecycle          | loss: spool correctness across seven exit paths |
| performance      | not ranked                             | observed, no measured difference | observed, no measured difference                |
| build complexity | not ranked                             | accepted pool ownership          | observed spool and filesystem coupling          |

Platform pumps and file redirection both survive correctness and expose the
same public API, so maintenance decides. Three floods of 256 KiB per channel
complete in 293 ms to 410 ms across the four implementations, which is no usable
difference at this scale; performance was observed, not ranked.

Synthesis is a fresh implementation of the pump design rather than a copy. It
grafts the launch gate from the close-gate respike, the retrying destruction
escalation, a permit that is returned only once both pump workers have actually
left the pipe, and an `UNKNOWN` outcome for a request whose child `close()`
destroyed. The daemon-policy comparison runs only against the synthesis, on a
child JVM, because the difference is observable only at JVM exit: a
daemon-worker transport lets its JVM exit unclosed, a non-daemon one does not,
and `close()` terminates either. Non-daemon is selected so an unclosed transport
is a visible leak.

## Compatibility vectors

Executed through a real child process against each contender and the synthesis.
`\\xNN` denotes the two literal characters CPython's `backslashreplace` emits,
not the byte.

| behavior                      | child output or argv              | required result                        |
| ----------------------------- | --------------------------------- | -------------------------------------- |
| invalid UTF-8                 | stdout bytes `61 ff`              | stdout `['a\\xff']`                    |
| invalid UTF-8 with valid text | stdout bytes `61 ff 62 0a`        | stdout `['a\\xffb']`                   |
| unterminated final line       | `alpha`                           | stdout `['alpha']`                     |
| repeated final newlines       | `alpha\n\n\n`                     | stdout `['alpha']`                     |
| interior blank stdout line    | `alpha\n\nbeta\n`                 | stdout `['alpha', '', 'beta']`         |
| empty output                  | nothing on either channel         | stdout `[]`, stderr `[]`               |
| blank stderr lines            | stderr `\n\nx\n\ny\n`             | stderr `['x', 'y']`                    |
| CRLF                          | stdout bytes `61 0d 0a 62 0d 0a`  | stdout `['a', 'b']`                    |
| lone CR                       | stdout bytes `61 0d 62`           | stdout `['a', 'b']`                    |
| literal semicolon             | one argv element `left;right`     | stdout `['left;right']`                |
| embedded NUL                  | one argv element containing a NUL | rejected before launch, nothing starts |

The first nine run in the decoding suite; the literal semicolon and the embedded
NUL run in the contract suite, because they are argv properties rather than
decoding ones.

Interior blank stdout lines survive while trailing ones do not, and stderr loses
every empty element wherever it appears. That asymmetry is Python's, and it is
deliberate: it is the observable shape callers already depend on.

## tmux failure-position differential

One private server per probe, three commands in one semicolon group, the failing
`select-pane -t =missing` moved through each position.

| error position | commands written | commands that produced output | exit |
| -------------- | ---------------: | ----------------------------: | ---: |
| first          |                3 |                             0 |    1 |
| middle         |                3 |                             1 |    1 |
| last           |                3 |                             2 |    1 |

The suites assert the output counts and a nonzero exit; the exit value of 1 was
read from a separate direct run of the same three groups. The count a caller
could infer from separators is wrong in two of three positions. An independent
command issued after a failed one still runs, and a literal semicolon inside an
argv element never separates anything. This is the empirical form of the
invariant already recorded from tmux's queue source: a future control-mode
engine must assign `COMPLETE`, `FAILED`, `SKIPPED`, or `UNKNOWN` to every
logical operation instead of waiting for a response block per lexical command.

## Falsification and respikes

### The close protocol was check-then-act

The close-race gate rejected the pump contender with two processes started after
`close()` had already returned. `if (closed) throw` is check-then-act: a caller
reads the flag, is descheduled, and starts its child after close found nothing to
destroy. The worse tail is close landing between the process start and its
publication, which leaves a child undestroyed and a tmux command applied after
the transport was closed. All three contenders shared the defect, so it is a
fault in non-differentiating lifecycle logic rather than a discriminator.

Three minimal launch-gate variants ran against the unmodified close-race oracle:

| variant                                        | close race | gate contract | selected                            |
| ---------------------------------------------- | ---------- | ------------- | ----------------------------------- |
| `ReentrantLock` with a `Condition`             | pass       | pass          | yes                                 |
| one lock-free `AtomicInteger` with a park loop | pass       | pass          | no: polling latency for no gain     |
| intrinsic monitor                              | pass       | pass          | no: blocks carriers by construction |

Because racing can only ever reveal a defect by luck, the respike also added a
deterministic gate contract: a closed gate admits no launch, close is won exactly
once, quiescence waits for an in-flight launch, quiescence is bounded, and an
interrupted wait reports that it did not quiesce. That last property exists
because the first version returned `void`: an interrupted `close()` proceeded to
destroy children while a caller was still between starting its process and
publishing it. A control implementing the original check-then-act flag fails the
contract every run, so the contract is not vacuous.

### The escalation was abandoned by a single interrupt

Independent review found that destruction wrapped `destroy()`, the graceful
wait, `destroyForcibly()`, and the forcible wait in one `try` with a single
`catch (InterruptedException)`. Clearing the caller's interrupt once on entry
does not stop a later interrupt from landing inside the graceful wait, and the
timeout path arrives with no prior interrupt at all. Against a child that ignores
`SIGTERM`, one interrupt skipped forcible destruction entirely, and the request's
own cleanup then dropped the last handle to the child, so `close()` could not
reap it either. All four implementations shared the shape.

Each wait now retries across interruption instead of returning, and the interrupt
is re-applied only once the child is dead. The escalation moved into shared code
for the same reason the decoder and the launch gate did: getting it wrong is not
a strategy difference. A red proof runs both shapes against the same
`SIGTERM`-ignoring child with the interrupt flag set before entry, so the
difference is deterministic rather than timing-dependent.

### Closing looked like tmux dying

A caller parked in `waitFor` when `close()` force-killed its child received an
ordinary result carrying exit 137 and whatever output had arrived — impossible
to distinguish from tmux itself exiting on a signal, and therefore impossible to
know whether the command was applied. The synthesis marks the processes `close()`
destroyed and reports `UNKNOWN`.

### A permit did not prove a worker was free

The design's central invariant is that holding a permit means two pump workers
are free. Returning the permit as soon as the request finished broke it on every
failure path: cancelling a drain does not unblock a pipe read, and a cancelled
`FutureTask` reports `isDone()` while its worker is still inside `readAllBytes`.
The next caller's drains were then queued behind stuck workers and its own child
stalled. The synthesis stops cancelling drains, tracks worker completion
directly, and withholds the permit when a pump cannot be reclaimed, so a wedged
pump costs visible capacity instead of silently oversubscribing the pool.

### The specified pinning gate cannot fail

`jdk.VirtualThreadPinned` is emitted only when a virtual thread parks while
pinned. A calibration probe on the pinned toolchain established what the gate can
see:

| construct on a virtual thread     | pin event recorded |
| --------------------------------- | ------------------ |
| park inside `synchronized`        | yes                |
| `Object.wait()` in `synchronized` | no                 |
| `Process.waitFor()`               | no                 |
| pipe `readAllBytes()`             | no                 |
| `Process.onExit().get()`          | no                 |

All three contenders and the synthesis record zero pin events, at a 64-frame
stack depth. That is a true statement about parking while pinned and says nothing
about carrier release. It is not merely incomplete: a process pipe read is
monitor-locked, so the virtual-thread drain contender pins a carrier on every
read it performs, and this gate reports zero for it. Reported alone it would have
been a false clean bill of health.

Carrier release is therefore measured directly, under
`jdk.virtualThreadScheduler.parallelism=1` and `maxPoolSize=1`. Four virtual
threads on one carrier:

| case                                 | elapsed |
| ------------------------------------ | ------- |
| `Thread.sleep` inside `synchronized` | 4018 ms |
| plain `Thread.sleep`                 | 1000 ms |
| `Process.waitFor()`                  | 1018 ms |
| `Process.onExit().get()`             | 1011 ms |

The harness detects serialization, so overlap is a result rather than an ignored
constraint. Four callers with a 2000 ms child complete in 2107 ms to 2145 ms
against an 8000 ms serial floor for every implementation.

### Cooperative carrier release is not enough

A library does not own the virtual-thread scheduler. Any unrelated code in the
same JVM that blocks inside a `synchronized` block holds a carrier for the
duration, and JDK 21 has no unpinned monitor blocking; that only arrives in
[JEP 491](https://openjdk.org/jeps/491). A transport whose drains are virtual
threads then cannot run them.

Holding the single carrier with one such thread and flooding both pipes from a
platform-thread caller kills the virtual-thread drain contender and leaves the
other two untouched. This is the gate that decides the bakeoff, and neither the
pinning recording nor the cooperative one-carrier measurement reaches it.

### The ledger writer could not register a short-lived child

The protocol requires every child to be registered before it can escape the
harness, by reading its start token from procfs. A JVM child is slow enough to
start that this always succeeded; a tmux client exits in about two milliseconds,
and the writer failed the whole tmux differential.

The token helper now reports an absent process distinctly, retrying its read
against a fresh existence probe so a reap between the two is not a hard failure,
and the writer treats that as a successful no-op. Recording a row with an unknown
token would be worse than recording nothing, because a recycled PID could later
read as this spike's process still being live, and a process that has already
been reaped is exactly what the ledger exists to prove cannot leak. A live child
still records; an already-reaped one appends no row; the ledger stays valid.
Ledger failures also raise their own type rather than `IllegalStateException`,
which the transport contract reserves for "transport is closed".

## Architecture correction

The architecture states that on Unix-like JDK 21 implementations process streams
subclass monitor-locked buffered streams, that blocking a virtual thread in those
reads can pin its carrier, and that the caller may block in `waitFor`. All three
claims hold. On the pinned Temurin `21.0.11+10` toolchain, whose `release` file
names source revision `254494a` in `adoptium/jdk21u`:

- [`ProcessImpl.waitFor`](https://github.com/adoptium/jdk21u/blob/254494a/src/java.base/unix/classes/java/lang/ProcessImpl.java)
  takes a `ReentrantLock` and waits on a `Condition`, not a monitor, so a
  virtual-thread caller blocking there releases its carrier;
- [`BufferedInputStream`](https://github.com/adoptium/jdk21u/blob/254494a/src/java.base/share/classes/java/io/BufferedInputStream.java)
  installs its
  [`InternalLock`](https://github.com/adoptium/jdk21u/blob/254494a/src/java.base/share/classes/jdk/internal/misc/InternalLock.java)
  only when it is not subclassed, and
  [`ProcessPipeInputStream`](https://github.com/adoptium/jdk21u/blob/254494a/src/java.base/unix/classes/java/lang/ProcessImpl.java)
  is a subclass, so every process pipe read runs under `synchronized (this)`.

So the pump pool is required, and for two independent reasons rather than one:
pipe reads pin the thread performing them, and the library cannot assume the
application leaves it a usable carrier at all. The caller may indeed be a virtual
thread and block in `waitFor`, which the architecture already allows and which is
now measured rather than assumed.

What does not survive is the acceptance gate. The architecture makes zero
transport-attributed `jdk.VirtualThreadPinned` events under floods, timeouts, and
interruption the condition for accepting the design. That gate cannot fail: the
event is emitted only when a virtual thread parks while pinned, and a
monitor-locked pipe read never parks. The virtual-thread drain contender pins a
carrier on every read it performs and still records zero. The gate should be
restated as carrier-starvation survival — flood both pipes while an unrelated
thread holds the only carrier — which is the measurement that actually separated
the contenders, with the pin recording kept as supporting evidence rather than as
the falsifier.

## Result record

Each row's digests are listed in full; a row that spans several artifacts cites
all of them.

| gate                        | oracle                                                                         | virtual-thread drains           | platform pumps       | file redirection     | synthesis           | verdict                                  | artifact digests                                                                                                                                                                                                                                                                                                                                                                      |
| --------------------------- | ------------------------------------------------------------------------------ | ------------------------------- | -------------------- | -------------------- | ------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| contract and classification | channels, exits, launch failure, timeout, interruption, close, redaction       | pass, 12 cases                  | pass, 12 cases       | pass, 12 cases       | pass, 12 cases      | pass                                     | `sha256:7408f64445050801d34c00995bcb5ca2f593c2232419352daa719f4aa90eac62`                                                                                                                                                                                                                                                                                                             |
| decoding differential       | nine decoding vectors through a real child                                     | pass, 9 cases                   | pass, 9 cases        | pass, 9 cases        | pass, 9 cases       | pass                                     | `sha256:7408f64445050801d34c00995bcb5ca2f593c2232419352daa719f4aa90eac62`                                                                                                                                                                                                                                                                                                             |
| close race                  | dispatch certainty and no launch after close, swept across the window          | pass after the gate graft       | hard fail, then pass | pass after the graft | pass                | shared defect; launch gate grafted       | `sha256:8ad25164d106959f082ecc0f861549fcb453374c685156ad49ff33073e42b209`, `sha256:7408f64445050801d34c00995bcb5ca2f593c2232419352daa719f4aa90eac62`                                                                                                                                                                                                                                  |
| launch-gate contract        | five deterministic properties plus a red proof against the replaced flag       | not applicable; shared gate     | not applicable       | not applicable       | not applicable      | lock gate selected                       | `sha256:1fcca71401aff4e976a09e086be5ec13411d82204f7ba33859414ee97f9197f6`                                                                                                                                                                                                                                                                                                             |
| destruction escalation      | red proof: abandon-on-interrupt leaves the child, retrying reaps it            | not applicable; shared code     | not applicable       | not applicable       | not applicable      | retrying escalation grafted              | `sha256:1fcca71401aff4e976a09e086be5ec13411d82204f7ba33859414ee97f9197f6`                                                                                                                                                                                                                                                                                                             |
| process liveness            | success, nonzero exit, close in flight, and an interrupt storm during cleanup  | pass, 3 cases                   | pass, 3 cases        | pass, 3 cases        | pass, 3 cases       | pass                                     | `sha256:7408f64445050801d34c00995bcb5ca2f593c2232419352daa719f4aa90eac62`                                                                                                                                                                                                                                                                                                             |
| close outcome               | a child destroyed by close reports `UNKNOWN`, not a signal exit                | not applicable; synthesis graft | not applicable       | not applicable       | pass                | grafted into the synthesis only          | `sha256:7408f64445050801d34c00995bcb5ca2f593c2232419352daa719f4aa90eac62`                                                                                                                                                                                                                                                                                                             |
| tmux differential           | first, middle, and last grouped error against a private server                 | pass, 8 cases                   | pass, 8 cases        | pass, 8 cases        | pass, 8 cases       | pass                                     | `sha256:7408f64445050801d34c00995bcb5ca2f593c2232419352daa719f4aa90eac62`                                                                                                                                                                                                                                                                                                             |
| pinning                     | zero-threshold `jdk.VirtualThreadPinned`, printed at 64-frame stack depth      | pass, 0 events                  | pass, 0 events       | pass, 0 events       | pass, 0 events      | passes for all; gate proven non-decisive | `sha256:b93e7c1b40d645dc536e3ac7a62383c7d6e084174f9b5fdeb11db339100e1d10`, `sha256:bcceb0d937df5e1eb6018eb9553a2b955fb2b84d0fe966cf193c26a954a0dbe7`                                                                                                                                                                                                                                  |
| pinning calibration         | which constructs the pin event can observe                                     | not applicable; harness probe   | not applicable       | not applicable       | not applicable      | only park-while-pinned is visible        | `sha256:85df4b5db97a32da3a3a40f2f4b22f74cc223170b9c6127ba62e5a9885911813`                                                                                                                                                                                                                                                                                                             |
| carrier release             | four callers, one carrier, 8000 ms serial floor                                | pass, 2145 ms                   | pass, 2128 ms        | pass, 2123 ms        | pass, 2107 ms       | pass                                     | `sha256:aa64897882a2b1089ca334c6038680fb460cb49e9c4b30c7313ba6c80c571b81`, `sha256:feb876dd95f813379c41c09f6e5c4b64c497731b003d07ec0bdfdea09d9f1b2d`, `sha256:0a27f7c41484f0439c584b9938281e62e0afbc0b6e15c13330c747dd45437446`, `sha256:2e2f32de912d8d5f9f3b53008cbe2e11b5a9a37008dd0903285f3daea080496d`, `sha256:08bb92aea5d9cafce1e48412616254a396499431e9503025b078553722e38203` |
| carrier starvation          | flood both pipes while an unrelated thread holds the only carrier              | hard fail, deadline exceeded    | pass                 | pass                 | pass                | rejects virtual-thread drains            | `sha256:bc003832d50123cc4a2d0a0271fab184c215ebc7a5939da21f74cc95b0eecbae`, `sha256:4f4b3727e8556ef31e8ffdfca47b6d343507e197a5c4977d826862f722e86537`, `sha256:6abae0611b547a9c697e7af0d2c7a17906dc177c14ce9c0c560187d145e5a7ee`, `sha256:b179949243830d67c3265eab8375beba3fb80f2ec974a5a7f8f7aad151e9a3a6`, `sha256:d09253700f35e5ec9bd185f15b2948d4b469efb63a990dc238aea1fdf3c00545` |
| worker policy               | a daemon-worker JVM exits unclosed, a non-daemon one does not, close ends both | not applicable; no pool         | not applicable       | not applicable       | pass, both policies | non-daemon selected                      | `sha256:7408f64445050801d34c00995bcb5ca2f593c2232419352daa719f4aa90eac62`                                                                                                                                                                                                                                                                                                             |
| process cleanup             | locked owned-process verifier over 1770 ledger rows                            | pass                            | pass                 | pass                 | pass                | pass                                     | `sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`                                                                                                                                                                                                                                                                                                             |

The cleanup verifier produces no output on success, so its row carries the digest
of the empty file.

## Not covered

- The pinning gate's search for transport-attributed frames is reported as
  "zero pin events" only. With no event recorded for any implementation, a
  frame filter over the printed recordings has nothing to reject and proves
  nothing further.
- Real tmux ran only at the host's 3.7b. The version matrix belongs to the test
  harness spike, not this one.
- Persistent control mode was not implemented; only the failure-position
  evidence that constrains its future design was gathered.

## Commands

The three contender suites and the synthesis ran together against real tmux:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        transport-suites \
        reuse \
        product \
        "$root/transport" \
        -PspikeRoot="$root" \
        --rerun-tasks \
        :virtual-pipes:test \
        :bounded-pumps:test \
        :file-redirect:test \
        :synthesis:test \
        > "$root/artifacts/contender-suites.log" 2>&1'
```

The launch-gate contract, its red proof, and the escalation red proof:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        transport-close-gate-respike \
        reuse \
        product \
        "$root/transport" \
        -PspikeRoot="$root" \
        --rerun-tasks \
        :oracle:test \
        > "$root/artifacts/gate-contract.log" 2>&1'
```

Each implementation's carrier gates ran in its own scope:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        transport-carrier-virtual-pipes \
        reuse \
        product \
        "$root/transport" \
        -PspikeRoot="$root" \
        --rerun-tasks \
        :virtual-pipes:carrierTest \
        > "$root/artifacts/carrier-virtual-pipes.log" 2>&1'
```

The pinning gate covered all four recordings:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    mkdir -p "$root/artifacts/jfr"; \
    "$root/artifacts/run-gradle.sh" \
        transport-jfr \
        reuse \
        product \
        "$root/transport" \
        -PspikeRoot="$root" \
        --rerun-tasks \
        -PjfrDir="$root/artifacts/jfr" \
        :virtual-pipes:jfrTest \
        :bounded-pumps:jfrTest \
        :file-redirect:jfrTest \
        :synthesis:jfrTest \
        > "$root/artifacts/jfr/trace-pinned.log" 2>&1'
```

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    counts="$root/artifacts/jfr/pin-counts.tsv"; \
    : > "$counts"; \
    for recording in "$root"/artifacts/jfr/*.jfr; do \
        printed="$recording.txt"; \
        mise x java@temurin-21.0.11+10.0.LTS -- \
            jfr print --stack-depth 64 --events jdk.VirtualThreadPinned "$recording" > "$printed"; \
        count="$(rg -c "^jdk\\.VirtualThreadPinned \\{" "$printed" || :)"; \
        printf "%s\t%s\n" "$(basename "$recording")" "${count:-0}" >> "$counts"; \
    done'
```

The process gate used:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/verify-no-owned-processes.sh" \
        "$root/artifacts/owned-processes.tsv" \
        "$root/artifacts/process-start-token.sh" \
        "$root/artifacts/validate-owned-process-ledger.sh" \
        > "$root/artifacts/process-cleanup.log" 2>&1'
```

## Evidence digests

Artifact labels are relative to the disposable root. No nonce, PID, hostname,
username, or local absolute path is durable.

| artifact                                                 | SHA-256                                                            |
| -------------------------------------------------------- | ------------------------------------------------------------------ |
| `artifacts/contender-suites.log`                         | `7408f64445050801d34c00995bcb5ca2f593c2232419352daa719f4aa90eac62` |
| `artifacts/gate-contract.log`                            | `1fcca71401aff4e976a09e086be5ec13411d82204f7ba33859414ee97f9197f6` |
| `artifacts/carrier-virtual-pipes.log`                    | `4f4b3727e8556ef31e8ffdfca47b6d343507e197a5c4977d826862f722e86537` |
| `artifacts/carrier-bounded-pumps.log`                    | `6abae0611b547a9c697e7af0d2c7a17906dc177c14ce9c0c560187d145e5a7ee` |
| `artifacts/carrier-file-redirect.log`                    | `b179949243830d67c3265eab8375beba3fb80f2ec974a5a7f8f7aad151e9a3a6` |
| `artifacts/carrier-synthesis.log`                        | `d09253700f35e5ec9bd185f15b2948d4b469efb63a990dc238aea1fdf3c00545` |
| `artifacts/carrier/virtual-pipes.tsv`                    | `feb876dd95f813379c41c09f6e5c4b64c497731b003d07ec0bdfdea09d9f1b2d` |
| `artifacts/carrier/bounded-pumps.tsv`                    | `0a27f7c41484f0439c584b9938281e62e0afbc0b6e15c13330c747dd45437446` |
| `artifacts/carrier/file-redirect.tsv`                    | `2e2f32de912d8d5f9f3b53008cbe2e11b5a9a37008dd0903285f3daea080496d` |
| `artifacts/carrier/synthesis.tsv`                        | `08bb92aea5d9cafce1e48412616254a396499431e9503025b078553722e38203` |
| `artifacts/jfr/pin-counts.tsv`                           | `b93e7c1b40d645dc536e3ac7a62383c7d6e084174f9b5fdeb11db339100e1d10` |
| `artifacts/jfr/trace-pinned.log`                         | `bcceb0d937df5e1eb6018eb9553a2b955fb2b84d0fe966cf193c26a954a0dbe7` |
| `artifacts/pin-probe/probe.jfr.txt`                      | `85df4b5db97a32da3a3a40f2f4b22f74cc223170b9c6127ba62e5a9885911813` |
| `artifacts/pin-probe/carrier-calibration.log`            | `aa64897882a2b1089ca334c6038680fb460cb49e9c4b30c7313ba6c80c571b81` |
| `artifacts/respikes/close-gate-root-cause.xml`           | `8ad25164d106959f082ecc0f861549fcb453374c685156ad49ff33073e42b209` |
| `artifacts/respikes/hostile-scheduler-virtual-pipes.xml` | `bc003832d50123cc4a2d0a0271fab184c215ebc7a5939da21f74cc95b0eecbae` |
| `artifacts/process-cleanup.log`                          | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `artifacts/task-4-evidence.sha256`                       | `bf011cb1152e888ba8c671b0924fddb5c956ce6696c122c9c8530f0936b37d6d` |
| pinned Temurin `lib/src.zip`                             | `ce97da39a01c328ed65e35b945a6ec9c02b88566cb5fa831ecd3cb19ebf62286` |

## Persistent control mode

Persistent control mode stays outside the default contenders. The
failure-position differential above is the reason: after the first error tmux
removes the rest of the group, so a client that expects one response block per
lexical command waits for blocks that cannot arrive. The minimum gate for a
future control transport is independent one-line requests and exact `COMPLETE`,
`FAILED`, `SKIPPED`, or `UNKNOWN` attribution for every logical operation, with a
bounded `UNKNOWN` on a lost or malformed guard.
