# Writing

Repository-specific rules for `README.md`, per-module and root `CHANGELOG.md`
files, `MIGRATION.md`, release notes, commit messages, CLI and help text, error
messages, Javadoc, Clojure docstrings, and source comments in `libtmux-java`.

Organization-wide voice, CommonMark 80-column wrapping, unwrapped pull request
and issue bodies, commit and pull request shape, and slop-free prose live in
[`libtmux/.github` `WRITING.md`](https://github.com/libtmux/.github/blob/main/WRITING.md).
For build, test, and pull request gates, see
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## README shape

The root `README.md` and each module `README.md` are the door; the guides under
`docs/guide/`, `libtmux.org`, and Javadoc are the manual.

- **Header.** Open with the logo block, a centered `# libtmux for Java` title,
  a subtitle surfacing [Java](https://libtmux.org/en/java/),
  [Kotlin](https://libtmux.org/en/kotlin/),
  [Scala](https://libtmux.org/en/scala/), and
  [Clojure](https://libtmux.org/en/clojure/) on `libtmux.org`, and the four
  badges (CI, Maven Central, `libtmux.org`, license). Link only pages that
  return 200.
- **Quick start first.** Show `io.github.libtmux:libtmux-bom` coordinates for
  both Gradle and Maven, then one runnable example starting its own server with
  `Server.start`.
- **Task and API headings.** Make each `##` heading a task in the reader's
  words (`Find sessions, windows, and panes`), and each `###` heading a linked
  API call and its job
  (`### [\`Server.sessions()\`][server]: what tmux has now`).
- **Scannability.** Keep paragraphs and bullets to three lines or fewer, hold
  code lines in fences within 80 columns, and omit manual contents walls,
  feature inventories, CI matrix narration, and inline BibTeX (`CITATION.cff`
  lives at root).
- **Reference links.** Link every public `libtmux` type, tool, and command named
  in prose to its `libtmux.org` reference page via reference-style definitions
  at the bottom of the file. Never put Markdown links inside code fences.
- **Module and package indexes.** Keep the `## Modules` bullet list and each
  `### [\`<module>\`](https://github.com/libtmux/libtmux-java/tree/master/<module>)`
  heading in root `README.md`, and keep the `## Package map` and exported
  package headings in `libtmux/README.md`.
- **Closing.** End with short bold-lead bullets under `## What it leaves out`
  and `## Support and status`, followed by compact links and the
  [libtmux.org](https://libtmux.org/) family line.

Four compatibility claims near the top move together:

- **Title:** `libtmux for Java`.
- **Status notice (word for word):** "Releases are numbered `0.1.N`, and each
  release increments `N`. The API is not settled: any release may change or
  remove exported identifiers, and the changelog marks each break. Pin an exact
  version to avoid one." Keep this wording identical across `README.md` and
  `CHANGELOG.md`.
- **Floors:** JDK 25 or newer, and tmux 3.3a through 3.8 (kept in lockstep with
  `gradle/tmux-lanes.json`).
- **Coordinates:** group `io.github.libtmux`, imported via `libtmux-bom`.

### Executed examples

Every displayed code fence is verified by CI unless a directive states why it
cannot run — see
[`docs/README.md`](../docs/README.md#how-these-pages-are-tested):

- Java and Scala fences compile and run against real tmux in `docs`, Kotlin
  fences run in `libtmux-kotlin`, and Clojure fences run in `integration-tests`.
- Self-contained examples start with their `import` lines and manage a private
  server via `Server.start` (`try`-with-resources closes and removes it);
  follow-on snippets declare assumed bindings on a `// Given:` first line.
- Keep `// →` result assertions on a single line so the snippet rewriter can
  parse the expression.
- Claims must be falsifiable ("No runtime dependencies" on `libtmux`, or a
  benchmark number paired with the command that reproduces it).

## Migration notes

Put API breakages and upgrade instructions under `## Next release` in the root
[`MIGRATION.md`](../MIGRATION.md); the maintainer assigns the version when
cutting a release. Cover renames, removals, and changed defaults or failure
contracts with concrete replacements.

Keep `README.md` and `docs/guide/` on the current API and link `MIGRATION.md`
instead of embedding migration sections. Use the `does-not-compile` snippet
directive in `MIGRATION.md` for rejected legacy Java calls; replacement examples
must compile and run.

## Changelog

`CHANGELOG.md` is a consumer ledger, not `git log`. Place unreleased entries
under `## Unreleased` grouped by `Added`, `Changed`, `Fixed`, `Removed`,
`Documented`, or `Security` (newest release first, dated `YYYY-MM-DD`).

Each published module keeps its own `CHANGELOG.md` beside its `README.md` (and
ships it inside its JAR): `libtmux/CHANGELOG.md` for core changes,
`libtmux-mcp/CHANGELOG.md` for MCP changes, and likewise across the remaining
published modules. Root `CHANGELOG.md` holds cross-module changes, docs, CI,
tests, build and release tooling, and `libtmux-bom`. Put each entry in one file:

- Every module shares the release version; a module with no changes in a release
  records ``No entries for `<module>`.`` under that heading, and root
  `CHANGELOG.md` records `No entries that span modules.` when none apply.
- Relative repository links inside `<module>/CHANGELOG.md` start with `../`.
- Open each bullet with a bold clause naming the literal identifier
  (`Pane.capture`, `LIBTMUX_TOOLSETS`, `--rerun-tasks`, `tmux://capabilities`)
  and a concrete verb (`add`, `fix`, `remove`, `reject`, `now`, `no longer`),
  stating any changed default or replacement directly:

```markdown
- **`capture_since` now reports an observe-only tmux effect.** Reading from a
  cursor leaves tmux state unchanged, so capability metadata no longer
  overstates what the call mutates.
```

## Release notes

Order release notes capability, then consequence, then compatibility:

> 0.1.2 adds streaming capture, and rejects `windowId` at pane scope rather
> than ignoring it. Pass `scope: window` to read at window scope. JDK 25 and
> tmux 3.3a through 3.8 are unchanged.

Title the release with the plain version (optionally preceded by
`libtmux for Java`) and link `CHANGELOG.md` rather than pasting it. Release
mechanics live in [`RELEASING.md`](../RELEASING.md).

## Commit messages

Follow the `Scope(type[detail]): concise description` format, 50-character
subject limit (excluding trailing `(#NN)`), and 72-character `why:` / `what:`
body rules in
[`libtmux/.github` `WRITING.md`](https://github.com/libtmux/.github/blob/main/WRITING.md#commits-and-pull-requests):

```text
Pane(feat[sendKeys]): Add support for a literal flag

why: Send characters without tmux interpreting them.

what:
- Add a literal field to SendKeysRequest
- Pass -l when it is set
```

Repository-specific scopes alongside `feat`, `fix`, `refactor`, `docs`, `chore`,
`test`, and `style`:

- `java(deps)` and `java(deps[dev])` for runtime and build/test dependencies.
- `ai(rules[AGENTS])` for agent instruction updates.

### Release commits

Never create tags and never push tags: a tag triggers the Central Portal publish
workflow and is pushed only by the maintainer. Use `Tag v<version>` as the
release commit subject, with `why:` and `what:` in the body.

## API documentation

### Clojure docstrings

Clojure docstrings use minimal Markdown. Backtick arguments and keyword options.
Link related public vars and namespaces with `[[same-namespace-var]]` or
`[[fully.qualified.namespace/var]]` (never an import alias) and explain why a
caller would follow the link. Both Markdown and offline HTML reference builds
validate public targets during module checks.

### Javadoc

Javadoc on public API specifies the caller contract rather than implementation
details. Make the first sentence a standalone summary ending at the first
period, starting with a verb:

```java
/** Captures the visible contents of this pane. */
```

Document what the Java signature cannot state on its own:

- **Nullability.** Exported packages are `@NullMarked` (enforced by NullAway in
  JSpecify mode), so references are non-null unless annotated `@Nullable`; do
  not repeat non-null guarantees in prose.
- **Collections.** Immutability, whether the collection is a live view or a
  frozen snapshot, and iteration order.
- **Blocking, threading, and lifetime.** Whether the call blocks on tmux, thread
  safety across callers, and behavior after the owning `Server` closes.
- **Failure.** Checked and unchecked exceptions with the exact trigger for each.

Tag rules:

- `@param` completes a phrase (`@param history scrollback lines to include, 0
  for the visible screen only`).
- `@return` states semantics (`@return the pane contents`), not the type.
- `@throws` states the condition (`@throws IllegalArgumentException if
  {@code history} is negative`).
- `@since` records the product version that introduced the declaration.
- `@deprecated` names the replacement and migration path, matching
  `@Deprecated(since = ..., forRemoval = ...)`.

Put package-level architecture, major types, and threading models in
`package-info.java` so type-level Javadoc stays local and contractual.

## Source comments

Keep a comment only when it passes all three gates; delete borderline comments:

- **Loss.** Would losing it cost a maintainer real time rediscovering an
  invariant, protocol or tmux version constraint, ordering/lifetime rule, or
  non-obvious failure mode that types and tests cannot express?
- **Elite.** State the constraint in one or two lines and stop; put design
  rationale and rejected alternatives in the commit message.
- **Upkeep.** Never hand-sync counts, offsets, line numbers, ticket IDs, or
  transient claims ("currently", "for now") that rot without a nearby edit.
  Frozen external protocol facts are fine:

```java
// Some releases report the pane ID only after the command completes,
// so this query must stay separate.
```

Never delete comments documenting invariants, protocol constraints, platform
quirks, or upstream workarounds. Public API Javadoc summaries, `@param`,
`@return`, `@throws`, and minimal usage examples are exempt from the loss gate
because they serve callers, up to the ceiling of a concise man page entry.

## Terminology and capitalization

- **tmux** is always lowercase, including at the start of a sentence.
- **Javadoc** is capitalized; **JDK** and **JVM** are uppercase. Write
  **JDK 25** rather than "Java 25".
- **Domain nouns** match tmux: `server`, `session`, `window`, `pane`, `client`,
  `option`, `hook`, `buffer` — no synonyms.
- **Literal identifiers** beat paraphrases: `LIBTMUX_TOOLSETS=inspect`,
  `--rerun-tasks`, `/tmp/libtmux-java-test/`.
- **No brittle references or coded labels:** avoid hard-coded line numbers,
  file/test counts, dated "as of" claims, bare SHAs, `[R1]` / `Option B` rule
  codes, or local absolute paths outside benchmark logs.

## Code blocks

Follow [`libtmux/.github` `WRITING.md`](https://github.com/libtmux/.github/blob/main/WRITING.md#markdown)
for plain CommonMark (never GitHub-only `> [!NOTE]` or `> [!WARNING]` alerts,
which break on Maven Central and external renderers), one logical `$ `-prefixed
command per `console` fence split with `\`, explanations in prose above the
fence rather than `#` comments inside, and code lines held within 80 columns.
