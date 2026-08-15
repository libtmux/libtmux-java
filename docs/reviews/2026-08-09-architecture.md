# Java architecture review

## Scope

Three independent reviews examined the Java architecture before executable
spikes:

- objective and Python-parity coverage
- Effective Java and downstream public-API design
- Java 21 runtime, process, JUnit, Gradle, and publication behavior

The review covered the complete architecture specification, including build
topology, immutable handles and snapshots, blocking transport, query AST and
generated metamodel, JSON schema, real-tmux fixtures, compatibility, consumer
journeys, and prototype deletion.

## Resolved findings

The reviewed design now requires:

- final immutable builders where record construction would expose invalid
  states
- explicit transport ownership and server identity realms
- dependency-free core publication with JSpecify as build-only `compileOnly`
- redacted diagnostics and outcome certainty for process failures
- contextual linked-window identity and pure immutable snapshot relations
- exhaustive named query nodes, exact collectors, compile-fail metamodel tests,
  and a versioned language-neutral schema
- `AutoCloseable` JUnit store state, a dedicated socket-path value, and cleanup
  that never signals an unowned PID
- executable contender, synthesis, consumer, reproducibility, and deletion
  gates before the clean rewrite

## Verdict

No unresolved architecture blocker remains before disposable spikes. All
implementation selections remain provisional where the specification names an
executable falsifier. A failed hard gate reopens this review and blocks the
clean-rewrite handoff.
