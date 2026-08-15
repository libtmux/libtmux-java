# Query expressions and the typed metamodel

## Status

Complete. The pushdown architecture gate, the JSON form, and the
handle-generation decision are recorded below.

Settled: the expression semantics, the compile-time safety argument, the edge
parser, the JSON form, the field model, and how the typed handles get written.

## What is settled

`FilterExpr<T>` is a sealed interface extending `Predicate<T>`, implemented by
records: `And`, `Or`, `Not`, `Compare`, `ToMany`, and `ToOne`. Extending
`Predicate` is what lets `stream().filter(expr)` work with no adapter, and being
a sealed tree of records is what lets the same value be printed, serialized, or
later compiled into a tmux `-f` filter. A lambda gives the first of those and
none of the rest.

Evaluation is a total function over the tree. Every consumer switches
exhaustively with no `default`, so adding a node kind breaks compilation at each
site that must learn about it rather than failing at runtime. The oracle walks a
built expression that way to prove the tree is usable as data, not only as code.

### Semantics proved

| rule                              | behavior                                            |
| --------------------------------- | --------------------------------------------------- |
| empty conjunction                 | true; it is the identity                            |
| empty disjunction                 | false; it is the zero                               |
| `any` over an empty relation      | false                                               |
| `all` over an empty relation      | true, vacuously                                     |
| `none` over an empty relation     | true                                                |
| `is` over an absent to-one target | false                                               |
| `exactlyOne` with no match        | `NoMatchException`                                  |
| `exactlyOne` with several matches | `MultipleMatchesException`                          |
| `oneOrEmpty` with no match        | empty `Optional`                                    |
| `oneOrEmpty` with several matches | `MultipleMatchesException`, never the first of many |

Vacuous truth for `all` is the standard reading and the one a tmux user expects:
a session with no windows does not fail "all windows are zoomed". Distinct
exceptions for zero and several matter because they are different bugs in the
caller's code, and returning the first of several would make an ambiguous query
look answered.

### Compile-time safety, proved rather than asserted

Field handles are typed by value kind. `TextField` carries `startsWith`,
`contains`, `endsWith`, `matches`, and `in`; `NumberField` carries the ordering
operators; `FlagField` carries only `isTrue` and `isFalse`. A to-many relation is
reachable only through `any`, `all`, or `none`, so an unquantified relation is
not itself a filter.

That claim cannot be tested from ordinary test sources, because code that does
not compile cannot be written there. The oracle therefore invokes
`javax.tools.JavaCompiler` on in-memory sources and requires each of these to be
rejected:

- a text operator on a number field, and on a flag field;
- an ordering operator on a text field;
- a to-many relation used directly as a filter; and
- a quantifier given a predicate over the wrong entity type.

A control case must compile. Without it a harness that rejected everything would
look like a perfect type system.

## The JSON form

The document encodes the backend-independent expression, not any backend's
lowering. Publishing a tmux-shaped intermediate form would be the wrong thing to
share: a lowering is by definition one target's business, and the pushdown gate
had just finished proving the AST needs no tmux vocabulary.

Writing switches exhaustively over the sealed tree, so a new node kind breaks
compilation rather than silently serializing as nothing. Reading refuses an
unknown schema version, model, field, relation, operator, or node kind, a value
of the wrong shape, and a missing member.

### Serialization is a second provenance boundary, and it agrees with the first

A document cannot carry an accessor. A field identifier therefore has to resolve
against a catalog the library already holds, which is the metamodel — so a
deserialized field is canonical by construction, and an identifier the catalog
does not know is refused rather than fabricated.

That is the same conclusion the pushdown gate reached from the other direction.
Pushdown needed fields to be name-addressable and library-minted to be
trustworthy; serialization makes name-addressable the only representable form.
The two constraints reinforce instead of competing, which is the strongest
evidence so far that the field model is right.

The kind does real work on the wire too: it decides how a value is read, and the
operator-by-kind table refuses a text operator on a number field. That table is
the runtime mirror of the compile-time typing, needed because a document is
untrusted input and the wire has no type system.

### Relations bind the catalog to a snapshot

A relation navigates a captured graph, so a catalog that resolves relation names
is bound to one snapshot: a filter document is deserialized _against_ the thing it
will filter, not in the abstract. This is the composition constraint appearing a
third time, now as a property of the wire format rather than of the API.

