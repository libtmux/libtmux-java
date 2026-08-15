# Java libtmux architecture

Status: independently reviewed and accepted for executable spikes; every
selection remains provisional until its falsification gates pass.

This specification defines a Java 21 port of libtmux under `java/`. The port
keeps libtmux's tmux vocabulary and blocking object API while replacing
Python-specific collection and reflection machinery with immutable Java
snapshots, generated type-safe fields, and ordinary Java streams.

The source baseline is libtmux at pinned revision
[`c4a980b`](https://github.com/tmux-python/libtmux/tree/c4a980b) and tmux
[`3.7b`](https://github.com/tmux/tmux/tree/3.7b). Compatibility behavior comes
from the Python source and tests at that libtmux revision, not from an
independent reimplementation of tmux semantics.

## Goals

- Provide full feature parity with the current public `Server`, `Session`,
  `Window`, `Pane`, and `Client` APIs, adapted to Java naming and types.
- Preserve tmux compatibility from 3.2a through 3.7b, including letter suffixes
  and version-specific workarounds.
- Keep the user-facing API blocking and safe to call from virtual threads.
- Make every query operate on an explicit, immutable, eager snapshot. A stream
  operation never starts a process or refreshes an object.
- Return ordered, unmodifiable `List<T>` values rather than a custom query
  collection.
- Provide a sealed, data-bearing `FilterExpr<T>` predicate AST with generated
  type-safe fields and optional versioned JSON serialization.
- Keep core's published dependency graph empty. JSpecify is build-only and
  omitted from consumer metadata; Jackson and JUnit live in optional artifacts.
- Preserve extension seams needed by a future engine-ops artifact without
  publishing speculative modules or abstractions.
- Publish ordinary Maven metadata, source and Javadoc jars, reproducible
  archives, signatures, and stable automatic module names.
- Test behavior against real tmux using a JUnit 5 extension that owns an exact
  per-test socket path and guarantees teardown.

## Specification boundary

This is the umbrella architecture, not one giant implementation plan. After
review, the first child plan covers only the disposable spike program. Its
results may amend this specification before clean implementation begins.
Build, transport, query and metamodel, hydration, testkit, and object-parity
work then receive independently executable plans and review gates. No child
plan may narrow the full-parity endpoint defined here.

No production source is written before the spike program finishes. The current
recommendations come from source study and are hypotheses, not executable proof.

## Non-goals

- No asynchronous mirror returning `CompletableFuture`.
- No production control-mode transport in the first artifact.
- No direct use of tmux's private imsg protocol.
- No custom Java collection that emulates Python `QueryList` or `QuerySet`.
- No automatic query pushdown in snapshot filtering.
- No Python dunder, mapping, dataclass-reflection, pytest, or doctest emulation.
- No methods whose current Python implementation only raises a deprecation
  tombstone.
- No empty engine-ops module reserved for hypothetical code.
- No `module-info.java` unless the final packaging spike proves that JPMS adds
  no processor, JSpecify, Jackson, JUnit, or consumer friction.

## Design choice

Three architecture families were evaluated.

### Mutable active-record port

This approach maps the Python classes directly and mutates each object during
`refresh()`. It has the smallest conceptual diff from Python, but collection
access hides I/O, mutable snapshots race under concurrent callers, and a query
can accidentally cross several moments in tmux state. It also couples future
transport work to live object mutation. It is rejected.

### Services with immutable data transfer records

This approach exposes immutable records and puts every operation on separate
services. It makes I/O and data ownership clear, but loses the hierarchy that
makes libtmux useful. Common calls become `paneService.capture(paneId)` instead
of `pane.capture()`, and parity becomes a vocabulary translation rather than a
port. It remains useful as an internal separation pattern, not as the public
API.

### Immutable hierarchy with operational handles

This is the selected design. `Server`, `Session`, `Window`, `Pane`, and
`Client` remain familiar behavior-rich classes. Each returned entity wraps an
immutable state record and stable server identity. Captured parent and child
relationships form an immutable graph. Operations delegate through a blocking
transport; they never mutate a previously returned graph.

`refresh()` returns a new handle. Creation methods return newly hydrated
handles. Operations whose Python contract has no return value remain `void`;
raw command methods return `CommandResult`. This preserves the public mental
model without importing Python's mutable implementation.

## Repository and publication layout

The Java build has one root and four subprojects:

- `java/libtmux`: the published core artifact.
- `java/libtmux-jackson`: the published versioned query-AST adapter.
- `java/libtmux-junit5`: the published real-tmux JUnit extension.
- `java/libtmux-metamodel-processor`: an internal JSR 269 processor used while
  compiling core.

`java/build-logic` is a small included build with precompiled Kotlin convention
plugins. It contains only Java-library and published-library conventions. Each
module build stays declarative, and configuration does not depend on a broad
root `subprojects` mutation block. The included build has its own settings and
repository declarations, explicitly imports the root version catalog, and puts
every external convention-plugin implementation artifact on its own classpath.
The spike verifies this against Gradle's
[composite-build model](https://docs.gradle.org/9.7.0/userguide/composite_builds.html).

The root contains `settings.gradle.kts`, `build.gradle.kts`,
`gradle/libs.versions.toml`, wrapper files, and dependency-verification
metadata. Repositories are declared centrally. Dynamic dependency versions are
forbidden.

The coordinate spike begins with this DNS-derived hypothesis:

- Group: `com.git-pull`
- Core artifact: `libtmux`
- Optional artifacts: `libtmux-jackson` and `libtmux-junit5`
- Java package root: `io.github.libtmux`
- Automatic modules: `io.github.libtmux`,
  `io.github.libtmux.jackson`, and `io.github.libtmux.junit5`

The group preserves the exact reversed `git-pull.com` domain as required by
[Central's namespace rules](https://central.sonatype.org/register/namespace/).
The Java package replaces the invalid hyphen with an underscore as described by
the [Java package naming guidance](https://docs.oracle.com/javase/specs/jls/se21/html/jls-6.html#jls-6.1).
The spike compares this domain-owned namespace with repository-host namespaces,
verifies which namespace can actually be claimed, and compiles consumers using
the resulting package. It records the selected coordinates only after that
evidence. Namespace verification is a release precondition, not something the
build pretends has already happened.

## Build baseline

The initial spike pins are:

- Gradle 9.7.0 with its distribution and wrapper checksums.
- Foojay toolchain resolver 1.0.0.
- Error Prone Gradle plugin 5.1.0 and Error Prone 2.50.0.
- NullAway 0.13.8.
- Spotless 8.9.0 with palantir-java-format 2.97.0.
- JUnit 5 BOM 5.14.4.
- JSpecify 1.0.1.
- Vanniktech Maven Publish 0.37.0.
- Jackson 2.21.5 LTS for the optional adapter.

These versions are candidates until the build spike exercises them together.
The authoritative sources are the
[current Gradle release](https://services.gradle.org/versions/current),
[Gradle plugin portal](https://plugins.gradle.org/), and upstream release pages
for [Error Prone](https://github.com/google/error-prone/releases),
[NullAway](https://github.com/uber/NullAway/releases),
[palantir-java-format](https://github.com/palantir/palantir-java-format/releases),
[JUnit](https://github.com/junit-team/junit-framework/releases),
[JSpecify](https://github.com/jspecify/jspecify/releases), and
[Vanniktech Maven Publish](https://github.com/vanniktech/gradle-maven-publish-plugin/releases).

Compilation, tests, and Javadocs for libtmux use exact Temurin 21.0.11+10 and
`--release 21`; an arbitrary Java 21 vendor is insufficient. The spike driver
selects that JVM through `mise x` so every non-interactive Gradle, Maven, Java,
Javadoc, and JFR process receives the same `JAVA_HOME`. Mise is harness tooling,
not a published dependency.

The ordinary product gates validate Gradle's Adoptium toolchain selection but
cannot prove auto-provisioning because the build JVM already satisfies that
request. A separate non-product resolver oracle requests an Amazon Corretto 21
compiler through Foojay 1.0.0 from a fresh Gradle user home with local
auto-detection disabled. It compiles a minimal source and verifies Java 21, an
Amazon vendor, and an installation below that fresh home's `jdks` directory.
This proves resolver integration without using a dynamically selected JDK for
any libtmux artifact. Foojay selects the latest compatible minor release, so
exact patch-level evidence remains bound to mise's Temurin 21.0.11+10. The
oracle follows Gradle 9.7's
[toolchain-provisioning contract](https://docs.gradle.org/9.7.0/userguide/toolchains.html#sec:auto_provisioning)
and the pinned
[Foojay resolver behavior](https://github.com/gradle/foojay-toolchains/blob/2a6cc60/README.md).
NullAway's JSpecify mode uses `-XDaddTypeAnnotationsToSymbol=true`,
`OnlyNullMarked=true`, and `RequireExplicitNullMarking` as an error. The spike
retains compile-negative tests for documented generic-method, wildcard,
generic-class, and JDK-annotation limitations rather than hiding them with a
broad suppression. The exact vendor, update, and provenance are recorded with
the spike and reproducible build. These constraints follow NullAway's
[JSpecify support contract](https://github.com/uber/NullAway/wiki/JSpecify-Support).

Every exported package is `@NullMarked`. `Optional` is used only as a return
type, never as a field, record component, constructor argument, or builder
argument. Primitive absence uses `OptionalInt` or `OptionalLong` where
applicable. Internal absence uses an explicit JSpecify-nullable representation.
Collections are never nullable, no method returns a null `Optional`, and public
inputs reject null unless their API states otherwise.

Core's published dependency graph is empty. JSpecify is a build-only
`compileOnly` dependency and is omitted from both the standard POM and Gradle
module metadata. The build spike must prove that plain Maven and Gradle
consumers compile and run without adding JSpecify, while a nullness-analysis
consumer can opt into the annotation artifact and read the retained contract.
Jackson and JUnit must not appear in the core POM or runtime graph.

The optional artifacts are separate coordinates, not optional dependencies of
core. `libtmux-jackson` publishes its Jackson API dependency because its public
module type exposes Jackson. `libtmux-junit5` publishes the deliberate JUnit API
scope required by extension interfaces. Their POMs and Gradle metadata are
consumer-tested independently.

## Domain model

### Server identity and configuration

`ServerEndpoint` is a closed value hierarchy with default, named-socket, and
socket-path variants. `ServerConfig` and `ProcessTransportConfig` are final
immutable classes built through fluent builders, not records. Configuration is
expected to evolve, so a public record's canonical constructor would be a
needless source and binary compatibility cost.

`Server.builder().build()` creates an out-of-the-box local server with
documented defaults. `Server.open(config)` owns its internally created
transport. `Server.using(config, transport)` borrows a caller-owned transport
and never closes it. `toBuilder()` copies every configuration and ownership
choice. Common operations have small convenience overloads; flag-heavy tmux
operations accept immutable options objects. Named methods and enums replace
positional boolean modes. Builders reject incompatible endpoint or flag choices
before dispatch.

Endpoint values used in equality are normalized without resolving or opening
the socket. Validation that requires tmux happens at dispatch, where an error
can include the complete command context.

`Server` holds `ServerConfig`, `ServerIdentity`, and an unsealed
`TmuxTransport`. The identity combines the transport realm with the resolved
logical tmux server without exposing a socket path. It is propagated to every
handle and refresh result. A custom transport supplies a stable realm identity,
so equal endpoint text in unrelated execution realms cannot make entities
equal.

The transport is an extension point; downstream tests and a future engine can
implement it. No transport implementation is selected through a global mutable
registry. `Server` implements `AutoCloseable`. Closing it is idempotent, closes
only an owned transport, and never kills tmux. Pure reads from an already
captured snapshot remain available; later operational calls through that server
or its handles fail with `IllegalStateException`.

### Entity handles and state

The five user-facing entities are final classes, not records. Records remain
appropriate for their transparent immutable state:

- `SessionState`
- `WindowState`
- `PaneState`
- `ClientState`
- `ClientAttachment`
- typed target IDs and dimensions

The state records defensively copy every collection. Public handle constructors
are not exposed; hydration goes through the snapshot reader or creation
operations.

`Session` equality uses `ServerIdentity` and `SessionId`. `Pane` equality uses
`ServerIdentity` and `PaneId`. `Client` equality uses `ServerIdentity` and the
client name, not changing attachment fields. A contextual `Window` represents
a tmux winlink and uses `ServerIdentity`, `SessionId`, `WindowIndex`, and
`WindowId`; tmux renumbering replaces that winlink identity. `WindowId` remains
available when callers need to compare the underlying window across links.
Names, active flags, layout, commands, and other changing snapshot fields never
participate. `Server` retains object identity. Hash codes use exactly the same
keys.

`toString()` shows safe object identity and user-facing names. No public type
containing paths, argv, environment-derived values, terminal content, or
credentials relies on a record-generated `toString()`. Such a type is a final
class or overrides `toString()` with a documented redacted form. Records are
reserved for transparent values whose complete component list is safe and
stable.

### Hierarchy snapshots

`Server.snapshot()` captures the closed `Server → Session → Window → Pane`
hierarchy and returns `ServerSnapshot`. The snapshot records its endpoint,
tmux capabilities, capture time, ordered roots, winlink relationships, and
indexes by stable ID. It does not claim transaction isolation that tmux does
not provide.

The snapshot reader chooses the smallest set of format queries that preserves
Python ordering, linked-window duplication, and target-resolution behavior.
The hierarchy spike compares a single `list-panes -a` rowset with scoped
`list-sessions`, `list-windows -a`, and `list-panes -a` reads. The winner must
pass differential real-tmux tests before the capture plan becomes public
behavior.

All handles returned from a hierarchy capture contain the closed relationship
data needed below their scope. `Session.windows()` and `Window.panes()` are
pure reads of captured state. Parent traversal is also pure. To observe newer
tmux state, the caller invokes `refresh()` and uses the returned handle.

`Server.sessions()`, `Server.windows()`, `Server.panes()`, `Server.clients()`,
and `Server.attachedSessions()` are compatibility accessors. Each eagerly
captures its result and returns `List.copyOf(...)`. In accordance with the
existing libtmux contract, a tmux error produces an empty list. Callers that
must distinguish no rows from an unavailable server use `isAlive()`,
`raiseIfDead()`, or a copied `Server` configured with strict query failure.

`Server.snapshot()` is explicit and strict: a capture failure raises rather
than returning an apparently valid empty graph. This distinction keeps the
lenient list contract while giving query and engine callers trustworthy
failure semantics.

Server-wide window and pane listings preserve winlink duplicates and order.
Point lookup uses tmux's current-winlink-then-lowest-index rule. A missing
target on a live server becomes a typed missing-object exception; an
unreachable server remains a command or transport failure.

`Client` state is a snapshot. `Client.attachment()` is a pure
`Optional<ClientAttachment>` read of captured state. A present attachment
contains non-null session, active-window, and active-pane handles; its pane is
the attached session's active pane, matching current libtmux behavior.
`Client.fetchAttachment()` performs one explicitly named live capture on every
call, and `Client.refresh()` returns a new client snapshot. Expression
evaluation, ordinary accessors, equality, hashing, and string conversion never
dispatch a command.

## Blocking transport

`TmuxTransport` exposes one blocking operation accepting immutable
`CommandRequest` and returning immutable `CommandResult`. It is not sealed.
The request contains argv elements, endpoint arguments, timeout, and safe
diagnostic metadata. It never contains a shell command string.

Every implementation is thread-safe and documents the same interruption,
timeout, and outcome-certainty contract. `close()` is idempotent. Calls begun
before close either complete under that contract or fail with their outcome
certainty intact; calls begun after close fail with `IllegalStateException`.
Borrowed transports may be shared by several servers and remain the caller's
responsibility.

`ProcessTransport` invokes `ProcessBuilder` with an argument list. Standard
input is closed immediately. Standard output and error remain separate and are
drained concurrently before a result is completed.

### JDK 21 pipe strategy

On Unix-like JDK 21 implementations, process streams subclass monitor-locked
buffered streams. Blocking a virtual thread in those reads can pin its carrier.
The default transport therefore uses an admission-bounded platform-thread pump
pool:

1. A caller acquires one process permit before launch.
2. The permit reserves two prestarted platform workers, one per pipe.
3. Only after both workers are reserved does the transport start the process.
4. The caller blocks in `waitFor` and may itself be a virtual thread.
5. Completion joins both drains, constructs the result, and releases the
   process permit.

The bound is configurable in `ProcessTransportConfig` and has a conservative
library default. One transport owns one bounded executor; copied and refreshed
handles reuse it rather than creating pools. Owned transports close the executor
and cannot keep the JVM alive after `Server.close()`. Borrowed shared transports
remain the caller's responsibility. The spike compares daemon and non-daemon
worker policy and must prove both prompt JVM exit and deterministic close.
Queued virtual-thread callers park on the admission primitive; the library
never creates an unbounded platform thread per request.

Both premises above are measured on JDK 21, not assumed: `ProcessImpl.waitFor`
takes a `ReentrantLock`, so a virtual-thread caller releases its carrier there,
while `ProcessPipeInputStream` subclasses `BufferedInputStream` and so reads
under a monitor. The relevant platform behavior is documented by
[JEP 444](https://openjdk.org/jeps/444), and monitor blocking stops pinning only
in [JEP 491](https://openjdk.org/jeps/491), after this baseline.

The hard falsifier is carrier starvation, not a pin recording: flood both pipes
while an unrelated thread holds the only carrier, and require the transport to
complete. A drain that needs its own virtual thread cannot run under that
condition, its child's pipe fills, and the command times out. JFR
`jdk.VirtualThreadPinned` events at a zero threshold with
`-Djdk.tracePinnedThreads=full` are recorded as supporting evidence only. They
cannot serve as the gate: the event fires when a virtual thread parks while
pinned, and a monitor-locked pipe read never parks, so a transport that pins a
carrier on every read still records zero.

### Completion and interruption

Low-level nonzero tmux exits remain data in `CommandResult`; raw execution does
not throw merely because the exit code is nonzero. High-level methods interpret
the result and raise typed command exceptions when their contract requires it.

Missing executables, process-launch failures, pipe failures, timeouts, and
interruption are transport exceptions. A timeout or interruption after launch
has outcome `UNKNOWN`, because tmux may already have applied the command. The
transport attempts bounded graceful destruction, then forced destruction,
closes streams, joins both pumps, preserves the caller's interrupt status, and
attaches cleanup failures as suppressed exceptions. One monotonic deadline
covers process wait, graceful destruction, forced destruction, stream closure,
and pump joins. Exhaustion cancels remaining work and returns the typed unknown
outcome; no cleanup phase is allowed an unbounded join.
This gate accounts for Java 21's warning that forcible process destruction need
not complete immediately in the
[`Process` contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Process.html).

The decoder matches Python's UTF-8 `backslashreplace` behavior. It removes
trailing empty stdout lines and empty stderr lines. `CommandResult` preserves
the true stdout and stderr channels. The historical `has-session` channel
rewrite is implemented only in the high-level `hasSession()` interpretation,
not by corrupting raw command results.

### Future transports

Control mode is not the default. tmux aborts the remainder of a semicolon group
after the first error, while a client that expects one result block per lexical
command can wait for blocks that will never arrive. A future control transport
must distinguish independent request lines from semicolon chains and assign
exactly one `COMPLETE`, `FAILED`, `SKIPPED`, or `UNKNOWN` outcome to every
logical operation.

A direct imsg transport is excluded because it would bind the Java artifact to
tmux's private, versioned, platform-specific protocol. Future engine work builds
on the public request, result, capability, target, and snapshot boundaries
instead.

## Formats, versions, and hydration

The Python `neo.py` responsibilities are consolidated rather than exposed as a
second ORM. Internal packages separate:

- typed format tokens and scopes
- tmux version and capability parsing
- version-safe format template construction
- row decoding
- hierarchy assembly
- point and parent target resolution

`TmuxVersion` retains the raw version suffix as well as its comparable release.
The literal `3.7` behavior remains distinguishable from `3.7a`, because the
current pane workaround applies only to the former. `master`, OpenBSD version
output, and malformed version text have explicit parsing tests.

`FormatToken<T>` records the stable token ID, tmux token, scope, minimum
version, decoder, and absence policy. Hydration does not silently ask old tmux
versions for unsupported fields. Internally it distinguishes an unsupported
field from a supported field that expanded empty. Public required properties
are non-null; legitimately absent version-gated properties return `Optional`.

Rows use an unambiguous separator and strict field-count validation. Parsing
never reads tmux's human display output. Snapshot ordering is stable and follows
the underlying tmux listing contract.

## Query model

### Expression contract

`FilterExpr<T>` is a sealed interface extending `Predicate<T>`. Its permitted
implementations are validating public nested records of `FilterExpr`. They are
supported API so the optional Jackson artifact can exhaustively pattern-match
them. Adding a permitted record is both a Java source-compatibility decision and
a wire-schema change. `test(T)` delegates to one exhaustive evaluator; Jackson
lowering uses a separate exhaustive switch over the same closed hierarchy. The
AST does not implement Java object serialization.

`FilterExpr` defines expression-preserving `and(FilterExpr<T>)`,
`or(FilterExpr<T>)`, and covariant `negate()` methods, plus a static
`not(FilterExpr<T>)` factory. Composition with another `FilterExpr` returns a
named AST node. Java's inherited methods accepting an arbitrary
`Predicate<? super T>` remain available and return an ordinary nonserializable
predicate. The distinction therefore lives in the argument and result types
rather than a second filtering method.

AST equality is structural, not logical equivalence. Each node defensively
copies collection operands while retaining encounter order. Regex nodes store
source text and explicit flags rather than `Pattern`, whose equality is based
on object identity. Field descriptors define the permitted scalar kinds and
their canonical encodings; unsupported operands fail at expression
construction, before optional serialization.

Scalar operations include current Python query parity:

- exact and case-insensitive exact equality
- contains and case-insensitive contains
- starts-with and case-insensitive starts-with
- ends-with and case-insensitive ends-with
- membership and non-membership
- regular expression and case-insensitive regular expression

Typed comparable fields may additionally expose ordering operations. String
operations never appear on numeric, boolean, temporal, identifier, or relation
fields. Absence uses explicit presence expressions rather than equality with
null.

To-many relations expose `any`, `all`, and `none`. Empty relations satisfy
`all`. To-one relations expose `is`. Relation evaluation reads only the graph
that owns the evaluated entity and never invokes a live accessor.

Membership operations accept `Collection<? extends V>`. Relation operations
accept `FilterExpr<? super R>`. Generated signatures are compiled from external
Maven and Gradle consumers so package-private state types cannot leak through
generic bounds, generated source, or Javadocs.

### Generated static metamodel

The internal processor is a custom JSR 269 processor. Package-private immutable
state records are the field source of truth. SOURCE-retained annotations record
the public model type, stable field ID, format token, scope, version, and
relation kind. The processor generates deterministic public classes such as
`Pane_` and `Session_` into the model package.

Generated handle types include string, boolean, comparable, optional,
to-one-relation, and to-many-relation fields. As a result,
`Pane_.command().startsWith("nv")` compiles while applying a string operation
to `Session_.attached()` does not. Normal use has no reflection or string path.

Generated classes are included in core's binary, source, and Javadoc jars. The
processor does not depend on core, avoiding a compilation cycle. Generated
output is reproducible and verified by golden source plus compile-pass and
compile-fail tests. The processor has explicit service registration or an
explicit compiler processor name; its generated directory is a declared compile
task output and an explicit input to source and Javadoc jars. Published core
variants and metadata contain generated classes and sources but no processor
dependency.

The legacy `name__contains=value` syntax exists only in an edge parser that
maps recognized field and operator IDs into the typed AST. It is not an
alternate query engine.

### Cardinality

Ordinary first-match selection uses `stream().findFirst()`. `Selections`
provides collectors with the following exact contracts:

- `exactlyOne()` has type `Collector<T, ?, T>`. It returns the sole element,
  throws `NoMatchException` for zero, and throws `MultipleMatchesException` for
  more than one.
- `oneOrEmpty()` has type `Collector<T, ?, Optional<T>>`. It returns
  `Optional.empty()` for zero, the element for one, and throws
  `MultipleMatchesException` for more than one.

Collectors are safe for sequential and parallel streams and retain only bounded
encounter-order diagnostic samples. They reject null elements immediately and
declare neither `CONCURRENT` nor `UNORDERED`. The same typed failures apply to
sequential and parallel collection. Like ordinary collectors, they consume the
stream to completion; they do not claim short-circuit behavior that the
collector protocol cannot provide.

### JSON module

`libtmux-jackson` publishes `LibTmuxJacksonModule`. It serializes only named
`FilterExpr` values, not arbitrary predicates. The wire format contains an
explicit schema version and stable model, field, and operator IDs. Java class
names and record component names are not wire identifiers.

The language-neutral schema is owned at
`java/schema/filter-expr-v1.schema.json`, packaged by the Jackson artifact, and
available to future engine implementations. Version 1 fixes canonical node
shapes for composition, scalar comparison, presence, to-one, and to-many
relations, including operand encoding and regex flags. A schema version is
immutable after publication. Backward-compatible readers may be added; a
breaking shape or semantic change creates a new schema file and version.

Unknown schema versions, fields, operators, or structurally invalid nodes fail
closed. Deserialization validates that the wire model agrees with the requested
`FilterExpr<T>` model and never enables Jackson default typing. Round-trip tests
cover every permitted AST node. Java and a small language-neutral conformance
fixture set must produce the same canonical documents. Loading core and using
queries does not require Jackson.

### Pushdown boundary

`filter(expr)` always evaluates locally over a materialized snapshot. The first
release does not lower expressions to tmux `-f`.

A future `querySessions(FilterExpr<Session>)` may inspect the AST before
materialization. Its compiler returns a tmux predicate and a residual local
expression. It may partially lower conjunctions; it lowers disjunction and
negation only when every branch is supported. Relations, unavailable fields,
and operators whose tmux null semantics differ remain local. No future compiler
changes the meaning of snapshot filtering.

Raw native filtering remains an explicitly unsafe escape hatch because tmux can
turn malformed or unknown expressions into an empty false value without an
error, making a malformed filter indistinguishable from no matches.

### Collection boundaries

Public producers return ordered `List<T>` values. APIs that consume multiple
objects accept `Collection<? extends T>` when replay or size matters and
`Stream<? extends T>` for one-pass transformations. An API accepts
`FilterExpr<T>` rather than `Predicate<T>` only when it needs to inspect,
serialize, or translate the expression; ordinary local filtering remains
standard `Stream.filter(Predicate)`.

## Options, hooks, and environment

Options use `OptionKey<T>`, `OptionScope`, and codecs for booleans, integers,
strings, complex values, inherited markers, and custom `@` values. Indexed
arrays are exposed as unmodifiable `NavigableMap<Integer, T>` values. Append
uses the next index after the current maximum and handles an empty array.

Hooks reuse the indexed option-array parser internally. Known hook names have
typed constants while custom names remain possible. Hook commands retain their
index and exact tmux text.

Environment operations preserve server and session scopes. Static environment
resolution accepts either the process environment or an explicit
`Map<String, String>` for tests. The `TMUX` value is split from the right so a
socket path may contain commas; its embedded session ID is discarded as stale.
`TMUX_PANE` must retain its `%` sigil.

## Parity inventory and source studies

Full parity is proved by an inventory, not by the five headline classes alone.
Before clean implementation, `java/docs/parity/python-api.md` records every
public Python module, class, method, property, function, constant, parameter,
return shape, exception, tmux command, and minimum-version rule. Each row names
its Java symbol, treatment, and owning contract test. Treatment is one of direct
translation, semantic Java adaptation, consolidation, or approved omission. An
omission requires source evidence that the symbol is Python machinery or a
deprecation tombstone; it cannot disappear merely because it is inconvenient.

`java/docs/parity/test-map.md` maps every Python unit, real-tmux, doctest, and
fixture behavior to a Java unit, compile, integration, consumer, or approved
non-applicable test. It separately records current Python defects so a Java
correction is deliberate and reviewable rather than an unnoticed divergence.

The source-to-package disposition begins as follows and is completed by that
inventory:

- `server.py`, `session.py`, `window.py`, `pane.py`, and `client.py` map to
  same-named public Java classes and object-family tests.
- `common.py` splits into public command results and version utilities plus
  internal process transport and decoding.
- `neo.py` consolidates into internal format, hydration, snapshot, and resolver
  packages; it does not survive as a competing public ORM.
- `formats.py` becomes the typed format catalog and generated field metadata.
- `options.py` and `hooks.py` retain public services with shared internal
  indexed-value codecs.
- `constants.py` becomes typed enums and value constants near their owning
  APIs rather than one untyped dumping ground.
- `exc.py` maps to the public exception package.
- `_internal/query_list.py` is replaced by the query AST, metamodel, edge
  parser, and selections.
- `_internal/env.py` becomes environment resolution; sparse arrays become
  unmodifiable navigable maps.
- `pytest_plugin.py`, test utilities, and control-mode fixtures map to
  `libtmux-junit5` and its real-tmux tests.

Main source and test paths mirror each other under the relevant Gradle module.
Public object families stay at the package root for short imports; supporting
format, query, option, hook, environment, transport, and exception packages
retain the Python project's conceptual boundaries.

The requested external study remains as source-grounded artifacts rather than
an uncheckable assertion. `java/docs/studies/java-library-patterns.md` records
the Gradle, publication, API, nullness, and testkit patterns evaluated from
established Java libraries. `java/docs/studies/tmux-protocol.md` records the
format, command-queue, control, process, and socket behavior used here.
`java/docs/studies/cpython-subprocess.md` records the Python subprocess and
UTF-8 normalization behavior that Java must match. Every durable source link is
pinned to a release tag or stable revision.

## Compatibility policy

The Java implementation ports current behavior, not Python implementation
accidents.

Behavior that must match includes:

- target sigils, command vocabulary, hierarchy navigation, and creation
- linked-window duplicates and canonical point resolution
- version gates and literal suffix workarounds
- option, hook, environment, buffer, prompt, menu, popup, and command behavior
- unsupported optional flags warning and being ignored where Python does so
- unavailable commands raising a typed unsupported-feature exception
- live-client attachment semantics
- low-level output normalization and high-level error translation
- empty-on-tmux-error list accessors with explicit strict liveness checks

The Java port does not preserve two identified Python defects: attached-session
filtering through an unregistered lookup, and inconsistent failure leniency
among list-shaped accessors. Attached sessions use an attached count greater
than zero, and all public list accessors follow the documented lenient default.

Deprecated tombstones, Python mapping behavior, reflection-based field access,
vendored loose-version parsing, and the bespoke `QueryList` are omitted.

## Exceptions and diagnostics

All library exceptions derive from one unchecked `LibTmuxException`. Typed
families distinguish:

- transport setup and execution failures
- timeout, interruption, and unknown outcomes
- nonzero tmux command failures with their `CommandResult`
- missing server, session, window, pane, or client targets
- unsupported tmux versions and features
- invalid option, hook, environment, and format values
- query no-match, multiple-match, unknown field, and invalid serialized AST

Programmer errors do not masquerade as tmux failures. Null arguments fail with
`NullPointerException`; invalid values or mutually exclusive flags fail with
`IllegalArgumentException`; and use after close fails with
`IllegalStateException`. `LibTmuxException` covers operational transport, tmux,
hydration, and query failures. Each subtype documents outcome certainty,
structured accessors, cause preservation, and whether retry is safe. Collector
exceptions expose bounded samples without terminal content.

Exceptions retain argv as separated elements and structured result fields.
Messages do not include environment values, terminal contents, or local socket
paths unless the caller explicitly supplied and requested that diagnostic.
Library logging uses lazy parameterized messages and stable structured keys. It
does not install handlers, levels, or formatters.

Core configuration, request/result, handle, snapshot, and AST types do not
implement `Serializable`; Jackson is the only supported expression persistence
format. Exception classes declare `serialVersionUID`. Nonserializable structured
details are transient while the diagnostic message and cause remain safe to
serialize. The library adds no Java deserialization entry point.

## JUnit 5 real-tmux extension

`TmuxExtension` implements per-test lifecycle callbacks and parameter
resolution. All state lives in `ExtensionContext.Store`; no process-global home
or environment value is mutated.

Before each test, the extension:

1. Creates a short private temporary directory and exact unique socket path.
2. Registers an idempotent `AutoCloseable` aggregate before starting a process.
3. Starts the selected process-ownership contender using `-S` and an explicit
   empty config, then creates a detached default session.
4. Queries `#{socket_path}` and verifies that the promised socket physically
   exists and matches the fixture path.
5. Retains a directly owned server `Process` when the selected contender can do
   so and captures tmux's server PID plus optional start instant for liveness
   diagnostics.
6. Resolves `TmuxTestContext`, `Server`, `Session`, and a dedicated
   `TmuxSocketPath` value; it never claims arbitrary `Path` parameters.
7. Registers every additional server or control test client immediately.

Teardown closes resources in reverse ownership order. It stops control clients
with bounded terminate/kill, issues `kill-server`, and waits for bounded exit to
one monotonic deadline. A directly owned live `Process` may then receive bounded
termination and forced termination under a second deadline. PID plus start
instant is diagnostic evidence, not sufficient ownership authority: the start
instant is optional and checking it before signaling has a reuse race. The
extension never signals an unowned PID. If exit cannot be proved safely,
teardown preserves a cleanup failure rather than risking an unrelated process.
It unlinks the stale socket and removes the directory only after daemon exit is
proved. One aggregate per-test store value owns all clients and servers;
cleanup does not depend on unspecified ordering among separate store values.
The aggregate implements `AutoCloseable`, not the deprecated
`Store.CloseableResource`. The lifecycle spike runs with store auto-close both
enabled and disabled and selects an explicit idempotent callback fallback when
required. The original test failure remains primary and cleanup failures are
suppressed. Cleanup failure is primary only when the test otherwise succeeded.

The extension must remain parallel-safe and prove cleanup after success,
assertion failure, abort, setup failure, timeout, and repeated parallel use.
Tests use real tmux fixtures by default. Mocks are limited to failures that
cannot be safely induced through the standard fixture, with the reason stated
in the test.

## Disposable spike protocol

Prototype code does not become the final library. Each spike runs in a
disposable worktree or temporary directory; only source-grounded findings,
commands, results, and decisions remain under `java/docs/spikes/`.

Every bakeoff follows the same sequence:

1. State the behavior and falsification gates before implementation.
2. Build three working contenders against the same examples and tests.
3. Record tool versions and exact results without local paths or personal data.
4. Score correctness first, then downstream API quality, maintainability,
   performance, and build complexity.
5. Select a winner and explicit grafts from the other contenders.
6. Rebuild the synthesized design and rerun all hard gates.
7. Treat every newly surfaced stumbling block as a new falsifiable question:
   create fresh contenders, bake them off, graft the result, and re-spike.
8. Repeat until the synthesized contender passes every gate without a known
   unresolved stumble.
9. Delete prototype source before starting the clean implementation.

The first required bakeoffs are:

- Build: single-project feature variants, direct multi-project configuration,
  and multi-project convention build.
- Transport: virtual-thread pipe drains, admission-bounded platform pumps, and
  temporary-file redirection; persistent control mode is a separate future
  falsifier.
- Hydration: direct scoped listings, one hierarchical pane rowset, and a hybrid
  capture plan.
- Metamodel: record-first custom processor, schema-first custom processor, and
  QueryDSL/JPA-style processor adaptation.
- JUnit lifecycle: callback-owned state, store-owned closeable resources, and
  parameter-owned `AutoCloseable` fixtures.
- Coordinates: verified domain namespace, repository-host namespace, and the
  strongest available organization namespace, each tested from Maven and
  Gradle consumers before one is selected.

Source study makes the convention build, bounded pumps, hybrid immutable graph,
record-first processor, and store-owned test resources the provisional winners.
No provisional winner survives a failed executable gate.

## Verification gates

### Transport

- Flood stdout and stderr beyond both pipe capacities under one more concurrent
  caller than the configured admission bound.
- Verify exact bytes, bounded completion, no worker starvation, and no zombies.
- Record zero-threshold JFR and pinned-thread traces on the pinned JDK 21
  toolchain; any transport-attributed pin event rejects the contender.
- Verify timeout and interruption cleanup, suppressed failures, restored
  interrupt status, and `UNKNOWN` post-dispatch outcomes.
- Compare UTF-8 backslash replacement, final-newline behavior, repeated trailing
  stdout blanks, empty stderr lines, literal semicolons, and NUL rejection with
  Python.

### Snapshot and query

- Differentially compare ordering, duplicates, active selections, missing
  targets, linked windows, and capture fields with Python against real tmux.
- Count transport calls and prove repeated, parallel, and relation stream
  operations execute zero calls after snapshot construction.
- Prove returned lists and maps reject mutation while retaining order.
- Property-test Boolean composition, Unicode matching, absence, regex behavior,
  empty-relation vacuity, and sequential/parallel cardinality.
- Compile valid generated expressions and reject invalid scalar, relation, and
  nullness operations.
- Generate sources twice and compare bytes.
- Round-trip every known JSON node and reject unknown schema, model, field, and
  operator IDs.

### Test harness and tmux compatibility

- Exercise tmux 3.2a, 3.3a, 3.4, 3.5, 3.6, literal 3.7, 3.7a, and 3.7b.
- Keep a tmux master lane informational because it is not a released
  compatibility target.
- Verify every extension lifecycle failure mode leaves no server, client,
  socket, or owned directory.
- Run the complete Java unit, compile, and real-tmux suite on the pinned JDK 21
  vendor and update.

### Static analysis and publication

- Run Spotless check, Error Prone, NullAway in JSpecify mode, Javadoc doclint,
  and the complete JUnit suite without broad exclusions.
- Publish to a temporary Maven repository and build plain Maven and Gradle
  consumers.
- Inspect POM and Gradle metadata and prove core publishes no Jackson, JUnit, or
  JSpecify dependency.
- Verify source and Javadoc jars, signatures, license, SCM metadata, and each
  `Automatic-Module-Name`.
- Verify generated metamodel classes and sources in binary, source, and Javadoc
  jars while proving the processor is absent from published variants.
- Exercise the included build from a fresh checkout to prove its repositories,
  imported catalog, and convention-plugin classpath are self-contained.
- Verify the distribution checksum and independently validate the wrapper JAR
  against Gradle's published checksum. Verify dependency checksums and reject
  dynamic versions, following Gradle's
  [wrapper verification guidance](https://docs.gradle.org/9.7.0/userguide/gradle_wrapper.html).
- Validate Central's required project name, description, URL, license,
  developer, SCM URL, SCM connection, and developer connection without adding
  personal data, as required by
  [Maven Central](https://central.sonatype.org/publish/requirements/).
- Pin build-JDK vendor and update. Build unsigned JAR, source, Javadoc, POM, and
  Gradle-module outputs in distinct checkout paths with fresh Gradle homes,
  fixed locale, encoding, and time zone, plus Javadoc `-notimestamp`; compare
  hashes. Detached PGP signatures are excluded because their creation is
  intentionally nondeterministic.

### Downstream journeys

- Compile and run default server/session creation, window split, pane send,
  capture, refresh, and teardown from plain Maven and Gradle consumers.
- Compile scalar and relational `FilterExpr` examples, lambda filtering, both
  cardinality collectors, the legacy edge parser, and compile-fail invalid
  field operations.
- Compile option, hook, environment, buffer, prompt, menu, and popup examples
  against the public artifacts.
- Compile Jackson round trips without Jackson on core's runtime path.
- Run a consumer JUnit 5 test that receives exact socket-path fixtures and
  proves cleanup after failure.
- Have an independent reviewer perform each journey without repository-internal
  imports or setup knowledge and record any API friction as a spike failure.

## Clean implementation sequence

After all spike findings are accepted, implementation starts from an empty
`java/` source tree rather than copying prototype code. The implementation is
split into independently reviewable slices:

1. Build, analysis, formatting, publication, and a minimal consumer smoke test.
2. Request/result types, blocking process transport, and transport hard gates.
3. Query AST, evaluator, collectors, and metamodel processor.
4. Version, capability, format, parser, snapshot graph, and resolution layers.
5. `Server`, `Session`, `Window`, `Pane`, and `Client` parity by object family.
6. Options, hooks, environment, buffers, prompts, menus, and remaining commands.
7. Jackson adapter and JUnit testkit.
8. Documentation, examples, compatibility matrix, packaging, and final
   adversarial review.

Each slice begins with failing public-contract tests, ends with its relevant
full gate, and is committed independently. A later slice may not weaken an
earlier gate.

## Adversarial review

An independent Java reviewer who did not author the artifact reviews this
architecture before the spike plan. The clean implementation receives a fresh
line-by-line review covering production code, build logic, annotation processor,
generated source, tests, consumer fixtures, documentation, and publication
metadata. A high-level public-symbol scan is not a substitute.

The rubric applies Effective Java guidance to static factories and builders,
equality and hashing, records, immutability and defensive copies, generic
variance, enums and sealed hierarchies, lambdas and streams, `Optional`,
exceptions, concurrency, serialization, module boundaries, and API evolution.
Every public symbol is treated as a downstream compatibility cost. The review
also checks nullness, cancellation, transport ownership, logging, processor
output, Javadocs, and Maven and Gradle metadata.

The reviewer must identify unnecessary files, speculative abstractions,
boolean-heavy APIs, leaking implementation types, misleading record equality,
hidden I/O, accidental mutability, broad suppressions, and local-path or AI
signatures. Findings are either fixed or rejected with source-backed rationale.
The independent reviewer reruns the downstream journeys and records the verdict
in `java/docs/reviews/`. The library is not complete until every actionable
finding is resolved and every verification gate passes on the clean
implementation.
