# 0017. Kotlin reads every core collection as read-only

Status: Accepted

## Context

Without an annotation, every Java collection the core returns reaches Kotlin
as the platform type `(Mutable)List` — `add` compiles and throws at runtime,
since the core's own collections reject mutation. Measured directly: a Java
method returning a plain `List` lets `add` compile under Kotlin 2.1 and 2.4
regardless of whether `kotlin-annotations-jvm` is on the caller's classpath,
while a method whose return is marked `@kotlin.annotations.jvm.ReadOnly`
resolves as `List`, with `add` unresolved, under both. `javac`, `scalac`, and
Scala 3 all compile either form without warning, so the annotation costs
nothing outside Kotlin.

The annotation is `compileOnly` and class-retained: no caller needs the
annotation jar on its runtime classpath, and nothing loads it at run time. It
names no Java module, so it does not appear in the core's module descriptor;
the core module compiles with `--add-reads
io.github.libtmux=ALL-UNNAMED` to read it during compilation. JetBrains'
newer `@Unmodifiable` annotation was rejected because Kotlin only recognizes
it from 2.4 onward, and the oldest Kotlin this project supports would still
see a plain mutable list under it.

## Decision

Mark every public method in the core that returns a `List`, `Set`, `Map`, or
other collection with `@kotlin.annotations.jvm.ReadOnly`.

## Consequences

`ReadOnlyCollectionsTest` reads the compiled classes and fails, naming the
method, if a public collection-returning method loses the mark.
`compileOldestConsumer` compiles a Kotlin 2.1 consumer with `-Werror` against
overloads whose `Mutable` forms are deprecated at `ERROR`, so a caller that
mistakenly reads a core collection as mutable fails to build rather than
failing at run time. A new public method returning a collection must carry the
annotation from the start; the conformance test exists precisely so this is
not left to review.
