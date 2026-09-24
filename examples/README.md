# examples

**Whole runnable programs. Not published.**

The README and the guides carry snippets. These are the other thing: complete
programs with a `main`, short enough to read in one go and real enough to run.

**Every one is executed by this module's own suite against a real tmux**, so an
example that stopped working fails the build. Examples rot silently otherwise —
they are the part of a project nobody compiles and everybody reads first.

| example | shows |
| --- | --- |
| [`BuildAWorkspace`](src/main/java/io/github/libtmux/examples/BuildAWorkspace.java) | making a session, a window, a split, and choosing a layout |
| [`FindPanesRunning`](src/main/java/io/github/libtmux/examples/FindPanesRunning.java) | typed filters over a capture, without asking tmux twice |
| [`WatchPaneOutput`](src/main/java/io/github/libtmux/examples/WatchPaneOutput.java) | control mode: reading `%output` as tmux pushes it |
| [`WatchWhatChanges`](src/main/java/io/github/libtmux/examples/WatchWhatChanges.java) | control mode: typed notifications as the server changes |
| [`RunACommand`](src/main/java/io/github/libtmux/examples/RunACommand.java) | running a command to its exit status, not reading the screen |
| [`ServeTmuxOverMcp`](src/main/java/io/github/libtmux/examples/ServeTmuxOverMcp.java) | serving this tmux to a model over MCP |
| [`WatchWithFlow`](src/main/kotlin/io/github/libtmux/examples/WatchWithFlow.kt) | Kotlin: pushed output as a `Flow`, and cancelling a wait |

## Run one

```console
$ ./gradlew :examples:test
```

Or against a tmux server of your own:

```console
$ ./gradlew :examples:compileJava
```

```console
$ java -cp examples/build/classes/java/main:libtmux/build/classes/java/main \
    io.github.libtmux.examples.FindPanesRunning /tmp/libtmux-java-dev/demo/s nvim
```

The suite launches each program's own `main` in a fresh JVM and checks what it
prints, so what it runs is exactly what you run. A new program without a launch
there fails the build.

## Next

- [`libtmux`](../libtmux/) · [Getting started](../docs/guide/getting-started.md)
