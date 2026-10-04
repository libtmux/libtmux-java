# Writing

How this project writes prose, for humans and agents alike. It governs
`README.md`, `CHANGELOG.md`, release notes, commit messages, CLI and help
text, error messages, API documentation, source comments, and migration
guides.

For build, test, and pull request workflow, see
[`CONTRIBUTING.md`](CONTRIBUTING.md).

Markdown files here wrap at 80 columns. A pull request or issue body does not:
GitHub renders a single newline as a space inside a file and as a line break in
a comment, so a hard-wrapped comment body arrives as ragged stubs.

## README

The README is the door; the guides under `docs/guide/` and the Javadoc are the
manual. It answers what this is, whether you can use it, how to install it, what
normal use looks like, and what contract you are buying into — in that order,
and then it stops.

Four things near the top are compatibility claims, and they move together:

- **The title is `libtmux for Java`.** Every port titles its README the same
  way.
- **The alpha notice states the terms in full.** Releases carry an `-alpha`
  prerelease tag, the API is not settled, any release may change or remove
  exported identifiers without a deprecation period, pin an exact version, not
  recommended for production. `CHANGELOG.md` and the `Status` section say the
  same thing in the same words.
- **The requirements are JDK 25 or newer, and tmux 3.2a through 3.7c.** The tmux
  range is not a claim — the matrix runs every lane of it — so it may not drift
  from `workflows/tmux-matrix.yml`.
- **Coordinates are group `io.github.libtmux`, imported through
  `libtmux-bom`**, shown for both Gradle and Maven, because a reader arrives
  with one of the two already open.

