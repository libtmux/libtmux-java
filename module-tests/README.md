# module-tests

**Builds of their own that consume what the repository publishes, the way a
user's project would.** Not part of the root build.

Each resolves only the published coordinates, from the staging repository the
root build writes, so a POM that names the wrong dependency, a jar missing a
class, or a module descriptor that does not load fails here, not for a user.

- **[`java/`](java/)** — `libtmux` loads as the named module
  `io.github.libtmux`, records its version, and drives a real tmux.
- **[`kotlin/`](kotlin/)** — the BOM selects `libtmux-kotlin`, which drives a
  real tmux.
- **[`scala/`](scala/)** — the BOM selects the Scala facades, which drive a
  real tmux; the core facade alone brings no Cats, FS2 or Ox.
- **[`sbt/`](sbt/)** — sbt resolves each artifact with the install line the
  documentation shows.
- **[`cli/`](cli/)** — `tmux-workspace` runs from its coordinates alone,
  reports the staged version, and loads and freezes a workspace on a real tmux.

## Run them

Stage the publications first:

```console
$ ./gradlew publishAllPublicationsToStagingRepository
```

Then run each build with the version it staged:

```console
$ ./gradlew -p module-tests/java run -PlibtmuxVersion=0.0.1-alpha.15-SNAPSHOT
```

```console
$ ./gradlew -p module-tests/scala run -PlibtmuxVersion=0.0.1-alpha.15-SNAPSHOT
```

```console
$ module-tests/sbt/sbtw -Dlibtmux.version=0.0.1-alpha.15-SNAPSHOT core/run cats/run ox/run direct/run
```

```console
$ ./gradlew -p module-tests/cli run -PlibtmuxVersion=0.0.1-alpha.15-SNAPSHOT
```

`sbtw` downloads the pinned sbt launcher once and checks its checksum before
running it. CI runs all five on every pull request, in its release rehearsal:
against a staging signed at the version a tag would publish.
