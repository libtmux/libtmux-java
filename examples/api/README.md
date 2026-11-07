# Common API programs

Each program has its own imports, entry point, client lifetime, and error
handling. The manifest connects a complete source file and its expected output
to the public API declarations it demonstrates. Java, Kotlin, Scala Direct,
Scala Cats, and Clojure have separate programs and consumer dependencies.

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

The program starts without `TMUX`, `TMUX_PANE` or `LIBTMUX_SOCKET*`, and with a
`TMUX_TMPDIR` of its own, so a client that names no socket cannot reach another
server. The run fails, after stopping it, when the program leaves a server
running there.

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

Use JDK 25, Git, and tmux 3.3a through 3.7c on Linux. The source checkout
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
release. Set `API_RUN_ROOT` to move the runner's directories from
`/tmp/libtmux-java-dev`; the default socket path under it must fit in 100
bytes. Each invocation starts a fresh fixture, so none of the programs requires
an earlier example to run first.

## Clojure programs

The Clojure variant uses Tools.deps and a `mainNamespace` entry point in the
manifest. Each file imports its public namespaces, closes its client, and
prints the manifest's expected output. The same private-server launcher owns
setup and teardown. No program depends on a repository test helper.

| Task | Complete program |
| --- | --- |
| Connect and capture | [connect.clj][connect] |
| List sessions | [list_sessions.clj][sessions] |
| Traverse session windows | [list_windows.clj][windows] |
| Traverse window panes | [list_panes.clj][panes] |
| Create a session | [new_session.clj][new-session] |
| Create and refresh a window | [new_window.clj][new-window] |
| Compare native and Java filters | [query.clj][query] |
| Send input and verify capture | [capture.clj][capture] |

The Clojure package has not shipped. Rehearse a consumer from this checkout
with JDK 25+, [Clojure CLI][cli], and tmux on PATH. Prepare a separate consumer
folder:

```console
$ mkdir -p build/clojure-api-consumer/src/main && \
    cp examples/api/clojure/deps.edn examples/api/run.sh \
      build/clojure-api-consumer/ && \
    cp -R examples/src/main/clojure build/clojure-api-consumer/src/main/
```

Install the two local artifacts in that consumer's isolated Maven repository:

```console
$ ./gradlew :libtmux:publishToMavenLocal \
    :libtmux-clojure:publishToMavenLocal \
    -Dmaven.repo.local="$PWD/build/clojure-api-consumer/m2"
```

Run a full program through the private-server launcher:

```console
$ cd build/clojure-api-consumer && \
    sh run.sh clojure \
      -M \
      -m libtmux.examples.api.capture
```

It prints `api-keys` and `api-line` on separate lines after verifying actual
shell output. The consumer classpath uses the installed library JARs and its
copied program sources. It does not use this checkout's library source paths.
Choose another namespace from the table to run a different task.

The native integration suite discovers every Clojure program in the manifest,
checks its public API targets, loads it without reflection warnings, and
executes its own CLI entry point. Both supported tmux endpoints run this gate:

```console
$ ./gradlew :integration-tests:clojureIntegrationTest \
    -PlibtmuxClojureTestNamespaces=libtmux.standalone-examples-test
```

[capture]: ../src/main/clojure/libtmux/examples/api/capture.clj
[cli]: https://clojure.org/guides/install_clojure
[connect]: ../src/main/clojure/libtmux/examples/api/connect.clj
[new-session]: ../src/main/clojure/libtmux/examples/api/new_session.clj
[new-window]: ../src/main/clojure/libtmux/examples/api/new_window.clj
[panes]: ../src/main/clojure/libtmux/examples/api/list_panes.clj
[query]: ../src/main/clojure/libtmux/examples/api/query.clj
[sessions]: ../src/main/clojure/libtmux/examples/api/list_sessions.clj
[windows]: ../src/main/clojure/libtmux/examples/api/list_windows.clj
