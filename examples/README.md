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

Each program has a `run` method that `main` calls, which is what lets the suite
run *exactly* what you run rather than an approximation of it.

## Next

- [`libtmux`](../libtmux/) · [Getting started](../docs/guide/getting-started.md)
