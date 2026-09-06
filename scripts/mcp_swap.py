#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["tomlkit>=0.13"]
# ///
"""Point every installed agent CLI at this build of ``libtmux-mcp``.

Use when you want to try the server you are editing in a real agent rather
than in a test. ``use`` rewrites each CLI's global config; ``revert``
restores from the backup and recovery record the swap wrote. Swapping a
config that is already swapped keeps the first backup, after verifying
the owned recovery state, so ``revert`` lands on the pre-swap config.

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
Deliberately narrow, and transactional:

- **Global configs only.** Project-local ``.mcp.json`` and
  ``.cursor/mcp.json`` are left alone; a swap is a thing you do to your
  own machine, not to a repository.
- **One server name.** Only the entry named by ``--name`` (default
  ``tmux``) is touched. Everything else in the file is preserved,
  including comments in TOML and JSONC.
- **A recovery pair per file, once.** The backup and its private, versioned
  ``.state`` record are written beside the original. ``revert`` proceeds only
  while the config, topology, backup, record, and server route still match.
- **One all-client transaction.** Every selected config, backup, and state
  destination is checked and staged before replacement. A failure rolls back
  in reverse; recovery files remain when exact rollback cannot be proven.
"""

from __future__ import annotations

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
import pathlib
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
STATE_SUFFIX = ".state"
STATE_VERSION = 1
STATE_MAX_BYTES = 16 * 1024


def _xdg_state_home() -> pathlib.Path:
    raw = os.environ.get("XDG_STATE_HOME")
    if raw and pathlib.Path(raw).is_absolute():
        return pathlib.Path(raw)
    return pathlib.Path.home() / ".local" / "state"


SWAP_LOCK_DIR = _xdg_state_home() / "libtmux-mcp-dev" / "swap"
SWAP_LOCK_FILE = SWAP_LOCK_DIR / "state.lock"


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


def state_of(layer: Layer) -> pathlib.Path:
    backup = backup_of(layer)
    return backup.with_name(backup.name + STATE_SUFFIX)


def entry_for(layer: Layer, command: str, arguments: list[str]) -> dict[str, t.Any]:
    if layer.cli == "opencode":
        return {"type": "local", "command": [command, *arguments]}
    return {"command": command, "args": arguments}


class FileState(t.NamedTuple):
    device: int
    inode: int
    mode: int
    size: int
    modified_ns: int
    data: bytes


OwnedFiles = dict[pathlib.Path, FileState]


class DirectoryState(t.NamedTuple):
    logical: pathlib.Path
    physical: pathlib.Path
    symlink: bool
    link_text: str | None
    link_device: int
    link_inode: int
    link_mode: int
    device: int
    inode: int
    mode: int


class LockState(t.NamedTuple):
    logical: pathlib.Path
    physical: pathlib.Path
    parent: DirectoryState | None
    device: int | None
    inode: int | None
    mode: int | None
    links: int | None
    descriptor: int | None


class ConfigState(t.NamedTuple):
    layer: Layer
    parent: DirectoryState
    symlink: bool
    link_text: str | None
    link_device: int
    link_inode: int
    link_mode: int
    target: pathlib.Path
    file: FileState


class BackupState(t.NamedTuple):
    path: pathlib.Path
    parent: DirectoryState
    physical: pathlib.Path
    file: FileState | None


class StateFile(t.NamedTuple):
    path: pathlib.Path
    parent: DirectoryState
    physical: pathlib.Path
    file: FileState | None
    record: dict[str, t.Any] | None


class PreparedUse(t.NamedTuple):
    config: ConfigState
    backup: BackupState
    state: StateFile
    output: bytes
    entry: dict[str, t.Any]
    server_name: str
    command: str
    arguments: tuple[str, ...]


class PreparedRevert(t.NamedTuple):
    config: ConfigState
    backup: BackupState
    state: StateFile


class StagedUse(t.NamedTuple):
    plan: PreparedUse
    output: pathlib.Path
    recovery: pathlib.Path
    backup: pathlib.Path | None
    state: pathlib.Path
    state_recovery: pathlib.Path | None


class StagedRevert(t.NamedTuple):
    plan: PreparedRevert
    restored: pathlib.Path
    recovery: pathlib.Path
    backup_recovery: pathlib.Path
    state_recovery: pathlib.Path


class ConfigWrite(t.NamedTuple):
    config: ConfigState
    committed: FileState
    recovery: pathlib.Path


class ConfigRemoval(t.NamedTuple):
    config: ConfigState
    recovery: pathlib.Path


class BackupWrite(t.NamedTuple):
    backup: BackupState
    committed: FileState
    cli: str


class StateWrite(t.NamedTuple):
    state: StateFile
    backup: BackupState
    committed: FileState | None
    recovery: pathlib.Path | None
    cli: str


class BackupRemoval(t.NamedTuple):
    backup: BackupState
    recovery: pathlib.Path
    cli: str


class StateRemoval(t.NamedTuple):
    state: StateFile
    recovery: pathlib.Path
    cli: str


def _file_state(path: pathlib.Path) -> FileState:
    before = path.stat()
    if not stat.S_ISREG(before.st_mode):
        raise ValueError(f"{path} is not a regular file")
    data = path.read_bytes()
    after = path.stat()
    before_key = (
        before.st_dev,
        before.st_ino,
        before.st_mode,
        before.st_size,
        before.st_mtime_ns,
    )
    after_key = (
        after.st_dev,
        after.st_ino,
        after.st_mode,
        after.st_size,
        after.st_mtime_ns,
    )
    if before_key != after_key:
        raise RuntimeError(f"{path} changed while it was read")
    return FileState(
        after.st_dev,
        after.st_ino,
        stat.S_IMODE(after.st_mode),
        after.st_size,
        after.st_mtime_ns,
        data,
    )


def _regular_file_state(path: pathlib.Path) -> FileState:
    details = path.lstat()
    if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
        raise ValueError(f"{path} is not a regular file")
    file = _file_state(path)
    if (details.st_dev, details.st_ino) != (file.device, file.inode):
        raise RuntimeError(f"{path} changed while it was resolved")
    return file


def _own(owned: OwnedFiles, path: pathlib.Path) -> None:
    owned[path] = _regular_file_state(path)


def _release(owned: OwnedFiles, path: pathlib.Path) -> None:
    owned.pop(path, None)


def _release_missing(owned: OwnedFiles, path: pathlib.Path) -> None:
    if not os.path.lexists(path):
        _release(owned, path)


