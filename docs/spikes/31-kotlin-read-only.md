# Kotlin reads a marked Java collection as read-only

## Verdict

Every public method in the core that returns a `List`, `Set`, `Map`, or other
collection carries `@kotlin.annotations.jvm.ReadOnly`. Kotlin reads it from the
class file and types the result `List`, `Set`, or `Map`, so `add` is not on it.
The annotation is `compileOnly` and CLASS-retained: no caller needs the jar, and
nothing loads it.

Without it, every Java collection reached Kotlin as the platform type
`(Mutable)List`: `add` compiled and threw at runtime.

## Measured

A Java class with one annotated and one plain `List` method, compiled against
`kotlin-annotations-jvm`, then read by each consumer compiler without that jar on
its path:

| compiler | flags | annotated method | plain method |
| --- | --- | --- | --- |
| Kotlin 2.1.21 | none | `add` unresolved; `List` | `add` compiles |
| Kotlin 2.1.21, jar on path | none | `add` unresolved; `List` | `add` compiles |
| javac 26 | `-Xlint:all -Werror` | compiles, no warning | compiles |
| scalac 2.13.18 | `-Werror -Xlint` | compiles, no warning | compiles |
| Scala 3.3.8 | `-Werror` | compiles, no warning | compiles |

Kotlin 2.1.21 and 2.4.10 both list `kotlin.annotations.jvm.ReadOnly` among the
annotation names they recognise. Only 2.4 also knows JetBrains'
`@Unmodifiable`, which is why that one was not used: the oldest Kotlin this
project supports would still see a mutable list.

The jar names no module, so the core's descriptor does not require it; the core
compiles with `--add-reads io.github.libtmux=ALL-UNNAMED` to read it from the
classpath.

## What checks it

`ReadOnlyCollectionsTest` reads the compiled classes and lists each public method
of a public type that returns a collection without the mark. Removing the mark
from `Server.sessions()` fails it with that method's name.

`compileOldestConsumer` compiles `Consumer.kt` with Kotlin 2.1 and `-Werror`.
It passes a core list and a core map to an overload pair in which the
`MutableList` and `MutableMap` forms are deprecated at `ERROR`, so a collection
read as mutable fails the build. `ListsTest` does the same with the build's own
compiler.
