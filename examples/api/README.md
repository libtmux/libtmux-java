# Common API programs

Each program has its own imports, entry point, client lifetime, and error
handling. The [JVM catalog](manifest.json) and
[native Clojure catalog](clojure/manifest.json) connect complete source files
and expected output to the public APIs they demonstrate. Each language has
separate programs and consumer dependencies.

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

The Clojure catalog uses Tools.deps and a `mainNamespace` entry point for each
program. Each file imports its public namespaces, closes its client, and
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

The capability programs use the same fixture and consumer:

| Task | Complete program |
| --- | --- |
| Build a layout and move a window | [workspace.clj][workspace] |
| Bound capture work and cancel a wait | [capture_concurrently.clj][concurrent] |
| Observe output on an owned worker | [watch_pane_output.clj][watch] |
| Read a command's actual exit status | [run_a_command.clj][run] |
| Stop a core.async consumer | [observe_with_async.clj][observe] |

Workspace saves tmux's serialized layout after a split, reapplies it, renames
the window, and moves it to another session. It acquires the new placement
after movement; the earlier observation retains its original placement.

Control programs require tmux 3.7+. On older supported tmux they check refusal
before client attachment and print `requires tmux 3.7+`. WatchPaneOutput runs
observation on an owned wait worker; gaps or termination fail the pending
marker read. RunACommand calls Java `Pane.run` directly and checks completion
before reading an exit status. Keep that blocking Java call outside STM and
`go` blocks.

The Clojure package has not shipped. Rehearse a consumer from this checkout
with JDK 25+, [Clojure CLI][cli], and tmux on PATH. Prepare a separate
consumer folder:

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
$ (cd build/clojure-api-consumer && \
    sh run.sh clojure \
      -M \
      -m libtmux.examples.api.capture)
```

It prints `api-keys` and `api-line` on separate lines after verifying actual
shell output. The consumer classpath uses the installed library JARs and its
copied program sources. It does not use this checkout's library source paths.
Choose another namespace from the table to run a different task.

The core.async program uses the optional artifact. Install it into the same
isolated repository:

```console
$ ./gradlew :libtmux-clojure-core-async:publishToMavenLocal \
    -Dmaven.repo.local="$PWD/build/clojure-api-consumer/m2"
```

From the repository root, select the consumer's optional dependency alias:

```console
$ (cd build/clojure-api-consumer && \
    sh run.sh clojure \
      -M:core-async \
      -m libtmux.examples.api.observe-with-async)
```

It closes the events consumer, waits for cleanup, and verifies that the
borrowed control connection still accepts requests. Blocking channel reads run
on the caller; the adapter owns its observation worker. No I/O runs in `go`.

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
[concurrent]: ../src/main/clojure/libtmux/examples/api/capture_concurrently.clj
[new-session]: ../src/main/clojure/libtmux/examples/api/new_session.clj
[new-window]: ../src/main/clojure/libtmux/examples/api/new_window.clj
[observe]: ../src/main/clojure/libtmux/examples/api/observe_with_async.clj
[panes]: ../src/main/clojure/libtmux/examples/api/list_panes.clj
[query]: ../src/main/clojure/libtmux/examples/api/query.clj
[run]: ../src/main/clojure/libtmux/examples/api/run_a_command.clj
[sessions]: ../src/main/clojure/libtmux/examples/api/list_sessions.clj
[windows]: ../src/main/clojure/libtmux/examples/api/list_windows.clj
[watch]: ../src/main/clojure/libtmux/examples/api/watch_pane_output.clj
[workspace]: ../src/main/clojure/libtmux/examples/api/workspace.clj