def _directory_state(path: pathlib.Path) -> DirectoryState:
    logical = path.lstat()
    symlink = stat.S_ISLNK(logical.st_mode)
    if not symlink and not stat.S_ISDIR(logical.st_mode):
        raise ValueError(f"{path} is not a directory or directory symlink")
    physical = path.resolve(strict=True)
    details = physical.stat()
    if not stat.S_ISDIR(details.st_mode):
        raise ValueError(f"{path} is not a directory")
    return DirectoryState(
        path,
        physical,
        symlink,
        os.readlink(path) if symlink else None,
        logical.st_dev,
        logical.st_ino,
        logical.st_mode,
        details.st_dev,
        details.st_ino,
        stat.S_IMODE(details.st_mode),
    )


def _inspect_lock(*, descriptor: int | None = None) -> LockState:
    parent = None
    if os.path.lexists(SWAP_LOCK_DIR):
        parent = _directory_state(SWAP_LOCK_DIR)
        if parent.symlink:
            raise RuntimeError(f"swap lock directory is a symlink: {SWAP_LOCK_DIR}")
        physical = parent.physical / SWAP_LOCK_FILE.name
    else:
        physical = SWAP_LOCK_FILE.resolve(strict=False)
    if not os.path.lexists(SWAP_LOCK_FILE):
        return LockState(
            SWAP_LOCK_FILE, physical, parent, None, None, None, None, descriptor
        )
    before = SWAP_LOCK_FILE.lstat()
    if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode):
        raise RuntimeError(f"swap lock is not a regular file: {SWAP_LOCK_FILE}")
    resolved = SWAP_LOCK_FILE.resolve(strict=True)
    after = SWAP_LOCK_FILE.lstat()
    before_key = (before.st_dev, before.st_ino, before.st_mode, before.st_nlink)
    after_key = (after.st_dev, after.st_ino, after.st_mode, after.st_nlink)
    if before_key != after_key or resolved != physical:
        raise RuntimeError(
            f"swap lock changed while it was inspected: {SWAP_LOCK_FILE}"
        )
    return LockState(
        SWAP_LOCK_FILE,
        physical,
        parent,
        after.st_dev,
        after.st_ino,
        stat.S_IMODE(after.st_mode),
        after.st_nlink,
        descriptor,
    )


def _validate_lock(lock: LockState) -> None:
    if lock.device is None or lock.inode is None:
        if lock.descriptor is not None:
            raise RuntimeError(f"swap lock path disappeared: {lock.logical}")
        return
    if lock.mode != 0o600:
        raise RuntimeError(f"swap lock mode is not 0600: {lock.logical}")
    if lock.links != 1:
        raise RuntimeError(f"swap lock has hard links: {lock.logical}")
    if lock.descriptor is None:
        return
    current = _inspect_lock(descriptor=lock.descriptor)
    expected = lock._replace(descriptor=lock.descriptor)
    if current != expected:
        raise RuntimeError(f"swap lock path changed: {lock.logical}")
    opened = os.fstat(lock.descriptor)
    if (
        not stat.S_ISREG(opened.st_mode)
        or (opened.st_dev, opened.st_ino) != (lock.device, lock.inode)
        or stat.S_IMODE(opened.st_mode) != lock.mode
        or opened.st_nlink != lock.links
    ):
        raise RuntimeError(f"swap lock descriptor changed: {lock.logical}")


@contextlib.contextmanager
def _state_lock() -> t.Iterator[LockState]:
    SWAP_LOCK_DIR.mkdir(parents=True, exist_ok=True)
    directory_fd: int | None = None
    lock_fd: int | None = None
    try:
        if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
            raise RuntimeError(
                "platform cannot open the swap lock without following links"
            )
        directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
        directory_flags |= getattr(os, "O_CLOEXEC", 0)
        directory_fd = os.open(SWAP_LOCK_DIR, directory_flags)
        parent = _directory_state(SWAP_LOCK_DIR)
        opened_parent = os.fstat(directory_fd)
        if parent.symlink or (opened_parent.st_dev, opened_parent.st_ino) != (
            parent.device,
            parent.inode,
        ):
            raise RuntimeError(f"swap lock directory changed: {SWAP_LOCK_DIR}")
        lock_flags = os.O_RDWR | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
        try:
            lock_fd = os.open(
                SWAP_LOCK_FILE.name,
                lock_flags | os.O_CREAT | os.O_EXCL,
                0o600,
                dir_fd=directory_fd,
            )
            os.fchmod(lock_fd, 0o600)
        except FileExistsError:
            lock_fd = os.open(SWAP_LOCK_FILE.name, lock_flags, dir_fd=directory_fd)
        fcntl.flock(lock_fd, fcntl.LOCK_EX)
        lock = _inspect_lock(descriptor=lock_fd)
        _validate_lock(lock)
    except Exception as error:
        if lock_fd is not None:
            os.close(lock_fd)
        if directory_fd is not None:
            os.close(directory_fd)
        raise SystemExit(f"swap lock is unusable: {error}") from error
    try:
        yield lock
        try:
            _validate_lock(lock)
        except Exception as error:
            raise SystemExit(f"swap lock changed before release: {error}") from error
    finally:
        os.close(t.cast(int, lock_fd))
        os.close(t.cast(int, directory_fd))


def _config_state(layer: Layer) -> ConfigState:
    parent = _directory_state(layer.path.parent)
    details = layer.path.lstat()
    symlink = stat.S_ISLNK(details.st_mode)
    if not symlink and not stat.S_ISREG(details.st_mode):
        raise ValueError(f"{layer.path} is not a regular file or symlink")
    link_text = os.readlink(layer.path) if symlink else None
    target = layer.path.resolve(strict=True)
    file = _file_state(target)
    if not symlink and (details.st_dev, details.st_ino) != (
        file.device,
        file.inode,
    ):
        raise RuntimeError(f"{layer.path} changed while it was resolved")
    return ConfigState(
        layer,
        parent,
        symlink,
        link_text,
        details.st_dev,
        details.st_ino,
        details.st_mode,
        target,
        file,
    )


def _backup_state(layer: Layer, *, required: bool = False) -> BackupState:
    path = backup_of(layer)
    parent = _directory_state(path.parent)
    physical = parent.physical / path.name
    if not os.path.lexists(path):
        if required:
            raise FileNotFoundError(path)
        return BackupState(path, parent, physical, None)
    details = path.lstat()
    if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
        raise ValueError(f"{path} is not a regular file")
    if path.resolve(strict=True) != physical:
        raise RuntimeError(f"{path} did not resolve in its preflight directory")
    file = _file_state(physical)
    if (details.st_dev, details.st_ino) != (file.device, file.inode):
        raise RuntimeError(f"{path} changed while it was resolved")
    return BackupState(path, parent, physical, file)


