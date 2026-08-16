# libtmux-jackson

**A filter expression, as a versioned JSON document.**

The core has no dependencies and never will. This module is where Jackson lives,
so that a filter can be stored, sent over a wire, or written by something that is
not a Java program.

`io.github.libtmux:libtmux-jackson` — [on Maven Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-jackson).

> **Alpha.** The API will change without notice. The `libtmux.filter/1` wire
> format is versioned separately and will not change under that name.

## Install

```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.1"))
    implementation("io.github.libtmux:libtmux-jackson")
}
```

## Write one

```java
String json = FilterJson.writeString(Pane_.command().startsWith("nvim"), "pane");

json.contains("libtmux.filter/1");        // → true
json.contains("pane_current_command");    // → true
```

## Read one back

```java
String json = FilterJson.writeString(Pane_.command().startsWith("nvim"), "pane");

FilterExpr<Pane> restored = FilterJson.readString(json, LibTmuxModels.pane());

restored.describe();                      // → pane_current_command starts-with nvim
```

## Use it like any other filter

It is a `Predicate`, so it drops straight into a stream over a capture you already
hold — reading it from JSON changes nothing about how it is applied:

```java
String json = FilterJson.writeString(Pane_.active().isTrue(), "pane");
FilterExpr<Pane> active = FilterJson.readString(json, LibTmuxModels.pane());

server.panes().stream().filter(active).toList().size();   // → 1
```

The document those calls produce:

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

## Why the names look like that

**Field and operator ids are tmux's own format names.** `pane_current_command`,
not `command`; `session_name`, not `name`. Java class names and record component
names are deliberately *not* wire identifiers. That is what lets the same document
mean the same thing to every port of libtmux — and to a model, which is how
[`libtmux-mcp`](../libtmux-mcp/) accepts filters.

## What it refuses, and why

**A field built from a lambda cannot be written.** Only expressions built from a
metamodel have wire identity:

<!-- snippet: throws: SchemaException -->
```java
FilterExpr<Session> mine = Fields.text("session_name", (Session s) -> s.name().toLowerCase())
        .is("demo");

FilterJson.writeString(mine, "session");
```

That field has a caller-chosen name and an accessor nobody else can resolve.
Writing it would produce a document that *looks* like a filter on
`#{session_name}` and answers a different question. Refusing it is what makes this
a format rather than a hope.

**Reading is validated against a model.** A document claiming `pane` cannot be
read as a `FilterExpr<Window>`. Unknown schema versions, models, fields,
relations, operators and node shapes all fail closed, with a `SchemaException`
naming what was wrong.

## The schema

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

## Next

- [Filtering guide](../docs/guide/filtering.md)
- [`libtmux-mcp`](../libtmux-mcp/) — the worked example of a non-Java caller
- [Root README](../README.md)
