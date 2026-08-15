# Java libtmux Disposable Spikes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Select and falsify the build, coordinate, transport, hydration,
query-metamodel, and JUnit lifecycle designs before any production Java source
is written.

**Architecture:** Every experiment uses three independently runnable
contenders against one shared oracle. Prototype source stays in a temporary
directory outside Git. Each selected design is rebuilt from winner-plus-graft
decisions, rerun through every hard gate, and respiked when synthesis reveals a
new stumbling block. Only sanitized evidence, parity inventories, source
studies, and decisions remain in the repository.

**Tech Stack:** Gradle Kotlin DSL, Java 21, JUnit 5, Error Prone, NullAway,
JSpecify, Spotless with palantir-java-format, JSR 269, Jackson, Maven consumer
projects, real tmux, JFR, and JUnit Platform TestKit.

## Global Constraints

- Read `java/docs/design/2026-08-09-architecture.md` before each task. It is the
  contract; this plan only sequences experiments.
- Require the resolved independent architecture verdict in
  `java/docs/reviews/2026-08-09-architecture.md` before Task 1. A missing or
  reopened blocker stops this plan.
- Work from the `java` branch without fetching, pushing, tagging, or rewriting
  history.
- Keep all prototype Gradle builds and Java source under the directory named by
  the Git-local `codex.libtmux-java-spike-root` value. Bind that value to its
  canonical creation parent, repository, and nonce in Git-local configuration.
  Never copy prototype source into the repository. Every shell or agent
  resolves the values afresh; no step relies on an exported variable surviving
  another shell.
- Commit only files listed under **Durable files**. Do not commit generated
  build output, JFR recordings, tmux sockets, Maven repositories, Gradle homes,
  or raw logs.
- Use the same fixtures and assertions for all contenders in a bakeoff. A
  contender that does not run is a failed contender, not evidence.
- Score correctness before API quality, maintainability, performance, and
  build complexity. A failed hard gate rejects a contender regardless of its
  score.
- Record exact tool versions and commands, but replace temporary absolute paths,
  usernames, hostnames, and process-specific values with descriptive labels.
- Link external source claims to a release tag or stable commit revision.
- Treat every new synthesis failure as a fresh falsifiable question. Add three
  minimal variants, rerun the shared oracle, graft the result, and repeat until
  no known failure remains.
- Run an independent evidence review after each task. The implementer fixes
  specification defects first, then quality defects, before committing.
- A spike commit records evidence only. It must use the repository commit
  format and pass `git diff --check` plus
  `prettier --check 'java/docs/**/*.md'`. Stage explicit files and inspect
  `git diff --cached --name-only` before every commit.
- Do not describe the Java library as complete or working. Completion requires
  the later clean rewrite and every project gate.

## Durable File Map

Only these durable files may be created or modified during this plan:

- `java/docs/design/2026-08-09-architecture.md` may receive only
  evidence-driven corrections
- `java/docs/plans/2026-08-09-clean-rewrite.md`
- `java/docs/spikes/00-protocol.md`
- `java/docs/spikes/01-build-and-coordinates.md`
- `java/docs/spikes/02-transport.md`
- `java/docs/spikes/03-hydration.md`
- `java/docs/spikes/04-query-metamodel.md`
- `java/docs/spikes/05-junit-lifecycle.md`
- `java/docs/spikes/06-integrated-synthesis.md`
- `java/docs/studies/java-library-patterns.md`
- `java/docs/studies/tmux-protocol.md`
- `java/docs/studies/cpython-subprocess.md`
- `java/docs/studies/engine-ops-seams.md`
- `java/docs/parity/python-api.md`
- `java/docs/parity/test-map.md`
- `java/schema/filter-expr-v1.schema.json`
- `java/schema/fixtures/filter-expr-v1.jsonl`
- `java/docs/reviews/2026-08-09-architecture.md`
- `java/docs/reviews/2026-08-09-spike-evidence.md`

The disposable tree uses this shape and is deleted at the end:

```text
$LIBTMUX_JAVA_SPIKE_ROOT/
  .libtmux-java-spike-owned-<nonce>
  tools/
    gradle-9.7.0/
    gradle-wrapper/
    apache-maven-3.9.16/
    tmux/
  build/
    bootstrap/
    single-project/
    direct-multiproject/
    convention-build/
    consumers/
    foojay-provisioning/
    oracle/
    synthesis/
  transport/
    virtual-pipes/
    bounded-pumps/
    file-redirect/
    oracle/
    synthesis/
  hydration/
    scoped-listings/
    pane-rowset/
    hybrid/
    oracle/
    synthesis/
  metamodel/
    record-first/
    schema-first/
    adapted-processor/
    oracle/
    synthesis/
  junit/
    callback-owned/
    store-owned/
    parameter-owned/
    oracle/
    synthesis/
  synthesis/
  artifacts/
```

---

## Task 1: Freeze the experiment protocol and baseline

**Durable files:**

- Create: `java/docs/spikes/00-protocol.md`

### Step 1.1: Reconfirm the Git boundary

- [ ] Run the read-only status and ancestry check.

```console
$ git status --short --branch
```

```console
$ git log --oneline --decorate origin/master..HEAD
```

- [ ] Record the branch name, base ref, clean/dirty state, architecture commit,
      and the rule that prototype source is never staged. Do not record absolute
      checkout paths.

### Step 1.2: Create an isolated prototype root

- [ ] Only the coordinating agent creates or destroys the root. Start no
      delegated worker until creation completes. Treat any preexisting
      ownership key as an active or interrupted run; never overwrite or
      automatically unset it. Once created, the four individual values and
      recovery bundle are immutable. Outside guarded cleanup, a missing or
      duplicate value stops the plan.
- [ ] Create one mode-700 root as a direct child of canonical `/tmp`. Derive its
      nonce from the `mktemp` basename, bind a mode-600 sentinel to that nonce,
      and persist the canonical root, creation parent, repository, and nonce in
      Git-local configuration. The creation trap can remove only the still-empty
      root it just made.

```console
$ sh -eu -c '
keys="codex.libtmux-java-spike-root codex.libtmux-java-spike-parent codex.libtmux-java-spike-repo codex.libtmux-java-spike-nonce codex.libtmux-java-spike-bundle"
for key in $keys; do
    if git config --local --get-all "$key" >/dev/null 2>&1; then
        printf "refusing preexisting Git-local key: %s\n" "$key" >&2
        exit 64
    fi
done
root=
sentinel=
rollback() {
    status=$?
    trap - 0 1 2 15
    for key in $keys; do
        git config --local --unset-all "$key" >/dev/null 2>&1 || :
    done
    if test -n "$sentinel" && test -f "$sentinel" && test ! -L "$sentinel"; then
        rm -- "$sentinel" || :
    fi
    if test -n "$root" && test -d "$root" && test ! -L "$root"; then
        rmdir -- "$root" || :
    fi
    exit "$status"
}
trap rollback 0 1 2 15
repo="$(realpath -e -- "$(git rev-parse --show-toplevel)")"
home="$(realpath -e -- "$HOME")"
parent="$(realpath -e -- /tmp)"
test "$repo" != /
test "$home" != /
case "$parent" in
    /|"$repo"|"$repo"/*|"$home"|"$home"/*) exit 64 ;;
esac
umask 077
root="$(mktemp -d "$parent/libtmux-java-spike.XXXXXXXXXX")"
root="$(realpath -e -- "$root")"
test "$(dirname -- "$root")" = "$parent"
base="$(basename -- "$root")"
nonce="${base#libtmux-java-spike.}"
test "$base" = "libtmux-java-spike.$nonce"
test "${#nonce}" -eq 10
case "$nonce" in
    ""|*[!A-Za-z0-9]*) exit 64 ;;
esac
case "$root" in
    /|"$home"|"$home"/*|"$repo"|"$repo"/*) exit 64 ;;
esac
case "$home" in
    "$root"|"$root"/*) exit 64 ;;
esac
case "$repo" in
    "$root"|"$root"/*) exit 64 ;;
esac
sentinel="$root/.libtmux-java-spike-owned-$nonce"
install -m 600 /dev/null "$sentinel"
test ! -L "$sentinel"
test "$(stat -c %u -- "$root")" = "$(id -u)"
test "$(stat -c %a -- "$root")" = 700
test "$(stat -c %u -- "$sentinel")" = "$(id -u)"
test "$(stat -c %a -- "$sentinel")" = 600
tab="$(printf '\t')"
case "$repo$parent$root" in
    *"$tab"*) exit 64 ;;
esac
bundle="$(printf "%s\t%s\t%s\t%s" "$repo" "$parent" "$nonce" "$root")"
git config --local --add codex.libtmux-java-spike-repo "$repo"
git config --local --add codex.libtmux-java-spike-parent "$parent"
git config --local --add codex.libtmux-java-spike-nonce "$nonce"
git config --local --add codex.libtmux-java-spike-root "$root"
git config --local --add codex.libtmux-java-spike-bundle "$bundle"
trap - 0 1 2 15
printf "%s\n" "$root"
'
```

- [ ] Resolve exactly one value for every key, revalidate ownership and path
      relationships, then create the disposable shape with POSIX shell syntax.
      Record only the token `<spike-root>` in durable notes.

```console
$ sh -eu -c '
one() {
    key="$1"
    count="$(git config --local --get-all "$key" | awk "END { print NR + 0 }")"
    if test "$count" -ne 1; then
        printf "expected one Git-local value for %s\n" "$key" >&2
        exit 64
    fi
    git config --local --get "$key"
}
configured="$(one codex.libtmux-java-spike-root)"
configured_parent="$(one codex.libtmux-java-spike-parent)"
configured_repo="$(one codex.libtmux-java-spike-repo)"
nonce="$(one codex.libtmux-java-spike-nonce)"
bundle="$(one codex.libtmux-java-spike-bundle)"
root="$(realpath -e -- "$configured")"
parent="$(realpath -e -- "$configured_parent")"
repo="$(realpath -e -- "$(git rev-parse --show-toplevel)")"
home="$(realpath -e -- "$HOME")"
test "$configured" = "$root"
test "$configured_parent" = "$parent"
test "$configured_repo" = "$repo"
old_ifs=$IFS
IFS="$(printf '\t')"
set -f
set -- $bundle
set +f
IFS=$old_ifs
test "$#" -eq 4
test "$1" = "$repo"
test "$2" = "$parent"
test "$3" = "$nonce"
test "$4" = "$root"
test "$parent" = "$(realpath -e -- /tmp)"
test "$(dirname -- "$root")" = "$parent"
test "$(basename -- "$root")" = "libtmux-java-spike.$nonce"
test -d "$root"
test ! -L "$root"
test "$(stat -c %u -- "$root")" = "$(id -u)"
test "$(stat -c %a -- "$root")" = 700
case "$root" in
    /|"$home"|"$home"/*|"$repo"|"$repo"/*) exit 64 ;;
esac
case "$home" in
    "$root"|"$root"/*) exit 64 ;;
esac
case "$repo" in
    "$root"|"$root"/*) exit 64 ;;
esac
sentinel="$root/.libtmux-java-spike-owned-$nonce"
test -f "$sentinel"
test ! -L "$sentinel"
test "$(stat -c %u -- "$sentinel")" = "$(id -u)"
test "$(stat -c %a -- "$sentinel")" = 600
for relative in \
    tools \
    build/bootstrap \
    build/single-project \
    build/direct-multiproject \
    build/convention-build \
    build/consumers \
    build/foojay-provisioning \
    build/oracle \
    build/synthesis \
    transport/virtual-pipes \
    transport/bounded-pumps \
    transport/file-redirect \
    transport/oracle \
    transport/synthesis \
    hydration/scoped-listings \
    hydration/pane-rowset \
    hydration/hybrid \
    hydration/oracle \
    hydration/synthesis \
    metamodel/record-first \
    metamodel/schema-first \
    metamodel/adapted-processor \
    metamodel/oracle \
    metamodel/synthesis \
    junit/callback-owned \
    junit/store-owned \
    junit/parameter-owned \
    junit/oracle \
    junit/synthesis \
    synthesis \
    artifacts
do
    mkdir -p -- "$root/$relative"
done
printf "%s\n" "$root"
'
```

- [ ] Require Linux with readable procfs for this spike harness. The production
      API remains Unix-like; this procfs gate belongs only to disposable process
      ownership evidence. Also require GNU `realpath`, `stat`, and `rm` plus
      util-linux `flock`; the ownership protocol deliberately fails rather than
      weakening its checks on a different implementation.

```console
$ test "$(uname -s)" = Linux && test -r /proc/self/stat
```

```console
$ sh -eu -c 'realpath --version | rg -q "GNU coreutils"; stat --version | rg -q "GNU coreutils"; rm --version | rg -q "GNU coreutils"; flock --version | rg -q "util-linux"'
```

- [ ] Create `artifacts/process-start-token.sh`,
      `artifacts/validate-owned-process-ledger.sh`,
      `artifacts/record-owned-process.sh`, `artifacts/owned-processes.tsv`, its
      lock file, and `artifacts/verify-no-owned-processes.sh`. The shared token
      helper removes the parenthesized command field before selecting start-time
      field 22, so spaces in a process name cannot shift the field. Every Java
      or shell fixture immediately calls the one locked writer with PID, token
      helper, and an owner label; direct ledger appends are forbidden. Validation
      rejects partial, unterminated, extra-field, nonnumeric, and malformed rows
      before either append or cleanup.

```sh
#!/bin/sh
set -eu
test "$(uname -s)" = Linux
pid="$1"
case "$pid" in
    "" | *[!0-9]*) exit 64 ;;
esac
stat_file="/proc/$pid/stat"
test -r "$stat_file"
IFS= read -r stat_line < "$stat_file"
remainder="${stat_line##*) }"
set -- $remainder
test "$#" -ge 20
printf '%s\n' "${20}"
```

```sh
#!/bin/sh
set -eu
ledger="$1"

test -f "$ledger"
test ! -L "$ledger"
if test -s "$ledger"; then
    last_byte="$(tail -c 1 "$ledger" | od -An -t u1 | tr -d '[:space:]')"
    test "$last_byte" = 10
fi
awk -F '\t' '
    NF != 3 { exit 1 }
    $1 !~ /^[1-9][0-9]*$/ { exit 1 }
    $2 !~ /^[0-9]+$/ { exit 1 }
    $3 !~ /^[A-Za-z0-9._:-]+$/ { exit 1 }
' "$ledger"
```