def _file_document(file: FileState) -> dict[str, t.Any]:
    return {
        "device": file.device,
        "inode": file.inode,
        "mode": file.mode,
        "sha256": hashlib.sha256(file.data).hexdigest(),
        "size": file.size,
    }


def _directory_document(directory: DirectoryState) -> dict[str, t.Any]:
    return {
        "device": directory.device,
        "inode": directory.inode,
        "link_device": directory.link_device if directory.symlink else None,
        "link_inode": directory.link_inode if directory.symlink else None,
        "link_mode": directory.link_mode if directory.symlink else None,
        "link_text": directory.link_text,
        "logical": str(directory.logical),
        "mode": directory.mode,
        "physical": str(directory.physical),
        "symlink": directory.symlink,
    }


def _config_document(config: ConfigState, file: FileState) -> dict[str, t.Any]:
    return {
        "file": _file_document(file),
        "link_device": config.link_device if config.symlink else None,
        "link_inode": config.link_inode if config.symlink else None,
        "link_mode": config.link_mode if config.symlink else None,
        "link_text": config.link_text,
        "logical": str(config.layer.path),
        "parent": _directory_document(config.parent),
        "symlink": config.symlink,
        "target": str(config.target),
    }


def _record_bytes(record: dict[str, t.Any]) -> bytes:
    data = (json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n").encode()
    if len(data) > STATE_MAX_BYTES:
        raise ValueError(f"recovery state exceeds {STATE_MAX_BYTES} bytes")
    return data


def _object(value: t.Any, keys: set[str], label: str) -> dict[str, t.Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"{label} has unknown or missing fields")
    return value


def _decode_record(layer: Layer, data: bytes) -> dict[str, t.Any]:
    if len(data) > STATE_MAX_BYTES:
        raise ValueError(f"recovery state exceeds {STATE_MAX_BYTES} bytes")
    try:
        root = json.loads(data)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("recovery state is malformed") from error
    document = _object(
        root, {"backup", "cli", "config", "server", "version"}, "recovery state"
    )
    if type(document["version"]) is not int or document["version"] != STATE_VERSION:
        raise ValueError("recovery state version is unsupported")
    if type(document["cli"]) is not str or document["cli"] != layer.cli:
        raise ValueError("recovery state names another client")
    server = _object(
        document["server"], {"arguments", "command", "name"}, "recovery server"
    )
    arguments = server["arguments"]
    if (
        not isinstance(arguments, list)
        or len(arguments) > 128
        or any(not isinstance(argument, str) for argument in arguments)
        or type(server["command"]) is not str
        or type(server["name"]) is not str
    ):
        raise ValueError("recovery server route is invalid")
    _object(document["backup"], {"file", "parent", "path", "target"}, "recovery backup")
    if not isinstance(document["config"], dict):
        raise TypeError("recovery config is invalid")
    return document


def _same_typed(left: t.Any, right: t.Any) -> bool:
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return set(left) == set(right) and all(
            _same_typed(left[key], right[key]) for key in left
        )
    if isinstance(left, list):
        return len(left) == len(right) and all(
            _same_typed(one, two) for one, two in zip(left, right, strict=True)
        )
    return bool(left == right)


def _state_file(layer: Layer, *, required: bool = False) -> StateFile:
    path = state_of(layer)
    parent = _directory_state(path.parent)
    physical = parent.physical / path.name
    if not os.path.lexists(path):
        if required:
            raise FileNotFoundError(path)
        return StateFile(path, parent, physical, None, None)
    details = path.lstat()
    if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
        raise ValueError(f"{path} is not a regular file")
    if details.st_size > STATE_MAX_BYTES:
        raise ValueError(f"{path} exceeds {STATE_MAX_BYTES} bytes")
    if path.resolve(strict=True) != physical:
        raise RuntimeError(f"{path} did not resolve in its preflight directory")
    file = _file_state(physical)
    if (details.st_dev, details.st_ino) != (file.device, file.inode):
        raise RuntimeError(f"{path} changed while it was resolved")
    if file.mode != 0o600:
        raise ValueError(f"{path} mode is not 0600")
    return StateFile(path, parent, physical, file, _decode_record(layer, file.data))


def _record_for(
    plan: PreparedUse, target_file: FileState, backup_file: FileState
) -> dict[str, t.Any]:
    return {
        "backup": {
            "file": _file_document(backup_file),
            "parent": _directory_document(plan.backup.parent),
            "path": str(plan.backup.path),
            "target": str(plan.backup.physical),
        },
        "cli": plan.config.layer.cli,
        "config": _config_document(plan.config, target_file),
        "server": {
            "arguments": list(plan.arguments),
            "command": plan.command,
            "name": plan.server_name,
        },
        "version": STATE_VERSION,
    }


def _verify_owned_recovery(
    config: ConfigState, backup: BackupState, state: StateFile
) -> None:
    record = state.record
    backup_file = backup.file
    if record is None or backup_file is None:
        raise RuntimeError("recovery backup and state are incomplete")
    if not _same_typed(record["config"], _config_document(config, config.file)):
        raise RuntimeError("config no longer matches the recovery state")
    route = record["server"]
    document = parse(config.layer, config.file.data)
    actual = (servers(config.layer, document) or {}).get(route["name"])
    expected = entry_for(config.layer, route["command"], route["arguments"])
    if actual != expected:
        raise RuntimeError("server route no longer matches the recovery state")
    expected_backup = {
        "file": _file_document(backup_file),
        "parent": _directory_document(backup.parent),
        "path": str(backup.path),
        "target": str(backup.physical),
    }
    if not _same_typed(record["backup"], expected_backup):
        raise RuntimeError("backup no longer matches the recovery state")


def _verify_directory(expected: DirectoryState) -> None:
    current = _directory_state(expected.logical)
    if current != expected:
        raise RuntimeError(f"{expected.logical} changed")


def _verify_config(config: ConfigState, expected: FileState) -> None:
    _verify_directory(config.parent)
    details = config.layer.path.lstat()
    if config.symlink:
        if (
            not stat.S_ISLNK(details.st_mode)
            or os.readlink(config.layer.path) != config.link_text
            or (details.st_dev, details.st_ino, details.st_mode)
            != (config.link_device, config.link_inode, config.link_mode)
        ):
            raise RuntimeError(f"{config.layer.path} symlink changed")
    elif not stat.S_ISREG(details.st_mode):
        raise RuntimeError(f"{config.layer.path} topology changed")
    if config.layer.path.resolve(strict=True) != config.target:
        raise RuntimeError(f"{config.layer.path} target changed")
    current = _file_state(config.target)
    if current != expected:
        raise RuntimeError(f"{config.layer.path} identity, mode, or bytes changed")
    if not config.symlink and (details.st_dev, details.st_ino) != (
        current.device,
        current.inode,
    ):
        raise RuntimeError(f"{config.layer.path} logical identity changed")


def _verify_missing_config(config: ConfigState) -> None:
    _verify_directory(config.parent)
    if config.symlink:
        details = config.layer.path.lstat()
        if (
            not stat.S_ISLNK(details.st_mode)
            or os.readlink(config.layer.path) != config.link_text
            or (details.st_dev, details.st_ino, details.st_mode)
            != (config.link_device, config.link_inode, config.link_mode)
        ):
            raise RuntimeError(f"{config.layer.path} symlink changed")
    elif os.path.lexists(config.layer.path):
        raise RuntimeError(f"{config.layer.path} appeared")
    if os.path.lexists(config.target):
        raise RuntimeError(f"{config.target} appeared")


def _verify_artifact(
    artifact: BackupState | StateFile, expected: FileState | None
) -> None:
    _verify_directory(artifact.parent)
    if expected is None:
        if os.path.lexists(artifact.path):
            raise RuntimeError(f"{artifact.path} appeared")
        return
    if not os.path.lexists(artifact.path) or artifact.path.is_symlink():
        raise RuntimeError(f"{artifact.path} topology changed")
    if artifact.path.resolve(strict=True) != artifact.physical:
        raise RuntimeError(f"{artifact.path} target changed")
    if _file_state(artifact.physical) != expected:
        raise RuntimeError(f"{artifact.path} identity, mode, or bytes changed")


def _verify_restored_artifact(artifact: BackupState | StateFile) -> None:
    expected = t.cast(FileState, artifact.file)
    _verify_artifact(artifact, expected)


def _reject_duplicate_targets(
    plans: t.Iterable[t.Any], lock: LockState | None = None
) -> None:
    config_paths: dict[pathlib.Path, str] = {}
    config_inodes: dict[tuple[int, int], str] = {}
    all_paths: dict[pathlib.Path, str] = {}
    all_inodes: dict[tuple[int, int], str] = {}

    def claim(
        label: str,
        logical: pathlib.Path,
        physical: pathlib.Path,
        inode: tuple[int, int] | None,
    ) -> None:
        owner = next(
            (
                all_paths[path]
                for path in dict.fromkeys((logical, physical))
                if path in all_paths and all_paths[path] != label
            ),
            None,
        )
        if owner is None and inode is not None:
            owner = all_inodes.get(inode)
            if owner == label:
                owner = None
        if owner is not None:
            raise SystemExit(
                f"duplicate transaction destination for {owner} and {label}"
            )
        all_paths[logical] = label
        all_paths[physical] = label
        if inode is not None:
            all_inodes[inode] = label

    if lock is not None:
        lock_inode = (
            None
            if lock.device is None or lock.inode is None
            else (lock.device, lock.inode)
        )
        claim("swap lock", lock.logical, lock.physical, lock_inode)
    for plan in plans:
        config = plan.config
        cli = config.layer.cli
        by_path = config_paths.get(config.target)
        by_inode = config_inodes.get((config.file.device, config.file.inode))
        if by_path is not None or by_inode is not None:
            other = by_path or by_inode
            raise SystemExit(f"duplicate physical config target for {other} and {cli}")
        config_paths[config.target] = cli
        config_inodes[(config.file.device, config.file.inode)] = cli
        claim(
            f"{cli} config",
            config.layer.path,
            config.target,
            (config.file.device, config.file.inode),
        )

        backup = plan.backup
        backup_inode = (
            None if backup.file is None else (backup.file.device, backup.file.inode)
        )
        claim(f"{cli} backup", backup.path, backup.physical, backup_inode)

        state = plan.state
        state_inode = (
            None if state.file is None else (state.file.device, state.file.inode)
        )
        claim(f"{cli} state", state.path, state.physical, state_inode)


def _check_lock_plan(plans: t.Iterable[t.Any]) -> None:
    try:
        lock = _inspect_lock()
        _reject_duplicate_targets(plans, lock)
        _validate_lock(lock)
    except Exception as error:
        raise SystemExit(f"swap lock is unusable: {error}") from error


def _stage(
    directory: pathlib.Path,
    logical_name: str,
    role: str,
    data: bytes,
    mode: int,
) -> pathlib.Path:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{logical_name}.mcp-swap-{role}-", dir=str(directory)
    )
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        return temporary
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def _apply_replace(
    staged: pathlib.Path,
    destination: pathlib.Path,
    *,
    expected: FileState,
    destination_expected: FileState,
    lock: LockState,
) -> tuple[FileState, Exception | None]:
    _validate_lock(lock)
    if _regular_file_state(staged) != expected:
        raise RuntimeError(f"{staged} changed before atomic take-aside")
    if _regular_file_state(destination) != destination_expected:
        raise RuntimeError(f"{destination} changed before atomic take-aside")
    delayed = _apply_unlink(
        destination,
        expected=destination_expected,
        lock=lock,
    )
    if delayed is not None:
        raise delayed
    committed, delayed = _publish_absent(
        staged,
        destination,
        expected=expected,
        lock=lock,
    )
    try:
        removal_error = _apply_unlink(staged, expected=expected, lock=lock)
    except Exception as error:  # noqa: BLE001 - retain the committed recovery
        removal_error = error
    if delayed is None:
        delayed = removal_error
    return committed, delayed


