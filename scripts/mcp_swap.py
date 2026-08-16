#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["tomlkit>=0.13"]
# ///
"""Point every installed agent CLI at this build of ``libtmux-mcp``.

Use when you want to try the server you are editing in a real agent rather
than in a test. ``use`` rewrites each CLI's global config; ``revert``
restores from the timestamped backup the swap wrote. Swapping a config
that is already swapped keeps the first backup rather than taking a new
one, so ``revert`` always lands on the pre-swap config.

Sources
-------
``--source`` picks where the server comes from:

- ``dist`` builds ``installDist`` and names the launcher script it
  writes, so an agent spawns it directly with no Gradle in front of the
  handshake. This is the default.
- ``gradle`` launches through ``./gradlew :libtmux-mcp:run``, which
  rebuilds on every start. Current source with nothing to remember, at
  the cost of a build check per launch — and a slow first launch after a
  change can outlast a client's handshake timeout.
- ``path`` takes a launcher you name with ``--bin``, wherever it came
  from.

Examples
--------
```console
$ uv run scripts/mcp_swap.py detect
```

```console
$ uv run scripts/mcp_swap.py status
```

```console
$ uv run scripts/mcp_swap.py use --dry-run
```

```console
$ uv run scripts/mcp_swap.py use --socket /tmp/libtmux-java-dev/demo/s --safety destructive
```

```console
$ uv run scripts/mcp_swap.py revert
```

Scope
-----
Deliberately narrow, and best-effort:

- **Global configs only.** Project-local ``.mcp.json`` and
  ``.cursor/mcp.json`` are left alone; a swap is a thing you do to your
  own machine, not to a repository.
- **One server name.** Only the entry named by ``--name`` (default
  ``tmux``) is touched. Everything else in the file is preserved,
  including comments in TOML.
- **A backup per file, once.** Written beside the original as
  ``<name>.mcp-swap-backup``. ``revert`` moves it back.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import shutil
import subprocess
import sys
import typing as t

import tomlkit

REPO = pathlib.Path(__file__).resolve().parent.parent

#: What the launcher is called once ``installDist`` has written it.
DIST_LAUNCHER = REPO / "libtmux-mcp" / "build" / "install" / "libtmux-mcp" / "bin" / "libtmux-mcp"

BACKUP_SUFFIX = ".mcp-swap-backup"


class Layer(t.NamedTuple):
    """One CLI's global config, and how its MCP servers are spelled in it."""

    cli: str
    path: pathlib.Path
    #: Where the servers live: a path of keys from the document root.
    at: tuple[str, ...]
    #: ``json`` or ``toml``. TOML is edited through tomlkit so comments survive.
    format: str

    def exists(self) -> bool:
        return self.path.is_file()


LAYERS = (
    Layer("claude", pathlib.Path.home() / ".claude.json", ("mcpServers",), "json"),
    Layer("codex", pathlib.Path.home() / ".codex" / "config.toml", ("mcp_servers",), "toml"),
    Layer("cursor", pathlib.Path.home() / ".cursor" / "mcp.json", ("mcpServers",), "json"),
    Layer("gemini", pathlib.Path.home() / ".gemini" / "settings.json", ("mcpServers",), "json"),
    Layer("agy", pathlib.Path.home() / ".gemini" / "config" / "mcp_config.json", ("mcpServers",), "json"),
    Layer("grok", pathlib.Path.home() / ".grok" / "config.toml", ("mcp_servers",), "toml"),
)


# ------------------------------------------------------------------ reading and writing


def load(layer: Layer) -> t.Any:
    text = layer.path.read_text(encoding="utf-8")
    return tomlkit.parse(text) if layer.format == "toml" else json.loads(text)


def save(layer: Layer, document: t.Any) -> None:
    text = tomlkit.dumps(document) if layer.format == "toml" else json.dumps(document, indent=2) + "\n"
    layer.path.write_text(text, encoding="utf-8")


def servers(layer: Layer, document: t.Any, *, create: bool = False) -> t.Any:
    """The mapping of server name to launch spec, or None when there is none."""
    node = document
    for key in layer.at:
        if key not in node:
            if not create:
                return None
            node[key] = {}
        node = node[key]
    return node


def backup_of(layer: Layer) -> pathlib.Path:
    return layer.path.with_name(layer.path.name + BACKUP_SUFFIX)


# ------------------------------------------------------------------ what to point at


def launcher(args: argparse.Namespace) -> tuple[str, list[str]]:
    """The command and arguments an agent should spawn."""
    if args.source == "path":
        if not args.bin:
            raise SystemExit("--source path needs --bin")
        command, prefix = args.bin, []
    elif args.source == "gradle":
        command, prefix = str(REPO / "gradlew"), [
            "--quiet",
            "--console=plain",
            ":libtmux-mcp:run",
            "--args",
        ]
    else:
        command, prefix = str(DIST_LAUNCHER), []

    flags: list[str] = []
    if args.socket:
        flags += ["--socket", args.socket]
    if args.socket_name:
        flags += ["--socket-name", args.socket_name]
    if args.tmux:
        flags += ["--tmux", args.tmux]
    if args.safety:
        flags += ["--safety", args.safety]
    if args.watch:
        flags += ["--watch"]

    # Gradle takes the server's own flags as one --args string.
    if args.source == "gradle":
        return command, prefix + [" ".join(flags)]
    return command, prefix + flags