```sh
#!/bin/sh
set -eu
ledger="$1"
token_helper="$2"
pid="$3"
owner="$4"
validator="$(dirname "$0")/validate-owned-process-ledger.sh"
lock="$ledger.lock"

case "$pid" in
    "" | *[!0-9]*) exit 64 ;;
esac
case "$owner" in
    "" | *[!A-Za-z0-9._:-]*) exit 64 ;;
esac
test "${#owner}" -le 128
start_tick="$("$token_helper" "$pid")"
case "$start_tick" in
    "" | *[!0-9]*) exit 64 ;;
esac
test -f "$lock"
test ! -L "$lock"
flock -x "$lock" sh -eu -c '
    validator="$1"
    ledger="$2"
    pid="$3"
    start_tick="$4"
    owner="$5"
    "$validator" "$ledger"
    printf "%s\t%s\t%s\n" "$pid" "$start_tick" "$owner" >> "$ledger"
' sh "$validator" "$ledger" "$pid" "$start_tick" "$owner"
```

```sh
#!/bin/sh
set -eu
ledger="$1"
token_helper="$2"
validator="$3"
"$validator" "$ledger"
while IFS="$(printf '\t')" read -r pid start_tick owner; do
    if test -e "/proc/$pid"; then
        current="$("$token_helper" "$pid")"
        if test "$current" = "$start_tick"; then
            printf '%s remains live: %s\n' "$owner" "$pid" >&2
            exit 1
        fi
    fi
done < "$ledger"
```

- [ ] Make all four helpers executable and create the process ledger and lock
      without overwriting previous state.

```console
$ sh -eu -c '
root="$(realpath -e -- "$(git config --local --get codex.libtmux-java-spike-root)")"
nonce="$(git config --local --get codex.libtmux-java-spike-nonce)"
test -f "$root/.libtmux-java-spike-owned-$nonce"
for script in \
    "$root/artifacts/process-start-token.sh" \
    "$root/artifacts/validate-owned-process-ledger.sh" \
    "$root/artifacts/record-owned-process.sh" \
    "$root/artifacts/verify-no-owned-processes.sh"
do
    test -f "$script"
    test ! -L "$script"
    chmod 700 -- "$script"
    test -x "$script"
done
ledger="$root/artifacts/owned-processes.tsv"
lock="$ledger.lock"
test ! -e "$ledger"
test ! -L "$ledger"
install -m 600 /dev/null "$ledger"
test ! -e "$lock"
test ! -L "$lock"
install -m 600 /dev/null "$lock"
'
```

### Step 1.3: Capture the reproducible tool baseline

- [ ] Record the operating-system family, architecture, installed tmux, and
      ambient Java. The current checkout has no Gradle or Maven executable and
      the ambient Java is not the test toolchain; do not use it as gate evidence.

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

- [ ] Install the exact Eclipse Temurin 21.0.11+10 LTS build with mise. Use
      `mise x` for every Gradle daemon, compiler, test worker, Javadoc task, JFR
      command, and Maven consumer so non-interactive shells receive the matching
      `JAVA_HOME`.

```console
$ mise install java@temurin-21.0.11+10.0.LTS
```

```console
$ mise x java@temurin-21.0.11+10.0.LTS -- java -version
```

- [ ] Download Gradle 9.7.0, validate the official distribution checksum, and
      unpack it without relying on a system Gradle.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; curl -fL --retry 3 --output "$root/tools/gradle-9.7.0-bin.zip" "https://services.gradle.org/distributions/gradle-9.7.0-bin.zip"'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; printf "%s  %s\n" 84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae "$root/tools/gradle-9.7.0-bin.zip" | sha256sum -c -'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; unzip -q "$root/tools/gradle-9.7.0-bin.zip" -d "$root/tools"'
```

- [ ] Create a minimal wrapper-bootstrap project with the following
      `tools/gradle-wrapper/settings.gradle.kts`. Gradle 7 and newer require a
      settings file before running the `wrapper` task.

```kotlin
rootProject.name = "libtmux-java-spike-wrapper"
```

- [ ] Generate one shared pinned wrapper with an isolated bootstrap cache and
      no persistent daemon, then independently validate its JAR. All disposable
      Gradle projects run through this wrapper with `-p`.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; mkdir -p "$root/artifacts/gradle-homes/bootstrap"; GRADLE_USER_HOME="$root/artifacts/gradle-homes/bootstrap" mise x java@temurin-21.0.11+10.0.LTS -- "$root/tools/gradle-9.7.0/bin/gradle" --no-daemon -p "$root/tools/gradle-wrapper" wrapper --gradle-version 9.7.0 --distribution-type bin --gradle-distribution-sha256-sum 84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; printf "%s  %s\n" 7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d "$root/tools/gradle-wrapper/gradle/wrapper/gradle-wrapper.jar" | sha256sum -c -'
```

- [ ] Download and verify Maven 3.9.16 without relying on a system Maven. Maven
      consumer projects later generate Maven Wrapper 3.3.4 in `only-script`
      mode with this binary and pin the distribution checksum.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; curl -fL --retry 3 --output "$root/tools/apache-maven-3.9.16-bin.tar.gz" "https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz"'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; printf "%s  %s\n" 831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6 "$root/tools/apache-maven-3.9.16-bin.tar.gz" | sha512sum -c -'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; tar -xzf "$root/tools/apache-maven-3.9.16-bin.tar.gz" -C "$root/tools"'
```

- [ ] Create `artifacts/run-gradle.sh`. It confines every Gradle user home to
      the owned root, always disables persistent daemons, and supports either a
      reusable cache for one contender or a fresh cache for a consumer or
      provisioning oracle. Product mode disables JDK auto-detection and
      downloading and exposes only the exact mise Temurin installation;
      resolver mode permits the isolated Foojay oracle to download its requested
      alternate vendor.

```sh
#!/bin/sh
set -eu

scope="$1"
cache_mode="$2"
toolchain_mode="$3"
project="$4"
shift 4

case "$scope" in
    "" | *[!A-Za-z0-9._-]*) exit 64 ;;
esac

