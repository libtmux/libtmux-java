# Installed consumers

These independent sbt and Gradle builds resolve the staged Maven coordinates.
Each build has separate core and Cats projects. Their test applications use
the existing `OwnedTmux` test utility and the staged Java fixture in test scope;
neither fixture belongs to the application runtime graph.

First generate the documentation inventory and stage the Scala artifacts.
Select JDK 25 or 27 in `JAVA_HOME` and the explicit tmux 3.7c executable in
`TMUX_TEST_BINARY`. Run one consumer compiler/JDK/OS cell through both tools:

```console
$ python3 libtmux-scala/scripts/verify-consumers.py \
    --scala-stage libtmux-scala/target/staging \
    --java-stage libtmux-scala/target/java-repository \
    --version 0.0.1-alpha.12-scala-dev.1 \
    --java-version 0.0.1-alpha.14 \
    --docs-exports libtmux-scala/examples/target/scala-3.9.0/resource_managed/test \
    --scala-version 3.9.0 \
    --jdk "$JAVA_HOME" \
    --tmux "$TMUX_TEST_BINARY" \
    --output libtmux-scala/target/consumers/linux-jdk25-scala3.9.0
```

Repeat with each supported JDK and both operating systems. Use a
separate output directory for each cell. The evidence JSON lists all 8
build-tool cells; only the two executed cells can pass. It records the
selected JVM, compiler jars, tmux version, commands, timing, input hashes,
runtime coordinates and staged jar hashes. Cleanup witnesses are printed only
after the owned fixture has closed.

Before building, the runner checks all four binary/source/Scaladoc/POM sets.
It rejects extra publications, bytecode above or below JDK 25, incorrect POM
metadata or dependency pins, source bytes that differ between archives, and
broken source links or line anchors. `--artifacts-only` performs these checks
without launching a consumer build.

The sbt projects consume the exported core/Cats installation settings verbatim.
The runner checks their hashes against the generated documentation inventory.
A third sbt project consumes the direct-Java installation export and uses the
matching released Java fixture. Its resolved Java coordinate must remain
identical on the runtime and test execution classpaths. Every application
runtime dependency must also retain its coordinate and bytes when test
fixtures are added. An unhandled build fence anywhere in the repository fails
the runner instead of silently losing coverage.

The sbt launcher must already be cached and match the producer's checked
checksum. Gradle uses the repository's pinned wrapper with the isolated build
directory. Dependency preparation belongs to the outer test loop; `--offline`
replays cached dependencies. Neither build declares Maven local. Missing staged
coordinates fail preflight even when a resolver has cached an earlier copy.

Run the inventory gate's negative controls:

```console
$ python3 libtmux-scala/consumers/test_inventory.py
```

These reject optional core dependencies, mixed Scala binary families, an
incompatible Scala standard library and a substituted staged jar. Scala 3's
unsuffixed Scala 2.13 standard library and Scala 3.9's matching unsuffixed
Scala 3 standard library are permitted.
