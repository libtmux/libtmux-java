# 0007. Frame listing rows with a per-process random separator

Status: Accepted

## Context

Python libtmux frames listing fields with U+241E, and carrying that separator
over looked free. It is not: the character has no special meaning to tmux, so
it survives into a window name, and tmux accepts such a name without
complaint. A window renamed to contain U+241E and listed with a three-field
U+241E-separated template reads back as four fields, and the extra field is
not detectable after the fact — a row with the expected field count but a name
that happens to contain the separator shifts every following field by one, so
a pane id can be misread as a name.

## Decision

Split listing rows on a separator token generated fresh for each process
rather than a fixed character. A caller would have to name a window with the
exact token this process generated to reproduce the hazard, which a random
per-process token makes practically unreachable.

## Consequences

Every listing format used by `RowFormat` carries a separator that changes
between runs, so no fixed string appearing in this library's source, tests, or
documentation is itself load-bearing as a separator. The defense composes with
arbitrary tmux object names; no name is rejected to make it safe.