script_dir="$(CDPATH= cd "$(dirname "$0")" && pwd -P)"
root="$(realpath "$script_dir/..")"
project="$(realpath "$project")"
case "$project" in
    "$root"/*) ;;
    *) exit 64 ;;
esac

base="$root/artifacts/gradle-homes"
mkdir -p "$base"
case "$cache_mode" in
    reuse)
        gradle_home="$base/$scope"
        mkdir -p "$gradle_home"
        ;;
    fresh)
        gradle_home="$(mktemp -d "$base/$scope.XXXXXX")"
        ;;
    *) exit 64 ;;
esac

driver="$(mise where java@temurin-21.0.11+10.0.LTS)"
case "$toolchain_mode" in
    product) auto_download=false ;;
    resolver) auto_download=true ;;
    *) exit 64 ;;
esac

GRADLE_USER_HOME="$gradle_home"
export GRADLE_USER_HOME
exec mise x java@temurin-21.0.11+10.0.LTS -- \
    "$root/tools/gradle-wrapper/gradlew" \
    --no-daemon \
    -Dorg.gradle.java.installations.auto-detect=false \
    -Dorg.gradle.java.installations.auto-download="$auto_download" \
    -Dorg.gradle.java.installations.paths="$driver" \
    -p "$project" \
    "$@"
```

- [ ] Create `artifacts/run-maven.sh`. It confines both Maven Wrapper state and
      the local artifact repository to the owned root. A fresh invocation gets
      a new user home and repository rather than silently reusing consumer
      state. It also disables shell startup files and ambient Maven arguments,
      supplies explicit user settings and toolchains files, and makes the
      isolated directory Maven's Java `user.home`.

```sh
#!/bin/sh
set -eu

scope="$1"
mode="$2"
executable="$3"
workdir="$4"
shift 4

case "$scope" in
    "" | *[!A-Za-z0-9._-]*) exit 64 ;;
esac

script_dir="$(CDPATH= cd "$(dirname "$0")" && pwd -P)"
root="$(realpath "$script_dir/..")"
executable="$(realpath "$executable")"
workdir="$(realpath "$workdir")"
case "$executable" in
    "$root"/*) ;;
    *) exit 64 ;;
esac
case "$workdir" in
    "$root" | "$root"/*) ;;
    *) exit 64 ;;
esac

home_base="$root/artifacts/maven-homes"
repo_base="$root/artifacts/maven-repositories"
mkdir -p "$home_base" "$repo_base"
case "$mode" in
    reuse)
        maven_home="$home_base/$scope"
        maven_repo="$repo_base/$scope"
        mkdir -p "$maven_home" "$maven_repo"
        ;;
    fresh)
        maven_home="$(mktemp -d "$home_base/$scope.XXXXXX")"
        maven_repo="$(mktemp -d "$repo_base/$scope.XXXXXX")"
        ;;
    *) exit 64 ;;
esac

MAVEN_USER_HOME="$maven_home"
MAVEN_SKIP_RC=1
MAVEN_ARGS=
MAVEN_OPTS="-Duser.home=$maven_home"
export MAVEN_USER_HOME MAVEN_SKIP_RC MAVEN_ARGS MAVEN_OPTS
cd "$workdir"
exec mise x java@temurin-21.0.11+10.0.LTS -- \
    "$executable" \
    -Duser.home="$maven_home" \
    -Dmaven.repo.local="$maven_repo" \
    --settings "$root/artifacts/maven-settings.xml" \
    --toolchains "$root/artifacts/maven-toolchains.xml" \
    "$@"
```

- [ ] Create the following isolated Maven user settings and toolchains files at
      `artifacts/maven-settings.xml` and `artifacts/maven-toolchains.xml`.
      Neither file contains credentials, mirrors, profiles, or host paths.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings
    xmlns="http://maven.apache.org/SETTINGS/1.2.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd"
>
  <interactiveMode>false</interactiveMode>
</settings>
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains
    xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/TOOLCHAINS/1.1.0 https://maven.apache.org/xsd/toolchains-1.1.0.xsd"
/>
```

- [ ] Create `artifacts/run-uv.sh` so source-inventory commands cannot create a
      repository-local `.venv` or write to the user's uv cache.

```sh
#!/bin/sh
set -eu

script_dir="$(CDPATH= cd "$(dirname "$0")" && pwd -P)"
root="$(realpath "$script_dir/..")"
UV_PROJECT_ENVIRONMENT="$root/artifacts/python-venv"
UV_CACHE_DIR="$root/artifacts/uv-cache"
PYTHONDONTWRITEBYTECODE=1
PYTEST_ADDOPTS="-p no:cacheprovider"
export UV_PROJECT_ENVIRONMENT UV_CACHE_DIR PYTHONDONTWRITEBYTECODE PYTEST_ADDOPTS
exec uv "$@"
```

- [ ] Make all three runners executable, then prove every pinned executable
      reports the intended version through its isolated runner.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; chmod 700 "$root/artifacts/run-gradle.sh" "$root/artifacts/run-maven.sh" "$root/artifacts/run-uv.sh"; chmod 600 "$root/artifacts/maven-settings.xml" "$root/artifacts/maven-toolchains.xml"'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" tool-version reuse product "$root/tools/gradle-wrapper" --version; "$root/artifacts/run-maven.sh" tool-version reuse "$root/tools/apache-maven-3.9.16/bin/mvn" "$root/tools" --version'
```

### Step 1.4: Write the common evidence schema

- [ ] In `00-protocol.md`, define one result table with these columns:
      `gate`, `oracle`, `contender A`, `contender B`, `contender C`, `synthesis`,
      `verdict`, and `artifact digest`.
- [ ] Define the contender states as `pass`, `hard fail`, or `not applicable`.
      Do not use an ambiguous partial-pass state.
- [ ] Define the fixed score order: correctness, downstream API, maintenance,
      performance, and build complexity.
- [ ] Define the synthesis loop and the requirement that every raw artifact
      cited by a row has a SHA-256 digest before the temporary root is deleted.

### Step 1.5: Validate and commit the protocol

- [ ] Check formatting and accidental path disclosure.

```console
$ ! rg -n '/home/|Users/|(^|[^A-Za-z])[A-Za-z]:[/\\]' java/docs/spikes/00-protocol.md
```

Expected: no matches.

```console
$ git diff --check
```

- [ ] Request an independent specification review of the protocol, resolve all
      findings, then commit only the protocol file.

```console
$ git add java/docs/spikes/00-protocol.md
```

```console
$ test "$(git diff --cached --name-only)" = java/docs/spikes/00-protocol.md
```

```console
$ git commit -F - <<'EOF'
Java(docs[spike]): Define test protocol

why: Disposable contenders need one falsifiable evidence contract.

what:
- Define the isolated prototype boundary
- Define shared gates, scoring, and synthesis reruns
EOF
```

---

## Task 2: Complete source studies and parity inventories

**Durable files:**

- Create: `java/docs/studies/java-library-patterns.md`
- Create: `java/docs/studies/tmux-protocol.md`
- Create: `java/docs/studies/cpython-subprocess.md`
- Create: `java/docs/studies/engine-ops-seams.md`
- Create: `java/docs/parity/python-api.md`
- Create: `java/docs/parity/test-map.md`

**Disposable files:**

- Create: `$LIBTMUX_JAVA_SPIKE_ROOT/artifacts/inventory.py`
- Create: `$LIBTMUX_JAVA_SPIKE_ROOT/artifacts/python-api.json`
- Create: `$LIBTMUX_JAVA_SPIKE_ROOT/artifacts/pytest-nodeids.txt`
- Create: `$LIBTMUX_JAVA_SPIKE_ROOT/artifacts/doctest-nodeids.txt`
- Create: `$LIBTMUX_JAVA_SPIKE_ROOT/artifacts/reconcile-inventory.py`

### Step 2.1: Inventory the Python public surface

- [ ] Enumerate every Python source module and exported symbol. Inspect
      `__all__`, package re-exports, public classes, methods, properties, functions,
      constants, enums, exceptions, signatures, defaults, and deprecations.

```console
$ fd --type f --extension py . src/libtmux | sort
```

```console
$ rg -n '^(__all__|class |def |    def |    @property|[A-Z][A-Z0-9_]* =)' src/libtmux
```

- [ ] Add one `python-api.md` row per symbol with columns `Python symbol`,
      `behavior`, `tmux command`, `version rule`, `Java treatment`, `Java symbol`,
      `contract test`, and `source evidence`.
- [ ] Use only these treatment values: `direct translation`, `semantic Java
adaptation`, `consolidation`, or `approved omission`.
- [ ] Give every omission source evidence proving it is Python machinery or a
      deprecation tombstone.
- [ ] Implement `inventory.py` with both AST and runtime passes. The AST pass
      includes decorated and async definitions, properties, assignments,
      `__all__`, and re-exports. The runtime pass imports each package module and
      records `inspect.signature`, public class members, enum values, and
      deprecation wrappers. Reconcile the passes instead of trusting either one.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-uv.sh" run --locked python "$root/artifacts/inventory.py" --source src/libtmux --output "$root/artifacts/python-api.json"'
```

### Step 2.2: Inventory every Python behavior test

- [ ] Enumerate unit tests, real-tmux tests, doctests, examples, plugin fixtures,
      parametrized cases, and version markers.

```console
$ fd --type f --extension py . tests | sort
```

```console
$ rg -n '^(def test_|async def test_|class Test|    >>>|pytestmark|@pytest\.mark|@pytest\.fixture)' src tests
```

- [ ] Capture pytest collection and doctest collection separately so generated
      parametrized node IDs and module doctests are not inferred from regexes.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-uv.sh" run --locked pytest --collect-only -q > "$root/artifacts/pytest-nodeids.txt"'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-uv.sh" run --locked pytest --collect-only -q --doctest-modules src/libtmux > "$root/artifacts/doctest-nodeids.txt"'
```

- [ ] Add one `test-map.md` row per behavior with columns `Python evidence`,
      `contract`, `Java test kind`, `Java test`, `tmux versions`, and `status`.
- [ ] Classify known Python defects separately. Record the attached-session
      lookup defect and inconsistent list-accessor leniency as deliberate Java
      corrections, not silent divergences.

### Step 2.3: Study established Java library patterns

- [ ] Inspect pinned released source for Gradle convention plugins, Central
      publication, dependency-free annotation contracts, JSR 269 golden tests,
      compile-fail testing, JUnit extension stores, and immutable graph APIs.
- [ ] In `java-library-patterns.md`, record the evaluated library, pinned
      revision, exact source link, reusable pattern, rejected part, and effect on a
      bakeoff gate.
- [ ] Include at least one counterexample for each provisional winner so the
      study cannot merely confirm the design.

### Step 2.4: Study tmux protocol and command-queue behavior

- [ ] Inspect pinned tmux source for process exit behavior, formats, target
      resolution, linked windows, socket selection, control-mode guards, and
      semicolon-group abort semantics.
- [ ] In `tmux-protocol.md`, distinguish released CLI contracts from internal
      implementation observations.
- [ ] Record the transport-critical invariant: after one command in a grouped
      list fails, tmux can discard later commands, so lexical separator counts do
      not prove how many control response blocks will arrive.

### Step 2.5: Study CPython subprocess and decoding behavior

- [ ] Inspect the pinned CPython release used by the Python project for
      `subprocess` pipe draining, timeout cleanup, UTF-8 `backslashreplace`, line
      splitting, and trailing-empty normalization.
- [ ] Turn each behavior into an input/output vector in
      `cpython-subprocess.md`, including invalid UTF-8, no final newline, repeated
      final newlines, empty stderr, literal semicolons, and embedded NUL rejection.

### Step 2.6: Study future engine-ops seams

- [ ] Inspect the current engine-ops comparison checkout read-only. Record its
      authenticated revision and inventory request/result, capability, target,
      snapshot, transport, query, and JSON-schema boundaries.
- [ ] In `engine-ops-seams.md`, map each boundary to the Java spike contract and
      add an executable consumer probe that can implement a second transport and
      evaluate the canonical query fixture without importing Java internals.
- [ ] Include grouped first-, middle-, and last-error control-mode evidence. A
      future engine must represent skipped operations instead of counting
      lexical separators.

```console
$ git -C ../libtmux-engine-ops rev-parse HEAD
```

```console
$ rg -n 'CommandRequest|CommandResult|capabil|snapshot|control|FilterExpr|query|schema' ../libtmux-engine-ops
```

### Step 2.7: Cross-check coverage and commit

- [ ] Confirm every public Python symbol has exactly one inventory row and every
      row names a Java treatment and test.
- [ ] Confirm every Python behavior test has exactly one test-map row or a
      source-backed non-applicable decision.
- [ ] Implement `reconcile-inventory.py` to read `python-api.json`, both node-ID
      lists, and the two Markdown tables. Fail on a missing row, duplicate row,
      unknown source symbol or node ID, empty Java treatment, or empty contract
      test. Run it before review.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-uv.sh" run --locked python "$root/artifacts/reconcile-inventory.py" --api "$root/artifacts/python-api.json" --pytest "$root/artifacts/pytest-nodeids.txt" --doctest "$root/artifacts/doctest-nodeids.txt" --api-map java/docs/parity/python-api.md --test-map java/docs/parity/test-map.md'
```

- [ ] Prove the isolated Python inventory created no environment, bytecode, or
      pytest cache in the repository.

```console
$ sh -eu -c 'test ! -e .venv; test ! -e .pytest_cache; test -z "$(fd --hidden --type d "^(__pycache__|\\.pytest_cache|\\.venv)$" . --exclude .git)"'
```

- [ ] Request independent coverage and durable-link reviews, resolve findings,
      then run the repository checks.

```console
$ git diff --check
```

```console
$ ! rg -n 'blob/(master|main)/|TODO|TBD|/home/|Users/|(^|[^A-Za-z])[A-Za-z]:[/\\]' java/docs/studies java/docs/parity
```

Expected: no matches.

- [ ] Commit only the six study and parity files.

```console
$ git add java/docs/parity/python-api.md java/docs/parity/test-map.md java/docs/studies/cpython-subprocess.md java/docs/studies/engine-ops-seams.md java/docs/studies/java-library-patterns.md java/docs/studies/tmux-protocol.md
```

```console
$ test "$(git diff --cached --name-only)" = "$(printf '%s\n' java/docs/parity/python-api.md java/docs/parity/test-map.md java/docs/studies/cpython-subprocess.md java/docs/studies/engine-ops-seams.md java/docs/studies/java-library-patterns.md java/docs/studies/tmux-protocol.md)"
```

```console
$ git commit -F - <<'EOF'
Java(docs[parity]): Map source contracts

why: Full parity and bakeoff gates need a complete source inventory.

what:
- Map Python APIs and tests to Java contracts
- Record pinned Java, tmux, CPython, and engine source studies
EOF
```

---

## Task 3: Bake off build topology and publication coordinates

**Durable files:**

- Create: `java/docs/spikes/01-build-and-coordinates.md`

**Disposable files:**

- Create `settings.gradle.kts`, `build.gradle.kts`, and
  `gradle/libs.versions.toml` in each of `build/single-project`,
  `build/direct-multiproject`, and `build/convention-build`. Put `Probe.java`
  and `ProbeTest.java` under `src/main/java/com/git_pull/libtmux/` and
  `src/test/java/com/git_pull/libtmux/` only in the single-project contender.
- Create `libtmux`, `libtmux-jackson`, `libtmux-junit5`, and
  `libtmux-metamodel-processor` module directories in the two multi-project
  contenders. Put the probe sources under the corresponding `libtmux/src/`
  main and test source sets. Create `build-logic/settings.gradle.kts`,
  `build-logic/build.gradle.kts`, and the two precompiled convention-plugin
  scripts only in `build/convention-build`.
- Create `build/foojay-provisioning/settings.gradle.kts`,
  `build/foojay-provisioning/build.gradle.kts`, and
  `build/foojay-provisioning/src/main/java/probe/FoojayProbe.java` for the
  non-product resolver oracle.
- Create plain, nullness-aware, and module-path Maven and Gradle consumers under
  `build/consumers`, each with one compile-and-run test.
- Create `build/oracle/verify-publication.sh` and
  `build/oracle/expected-artifacts.txt` as the shared artifact oracle.

### Step 3.1: Write the shared build oracle first

- [ ] Define one minimal exported `Probe` API in package
      `com.git_pull.libtmux` for every contender.

```java
package com.git_pull.libtmux;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class Probe {
    private Probe() {}

    public static String identity(String value) {
        return Objects.requireNonNull(value, "value");
    }
}
```

- [ ] Define a compile-negative source that passes `null` to `identity` and a
      plain consumer that has no JSpecify, Jackson, or JUnit dependency.
- [ ] Configure JSpecify as `compileOnly`, never `compileOnlyApi`. Strip it from
      the POM and Gradle module metadata. Add separate Maven and Gradle
      nullness-aware consumers that explicitly add JSpecify and prove the
      retained `@NullMarked` contract is readable.
- [ ] Define shared assertions for `--release 21`, pinned toolchain vendor and
      update, Spotless, Error Prone, NullAway JSpecify mode, doclint, tests,
      publication metadata, generated source inclusion, and dependency graphs.
- [ ] Give every contender the same custom lifecycle tasks: `hardGates`,
      `publishSpike`, and `verifySpikePublication`. These aggregate the
      contender-specific tasks and make the commands below identical.
- [ ] Generate Maven Wrapper 3.3.4 in `only-script` mode for each Maven consumer
      with Maven 3.9.16 and the pinned distribution SHA-256.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; for consumer in "$root"/build/consumers/maven-*; do scope="wrapper-$(basename "$consumer")"; "$root/artifacts/run-maven.sh" "$scope" reuse "$root/tools/apache-maven-3.9.16/bin/mvn" "$consumer" -f "$consumer/pom.xml" -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dtype=only-script -Dmaven=3.9.16 -DdistributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce; done'
```

### Step 3.2: Implement contender A, single-project feature variants

- [ ] Build one project in which feature variants publish core, Jackson, and
      JUnit capabilities from a single Gradle project.
- [ ] Give the variants distinct dependency graphs and published artifacts.
- [ ] Run the shared oracle. Record configuration warnings, variant selection,
      POM fidelity, task graph size, and consumer usability.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" build-single reuse product "$root/build/single-project" clean hardGates publishSpike verifySpikePublication'
```

### Step 3.3: Implement contender B, direct multi-project configuration

- [ ] Build four projects with declarative module scripts and explicit shared
      configuration in the root build, without an included convention build.
- [ ] Publish core, Jackson, and JUnit artifacts to an isolated temporary Maven
      repository.
- [ ] Run the same oracle and record duplication, variant metadata, and
      downstream results.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" build-direct reuse product "$root/build/direct-multiproject" clean hardGates publishSpike verifySpikePublication'
```

### Step 3.4: Implement contender C, included convention build

- [ ] Build four projects plus `build-logic`, with precompiled Java-library and
      published-library convention plugins.
- [ ] Give `build-logic/settings.gradle.kts` its own repositories and explicit
      root version-catalog import. Put every external plugin implementation on the
      included build's classpath.
- [ ] Run the same oracle from a fresh copied tree with a fresh Gradle user home
      so hidden root configuration cannot make it pass.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; fresh="$(mktemp -d "$root/build/convention-copy.XXXXXX")"; tar --exclude=.gradle --exclude="*/build" -C "$root/build/convention-build" -cf - . | tar -C "$fresh" -xf -; "$root/artifacts/run-gradle.sh" build-convention fresh product "$fresh" clean hardGates publishSpike verifySpikePublication'
```

### Step 3.5: Exercise the candidate versions together

- [ ] Pin the candidate versions from the architecture specification in a
      version catalog. Reject dynamic versions.
- [ ] Verify Gradle wrapper distribution and wrapper-JAR checksums, dependency
      verification metadata, Java 21 toolchains, Error Prone 2.50.0, NullAway
      0.13.8 JSpecify mode, Spotless 8.9.0, palantir-java-format 2.97.0, JUnit
      5.14.4, JSpecify 1.0.1, Vanniktech 0.37.0, and Jackson 2.21.5 together.
- [ ] Retain narrow compile-negative tests for generic method, wildcard,
      generic-class, and JDK-annotation limitations. Reject broad suppressions.
- [ ] Keep ordinary product gates on the exact mise-provided Temurin toolchain.
      Separately configure Foojay resolver 1.0.0 in the non-product provisioning
      probe, request an Amazon Corretto 21 compiler, and disable local
      auto-detection. A fresh Gradle home must download the alternate-vendor
      compiler, compile a minimal source with `--release 21`, and verify from
      toolchain metadata that the language is 21, the vendor matches
      `JvmVendorSpec.AMAZON`, and the installation is below that fresh home's
      `jdks` directory. Keep this network-dependent probe out of publication
      and reproducibility hashes.

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "foojay-provisioning-probe"
```

```kotlin
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.AMAZON
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

val provisionedCompiler = javaToolchains.compilerFor {
    languageVersion = JavaLanguageVersion.of(21)
    vendor = JvmVendorSpec.AMAZON
}

tasks.register("verifyFoojayProvisioning") {
    dependsOn(tasks.named("compileJava"))

    doLast {
        val metadata = provisionedCompiler.get().metadata
        val gradleHome = requireNotNull(System.getenv("GRADLE_USER_HOME"))
        val cacheRoot = file(gradleHome).resolve("jdks").toPath().toRealPath()
        val installation = metadata.installationPath.asFile.toPath().toRealPath()

        check(metadata.languageVersion.asInt() == 21)
        check(JvmVendorSpec.AMAZON.matches(metadata.vendor))
        check(installation.startsWith(cacheRoot))

        logger.lifecycle(
            "provisioned language={}, vendor={}, runtime={}",
            metadata.languageVersion,
            metadata.vendor,
            metadata.javaRuntimeVersion,
        )
    }
}
```

```java
package probe;

public final class FoojayProbe {
    private FoojayProbe() {}

    public static int featureVersion() {
        return Runtime.version().feature();
    }
}
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; scope="foojay-$(openssl rand -hex 8)"; log="$root/artifacts/$scope.log"; "$root/artifacts/run-gradle.sh" "$scope" fresh resolver "$root/build/foojay-provisioning" --info clean verifyFoojayProvisioning > "$log" 2>&1; rg -n "provisioned language=21, vendor=.*Amazon" "$log"; printf "Foojay evidence scope: %s\n" "$scope"'
```

### Step 3.6: Bake off three coordinate namespaces