def build(args: argparse.Namespace) -> None:
    """Make sure the launcher this is about to point at actually exists."""
    if args.source != "dist":
        return
    print("building :libtmux-mcp:installDist ...", file=sys.stderr)
    subprocess.run(
        [str(REPO / "gradlew"), "--quiet", "--console=plain", ":libtmux-mcp:installDist"],
        cwd=REPO,
        check=True,
    )
    if not DIST_LAUNCHER.is_file():
        raise SystemExit(f"installDist did not write {DIST_LAUNCHER}")


# ------------------------------------------------------------------ commands


def cmd_detect(args: argparse.Namespace) -> int:
    for layer in LAYERS:
        state = "present" if layer.exists() else "missing"
        swapped = " (swapped)" if backup_of(layer).is_file() else ""
        print(f"{layer.cli:<8} {state:<8} {layer.path}{swapped}")
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    for layer in LAYERS:
        if not layer.exists():
            continue
        try:
            entry = (servers(layer, load(layer)) or {}).get(args.name)
        except (ValueError, tomlkit.exceptions.TOMLKitError) as error:
            print(f"{layer.cli:<8} unreadable: {error}")
            continue
        if entry is None:
            print(f"{layer.cli:<8} no '{args.name}' server")
            continue
        command = entry.get("command", "?")
        rest = " ".join(str(word) for word in entry.get("args", []))
        print(f"{layer.cli:<8} {command} {rest}".rstrip())
    return 0


def cmd_use(args: argparse.Namespace) -> int:
    build(args)
    command, arguments = launcher(args)
    print(f"pointing '{args.name}' at: {command} {' '.join(arguments)}".rstrip(), file=sys.stderr)

    for layer in chosen(args):
        if not layer.exists():
            print(f"{layer.cli:<8} skipped, no config")
            continue
        document = load(layer)
        entry = {"command": command, "args": arguments}
        if args.dry_run:
            print(f"{layer.cli:<8} would set {args.name} = {json.dumps(entry)}")
            continue
        # Taken once. Swapping something already swapped must still revert to
        # the config that was there before any of this started.
        if not backup_of(layer).is_file():
            shutil.copy2(layer.path, backup_of(layer))
        into = servers(layer, document, create=True)
        into[args.name] = entry
        save(layer, document)
        print(f"{layer.cli:<8} set {args.name}")
    return 0


def cmd_revert(args: argparse.Namespace) -> int:
    for layer in chosen(args):
        backup = backup_of(layer)
        if not backup.is_file():
            print(f"{layer.cli:<8} nothing to revert")
            continue
        if args.dry_run:
            print(f"{layer.cli:<8} would restore {backup}")
            continue
        shutil.move(str(backup), str(layer.path))
        print(f"{layer.cli:<8} restored")
    return 0


def cmd_doctor(args: argparse.Namespace) -> int:
    ok = True
    if not (REPO / "gradlew").is_file():
        print("no gradlew: is this the repository root?")
        ok = False
    if args.source == "dist" and not DIST_LAUNCHER.is_file():
        print(f"no launcher at {DIST_LAUNCHER}; run './gradlew :libtmux-mcp:installDist'")
        ok = False
    for layer in LAYERS:
        if not layer.exists():
            continue
        try:
            load(layer)
        except Exception as error:  # noqa: BLE001 - a broken config is what this reports
            print(f"{layer.cli:<8} will not parse: {error}")
            ok = False
    print("ready" if ok else "not ready")
    return 0 if ok else 1


def chosen(args: argparse.Namespace) -> tuple[Layer, ...]:
    if not args.cli:
        return LAYERS
    wanted = set(args.cli)
    return tuple(layer for layer in LAYERS if layer.cli in wanted)


# ------------------------------------------------------------------ argument parsing


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="mcp_swap", description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    def shared(sub: argparse.ArgumentParser) -> None:
        sub.add_argument("--name", default="tmux", help="the MCP server name to write (default: tmux)")
        sub.add_argument("--cli", action="append", choices=[layer.cli for layer in LAYERS])
        sub.add_argument("--dry-run", action="store_true", help="say what would change, change nothing")

    detect = commands.add_parser("detect", help="which agent CLIs have a config here")
    detect.set_defaults(run=cmd_detect)

    status = commands.add_parser("status", help="what each CLI currently points at")
    status.add_argument("--name", default="tmux")
    status.set_defaults(run=cmd_status)

    use = commands.add_parser("use", help="point every CLI at this build")
    shared(use)
    use.add_argument("--source", choices=("dist", "gradle", "path"), default="dist")
    use.add_argument("--bin", help="the launcher to use with --source path")
    use.add_argument("--socket", help="tmux socket path to serve")
    use.add_argument("--socket-name", help="tmux socket name to serve")
    use.add_argument("--tmux", help="which tmux binary the server should run")
    use.add_argument("--safety", choices=("readonly", "mutating", "destructive"))
    use.add_argument("--watch", action="store_true", help="push notifications as tmux changes")
    use.set_defaults(run=cmd_use)

    revert = commands.add_parser("revert", help="restore each config from its backup")
    shared(revert)
    revert.set_defaults(run=cmd_revert)

    doctor = commands.add_parser("doctor", help="check this is ready to swap")
    doctor.add_argument("--source", choices=("dist", "gradle", "path"), default="dist")
    doctor.set_defaults(run=cmd_doctor)

    return parser


def main() -> int:
    args = build_parser().parse_args()
    return int(args.run(args))


if __name__ == "__main__":
    raise SystemExit(main())
