# 0016. libtmux-mcp serves tools synchronously

Status: Accepted

## Context

The MCP Java SDK runs a synchronous tool handler on `Schedulers.boundedElastic`
rather than on the thread reading the transport, so a handler that blocks for
minutes costs a pool thread and nothing else. Measured over stdio with one
six-second blocking call and twenty fast calls sent behind it on the same
connection: the synchronous server served all twenty during the block, while a
naive reactive handler (`Mono.fromCallable` that blocks) served none of them
*and* stretched the blocking call itself by three seconds, because the single
reactor thread it pinned was also the one reading the transport. Wrapping a
blocking handler in `subscribeOn(boundedElastic)` matched the synchronous
server's behavior exactly, which is the same pool the synchronous server
already uses without a caller having to ask for it.

`boundedElastic` defaults to ten threads per core, so concurrent long-blocking
handlers are served up to roughly 150 at once on this project's target
hardware; past that ceiling, requests are lost rather than queued. No agent
issues anywhere near that many concurrent calls on one stdio pipe, so this is
a bound worth knowing rather than one worth designing around — it is why the
wait tools cap at two minutes: a ceiling on how long a handler holds its
thread is also a ceiling on how many can pile up behind it.

## Decision

Serve `libtmux-mcp` with `McpServer.sync`, and write tool handlers as plain
blocking code. Cap a blocking wait tool's duration (`Waits.CEILING`, two
minutes) rather than leaving it open-ended.

## Consequences

A tool handler may block for as long as its own contract allows without
starving other calls on the same connection, and nothing about staying
synchronous gives up a protocol feature — progress notifications, logging,
elicitation, and sampling remain available through
`McpSyncServerExchange`. Writing a handler as reactive code that blocks inside
it would silently reintroduce the connection-wide stall this decision avoids.