- [ ] For each topology, publish three coordinate/package variants: verified
      domain namespace, repository-host namespace, and strongest available
      organization namespace.
- [ ] Check namespace claimability using Central's documented verification
      flow. Do not publish externally or claim an unverified namespace.
- [ ] Build plain, nullness-aware, and module-path Maven and Gradle consumers
      against each temporary coordinate. Every command names its project file
      or project directory; none runs against the repository root.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; for consumer in "$root"/build/consumers/maven-*; do scope="consumer-$(basename "$consumer")"; "$root/artifacts/run-maven.sh" "$scope" fresh "$consumer/mvnw" "$consumer" -f "$consumer/pom.xml" --batch-mode --errors verify; done'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; for consumer in "$root"/build/consumers/gradle-*; do scope="consumer-$(basename "$consumer")"; "$root/artifacts/run-gradle.sh" "$scope" fresh product "$consumer" clean test; done'
```

- [ ] Reject coordinates that violate Java package naming or create an
      implausible downstream import. Select and record one group and package
      during this spike. Central account-side namespace verification may remain
      an explicit release precondition, but uncertainty about the Java group or
      package may not pass synthesis.

### Step 3.7: Inspect contender publication artifacts

- [ ] Inspect core POM and Gradle module metadata. Prove core has no JSpecify,
      Jackson, JUnit, processor, or convention-plugin dependency.
- [ ] Prove Jackson and JUnit expose only their deliberate public API
      dependencies.
- [ ] Verify binary, source, and Javadoc jars, license, project description,
      URL, SCM metadata, and `Automatic-Module-Name` values without personal
      metadata.
- [ ] Compile plain classpath and module-path consumers. Retain JPMS descriptors
      only if the module-path journey adds no split-package, service-loading,
      reflection, or annotation-processing friction; otherwise publish only
      the verified automatic module names.

### Step 3.8: Synthesize and run final publication gates

- [ ] Select one topology and coordinate result, name every graft from rejected
      contenders, and rebuild the selection under
      `$LIBTMUX_JAVA_SPIKE_ROOT/build/synthesis`.
- [ ] Run every build, analysis, publication, reproducibility, Maven consumer,
      and Gradle consumer gate against the synthesis.
- [ ] If any gate fails, create three minimal fixes for that specific failure
      and repeat before writing a verdict.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" build-synthesis reuse product "$root/build/synthesis" clean hardGates publishSpike verifySpikePublication'
```

- [ ] Sign the synthesis publication with an ephemeral non-personal test key
      and verify every detached signature locally. Require nonempty artifact and
      signature sets with equal cardinality. Stop the keyring's `gpg-agent`
      before leaving the step. Do not publish the key or include signatures in
      reproducibility hash comparisons.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; keyring="$(mktemp -d "$root/artifacts/gnupg.XXXXXX")"; chmod 700 "$keyring"; repository="$root/build/synthesis/build/spike-repository"; artifacts="$root/artifacts/signing-artifacts.txt"; signatures="$root/artifacts/signing-signatures.txt"; trap '\''gpgconf --homedir "$keyring" --kill gpg-agent >/dev/null 2>&1 || :'\'' EXIT HUP INT TERM; gpg --homedir "$keyring" --batch --no-tty --pinentry-mode loopback --passphrase "" --quick-generate-key "libtmux spike <noreply@spike.invalid>" default default 1d; fd --type f '\''\.(jar|pom|module)$'\'' "$repository" | sort > "$artifacts"; test -s "$artifacts"; while IFS= read -r artifact; do gpg --homedir "$keyring" --batch --no-tty --yes --pinentry-mode loopback --passphrase "" --armor --detach-sign "$artifact"; done < "$artifacts"; fd --type f '\''\.asc$'\'' "$repository" | sort > "$signatures"; test -s "$signatures"; test "$(wc -l < "$artifacts")" -eq "$(wc -l < "$signatures")"; while IFS= read -r signature; do gpg --homedir "$keyring" --batch --no-tty --verify "$signature" "${signature%.asc}"; done < "$signatures"; agent_socket="$(gpgconf --homedir "$keyring" --list-dirs agent-socket)"; gpgconf --homedir "$keyring" --kill gpg-agent; test ! -S "$agent_socket"; trap - EXIT HUP INT TERM'
```

- [ ] Create a new numbered reproducibility run directory for every attempt and
      copy only synthesis inputs into its `a` and `b` children. Run each with
      `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`, `TZ=UTC`, UTF-8 file encoding, a fixed
      `SOURCE_DATE_EPOCH`, the pinned mise JDK, a distinct fresh Gradle home,
      and Javadoc `-notimestamp`. Require each publication's relative path set
      to match `expected-artifacts.txt` and be nonempty. Hash those exact files
      relative to the publication root and require byte-identical manifests.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; run="$(mktemp -d "$root/build/repro-run.XXXXXX")"; token="$(basename "$run")"; for copy in a b; do target="$run/$copy"; mkdir -p "$target"; tar --exclude=.gradle --exclude="*/build" -C "$root/build/synthesis" -cf - . | tar -C "$target" -xf -; LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC SOURCE_DATE_EPOCH=1786233600 "$root/artifacts/run-gradle.sh" "repro-$token-$copy" fresh product "$target" -Dfile.encoding=UTF-8 clean publishSpike verifySpikePublication; repository="$target/build/spike-repository"; test -d "$repository"; (cd "$repository"; fd --type f '\''\.(jar|pom|module)$'\'' . | sed "s#^./##" | sort) > "$run/$copy.paths"; test -s "$run/$copy.paths"; diff -u "$root/build/oracle/expected-artifacts.txt" "$run/$copy.paths"; (cd "$repository"; while IFS= read -r artifact; do sha256sum "$artifact"; done < "$run/$copy.paths") > "$run/$copy.sha256"; done; diff -u "$run/a.paths" "$run/b.paths"; diff -u "$run/a.sha256" "$run/b.sha256"; printf "reproducibility evidence: <%s>\n" "$token"'
```

### Step 3.9: Record and commit the evidence

- [ ] Write the contenders, exact gates, results, scores, winner, grafts,
      synthesis rerun, unresolved release preconditions, and artifact digests in
      `01-build-and-coordinates.md`.
- [ ] Request independent build and Central-publication review, resolve
      findings, sanitize paths, and commit only the note.

```console
$ git add java/docs/spikes/01-build-and-coordinates.md
```

```console
$ test "$(git diff --cached --name-only)" = java/docs/spikes/01-build-and-coordinates.md
```

```console
$ git commit -F - <<'EOF'
Java(docs[spike]): Record build bakeoff

why: Build topology and coordinates need consumer-tested evidence.

what:
- Compare three publication topologies and namespaces
- Record synthesis, metadata, and reproducibility gates
EOF
```

---

## Task 4: Bake off the blocking process transport

**Durable files:**

- Create: `java/docs/spikes/02-transport.md`

**Disposable files:**

- Create `transport/settings.gradle.kts`, `transport/build.gradle.kts`, and
  `transport/gradle/libs.versions.toml` with subprojects `oracle`,
  `virtual-pipes`, `bounded-pumps`, `file-redirect`, and `synthesis`.
- Create shared contract types under
  `transport/oracle/src/main/java/spike/transport/contract/` and shared test
  fixtures under `transport/oracle/src/testFixtures/java/spike/transport/`.
- Create `VirtualPipeTransport.java`, `BoundedPumpTransport.java`, and
  `FileRedirectTransport.java` in the matching contender's
  `src/main/java/spike/transport/` directory.
- Create `TransportContractTest.java`, `CloseRaceTest.java`,
  `DecodeDifferentialTest.java`, `PipeFloodChild.java`, and
  `ProcessLivenessTest.java` in the oracle test fixtures.
- Create `transport/oracle/src/test/resources/pinned.jfc` and expose identical
  `test` and `jfrTest` tasks from every contender.

### Step 4.1: Write the shared transport contract and failure oracle

- [ ] Use the same unsealed blocking interface and immutable values in all
      contenders.

```java
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

interface SpikeTransport extends AutoCloseable {
    SpikeResult execute(SpikeRequest request);

    @Override
    void close();
}

record SpikeEndpoint(List<String> arguments, String transportRealm) {
    SpikeEndpoint {
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(transportRealm, "transportRealm");
    }

    @Override
    public String toString() {
        return "SpikeEndpoint[argumentCount=" + arguments.size() + "]";
    }
}

record SpikeRequest(
        SpikeEndpoint endpoint,
        List<String> argv,
        Duration timeout,
        Map<String, String> diagnosticMetadata) {
    SpikeRequest {
        Objects.requireNonNull(endpoint, "endpoint");
        argv = List.copyOf(argv);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout is not positive");
        }
        diagnosticMetadata = Map.copyOf(diagnosticMetadata);
    }

    @Override
    public String toString() {
        return "SpikeRequest[argumentCount=" + argv.size() + ", timeout=" + timeout + "]";
    }
}

record SpikeResult(int exitCode, List<String> stdout, List<String> stderr) {
    SpikeResult {
        stdout = List.copyOf(stdout);
        stderr = List.copyOf(stderr);
    }

    @Override
    public String toString() {
        return "SpikeResult[exitCode="
                + exitCode
                + ", stdoutLines="
                + stdout.size()
                + ", stderrLines="
                + stderr.size()
                + "]";
    }
}

enum DispatchOutcome {
    NOT_DISPATCHED,
    COMPLETE,
    UNKNOWN
}

final class SpikeTransportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final DispatchOutcome outcome;

    SpikeTransportException(String message, DispatchOutcome outcome, Throwable cause) {
        super(message, cause);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public DispatchOutcome outcome() {
        return outcome;
    }
}
```

- [ ] Exercise an empty endpoint-argument list for the default server, `-L`
      arguments for a named socket, and `-S` arguments for an exact path. The
      realm remains explicit in all three cases.

- [ ] Define one monotonic deadline across process wait, graceful destroy,
      forcible destroy, stream close, and both drains.
- [ ] Define parameterized tests for exact channel preservation, nonzero exits
      as data, launch failure, timeout, interruption, restored interrupt status,
      use after close, idempotent close, and `UNKNOWN` after possible dispatch.
- [ ] Assert redaction: request, endpoint, result, exceptions, failed assertions,
      and logs omit argv values, endpoint paths, metadata values, stdout, and
      stderr. Counts, timeout, exit code, and explicitly safe metadata keys may
      remain.
- [ ] Close stdin immediately in every contender. Race `execute()` with
      `close()` while a request is queued, admitted but not launched, launched,
      and draining. Prove no post-close launch, correct dispatch certainty,
      permit release, idempotent close, and bounded completion.
- [ ] Exercise owned and borrowed lifecycle wrappers: owned close closes one
      transport exactly once; borrowed close never closes it; two borrowers can
      share it and closing one does not invalidate the other.
- [ ] Reject `CompletableFuture`, async public return types, and shell command
      strings in every contender. The public contract remains one blocking call
      over an argv list.

### Step 4.2: Build the pipe-flood and decoding fixtures

- [ ] Create a child Java process that writes deterministic data larger than
      both pipe capacities to stdout and stderr concurrently, then exits with a
      requested code.
- [ ] Name the JFR-covered `TransportContractTest` methods `pipeFlood`,
      `timeout`, and `interruption`. Every contender's `jfrTest` filters for
      those methods, and the shell oracle verifies all three appear in that
      contender's XML results.
- [ ] Create vectors for valid UTF-8, malformed UTF-8, no final newline,
      repeated final blank lines, empty stderr lines, literal semicolons, and NUL in
      an argv element.
- [ ] Assert byte or normalized-line equality against the CPython study oracle;
      never merge stdout and stderr.
- [ ] Run one more concurrent request than the configured admission bound and
      assert bounded completion with no starvation or remaining child process.

### Step 4.3: Implement contender A, virtual-thread pipe drains

- [ ] Start one process per request and drain stdout and stderr directly on two
      virtual threads.
- [ ] Use structured ownership only inside the contender; do not introduce a
      production API.
- [ ] Run every shared test plus JFR and pinned-thread tracing. Any
      transport-attributed pin rejects the contender.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" transport-virtual-pipes reuse product "$root/transport" :virtual-pipes:clean :virtual-pipes:test'
```

### Step 4.4: Implement contender B, admission-bounded platform pumps

- [ ] Prestart a bounded platform-thread executor with two workers per admitted
      process.
- [ ] Acquire one process permit before launch, reserve both drains, close
      stdin, start the process, join both drains to the monotonic deadline, then
      release the permit.
- [ ] Compare daemon and non-daemon worker policies. Require deterministic
      `close()` and prompt JVM exit for owned transport resources.
- [ ] Run every shared test plus JFR and pinned-thread tracing.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" transport-bounded-pumps reuse product "$root/transport" :bounded-pumps:clean :bounded-pumps:test'
```

### Step 4.5: Implement contender C, temporary-file redirection

- [ ] Redirect stdout and stderr to separate private temporary files, wait for
      process completion, then decode both files under the same deadline.
- [ ] Prove cleanup on success, nonzero exit, timeout, interruption, launch
      failure, and forced destruction.
- [ ] Run every shared test plus JFR and pinned-thread tracing.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" transport-file-redirect reuse product "$root/transport" :file-redirect:clean :file-redirect:test'
```

### Step 4.6: Run tmux-specific failure-position differentials

- [ ] Execute independent one-shot commands and semicolon-grouped commands with
      first, middle, and last command failures.
- [ ] Run every tmux probe through a private unique `-S` socket and explicit
      empty config. Register socket, server PID, start instant, and client PID in
      the oracle before dispatch; teardown and liveness assertions run even when
      the probe fails.
- [ ] Record actual exit status and stdout/stderr attribution for each position.
- [ ] Demonstrate why persistent control mode is not the default: tmux may
      remove later grouped commands after the first error, so a separator-counting
      client can wait for result blocks that cannot exist.
- [ ] Keep persistent control mode outside the three default contenders. Record
      the minimum future gate: independent one-line requests and exact
      `COMPLETE`, `FAILED`, `SKIPPED`, or `UNKNOWN` attribution.

### Step 4.7: Capture hard pinning and cleanup evidence

- [ ] Put this exact zero-threshold recording configuration in `pinned.jfc`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="libtmux transport pinning">
  <event name="jdk.VirtualThreadPinned">
    <setting name="enabled">true</setting>
    <setting name="threshold">0 ns</setting>
    <setting name="stackTrace">true</setting>
  </event>
</configuration>
```

