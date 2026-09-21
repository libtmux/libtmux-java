# Scala mode benchmarks

`ModeBenchmarks` measures equivalent, live-tmux workloads and writes raw JSON
samples. It is an outer-tier measurement tool. It is not run by unit,
integration, documentation, or compatibility checks.

Create an unused socket under the Java-port development root and a minimal tmux
configuration, then run the program with its explicit tmux binary, socket,
configuration and JSON output paths. The optional final arguments select warmups
and samples; the defaults are three warmups and nine samples.

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
