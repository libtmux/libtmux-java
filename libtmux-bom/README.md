# libtmux-bom

**Name a version once. Every other libtmux coordinate follows it.**

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.3"))

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
      <version>0.0.1-alpha.3</version>
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

## Check it took

After adding the platform, the coordinates below need no version. If one still
does, the platform is not being imported — most often because `platform(...)` was
written as an ordinary dependency:

```console
$ ./gradlew dependencies --configuration runtimeClasspath
```

```text
runtimeClasspath
+--- io.github.libtmux:libtmux-bom:0.0.1-alpha.3
|    \--- io.github.libtmux:libtmux:0.0.1-alpha.3 (c)
\--- io.github.libtmux:libtmux -> 0.0.1-alpha.3
```

The `-> 0.0.1-alpha.2` on a coordinate you wrote without a version is the platform
doing its job.

## What it manages

[`libtmux`](../libtmux/) · [`libtmux-jackson`](../libtmux-jackson/) ·
[`libtmux-junit5`](../libtmux-junit5/) · [`libtmux-kotlin`](../libtmux-kotlin/) ·
[`libtmux-mcp`](../libtmux-mcp/) · [`libtmux-workspace`](../libtmux-workspace/)

That list is checked rather than trusted. `platformCoversEveryPublishedModule`
fails the build when the set of modules declaring a publication stops matching
this one — so a new module cannot be released without appearing here.

## Next

- [Root README](../README.md) · [RELEASING.md](../RELEASING.md)
