# benchmarks

**Measures what an operation costs. Not published.**

This benchmark uses `ProcessTransport`, which starts a tmux process for each
dispatch. It measures how many dispatches each operation takes and regenerates
[`docs/benchmarks/operation-costs.md`](../docs/benchmarks/operation-costs.md) from a real
run.

```console
$ ./gradlew operationBenchmark -PlibtmuxTmux=/path/to/tmux
```

The table is **never hand-edited**, and it stamps which tmux answered, because a
table without its conditions is a claim rather than a measurement. Read the
dispatch counts; the milliseconds are one machine at one moment.

Four things are measured, and each exists because the library made a choice that
costs something: collapsing round trips with `batch()` and `chain()`, capturing
the hierarchy, fencing a handle's command against a replaced server, and reading
a scope's options.

Its own module, and excluded from `check`: a benchmark starts a tmux server per
case and takes seconds. Keeping it inside a published artifact's tests made that
a matter of remembering a tag rather than a matter of where the code lives.

## The Scala facades' modes

`ModeBenchmarks` runs equivalent live-tmux workloads through each way the Scala
facades reach tmux: Java process reads, direct-style blocking reads, Cats
serial and bounded reads, batches and chains, admitted control requests,
polling and pushed output. It writes raw JSON samples — elapsed time,
allocation, client, process and worker counts, p50/p95/p99 and throughput —
wherever it is told:

```console
$ ./gradlew :benchmarks:scalaModeBenchmark \
    -PlibtmuxTmux=/path/to/tmux \
    -PscalaBenchSocket=/tmp/libtmux-java-dev/bench/s \
    -PscalaBenchConfig=/tmp/libtmux-java-dev/bench/empty.conf \
    -PscalaBenchOut=/tmp/libtmux-java-dev/bench/modes.json
```

It refuses an existing socket and validates pane ids, capture order and
completion markers before recording anything. A run describes the machine and
revision it ran on, so none is committed.

## Next

- [Batching and chaining](../docs/guide/batching-and-chaining.md) · [the measured table](../docs/benchmarks/operation-costs.md)
