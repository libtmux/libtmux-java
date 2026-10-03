# Common API programs

Each program has its own imports, entry point, client lifetime, and error
handling. The manifest connects a complete source file and its expected output
to the public API declarations it demonstrates. Java, Kotlin, Scala Direct,
and Scala Cats have separate programs and consumer dependencies.

The programs cover connecting to a server, listing sessions, windows and panes,
creating sessions and windows, typed queries, and sending and capturing input.
Run the executable contract tests from the repository root:

```console
$ ./gradlew :examples:test --tests '*ApiProgramsTest'
```

## Server ownership

[`run.sh`](run.sh) creates a private tmux server under
`/tmp/libtmux-java-dev/`. It starts `work-one` with an `editor` window containing
two panes and a `logs` window containing one pane. It also starts `work-two`
with one `editor` pane. Every fixture pane runs `/bin/cat`.

Each program receives the tmux executable, socket path, and configuration file
as three arguments. It opens an ordinary client connected to that server.
Closing the client releases its transport; the launcher owns server shutdown.
The launcher stops the server on success or failure and waits for its liveness
probe to fail before removing the directory. A shutdown failure retains the
directory and returns a nonzero status.

Lists returned by a server read capture its state at that read. A session's
windows and a window's panes are captured properties. `NewWindow` reads the
session again before printing its changed windows. The Cats programs execute
their effects through `IOApp`; constructing an `IO` alone does not run it.

## Minimal consumer

Use JDK 25, Git, and tmux 3.2a through 3.7c on Linux. The source checkout
supplies the Gradle wrapper and library dependencies. Save the chosen variant's
`settings.gradle.kts` and `build.gradle.kts`, the shared `gradle.properties` and
`run.sh`, and one complete program under `src/main/java`, `src/main/kotlin`,
or `src/main/scala`. The manifest records those destination paths and each
program's main class.

For example, prepare the Java capture program in an empty directory:

```console
$ git clone https://github.com/libtmux/libtmux-java libtmux-source && \
  cp libtmux-source/examples/api/java/*.gradle.kts . && \
  cp libtmux-source/examples/api/gradle.properties . && \
  cp libtmux-source/examples/api/run.sh . && \
  mkdir -p src/main/java && \
  cp libtmux-source/examples/src/main/java/io/github/libtmux/examples/api/java/Capture.java \
    src/main/java/
```

Build the consumer and run it against the launcher's private server:

```console
$ ./libtmux-source/gradlew --project-dir . --console=plain --quiet \
    installDist -PexampleMain=io.github.libtmux.examples.api.java.Capture && \
  sh run.sh build/install/api-example/bin/api-example
```

The program waits for each printed marker before capturing and checking it:

```text
api-keys
api-line
```

Set `TMUX_BIN` to an absolute executable path to choose a particular tmux
release. Each invocation starts a fresh fixture, so none of the programs
requires an earlier example to run first.
