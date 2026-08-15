# Filtering

An expression is a value that happens to be a predicate. It drops into a stream
unchanged, and — unlike a lambda — it can also be printed, stored, or translated
into another system's filter language.

```java
List<Window> editors = server.windows().stream()
        .filter(Window_.name().startsWith("edit"))
        .toList();

Window only = Selections.exactlyOne(editors);
```

## Typed fields

`Session_`, `Window_`, `Pane_` and `Client_` expose one handle per field, and
each handle offers only the operators its type supports. Asking a flag to start
with a string is a compile error, not a runtime cast failure.

Field ids are tmux's own format names — `pane_current_command`, `window_name` —
which is what keeps an expression meaningful to something that is not this
library.

## Composition and relations

`and`, `or` and `negate` compose expressions. Relations quantify:

```java
var busy = Window_.panes().any(Pane_.command().startsWith("nv"));
```

`any`, `all` and `none` cross a to-many relation; `is` crosses a to-one. `all`
over an empty relation is true — a session with no windows does not fail "all
windows are zoomed".

## Saying what it is

```java
assertTrue(busy.describe().contains("pane_current_command"));
```

This is the half a lambda cannot do, and the reason the AST is a sealed tree of
records rather than a captured function.

## Cardinality

`Selections.exactlyOne` raises distinct exceptions for none and for several,
because those are different bugs in a caller's code. `Selections.oneOrEmpty`
returns an `Optional` but still raises on several. `findFirst` stays on `Stream`
where it already is.

## Filtering never asks tmux

An expression evaluates locally over a capture you already hold. Filtering issues
no commands, so a stream pipeline costs nothing and cannot observe a
half-changed server.

Expressions retain enough structure for a future compiler to lower them to tmux's
own `-f` predicate, but no release does that today, and no such compiler would
change what snapshot filtering means.

## Writing an expression down

The optional `libtmux-jackson` module gives an expression a versioned wire form.
This snippet is exercised by `FilterJsonTest` rather than `ExamplesTest`, since
the core suite does not depend on Jackson:

```java
String json = FilterJson.writeString(Pane_.command().startsWith("nv"), "pane");
FilterExpr<Pane> restored = FilterJson.readString(json, LibTmuxModels.pane());
```

Only expressions built from a metamodel can be written. A field built from a
lambda has a caller-chosen name and an accessor nobody else can resolve, so it
has no wire identity, and refusing it is what makes this a format rather than a
hope.

Reading is validated against a model: a document claiming `pane` cannot be read
as a `FilterExpr<Window>`. Unknown schema versions, models, fields, relations,
operators and node shapes all fail closed.

## Who the wire form is actually for

Field and operator identifiers are tmux's own format names — `pane_current_command`,
not anything Java calls a field. So the document means the same thing to every
port of libtmux, and to a caller that is not a Java program at all.

`libtmux-mcp` is the worked example. Its `tmux_list_panes` tool takes an optional
`filter`, which is one of these documents:

```json
{"schema": "libtmux.filter/1", "model": "pane",
 "expr": {"node": "compare", "field": "pane_current_command",
          "op": "starts_with", "value": "nvim"}}
```

A model cannot write Java, so this is the only way it can say what it wants
narrowed. What it gets back costs the same one capture the unfiltered listing
would have, because the filter runs over what that capture returned.

## Taking a filter in your own API

Prefer accepting the entities and letting the caller filter:

```java
public List<PaneSummary> describe(Collection<Pane> panes) { … }
```

rather than accepting the expression and filtering inside. A method taking a
`FilterExpr` reads as though tmux did the selecting, and it does not. Reserve
`FilterExpr` parameters for code that inspects or translates an expression —
serializing it, or lowering it — which is what `FilterJson` does.