def _publish_absent(
    staged: pathlib.Path,
    destination: pathlib.Path,
    *,
    expected: FileState,
    lock: LockState,
) -> tuple[FileState, Exception | None]:
    _validate_lock(lock)
    if _regular_file_state(staged) != expected:
        raise RuntimeError(f"{staged} changed before atomic publication")
    if os.path.lexists(destination):
        raise RuntimeError(f"{destination} appeared before atomic publication")
    _validate_lock(lock)
    delayed: Exception | None = None
    try:
        os.link(staged, destination, follow_symlinks=False)
    except Exception as error:
        try:
            committed = _regular_file_state(destination)
        except (OSError, RuntimeError, ValueError):
            raise error
        if committed != expected:
            raise RuntimeError(
                f"atomic publication of {destination} was not exact"
            ) from error
        delayed = error
    else:
        committed = _regular_file_state(destination)
    if committed != expected:
        raise RuntimeError(f"atomic publication of {destination} was not exact")
    return committed, delayed


def _remove_exact(path: pathlib.Path, expected: FileState) -> Exception | None:
    quarantine_dir = pathlib.Path(
        tempfile.mkdtemp(prefix=f".{path.name}.mcp-swap-retained-", dir=path.parent)
    )
    quarantine_dir.chmod(0o700)
    quarantine = quarantine_dir / "artifact"
    delayed: Exception | None = None
    try:
        path.rename(quarantine)
    except Exception as error:  # noqa: BLE001 - authenticate a possibly completed move
        try:
            current = _regular_file_state(quarantine)
        except (OSError, RuntimeError, ValueError):
            try:
                quarantine_dir.rmdir()
            except OSError:
                pass
            raise error
        delayed = error
    else:
        current = _regular_file_state(quarantine)
    if current != expected:
        raise RuntimeError(f"{path} changed; retained at {quarantine_dir}")
    if os.path.lexists(path):
        delayed = delayed or RuntimeError(f"{path} appeared during removal")
    try:
        quarantine.unlink()
    except Exception as error:
        if os.path.lexists(quarantine):
            raise RuntimeError(
                f"{path} removal failed; retained at {quarantine_dir}: {error}"
            ) from error
        delayed = delayed or error
    if os.path.lexists(quarantine):
        raise RuntimeError(f"{quarantine} still exists after removal")
    try:
        quarantine_dir.rmdir()
    except Exception as error:
        if quarantine_dir.exists():
            raise
        delayed = delayed or error
    return delayed