To-one relations are not on the wire; the reader refuses them explicitly rather
than pretending. The quantified forms round-trip, including over an empty
relation, where the vacuous reading has to survive serialization.

### Round trips are judged on behaviour

Every case asserts that the same population matches before and after, not merely
that the tree looks alike. The normalization identities the pushdown gate made
mandatory are asserted across the wire as well: an empty conjunction stays true,
an empty disjunction stays false, and their negations stay false and true.

An exhaustiveness guard requires every operator to have a wire spelling, so
adding one without a mapping fails here rather than on a user's wire.

### The core stays dependency-free, structurally

A separate consumer project declares only the core and nothing else. If the core
ever needs an optional module to compile or to run its published API, that project
stops compiling — a louder signal than a dependency report nobody reads.

## Handle generation: none

No generator is justified. The metamodel is small, explicit domain code.

The question was live while the field model was uncertain. It is not any more:
the pushdown gate and the JSON form between them fixed the shape a generator
would have had to emit — canonical fields minted through `EntityMetamodel`,
explicit kind, library-controlled provenance, static scalar handles,
snapshot-bound relation handles, and unknown identifiers refused. A processor
bakeoff at this point asks only whether that shape can be automated, which is a
tooling question rather than an architecture gate.

Hand-written also wins on the costs that a library actually pays: no annotation
processor lifecycle, no generated sources to debug, no incremental-build edge
cases, no source-generation determinism requirement, no IDE and build-tool
integration tax, and code that reads plainly under review.

**Reopen criterion.** Reconsider generation only if maintaining the metamodel
produces demonstrated duplication bugs, or materially burdens adding or renaming
canonical fields. The handle API is deliberately identical either way — a caller
writes `Pane_.command()` and `Session_.windows(snapshot)` — so generation can
replace hand-written declarations later without any consumer noticing.

### The drift guard replaces the generator

Handwriting invites exactly three mistakes, all of which a generator would have
made impossible, so they are asserted directly instead. A reflective conformance
check reads what a metamodel class actually declares and requires: identifiers
unique, each handle's kind matching the field it exposes, every scalar handle
canonically minted, declared coverage matching what the entity is meant to
expose, and relation handles taking the graph they navigate wherever the entity
does not hold its own relations.

Reflection rather than a maintained list is the point: a hand-kept inventory of
expected handles would drift the same way the metamodel does.

The guard carries its own red proofs — a metamodel that mints through the plain
builder instead of `EntityMetamodel`, one that gives two handles the same
identifier, and one whose coverage has moved — each required to fail. A guard
that has never failed is not evidence.

## Open decisions

- **Pushdown.** Snapshot filtering never pushes down. The node set retains enough
  data to compile into a tmux `-f` filter later, which the AST shape supports but
  nothing yet exercises.

## Composing with a captured hierarchy

Both models pass in isolation, which says nothing about whether they fit. Applied
to real snapshots on every lane, they do — and the fit imposes one constraint
neither spike could have surfaced alone.

**A relation has to name the snapshot it navigates.** A captured entity holds no
pointer back to its graph, deliberately: that is what keeps the states plain
immutable records and keeps a stale entity from resurrecting a dead server. So a
to-many handle cannot be a bare static like a scalar one. `Session_.windows()`
has to become `Session_.windows(snapshot)`, and the same for the to-one case.

That is a live constraint on how the handles are generated, not a detail. Scalar
handles can be static; relation handles are factories over a snapshot. Any
generator has to emit both shapes, and the split has to be visible in the API
rather than hidden behind a thread-local or a back-reference.

Filtering over those relations issues no tmux command, which is the line the two
spikes draw together: the snapshot is the boundary, and query navigation stays on
the near side of it. Cardinality also behaves on real data — a window linked into
two sessions genuinely is several window contexts, so `exactlyOne` over
`name == "win-a"` raises rather than silently picking one.

## Pushdown architecture gate

Run before the handle-generation bakeoff on purpose. Proving deterministic
incremental generation for a field model that lowering then invalidates is
wasted work; the dependency order is semantics, then lowering, then ergonomics.

Three lowering architectures were built blind against one differential matrix —
every vector evaluated in-JVM and asked of real tmux, on all eight lanes, with
the two answers required to be identical or the contender required to have
refused. 69 vectors x 8 lanes per contender.

