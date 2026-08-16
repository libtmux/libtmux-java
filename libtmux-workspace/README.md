# libtmux-workspace

**Builds a session from a tmuxp-shaped YAML description.**

Enough of [tmuxp](https://tmuxp.git-pull.com/)'s format to describe a workspace
and build it. Not a runtime-compatible reimplementation.

`io.github.libtmux:libtmux-workspace` — [on Maven Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-workspace).

> **Alpha.** The API will change without notice.

## Install

```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.1"))
    implementation("io.github.libtmux:libtmux-workspace")
}
```

## Use it

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

```java
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

`WorkspaceBuilder.read(path)` does the same from a file on disk.

## What you get back

`build` returns the session it made, so there is nothing to look up afterwards:

```java
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
Workspace workspace = WorkspaceBuilder.parse("session_name: added\nwindows:\n  - window_name: w\n");

WorkspaceBuilder.build(server, workspace);

server.sessions().size();                         // → 2
server.hasSession("libtmux");                     // → true
```

## Read first, build second

`read` and `parse` produce a `Workspace` value; `build` is what touches tmux.
That split is the point: a description that tmux could not build is **rejected
while it is still text**, before a single window exists.

<!-- snippet: throws: IllegalArgumentException -->
```java
WorkspaceBuilder.parse("session_name: s\nwindows:\n  - window_name: w\n    layout: sideways");
// IllegalArgumentException — tmux has no layout called "sideways"
```

Building half a workspace and then failing leaves a mess someone has to clean up
by hand. Every layout name tmux accepts is accepted here, and nothing else is.

## What the format supports

| key | notes |
| --- | --- |
| `session_name` | required |
| `windows[].window_name` | |
| `windows[].layout` | any layout tmux knows; validated before building |
| `windows[].panes[]` | a bare string, a `{shell_command: ...}` mapping, or a list of commands |

A window with no panes stated still gets the one tmux makes for it.

## Next

- [`libtmux`](../libtmux/) — the API this builds on
- [Root README](../README.md)
