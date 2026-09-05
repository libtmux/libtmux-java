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
$ uv run scripts/mcp_swap.py use --socket /tmp/libtmux-java-dev/demo/s
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
  including comments in TOML and JSONC.
- **A backup per file, once.** Written beside the original as
  ``<name>.mcp-swap-backup``. ``revert`` moves it back.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import stat
import subprocess
import sys
import tempfile
import typing as t

import tomlkit

REPO = pathlib.Path(__file__).resolve().parent.parent

#: What the launcher is called once ``installDist`` has written it.
DIST_LAUNCHER = (
    REPO / "libtmux-mcp" / "build" / "install" / "libtmux-mcp" / "bin" / "libtmux-mcp"
)

BACKUP_SUFFIX = ".mcp-swap-backup"


class Layer(t.NamedTuple):
    """One CLI's global config, and how its MCP servers are spelled in it."""

    cli: str
    path: pathlib.Path
    #: Where the servers live: a path of keys from the document root.
    at: tuple[str, ...]
    #: ``json``, ``jsonc``, or ``toml``.
    format: str

    def exists(self) -> bool:
        return self.path.is_file()


LAYERS = (
    Layer("claude", pathlib.Path.home() / ".claude.json", ("mcpServers",), "json"),
    Layer(
        "codex",
        pathlib.Path.home() / ".codex" / "config.toml",
        ("mcp_servers",),
        "toml",
    ),
    Layer(
        "cursor", pathlib.Path.home() / ".cursor" / "mcp.json", ("mcpServers",), "json"
    ),
    Layer(
        "gemini",
        pathlib.Path.home() / ".gemini" / "settings.json",
        ("mcpServers",),
        "json",
    ),
    Layer(
        "grok", pathlib.Path.home() / ".grok" / "config.toml", ("mcp_servers",), "toml"
    ),
    Layer(
        "agy",
        pathlib.Path.home() / ".gemini" / "config" / "mcp_config.json",
        ("mcpServers",),
        "json",
    ),
    Layer(
        "opencode",
        pathlib.Path(
            os.environ.get("XDG_CONFIG_HOME") or pathlib.Path.home() / ".config"
        )
        / "opencode"
        / "opencode.jsonc",
        ("mcp",),
        "jsonc",
    ),
    Layer(
        "pi",
        pathlib.Path.home() / ".pi" / "agent" / "mcp.json",
        ("mcpServers",),
        "jsonc",
    ),
)

CLI_ALIASES = {"antigravity": "agy"}
CLI_COLUMN = max(len(layer.cli) for layer in LAYERS) + 1

# Pi itself has no MCP client. This extension reads the config above.
PI_ADAPTER_DIR = (
    pathlib.Path.home() / ".pi" / "agent" / "npm" / "node_modules" / "pi-mcp-adapter"
)
PI_ADAPTER_HINT = "needs the pi-mcp-adapter package; pi has no built-in MCP client"


# ------------------------------------------------------------------ JSONC


_JSON_WS = " \t\n\r"


def _jsonc_blank_comments(text: str) -> str:
    """Blank comments without moving offsets used by the edit scanner."""
    out = list(text)
    i = 0
    in_string = False
    while i < len(text):
        char = text[i]
        if in_string:
            if char == "\\":
                i += 2
                continue
            if char == '"':
                in_string = False
            i += 1
        elif char == '"':
            in_string = True
            i += 1
        elif char == "/" and i + 1 < len(text) and text[i + 1] == "/":
            while i < len(text) and text[i] != "\n":
                out[i] = " "
                i += 1
        elif char == "/" and i + 1 < len(text) and text[i + 1] == "*":
            end = text.find("*/", i + 2)
            end = len(text) if end == -1 else end + 2
            for index in range(i, end):
                if out[index] != "\n":
                    out[index] = " "
            i = end
        else:
            i += 1
    return "".join(out)


def _jsonc_blank_trailing_commas(text: str) -> str:
    """Blank trailing commas so the standard JSON decoder can parse JSONC."""
    out = list(text)
    i = 0
    in_string = False
    last_comma = -1
    while i < len(text):
        char = text[i]
        if in_string:
            if char == "\\":
                i += 2
                continue
            if char == '"':
                in_string = False
            i += 1
            continue
        if char == '"':
            in_string = True
            last_comma = -1
        elif char == ",":
            last_comma = i
        elif char in "}]":
            if last_comma != -1:
                out[last_comma] = " "
            last_comma = -1
        elif char not in _JSON_WS:
            last_comma = -1
        i += 1
    return "".join(out)