- [ ] Configure each `jfrTest` task to use JUnit Platform, one test worker, the
      pinned JFC path, a contender-specific recording in the supplied `jfrDir`,
      `dumponexit=true`, and `-Djdk.tracePinnedThreads=full`.

```kotlin
val jfrDir = providers.gradleProperty("jfrDir")
val testSourceSet = sourceSets.named("test")

tasks.register<Test>("jfrTest") {
    useJUnitPlatform()
    maxParallelForks = 1
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    failOnNoDiscoveredTests = true
    filter {
        includeTestsMatching("*TransportContractTest.pipeFlood")
        includeTestsMatching("*TransportContractTest.timeout")
        includeTestsMatching("*TransportContractTest.interruption")
        setFailOnNoMatchingTests(true)
    }
    val recording = jfrDir.map { directory -> file("$directory/${project.name}.jfr") }
    val settings = rootProject.file("oracle/src/test/resources/pinned.jfc")
    outputs.file(recording)
    doFirst {
        val output = recording.get()
        output.parentFile.mkdirs()
        check(!output.exists() || output.delete())
    }
    jvmArgs(
        recording.map { file ->
            "-XX:StartFlightRecording=filename=${file.absolutePath},settings=${settings.absolutePath},dumponexit=true,disk=true"
        }.get(),
        "-Djdk.tracePinnedThreads=full",
    )
}
```

- [ ] Run the flood, timeout, and interruption suites with zero-threshold
      `jdk.VirtualThreadPinned` JFR events on the pinned JDK.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; mkdir -p "$root/artifacts/jfr"; "$root/artifacts/run-gradle.sh" transport-jfr reuse product "$root/transport" --rerun-tasks -PjfrDir="$root/artifacts/jfr" :virtual-pipes:jfrTest :bounded-pumps:jfrTest :file-redirect:jfrTest > "$root/artifacts/jfr/trace-pinned.log" 2>&1'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; for contender in virtual-pipes bounded-pumps file-redirect; do test -s "$root/artifacts/jfr/$contender.jfr"; reports="$root/transport/$contender/build/test-results/jfrTest"; for method in pipeFlood timeout interruption; do rg -l "name=\"$method\"" "$reports"/TEST-*.xml >/dev/null; done; done'
```

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; counts="$root/artifacts/jfr/pin-counts.tsv"; : > "$counts"; for recording in "$root"/artifacts/jfr/*.jfr; do printed="$recording.txt"; mise x java@temurin-21.0.11+10.0.LTS -- jfr print --events jdk.VirtualThreadPinned "$recording" > "$printed"; count="$(rg -c "^jdk\\.VirtualThreadPinned \\{" "$printed" || :)"; printf "%s\t%s\n" "$(basename "$recording")" "${count:-0}" >> "$counts"; done; ! rg -n "spike\.transport" "$root"/artifacts/jfr/*.jfr.txt "$root/artifacts/jfr/trace-pinned.log"'
```

- [ ] Run the same suites with full pinned-thread traces and reject any frame
      attributable to the transport.
- [ ] Inspect child PIDs after each test batch and prove no zombies or live
      descendants remain.
- [ ] Hash the raw JFR, trace, test, and process-liveness artifacts before
      summarizing them.

### Step 4.8: Synthesize and rerun

- [ ] Select the transport winner and explicit grafts. Reimplement the combined
      design in `$LIBTMUX_JAVA_SPIKE_ROOT/transport/synthesis` without copying a
      contender directory.
- [ ] Rerun all correctness, flood, admission, pinning, timeout, interruption,
      decoding, shutdown, and grouped-error gates.
- [ ] Respike every newly exposed failure with three minimal variants until the
      synthesis has no known hard-gate failure.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; directory="$root/artifacts/jfr-synthesis"; mkdir -p "$directory"; "$root/artifacts/run-gradle.sh" transport-synthesis reuse product "$root/transport" --rerun-tasks :synthesis:clean :synthesis:test -PjfrDir="$directory" :synthesis:jfrTest > "$directory/trace-pinned.log" 2>&1; recording="$directory/synthesis.jfr"; test -f "$recording"; mise x java@temurin-21.0.11+10.0.LTS -- jfr print --events jdk.VirtualThreadPinned "$recording" > "$recording.txt"; ! rg -n "spike\.transport" "$recording.txt" "$directory/trace-pinned.log"'
```

### Step 4.9: Record and commit the evidence

- [ ] Write `02-transport.md` with test vectors, exact outcomes, pin-event
      counts, process-liveness results, scores, winner, grafts, synthesis results,
      and raw artifact digests. Do not embed local paths or terminal content.
- [ ] Request independent concurrency and process-cleanup review, resolve
      findings, then commit only the note.

```console
$ git add java/docs/spikes/02-transport.md
```

```console
$ test "$(git diff --cached --name-only)" = java/docs/spikes/02-transport.md
```

```console
$ git commit -F - <<'EOF'
Java(docs[spike]): Record transport bakeoff

why: Blocking tmux execution needs pinning and cleanup evidence.

what:
- Compare three stdout and stderr drain strategies
- Record failure attribution and synthesized hard gates
EOF
```

---

## Task 5: Bake off immutable hierarchy hydration

**Durable files:**

- Create: `java/docs/spikes/03-hydration.md`

**Disposable files:**

- Create `hydration/settings.gradle.kts`, `hydration/build.gradle.kts`, and
  `hydration/gradle/libs.versions.toml` with subprojects `oracle`,
  `scoped-listings`, `pane-rowset`, `hybrid`, and `synthesis`.
- Create shared graph types under
  `hydration/oracle/src/main/java/spike/hydration/contract/` and shared test
  fixtures under `hydration/oracle/src/testFixtures/java/spike/hydration/`.
- Create `ScopedListingCapture.java`, `PaneRowsetCapture.java`, and
  `HybridCapture.java` in the matching contender source directory.
- Create `PythonDifferentialTest.java`, `LinkedWindowIdentityTest.java`,
  `SnapshotPurityTest.java`, `RefreshContractTest.java`,
  `CollectionImmutabilityTest.java`, and `TmuxMatrixFixture.java` in the oracle.
- Create `hydration/oracle/tmux-matrix.tsv` and
  `hydration/oracle/build-tmux-matrix.sh`; the manifest contains the eight
  release tags, pinned commit IDs, release-archive URLs, and expected SHA-256
  values established by the tmux source study.

### Step 5.1: Write the immutable graph contract first

- [ ] Give every contender the same capture interface.

```java
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

interface HierarchyCapture {
    HierarchySnapshot capture(ServerEndpoint endpoint);
}

record HierarchySnapshot(
        ServerEndpoint endpoint,
        ServerIdentity serverIdentity,
        TmuxVersion tmuxVersion,
        Set<Capability> capabilities,
        Instant capturedAt,
        List<SessionState> sessions,
        List<WindowState> windows,
        List<PaneState> panes,
        List<ClientState> clients,
        SnapshotIndexes indexes) {
    HierarchySnapshot {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(serverIdentity, "serverIdentity");
        Objects.requireNonNull(tmuxVersion, "tmuxVersion");
        Objects.requireNonNull(capturedAt, "capturedAt");
        capabilities = Set.copyOf(capabilities);
        sessions = List.copyOf(sessions);
        windows = List.copyOf(windows);
        panes = List.copyOf(panes);
        clients = List.copyOf(clients);
        Objects.requireNonNull(indexes, "indexes");
    }
}

record WindowContext(String sessionId, int windowIndex, String windowId) {}

record SnapshotIndexes(
        Map<String, Integer> sessionsById,
        Map<WindowContext, Integer> windowsByContext,
        Map<String, Integer> panesById,
        Map<String, Integer> clientsByName,
        Map<String, List<WindowContext>> windowsBySession,
        Map<WindowContext, List<String>> panesByWindow) {
    SnapshotIndexes {
        sessionsById = copyOrdered(sessionsById);
        windowsByContext = copyOrdered(windowsByContext);
        panesById = copyOrdered(panesById);
        clientsByName = copyOrdered(clientsByName);
        windowsBySession = copyOrderedLists(windowsBySession);
        panesByWindow = copyOrderedLists(panesByWindow);
    }

    private static <K, V> Map<K, V> copyOrdered(Map<K, V> source) {
        Objects.requireNonNull(source, "source");
        var copy = new LinkedHashMap<K, V>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value")));
        return Collections.unmodifiableMap(copy);
    }

    private static <K, V> Map<K, List<V>> copyOrderedLists(Map<K, List<V>> source) {
        Objects.requireNonNull(source, "source");
        var copy = new LinkedHashMap<K, List<V>>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "key"), List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }
}
```

- [ ] Use contextual window identity composed of server identity, session ID,
      window index, and tmux window ID. Use server identity plus intrinsic ID for
      sessions and panes, and identity plus client name for clients.
- [ ] Put session ID, window index, intrinsic window ID, active/link flags, and
      parent/child indexes in `WindowState`; this is the explicit winlink
      relation. Keep transport-call instrumentation only in `CountingTransport`,
      outside snapshot equality.
- [ ] Make relation access pure over one captured graph. Add a counting
      transport that fails the test if relation navigation performs I/O.
- [ ] Resolve session, contextual window, pane, client, parent, and child points
      through `SnapshotIndexes`. Prove linked-window contexts remain distinct
      while intrinsic window IDs may repeat.
- [ ] Test `equals()` and `hashCode()` in hash sets across distinct transport
      realms, independent refreshes, linked windows, and renumbered winlinks.

### Step 5.2: Build the real-tmux differential fixture

- [ ] Build or otherwise provision isolated binaries for tmux 3.2a, 3.3a, 3.4,
      3.5, 3.6, literal 3.7, 3.7a, and 3.7b from pinned release sources under
      the temporary root. Record compiler and library provenance. Keep a pinned
      master build informational and never let it replace a released matrix
      lane.
- [ ] Make `build-tmux-matrix.sh` verify each archive against
      `tmux-matrix.tsv`, build into `tools/tmux/<version>`, and fail if any
      binary does not print the exact requested release. Make the script
      executable and run it once before the contender tests.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; chmod 700 "$root/hydration/oracle/build-tmux-matrix.sh"; "$root/hydration/oracle/build-tmux-matrix.sh" --manifest "$root/hydration/oracle/tmux-matrix.tsv" --prefix "$root/tools/tmux"'
```

- [ ] Require every released matrix lane to run before accepting hydration or
      lifecycle evidence. An unavailable required lane blocks the spike verdict;
      it is not recorded as a pass or deferred to the clean implementation.
- [ ] Create sessions with multiple windows and panes, link one window into two
      sessions at different indexes, create active and inactive selections, and
      attach a deterministic `-CC` control client.
- [ ] For every test and version, create a private temporary directory, empty
      config, and unique socket; pass the selected binary, `-S` socket, and `-f`
      config explicitly to every process. Register server/client PIDs plus start
      instants before use and run cleanup in `finally`. Never contact the default
      tmux server.
- [ ] Capture the same topology through Python libtmux and raw tmux format rows.
- [ ] Normalize only documented representation differences; preserve ordering,
      duplicates, identities, active selections, missing targets, empty strings,
      version-gated absence, and literal `3.7` versus `3.7a` behavior.

### Step 5.3: Implement contender A, direct scoped listings

- [ ] Issue separate `list-sessions`, per-session `list-windows`, per-window
      `list-panes`, and `list-clients` commands with typed format tokens.
- [ ] Validate row field counts and distinguish unsupported tokens from
      supported empty values.
- [ ] Run every differential and immutability test. Record exact command counts
      by topology size.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" hydration-scoped reuse product "$root/hydration" :scoped-listings:clean :scoped-listings:test -PtmuxMatrix="$root/tools/tmux"'
```

### Step 5.4: Implement contender B, one hierarchical pane rowset

- [ ] Build the hierarchy primarily from one all-panes rowset carrying session,
      window, and pane fields, plus the minimum client query.
- [ ] Preserve linked-window contextual duplicates without collapsing them by
      intrinsic window ID.
- [ ] Run the same oracle and record fields that cannot be represented or
      version-gated safely.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" hydration-pane-rowset reuse product "$root/hydration" :pane-rowset:clean :pane-rowset:test -PtmuxMatrix="$root/tools/tmux"'
```

### Step 5.5: Implement contender C, hybrid capture plan

- [ ] Use the smallest fixed combination of scoped session, contextual window,
      pane, and client rowsets that preserves every identity and relation.
- [ ] Resolve point and parent targets only after strict decoding; never parse
      human display text.
- [ ] Run the same oracle and record command counts and assembly complexity.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" hydration-hybrid reuse product "$root/hydration" :hybrid:clean :hybrid:test -PtmuxMatrix="$root/tools/tmux"'
```

### Step 5.6: Prove snapshot purity and collection contracts

- [ ] Repeatedly traverse parent and child relations and filter in parallel
      against a counting transport. Assert zero transport calls after initial
      snapshot construction.
- [ ] Test refresh separately. Each refresh performs the contender's expected
      bounded capture calls, returns a new handle and graph, and never mutates
      the original graph or changes its equality/hash behavior.
- [ ] Assert every public list and map rejects mutation while retaining encounter
      order and linked-window duplicates.
- [ ] Assert list-shaped live accessors return empty collections for every tmux
      command failure while explicit snapshot and liveness operations fail loudly.

### Step 5.7: Synthesize and rerun

- [ ] Select the hydration winner and grafts, then rebuild it in
      `$LIBTMUX_JAVA_SPIKE_ROOT/hydration/synthesis` without copying source.
- [ ] Run all Python differentials across tmux 3.2a, 3.3a, 3.4, 3.5, 3.6,
      literal 3.7, 3.7a, and 3.7b, plus purity checks, mutation checks,
      missing-target cases, and command-count assertions.
- [ ] Respike each uncovered version or topology failure with three focused
      variants before accepting the design.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" hydration-synthesis reuse product "$root/hydration" :synthesis:clean :synthesis:test -PtmuxMatrix="$root/tools/tmux"'
```

### Step 5.8: Record and commit the evidence

- [ ] Write `03-hydration.md` with row shapes, tmux commands, identity outcomes,
      differential results, command counts, winner, grafts, synthesis results, and
      artifact digests.
- [ ] Request independent tmux and immutable-model review, resolve findings,
      then commit only the note.

