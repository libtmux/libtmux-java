<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Java workspace">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-workspace

**Builds a session from a tmuxp-shaped YAML description.**

[![libtmux.org](https://img.shields.io/badge/docs-libtmux.org-0b7285)](https://libtmux.org/en/java/latest/workspace/)
[![libtmux-workspace](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-workspace)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-workspace)

</div>

[`WorkspaceBuilder.parse`][parse] reads a tmuxp-shaped YAML description into a
[`Workspace`][workspace] value, and [`WorkspaceBuilder.build`][build] makes the
session. Enough of [tmuxp](https://tmuxp.git-pull.com/)'s format to describe a
workspace and build it; not a runtime-compatible reimplementation.

`io.github.libtmux:libtmux-workspace` — [on Maven
Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-workspace).
API reference: [libtmux.org][reference] for the current trunk,
[javadoc.io][javadoc] for a released version. For a command line, see
[`libtmux-workspace-cli`][cli].

> Releases are numbered `0.1.N`, and each release increments `N`. The API is
> not settled: any release may change or remove exported identifiers, and the
> changelog marks each break. Pin an exact version to avoid one.

**Contents** — [Quick start](#quick-start) · [What you get
back](#what-you-get-back) · [Read first](#read-first-build-second) · [The
format](#what-the-format-supports) · [What it leaves out](#what-it-leaves-out)

## Quick start

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.1.0"))
    implementation("io.github.libtmux:libtmux-workspace")
}
```

Describe a workspace in YAML:

```yaml
# workspace.yaml
session_name: built
windows:
  - window_name: editor
    layout: even-horizontal
    panes:
      - shell_command: echo editor-pane-one
      - shell_command: echo editor-pane-two
  - window_name: server
    panes:
      - echo server-pane
```

### [`WorkspaceBuilder.build`][build]: make the session

[`parse`][parse] reads the text and `build` makes the session on a
[`Server`][server]:

```java
// Given: Server server
Workspace workspace = WorkspaceBuilder.parse("""
        session_name: built
        windows:
          - window_name: editor
            layout: even-horizontal
            panes:
              - shell_command: echo editor-pane-one
              - shell_command: echo editor-pane-two
        """);

Session session = WorkspaceBuilder.build(server, workspace);
```

[`WorkspaceBuilder.read`][read] does the same from a file on disk.

## What you get back

### [`Session`][session]: the one `build` made

`build` returns the session it made, so there is nothing to look up afterwards:

```java
// Given: Server server
Workspace workspace = WorkspaceBuilder.parse("""
        session_name: built
        windows:
          - window_name: editor
            layout: even-horizontal
            panes:
              - shell_command: echo one
              - shell_command: echo two
          - window_name: server
            panes:
              - echo three
        """);

workspace.sessionName();                          // → built
workspace.windows().size();                       // → 2

Session session = WorkspaceBuilder.build(server, workspace);

session.name();                                   // → built
session.windows().size();                         // → 2
session.windows().get(0).panes().size();          // → 2
```

It adds a session; it does not take over the one you had:

```java
// Given: Server server
Workspace workspace = WorkspaceBuilder.parse("session_name: added\nwindows:\n  - window_name: w\n");

WorkspaceBuilder.build(server, workspace);

server.sessions().size();                         // → 2
server.hasSession("libtmux");                     // → true
```

## Read first, build second

### [`WorkspaceBuilder.parse`][parse]: rejected while it is still text

`read` and `parse` produce a `Workspace` value, and `build` is what touches
tmux. A description tmux could not build is rejected before a single window
exists:

<!-- snippet: throws: IllegalArgumentException -->
```java
WorkspaceBuilder.parse("session_name: s\nwindows:\n  - window_name: w\n    layout: sideways");
// IllegalArgumentException — tmux has no layout called "sideways"
```

- Building half a workspace and then failing leaves a mess someone has to clean
  up by hand.
- Every layout name tmux accepts is accepted here, and nothing else is.

## What the format supports

| key | notes |
| --- | --- |
| `session_name` | required |
| `windows[].window_name` | |
| `windows[].layout` | any layout tmux knows; validated before building |
| `windows[].panes[]` | a bare string, a `{shell_command: ...}` mapping, or a list of commands |

A window with no panes stated still gets the one tmux makes for it.

## What it leaves out

- **A runtime-compatible tmuxp.** It reads enough of the format to build a
  workspace.
- **Taking over a session.** `build` adds one and leaves yours alone.
- **A command line.** [`libtmux-workspace-cli`][cli] loads, captures, converts
  and imports workspaces from a shell.

## Support and status

- **Releases.** Releases are numbered `0.1.N`, and each release increments
  `N`. The API is not settled: any release may change or remove exported
  identifiers, and the changelog marks each break. Pin an exact version to
  avoid one.
- **Layouts follow tmux.** Every layout name tmux accepts is accepted, and
  nothing else is.

## Documentation

- [`libtmux`](../libtmux/README.md) — the API this builds on
- [`libtmux-workspace-cli`][cli] — the command line
- [Root README](../README.md)

[build]: https://libtmux.org/en/java/latest/workspace/reference/io-github-libtmux-workspace-workspacebuilder-workspacebuilder-build/
[cli]: ../libtmux-workspace-cli/README.md
[javadoc]: https://javadoc.io/doc/io.github.libtmux/libtmux-workspace
[parse]: https://libtmux.org/en/java/latest/workspace/reference/io-github-libtmux-workspace-workspacebuilder-workspacebuilder-parse/
[read]: https://libtmux.org/en/java/latest/workspace/reference/io-github-libtmux-workspace-workspacebuilder-workspacebuilder-read/
[reference]: https://libtmux.org/en/java/latest/workspace/reference/
[server]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server/
[session]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-session-session/
[workspace]: https://libtmux.org/en/java/latest/workspace/reference/io-github-libtmux-workspace-workspace-workspace/
