# AGENTS.md

Guidance for agents working on libtmux for Java. This repository is the whole
of the port; nothing outside it governs the Java code.

## Keep off other ports' sockets

Sibling libtmux ports — Python, Go, Rust, TypeScript, C#, C++ — are worked on in
parallel on this machine, and every one of them starts real tmux servers in
`/tmp`. Their debris outlives their test runs: servers from an exited benchmark
stay up, holding ptys, until something kills them.

**Every tmux server this project starts belongs under a path that names this
port.** Use one of:

- `/tmp/libtmux-java-test/…` — anything a test starts.
- `/tmp/libtmux-java-dev/…` — anything started by hand while investigating.

Never `/tmp/libtmux-…` on its own, and never the default socket. `libtmux-` is
the prefix a sibling port is also using, which is the whole problem.

Why it matters more than tidiness: a suite sharing `/tmp` with another port's
leftovers fails intermittently, under load, in places that have nothing to do
with the change under test — `SERVER_GONE`, misframed rows, `fork: No space
left on device`. Every one of those reads as a regression in whatever you last
touched, and hours go into a bug that was never yours.

Before blaming a change for an intermittent real-tmux failure, count what is
already running:

```console
$ pgrep -c tmux
```

Then find whose it is, since a socket path exists only in the process's own
command line:

```console
$ for p in $(pgrep tmux); do tr '\0' ' ' < /proc/$p/cmdline | rg -o '\-S [^ ]+'; done
```

Kill only sockets under this port's roots. Another port's servers are not
yours to reap.

## Socket paths are short by necessity

A unix socket path cannot exceed about 104 bytes, and tmux reports a longer one
as `error connecting to … (File name too long)`. That rules out sockets under a
build directory or a deep scratch path, and it is why the roots above are short.

## Commands

Everything, including formatting and the static analysis gates:

```console
$ ./gradlew check
```

Against every supported tmux release:

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=/path/to/tmux/builds
```

Regenerate the measured comparison of the carriers, which is never hand-edited:

```console
$ ./gradlew modeBenchmark -PlibtmuxTmux=/path/to/tmux
```

## Carriers change cost, not answers

`ExecutionMode` chooses how a command travels, and a suite can be run under any
of them:

```console
$ LIBTMUX_MODE=control ./gradlew check
```

A failure that appears only under one carrier is a real finding — the library
claims the answer does not depend on the carrier, and
`ExecutionModeConformanceTest` is where that claim is gated. Confirm it against
a clean `/tmp` first: the failure modes of cross-port debris and of a genuine
carrier defect look alike.

## Comments earn their maintenance cost

A comment ships only if it passes all three gates. Fail any: delete or rewrite.
Borderline: delete — borderline means the information is reconstructible, which
is what makes deletion cheap.

**Loss.** Three years from now, would losing this cost a maintainer real time
rediscovering intent, an invariant, a constraint, or a failure mode the code and
tests do not already make obvious?

**Elite.** Would SQLite, Redis, the Go standard library, or CPython write this
comment, at this length? Those projects state the constraint and stop. They do
not argue with an imagined objector.

**Upkeep.** Will it stay true without maintenance? A comment that hand-syncs a
value the code owns — a count, an offset, a line reference, a duplicated
constant — is false the first time that value moves.

### Ceiling

One or two lines. A comment reaching four is either carrying several facts, in
which case split it, or arguing, in which case cut it to the fact.

Rationale, alternatives weighed, and the story of how the code got here belong
in the commit message: timestamped, attached to the exact diff, and free to
maintain.

A comment often holds both a constraint and the deliberation that found it. Keep
the constraint, cut the deliberation. "Runs at most once per second" survives;
"this is the right trade for now" does not.

### Keep

- Why over how: upstream quirks, protocol and compatibility constraints,
  performance tradeoffs still part of the contract.
- Invariants, preconditions, ordering, lifetime, and concurrency requirements
  that types and tests cannot express.
- Code that looks wrong but is not, so a later cleanup does not reintroduce the
  bug.
- A high-level sketch of an algorithm whose local operations do not reveal the
  whole.

### Delete

- Narration of the next lines; code translated into English.
- Restated names, types, defaults, or control flow.
- Values duplicated from the code and hand-synced.
- Justification, hedging, or apology for a choice.
- Speculation about future requirements.
- History version control already holds, including commented-out code.
- Ticket and issue numbers. They say nothing to a reader without tracker access,
  and they rot when the tracker moves. Unfinished work goes in the tracker, not
  the source.
- Transient observations — "currently", "for now", "the latest release" —
  that go stale with no nearby edit.

### The upkeep gate in practice

It reaches values that track our own code. It does not reach frozen external
facts.

Bad (Delete):

```java
// There are 321 tests to complete for servers.
```

Good (Keep):

```java
// tmux < 3.2 reports the pane ID only after the command completes,
// so this query must stay separate.
```

### Documentation exception

Doctests, minimal usage examples, and param, return, and raises lines on public
API are exempt from the loss gate — they serve the caller, not the maintainer.
They are exempt from nothing else. Ceiling: a good man page entry.

Javadoc summaries and `@param`, `@return`, and `@throws` tags fall under this
exception.

## Git Commit Standards

Format commit messages as:
```
Scope(type[detail]): concise description

why: Explanation of necessity or impact.

what:
- Specific technical changes made
- Focused on a single topic
```

Keep the subject ≤50 chars (excluding any trailing `(#NN)` PR ref); wrap
body lines at ≤72 chars. Separate the `why:` and `what:` blocks with a
blank line.

Common commit types:
- **feat**: New features or enhancements
- **fix**: Bug fixes
- **refactor**: Code restructuring without functional change
- **docs**: Documentation updates
- **chore**: Maintenance (dependencies, tooling, config)
- **test**: Test-related updates
- **style**: Code style and formatting
- **java(deps)**: Dependencies
- **java(deps[dev])**: Dev Dependencies
- **ai(rules[AGENTS])**: AI rule updates

Example:
```
Pane(feat[sendKeys]): Add support for a literal flag

why: Send characters without tmux interpreting them.

what:
- Add a literal field to SendKeysRequest
- Pass -l when it is set
```

### Release commits

Never create tags. Never push tags. The user handles tagging and tag
pushes (tags trigger the CI publish workflow).

Release commit subjects are plain and short: `Tag v<version>`. Put
the detailed why/what in the commit body. Don't use the
`Scope(type[detail]):` format for releases — don't bury the lede.

For multi-line commits, use heredoc to preserve formatting:
```bash
git commit -m "$(cat <<'EOF'
Scope(feat[detail]): Concise description

why: Explanation of the change.

what:
- First change
- Second change
EOF
)"
```

## Code Blocks

Code blocks are paste-and-run units: pasting one block runs exactly one
intended action. Doctests and other executed examples are exempt — the test
suite runs them, nobody pastes them.

- **One command per block.** Multiple steps may share a block only when
  explicitly chained with `&&`, `;`, or `\` continuations — the chain is
  then one logical command.
- **Explanations go in prose above the block**, never as `#` comments inside it.
- **Command menus are per-command blocks with prose lead-ins**, not tables.
- **Shell commands use the `console` tag with a `$ ` prefix.** This separates
  interactive commands from scripts and enables prompt-aware copy.
- **Split long commands with `\`** — one flag or flag+value pair per indented
  continuation line, positional arguments last.

Good:

Show the last ten commits as a graph:

```console
$ git log \
    --max-count=10 \
    --graph \
    --oneline
```

Bad:

```console
# Show the last ten commits as a graph
$ git log --max-count=10 --graph --oneline
```