```console
$ git add java/docs/spikes/03-hydration.md
```

```console
$ test "$(git diff --cached --name-only)" = java/docs/spikes/03-hydration.md
```

```console
$ git commit -F - <<'EOF'
Java(docs[spike]): Record hydration bakeoff

why: Snapshot identity and purity need real-tmux differentials.

what:
- Compare three hierarchy capture plans
- Record linked-window and zero-I/O synthesis gates
EOF
```

---

## Task 6: Bake off query AST and generated metamodel designs

**Durable files:**

- Create: `java/docs/spikes/04-query-metamodel.md`
- Create: `java/schema/filter-expr-v1.schema.json`
- Create: `java/schema/fixtures/filter-expr-v1.jsonl`

**Disposable files:**

- Create `metamodel/settings.gradle.kts`, `metamodel/build.gradle.kts`, and
  `metamodel/gradle/libs.versions.toml` with subprojects `oracle`,
  `record-first`, `schema-first`, `adapted-processor`, `core-probe`,
  `jackson-probe`, and `synthesis`.
- Create shared query contracts under
  `metamodel/oracle/src/main/java/spike/query/contract/` and test fixtures under
  `metamodel/oracle/src/testFixtures/java/spike/query/`.
- Create `FilterExprPropertyTest.java`, `SelectionsContractTest.java`,
  `GeneratedApiCompileTest.java`, `ExhaustivenessMutationTest.java`,
  `GenerationDeterminismTest.java`, `SchemaConformanceTest.java`, and
  `PublishedConsumerTest.java` in the oracle.
- Create `RecordFirstProcessor.java`, `SchemaFirstProcessor.java`, and
  `AdaptedProcessor.java` in matching contender modules, each with
  `META-INF/services/javax.annotation.processing.Processor` or an explicit
  processor-class compiler argument.
- Create valid and invalid Java sources under
  `metamodel/oracle/src/test/resources/compile/{pass,fail}/`; compile them with
  the JDK `JavaCompiler` API so expected failure is asserted without adding a
  processor dependency to core.

### Step 6.1: Freeze the public query contract

- [ ] Use a sealed `FilterExpr<T>` extending `Predicate<T>` with public nested
      validating record nodes. Keep the AST free of core dependencies beyond field
      descriptors and model IDs.
- [ ] Make expression-preserving `and`, `or`, and `negate` overloads return
      `FilterExpr<T>`, while inherited arbitrary-predicate composition remains an
      ordinary `Predicate<T>`.
- [ ] Use one exhaustive evaluator switch and a separate exhaustive Jackson
      lowering switch. Do not implement Java object serialization.
- [ ] Store regex source plus explicit flags and defensively copy ordered
      operands.
- [ ] Add a compile mutation that introduces one temporary permitted record.
      Compilation must fail until both the evaluator and Jackson switches gain
      an explicit arm. Reject `default` arms in either switch.

### Step 6.2: Write shared semantic and cardinality tests first

- [ ] Property-test exact, case-insensitive exact, contains, case-insensitive
      contains, starts-with, case-insensitive starts-with, ends-with,
      case-insensitive ends-with, membership, non-membership, regex,
      case-insensitive regex, presence, typed ordering, `and`, `or`, `not`, and
      structural equality. Include composed Unicode, decomposed Unicode,
      supplementary code points, and locale-sensitive case vectors.
- [ ] Test `any`, `all`, `none`, and to-one `is` over captured relations. Assert
      that `all` is true for an empty relation and relation evaluation performs no
      transport call.
- [ ] Freeze the collector signatures and outcomes.

```java
Collector<String, ?, String> exactlyOne = Selections.exactlyOne();
Collector<String, ?, Optional<String>> oneOrEmpty = Selections.oneOrEmpty();
```

- [ ] Assert zero throws `NoMatchException` only for `exactlyOne`, one returns
      the element, multiple throws `MultipleMatchesException`, null is rejected
      immediately, parallel and sequential failures match, encounter-order
      diagnostics are bounded, and neither collector declares `CONCURRENT` or
      `UNORDERED`. Reject `IDENTITY_FINISH` because each finisher validates
      cardinality and changes the accumulation type.
- [ ] Count upstream elements with `peek` and prove both collectors consume the
      stream to completion before throwing on multiple matches. Reject an
      accumulator that throws on the second element.
- [ ] Mutate every caller-owned operand collection after AST construction,
      reject null collections and null elements, and prove structural equality
      and hash codes remain stable.
- [ ] Prove `LibTmuxJacksonModule` rejects arbitrary lambdas and predicates;
      only named `FilterExpr` nodes serialize.

### Step 6.3: Write shared compile-pass and compile-fail consumers

- [ ] Compile string operations on string fields, ordering on comparable fields,
      presence on optional fields, to-one `is`, to-many quantifiers, expression
      composition, lambda filtering, and both collectors.
- [ ] Reject string operations on boolean fields, scalar operations on relation
      fields, wrong relation model expressions, nullable public operands, and
      package-private state leakage.
- [ ] Compile exact wildcard contracts: membership accepts
      `Collection<? extends V>` and relation operators accept
      `FilterExpr<? super R>`. Compile invariant or raw-type escapes only as
      expected failures.
- [ ] Run these consumers through both Maven and Gradle against a temporary
      published core artifact.

### Step 6.4: Implement contender A, record-first custom processor

- [ ] Annotate package-private immutable state records with SOURCE-retained
      model, field ID, format token, scope, minimum version, scalar kind, absence,
      and relation metadata.
- [ ] Generate deterministic public `Pane_`, `Session_`, `Window_`, and
      `Client_` metamodel classes directly from record components.
- [ ] Run the semantic, compile, deterministic-generation, Javadoc, and
      publication oracles.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" metamodel-record-first reuse product "$root/metamodel" :record-first:clean :record-first:test :oracle:generatedApiAgainstRecordFirst'
```

### Step 6.5: Implement contender B, schema-first custom processor

- [ ] Define one explicit model schema consumed by a custom JSR 269 processor,
      then generate state records and public metamodel from the schema.
- [ ] Keep the processor independent of core and register it explicitly.
- [ ] Run the same oracle and record source-of-truth drift risk and generated
      API quality.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" metamodel-schema-first reuse product "$root/metamodel" :schema-first:clean :schema-first:test :oracle:generatedApiAgainstSchemaFirst'
```

### Step 6.6: Implement contender C, local JPA-style baseline

- [ ] Implement `AdaptedProcessor` locally with JSR 269 against the same
      SOURCE-retained model annotations and immutable state records as contender
      A. Adapt only the JPA static-metamodel generator organization: one
      deterministic `Model_` companion per model, inherited attribute discovery,
      and stable member ordering. Record the pinned Jakarta Persistence
      static-metamodel specification used for that internal shape in
      `java-library-patterns.md`.
- [ ] Replace JPA `SingularAttribute` and collection attribute types with the
      same libtmux field and relation descriptors exposed by the other
      contenders. Do not copy a Hibernate or QueryDSL processor and do not add a
      JPA, Hibernate, or QueryDSL compile/runtime dependency.
- [ ] Generate exactly the frozen method-style public surface, including
      `Pane_.command()`: descriptor storage is `private static final` and public
      access is through static accessor methods. Public mutable or `volatile`
      metamodel fields are a hard failure. Run the same oracle and treat naming,
      generic-signature, or processor-implementation leakage as a hard failure.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" metamodel-adapted reuse product "$root/metamodel" :adapted-processor:clean :adapted-processor:test :oracle:generatedApiAgainstAdaptedProcessor'
```

### Step 6.7: Prove deterministic artifacts and processor isolation

- [ ] Generate sources twice from clean directories and compare bytes.
- [ ] Verify golden sources plus compile-pass and compile-fail cases.
- [ ] Run `exhaustivenessMutationTest`; it copies source to a disposable
      directory, adds a complete `ProbeNode` permitted record, and requires
      compilation errors at both exhaustive switches until matching arms are
      inserted. The original sources remain unchanged.
- [ ] Inspect binary, source, and Javadoc jars for generated classes and sources.
- [ ] Prove the processor and its dependencies are absent from core POM, Gradle
      metadata, runtime graph, and published variants.

### Step 6.8: Build the optional JSON adapter oracle

- [ ] Define schema version 1 fixtures for every composition, scalar,
      presence, to-one, and to-many node using stable model, field, operator, and
      regex-flag IDs.
- [ ] Write the complete immutable JSON Schema to
      `java/schema/filter-expr-v1.schema.json` and one canonical document per
      node family to `java/schema/fixtures/filter-expr-v1.jsonl`. The schema
      closes unknown properties and uses stable IDs rather than Java names.
- [ ] Implement the legacy `name__operator=value` edge parser only as a mapping
      from recognized model field and operator IDs into named AST nodes. Prove
      it cannot create a parallel evaluator or bypass typed operand validation.
- [ ] Round-trip every permitted node with Jackson default typing disabled.
- [ ] Reject unknown schema versions, models, fields, operators, wrong requested
      model types, and structurally invalid documents.
- [ ] Validate every fixture against the schema with an independent JSON Schema
      validator in the oracle. Canonicalize the JSONL fixture and Java adapter
      output with `jq -cS`, then require byte equality.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; jq -cS . java/schema/fixtures/filter-expr-v1.jsonl > "$root/artifacts/filter-expr-v1.expected.jsonl"'
```

- [ ] Package `filter-expr-v1.schema.json` in the Jackson artifact and inspect
      the JAR. Keep both core and its generated metamodel dependency-free at
      runtime.
- [ ] Prove core query construction and evaluation loads without Jackson on its
      compile or runtime path.

### Step 6.9: Synthesize and rerun

- [ ] Select the processor/query winner and grafts. Rebuild it under
      `$LIBTMUX_JAVA_SPIKE_ROOT/metamodel/synthesis` without copying contender
      source.
- [ ] Rerun semantic properties, compile consumers, generation determinism,
      artifact inspection, JSON fixtures, legacy edge-parser cases, and zero-I/O
      relation gates.
- [ ] Respike every new generic, nullness, serialization, or generation failure
      with three focused alternatives.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" metamodel-synthesis reuse product "$root/metamodel" :synthesis:clean :synthesis:test :oracle:exhaustivenessMutationTest :oracle:publishedConsumerTest'
```

### Step 6.10: Record and commit the evidence

- [ ] Write `04-query-metamodel.md` with public signatures, invalid examples,
      semantic results, generated-source hashes, artifact graphs, JSON results,
      scores, winner, grafts, and synthesis reruns.
- [ ] Request independent Effective Java, annotation-processing, and wire-schema
      review, resolve findings, then commit only the note.

```console
$ git add java/docs/spikes/04-query-metamodel.md java/schema/filter-expr-v1.schema.json java/schema/fixtures/filter-expr-v1.jsonl
```

```console
$ test "$(git diff --cached --name-only)" = "$(printf '%s\n' java/docs/spikes/04-query-metamodel.md java/schema/filter-expr-v1.schema.json java/schema/fixtures/filter-expr-v1.jsonl)"
```

```console
$ git commit -F - <<'EOF'
Java(docs[spike]): Record query bakeoff

why: Typed filtering needs executable API and generation evidence.

what:
- Compare three static metamodel sources
- Record AST, collector, JSON, and synthesis gates
EOF
```

---

## Task 7: Bake off real-tmux JUnit lifecycle ownership

**Durable files:**

- Create: `java/docs/spikes/05-junit-lifecycle.md`

**Disposable files:**

- Create `junit/settings.gradle.kts`, `junit/build.gradle.kts`, and
  `junit/gradle/libs.versions.toml` with subprojects `oracle`,
  `callback-owned`, `store-owned`, `parameter-owned`, and `synthesis`.
- Create shared extension contracts under
  `junit/oracle/src/main/java/spike/junit/contract/` and TestKit fixtures under
  `junit/oracle/src/testFixtures/java/spike/junit/`.
- Create `LifecycleMatrixTest.java`, `ParallelLifecycleTest.java`,
  `DisabledStoreAutocloseTest.java`, `ForcedCleanupTest.java`,
  `ParameterConflictTest.java`, and `PostTestLivenessTest.java` in the oracle.
- Create `CallbackOwnedExtension.java`, `StoreOwnedExtension.java`, and
  `ParameterOwnedExtension.java` in matching contender source directories.

### Step 7.1: Write the shared fixture contract first

- [ ] Resolve `TmuxTestContext`, `Server`, `Session`, and `TmuxSocketPath`
      parameters without changing process-global home or environment state.
      Never claim an arbitrary `Path` parameter, and prove another extension's
      `Path` resolver does not conflict.
- [ ] Define `TmuxSocketPath` as a defensively validated immutable value around
      one absolute `Path`; its `toString()` is redacted. `TmuxTestContext`
      exposes the raw path only through an explicit accessor.
- [ ] Start tmux with `-S`, an explicit empty config, and a detached default
      session. Query `#{socket_path}` and assert both exact equality and physical
      existence.
- [ ] Capture the server PID and optional
      `ProcessHandle.Info.startInstant()` before exposing the fixture. Treat an
      empty start instant as unverified identity and never signal that PID.

### Step 7.2: Build the lifecycle failure matrix

- [ ] Use JUnit Platform TestKit to run success, assertion failure, assumption
      abort, setup failure, timeout, repeated test, and concurrent test cases.
- [ ] Register an extra server and a control client immediately in the fixture
      and include them in every cleanup assertion.
- [ ] After each case, assert no live owned daemon or client, no socket, and no
      owned temporary directory. Preserve the original test failure and suppress
      cleanup failures unless cleanup is the only failure.

### Step 7.3: Implement contender A, callback-owned state

- [ ] Store resources in extension instance fields and clean them from matching
      lifecycle callbacks.
- [ ] Run the full lifecycle matrix, including parallel execution, and record
      state-sharing or ordering failures.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" junit-callback reuse product "$root/junit" :callback-owned:clean :callback-owned:test -PtmuxMatrix="$root/tools/tmux"'
