# Build topology and publication coordinates

> **Superseded, 2026-08-15.** The coordinates below were chosen when this port
> lived inside the Python library's tree. The group is now `io.github.libtmux`,
> verified through the GitHub organisation that owns the code, and the Java
> package root matches it. What the note decided about build topology — the
> included `build-logic` convention build — still stands, and the reasoning about
> hyphens in a group id versus a package is why the two now agree instead of
> differing by a underscore.

## Verdict

Use the included `build-logic` convention build and publish these coordinates:

- `com.git-pull:libtmux`
- `com.git-pull:libtmux-jackson`
- `com.git-pull:libtmux-junit5`

The Java package root is `com.git_pull.libtmux`. The automatic module names
are `com.git_pull.libtmux`, `com.git_pull.libtmux.jackson`, and
`com.git_pull.libtmux.junit5`.

The build and Java names are settled. Release remains blocked until an
authorized Central Portal account verifies `com.git-pull` through the exact
`git-pull.com` DNS TXT challenge. This spike did not claim a namespace or
publish outside isolated file repositories.

## Fixed oracle

All contenders use the same exported `Probe` contract and the same lifecycle
tasks: `hardGates`, `publishSpike`, and `verifySpikePublication`. The oracle
requires:

- Gradle 9.7.0 and exact mise Temurin `21.0.11+10-LTS` with `--release 21`;
- Error Prone plugin 5.1.0 and Error Prone 2.50.0, NullAway 0.13.8 in JSpecify
  mode, Spotless 8.9.0, palantir-java-format 2.97.0, JUnit 5.14.4, JSpecify
  1.0.1, Vanniktech 0.37.0, and Jackson 2.21.5;
- pinned wrapper and dependency checksums with no dynamic versions;
- formatting, compilation, tests, Error Prone, NullAway, doclint, generated
  sources, sources/Javadoc JARs, publication metadata, and exact toolchain
  identity; and
- six fresh consumer journeys: plain, nullness-aware, and module-path projects
  in both Maven 3.9.16 and Gradle 9.7.0.

The isolated compile-negative gate detects direct-null, generic-method, and
JDK-annotation violations. Wildcard and generic-class cases remain documented
NullAway/JSpecify limitations. No broad production suppression is present.

Configuration-cache compatibility was measured after this fixed oracle. It is
comparative maintenance evidence, not a retroactive correctness gate.

JSpecify is `compileOnly`, not `compileOnlyApi`. It is absent from POM and
Gradle metadata, while the package-level `@NullMarked` annotation remains
readable in the binary JAR. A plain consumer has no JSpecify, Jackson, or JUnit
dependency.

## Contender results

The three implementations are independent. Each has its own Gradle settings,
catalog, scripts, source, output, and runner scope. Coordinate runs copy one
contender's inputs into a fresh directory. No contender reads another
contender's source, output, or cache.

### Single-project feature source sets

One project owns core, Jackson, and JUnit feature source sets. Core strips the
optional feature variants. Two dedicated `AdhocComponentWithVariants`
components publish JAR-backed API/runtime variants for Jackson and JUnit. The
dedicated configurations are necessary because the feature component's
secondary classes variant otherwise wins Gradle artifact selection with no
JAR.

Common-gate result: pass; 35 tasks; exact metadata; all consumers pass; no
Javadoc warning or Gradle 10 deprecation under `--warning-mode all`. Strict
configuration-cache storage later failed on four script-object captures.

### Direct multi-project configuration

Four declarative projects use explicit shared root configuration without an
included convention build.

Common-gate result: pass; 46 tasks; exact metadata; all consumers pass; no
Javadoc warning or Gradle 10 deprecation under `--warning-mode all`. Strict
configuration-cache storage later failed on three script-object captures.

### Included convention build

Four declarative projects use an included `build-logic` build with precompiled
Java-library and published-library conventions. The included build declares
its repositories, imports the root catalog explicitly, and carries every
external plugin implementation on its own classpath. Vanniktech's top-level
plugin owns each standard module publication.