Every Java block in the README is compiled and then run against a real tmux by
[the documentation module](../docs/README.md#how-these-pages-are-tested). A snippet is a test: it cannot be illustrative
pseudo-code, an elided `try` block, or a call that no longer exists, and one
that stops working fails the build. A block that is meant not to compile says so
and is checked for being rejected.

Claims have to be falsifiable. "Zero runtime dependencies" is one. A performance
claim carries its number and the command that reproduces it, or it does not
appear.

Headings are literal: `Requirements`, `Installation`, `Documentation`, `Status`.
A reader scanning for how to install does not look under a heading called
something clever, and neither does an agent.

Badges are metadata, not an introduction. Each of the five present — CI, tmux
matrix, Maven Central, license, status — answers a question a reader would
otherwise have to go and ask. A sixth needs the same defence.

No screenshot carries information that exists only in the screenshot. Images go
stale, do not survive grep, and reach a screen reader as nothing.

## Migration notes

Put API breakages and upgrade instructions in the root
[`MIGRATION.md`](../MIGRATION.md) file. Cover renames, removals, and changes to
defaults or failure contracts with a concrete replacement or instructions for
callers. Add notes under `## Next release`; the maintainer assigns the version
when releasing.

The README and getting-started guide show the current API. Link to
`MIGRATION.md` with a short upgrading sentence instead of embedding migration
sections there. Keep the changelog entry brief: state the impact and
replacement, then link to the migration notes for detailed steps.

Migration examples remain part of the documentation suite. Use its `does-not-compile`
directive for obsolete Java examples that demonstrate a rejected API; current
replacement examples must compile and run.

## Changelog

`CHANGELOG.md` is a ledger for the people who consume releases, not a rendering
of `git log`. The question a reader arrives with is whether an entry affects
them, so one change gets one entry, and the entry leads with what they will
observe.

Entries land under `## Unreleased`. The maintainer assigns the version when
cutting a release, so nothing written here predicts one.

Group under `Added`, `Changed`, `Fixed`, `Removed`, `Documented`, or `Security`,
newest release first, dated `YYYY-MM-DD`.

An entry opens with a bold clause naming what changed, then gives the prose that
makes it decidable:

```markdown
- **`capture_since` now reports an observe-only tmux effect.** Reading from a
  cursor leaves tmux state unchanged, so the capability metadata no longer
  overstates what the call changes.
```

Name identifiers literally: `Pane.capture`, `LIBTMUX_TOOLSETS`,
`--rerun-tasks`, `tmux://capabilities`. Lead with a concrete verb: add, fix,
remove, reject, `now`, or `no longer`.

State a changed default explicitly, and an incompatibility more explicitly
still, with the way forward in the same entry.

Do not sell a fix: "no longer returns another command's reply", not "improves
reliability". Do not describe effort, and never write "various fixes" — a set of
changes too small to enumerate is reported as no user-visible behaviour having
changed. A refactor nothing can observe is not an entry at all.

This project is alpha, so an incompatibility is expected rather than
exceptional. It still gets stated plainly, with what to write instead.

## Release notes

A release note answers a different question from the changelog: not "what
changed" but "should I upgrade, and what do I need to know first". It is
editorial where the changelog is exhaustive, and it may leave things out.

Order it capability, then consequence, then compatibility:

> 0.0.1-alpha.7 adds streaming capture, and rejects `windowId` at pane scope
> rather than ignoring it. Pass `scope: window` to read at window scope. JDK 25
> and tmux 3.2a through 3.7c are unchanged.

The title is plain — the version, optionally preceded by `libtmux for Java`.
Never "we are excited to announce"; state what shipped.

Link `CHANGELOG.md` rather than pasting it. How a release is actually cut is in
[`RELEASING.md`](../RELEASING.md).

## Commit messages

```
Scope(type[detail]): concise description

why: Explanation of necessity or impact.

what:
- Specific technical changes made
- Focused on a single topic
```

Keep the subject to 50 characters or fewer, excluding any trailing `(#NN)` pull
request reference, and wrap body lines at 72. Separate the `why:` and `what:`
blocks with a blank line.

Common types:

- **feat**: New features or enhancements
- **fix**: Bug fixes
- **refactor**: Code restructuring without functional change
- **docs**: Documentation updates
- **chore**: Maintenance (dependencies, tooling, config)
- **test**: Test-related updates
- **style**: Code style and formatting
- **java(deps)**: Dependencies
- **java(deps[dev])**: Dev dependencies
- **ai(rules[AGENTS])**: AI rule updates

Example:

```
Pane(feat[sendKeys]): Add support for a literal flag

why: Send characters without tmux interpreting them.

what:
- Add a literal field to SendKeysRequest
- Pass -l when it is set
```

Use a heredoc so the formatting survives the shell:

```console
$ git commit -m "$(cat <<'EOF'
Scope(feat[detail]): Concise description

why: Explanation of the change.

what:
- First change
- Second change
EOF
)"
```

The rationale, the alternatives weighed, and what was rejected go in the body.
It is the durable record — timestamped, attached to the exact diff, and free to
maintain — which is why a source comment does not carry them.

No emoji, no trailing attribution, no tool metadata.

### Release commits

Never create tags. Never push tags. The owner handles tagging and tag pushes,
because a tag triggers the publish workflow.

A release commit subject is plain and short: `Tag v<version>`. The detailed why
and what go in the body. Do not use the `Scope(type[detail]):` format for a
release — it buries the lede.

## API documentation

Javadoc on public API is a specification, not a programming guide. It states
what a caller may rely on, which is the part the signature cannot state on its
own.

The first sentence becomes the summary in every index the tool generates, so it
has to stand alone and end at the first period. Lead with a verb describing the
thing:

```java
/** Captures the visible contents of this pane. */
```

Not "This method is used to capture…". Deleting the introductory clause is the
most useful edit available.

Document the contract, not the mechanism. What a reader infers the API from:

- **Nullability.** Packages are `@NullMarked`, so a reference is non-null unless
  it carries `@Nullable`. NullAway enforces that in JSpecify mode at build time,
  which makes the annotation the documentation — a prose sentence repeating it
  is a second copy to keep true.
- **What a returned collection is.** Whether it is immutable, whether it is a
  view over live state, and what order it is in.
- **Blocking and threading.** Whether the call waits on tmux, and whether the
  type is safe to use from more than one thread.
- **Failure.** Which exceptions, and the condition producing each, the unchecked
  ones included.
- **Lifetime.** What holds after the owning `Server` is closed.

Tags carry their weight:

- `@param` completes a phrase: `@param history scrollback lines to include, 0
  for the visible screen only`. Not `@param history the history`.
- `@return` documents semantics, not the type: `@return the pane contents, never
  null`. Not `@return a String`.
- `@throws` documents the condition: `@throws IllegalArgumentException if
  {@code history} is negative`. Not "when an illegal argument is supplied".
- `@since` carries the product version the declaration first appeared in.
- `@deprecated` names the replacement and shows the migration, mirroring the
  `@Deprecated(since = …, forRemoval = …)` beside it. Where removal is not
  actually decided, do not invent a version.

Architecture belongs in `package-info.java` — what the package is for, its major
types, the threading model, what holds across all of it — so that type-level
Javadoc stays local and contractual.

Javadoc is exempt from the loss gate below, because it serves the caller rather
than the maintainer. It is exempt from nothing else. Ceiling: a good man page
entry.

## Source comments

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

Minimal usage examples, and param, return, and throws lines on public API are
exempt from the loss gate — they serve the caller, not the maintainer. They are
exempt from nothing else. Ceiling: a good man page entry.

Javadoc summaries and `@param`, `@return`, and `@throws` tags fall under this
exception.

## Terminology and capitalization

One voice across every surface. A Javadoc comment says what a caller may rely
on; a changelog entry says what changed; an error message names what failed;
prose says what happens. All of them are present tense, lead with the thing
being described, and stop.

The most useful editing operation is deleting the introductory sentence.

| Instead of | Prefer |
| --- | --- |
| "We added…" | "`Pane` now supports…" |
| "New and improved" | "`Pane` now…" |
| "powerful", "seamless" | state the capability |
| "easily", "simply", "just" | omit |
| "robust" | name the failure that is handled |
| "comprehensive" | name what is covered |
| "production-ready" | state the guarantee |
| "optimized" | give the magnitude |
| "various fixes" | name the components |
| "under the hood" | omit unless observable |
| "please note that" | state the fact |
| "leverage", "utilize" | "use" |
| "delve into" | "read", or omit |
| "best practices" | name the practice |
| "in order to" | "to" |

Spell the project's own terms one way, everywhere. Grep is part of debugging,
users paste error text into a search box, and an agent reasoning across files
has nothing but the string:

- **tmux** is lowercase, always, including at the start of a sentence.
- **Javadoc** is capitalized; **JDK** and **JVM** are upper case. "Java 25" and
  "JDK 25" both appear upstream — this project writes **JDK 25**.
- A **pane**, **window**, **session**, and **server** are what tmux calls them.
  Do not introduce a synonym for one.
- Write the identifier, not a description of it: `LIBTMUX_TOOLSETS=inspect`,
  not "the toolset environment variable"; `--rerun-tasks`, not "the rerun flag";
  `/tmp/libtmux-java-test/`, not "the test socket directory".

Treat AI slop as review-hostile noise. The goal is information density:

- **AI signatures.** No "Generated by", no conversational filler, no unexplained
  emoji, no tool metadata.
- **Brittle references.** No hard-coded line numbers, fragile file counts, dated
  "as of" claims, bare SHAs, or local absolute paths — unless they are strict
  evidentiary artefacts such as a benchmark log.
- **Diff narration.** Do not restate what moved, was renamed, or was removed in
  anything the reader holds alongside the diff: code, Javadoc, README, or a pull
  request description. The diff and the commit message already carry it.
- **Branch-internal narrative.** Do not mention intermediate states, abandoned
  approaches, or "no longer" behaviour unless users of a published release
  actually experienced the old state.
- **Low-value scaffolding.** No ownerless TODOs, unused future-proofing, debug
  artefacts, or defensive wrappers around failure modes nothing can reach.
- **Coded labels.** Write rules and findings as plain imperatives. No `[R1]`,
  `Option B`, or any index a reader has to decode.

Never delete a comment documenting an invariant, a protocol constraint, a
platform quirk, or an upstream workaround. Those are the facts
[Source comments](#source-comments) keeps.

## Code blocks

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

## Examples

<!-- shared:examples -->

An example is code written for a reader: a program under `examples/`, code in
a doc comment or docstring, and every fenced block in a README or docs page.
Shell blocks also follow [Code blocks](#code-blocks).

The text between the shared markers is the same in every libtmux port.
Change it in all of them together.

### Width

- **Examples stay within 80 columns.** They render in fixed-width boxes that
  scroll sideways, and 80 columns fits a libtmux.org code block in a
  laptop-width window. Comments inside examples wrap at 80 too.
- **The width check enforces it.** It reads the tracked files that
  `.github/example-width.toml` names and fails on a wider line. It measures
  the whole source line, so code in a doc comment counts its indent and
  comment marker. It skips output (a fence tagged `text`, and what a
  `console` block prints), hidden setup lines, and a line that is only a URL;
  an untagged fence counts as code.
- **A line that must stay wider is listed there with its reason.** An entry
  that no longer matches a line fails the check, so no stale entry stays.
- **The formatter's width is the hard limit for all other source.** Example
  directories set their formatter to 80 where the formatter takes a width.

### Reaching 80

- **Change the code, not the line breaks.** A formatter rejoins any line that
  fits its width. Name a sub-expression, use a short example name, hide setup
  the reader does not need, or print less.
- **Break at the outermost level when a break is still needed:** after an
  opening parenthesis with one argument per line, one call per line in a
  chain, one field per line in a literal.
- **Put a comment on its own line above the code it explains.** Never trail
  one after code in an example, unless the repository's example runner reads
  it there, as with an assertion marker.
- **Break a long string at a word boundary,** never inside a tmux format
  (`#{...}`) or an escape sequence; the joined text stays the same.
- **Continue a long command in a `console` block the way its shell does:**
  `\` after a `$ ` prompt, a backtick after `PS> `, one flag per continuation
  line.

### What never breaks

- **Output a test compares.** Wrapping it changes what the test expects.
- **A block copied from a source file.** Fix the width in the source and run
  the sync command; never edit the copy.
- **Marker lines and URLs,** which tools and readers take whole.

<!-- /shared:examples -->

### In this repository

- **Hard limit:** palantir-java-format through Spotless, configured in
  `build-logic/conventions/src/main/kotlin/libtmux.java-library.gradle.kts`,
  which takes no width key, so Java keeps palantir's fixed 120 columns; scalafmt
  reads `maxColumn` in `.scalafmt.conf`, set to the example width. Kotlin has
  no formatter. The width check is `python3 tools/check_example_width.py`.
- **Not formatted:** Javadoc `<pre>{@code}` blocks, Kotlin sources, and every
  Markdown fence; held to the example width by hand. Java examples reach it
  through named variables and extracted helpers, because palantir rejoins any
  line that fits its own width.
- **Runs, compiles, exempt:** a fence in `docs/` or a README compiles and
  runs against live tmux unless a `<!-- snippet: ... -->` directive on the
  line above it says otherwise: `throws` (must fail with that exception),
  `does-not-compile` (the compiler must reject it), `compile-only` or
  `skip`, each with its reason, as [docs/README.md](../docs/README.md)
  lists. `// →` states a value, and `// Given:` binds a fixture.
- **Compared output and copied blocks:** `// →` lines and the text they
  compare stay on one line. No block is copied from a source file, so there
  is no sync command; `./gradlew :docs:test` runs the fences and
  `./gradlew :examples:test` runs the programs.

Bad, over 80:

```java
        Path socket = Path.of(args.length > 0 ? args[0] : "/tmp/libtmux-java-dev/demo/s");
```

Good, hold the default in a constant:

```java
        Path socket = Path.of(args.length > 0 ? args[0] : DEMO);
```