def _apply_unlink(
    path: pathlib.Path,
    *,
    expected: FileState,
    lock: LockState,
) -> Exception | None:
    _validate_lock(lock)
    if _regular_file_state(path) != expected:
        raise RuntimeError(f"{path} changed before removal")
    _validate_lock(lock)
    delayed = _remove_exact(path, expected)
    try:
        _validate_lock(lock)
    except Exception as error:  # noqa: BLE001 - preserve post-unlink failure
        if delayed is None:
            delayed = error
    return delayed


def _cleanup_owned(
    owned: OwnedFiles,
    preserve: set[pathlib.Path] | None = None,
    *,
    lock: LockState,
) -> list[str]:
    retained = preserve or set()
    errors: list[str] = []
    for path in sorted(
        (candidate for candidate in owned if candidate not in retained), key=str
    ):
        try:
            if not os.path.lexists(path):
                continue
            _validate_lock(lock)
            delayed = _remove_exact(path, owned[path])
            if delayed is not None:
                raise delayed
            _validate_lock(lock)
        except (OSError, RuntimeError, ValueError) as error:
            errors.append(f"could not remove task-owned stage {path}: {error}")
    return errors


def _require_cleanup(action: str, owned: OwnedFiles, lock: LockState) -> None:
    errors = _cleanup_owned(owned, lock=lock)
    if not errors:
        return
    retained = {path for path in owned if os.path.lexists(path)}
    detail = f"{action} committed but cleanup incomplete: " + "; ".join(errors)
    if retained:
        detail += "; recovery artifacts: " + ", ".join(
            str(path) for path in sorted(retained, key=str)
        )
    raise SystemExit(detail)


def _transaction_failure(
    action: str,
    error: Exception,
    rollback_errors: list[str],
    cleanup_errors: list[str],
    preserved: set[pathlib.Path],
) -> t.NoReturn:
    details = [f"{action} failed: {error}"]
    if rollback_errors:
        details.append("rollback incomplete: " + "; ".join(rollback_errors))
    if cleanup_errors:
        details.append("cleanup incomplete: " + "; ".join(cleanup_errors))
    if preserved:
        details.append(
            "recovery artifacts: "
            + ", ".join(str(path) for path in sorted(preserved, key=str))
        )
    raise SystemExit("; ".join(details)) from error


def _plan_use(
    args: argparse.Namespace, command: str, arguments: list[str]
) -> list[PreparedUse]:
    prepared: list[PreparedUse] = []
    for layer in chosen(args):
        if not os.path.lexists(layer.path):
            print(f"{layer.cli:<{CLI_COLUMN}} skipped, no config")
            continue
        try:
            config = _config_state(layer)
        except Exception as error:
            raise SystemExit(f"{layer.cli} config is unreadable: {error}") from error
        try:
            backup = _backup_state(layer)
        except Exception as error:
            raise SystemExit(f"{layer.cli} backup is unusable: {error}") from error
        try:
            state = _state_file(layer)
            if (backup.file is None) != (state.file is None):
                raise RuntimeError("backup and state must exist together")
            if state.file is not None:
                _verify_owned_recovery(config, backup, state)
        except Exception as error:
            raise SystemExit(
                f"{layer.cli} recovery state is unusable: {error}"
            ) from error
        try:
            document = parse(layer, config.file.data)
            entry = entry_for(layer, command, arguments)
            into = servers(layer, document, create=True)
            into[args.name] = entry
            output = render(layer, document, config.file.data)
        except Exception as error:
            raise SystemExit(f"{layer.cli} config is unreadable: {error}") from error
        plan = PreparedUse(
            config,
            backup,
            state,
            output,
            entry,
            args.name,
            command,
            tuple(arguments),
        )
        largest_identity = (1 << 64) - 1
        preview_target = FileState(
            largest_identity,
            largest_identity,
            config.file.mode,
            len(output),
            0,
            output,
        )
        preview_backup = backup.file or FileState(
            largest_identity,
            largest_identity,
            config.file.mode,
            config.file.size,
            0,
            config.file.data,
        )
        try:
            _record_bytes(_record_for(plan, preview_target, preview_backup))
        except Exception as error:
            raise SystemExit(
                f"{layer.cli} recovery state is unusable: {error}"
            ) from error
        prepared.append(plan)
    _reject_duplicate_targets(prepared)
    return prepared


def _plan_revert(args: argparse.Namespace) -> list[PreparedRevert]:
    prepared: list[PreparedRevert] = []
    for layer in chosen(args):
        backup_exists = os.path.lexists(backup_of(layer))
        state_exists = os.path.lexists(state_of(layer))
        if not backup_exists and not state_exists:
            print(f"{layer.cli:<{CLI_COLUMN}} nothing to revert")
            continue
        if backup_exists != state_exists:
            raise SystemExit(f"{layer.cli} recovery backup and state are incomplete")
        try:
            backup = _backup_state(layer, required=True)
        except Exception as error:
            raise SystemExit(f"{layer.cli} backup is unusable: {error}") from error
        try:
            state = _state_file(layer, required=True)
        except Exception as error:
            raise SystemExit(
                f"{layer.cli} recovery state is unusable: {error}"
            ) from error
        try:
            config = _config_state(layer)
        except Exception as error:
            raise SystemExit(f"{layer.cli} config is unreadable: {error}") from error
        try:
            _verify_owned_recovery(config, backup, state)
            record = t.cast(dict[str, t.Any], state.record)
            server_name = record["server"]["name"]
            if server_name != args.name:
                raise RuntimeError(
                    f"state belongs to server {server_name!r}, not {args.name!r}"
                )
        except Exception as error:
            raise SystemExit(
                f"{layer.cli} recovery ownership changed: {error}"
            ) from error
        prepared.append(PreparedRevert(config, backup, state))
    _reject_duplicate_targets(prepared)
    return prepared