The invariant was frozen before baking: `querySessions(expr)` means tmux
evaluates the complete predicate; if that cannot be guaranteed exactly for this
command, server version, fields and operators, it fails explicitly. Silent
approximation is the failure mode being hunted, not a tradeoff.

### Two outcomes, recorded separately

**Bakeoff winner: the residual planner.** It was the only implementation that
survived the adversarial constant-expression probe as written.

**Selected architecture: the exact partial compiler, with the planner's
normalized condition lattice grafted in.** Residual planning — the planner's
defining architectural difference — is not what saved it. A normalization layer
the exact compiler lacked is, and that layer is independently graftable.

Keeping both statements is the point. The first preserves what was measured; the
second keeps an implementation defect from deciding an architecture contest.

**Rejected: the restricted remote-expression type.** Runtime command, version and
value capability prevent compile-time eligibility from being authoritative. The
counterexample is measured: an expression over `list-clients` is structurally
lowerable yet operationally rejected below tmux 3.4, and two expressions with
identical structure and identical types differ in eligibility purely by which
entity they select over and which server answers. Its parallel hierarchy also
cost every seam: conjoining one restricted term with one core term degraded to an
opaque lambda and lost all pushdown.

### What the constant probe found

A bare literal in a tmux condition is read as a _variable lookup_, expands empty,
and is falsy. So `Not(And([]))` lowered to `#{?1,0,1}` matches **every** row,
exit 0, on all eight lanes, where Java answers false for every row. Two of the
three contenders emitted it — the second through its own blessed typed API, which
its dedicated exactness reviewer scored strong without probing.

The finding is not the missing null check. It is that **lowering must target a
normalized semantic IR capable of representing constants, rather than emitting
tmux condition syntax directly from AST nodes**. Patching the negation would fix
one symptom of a missing stage. The stage owns constant folding, associative
flattening, and composition shape, and only then renders.

### Silent divergences measured

Each produces a plausible row set with exit status 0. None is an error.

| construct            | tmux behavior                           | consequence                              |
| -------------------- | --------------------------------------- | ---------------------------------------- |
| `#{?1,0,1}`          | literal read as a variable, so falsy    | negated-empty matches every row          |
| `#{!:x}`             | does not exist before 3.6; expands `""` | negation returns no rows on 5 of 8 lanes |
| right-nested `Or`    | breaks between 99 and 100 operands      | a 100-member `in()` matches every row    |
| `$` in a name on 3.4 | stored `a$b`, reads back escaped        | text equality is inexact on that lane    |
| `#{<:2,10}`          | lexical, not arithmetic                 | numeric ordering wrong from 2 upward     |
| `#{m:foo**,foobar}`  | user data occupies glob syntax          | `startsWith("foo*")` over-matches        |
| a field valued `00`  | truthy; only `""` and `0` are falsy     | a flag test misreads the value           |

tmux's format escape character is `#`, not backslash: `#{==:a#,b,a#,b}` is 1
while the backslash form is 0. Lowering therefore escapes twice, fnmatch first
then format, and user data reaches the wire only through an escaped node.

### Capability is per command, not per version

| command              | 3.2a     | 3.3a     | 3.4+    |
| -------------------- | -------- | -------- | ------- |
| `list-sessions -f`   | filters  | filters  | filters |
| `list-windows -a -f` | filters  | filters  | filters |
| `list-panes -a -f`   | filters  | filters  | filters |
| `list-clients -f`    | rejected | rejected | filters |

Baseline row counts without `-f` confirm the filtering is real. Capability is
therefore entity x command x version, and a value-fidelity axis is needed beside
it, because a value that does not round-trip makes even equality inexact.

### Forced core changes

Both are backend-independent; the AST needs no tmux vocabulary, and all three
contenders compiled against an unmodified core.

**Field kind is explicit.** `FieldRef` carries `FieldKind`, and lowering switches
on it rather than on the operand's runtime class. Exactness previously rested on
a builder invariant the type system did not carry — a number field's operand
merely happens to box to `Integer` — and two contenders substituted that accident
for the field's real kind, which silently selects lexical comparison where the
caller meant arithmetic.

