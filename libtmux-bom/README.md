# libtmux-bom

[![Maven Central](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-bom?label=libtmux-bom&color=blue)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-bom)

**Name a version once. Every other libtmux coordinate follows it.**

```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.1"))

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
      <version>0.0.1-alpha.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```
</details>

## Why

These modules are built against each other. Mixing two releases of them is the
failure a platform exists to prevent, and it is easy to do by hand when three
coordinates each carry their own version string.

## What it manages

[`libtmux`](../libtmux/) · [`libtmux-jackson`](../libtmux-jackson/) ·
[`libtmux-junit5`](../libtmux-junit5/) · [`libtmux-kotlin`](../libtmux-kotlin/) ·
[`libtmux-mcp`](../libtmux-mcp/) · [`libtmux-workspace`](../libtmux-workspace/)

That list is checked rather than trusted. `platformCoversEveryPublishedModule`
fails the build when the set of modules declaring a publication stops matching
this one — so a new module cannot be released without appearing here.

## Next

- [Root README](../README.md) · [RELEASING.md](../RELEASING.md)
