<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Java">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-jackson

**A filter expression, as a versioned JSON document.**

</div>

[`FilterJson`][filter-json] writes a [`FilterExpr`][filterexpr] as a
`libtmux.filter/1` document and reads it back, so a filter can be stored, sent
over a wire, or written by something that is not a Java program.

`io.github.libtmux:libtmux-jackson` — [on Maven
Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-jackson).
API reference: [`FilterJson`][filter-json] on libtmux.org for the current trunk,
[javadoc.io][javadoc] for a released version.

> Releases are numbered `0.1.N`, and each release increments `N`. The API is
> not settled: any release may change or remove exported identifiers, and the
> changelog marks each break. Pin an exact version to avoid one.
>
> The `libtmux.filter/1` wire format is versioned separately and will not
> change under that name.

**Contents** — [Quick start](#quick-start) · [The document](#the-document) ·
[Why the names look like that](#why-the-names-look-like-that) ·
[What it refuses](#what-it-refuses) · [Support](#support-and-status)

## Quick start

The core has no dependencies and never will. This module is where Jackson
lives:

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.19"))
    implementation("io.github.libtmux:libtmux-jackson")
}
```

### [`FilterJson.writeString`][filter-json]: an expression as a document

It takes the expression and the model that gives its fields their identity.
[`LibTmuxModels.pane()`][models] is the model for a [`Pane`][pane] filter:

```java
String json = FilterJson.writeString(
        Pane_.command().startsWith("nvim"), LibTmuxModels.pane());

json.contains("libtmux.filter/1");        // → true
json.contains("pane_current_command");    // → true
```

### [`FilterJson.readString`][filter-json]: a document as an expression

```java
String json = FilterJson.writeString(
        Pane_.command().startsWith("nvim"), LibTmuxModels.pane());

FilterExpr<Pane> restored = FilterJson.readString(json, LibTmuxModels.pane());

restored.describe();                      // → pane_current_command starts-with nvim
```

### [`FilterExpr`][filterexpr]: use it like any other filter

It is a `Predicate`, so it drops straight into a stream over a capture you
already hold. Reading it from JSON changes nothing about how it is applied:

```java
// Given: Server server
String json = FilterJson.writeString(Pane_.active().isTrue(), LibTmuxModels.pane());
FilterExpr<Pane> active = FilterJson.readString(json, LibTmuxModels.pane());

server.panes().stream().filter(active).toList().size();   // → 1
```

## The document

The first example produces this:

```json
{
  "schema": "libtmux.filter/1",
  "model": "pane",
  "expr": {
    "node": "compare",
    "field": "pane_current_command",
    "op": "starts_with",
    "value": "nvim"
  }
}
```

[`filter-expr-v1.schema.json`](src/main/resources/io/github/libtmux/jackson/filter-expr-v1.schema.json)
ships inside the jar, so a consumer can validate without fetching anything.

| node | shape |
| --- | --- |
| `and` / `or` | `{"node": "and", "operands": [...]}` — empty `and` is true, empty `or` is false |
| `not` | `{"node": "not", "operand": {...}}` |
| `compare` | `{"node": "compare", "field": ..., "op": ..., "value": ...}` |
| `to_many` | `{"node": "to_many", "relation": ..., "quantifier": "any"\|"all"\|"none", "predicate": {...}}` |
| `to_one` | `{"node": "to_one", "relation": ..., "predicate": {...}}` |

Operators: `equals`, `not_equals`, `contains`, `starts_with`, `ends_with`,
`matches`, `less_than`, `at_most`, `greater_than`, `at_least`, `in`.

## Why the names look like that

- **Field and operator ids are tmux's own format names.** The id is
  `pane_current_command` where the Java accessor is `command`, and
  `session_name` where it is `name`. Java class names and record component
  names are never wire identifiers.
- **`matches` is Java's.** Its pattern syntax and numeric flags are those of
  `java.util.regex.Pattern`, so a non-Java consumer must reproduce those
  semantics or reject that operator.
- **A non-Java caller already exists.** [`libtmux-mcp`][mcp] accepts these
  documents directly.

## What it refuses

### [`SchemaException`][schema-exception]: an undeclared field cannot be written

Only the exact handles the supplied model declares have wire identity:

<!-- snippet: throws: SchemaException -->
```java
FilterExpr<Session> mine = Fields.text("session_name", (Session s) -> s.name().toLowerCase())
        .is("demo");

FilterJson.writeString(mine, LibTmuxModels.session());
```

It has a declared field's name and a different accessor. Writing it
would produce a document that looks like a filter on `#{session_name}` and
answers a different question, so writing it throws `SchemaException`.

### Writing and reading are validated against a model

- A document claiming `pane` cannot be read as a `FilterExpr` of a
  [`Window`][window].
- An expression cannot borrow the name of a field or relation its model did not
  declare.
- Unknown schema versions, models, fields, relations, operators, properties and
  node shapes all fail closed, with a `SchemaException` naming what was wrong.

## What it leaves out

- **Dependencies in the core.** Jackson lives here, so [`libtmux`][libtmux]
  never carries it.
- **Java names on the wire.** Class and record component names are not
  identifiers in a document.

## Support and status

- **Releases.** Releases are numbered `0.1.N`, and each release increments
  `N`. The API is not settled: any release may change or remove exported
  identifiers, and the changelog marks each break. Pin an exact version to
  avoid one.
  The `libtmux.filter/1` format is versioned separately.
- **Versioned fail-closed reading.** An unknown schema version is refused, not
  guessed at.

## Documentation

- [Filtering guide](../docs/guide/filtering.md)
- [`libtmux-mcp`][mcp] — the worked example of a non-Java caller
- [Root README](../README.md)

[filter-json]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-jackson-filterjson-filterjson/
[filterexpr]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-query-filterexpr-filterexpr/
[javadoc]: https://javadoc.io/doc/io.github.libtmux/libtmux-jackson
[libtmux]: ../libtmux/README.md
[mcp]: ../libtmux-mcp/README.md
[models]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-jackson-libtmuxmodels-libtmuxmodels/
[pane]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-pane-pane/
[schema-exception]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-jackson-schemaexception-schemaexception/
[window]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-window-window/
