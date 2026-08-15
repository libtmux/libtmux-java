# Engine-ops seam study

The comparison checkout was inspected read-only at authenticated revision
[`5b2c88e`](https://github.com/tmux-python/libtmux/commit/5b2c88e57e6e15422a8e845ef5d55fe7a606c315),
which is reachable from its tracked `engine-ops` branch. No comparison-checkout
file was changed.

## Boundary map

| Boundary         | Comparison evidence                                                                                                                                                                                                                                                                                                             | Java spike contract                                                                                                                | Preserved or corrected                                                                                                     |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Request/result   | [`CommandRequest` validates argv and NUL; `CommandResult` carries channels and return code](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/engines/base.py#L247-L330)                                                                                                                             | Public immutable `CommandRequest` and `CommandResult`; argv elements remain separate; nonzero tmux exits are data.                 | Preserve the value boundary; add endpoint, timeout, safe diagnostics, and explicit outcome certainty.                      |
| Engine/transport | [`TmuxEngine.run` and `run_batch`](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/engines/base.py#L397-L414) and [`ServerConnection`](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/engines/connection.py#L79-L135)                                                | Public, unsealed, thread-safe `TmuxTransport` with one blocking `execute`; `ServerEndpoint` owns socket identity independently.    | Preserve substitutability; do not expose registry globals or Python duck typing.                                           |
| Capability       | [`SupportsTmuxVersion`](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/engines/base.py#L433-L447)                                                                                                                                                                                                 | Immutable `TmuxCapabilities` resolved once per endpoint/transport realm and carried by snapshots.                                  | Preserve optional version knowledge; make unsupported versus unknown explicit rather than “assume latest” in strict paths. |
| Target           | [closed target union and rendering](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/ops/_types.py#L364-L381)                                                                                                                                                                                       | Closed typed target values for session, window, pane, client, name, index, special, and deferred plan references.                  | Preserve sigils and validation; keep deferred references out of immediate transport requests.                              |
| Outcome          | [complete, failed, skipped, and unknown status](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/ops/_types.py#L33-L46)                                                                                                                                                                             | Every logical operation receives exactly one `COMPLETE`, `FAILED`, `SKIPPED`, or `UNKNOWN` outcome.                                | Preserve `SKIPPED`; it is mandatory for grouped-command aborts rather than synthesized empty results.                      |
| Snapshot         | [`ServerSnapshot` assembles a tree from pane rows](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/models/snapshots.py#L339-L375)                                                                                                                                                                  | Strict immutable `ServerSnapshot` with stable IDs, encounter order, winlink relationships, endpoint realm, and capability capture. | Preserve pure immutable reads; strengthen linked-window identity and failure semantics.                                    |
| Query            | [`PaneQuery` is immutable and evaluates snapshots](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/query.py#L78-L130)                                                                                                                                                                              | Public sealed `FilterExpr<T>`, generated typed fields, and pure evaluation against an owning snapshot.                             | Preserve purity and chainability; replace string lookup names and the legacy `QueryList` evaluator.                        |
| JSON schema      | [`schema_for_type` uses optional reflection/introspection](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/mcp/schema.py#L22-L75) and [`ToolDescriptor` separates metadata from adapters](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/mcp/descriptor.py#L32-L125) | Optional Jackson module serializes the closed AST and descriptors through explicit tagged schemas; core has no Jackson dependency. | Preserve framework-independent descriptors; reject best-effort reflection and default typing.                              |
| Serialization    | [targets and operations serialize as tagged plain data](https://github.com/tmux-python/libtmux/blob/5b2c88e/src/libtmux/experimental/ops/serialize.py#L35-L123)                                                                                                                                                                 | Explicit stable discriminator and field IDs for supported AST nodes; unknown types fail closed.                                    | Preserve tagged data and fail-closed reconstruction; do not add Java object serialization.                                 |

## Grouped-control evidence

The first-, middle-, and last-error probe described in the tmux protocol study
is the seam's decisive correction. One three-operation group produced one, two,
and three response blocks respectively as the error moved right. The required
outcomes are:

| Error position | Required outcomes                |
| -------------- | -------------------------------- |
| first          | `FAILED`, `SKIPPED`, `SKIPPED`   |
| middle         | `COMPLETE`, `FAILED`, `SKIPPED`  |
| last           | `COMPLETE`, `COMPLETE`, `FAILED` |

A future engine cannot allocate pending completions from the number of lexical
semicolons and then wait for all of them. It must model the group dependency,
finish removed operations as skipped, and reserve unknown for loss of evidence.

## Public consumer probe

This downstream source is the executable acceptance shape for the clean Java
implementation. It imports only exported packages. The later consumer project
must compile and run it without module patching, reflection, or access to an
`internal` package.

```java
package probe;

import io.github.libtmux.Pane;
import io.github.libtmux.ServerSnapshot;
import io.github.libtmux.format.CanonicalQueryFixture;
import io.github.libtmux.query.FilterExpr;
import io.github.libtmux.query.Pane_;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.OperationOutcome;
import io.github.libtmux.transport.TmuxTransport;
import java.util.List;

public final class EngineSeamProbe {
    private EngineSeamProbe() {}

    private static final class SecondTransport implements TmuxTransport {
        @Override
        public CommandResult execute(CommandRequest request) {
            return CommandResult.completed(request, List.of("probe"), List.of(), 0);
        }

        @Override
        public void close() {}
    }

    public static void main(String[] args) {
        try (TmuxTransport transport = new SecondTransport()) {
            CommandRequest request = CommandRequest.of("display-message", "-p", "probe");
            if (transport.execute(request).outcome() != OperationOutcome.COMPLETE) {
                throw new AssertionError("second transport did not complete");
            }
        }

        ServerSnapshot snapshot = CanonicalQueryFixture.linkedWindow();
        FilterExpr<Pane> active = Pane_.active().isTrue();
        List<Pane> panes = snapshot.queryPanes(active);
        if (panes.size() != 1 || !panes.getFirst().paneId().equals("%1")) {
            throw new AssertionError("canonical query fixture changed");
        }

        CommandResult skipped = CommandResult.skipped(
            CommandRequest.of("display-message", "later"),
            "earlier grouped command failed"
        );
        if (skipped.outcome() != OperationOutcome.SKIPPED) {
            throw new AssertionError("skipped outcome was lost");
        }
    }
}
```

The names and factories in this probe are contract requirements established by
the spike, not claims that production Java source already exists. Its consumer
gate simultaneously proves external transport substitution, canonical query
evaluation, and explicit skipped-operation representation.