def _changed_config(config: ConfigState, expected: FileState) -> None:
    try:
        _verify_config(config, expected)
    except Exception as error:
        raise RuntimeError(
            f"{config.layer.cli} config changed during preflight"
        ) from error


def _changed_backup(backup: BackupState, expected: FileState | None, cli: str) -> None:
    try:
        _verify_artifact(backup, expected)
    except Exception as error:
        raise RuntimeError(f"{cli} backup changed during preflight") from error


def _changed_state(state: StateFile, expected: FileState | None, cli: str) -> None:
    try:
        _verify_artifact(state, expected)
    except Exception as error:
        raise RuntimeError(f"{cli} recovery state changed during preflight") from error


def _stage_use(
    plans: list[PreparedUse], owned: OwnedFiles, lock: LockState
) -> list[StagedUse]:
    staged: list[StagedUse] = []
    try:
        for plan in plans:
            _validate_lock(lock)
            config = plan.config
            output = _stage(
                config.target.parent,
                config.layer.path.name,
                "output",
                plan.output,
                config.file.mode,
            )
            _own(owned, output)
            recovery = _stage(
                config.target.parent,
                config.layer.path.name,
                "recovery",
                config.file.data,
                config.file.mode,
            )
            _own(owned, recovery)
            backup = None
            if plan.backup.file is None:
                backup = _stage(
                    plan.backup.parent.physical,
                    plan.backup.path.name,
                    "new",
                    config.file.data,
                    config.file.mode,
                )
                _own(owned, backup)
            target_file = _file_state(output)
            backup_file = (
                _file_state(backup)
                if backup is not None
                else t.cast(FileState, plan.backup.file)
            )
            state = _stage(
                plan.state.parent.physical,
                plan.state.path.name,
                "state",
                _record_bytes(_record_for(plan, target_file, backup_file)),
                0o600,
            )
            _own(owned, state)
            state_recovery = None
            if plan.state.file is not None:
                state_recovery = _stage(
                    plan.state.parent.physical,
                    plan.state.path.name,
                    "recovery-state",
                    plan.state.file.data,
                    plan.state.file.mode,
                )
                _own(owned, state_recovery)
            staged.append(
                StagedUse(plan, output, recovery, backup, state, state_recovery)
            )
            _validate_lock(lock)
    except Exception as error:
        cleanup = _cleanup_owned(owned, lock=lock)
        detail = f"swap staging failed: {error}"
        if cleanup:
            detail += "; " + "; ".join(cleanup)
        raise SystemExit(detail) from error
    return staged


def _stage_revert(
    plans: list[PreparedRevert], owned: OwnedFiles, lock: LockState
) -> list[StagedRevert]:
    staged: list[StagedRevert] = []
    try:
        for plan in plans:
            _validate_lock(lock)
            config = plan.config
            backup = t.cast(FileState, plan.backup.file)
            restored = _stage(
                config.target.parent,
                config.layer.path.name,
                "restore",
                backup.data,
                backup.mode,
            )
            _own(owned, restored)
            recovery = _stage(
                config.target.parent,
                config.layer.path.name,
                "recovery",
                config.file.data,
                config.file.mode,
            )
            _own(owned, recovery)
            backup_recovery = _stage(
                plan.backup.parent.physical,
                plan.backup.path.name,
                "recovery",
                backup.data,
                backup.mode,
            )
            _own(owned, backup_recovery)
            state = t.cast(FileState, plan.state.file)
            state_recovery = _stage(
                plan.state.parent.physical,
                plan.state.path.name,
                "recovery-state",
                state.data,
                state.mode,
            )
            _own(owned, state_recovery)
            staged.append(
                StagedRevert(
                    plan,
                    restored,
                    recovery,
                    backup_recovery,
                    state_recovery,
                )
            )
            _validate_lock(lock)
    except Exception as error:
        cleanup = _cleanup_owned(owned, lock=lock)
        detail = f"revert staging failed: {error}"
        if cleanup:
            detail += "; " + "; ".join(cleanup)
        raise SystemExit(detail) from error
    return staged


def _restore_removed_config(
    operation: ConfigWrite | ConfigRemoval,
    owned: OwnedFiles,
    lock: LockState,
) -> None:
    if isinstance(operation, ConfigWrite):
        _verify_config(operation.config, operation.committed)
        delayed = _apply_unlink(
            operation.config.target,
            expected=operation.committed,
            lock=lock,
        )
        if delayed is not None:
            raise delayed
    else:
        _verify_missing_config(operation.config)
    _, delayed = _publish_absent(
        operation.recovery,
        operation.config.target,
        expected=owned[operation.recovery],
        lock=lock,
    )
    _release_missing(owned, operation.recovery)
    if delayed is not None:
        raise delayed
    _verify_config(operation.config, operation.config.file)


