# Disposable spike protocol

## Baseline and boundary

The baseline is branch `java` from `origin/master`, at architecture review
commit `c89fdb9a`. The checkout was clean before this protocol was added.
Prototype source, generated output, tool distributions, logs, sockets,
recordings, caches, and consumer repositories remain below `<spike-root>` and
are never staged. The only durable record of a spike is sanitized evidence
under `java/docs/spikes/`.

The coordinating agent alone creates or destroys `<spike-root>` and starts no
worker before creation completes. The ownership record is five immutable,
Git-local values: canonical root, canonical creation parent, repository,
nonce, and a tab-separated recovery bundle containing those values. A
preexisting value signals an active or interrupted run and is never overwritten
or automatically unset. Outside guarded coordinator cleanup, a missing or
duplicate value stops the spike.

Creation requires canonical `/tmp` as the parent and `<spike-root>` as its
direct child, outside both the repository and home. The root must be owned by
the current user and mode `700`; its regular, non-symlink nonce sentinel must
be owned by that user and mode `600`. Creation rollback may remove only the
still-empty root it just created. A resume re-resolves all five values, checks
their recovery-bundle agreement, path relationships, ownership, permissions,
and sentinel before it creates any disposable content.

The harness requires Linux procfs and GNU `realpath`, `stat`, and `rm`, plus
util-linux `flock`. The shared process-token helper must remove the
parenthesized command field from `/proc/<pid>/stat` before it selects field 22.
Every Java or shell fixture immediately registers its PID, derived start tick,
and owner label through the one locked writer; direct ledger appends are
forbidden. Before append and before cleanup, validation requires a
newline-terminated, tab-separated three-field row: positive numeric PID,
numeric start tick, and a nonempty owner label limited to safe label characters.
The ledger and lock are regular non-symlink mode-`600` files created only when
absent. Cleanup rejects an invalid ledger and fails if a recorded PID retains
the same start tick.

