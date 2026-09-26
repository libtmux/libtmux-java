# 0004. Query expressions are a sealed tree with a hand-written metamodel

Status: Accepted

## Context

An expression needs to be printable, storable, and usable as a predicate.
`FilterExpr<T>` is a sealed interface extending `Predicate<T>`, implemented by
records (`And`, `Or`, `Not`, `Compare`, `ToMany`, `ToOne`); extending
`Predicate` is what lets `stream().filter(expr)` work with no adapter, and
being a sealed tree of records is what lets the same value be printed,
serialized, or compiled into a tmux `-f` filter. A lambda gives only the first
of those. Every consumer switches over the tree exhaustively with no
`default`, so a new node kind breaks compilation at each site that must learn
about it.

Field handles (`Fields.text`, `Fields.number`, `Fields.flag`, `Fields.toMany`,
`Fields.toOne`) are typed by `FieldKind` explicitly, carried beside the
accessor rather than inferred from the operand's runtime class — a number
field's operand merely happens to box to `Integer`, and reading the kind off
that accident silently selects tmux's lexical comparison where the caller
meant arithmetic. A to-many relation is reachable only through `any`, `all`,
or `none` on `Selections`, so an unquantified relation is not itself a filter;
`javax.tools.JavaCompiler` invoked on in-memory sources is what proves that
rejection, because code that fails to compile cannot be written into ordinary
test sources.

A code generator for the field metamodel was considered and rejected. The
metamodel is small, explicit domain code, and hand-writing it costs nothing a
library actually pays for: no annotation-processor lifecycle, no generated
sources to debug, no incremental-build edge cases, and code that reads plainly
under review. A generator was reopened as a question only while the field
model itself was uncertain; it no longer is.

## Decision

Represent an expression as a sealed `FilterExpr<T>` tree of records
implementing `Predicate<T>`. Mint field handles by hand through `Fields`,
carrying `FieldKind` explicitly rather than deriving it from the accessor's
return type. Do not generate the metamodel.

## Consequences

Adding an operator or node kind is a compile error at every exhaustive switch
until it is handled, not a silent gap. Hand-writing invites three mistakes a
generator would have made impossible — a duplicate identifier, a kind that
does not match the field it exposes, and declared coverage drifting from what
an entity actually exposes — so a reflective conformance test reads what a
metamodel class declares and requires all three to hold; a hand-kept inventory
of expected handles would drift the same way the metamodel itself would.
Vacuous quantification follows the standard reading: `all` over an empty
relation is true, `any` is false, and `none` is true, so a session with no
windows does not fail "all windows are zoomed".