def _rollback_use(
    operations: list[ConfigWrite | ConfigRemoval | BackupWrite | StateWrite],
    owned: OwnedFiles,
    lock: LockState,
) -> tuple[list[str], set[pathlib.Path]]:
    errors: list[str] = []
    preserved: set[pathlib.Path] = set()
    blocked: set[str] = set()
    for operation in reversed(operations):
        if isinstance(operation, (ConfigWrite, ConfigRemoval)):
            cli = operation.config.layer.cli
            try:
                _restore_removed_config(operation, owned, lock)
            except Exception as error:  # noqa: BLE001 - continue reverse rollback
                blocked.add(cli)
                if operation.recovery.exists():
                    preserved.add(operation.recovery)
                errors.append(f"{cli} config: {error}")
            continue

        if isinstance(operation, StateWrite):
            cli = operation.cli
            if cli in blocked:
                preserved.update((operation.state.path, operation.backup.path))
                if operation.recovery is not None:
                    preserved.add(operation.recovery)
                continue
            try:
                _verify_artifact(operation.state, operation.committed)
                if operation.state.file is None:
                    delayed = _apply_unlink(
                        operation.state.physical,
                        expected=operation.committed,
                        lock=lock,
                    )
                    if delayed is not None:
                        raise delayed
                else:
                    recovery = t.cast(pathlib.Path, operation.recovery)
                    if operation.committed is not None:
                        delayed = _apply_unlink(
                            operation.state.physical,
                            expected=operation.committed,
                            lock=lock,
                        )
                        if delayed is not None:
                            raise delayed
                    _, delayed = _publish_absent(
                        recovery,
                        operation.state.physical,
                        expected=owned[recovery],
                        lock=lock,
                    )
                    _release_missing(owned, recovery)
                    if delayed is not None:
                        raise delayed
                    _verify_restored_artifact(operation.state)
            except Exception as error:  # noqa: BLE001 - continue reverse rollback
                blocked.add(cli)
                preserved.update((operation.state.path, operation.backup.path))
                if operation.recovery is not None and operation.recovery.exists():
                    preserved.add(operation.recovery)
                errors.append(f"{cli} recovery state: {error}")
            continue

        cli = operation.cli
        if cli in blocked:
            preserved.add(operation.backup.path)
            continue
        try:
            _verify_artifact(operation.backup, operation.committed)
            delayed = _apply_unlink(
                operation.backup.physical,
                expected=operation.committed,
                lock=lock,
            )
            if delayed is not None:
                raise delayed
        except Exception as error:  # noqa: BLE001 - continue reverse rollback
            preserved.add(operation.backup.path)
            errors.append(f"{cli} backup: {error}")
    return errors, preserved


def _commit_use(staged: list[StagedUse], owned: OwnedFiles, lock: LockState) -> None:
    operations: list[ConfigWrite | ConfigRemoval | BackupWrite | StateWrite] = []
    committed_backups: dict[str, FileState] = {}
    committed_states: dict[str, FileState] = {}
    try:
        for item in staged:
            _changed_config(item.plan.config, item.plan.config.file)
            _changed_backup(
                item.plan.backup,
                item.plan.backup.file,
                item.plan.config.layer.cli,
            )
            _changed_state(
                item.plan.state,
                item.plan.state.file,
                item.plan.config.layer.cli,
            )

        for item in staged:
            if item.backup is None:
                continue
            plan = item.plan
            cli = plan.config.layer.cli
            _changed_config(plan.config, plan.config.file)
            _changed_backup(plan.backup, None, cli)
            committed, delayed = _publish_absent(
                item.backup,
                plan.backup.physical,
                expected=owned[item.backup],
                lock=lock,
            )
            _release_missing(owned, item.backup)
            operations.append(BackupWrite(plan.backup, committed, cli))
            committed_backups[cli] = committed
            if delayed is not None:
                raise delayed
            _verify_artifact(plan.backup, committed)

        for item in staged:
            plan = item.plan
            cli = plan.config.layer.cli
            _changed_config(plan.config, plan.config.file)
            expected_backup = committed_backups.get(cli, plan.backup.file)
            _changed_backup(plan.backup, expected_backup, cli)
            _changed_state(plan.state, plan.state.file, cli)
            if plan.state.file is not None:
                recovery = t.cast(pathlib.Path, item.state_recovery)
                removed, delayed = _apply_replace(
                    plan.state.physical,
                    recovery,
                    expected=plan.state.file,
                    destination_expected=owned[recovery],
                    lock=lock,
                )
                if removed == plan.state.file:
                    owned[recovery] = removed
                operations.append(
                    StateWrite(plan.state, plan.backup, None, recovery, cli)
                )
                if removed != plan.state.file:
                    raise RuntimeError(f"{cli} recovery state identity changed")
                if delayed is not None:
                    raise delayed
                _verify_artifact(plan.state, None)
            committed, delayed = _publish_absent(
                item.state,
                plan.state.physical,
                expected=owned[item.state],
                lock=lock,
            )
            _release_missing(owned, item.state)
            operation = StateWrite(
                plan.state,
                plan.backup,
                committed,
                item.state_recovery,
                cli,
            )
            if plan.state.file is None:
                operations.append(operation)
            else:
                operations[-1] = operation
            committed_states[cli] = committed
            if delayed is not None:
                raise delayed
            _verify_artifact(plan.state, committed)

        for item in staged:
            plan = item.plan
            cli = plan.config.layer.cli
            _changed_config(plan.config, plan.config.file)
            expected_backup = committed_backups.get(cli, plan.backup.file)
            _changed_backup(plan.backup, expected_backup, cli)
            _changed_state(plan.state, committed_states[cli], cli)
            removed, delayed = _apply_replace(
                plan.config.target,
                item.recovery,
                expected=plan.config.file,
                destination_expected=owned[item.recovery],
                lock=lock,
            )
            if removed == plan.config.file:
                owned[item.recovery] = removed
            operations.append(ConfigRemoval(plan.config, item.recovery))
            if removed != plan.config.file:
                raise RuntimeError(f"{cli} config recovery identity changed")
            if delayed is not None:
                raise delayed
            committed, delayed = _publish_absent(
                item.output,
                plan.config.target,
                expected=owned[item.output],
                lock=lock,
            )
            _release_missing(owned, item.output)
            operations[-1] = ConfigWrite(plan.config, committed, item.recovery)
            _verify_config(plan.config, committed)
            expected_record = _record_for(
                plan, committed, t.cast(FileState, expected_backup)
            )
            if (
                _decode_record(plan.config.layer, committed_states[cli].data)
                != expected_record
            ):
                raise RuntimeError(f"{cli} recovery state does not own the new config")
            if delayed is not None:
                raise delayed
    except Exception as error:  # noqa: BLE001 - every commit failure rolls back
        rollback_errors, preserved = _rollback_use(operations, owned, lock)
        cleanup_errors = _cleanup_owned(owned, preserved, lock=lock)
        _transaction_failure("swap", error, rollback_errors, cleanup_errors, preserved)

    _require_cleanup("swap", owned, lock)


