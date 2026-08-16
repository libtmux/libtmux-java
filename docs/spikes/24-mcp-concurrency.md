# Whether a blocking tool call stalls an MCP connection

## Verdict

Serve with `McpServer.sync`. The SDK runs a synchronous handler on
`Schedulers.boundedElastic` rather than on the thread reading the transport, so a
tool that blocks for a minute costs a pool thread and nothing else.

Writing the same handler reactively is where it goes wrong. A `Mono` that blocks
pins the single reactor thread and the connection serves nothing at all.

## Measured

MCP Java SDK 2.0.0 over stdio, 20 cores. One `slow` call blocking for six seconds,
then twenty `fast` calls sent behind it down the same connection.

| contender | how `slow` is written | fast calls served during it | `slow` took |
| --------- | --------------------- | --------------------------: | ----------: |
| **`sync`** | plain blocking handler | **20 of 20** | 6.23 s |
| `async-block` | `Mono.fromCallable` that blocks | 0 of 20 | 9.39 s |
| `async-nonblock` | `Mono.delay` | 20 of 20 | 6.66 s |
| `async-elastic` | blocking, `subscribeOn(boundedElastic)` | 20 of 20 | 6.62 s |

The naive reactive contender is worse than doing nothing: it serves none of the
interleaved calls *and* stretches the blocking call itself by three seconds,
because the reactor thread it pinned is also the one reading the transport.

## How many at once

Concurrent six-second blocking calls, same connection:

| sent | returned | wall clock |
| ---: | -------: | ---------- |
| 8 | 8 | one wave at 6.05 s |
| 40 | 40 | one wave at 6.05 s |
| 220 | 159 | one wave at 6.05 s; 61 never returned |

`boundedElastic` defaults to ten threads per core, so the ceiling here is around
150 concurrent blocking handlers. Past it, requests are lost rather than queued.

No agent issues 150 concurrent calls on one stdio pipe, so this is a bound worth
knowing rather than one worth designing around. It is the reason the wait tools
are capped at two minutes rather than left open: a ceiling on how long a handler
holds its thread is also a ceiling on how many can pile up.

## What this settles

- The wait tools may block. That was the open question, and the answer is that
  bounding them protects the agent's turn, not the connection.
- `McpSyncServerExchange` still carries `progressNotification`, `loggingNotification`,
  `createElicitation` and `createMessage`, so nothing about staying synchronous
  gives up a protocol feature.

## Commands

The probe and its oracle are throwaway; what they measured is above.

```console
$ java -cp "$CP" ConcurrencySpike sync-block
```