Final result: pass; 55 tasks from a fresh copied tree and fresh Gradle home;
configuration cache stored and reused; no Javadoc warning or Gradle 10
deprecation under `--warning-mode all`.

## Ranking and synthesis

The protocol ranks lexicographically. Later criteria cannot compensate for an
earlier loss.

| criterion        | single project                                       | direct multi-project                                    | included build-logic                               |
| ---------------- | ---------------------------------------------------- | ------------------------------------------------------- | -------------------------------------------------- |
| correctness      | pass                                                 | pass                                                    | pass                                               |
| downstream API   | pass                                                 | pass                                                    | pass                                               |
| maintenance      | loss: custom components; cache diagnostic fails      | loss: shared root configuration; cache diagnostic fails | win: reusable conventions; cache diagnostic passes |
| performance      | observed 35 tasks; not ranked after maintenance loss | observed 46 tasks; not ranked after maintenance loss    | observed 55 tasks                                  |
| build complexity | observed custom feature publication; not ranked      | observed explicit cross-project wiring; not ranked      | accepted included-build boundary                   |

All three contenders survive the fixed oracle and downstream consumer gates.
The included convention build wins at the first differentiating criterion,
maintenance; later criteria do not overturn that result.

Synthesis is a clean copy of that contender. Its vendored oracle makes the
single-project respike's result executable: every Gradle metadata variant must
have a nonempty `files` array, and no variant may publish
`LibraryElements=classes` or `LibraryElements=resources`. A red-green fixture
proves this rejects the original A failure, an empty API variant, and a
file-backed resources variant. No implementation was grafted from the direct
multi-project contender; its explicit project graph served as an independent
metadata cross-check. Vendoring also keeps a copied synthesis tree
self-contained.

## Coordinates

| family          | group tested in all contenders | result                                                                                                                        |
| --------------- | ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| domain          | `com.git-pull`                 | selected; DNS exists; Central account verification remains required                                                           |
| repository host | `io.github.tmux-python`        | consumers pass; GitHub organization exists, but Central automatic registration covers the login username, not an organization |
| organization    | `org.tmuxpython`               | consumers pass, but no corresponding DNS A record was found; rejected as implausible and unclaimable from current evidence    |

