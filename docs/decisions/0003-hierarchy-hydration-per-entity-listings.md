# 0003. Hydrate the hierarchy with one listing per entity kind

Status: Accepted

## Context

Three capture plans were measured against the same fixture — two sessions,
four window contexts, six panes, one window linked into a second session —
across every released tmux lane. Direct scoped listings (one `list-windows`
per session, one `list-panes` per window context) cost a process launch per
window and grow with the topology: nine commands for four window contexts in
the fixture alone. A single hierarchical `list-panes -a` recovers sessions and
windows by taking each the first time a row mentions it; it is the cheapest in
commands, but its correctness rests on an ordering tmux documents no guarantee
for, and it repeats each session's fields once per pane, a measured 1.47x byte
increase over per-entity listings. Four independent server-wide listings —
sessions, windows, panes, clients — tie the other plans on correctness and
expose the same API, so maintenance decided: nothing here is derived from an
undocumented coincidence.

tmux expands an unknown token to the empty string, identically to a known
token whose value is empty, so absence is version knowledge rather than wire
knowledge. `pane_floating_flag` is empty through tmux 3.6 and `0` from 3.7 —
measured across the matrix, not inferred — so a captured value that depends on
server version is optional and reported as absent rather than collapsed to
`false`.

## Decision

Capture one server-wide listing per entity kind — sessions, windows, panes,
clients — reading each entity from the command that owns it, so ordering and
membership stay tmux's decision rather than the client's. Relations are keyed
on the window context (session plus index), not the window id, because a
window linked into two sessions is one window and two positions. Server
identity (`pid`, `socket_path`, `start_time`, `version`) rides the session
rows instead of a fifth identity query, which keeps the count at four commands
regardless of topology size.

## Consequences

A hierarchy snapshot costs four commands whatever the server holds, fenced
against a stale incarnation (see
[0013](0013-server-identity-is-pid-and-start-time.md)) in the same batch. A
version-gated field is modeled as optional rather than defaulted, so a caller
cannot mistake "this server does not report it" for "this server reports it as
false".
