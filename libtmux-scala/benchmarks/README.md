# Scala mode benchmarks

`ModeBenchmarks` measures equivalent, live-tmux workloads and writes raw JSON
samples. It is an outer-tier measurement tool. It is not run by unit,
integration, documentation, or compatibility checks.

Create an unused socket under the Java-port development root and a minimal tmux
configuration, then run the program with its explicit tmux binary, socket,
configuration and JSON output paths. The optional final arguments select warmups
and samples; the defaults are three warmups and nine samples.

## Running it

The sbt build resolves the Java artifacts this module depends on from a local
stage, and sbt caches that resolution, so a version staged earlier can shadow
one just rebuilt. Clean before staging:

```console
$ ./libtmux-scala/sbtw clean
```

Stage the Java artifacts the Scala build consumes:

```console
$ ./libtmux-scala/scripts/stage-java.sh
```

Then run the benchmark itself, from the repository root, against an explicit
tmux and an owned socket:

```console
$ ./libtmux-scala/sbtw \
    "benchmarks/run /path/to/tmux /tmp/libtmux-java-dev/scala-bench/s /tmp/libtmux-java-dev/scala-bench/empty.conf /tmp/libtmux-java-dev/scala-bench/results.json"
```

[`results/`](results/) holds one committed run's raw JSON, named for the date
and the tmux it ran against. Regenerate rather than hand-edit it.

The benchmark creates and removes one three-pane session. It rejects an existing
socket and validates pane IDs, capture ordering and completion markers before
recording data. Each result retains raw elapsed and current-thread allocation
samples, client/process/worker counts, p50/p95/p99, and throughput. Pushed output
and polling use the same terminal marker. Control subscription creation and
cancellation/release are measured separately.

The output compares Java process reads, Scala blocking reads, Cats serial and
bounded reads, Cats batches and chains, admitted control requests, polling and
pushed output. It does not compare unmatched operations or claim that pushed
bytes are captured terminal state. Measurements describe the recorded host and
revision; they are not a capacity, p99, or cross-machine performance claim.