**Field provenance is unforgeable.** Pushdown eligibility depends on whether the
library wrote the accessor, and that must not be caller-declarable. The measured
hole: `Fields.text("session_name", r -> r.text("session_name").toLowerCase())`
lowers to a filter on `#{session_name}` and reads exact while answering a
different question, and no compiler can inspect the lambda to notice.

Public builders now mint derived fields, which are local-only. Canonical fields
come from `EntityMetamodel`'s protected factories, so minting one requires
declaring the type a metamodel by extension — a visible act rather than a boolean
somebody can set. `FieldProvenance` is sealed and its canonical instance is
package-private with a private constructor. Four compiler-invoked cases prove an
outsider cannot reach the instance, the constructor, the canonical factory, or a
third implementation of the sealed interface — with a control proving the same
probe compiles ordinary derived-field code, because the first version of that
control failed and briefly made all four rejections meaningless.

### The matrix changed jobs

All three contenders produced byte-identical per-vector outcomes on all eight
lanes. The matrix proves no contender weakens the frozen invariant and
discriminates nothing else; every finding above came from adversarial probes.

It is therefore a conformance suite, not the architecture gate. The gate is
generative and adversarial, over value fidelity, arity, nesting depth,
normalization identities, and capability. Its widest `in()` had three members,
which is why the hundred-member break went unseen.

Normalization identities worth asserting directly: `not(and())` is false,
`not(or())` is true, singleton `and`/`or` is the operand, double negation is
identity, associativity holds, and differently shaped trees over the same operand
set agree. Arity sweeps must cross the composition break rather than stop below
it.

### Still open

Value fidelity is one data point: only `$` on tmux 3.4 is measured, so every
exactness claim here is conditional on a full character x version x field survey.
The composition break is pinned between 99 and 100 on one lane only. Whether
partial pushdown is net positive is unresolved — relation residuals force a
second unfiltered listing that can dominate the saving — and nothing in the
design yet distinguishes a residual that excludes one row in twenty from one that
excludes seventeen.

## The legacy edge parser

`name__contains=dev` is parsed into the same expressions, and confined to one
entry point. The field and the operator arrive as untrusted strings, so their
pairing cannot be checked before the program runs; keeping that in a single
parser is what stops it from spreading into the typed API.

The parser is given a catalog of the fields a caller may name. An unknown field
is refused rather than guessed, `field` without a suffix means equality, and an
empty filter set yields the identity conjunction, which matches everything.

Every rejection it performs is a compile error in the typed form — a text
operator on a number field, an ordering operator on a text field, a text operator
on a flag, an unknown field, an unknown operator, and a value that is not of the
field's type. Asserting both sides of that correspondence is the point: it makes
the cost of the legacy shape explicit rather than hidden.

## Evidence

Forty-eight cases, all passing: nine over expression semantics and cardinality,
ten over compile-time rejection, six over the edge parser, three over field
provenance, fourteen over the JSON form, four over the metamodel drift guard, and
two proving the core compiles and runs with nothing else on the classpath.

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        metamodel \
        reuse \
        product \
        "$root/metamodel" \
        --rerun-tasks \
        :record-first:test \
        > "$root/artifacts/metamodel-record-first.log" 2>&1'
```

The cited artifact is the per-suite result summary rather than the build log. The
log records that a build succeeded and nothing about how many cases ran, so its
digest cannot tell a fifteen-case run from a twenty-one-case one.

Composition is covered separately, by 24 cases over real captures across all
eight tmux lanes, inside the hydration synthesis suite's 120. The pushdown gate
adds its own differential corpus across the same lanes.

| artifact                                  | SHA-256                                                            |
| ----------------------------------------- | ------------------------------------------------------------------ |
| `artifacts/metamodel-results.tsv`         | `adf6fbc0d25fb070714cfe97b5ac2ee99d01ac3dcf481771a27596d4b816a9d3` |
| `artifacts/pushdown-semantics.md`         | `089262cf2940f337467075acbcbd7a5b932d542501ab22432b5c67447701a536` |
| `artifacts/pushdown-coverage.tsv`         | `694c1bcf6e66b79435ec2cac8543952cb63dd5cbf7a1848e238eab8667a02bc8` |
| `artifacts/query-integration-results.tsv` | `cd22beec6844997629629a202e7fa191a8b8ec3cc1061173bbd0adf6e8f74e37` |