def _rollback_revert(
    operations: list[ConfigWrite | ConfigRemoval | BackupRemoval | StateRemoval],
    owned: OwnedFiles,
    lock: LockState,
) -> tuple[list[str], set[pathlib.Path]]:
    errors: list[str] = []
    preserved: set[pathlib.Path] = set()
    for operation in reversed(operations):
        if isinstance(operation, StateRemoval):
            cli = operation.cli
            try:
                _verify_directory(operation.state.parent)
                if os.path.lexists(operation.state.path):
                    raise RuntimeError(
                        f"{operation.state.path} appeared before rollback"
                    )
                _, delayed = _publish_absent(
                    operation.recovery,
                    operation.state.physical,
                    expected=owned[operation.recovery],
                    lock=lock,
                )
                _release_missing(owned, operation.recovery)
                if delayed is not None:
                    raise delayed
                _verify_restored_artifact(operation.state)
            except Exception as error:  # noqa: BLE001 - continue reverse rollback
                if operation.recovery.exists():
                    preserved.add(operation.recovery)
                preserved.add(operation.state.path)
                errors.append(f"{cli} recovery state: {error}")
            continue

        if isinstance(operation, BackupRemoval):
            cli = operation.cli
            try:
                _verify_directory(operation.backup.parent)
                if os.path.lexists(operation.backup.path):
                    raise RuntimeError(
                        f"{operation.backup.path} appeared before rollback"
                    )
                _, delayed = _publish_absent(
                    operation.recovery,
                    operation.backup.physical,
                    expected=owned[operation.recovery],
                    lock=lock,
                )
                _release_missing(owned, operation.recovery)
                if delayed is not None:
                    raise delayed
                _verify_restored_artifact(operation.backup)
            except Exception as error:  # noqa: BLE001 - continue reverse rollback
                if operation.recovery.exists():
                    preserved.add(operation.recovery)
                errors.append(f"{cli} backup: {error}")
            continue

        cli = operation.config.layer.cli
        try:
            _restore_removed_config(operation, owned, lock)
        except Exception as error:  # noqa: BLE001 - continue reverse rollback
            if operation.recovery.exists():
                preserved.add(operation.recovery)
            errors.append(f"{cli} config: {error}")
    return errors, preserved


def _commit_revert(
    staged: list[StagedRevert], owned: OwnedFiles, lock: LockState
) -> None:
    operations: list[ConfigWrite | ConfigRemoval | BackupRemoval | StateRemoval] = []
    committed_configs: dict[str, FileState] = {}
    try:
        for item in staged:
            plan = item.plan
            _changed_config(plan.config, plan.config.file)
            _changed_backup(plan.backup, plan.backup.file, plan.config.layer.cli)
            _changed_state(plan.state, plan.state.file, plan.config.layer.cli)

        for item in staged:
            plan = item.plan
            cli = plan.config.layer.cli
            _changed_config(plan.config, plan.config.file)
            _changed_backup(plan.backup, plan.backup.file, cli)
            _changed_state(plan.state, plan.state.file, cli)
            removed, delayed = _apply_replace(
                plan.config.target,
                item.recovery,
                expected=plan.config.file,
                destination_expected=owned[item.recovery],
                lock=lock,
            )
            if removed == plan.config.file:
                owned[item.recovery] = removed
            operations.append(ConfigRemoval(plan.config, item.recovery))
            if removed != plan.config.file:
                raise RuntimeError(f"{cli} config recovery identity changed")
            if delayed is not None:
                raise delayed
            committed, delayed = _publish_absent(
                item.restored,
                plan.config.target,
                expected=owned[item.restored],
                lock=lock,
            )
            _release_missing(owned, item.restored)
            operations[-1] = ConfigWrite(plan.config, committed, item.recovery)
            committed_configs[cli] = committed
            if delayed is not None:
                raise delayed
            _verify_config(plan.config, committed)

        for item in staged:
            plan = item.plan
            cli = plan.config.layer.cli
            _verify_config(plan.config, committed_configs[cli])
            _changed_backup(plan.backup, plan.backup.file, cli)
            _changed_state(plan.state, plan.state.file, cli)
            removed, delayed = _apply_replace(
                plan.backup.physical,
                item.backup_recovery,
                expected=plan.backup.file,
                destination_expected=owned[item.backup_recovery],
                lock=lock,
            )
            if removed == plan.backup.file:
                owned[item.backup_recovery] = removed
            operations.append(BackupRemoval(plan.backup, item.backup_recovery, cli))
            if removed != plan.backup.file:
                raise RuntimeError(f"{cli} backup recovery identity changed")
            if delayed is not None:
                raise delayed
            _verify_artifact(plan.backup, None)

        for item in staged:
            plan = item.plan
            cli = plan.config.layer.cli
            _verify_config(plan.config, committed_configs[cli])
            _verify_directory(plan.backup.parent)
            if os.path.lexists(plan.backup.path):
                raise RuntimeError(f"{cli} backup still exists after removal")
            _changed_state(plan.state, plan.state.file, cli)
            removed, delayed = _apply_replace(
                plan.state.physical,
                item.state_recovery,
                expected=plan.state.file,
                destination_expected=owned[item.state_recovery],
                lock=lock,
            )
            if removed == plan.state.file:
                owned[item.state_recovery] = removed
            operations.append(StateRemoval(plan.state, item.state_recovery, cli))
            if removed != plan.state.file:
                raise RuntimeError(f"{cli} recovery state identity changed")
            if delayed is not None:
                raise delayed
            _verify_artifact(plan.state, None)
    except Exception as error:  # noqa: BLE001 - every commit failure rolls back
        rollback_errors, preserved = _rollback_revert(operations, owned, lock)
        cleanup_errors = _cleanup_owned(owned, preserved, lock=lock)
        _transaction_failure(
            "revert", error, rollback_errors, cleanup_errors, preserved
        )

    _require_cleanup("revert", owned, lock)


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
    prepared = _plan_use(args, command, arguments)
    _check_lock_plan(prepared)
    print(
        f"pointing '{args.name}' at: {command} {' '.join(arguments)}".rstrip(),
        file=sys.stderr,
    )
    if args.dry_run:
        for item in prepared:
            layer = item.config.layer
            print(
                f"{layer.cli:<{CLI_COLUMN}} would set {args.name} = {json.dumps(item.entry)}"
            )
        return 0

    build(args)
    with _state_lock() as lock:
        _reject_duplicate_targets(prepared, lock)
        _validate_lock(lock)
        owned: OwnedFiles = {}
        staged = _stage_use(prepared, owned, lock)
        _commit_use(staged, owned, lock)
    for item in prepared:
        layer = item.config.layer
        print(f"{layer.cli:<{CLI_COLUMN}} set {args.name}")
    return 0


def cmd_revert(args: argparse.Namespace) -> int:
    prepared = _plan_revert(args)
    _check_lock_plan(prepared)
    if args.dry_run:
        for item in prepared:
            layer = item.config.layer
            print(f"{layer.cli:<{CLI_COLUMN}} would restore {item.backup.path}")
        return 0

    with _state_lock() as lock:
        _reject_duplicate_targets(prepared, lock)
        _validate_lock(lock)
        owned: OwnedFiles = {}
        staged = _stage_revert(prepared, owned, lock)
        _commit_revert(staged, owned, lock)
    for item in prepared:
        layer = item.config.layer
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
