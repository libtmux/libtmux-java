# benchmarks

**Measures what an operation costs. Not published.**

This benchmark uses `ProcessTransport`, which starts a tmux process for each
dispatch. It measures how many dispatches each operation takes and regenerates
[`docs/benchmarks/operations.md`](../docs/benchmarks/operations.md) from a real
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

## Next

- [Batching and chaining](../docs/guide/batching-and-chaining.md) · [the measured table](../docs/benchmarks/operations.md)