```

### Step 7.4: Implement contender B, store-owned aggregate resource

- [ ] Register one idempotent aggregate `AutoCloseable` in
      `ExtensionContext.Store` before process start. Do not use the deprecated
      `ExtensionContext.Store.CloseableResource`.
- [ ] Track all clients and servers in acquisition order and close them in
      reverse order.
- [ ] Issue `kill-server` and wait to one monotonic deadline. Terminate and
      forcibly terminate under a second bounded deadline only when the contender
      retained the directly owned server `Process`. Detached PID and start
      instant data remains diagnostic-only and never authorizes a signal.
- [ ] Unlink the socket and remove the directory only after daemon exit is
      proved. Run the full lifecycle matrix.
- [ ] Run the matrix with
      `junit.jupiter.extensions.store.close.autocloseable.enabled=false`. A
      contender that depends only on store auto-close fails; an explicit
      idempotent callback fallback may be grafted during synthesis.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" junit-store reuse product "$root/junit" :store-owned:clean :store-owned:test -PtmuxMatrix="$root/tools/tmux"'
```

### Step 7.5: Implement contender C, parameter-owned AutoCloseable fixture

- [ ] Return an `AutoCloseable` fixture from parameter resolution and require
      the test to close it explicitly.
- [ ] Run the same matrix, including cases where setup or the test body prevents
      explicit close, and record cleanup reliability and API burden.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" junit-parameter reuse product "$root/junit" :parameter-owned:clean :parameter-owned:test -PtmuxMatrix="$root/tools/tmux"'
```

### Step 7.6: Exercise forcible cleanup safely

- [ ] Bake off three cleanup backstops behind the same lifecycle contenders:
      retain a directly owned foreground `tmux -D` `Process`, use detached tmux
      plus PID/start-instant verification, and use socket-only cleanup that
      reports failure rather than signaling an uncertain process.
- [ ] Run the foreground-process variant across every required tmux release. In
      the detached variant, never signal when `startInstant()` is empty or no
      longer matches. Record the remaining check-then-signal race as a hard
      safety cost rather than claiming PID equality proves ownership.
- [ ] Use an isolated fixture mode to make graceful `kill-server` unavailable.
      Prove bounded terminate then forcibly-terminate only for a directly owned
      live `Process`; otherwise preserve the cleanup failure without risking an
      unrelated process.
- [ ] Repeat the entire matrix concurrently enough times to expose namespace,
      store, and timing collisions.

### Step 7.7: Synthesize and rerun

- [ ] Select the lifecycle winner and grafts. Rebuild it under
      `$LIBTMUX_JAVA_SPIKE_ROOT/junit/synthesis` without copying contender source.
- [ ] Rerun every TestKit case, parallel repetition, exact socket assertion,
      PID/start-instant ownership check, forced cleanup, and post-test liveness
      scan.
- [ ] Respike any new callback-ordering, timeout, or failure-preservation issue
      with three minimal variants.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" junit-synthesis reuse product "$root/junit" :synthesis:clean :synthesis:test -PtmuxMatrix="$root/tools/tmux"'
```

### Step 7.8: Record and commit the evidence

- [ ] Write `05-junit-lifecycle.md` with the lifecycle matrix, ownership model,
      deadlines, exact cleanup outcomes, scores, winner, grafts, synthesis reruns,
      and artifact digests.
- [ ] Request independent JUnit and process-safety review, resolve findings,
      then commit only the note.

```console
$ git add java/docs/spikes/05-junit-lifecycle.md
```

```console
$ test "$(git diff --cached --name-only)" = java/docs/spikes/05-junit-lifecycle.md
```

```console
$ git commit -F - <<'EOF'
Java(docs[spike]): Record JUnit bakeoff

why: Real-tmux fixtures need failure-proof ownership evidence.

what:
- Compare three extension lifecycle strategies
- Record exact-socket and process-cleanup synthesis gates
EOF
```

---

## Task 8: Rebuild the integrated winner and run consumer journeys

**Durable files:**

- Create: `java/docs/spikes/06-integrated-synthesis.md`
- Modify only if falsified by evidence:
  `java/docs/design/2026-08-09-architecture.md`

**Disposable files:**

- Create `synthesis/settings.gradle.kts`, `synthesis/build.gradle.kts`, the
  selected module layout, and plain Maven and Gradle consumers from an empty
  `synthesis/` directory.
- Create `IntegratedJourneyTest.java`, `TransportOwnershipTest.java`,
  `EngineBoundaryConsumerTest.java`, and `PublicSurfaceCompileTest.java` in the
  integrated oracle.
- Create `synthesis/consumers/maven/pom.xml`,
  `synthesis/consumers/maven/README.md`,
  `synthesis/consumers/maven/src/test/java/consumer/LibTmuxJourneyTest.java`,
  `synthesis/consumers/gradle/settings.gradle.kts`,
  `synthesis/consumers/gradle/build.gradle.kts`,
  `synthesis/consumers/gradle/README.md`, and the equivalent Gradle journey
  test. Each README contains the exact isolated runner command, required tmux
  matrix property, expected result, and no repository-internal setup step.

### Step 8.1: Freeze selected contracts before integration

- [ ] Copy no prototype source. Write a contract table from the five bakeoff
      notes naming each winner, graft, rejected alternative, hard gate, and
      remaining external release precondition.
- [ ] Reconcile every selection with the architecture specification. Where
      evidence falsified a provisional choice, update the specification with the
      proven contract before integration and explain the change as current design,
      not branch history.

### Step 8.2: Rebuild one minimal vertical slice from scratch

- [ ] Recreate the selected build topology and package coordinates. Set the
      integrated root project name to `synthesis`; the JFR oracle requires the
      exact `synthesis.jfr` recording name.
- [ ] Implement the proven request/result transport, one immutable
      session-window-pane capture, the query AST and generated fields needed to
      filter it, the Jackson round trip, and the selected JUnit lifecycle winner
      plus grafts. Do not hardcode a provisional store-owned result.
- [ ] Implement the minimal live operations required by the integrated journey:
      open server, create session and window, split pane, send keys, capture,
      refresh, query, and deterministic teardown.
- [ ] Add compile-only public signature probes for every inventoried option,
      hook, environment, buffer, prompt, menu, and popup API. These are
      disposable API contracts with explicit unsupported implementations; they
      are not runtime-parity claims.
- [ ] Keep this integrated slice disposable. Its purpose is to expose
      cross-boundary failures before the production rewrite.

### Step 8.3: Run the hard gates together

- [ ] Run Spotless, Error Prone, NullAway JSpecify mode, doclint, unit tests,
      real-tmux tests, compile-pass tests, compile-fail tests, and publication.
- [ ] Rerun transport flood, pinning, timeout, interruption, hierarchy
      differential, zero-I/O snapshot, generated-source determinism, JSON failure,
      and JUnit cleanup gates without weakening their assertions.
- [ ] Rerun unsigned reproducibility, detached-signature verification, POM and
      Gradle-metadata inspection, binary/source/Javadoc JAR inspection,
      processor isolation, dependency-free core, classpath consumer, and
      module-path consumer gates from Task 3.
- [ ] Run tmux 3.2a, 3.3a, 3.4, 3.5, 3.6, literal 3.7, 3.7a, and 3.7b. Keep the
      pinned master lane informational. Do not accept synthesis while a released
      matrix lane is unavailable or failing.
- [ ] Build, publish, inspect, and verify the integrated synthesis before any
      downstream consumer attempts to resolve it.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" integrated-synthesis reuse product "$root/synthesis" clean hardGates publishSpike verifySpikePublication -PtmuxMatrix="$root/tools/tmux"'
```

- [ ] Expose the same source-set-wired `jfrTest` and exact transport method
      filters from the integrated build. Force a fresh recording, require the
      three XML test outcomes, print pin events, and reject integrated transport
      frames in either JFR or full pinned-thread traces. Match the architecture's
      reserved `.transport.` package boundary so owned drain workers and helpers
      remain attributable regardless of the selected root package.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; directory="$(mktemp -d "$root/artifacts/jfr-integrated.XXXXXX")"; "$root/artifacts/run-gradle.sh" integrated-jfr reuse product "$root/synthesis" --rerun-tasks -PjfrDir="$directory" jfrTest > "$directory/trace-pinned.log" 2>&1; recording="$directory/synthesis.jfr"; test -s "$recording"; reports="$root/synthesis/build/test-results/jfrTest"; for method in pipeFlood timeout interruption; do rg -l "name=\"$method\"" "$reports"/TEST-*.xml >/dev/null; done; mise x java@temurin-21.0.11+10.0.LTS -- jfr print --events jdk.VirtualThreadPinned "$recording" > "$recording.txt"; ! rg -n "\\.transport\\." "$recording.txt" "$directory/trace-pinned.log"; printf "integrated JFR evidence: <%s>\n" "$(basename "$directory")"'
```

- [ ] Sign the integrated publication with a new non-personal key, require
      nonempty equal artifact/signature sets, verify each signature, and stop
      the isolated keyring's agent.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; keyring="$(mktemp -d "$root/artifacts/gnupg-integrated.XXXXXX")"; chmod 700 "$keyring"; repository="$root/synthesis/build/spike-repository"; artifacts="$root/artifacts/integrated-signing-artifacts.txt"; signatures="$root/artifacts/integrated-signing-signatures.txt"; trap '\''gpgconf --homedir "$keyring" --kill gpg-agent >/dev/null 2>&1 || :'\'' EXIT HUP INT TERM; gpg --homedir "$keyring" --batch --no-tty --pinentry-mode loopback --passphrase "" --quick-generate-key "libtmux spike <noreply@spike.invalid>" default default 1d; fd --type f '\''\.(jar|pom|module)$'\'' "$repository" | sort > "$artifacts"; test -s "$artifacts"; while IFS= read -r artifact; do gpg --homedir "$keyring" --batch --no-tty --yes --pinentry-mode loopback --passphrase "" --armor --detach-sign "$artifact"; done < "$artifacts"; fd --type f '\''\.asc$'\'' "$repository" | sort > "$signatures"; test -s "$signatures"; test "$(wc -l < "$artifacts")" -eq "$(wc -l < "$signatures")"; while IFS= read -r signature; do gpg --homedir "$keyring" --batch --no-tty --verify "$signature" "${signature%.asc}"; done < "$signatures"; agent_socket="$(gpgconf --homedir "$keyring" --list-dirs agent-socket)"; gpgconf --homedir "$keyring" --kill gpg-agent; test ! -S "$agent_socket"; trap - EXIT HUP INT TERM'
```

- [ ] Repeat the two-copy unsigned reproducibility oracle from the integrated
      source tree with fresh product caches and the exact expected publication
      path set.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; run="$(mktemp -d "$root/build/integrated-repro-run.XXXXXX")"; token="$(basename "$run")"; for copy in a b; do target="$run/$copy"; mkdir -p "$target"; tar --exclude=.gradle --exclude="*/build" -C "$root/synthesis" -cf - . | tar -C "$target" -xf -; LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC SOURCE_DATE_EPOCH=1786233600 "$root/artifacts/run-gradle.sh" "$token-$copy" fresh product "$target" -Dfile.encoding=UTF-8 clean publishSpike verifySpikePublication; repository="$target/build/spike-repository"; test -d "$repository"; (cd "$repository"; fd --type f '\''\.(jar|pom|module)$'\'' . | sed "s#^./##" | sort) > "$run/$copy.paths"; test -s "$run/$copy.paths"; diff -u "$root/build/oracle/expected-artifacts.txt" "$run/$copy.paths"; (cd "$repository"; while IFS= read -r artifact; do sha256sum "$artifact"; done < "$run/$copy.paths") > "$run/$copy.sha256"; done; diff -u "$run/a.paths" "$run/b.paths"; diff -u "$run/a.sha256" "$run/b.sha256"; printf "integrated reproducibility evidence: <%s>\n" "$token"'
```

### Step 8.4: Run independent Maven and Gradle journeys

- [ ] From a plain Maven consumer, create a server and session, split a window,
      send keys, capture output, refresh, filter with a generated field, collect
      cardinality, and close all owned resources.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; consumer="$root/synthesis/consumers/maven"; "$root/artifacts/run-maven.sh" integrated-maven fresh "$root/tools/apache-maven-3.9.16/bin/mvn" "$consumer" -f "$consumer/pom.xml" --batch-mode --errors verify -Dtmux.matrix="$root/tools/tmux"'
```

- [ ] Run the same journey from a plain Gradle consumer.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" integrated-gradle fresh product "$root/synthesis/consumers/gradle" clean test -PtmuxMatrix="$root/tools/tmux"'
```

- [ ] Prove `Server.open()` closes exactly one owned transport,
      `Server.using()` never closes a borrowed transport, two servers can share
      one borrowed transport, and closing either server leaves the other usable.
- [ ] Compile option, hook, environment, buffer, prompt, menu, and popup surface
      sketches against the selected public contracts even when their production
      implementations do not yet exist. The sketches must use only intended public
      types and must not claim runtime parity.
- [ ] Run a consumer JUnit test receiving exact socket fixtures and prove
      cleanup after an intentional assertion failure.
- [ ] Run the engine-ops boundary consumer with a second transport, capability
      catalog, target resolver, immutable snapshot, and canonical query-schema
      fixture without repository-internal imports.
- [ ] Have an independent reviewer run both consumers from their README alone.
      The reviewer starts in each consumer directory and receives no setup
      instructions beyond that README. Treat any repository-internal import or
      undocumented setup knowledge as a hard API-friction failure.

### Step 8.5: Repeat newly exposed bakeoffs

- [ ] For every integration or consumer failure, state one falsifiable question
      and build three minimal variants under a new numbered directory in
      `$LIBTMUX_JAVA_SPIKE_ROOT/synthesis/`.
- [ ] Apply the winning graft to a fresh integrated rebuild and rerun all
      previously passing gates as well as the new gate.
- [ ] Continue until the integrated slice has no known unresolved design
      stumble. Only Central account-side namespace ownership may remain an
      external release precondition. Missing tmux lanes, implementation
      failures, and failed architecture gates block the clean-rewrite handoff.

```console
$ sh -eu -c 'root="$(git config --local --get codex.libtmux-java-spike-root)"; "$root/artifacts/run-gradle.sh" integrated-synthesis reuse product "$root/synthesis" clean hardGates publishSpike verifySpikePublication integratedJourneys -PtmuxMatrix="$root/tools/tmux"'
```

### Step 8.6: Record and commit synthesis evidence

- [ ] Write `06-integrated-synthesis.md` with the selected contracts,
      cross-boundary failures, respikes, all-gate matrix, consumer feedback,
      artifact digests, and explicit preconditions.
- [ ] Request independent architecture and downstream-API review, resolve
      findings, then commit only the note and any evidence-driven architecture
      correction.