def _jsonc_loads(text: str) -> t.Any:
    if not text.strip():
        return {}
    return json.loads(_jsonc_blank_trailing_commas(_jsonc_blank_comments(text)))


class _JsoncMember(t.NamedTuple):
    key: str
    start: int
    end: int
    value_start: int
    value_end: int


class _JsoncScanner:
    """Locate value spans in comment-blanked JSON text."""

    def __init__(self, text: str) -> None:
        self.text = text
        self.pos = 0

    def skip_ws(self) -> None:
        while self.pos < len(self.text) and self.text[self.pos] in _JSON_WS:
            self.pos += 1

    def read_string(self) -> str:
        start = self.pos
        self.pos += 1
        while self.pos < len(self.text):
            char = self.text[self.pos]
            if char == "\\":
                self.pos += 2
                continue
            self.pos += 1
            if char == '"':
                break
        return self.text[start : self.pos]

    def read_value(self) -> tuple[int, int]:
        self.skip_ws()
        start = self.pos
        char = self.text[self.pos]
        if char == '"':
            self.read_string()
        elif char in "{[":
            self._read_container()
        else:
            while (
                self.pos < len(self.text)
                and self.text[self.pos] not in ",}]"
                and self.text[self.pos] not in _JSON_WS
            ):
                self.pos += 1
        return start, self.pos

    def _read_container(self) -> None:
        self.pos += 1
        depth = 1
        while self.pos < len(self.text) and depth:
            char = self.text[self.pos]
            if char == '"':
                self.read_string()
                continue
            if char in "{[":
                depth += 1
            elif char in "}]":
                depth -= 1
            self.pos += 1

    def read_members(self, start: int) -> list[_JsoncMember]:
        self.pos = start + 1
        found: list[_JsoncMember] = []
        while True:
            self.skip_ws()
            if self.pos >= len(self.text) or self.text[self.pos] == "}":
                return found
            if self.text[self.pos] == ",":
                self.pos += 1
                continue
            member_start = self.pos
            raw_key = self.read_string()
            self.skip_ws()
            self.pos += 1
            value_start, value_end = self.read_value()
            found.append(
                _JsoncMember(
                    json.loads(raw_key),
                    member_start,
                    value_end,
                    value_start,
                    value_end,
                )
            )


def _jsonc_object_span(text: str, path: tuple[str, ...]) -> tuple[int, int] | None:
    scanner = _JsoncScanner(text)
    scanner.skip_ws()
    if scanner.pos >= len(text) or text[scanner.pos] != "{":
        return None
    cursor = scanner.pos
    for key in path:
        match = next(
            (
                member
                for member in _JsoncScanner(text).read_members(cursor)
                if member.key == key
            ),
            None,
        )
        if match is None or text[match.value_start] != "{":
            return None
        cursor = match.value_start
    tail = _JsoncScanner(text)
    tail.pos = cursor
    return tail.read_value()


def _jsonc_render(value: t.Any, depth: int) -> str:
    rendered = json.dumps(value, indent=2, ensure_ascii=False)
    return rendered.replace("\n", "\n" + "  " * depth)


def _jsonc_next_edit(
    text: str, data: t.Mapping[str, t.Any], path: tuple[str, ...]
) -> tuple[int, int, str] | None:
    blanked = _jsonc_blank_comments(text)
    span = _jsonc_object_span(blanked, path)
    if span is None:
        return None
    object_start, object_end = span
    members = _JsoncScanner(blanked).read_members(object_start)
    by_key = {member.key: member for member in members}
    depth = len(path) + 1
    pad = "  " * depth

    for key, value in data.items():
        member = by_key.get(key)
        if member is None:
            body = _jsonc_render(value, depth)
            name = json.dumps(key, ensure_ascii=False)
            if members:
                tail = members[-1].end
                return tail, tail, f",\n{pad}{name}: {body}"
            if blanked[object_start + 1 : object_end - 1].strip():
                return None
            interior = text[object_start + 1 : object_end - 1]
            anchor = object_start + 1 + len(interior.rstrip())
            closing = "  " * (depth - 1)
            return anchor, object_end - 1, f"\n{pad}{name}: {body}\n{closing}"
        current = json.loads(
            _jsonc_blank_trailing_commas(blanked[member.value_start : member.value_end])
        )
        if isinstance(value, dict) and isinstance(current, dict):
            nested = _jsonc_next_edit(text, value, (*path, key))
            if nested is not None:
                return nested
        elif current != value:
            return member.value_start, member.value_end, _jsonc_render(value, depth)

    for index, member in enumerate(members):
        if member.key in data:
            continue
        if index:
            return members[index - 1].end, member.end, ""
        trailing = blanked[member.end : object_end]
        drop_to = member.end
        if trailing.lstrip(_JSON_WS).startswith(","):
            drop_to += trailing.index(",") + 1
        return object_start + 1, drop_to, ""
    return None