The required execution baseline is Eclipse Temurin `21.0.11+10` through mise,
Gradle `9.7.0`, and Maven `3.9.16`. The [Temurin
release](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.11%2B10)
identifies the JDK build. Every Java-facing command uses
`mise x java@temurin-21.0.11+10.0.LTS`; ambient Java is inventory only, never
gate evidence. Bootstrap Gradle with an isolated `GRADLE_USER_HOME`, no
persistent daemon, and the [versioned official Gradle distribution
checksum](https://services.gradle.org/distributions/gradle-9.7.0-bin.zip.sha256)
`84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae`.
The shared wrapper pins that distribution and its checksum; independently
verify its JAR against the [versioned official wrapper-JAR
checksum](https://services.gradle.org/distributions/gradle-9.7.0-wrapper.jar.sha256),
SHA-256
`7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`.

The Maven distribution requires the [versioned Apache archive
SHA-512](https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz.sha512)
`831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6`.
Maven consumer projects generate Maven Wrapper `3.3.4` in `only-script` mode
with that binary and pin their distribution checksum, as specified by the
[Maven Wrapper `maven-wrapper-3.3.4` usage
guide](https://github.com/apache/maven-wrapper/blob/maven-wrapper-3.3.4/maven-wrapper-plugin/src/site/markdown/usage.md).
The Gradle runner offers reused caches for an individual contender and fresh
caches for consumers and oracles. Product mode disables toolchain
auto-detection and downloading and exposes only the mise Temurin installation;
resolver mode permits the isolated Foojay oracle to download its requested
alternate vendor through the [pinned Foojay resolver
documentation](https://github.com/gradle/foojay-toolchains/blob/2a6cc60/README.md).

The Maven runner keeps both its user home and local repository below
`<spike-root>`, with distinct fresh or reused pairs. It disables startup files,
clears ambient Maven arguments, supplies explicit credential-free settings and
toolchains files, and gives Java that isolated user home. The uv runner assigns
an isolated project environment and cache, disables bytecode files, and turns
off pytest's cache provider so source inventory cannot write to the checkout or
the user's uv cache.

## Executed baseline evidence

The following is Task 1 evidence, not a claim that later bakeoff gates ran.
The observed host baseline was Linux on `x86_64`, tmux `3.7b`, and ambient
OpenJDK `26.0.2+10-55`. The ambient JVM is inventory evidence only; all Java
gate commands used the pinned Temurin runtime.

```console
$ git status --short --branch
```

```console
$ git log --oneline --decorate origin/master..HEAD
```

```console
$ tmux -V
```

```console
$ java -XshowSettings:properties -version
```

```console
$ uname -s
```

```console
$ uname -m
```

Each command completed with exit status zero. The boundary check reported
branch `java` at `5620ee0e6225c95350818bdf4dd5c8f6b0334275` before the first
protocol commit.

```console
$ sh -eu -c 'for key in codex.libtmux-java-spike-root codex.libtmux-java-spike-parent codex.libtmux-java-spike-repo codex.libtmux-java-spike-nonce codex.libtmux-java-spike-bundle; do test "$(git config --local --get-all "$key" | awk "END { print NR + 0 }")" -eq 1; done; root="$(realpath -e -- "$(git config --local --get codex.libtmux-java-spike-root)")"; parent="$(realpath -e -- "$(git config --local --get codex.libtmux-java-spike-parent)")"; nonce="$(git config --local --get codex.libtmux-java-spike-nonce)"; test "$parent" = "$(realpath -e -- /tmp)"; test "$(dirname -- "$root")" = "$parent"; test -d "$root"; test ! -L "$root"; test "$(stat -c %u -- "$root")" = "$(id -u)"; test "$(stat -c %a -- "$root")" = 700; sentinel="$root/.libtmux-java-spike-owned-$nonce"; test -f "$sentinel"; test ! -L "$sentinel"; test "$(stat -c %u -- "$sentinel")" = "$(id -u)"; test "$(stat -c %a -- "$sentinel")" = 600'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; for relative in tools build/bootstrap build/single-project build/direct-multiproject build/convention-build build/consumers build/foojay-provisioning build/oracle build/synthesis transport/virtual-pipes transport/bounded-pumps transport/file-redirect transport/oracle transport/synthesis hydration/scoped-listings hydration/pane-rowset hydration/hybrid hydration/oracle hydration/synthesis metamodel/record-first metamodel/schema-first metamodel/adapted-processor metamodel/oracle metamodel/synthesis junit/callback-owned junit/store-owned junit/parameter-owned junit/oracle junit/synthesis synthesis artifacts; do test -d "$root/$relative"; done'
```

Passed: every ownership key had one value and the root and sentinel met their
canonical-parent, owner, regular-file, non-symlink, and mode checks.

```console
$ test "$(uname -s)" = Linux && test -r /proc/self/stat
```

```console
$ sh -eu -c 'realpath --version | rg -q "GNU coreutils"; stat --version | rg -q "GNU coreutils"; rm --version | rg -q "GNU coreutils"; flock --version | rg -q "util-linux"'
```

Both capability checks passed.

```console
$ <spike-root>/artifacts/validate-owned-process-ledger.sh <spike-root>/artifacts/owned-processes.tsv
```

```console
$ SPIKE_ROOT=<spike-root> sh -eu -c '"$SPIKE_ROOT/artifacts/record-owned-process.sh" "$SPIKE_ROOT/artifacts/owned-processes.tsv" "$SPIKE_ROOT/artifacts/process-start-token.sh" "$$" baseline-ledger'
```

```console
$ <spike-root>/artifacts/verify-no-owned-processes.sh <spike-root>/artifacts/owned-processes.tsv <spike-root>/artifacts/process-start-token.sh <spike-root>/artifacts/validate-owned-process-ledger.sh
```

```console
$ sh -n <spike-root>/artifacts/process-start-token.sh <spike-root>/artifacts/validate-owned-process-ledger.sh <spike-root>/artifacts/record-owned-process.sh <spike-root>/artifacts/verify-no-owned-processes.sh <spike-root>/artifacts/run-gradle.sh <spike-root>/artifacts/run-maven.sh <spike-root>/artifacts/run-uv.sh
```

All four commands passed: the ledger was valid, the locked writer registered a
short-lived shell fixture, cleanup found no matching live process, and every
helper and runner passed syntax validation.

```console
$ mise install java@temurin-21.0.11+10.0.LTS
```

```console
$ mise x java@temurin-21.0.11+10.0.LTS -- java -version
```

Both commands passed and reported Temurin `21.0.11+10`.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; curl -fL --retry 3 --output "$root/tools/gradle-9.7.0-bin.zip" "https://services.gradle.org/distributions/gradle-9.7.0-bin.zip"'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; unzip -q "$root/tools/gradle-9.7.0-bin.zip" -d "$root/tools"'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; mkdir -p "$root/artifacts/gradle-homes/bootstrap"; GRADLE_USER_HOME="$root/artifacts/gradle-homes/bootstrap" mise x java@temurin-21.0.11+10.0.LTS -- "$root/tools/gradle-9.7.0/bin/gradle" --no-daemon -p "$root/tools/gradle-wrapper" wrapper --gradle-version 9.7.0 --distribution-type bin --gradle-distribution-sha256-sum 84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; curl -fL --retry 3 --output "$root/tools/apache-maven-3.9.16-bin.tar.gz" "https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz"'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; tar -xzf "$root/tools/apache-maven-3.9.16-bin.tar.gz" -C "$root/tools"'
```

Each download, unpack, and wrapper-generation command passed. The verification
command was:

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; printf "%s  %s\n" 84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae "$root/tools/gradle-9.7.0-bin.zip" | sha256sum -c -; printf "%s  %s\n" 7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d "$root/tools/gradle-wrapper/gradle/wrapper/gradle-wrapper.jar" | sha256sum -c -; printf "%s  %s\n" 831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6 "$root/tools/apache-maven-3.9.16-bin.tar.gz" | sha512sum -c -'
```

Passed: the Gradle archive, wrapper JAR, and Maven archive matched their
published checksums. The isolated runner commands were:

```console
$ <spike-root>/artifacts/run-gradle.sh tool-version reuse product <spike-root>/tools/gradle-wrapper --version
```

```console
$ <spike-root>/artifacts/run-maven.sh tool-version reuse <spike-root>/tools/apache-maven-3.9.16/bin/mvn <spike-root>/tools --version
```

`mise install java@temurin-21.0.11+10.0.LTS` and
`mise x java@temurin-21.0.11+10.0.LTS -- java -version` passed, reporting
Temurin `21.0.11+10`. The official Gradle archive checksum and wrapper-JAR
checksum passed; the Gradle runner reported `9.7.0` on Temurin `21.0.11+10`.
The Maven archive SHA-512 passed; the Maven runner reported `3.9.16` on the
same Temurin runtime. The raw-artifact digests are Gradle archive
`sha256:84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae`,
wrapper JAR
`sha256:7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`,
and Maven archive
`sha256:80ffca22aed9e8b9713a232f3394fd81d7f20322df75efdb2b047dbd3e3a23bb`.

No contender, synthesis, publication, consumer journey, full Java test suite,
or prototype deletion gate has run. Those remain requirements of later spike
records.

## Shared experiment contract

Each question starts with a behavior statement, a common fixture set, and
falsification gates. Three independently runnable contenders use those same
fixtures and oracle. A contender that cannot run is a hard fail. No contender
may depend on source, output, caches, or state owned by another contender.

Every contender must be classified exactly once per gate:

- `pass`: the gate completed and met its assertion.
- `hard fail`: the gate did not complete or an assertion failed.
- `not applicable`: the gate cannot apply to that contender; the row states
  why and identifies the equivalent applicable gate.

Hard failures reject a contender regardless of score. Rank surviving
contenders in this fixed order: correctness, downstream API, maintenance,
performance, then build complexity. A lower-priority score never compensates
for a loss earlier in that order.

All contenders run static analysis, complete tests, real-tmux compatibility,
publication metadata, reproducibility, process cleanup, and plain Maven and
Gradle consumer journeys when those gates apply. Exact commands, tool versions,
exit outcomes, and redacted artifacts accompany the relevant gate. Temporary
paths, usernames, hostnames, PIDs, socket names, and raw local environment
values are replaced with stable labels such as `<spike-root>`.

## Result record

Each bakeoff note contains this table. A cell records the state, concise result,
and raw-artifact label; an absent result is not evidence.

| gate     | oracle               | contender A          | contender B          | contender C          | synthesis           | verdict      | artifact digest   |
| -------- | -------------------- | -------------------- | -------------------- | -------------------- | ------------------- | ------------ | ----------------- |
| `<gate>` | `<shared assertion>` | `<state and result>` | `<state and result>` | `<state and result>` | `<state and rerun>` | `<decision>` | `sha256:<digest>` |

`oracle` identifies the shared assertion and fixture, not a preferred
implementation. `synthesis` records the winner-plus-graft rebuild and the
complete rerun of every hard gate. `verdict` names the selected contender,
accepted grafts, and rejected alternatives with the decisive gate. The
`artifact digest` column gives the SHA-256 digest of every raw artifact cited
by the row. Compute and record each digest before deleting `<spike-root>`.

## Synthesis loop

Select a provisional winner only after its complete shared evidence is
available. Apply explicitly named grafts, rebuild a new synthesis contender,
and rerun every hard gate plus affected downstream journeys. A synthesis
failure becomes a new falsifiable question: create three minimal independent
variants, repeat the shared oracle, record their result table, select any new
winner and grafts, then rerun synthesis. Continue until the synthesis contender
passes every hard gate with no known unresolved failure.

Before deletion, validate the owned-process ledger and prove no owned process
remains live. Preserve only the sanitized notes, decisions, exact commands,
and digests needed to reproduce or audit the result. The later clean rewrite
starts from an empty Java source tree; it does not promote prototype source.