```console
$ git add java/docs/spikes/06-integrated-synthesis.md java/docs/design/2026-08-09-architecture.md
```

```console
$ sh -eu -c 'git diff --cached --name-only | rg -x java/docs/spikes/06-integrated-synthesis.md >/dev/null; git diff --cached --name-only | while IFS= read -r path; do case "$path" in java/docs/spikes/06-integrated-synthesis.md|java/docs/design/2026-08-09-architecture.md) ;; *) exit 64;; esac; done'
```

```console
$ git commit -F - <<'EOF'
Java(docs[spike]): Synthesize measured design

why: Selected components must pass together before the clean rewrite.

what:
- Record integrated gates and consumer journeys
- Reconcile the architecture with executable evidence
EOF
```

---

## Task 9: Audit evidence and destroy every prototype

**Durable files:**

- Create: `java/docs/reviews/2026-08-09-spike-evidence.md`
- Create: `java/docs/plans/2026-08-09-clean-rewrite.md`
- Modify only for reviewed evidence corrections:
  `java/docs/design/2026-08-09-architecture.md`

### Step 9.1: Perform an independent evidence audit

- [ ] Give an independent reviewer the objective, architecture, protocol,
      studies, parity inventories, bakeoff notes, synthesis note, complete
      read-only prototype source, shared oracles, raw evidence, and digest
      manifest before deleting the temporary root.
- [ ] Require the reviewer to check objective coverage, source pinning,
      contender independence, shared-oracle fairness, hard-gate results, result
      digests, winner/graft logic, respike completeness, API friction, and hidden
      unresolved failures.
- [ ] Classify each finding as blocking, actionable non-blocking, or rejected
      with source-backed rationale. Resolve every blocking and actionable
      non-blocking finding and rerun its affected gate before closing the audit.

### Step 9.2: Verify the parity and evidence closure

- [ ] Draft `java/docs/plans/2026-08-09-clean-rewrite.md` from the accepted
      evidence before closing parity. Do not stage or implement it yet. Map every
      Python API row and behavior-test row to a named clean implementation task
      and contract test in that draft, then prove the inventories have no
      unmapped row.
- [ ] Prove every architecture verification gate appears in a successful
      synthesis result. Central account-side namespace ownership is the only
      permitted external release precondition and is not a substitute for a
      selected group/package or consumer test.
- [ ] Prove no note reports a provisional winner as accepted after a failed
      executable gate.
- [ ] Record the final verdict in `2026-08-09-spike-evidence.md`. This verdict
      authorizes only the clean-rewrite plan, not production readiness.

### Step 9.3: Delete the disposable tree safely

- [ ] Complete or stop every delegated agent before deletion; no worker may
      retain a pending command against the disposable root. Only the
      coordinating agent runs cleanup.
- [ ] Before deletion, run the spike-owned process verifier over the PID/start
      instant ledger and require no live server, client, test worker, or child
      process. Move the owned sentinel to a nonce-bound closing tombstone in the
      stored parent before recursive deletion. The tombstone makes an
      interrupted deletion resumable. The immutable recovery bundle remains
      until the final transition. After the root is absent, removing the
      individual root key marks successful verification; only then remove the
      tombstone and remaining individual keys. Remove the bundle last. Delete
      without `--force`.

```console
$ sh -eu -c '
count_values() {
    git config --local --get-all "$1" | awk "END { print NR + 0 }"
}
match_optional() {
    key="$1"
    expected="$2"
    count="$(count_values "$key")"
    case "$count" in
        0) ;;
        1) test "$(git config --local --get "$key")" = "$expected" ;;
        *) printf "duplicate Git-local value: %s\n" "$key" >&2; exit 64 ;;
    esac
}
bundle_key=codex.libtmux-java-spike-bundle
test "$(count_values "$bundle_key")" -eq 1
bundle="$(git config --local --get "$bundle_key")"
old_ifs=$IFS
IFS="$(printf '\t')"
set -f
set -- $bundle
set +f
IFS=$old_ifs
test "$#" -eq 4
configured_repo="$1"
configured_parent="$2"
nonce="$3"
configured="$4"
root="$(realpath -m -- "$configured")"
parent="$(realpath -e -- "$configured_parent")"
repo="$(realpath -e -- "$(git rev-parse --show-toplevel)")"
home="$(realpath -e -- "$HOME")"
test "$configured" = "$root"
test "$configured_parent" = "$parent"
test "$configured_repo" = "$repo"
match_optional codex.libtmux-java-spike-root "$root"
match_optional codex.libtmux-java-spike-parent "$parent"
match_optional codex.libtmux-java-spike-repo "$repo"
match_optional codex.libtmux-java-spike-nonce "$nonce"
root_key_count="$(count_values codex.libtmux-java-spike-root)"
parent_key_count="$(count_values codex.libtmux-java-spike-parent)"
repo_key_count="$(count_values codex.libtmux-java-spike-repo)"
nonce_key_count="$(count_values codex.libtmux-java-spike-nonce)"
test "$parent" = "$(realpath -e -- /tmp)"
test "${#nonce}" -eq 10
case "$nonce" in
    ""|*[!A-Za-z0-9]*) exit 64 ;;
esac
test "$(dirname -- "$root")" = "$parent"
test "$(basename -- "$root")" = "libtmux-java-spike.$nonce"
case "$root" in
    /|"$home"|"$home"/*|"$repo"|"$repo"/*) exit 64 ;;
esac
case "$home" in
    "$root"|"$root"/*) exit 64 ;;
esac
case "$repo" in
    "$root"|"$root"/*) exit 64 ;;
esac
sentinel="$root/.libtmux-java-spike-owned-$nonce"
closing="$parent/.libtmux-java-spike-closing-$nonce"
if test -e "$root" || test -L "$root"; then
    test -d "$root"
    test ! -L "$root"
    test "$(realpath -e -- "$root")" = "$root"
    test "$(stat -c %u -- "$root")" = "$(id -u)"
    test "$(stat -c %a -- "$root")" = 700
fi
if test -f "$sentinel" && test ! -L "$sentinel"; then
    test "$root_key_count" -eq 1
    test "$parent_key_count" -eq 1
    test "$repo_key_count" -eq 1
    test "$nonce_key_count" -eq 1
    test -d "$root"
    test ! -e "$closing"
    test ! -L "$closing"
    test "$(stat -c %u -- "$sentinel")" = "$(id -u)"
    test "$(stat -c %a -- "$sentinel")" = 600
    ledger="$root/artifacts/owned-processes.tsv"
    lock="$ledger.lock"
    token_helper="$root/artifacts/process-start-token.sh"
    validator="$root/artifacts/validate-owned-process-ledger.sh"
    writer="$root/artifacts/record-owned-process.sh"
    verifier="$root/artifacts/verify-no-owned-processes.sh"
    for file in "$ledger" "$lock" "$token_helper" "$validator" "$writer" "$verifier"; do
        test -f "$file"
        test ! -L "$file"
        test "$(stat -c %u -- "$file")" = "$(id -u)"
    done
    test "$(stat -c %a -- "$ledger")" = 600
    test "$(stat -c %a -- "$lock")" = 600
    test "$(stat -c %a -- "$token_helper")" = 700
    test "$(stat -c %a -- "$validator")" = 700
    test "$(stat -c %a -- "$writer")" = 700
    test "$(stat -c %a -- "$verifier")" = 700
    test -x "$token_helper"
    test -x "$validator"
    test -x "$writer"
    test -x "$verifier"
    "$verifier" "$ledger" "$token_helper" "$validator"
    mv -- "$sentinel" "$closing"
elif test -f "$closing" && test ! -L "$closing"; then
    test ! -e "$sentinel"
    test ! -L "$sentinel"
elif test ! -e "$root" && test ! -L "$root" && test ! -e "$sentinel" && test ! -L "$sentinel" && test ! -e "$closing" && test ! -L "$closing"; then
    test "$root_key_count" -eq 0
else
    printf "ownership sentinel is missing or ambiguous\n" >&2
    exit 64
fi
if test -f "$closing"; then
    test ! -L "$closing"
    test "$(stat -c %u -- "$closing")" = "$(id -u)"
    test "$(stat -c %a -- "$closing")" = 600
fi
if test -d "$root"; then
    rm --recursive --one-file-system -- "$root" </dev/null
fi
test ! -e "$root"
test ! -L "$root"
if test "$root_key_count" -eq 1; then
    git config --local --unset-all codex.libtmux-java-spike-root
fi
if test -f "$closing"; then
    rm -- "$closing"
fi
for key in \
    codex.libtmux-java-spike-repo \
    codex.libtmux-java-spike-parent \
    codex.libtmux-java-spike-nonce
do
    if test "$(count_values "$key")" -eq 1; then
        git config --local --unset-all "$key"
    fi
done
git config --local --unset-all "$bundle_key"
'
```

- [ ] Prove no ownership handoff remains.

```console
$ ! git config --local --get-regexp '^codex\.libtmux-java-spike-(root|parent|repo|nonce|bundle)$'
```

- [ ] Prove no prototype Java, Gradle, JFR, log, socket, or build artifact was
      copied into the repository.

```console
$ git status --short
```

```console
$ sh -eu -c 'git diff --cached --quiet; expected="$(printf "%s\n" java/docs/plans/2026-08-09-clean-rewrite.md java/docs/reviews/2026-08-09-spike-evidence.md)"; test "$(git ls-files --others --exclude-standard | sort)" = "$expected"; unstaged="$(git diff --name-only)"; test -z "$unstaged" || test "$unstaged" = java/docs/design/2026-08-09-architecture.md'
```

```console
$ fd --hidden --type f --exclude .git . java | sort
```

```console
$ sh -eu -c 'test ! -e .venv; test ! -e .pytest_cache; test -z "$(fd --hidden --type d "^(__pycache__|\\.pytest_cache|\\.venv)$" . --exclude .git)"'
```

Expected: the evidence review and clean-plan draft are the only untracked files;
only the architecture may have an unstaged evidence correction. The committed
plans, schema, studies, parity inventories, and spike notes remain, but no
production source tree exists yet.

### Step 9.4: Run the documentation gate and commit the review

- [ ] Scan all durable artifacts for placeholders, AI signatures, moving source
      links, and private paths.

```console
$ ! rg -n --glob '!java/docs/plans/2026-08-09-disposable-spikes.md' 'TODO|TBD|Generated by|blob/(master|main)/|/home/|Users/|(^|[^A-Za-z])[A-Za-z]:[/\\]' java
```

Expected: no matches.

```console
$ git diff --check
```

- [ ] Commit only the independent review and reviewed evidence corrections.

```console
$ git add java/docs/design/2026-08-09-architecture.md java/docs/parity/python-api.md java/docs/parity/test-map.md java/docs/reviews/2026-08-09-spike-evidence.md java/docs/spikes/00-protocol.md java/docs/spikes/01-build-and-coordinates.md java/docs/spikes/02-transport.md java/docs/spikes/03-hydration.md java/docs/spikes/04-query-metamodel.md java/docs/spikes/05-junit-lifecycle.md java/docs/spikes/06-integrated-synthesis.md java/docs/studies/cpython-subprocess.md java/docs/studies/engine-ops-seams.md java/docs/studies/java-library-patterns.md java/docs/studies/tmux-protocol.md java/schema/filter-expr-v1.schema.json java/schema/fixtures/filter-expr-v1.jsonl
```

```console
$ sh -eu -c 'test -n "$(git diff --cached --name-only)"; git diff --cached --name-only | while IFS= read -r path; do case "$path" in java/docs/design/2026-08-09-architecture.md|java/docs/parity/python-api.md|java/docs/parity/test-map.md|java/docs/reviews/2026-08-09-spike-evidence.md|java/docs/spikes/00-protocol.md|java/docs/spikes/01-build-and-coordinates.md|java/docs/spikes/02-transport.md|java/docs/spikes/03-hydration.md|java/docs/spikes/04-query-metamodel.md|java/docs/spikes/05-junit-lifecycle.md|java/docs/spikes/06-integrated-synthesis.md|java/docs/studies/cpython-subprocess.md|java/docs/studies/engine-ops-seams.md|java/docs/studies/java-library-patterns.md|java/docs/studies/tmux-protocol.md|java/schema/filter-expr-v1.schema.json|java/schema/fixtures/filter-expr-v1.jsonl) ;; *) exit 64;; esac; done; git diff --cached --name-only | rg -x java/docs/reviews/2026-08-09-spike-evidence.md'
```

```console
$ git commit -F - <<'EOF'
Java(docs[review]): Accept spike evidence

why: The clean rewrite needs independently audited design inputs.

what:
- Record adversarial evidence and API review
- Close parity, synthesis, and prototype-deletion gates
EOF
```

### Step 9.5: Hand off to the clean-rewrite plan

- [ ] Finalize the drafted `java/docs/plans/2026-08-09-clean-rewrite.md`. It must
      start from an empty production source tree, split the
      architecture's eight implementation slices into independently executable
      TDD tasks, map every parity row and accepted gate to an owner, and prohibit
      copying prototype source.
- [ ] Give independent coverage, Effective Java/API, and runtime/build reviewers
      the objective, accepted architecture, complete spike evidence, parity
      inventories, and proposed clean plan. Resolve every blocker and record the
      accepted verdict in the plan. A missing reviewer input or unresolved gate
      blocks implementation.
- [ ] Run the documentation, privacy, shell-syntax, and exact-file staging gates,
      then commit only the reviewed clean plan.

```console
$ prettier --check java/docs/plans/2026-08-09-clean-rewrite.md
```

```console
$ ! rg -n 'TODO|TBD|Generated by|blob/(master|main)/|/home/|Users/|(^|[^A-Za-z])[A-Za-z]:[/\\]' java/docs/plans/2026-08-09-clean-rewrite.md
```

```console
$ git diff --check
```

```console
$ git add java/docs/plans/2026-08-09-clean-rewrite.md
```

```console
$ test "$(git diff --cached --name-only)" = java/docs/plans/2026-08-09-clean-rewrite.md
```

```console
$ git commit -F - <<'EOF'
Java(docs[plan]): Plan clean rewrite

why: Accepted spike evidence needs an executable production handoff.

what:
- Map parity and design gates to clean TDD slices
- Record independent plan acceptance
EOF
```

- [ ] Do not begin clean implementation in this plan.