def _jsonc_merge(text: str, data: t.Mapping[str, t.Any]) -> str:
    """Reconcile data through text splices, preserving untouched JSONC bytes."""
    if not text.strip():
        return json.dumps(dict(data), indent=2, ensure_ascii=False) + "\n"
    for _ in range(10_000):
        edit = _jsonc_next_edit(text, data, ())
        if edit is None:
            return text
        start, end, replacement = edit
        text = text[:start] + replacement + text[end:]
    raise RuntimeError("JSONC merge did not converge")


# ------------------------------------------------------------------ reading and writing


def parse(layer: Layer, raw: bytes) -> t.Any:
    text = raw.decode("utf-8")
    if layer.format == "toml":
        return tomlkit.parse(text)
    if layer.format == "jsonc":
        return _jsonc_loads(text)
    return json.loads(text)


def load(layer: Layer) -> t.Any:
    return parse(layer, layer.path.read_bytes())


def render(layer: Layer, document: t.Any, original: bytes) -> bytes:
    if layer.format == "toml":
        return tomlkit.dumps(document).encode("utf-8")
    if layer.format == "jsonc":
        return _jsonc_merge(original.decode("utf-8"), document).encode("utf-8")
    return (json.dumps(document, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def save(layer: Layer, data: bytes) -> None:
    """Replace config bytes atomically while retaining mode and symlinks."""
    target = layer.path.resolve() if layer.path.is_symlink() else layer.path
    mode = stat.S_IMODE(target.stat().st_mode) if target.exists() else None
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=target.name + ".", dir=str(target.parent)
    )
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            if mode is not None:
                os.fchmod(stream.fileno(), mode)
            stream.write(data)
        temporary.replace(target)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


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


def entry_for(layer: Layer, command: str, arguments: list[str]) -> dict[str, t.Any]:
    if layer.cli == "opencode":
        return {"type": "local", "command": [command, *arguments]}
    return {"command": command, "args": arguments}


class Prepared(t.NamedTuple):
    layer: Layer
    original: bytes
    output: bytes
    entry: dict[str, t.Any]


# ------------------------------------------------------------------ what to point at


def launcher(args: argparse.Namespace) -> tuple[str, list[str]]:
    """The command and arguments an agent should spawn."""
    if args.source == "path":
        if not args.bin:
            raise SystemExit("--source path needs --bin")
        command, prefix = args.bin, []
    elif args.source == "gradle":
        command, prefix = (
            str(REPO / "gradlew"),
            [
                "--quiet",
                "--console=plain",
                ":libtmux-mcp:run",
                "--args",
            ],
        )
    else:
        command, prefix = str(DIST_LAUNCHER), []

    flags: list[str] = []
    if args.socket:
        flags += ["--socket", args.socket]
    if args.socket_name:
        flags += ["--socket-name", args.socket_name]
    if args.tmux:
        flags += ["--tmux", args.tmux]

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
        [
            str(REPO / "gradlew"),
            "--quiet",
            "--console=plain",
            ":libtmux-mcp:installDist",
        ],
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
        caveat = ""
        if layer.cli == "pi" and not PI_ADAPTER_DIR.is_dir():
            caveat = f" -- {PI_ADAPTER_HINT}"
        print(f"{layer.cli:<{CLI_COLUMN}} {state:<8} {layer.path}{swapped}{caveat}")
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
            print(f"{layer.cli:<{CLI_COLUMN}} no '{args.name}' server")
            continue
        command = entry.get("command", "?")
        if layer.cli == "opencode" and isinstance(command, list):
            command, *arguments = command
        else:
            arguments = entry.get("args", [])
        rest = " ".join(str(word) for word in arguments)
        print(f"{layer.cli:<{CLI_COLUMN}} {command} {rest}".rstrip())
    return 0


def cmd_use(args: argparse.Namespace) -> int:
    command, arguments = launcher(args)
    prepared: list[Prepared] = []
    for layer in chosen(args):
        if not layer.exists():
            print(f"{layer.cli:<{CLI_COLUMN}} skipped, no config")
            continue
        try:
            original = layer.path.read_bytes()
            document = parse(layer, original)
            entry = entry_for(layer, command, arguments)
            into = servers(layer, document, create=True)
            into[args.name] = entry
            output = render(layer, document, original)
        except Exception as error:
            raise SystemExit(f"{layer.cli} config is unreadable: {error}") from error
        prepared.append(Prepared(layer, original, output, entry))

    build(args)
    print(
        f"pointing '{args.name}' at: {command} {' '.join(arguments)}".rstrip(),
        file=sys.stderr,
    )
    if not args.dry_run:
        for item in prepared:
            if item.layer.path.read_bytes() != item.original:
                raise SystemExit(
                    f"{item.layer.cli} config changed during preflight; nothing written"
                )
    for item in prepared:
        layer = item.layer
        if args.dry_run:
            print(
                f"{layer.cli:<{CLI_COLUMN}} would set {args.name} = {json.dumps(item.entry)}"
            )
            continue
        # Taken once. Swapping something already swapped must still revert to
        # the config that was there before any of this started.
        if not backup_of(layer).is_file():
            shutil.copy2(layer.path, backup_of(layer))
        save(layer, item.output)
        print(f"{layer.cli:<{CLI_COLUMN}} set {args.name}")
    return 0


def cmd_revert(args: argparse.Namespace) -> int:
    for layer in chosen(args):
        backup = backup_of(layer)
        if not backup.is_file():
            print(f"{layer.cli:<{CLI_COLUMN}} nothing to revert")
            continue
        if args.dry_run:
            print(f"{layer.cli:<{CLI_COLUMN}} would restore {backup}")
            continue
        shutil.move(str(backup), str(layer.path))
        print(f"{layer.cli:<{CLI_COLUMN}} restored")
    return 0


def cmd_doctor(args: argparse.Namespace) -> int:
    ok = True
    if not (REPO / "gradlew").is_file():
        print("no gradlew: is this the repository root?")
        ok = False
    if args.source == "dist" and not DIST_LAUNCHER.is_file():
        print(
            f"no launcher at {DIST_LAUNCHER}; run './gradlew :libtmux-mcp:installDist'"
        )
        ok = False
    for layer in LAYERS:
        if not layer.exists():
            continue
        try:
            load(layer)
        except Exception as error:  # noqa: BLE001 - a broken config is what this reports
            print(f"{layer.cli:<{CLI_COLUMN}} will not parse: {error}")
            ok = False
    if (
        next(layer for layer in LAYERS if layer.cli == "pi").exists()
        and not PI_ADAPTER_DIR.is_dir()
    ):
        print(f"pi{'':<{CLI_COLUMN - 2}} {PI_ADAPTER_HINT}")
        ok = False
    print("ready" if ok else "not ready")
    return 0 if ok else 1


def chosen(args: argparse.Namespace) -> tuple[Layer, ...]:
    if not args.cli:
        return LAYERS
    wanted = {CLI_ALIASES.get(cli, cli) for cli in args.cli}
    return tuple(layer for layer in LAYERS if layer.cli in wanted)


# ------------------------------------------------------------------ argument parsing


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="mcp_swap", description=__doc__.splitlines()[0]
    )
    commands = parser.add_subparsers(dest="command", required=True)

    def shared(sub: argparse.ArgumentParser) -> None:
        sub.add_argument(
            "--name",
            default="tmux",
            help="the MCP server name to write (default: tmux)",
        )
        sub.add_argument(
            "--cli",
            action="append",
            choices=[*(layer.cli for layer in LAYERS), *CLI_ALIASES],
            help="limit the swap; antigravity is an alias for agy",
        )
        sub.add_argument(
            "--dry-run",
            action="store_true",
            help="say what would change, change nothing",
        )

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
    use.set_defaults(run=cmd_use)

    revert = commands.add_parser("revert", help="restore each config from its backup")
    shared(revert)
    revert.set_defaults(run=cmd_revert)

    doctor = commands.add_parser("doctor", help="check this is ready to swap")
    doctor.add_argument("--source", choices=("dist", "gradle", "path"), default="dist")
    doctor.set_defaults(run=cmd_doctor)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    arguments = sys.argv[1:] if argv is None else argv
    if not arguments:
        parser.print_help()
        return 0
    args = parser.parse_args(arguments)
    return int(args.run(args))


if __name__ == "__main__":
    raise SystemExit(main())