[Central's namespace instructions](https://central.sonatype.org/register/namespace/)
require an exact reversed owned domain, preserve hyphens in the Maven group,
and check the exact domain's TXT record. They also state that automatic GitHub
namespaces cover the login username rather than an organization. The
[coordinate requirements](https://central.sonatype.org/publish/requirements/coordinates/)
permit the hyphen in `com.git-pull`. Java identifiers do not, so the package
uses the architecture's `com.git_pull` spelling.

All nine topology/group combinations publish only to isolated repositories.
Each one passes plain, nullness-aware, and module-path Maven and Gradle
consumers. The package and imports remain the same across group experiments;
the bakeoff tests publication ownership separately from Java source identity.

## Published dependency graphs

Core has no POM dependencies and no Gradle metadata dependencies. JSpecify,
Jackson, JUnit, the metamodel processor, and build logic are absent.

`libtmux-jackson` publishes these compile/API dependencies:

- `com.git-pull:libtmux:0.0.0-spike`
- `com.fasterxml.jackson.core:jackson-databind:2.21.5`

`libtmux-junit5` publishes these dependencies:

- `com.git-pull:libtmux:0.0.0-spike` at compile/API scope
- `org.junit:junit-bom:5.14.4` as an imported platform
- `org.junit.jupiter:junit-jupiter-api` at compile/API scope

The oracle checks the same graph in POM XML and Gradle module JSON. It also
requires exactly 15 nonempty files: binary, sources, Javadoc, POM, and Gradle
module metadata for each artifact. Binary JARs carry the license and exact
automatic module name; sources and Javadocs contain the generated probe; POMs
contain project name, description, URL, license, SCM, and non-personal
developer metadata.

## Consumer journeys

- Plain Maven and Gradle projects depend only on core and compile and run.
- Nullness-aware projects add JSpecify and Jackson explicitly, read the
  retained `@NullMarked` contract, and call the adapter.
- Module-path projects use the three automatic module names and the JUnit
  extension without split-package, service-loading, reflection, or
  annotation-processing workarounds.

The originally failing single-project/domain Gradle nullness consumer was
rerun unchanged after the dedicated-configuration graft and passed.

## Resolver, signing, and reproducibility

Foojay resolver 1.0.0 ran only in a fresh non-product project with local
auto-detection disabled. It downloaded Amazon Corretto, compiled with
`--release 21`, and verified language 21, Amazon vendor, runtime
`21.0.12+8-LTS`, and installation below the fresh resolver home's `jdks`
directory. Product artifacts remained on exact mise Temurin
`21.0.11+10-LTS`.

An ephemeral `libtmux spike <noreply@spike.invalid>` test key signed all 15
synthesis artifacts. All 15 detached armored signatures verified locally. The
key was not published, and its `gpg-agent` stopped before the gate returned.
Signatures are excluded from reproducibility comparisons.

The final numbered reproducibility run copied only synthesis inputs into `a`
and `b`. Each copy used a distinct fresh Gradle home, `LANG=C.UTF-8`,
`LC_ALL=C.UTF-8`, `TZ=UTC`, `SOURCE_DATE_EPOCH=1786233600`, UTF-8 file
encoding, exact mise Temurin, and Javadoc `-notimestamp`. Both relative-path
manifests contain the same 15 expected paths. Their artifact SHA-256 manifests
are byte-identical.

## Falsification and respikes

A later strict configuration-cache diagnostic fails A with four captured
script objects in its policy, nullness, toolchain, and variant-selection tasks.
It fails B with three captures in its policy, nullness, and toolchain tasks.
C's typed verification tasks store and reuse the cache. Because the diagnostic
was not part of the fixed oracle, it contributes to maintenance ranking rather
than rejecting A or B. The failures are preserved rather than weakened or
grafted into synthesis.

The first coordinate matrix exposed Gradle secondary variants from the
single-project feature component. Three nonfatal variants ran against the same
consumer:

- dedicated JAR-backed configurations: pass;
- component variant-name filtering: consumer hard fail; and
- removal of outgoing feature secondary variants: consumer hard fail.

The passing dedicated model was restored before the full matrix.

The synthesis review then exercised the claimed graft directly. Before the
vendored-oracle change, all three invalid module fixtures were accepted. After
the change, the original missing-files/classes variant, an empty-files API
variant, and a file-backed resources variant are all rejected while the valid
synthesis repository passes.

The first copied synthesis run exposed a sibling-oracle path dependency. Three
variants passed: vendored oracle, harness sibling, and injected absolute
property. Vendoring was selected because it is the only self-contained input
model. The vendored executable adds the selected variant-files invariant to
the shared publication checks.

The next run exposed ordering, not content, in the expected artifact manifest:
both sides had the same 15 unique paths. Three variants passed: canonical
C-sorted expected data, sort-normalization at comparison time, and legacy
artifact enumeration. Canonical stored order was selected. The final runner
asserts `LC_ALL=C sort -c` and generates actual manifests with explicit
`LC_ALL=C` sorting.

## Result record

| gate                           | oracle                                                                   | contender A                            | contender B                            | contender C                            | synthesis               | verdict                       | artifact digest                                                           |
| ------------------------------ | ------------------------------------------------------------------------ | -------------------------------------- | -------------------------------------- | -------------------------------------- | ----------------------- | ----------------------------- | ------------------------------------------------------------------------- |
| build and analysis             | exact toolchain, pins, formatting, tests, Error Prone, NullAway, doclint | pass, 35 tasks                         | pass, 46 tasks                         | pass, 55 tasks                         | pass, 55 tasks          | pass                          | `sha256:df9c23c3e45615ffc92a602e37525d69c918b37f5ec0aae8e0b19be85aff1ce0` |
| publication                    | 15 files, exact POM/module graph, JAR contents and names                 | pass                                   | pass                                   | pass                                   | pass                    | pass                          | `sha256:df9c23c3e45615ffc92a602e37525d69c918b37f5ec0aae8e0b19be85aff1ce0` |
| coordinate consumers           | three groups times six fresh consumers                                   | pass                                   | pass                                   | pass                                   | pass, selected group    | pass: 9 cells and 54 journeys | `sha256:303da1f6b3dd0bf98701a1839373efdffea47d4e604ed88898a772bfa4ee556f` |
| configuration-cache diagnostic | post-oracle maintenance evidence; second run reuses entry                | fails, four captured script objects    | fails, three captured script objects   | pass, stored and reused                | pass, stored and reused | C wins maintenance; no reject | `sha256:3b5d14d0f4ed2df7b4282780298fa9d5e52a9f623ff087e68eb48780b91f32cb` |
| Foojay                         | fresh resolver home provisions Amazon Java 21 below `jdks`               | not applicable; shared resolver oracle | not applicable; shared resolver oracle | not applicable; shared resolver oracle | pass, resolver only     | pass                          | `sha256:3a6c92d43c923033068bf4a0dea2a3ccde7e76649f897f4d51199ec881105797` |
| reproducibility                | two copies, exact path set and byte-identical artifact manifest          | pass, 15 paths                         | pass, 15 paths                         | pass, 15 paths                         | pass, 15 paths          | pass                          | `sha256:d93bfb820d0736e3af798079c312ae451040e092ba9d2987b98251291d8dc3de` |
| signing                        | equal nonempty artifact/signature sets; all verify; agent stopped        | not applicable; synthesis gate         | not applicable; synthesis gate         | not applicable; synthesis gate         | pass, 15 of 15          | pass                          | `sha256:9371a5721cc67c2c8f304f029702a4ea04d69ddee6daeabf6a926d27498f3338` |
| process and repository cleanup | locked owned-process verifier; Git scope check                           | pass                                   | pass                                   | pass                                   | pass                    | pass                          | `sha256:c8cd230ab39cdfd5af69ee438d475754eb14787b05917880ae80bd40fc7dc618` |

## Commands

The final single-project gate used:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        build-single-secondary-fix \
        reuse \
        product \
        "$root/build/single-project" \
        --warning-mode \
        all \
        clean \
        hardGates \
        publishSpike \
        verifySpikePublication'
```

The final direct multi-project gate used:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        build-direct-final \
        reuse \
        product \
        "$root/build/direct-multiproject" \
        --warning-mode \
        all \
        clean \
        hardGates \
        publishSpike \
        verifySpikePublication'
```

The included-build acceptance copied only its inputs and used a fresh home:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    fresh="$(mktemp -d "$root/build/convention-copy.XXXXXX")"; \
    tar --exclude=.gradle --exclude="*/build" \
        -C "$root/build/convention-build" -cf - . \
        | tar -C "$fresh" -xf -; \
    "$root/artifacts/run-gradle.sh" \
        build-convention \
        fresh \
        product \
        "$fresh" \
        --warning-mode \
        all \
        clean \
        hardGates \
        publishSpike \
        verifySpikePublication'
```

The synthesis gate used:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        synthesis-review-repair \
        reuse \
        product \
        "$root/build/synthesis" \
        --warning-mode \
        all \
        clean \
        hardGates \
        publishSpike \
        verifySpikePublication'
```

The matrix runner executes each Maven wrapper with an explicit `-f` POM and
each Gradle consumer with an explicit project directory:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-coordinate-matrix.sh"'
```

The fresh synthesis consumer, contender reproducibility, synthesis
reproducibility, signing, and process gates used:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-synthesis-consumers-review-repair.sh"'
```

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-contender-reproducibility.sh"'
```

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-reproducibility.sh" \
        synthesis-review-repair-4 \
        "$root/build/synthesis"'
```

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-signing.sh"'
```

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/verify-no-owned-processes.sh" \
        "$root/artifacts/owned-processes.tsv" \
        "$root/artifacts/process-start-token.sh" \
        "$root/artifacts/validate-owned-process-ledger.sh"'
```

## Evidence digests

Artifact labels are relative to the disposable root. No nonce, PID, hostname,
username, or local absolute path is durable.

| artifact                                                                | SHA-256                                                            |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `artifacts/build-single-secondary-fix.log`                              | `80b2d338b319456091f9e279523fd5a0a0dc477b9d8c6b570785836b48abaf2b` |
| `artifacts/build-direct-final.log`                                      | `b49b5dd307bcf49daf5ca5318de2c02c849a1278781fcfb4149b54b0b1debfe9` |
| `artifacts/build-convention-config-cache-typed-2a.log`                  | `4e5fb6f7de7d96146e1b587ea0199487faf8f5f27961c0df48637b62ebbfa795` |
| `artifacts/build-convention-config-cache-typed-2b.log`                  | `30272fe316b333e4874aa16270246ed8600b9ff877c09c23791f0a145e92e594` |
| `artifacts/build-convention-fix.log`                                    | `08632ee3e399dd7ff6a384d35652eb4151206b4d9371f9d959387cee2ca1d2ce` |
| `artifacts/build-convention-fresh-token.txt`                            | `706ec9356f020208fb810701113632bfc11e8c3fcb8bc02e1c6f964a34b0328d` |
| `build/synthesis/oracle/verify-publication.sh`                          | `1e4c07c7f9738900904406a49c687cbf525a87f0e0f4d03aa7e8e4dd75eb4817` |
| `artifacts/synthesis-oracle-variant-test-red.log`                       | `1c9e11c9218d6c99efed1f419467e670f711c008427cd54ee67cba86570aa4fb` |
| `artifacts/synthesis-oracle-variant-test-green.log`                     | `163d63d06fef4efe5c90b69ed7ce30f56fff441881f99cf774968df7367782ec` |
| `artifacts/build-synthesis-review-repair.log`                           | `4e0790c673597013b0633e0b920a85468088cdf37bba56c8e72ce3a16a0a19bb` |
| `artifacts/build-synthesis-review-config-cache-1.log`                   | `154d69dde00311d20ab5a3a264f87d751d8f5f169774c6a3b81154b78a5f8230` |
| `artifacts/build-synthesis-review-config-cache-2.log`                   | `dc62991406716b313e5185ceaf20850dd5fb2ced174695ba4bab9ab2d9e82f21` |
| `artifacts/a-domain-rerun.log`                                          | `46990c0a58301fc1469f74f6e8afd9fe4fb25e8356a145a9dbe0465843af326d` |
| `artifacts/config-cache-contenders.log`                                 | `3b5d14d0f4ed2df7b4282780298fa9d5e52a9f623ff087e68eb48780b91f32cb` |
| `artifacts/build-single-config-cache-1.log`                             | `6d5ecefb7019edb5216d6e36674ea6e51357f78661a5ca59dcf8bcb824b57331` |
| `artifacts/build-direct-config-cache-1.log`                             | `481f9f0b46a3ac0326296ed9c149b4e42dad9511aac4328fee7d56dc87228299` |
| `artifacts/coordinate-matrix.log`                                       | `303da1f6b3dd0bf98701a1839373efdffea47d4e604ed88898a772bfa4ee556f` |
| `artifacts/coordinate-matrix-summary.log`                               | `e4f8e15dab6391f3770aa4d2126ebbc9df3d46d7cdd11afd5bf6e396fe11e88b` |
| `artifacts/coordinate-claimability.log`                                 | `8f98cf93f21069cf87cd261d42c3e6af0378a28b6648e483cac5c3043bd82919` |
| `artifacts/foojay-provisioning.log`                                     | `3a6c92d43c923033068bf4a0dea2a3ccde7e76649f897f4d51199ec881105797` |
| `artifacts/respike-a-secondary.log`                                     | `06a7ea0918936b8cadea1559776e6fcf9c911bff27b7f2a0ae6ef9a5b8d7b95b` |
| `artifacts/respike-a-secondary-logs.sha256`                             | `53d7b17cd7d4c82a96797025529e9f2781abeda7de19666e34a9662916c84929` |
| `artifacts/respike-repro-oracle.log`                                    | `f1c7793732bf9f5136f823996d92f746b5d73633f16c2f6fff3eaa20352d252e` |
| `artifacts/respike-repro-oracle-logs.sha256`                            | `04033de7aadd5f1426ec6f7d0632a0aed4dd0c31263296adf8398a76e74146b7` |
| `artifacts/repro-manifest-order-root-cause.log`                         | `b4aeebe22722b8073a91bbfb998b4c10ed01eaee3d61d1db7e4303f16721b05b` |
| `artifacts/respike-repro-manifest-order.log`                            | `4acf4b437e68f7b16ce025f61d9bac5717c30e767e21597782ee8929ed5f7b44` |
| `artifacts/respike-repro-manifest-order-logs.sha256`                    | `24b661ea8c0a5cfffedb8404ea0876106200fa19b28b8af7b2afa98f0d68e4ed` |
| `artifacts/repro-synthesis-review-repair-4.paths`                       | `a124c3f4a032ded4400f7c59bd724717092436bacae58ff0a7941d0f986c0358` |
| `artifacts/repro-synthesis-review-repair-4-artifacts.sha256`            | `aea9c983d4f79274c72a4b669478c14a5fba89193410534d51d5cc35cfe8febf` |
| `artifacts/repro-synthesis-review-repair-4-a.log`                       | `a65677e24ea9cb7e6efd61c3bb5d3e6313865830a9725510759b14eb3dfaf1b0` |
| `artifacts/repro-synthesis-review-repair-4-b.log`                       | `80e1efd77a0cbea3835c3139317c9973b5c0036e93e37d479b0bf54b54a6cb97` |
| `artifacts/repro-contenders.log`                                        | `d93bfb820d0736e3af798079c312ae451040e092ba9d2987b98251291d8dc3de` |
| `artifacts/repro-single-project-artifacts.sha256`                       | `9f034031a5ca32a0a9fd65477913f80b30a4e2854768089cbde9d8ed21784417` |
| `artifacts/repro-direct-multiproject-artifacts.sha256`                  | `7a7a1086c2920068f68078f68ad579a492d45ac8c69cebea590dd3061f072b5e` |
| `artifacts/repro-convention-build-artifacts.sha256`                     | `aea9c983d4f79274c72a4b669478c14a5fba89193410534d51d5cc35cfe8febf` |
| `artifacts/consumers-synthesis-review-repair.log`                       | `2415c0700e866f26bc82f5d85a7a10e91e143f875894731ba786a342a1ddb311` |
| `artifacts/publication-review-resolution.log`                           | `dc2c9b238147d6a6e5c3a177fd0341acce1888d9a82d9f191bda80456a95397d` |
| `artifacts/signing-artifacts.txt`                                       | `258009e104a9db6037f085e95d8c8e710332fe6b22f1f127e1aed17504d446f6` |
| `artifacts/signing-signatures.txt`                                      | `e8a25482578cf42376a817a9558ced6b0b21dcafe090b3228dbbc8007d0e91c0` |
| `artifacts/signing-signature-sha256.txt`                                | `377693af090bed987ab2ef063a8c3fb7a0d0d36d7484e4e216f84a3ad2a8e9c0` |
| `artifacts/signing-verification.log`                                    | `9371a5721cc67c2c8f304f029702a4ea04d69ddee6daeabf6a926d27498f3338` |
| `build/synthesis/build/reports/nullness-negative/nullness-negative.txt` | `89eaa43a8f31efaae5eff12a0ff7fd88c3b5ddf4fd94571f6eabf9124c55fd6c` |
| `artifacts/process-cleanup.log`                                         | `c8cd230ab39cdfd5af69ee438d475754eb14787b05917880ae80bd40fc7dc618` |
| `artifacts/task-3-document-audit.log`                                   | `5dc8c64a45df36b56017756a84ef66a4efc2eca0d5f07ad6915a45572ab8be61` |
| `artifacts/task-3-evidence.sha256`                                      | `df9c23c3e45615ffc92a602e37525d69c918b37f5ec0aae8e0b19be85aff1ce0` |

## Release precondition

Before any Central release, an authorized account must register
`com.git-pull`, place the assigned verification key in the exact
`git-pull.com` TXT record, and confirm Central reports the namespace verified.
That account-side action is deliberately outside this disposable spike.
