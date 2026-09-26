# Fixture operation catalog

`operation-catalog.json` here is a small, hand-authored catalog shaped exactly like the DX
redesign's `operation-catalog.json` schema (one record per `@Operation` method: owner, name, kind,
parameters, return type, Javadoc summary), covering a representative slice of
`io.github.libtmux`'s real `@Operation` methods (`Client`, `Pane`, `Server`, `Session`, `Window`;
every generatable kind; a varargs and an `Optional`/`List`-returning method each). `ScalaCodegen`
(`project/ScalaCodegen.scala`) reads it to generate the direct-style extensions and Cats forwards
this build compiles against.

Once the javadoc Doclet ships `operation-catalog.json` inside the `libtmux` jar
(`META-INF/io.github.libtmux/operation-catalog.json`), point `operationCatalog` in `build.sbt` at
the staged jar's extracted copy instead of this file. `ScalaCodegen` itself does not change: it
already reads the full schema, this fixture is just a small slice of it.
