# Filtering

An expression is a value that happens to be a predicate. It drops into a stream
unchanged, and — unlike a lambda — it can also be printed, stored, or translated
into another system's filter language.

```java
// Given: Server server
server.newSession("build").newWindow("editor");

List<Window> editors = server.windows().stream()
        .filter(Window_.name().startsWith("edit"))
        .toList();

Window only = Selections.exactlyOne(editors);

editors.size();                      // → 1
only.name();                         // → editor
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
var busy = Window_.panes().any(Pane_.command().startsWith("nv"));

busy.describe();                     // → panes any (pane_current_command starts-with nv)
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

Expressions retain enough structure to lower a safe subset to tmux's own `-f`
predicate. `TmuxFilters.format` does that lowering. A relation, or an operand
containing `,`, `#`, `{`, `}`, or `:`, stays empty, and the caller filters the
capture it already holds. `Server.session(String)` and `Server.pane(PaneId)`
use a targeted listing when the name or id is safe to put in a format, and a
whole-server capture otherwise. `sessions(FilterExpr)`, `windows(FilterExpr)`,
and `panes(FilterExpr)` send a safe expression as `list-sessions -f`,
`list-windows -f`, or `list-panes -f`, and still apply the expression to what
comes back. A relation, or an expression tmux cannot apply, reads the whole
server.
Filtering a list already in hand still issues no commands.

## Writing an expression down

The optional `libtmux-jackson` module gives an expression a versioned wire form.
This snippet is exercised by `FilterJsonTest` rather than `ExamplesTest`, since
the core suite does not depend on Jackson:

```java
String json = FilterJson.writeString(
        Pane_.command().startsWith("nv"), LibTmuxModels.pane());
FilterExpr<Pane> restored = FilterJson.readString(json, LibTmuxModels.pane());

restored.describe();                 // → pane_current_command starts-with nv
```

Only exact handles declared by the supplied model can be written. A field may
borrow a declared name while carrying a different accessor, so the name alone
has no wire identity. Refusing it is what makes this a format rather than a hope.

Writing and reading are validated against a model: a document claiming `pane`
cannot be read as a `FilterExpr<Window>`, and an expression cannot borrow a field
or relation name its model did not declare. Unknown schema versions, models,
fields, relations, operators, properties and node shapes all fail closed.

## Who the wire form is actually for

Field and operator identifiers are tmux's own format names — `pane_current_command`,
not anything Java calls a field. That makes most of the document independent of
Java names. The `matches` operand is the exception: its syntax and numeric flags
are those of `java.util.regex.Pattern`. A non-Java consumer must reproduce those
semantics or reject that operator.

An application that stores a pane predicate is the worked example. The wire
form is one of these documents:

```json
{"schema": "libtmux.filter/1", "model": "pane",
 "expr": {"node": "compare", "field": "pane_current_command",
          "op": "starts_with", "value": "nvim"}}
```

Java applications can read that document with the matching `FilterModel` and
apply it to a captured hierarchy. `libtmux-mcp` deliberately does not accept
this open expression format: `list_panes` returns bounded typed metadata for a
client to filter, while `search_panes` searches only rendered terminal text.

## Filters that arrive as strings

A CLI flag, a config file or a stored query carries a filter as untrusted text.
Read it with the wire form above: `FilterJson.readString` checks the document
against a model and refuses any field, relation or operator the model did not
declare, so a wrong name fails closed rather than being guessed. The core takes
no string form of its own, which keeps the typed expression the only thing the
rest of the library accepts.

## Taking a filter in your own API

Prefer accepting the entities and letting the caller filter:

<!-- snippet: skip: a signature, shown for its shape rather than to be run -->
```java
public List<PaneSummary> describe(Collection<Pane> panes) { … }
```

rather than accepting the expression and filtering inside. A method taking a
`FilterExpr` reads as though tmux did the selecting, and it does not. Reserve
`FilterExpr` parameters for code that inspects or translates an expression —
serializing it, or lowering it — which is what `FilterJson` does.
