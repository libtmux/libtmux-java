# 0001. An included build-logic convention build

Status: Accepted

## Context

Three ways of sharing Gradle configuration across the published modules were
measured against one fixed oracle: exact toolchain and formatting, Error
Prone, NullAway in JSpecify mode, exact publication metadata, and six fresh
consumer journeys spanning Maven and Gradle, plain, nullness-aware, and
module-path. A single project using feature source sets, a direct
multi-project build with explicit shared root configuration, and an included
`build-logic` build with precompiled convention plugins all passed every gate.

They differed on maintenance. The single-project layout needed dedicated
`AdhocComponentWithVariants` components to publish each optional feature
(Jackson, JUnit 5) as its own artifact, because the feature component's
secondary classes variant otherwise won Gradle's own artifact selection with
no JAR behind it. The direct multi-project layout duplicated shared
configuration across every project's build file. Only the included build
carried its conventions as reusable, versioned plugins, and only it passed a
later configuration-cache diagnostic that was not part of the original gate.

## Decision

Share build logic through an included `build-logic` build holding precompiled
convention plugins (`libtmux.java-library`, `libtmux.published-library`, and
siblings under `build-logic/src/main/kotlin/`), applied by each module that
needs them. Publish under the Maven group `io.github.libtmux`, verified
through the GitHub organisation that owns the code.

## Consequences

A new published module adds a directory and applies the relevant convention
plugin instead of repeating build logic inline.
`platformCoversEveryPublishedModule` (`.github/CONTRIBUTING.md`) keeps the set
of published Gradle directories and `libtmux-bom` in agreement, because
*applies the publishing plugin* and *declares a publication* came apart once
already. The included build is not itself published, so its plugins can
change shape without a compatibility guarantee to a consumer.
