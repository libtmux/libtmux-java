# 0012. Creation options are a builder-built spec, applied by three overloads

Status: Accepted

## Context

`split-window` takes seventeen options in the Python sibling, and
`new-window`/`new-session` share most of them; `split-window` alone gained six
flags in tmux 3.7. Four call shapes were written out and scored on call-site
size and on what each let a caller write down that should not compile: a spec
record with a setter-per-field builder (the shape `java.net.http.HttpRequest`,
OkHttp, and the AWS SDK settled on); a fluent chain ending in a terminal verb;
sealed option values passed as varargs; and a builder combining directional
and size verbs with a record-like immutable spec.

A record was rejected for the spec type itself, independent of the four call
shapes: a record's canonical constructor is public API, and a command whose
flag set has already grown once inside the supported range would force that
constructor to change again. A varargs-of-sealed-values shape was the most
compact at the call site but needed roughly triple the public types of the
alternatives, resolved contradictions by argument order rather than by the
type system, and is not a shape any widely used Java library offers. A fluent
chain reads well but is bound to the pane it started from, so describing a
spec once and applying it to two different panes needs two separately built
chains — and a stray `pane.splitting().toRight()` that is never terminated
compiles and looks exactly like a split that happened, which no other
candidate allows.

## Decision

A creation option is a `*Spec` class (`SplitSpec`, `WindowSpec`,
`SessionSpec`), immutable once built, constructed by a `Builder` whose methods
are named verbs (`toRight()`, `percent(30)`, `running(command)`) rather than
setters taking an enum or wrapper value. Each creation call is offered as
three overloads: no argument, a lambda configuring the builder inline, or a
prebuilt spec that may be reused across calls and panes.

## Consequences

A caller writes `pane.split(s -> s.toRight().percent(30))` for one-off use or
builds a `SplitSpec` once to apply identically to several panes. A range
check that the type system cannot express cheaply — such as `percent(400)` —
stays a runtime `IllegalArgumentException` from the spec's compact constructor
rather than a compile-time constraint. Adding a flag tmux introduces later is
a new builder method, not a change to a public constructor.
