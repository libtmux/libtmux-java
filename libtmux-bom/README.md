<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Java">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-bom

**Name one BOM version. Every BOM-managed libtmux coordinate follows it.**

[![libtmux.org](https://img.shields.io/badge/docs-libtmux.org-0b7285)](https://libtmux.org/en/java/)
[![libtmux-bom](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-bom?label=libtmux-bom)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-bom)

</div>

Import it as a platform and leave the version off every module it manages.
[`libtmux`](../libtmux/README.md),
[`libtmux-jackson`](../libtmux-jackson/README.md) and the rest are built against
each other, so one version keeps them together.

`io.github.libtmux:libtmux-bom` — [on Maven Central][central]. It is a platform
and has no API of its own; each module it manages links its reference from its
README.

**Contents** — [Quick start](#quick-start) · [What it manages](#what-it-manages)
· [What it leaves out](#what-it-leaves-out) · [Support](#support-and-status)

## Quick start

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.1.2"))

    implementation("io.github.libtmux:libtmux")            // no version
    implementation("io.github.libtmux:libtmux-jackson")    // no version
    testImplementation("io.github.libtmux:libtmux-junit5") // no version
}
```

<details>
<summary>Maven</summary>

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.libtmux</groupId>
      <artifactId>libtmux-bom</artifactId>
      <version>0.1.2</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```
</details>

Mixing two releases of these artifacts is the failure a platform exists to
prevent, and it is easy to do by hand when three coordinates each carry their
own version string.

### `./gradlew dependencies`: check it took

After adding the platform, the coordinates need no version. If one still does,
the platform is not being imported, most often because `platform(...)` was
written as an ordinary dependency:

```console
$ ./gradlew dependencies --configuration runtimeClasspath
```

```text
runtimeClasspath
+--- io.github.libtmux:libtmux-bom:0.1.2
|    \--- io.github.libtmux:libtmux:0.1.2 (c)
\--- io.github.libtmux:libtmux -> 0.1.2
```

The `-> 0.1.2` on a coordinate you wrote without a version is the
platform doing its job.

## What it manages

[`libtmux`](../libtmux/) · [`libtmux-jackson`](../libtmux-jackson/) ·
[`libtmux-junit5`](../libtmux-junit5/) · [`libtmux-kotlin`](../libtmux-kotlin/)
· [`libtmux-mcp`](../libtmux-mcp/) ·
[`libtmux-workspace`](../libtmux-workspace/) ·
[`libtmux-workspace-cli`](../libtmux-workspace-cli/) ·
[`libtmux-scala`](../libtmux-scala/) ·
[`libtmux-scala-cats`](../libtmux-scala-cats/) ·
[`libtmux-scala-ox`](../libtmux-scala-ox/)

A test checks that list:

- `platformCoversEveryPublishedModule` fails the build when the set of published
  artifacts stops matching this one.
- The Scala artifacts carry the `_3` suffix in their coordinates, as every
  Scala 3 artifact does.

## What it leaves out

- **An API.** A platform has no code; each module links its own reference.
- **Mixed releases.** One version applies to every module listed above.

## Support and status

- **Releases.** Releases are numbered `0.1.N`, and each release increments
  `N`. Pin an exact version, not a range: any release may change or remove
  exported identifiers.
- **Checked.** The version in every install block is held to the version this
  build publishes.

## Documentation

- [Root README](../README.md) · [RELEASING.md](../RELEASING.md)

[central]: https://central.sonatype.com/artifact/io.github.libtmux/libtmux-bom
