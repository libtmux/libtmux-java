from __future__ import annotations

import importlib.util
import json
import os
import pathlib
import re
import stat
import sys
import types

import pytest
import tomllib

SCRIPT = pathlib.Path(__file__).with_name("mcp_swap.py")
CANONICAL_CLIS = (
    "claude",
    "codex",
    "cursor",
    "gemini",
    "grok",
    "agy",
    "opencode",
    "pi",
)
LAUNCHER = "/opt/libtmux-java/bin/libtmux-mcp"


@pytest.fixture
def swapper(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> types.ModuleType:
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(home / ".config"))
    name = f"mcp_swap_test_{tmp_path.name}"
    spec = importlib.util.spec_from_file_location(name, SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _jsonc_loads(text: str) -> object:
    without_comments = re.sub(r"(?m)^[ \t]*//[^\n]*(?:\n|$)", "", text)
    without_comments = re.sub(r"/\*.*?\*/", "", without_comments, flags=re.DOTALL)
    without_trailing_commas = re.sub(r",(?=\s*[}\]])", "", without_comments)
    return json.loads(without_trailing_commas)


def _seed_configs(swapper: types.ModuleType) -> dict[str, bytes]:
    originals: dict[str, bytes] = {}
    for layer in swapper.LAYERS:
        layer.path.parent.mkdir(parents=True, exist_ok=True)
        if layer.format == "toml":
            raw = b'title = "keep"\n'
        elif layer.cli == "opencode":
            raw = (
                b"{\n"
                b"  // root comment stays\n"
                b'  "$schema": "https://opencode.ai/config.json",\n'
                b'  "unrelated": {"keep": true},\n'
                b"}\n"
            )
        elif layer.cli == "pi":
            raw = (
                b"{\n"
                b"  // pi-mcp-adapter accepts JSONC\n"
                b'  "unrelated": {"keep": true},\n'
                b'  "mcpServers": {},\n'
                b"}\n"
            )
        else:
            raw = b'{\n  "unrelated": {"keep": true}\n}\n'
        layer.path.write_bytes(raw)
        layer.path.chmod(0o640)
        originals[layer.cli] = raw
    return originals


def _document(layer: object) -> dict[str, object]:
    text = layer.path.read_text(encoding="utf-8")
    if layer.format == "toml":
        return tomllib.loads(text)
    if layer.format == "jsonc":
        parsed = _jsonc_loads(text)
        assert isinstance(parsed, dict)
        return parsed
    parsed = json.loads(text)
    assert isinstance(parsed, dict)
    return parsed


def _assert_swapped(layer: object) -> None:
    document = _document(layer)
    entry = document[layer.at[0]]["tmux"]
    if layer.cli == "opencode":
        assert entry == {
            "type": "local",
            "command": [LAUNCHER, "--socket", "/tmp/libtmux-java-dev/test/s"],
        }
    else:
        assert entry == {
            "command": LAUNCHER,
            "args": ["--socket", "/tmp/libtmux-java-dev/test/s"],
        }


def _use_args(*extra: str) -> list[str]:
    return [
        "use",
        "--source",
        "path",
        "--bin",
        LAUNCHER,
        "--socket",
        "/tmp/libtmux-java-dev/test/s",
        *extra,
    ]


@pytest.mark.parametrize("cli", CANONICAL_CLIS)
def test_each_client_swaps_and_restores_in_isolation(
    swapper: types.ModuleType, cli: str
) -> None:
    """Selecting one client must not touch any other client's config."""
    originals = _seed_configs(swapper)

    assert swapper.main(_use_args("--cli", cli)) == 0
    selected = next(layer for layer in swapper.LAYERS if layer.cli == cli)
    _assert_swapped(selected)
    assert stat.S_IMODE(selected.path.stat().st_mode) == 0o640
    assert swapper.backup_of(selected).read_bytes() == originals[cli]
    assert _state_of(swapper, selected).is_file()
    for layer in swapper.LAYERS:
        if layer.cli != cli:
            assert layer.path.read_bytes() == originals[layer.cli]
            assert not swapper.backup_of(layer).exists()

    assert swapper.main(["revert", "--cli", cli]) == 0
    assert selected.path.read_bytes() == originals[cli]
    assert stat.S_IMODE(selected.path.stat().st_mode) == 0o640
    assert not swapper.backup_of(selected).exists()
    assert not _state_of(swapper, selected).exists()


def test_all_eight_clients_commit_only_after_full_preflight(
    swapper: types.ModuleType,
) -> None:
    """The default selection swaps and byte-restores all eight clients."""
    originals = _seed_configs(swapper)

    assert tuple(layer.cli for layer in swapper.LAYERS) == CANONICAL_CLIS
    assert swapper.main(_use_args()) == 0
    for layer in swapper.LAYERS:
        _assert_swapped(layer)
        assert stat.S_IMODE(layer.path.stat().st_mode) == 0o640
        assert swapper.backup_of(layer).read_bytes() == originals[layer.cli]
        assert _state_of(swapper, layer).is_file()

    assert swapper.main(["revert"]) == 0
    for layer in swapper.LAYERS:
        assert layer.path.read_bytes() == originals[layer.cli]
        assert stat.S_IMODE(layer.path.stat().st_mode) == 0o640
        assert not swapper.backup_of(layer).exists()
        assert not _state_of(swapper, layer).exists()


def test_failed_late_config_preflight_writes_nothing(
    swapper: types.ModuleType,
) -> None:
    """A malformed final config must not leave earlier clients half-swapped."""
    originals = _seed_configs(swapper)
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    originals["pi"] = b"{ malformed\n"
    pi.path.write_bytes(originals["pi"])

    with pytest.raises(SystemExit, match="pi.*unreadable"):
        swapper.main(_use_args())

    for layer in swapper.LAYERS:
        assert layer.path.read_bytes() == originals[layer.cli]
        assert not swapper.backup_of(layer).exists()


def test_config_changed_after_render_preflight_writes_nothing(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A concurrent late-config edit must stop before earlier writes begin."""
    originals = _seed_configs(swapper)
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    external = b'{"external": true}\n'

    def change_late_config(_args: object) -> None:
        pi.path.write_bytes(external)

    monkeypatch.setattr(swapper, "build", change_late_config)
    with pytest.raises(SystemExit, match="pi.*changed during preflight"):
        swapper.main(_use_args())

    for layer in swapper.LAYERS:
        expected = external if layer.cli == "pi" else originals[layer.cli]
        assert layer.path.read_bytes() == expected
        assert not swapper.backup_of(layer).exists()


def test_late_backup_destination_failure_writes_nothing(
    swapper: types.ModuleType,
) -> None:
    """Every backup destination must be feasible before the first write."""
    originals = _seed_configs(swapper)
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    blocked = swapper.backup_of(pi)
    blocked.mkdir()

    with pytest.raises(SystemExit, match="pi.*backup"):
        swapper.main(_use_args())

    for layer in swapper.LAYERS:
        assert layer.path.read_bytes() == originals[layer.cli]
        if layer.cli != pi.cli:
            assert not swapper.backup_of(layer).exists()
    assert list(blocked.iterdir()) == []


def test_use_commit_failure_rolls_back_every_client(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A later config failure must reverse all earlier config and backup writes."""
    originals = _seed_configs(swapper)
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    destinations = _fail_first_replace_to(monkeypatch, pi.path)

    with pytest.raises((OSError, SystemExit), match="synthetic replace failure"):
        swapper.main(_use_args())

    _assert_original_state(swapper, originals)
    configs = [layer.path for layer in swapper.LAYERS]
    assert [path for path in destinations if path in configs] == [
        *configs,
        *reversed(configs),
    ]
    _assert_no_stages(swapper)


def test_state_publication_failure_rolls_back_every_client(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A later sidecar failure reverses earlier sidecars and backups."""
    originals = _seed_configs(swapper)
    states = [_state_of(swapper, layer) for layer in swapper.LAYERS]
    destinations = _fail_first_replace_to(monkeypatch, states[-1])
    real_unlink = os.unlink
    removed: list[pathlib.Path] = []

    def track_state_removal(path: object, *args: object, **kwargs: object) -> None:
        target = pathlib.Path(path)
        if target in states:
            removed.append(target)
        real_unlink(path, *args, **kwargs)

    monkeypatch.setattr(os, "unlink", track_state_removal)

    with pytest.raises(SystemExit, match="synthetic replace failure"):
        swapper.main(_use_args())

    _assert_original_state(swapper, originals)
    assert [path for path in destinations if path in states] == states
    assert removed == list(reversed(states[:-1]))
    _assert_no_stages(swapper)


@pytest.mark.parametrize("failure", ["state", "config"])
def test_failed_repeat_use_restores_existing_recovery_identity(
    swapper: types.ModuleType,
    monkeypatch: pytest.MonkeyPatch,
    failure: str,
) -> None:
    """Repeat-use rollback restores the owned recovery pair itself."""
    _seed_configs(swapper)
    assert swapper.main(_use_args()) == 0
    before = {layer.cli: _layer_state(swapper, layer) for layer in swapper.LAYERS}
    state_inodes = {
        layer.cli: (
            _state_of(swapper, layer).stat().st_dev,
            _state_of(swapper, layer).stat().st_ino,
        )
        for layer in swapper.LAYERS
    }
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    destination = _state_of(swapper, pi) if failure == "state" else pi.path
    _fail_first_replace_to(monkeypatch, destination)

    with pytest.raises(
        (OSError, SystemExit), match="synthetic replace failure"
    ) as stopped:
        swapper.main(_use_args("--bin", "/opt/another/libtmux-mcp"))

    assert "rollback incomplete" not in str(stopped.value)
    assert {
        layer.cli: _layer_state(swapper, layer) for layer in swapper.LAYERS
    } == before
    assert {
        layer.cli: (
            _state_of(swapper, layer).stat().st_dev,
            _state_of(swapper, layer).stat().st_ino,
        )
        for layer in swapper.LAYERS
    } == state_inodes
    _assert_no_stages(swapper)


def test_blocked_repeat_use_retains_prior_state_recovery(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A human replacement must not discard the prior recovery state."""
    _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    assert swapper.main(_use_args("--cli", layer.cli)) == 0
    state = _state_of(swapper, layer)
    prior_config = layer.path.read_bytes()
    prior_state = state.read_bytes()
    prior_identity = (state.stat().st_dev, state.stat().st_ino)
    original_backup = swapper.backup_of(layer).read_bytes()
    human = b'{"human": true}\n'
    human_identity: tuple[int, int] | None = None
    real_replace = os.replace

    def replace_then_human_edit(src: object, dst: object) -> None:
        nonlocal human_identity
        source = pathlib.Path(src)
        destination = pathlib.Path(dst)
        real_replace(source, destination)
        if destination == layer.path and "mcp-swap-output" in source.name:
            replacement = destination.with_name(f".{destination.name}.human")
            replacement.write_bytes(human)
            replacement.chmod(0o640)
            real_replace(replacement, destination)
            human_identity = (destination.stat().st_dev, destination.stat().st_ino)
            raise OSError("synthetic post-commit failure")

    monkeypatch.setattr(os, "replace", replace_then_human_edit)
    with pytest.raises(SystemExit, match="rollback incomplete"):
        swapper.main(
            _use_args(
                "--cli",
                layer.cli,
                "--bin",
                "/opt/another/libtmux-mcp",
            )
        )

    assert layer.path.read_bytes() == human
    assert (layer.path.stat().st_dev, layer.path.stat().st_ino) == human_identity
    assert swapper.backup_of(layer).read_bytes() == original_backup
    config_recoveries = list(
        layer.path.parent.glob(f".{layer.path.name}.mcp-swap-recovery-*")
    )
    assert len(config_recoveries) == 1
    assert config_recoveries[0].read_bytes() == prior_config
    recoveries = list(state.parent.glob(f".{state.name}.mcp-swap-recovery-state-*"))
    assert len(recoveries) == 1
    assert recoveries[0].read_bytes() == prior_state
    assert (recoveries[0].stat().st_dev, recoveries[0].stat().st_ino) == (
        prior_identity
    )


def test_repeat_use_refuses_an_unowned_config_edit(
    swapper: types.ModuleType,
) -> None:
    """A retained backup never authorizes overwriting a human edit."""
    _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    assert swapper.main(_use_args("--cli", layer.cli)) == 0
    human = layer.path.read_bytes() + b"\n"
    layer.path.write_bytes(human)

    with pytest.raises(SystemExit, match="claude"):
        swapper.main(_use_args("--cli", layer.cli, "--bin", "/opt/next/mcp"))

    assert layer.path.read_bytes() == human
    assert swapper.backup_of(layer).is_file()
    assert _state_of(swapper, layer).is_file()


def test_repeat_use_updates_owned_state_but_keeps_the_first_backup(
    swapper: types.ModuleType,
) -> None:
    """An owned repeat swap advances its record without moving its baseline."""
    originals = _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    assert swapper.main(_use_args("--cli", layer.cli)) == 0
    backup = swapper.backup_of(layer)
    first_state = _state_of(swapper, layer).read_bytes()

    assert swapper.main(_use_args("--cli", layer.cli, "--bin", "/opt/next/mcp")) == 0

    assert backup.read_bytes() == originals[layer.cli]
    assert _state_of(swapper, layer).read_bytes() != first_state
    assert _document(layer)["mcpServers"]["tmux"]["command"] == "/opt/next/mcp"
    assert swapper.main(["revert", "--cli", layer.cli]) == 0
    assert layer.path.read_bytes() == originals[layer.cli]


def test_revert_commit_failure_restores_the_swapped_transaction(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A later restore failure must put earlier clients and backups back."""
    _seed_configs(swapper)
    assert swapper.main(_use_args()) == 0
    before = {layer.cli: _layer_state(swapper, layer) for layer in swapper.LAYERS}
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    destinations = _fail_first_replace_to(monkeypatch, pi.path)

    with pytest.raises((OSError, SystemExit), match="synthetic replace failure"):
        swapper.main(["revert"])

    assert {
        layer.cli: _layer_state(swapper, layer) for layer in swapper.LAYERS
    } == before
    configs = [layer.path for layer in swapper.LAYERS]
    assert [path for path in destinations if path in configs] == [
        *configs,
        *reversed(configs),
    ]
    _assert_no_stages(swapper)


def test_state_removal_failure_restores_the_swapped_transaction(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Sidecar cleanup participates in reverse all-client rollback."""
    _seed_configs(swapper)
    assert swapper.main(_use_args()) == 0
    before = {layer.cli: _owned_layer_state(swapper, layer) for layer in swapper.LAYERS}
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    blocked = _state_of(swapper, pi)
    real_replace = swapper._apply_replace

    def fail_state_removal(
        source: pathlib.Path, destination: pathlib.Path
    ) -> tuple[object, object]:
        if pathlib.Path(source) == blocked:
            raise OSError("synthetic state removal failure")
        return real_replace(source, destination)

    monkeypatch.setattr(swapper, "_apply_replace", fail_state_removal)
    with pytest.raises(SystemExit, match="synthetic state removal failure"):
        swapper.main(["revert"])

    assert {
        layer.cli: _owned_layer_state(swapper, layer) for layer in swapper.LAYERS
    } == before
    assert swapper.main(["revert", "--dry-run"]) == 0
    _assert_no_stages(swapper)


def test_revert_refuses_a_human_edit_and_retains_recovery(
    swapper: types.ModuleType,
) -> None:
    """Revert owns only the exact config state written by use."""
    originals = _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    assert swapper.main(_use_args("--cli", layer.cli)) == 0
    human = layer.path.read_bytes() + b"\n"
    layer.path.write_bytes(human)

    with pytest.raises(SystemExit, match="claude"):
        swapper.main(["revert", "--cli", layer.cli])

    assert layer.path.read_bytes() == human
    assert swapper.backup_of(layer).read_bytes() == originals[layer.cli]
    assert _state_of(swapper, layer).is_file()


def test_revert_refuses_a_same_path_inode_replacement(
    swapper: types.ModuleType,
) -> None:
    """Identical bytes at a new physical config identity are not owned."""
    _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    assert swapper.main(_use_args("--cli", layer.cli)) == 0
    swapped = layer.path.read_bytes()
    prior_inode = layer.path.stat().st_ino
    replacement = layer.path.with_name("replacement.json")
    replacement.write_bytes(swapped)
    replacement.chmod(0o640)
    os.replace(replacement, layer.path)
    assert layer.path.stat().st_ino != prior_inode

    with pytest.raises(SystemExit, match="claude"):
        swapper.main(["revert", "--cli", layer.cli])

    assert layer.path.read_bytes() == swapped
    assert swapper.backup_of(layer).is_file()
    assert _state_of(swapper, layer).is_file()


def test_symlink_config_survives_use_and_revert(
    swapper: types.ModuleType, tmp_path: pathlib.Path
) -> None:
    """Both directions write through a config symlink without replacing it."""
    originals = _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    target = tmp_path / "real" / "claude.json"
    target.parent.mkdir()
    target.write_bytes(originals[layer.cli])
    target.chmod(0o640)
    layer.path.unlink()
    link_text = os.path.relpath(target, layer.path.parent)
    layer.path.symlink_to(link_text)

    assert swapper.main(_use_args("--cli", layer.cli)) == 0
    assert layer.path.is_symlink()
    assert os.readlink(layer.path) == link_text
    _assert_swapped(layer)

    assert swapper.main(["revert", "--cli", layer.cli]) == 0
    assert layer.path.is_symlink()
    assert os.readlink(layer.path) == link_text
    assert target.read_bytes() == originals[layer.cli]
    assert stat.S_IMODE(target.stat().st_mode) == 0o640
    assert not swapper.backup_of(layer).exists()
    assert not any(".mcp-swap-" in path.name for path in tmp_path.rglob("*"))


def test_revert_refuses_a_config_symlink_retarget(
    swapper: types.ModuleType, tmp_path: pathlib.Path
) -> None:
    """A link moved after use cannot redirect restoration into another file."""
    originals = _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    first = tmp_path / "first.json"
    second = tmp_path / "second.json"
    first.write_bytes(originals[layer.cli])
    first.chmod(0o640)
    layer.path.unlink()
    layer.path.symlink_to(first)
    assert swapper.main(_use_args("--cli", layer.cli)) == 0
    swapped = first.read_bytes()
    second.write_bytes(swapped)
    second.chmod(0o640)
    layer.path.unlink()
    layer.path.symlink_to(second)

    with pytest.raises(SystemExit, match="claude"):
        swapper.main(["revert", "--cli", layer.cli])

    assert second.read_bytes() == swapped
    assert first.read_bytes() == swapped
    assert swapper.backup_of(layer).is_file()
    assert _state_of(swapper, layer).is_file()


@pytest.mark.parametrize("artifact", ["backup", "state"])
def test_revert_refuses_tampered_recovery_artifacts(
    swapper: types.ModuleType, artifact: str
) -> None:
    """Neither half of the recovery unit may change before restore."""
    _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    assert swapper.main(_use_args("--cli", layer.cli)) == 0
    swapped = layer.path.read_bytes()
    selected = (
        swapper.backup_of(layer) if artifact == "backup" else _state_of(swapper, layer)
    )
    if artifact == "backup":
        tampered = b"tampered recovery artifact\n"
    else:
        document = json.loads(selected.read_text(encoding="utf-8"))
        document["server"]["command"] = "/tampered/mcp"
        tampered = (
            json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n"
        ).encode()
    selected.write_bytes(tampered)

    with pytest.raises(SystemExit, match="claude"):
        swapper.main(["revert", "--cli", layer.cli])

    assert layer.path.read_bytes() == swapped
    assert selected.read_bytes() == tampered
    assert swapper.backup_of(layer).exists()
    assert _state_of(swapper, layer).exists()


def test_revert_refuses_a_replaced_backup_inode(
    swapper: types.ModuleType,
) -> None:
    """Byte-identical backup replacement still loses recovery ownership."""
    _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    assert swapper.main(_use_args("--cli", layer.cli)) == 0
    swapped = layer.path.read_bytes()
    backup = swapper.backup_of(layer)
    replacement = backup.with_name("replacement.backup")
    replacement.write_bytes(backup.read_bytes())
    replacement.chmod(stat.S_IMODE(backup.stat().st_mode))
    os.replace(replacement, backup)

    with pytest.raises(SystemExit, match="claude"):
        swapper.main(["revert", "--cli", layer.cli])

    assert layer.path.read_bytes() == swapped
    assert backup.is_file() and _state_of(swapper, layer).is_file()


@pytest.mark.parametrize("dry_run", [True, False], ids=["dry-run", "commit"])
def test_all_selected_revert_preflights_every_recovery_record(
    swapper: types.ModuleType, dry_run: bool
) -> None:
    """A bad final record blocks every selected restore, including dry-run."""
    _seed_configs(swapper)
    assert swapper.main(_use_args()) == 0
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    _state_of(swapper, pi).write_bytes(b"{ malformed\n")
    before = {layer.cli: _owned_layer_state(swapper, layer) for layer in swapper.LAYERS}
    command = ["revert", *(["--dry-run"] if dry_run else [])]

    with pytest.raises(SystemExit, match="pi"):
        swapper.main(command)

    assert {
        layer.cli: _owned_layer_state(swapper, layer) for layer in swapper.LAYERS
    } == before
    _assert_no_stages(swapper)


def test_recovery_record_is_bounded_private_and_route_specific(
    swapper: types.ModuleType,
) -> None:
    """The durable ownership record identifies the exact requested route."""
    _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    args = _use_args("--cli", layer.cli, "--name", "private-tmux")
    assert swapper.main(args) == 0
    state = _state_of(swapper, layer)
    document = json.loads(state.read_text(encoding="utf-8"))

    assert stat.S_ISREG(state.lstat().st_mode)
    assert stat.S_IMODE(state.stat().st_mode) == 0o600
    assert state.stat().st_size <= 16 * 1024
    assert document["version"] == 1
    assert document["cli"] == layer.cli
    assert document["server"] == {
        "name": "private-tmux",
        "command": LAUNCHER,
        "arguments": ["--socket", "/tmp/libtmux-java-dev/test/s"],
    }

    with pytest.raises(SystemExit, match="claude"):
        swapper.main(["revert", "--cli", layer.cli])
    assert swapper.main(["revert", "--cli", layer.cli, "--name", "private-tmux"]) == 0


def test_duplicate_physical_config_targets_are_rejected(
    swapper: types.ModuleType,
) -> None:
    """Two logical client configs may not race to replace one physical file."""
    originals = _seed_configs(swapper)
    claude = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    cursor = next(layer for layer in swapper.LAYERS if layer.cli == "cursor")
    cursor.path.unlink()
    cursor.path.symlink_to(claude.path)

    with pytest.raises(SystemExit, match="duplicate physical config target"):
        swapper.main(_use_args())

    assert claude.path.read_bytes() == originals[claude.cli]
    assert cursor.path.is_symlink()
    assert all(not swapper.backup_of(layer).exists() for layer in swapper.LAYERS)


def test_duplicate_recovery_destinations_are_rejected(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A sidecar cannot share another selected client's physical destination."""
    originals = _seed_configs(swapper)
    claude = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    cursor = next(layer for layer in swapper.LAYERS if layer.cli == "cursor")
    real_state_of = swapper.state_of
    shared = real_state_of(claude)

    def overlapping_state(layer: object) -> pathlib.Path:
        return shared if layer.cli == cursor.cli else real_state_of(layer)

    monkeypatch.setattr(swapper, "state_of", overlapping_state)
    with pytest.raises(SystemExit, match="duplicate transaction destination"):
        swapper.main(_use_args())

    _assert_original_state(swapper, originals)


def test_symlink_transition_after_planning_writes_nothing(
    swapper: types.ModuleType,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: pathlib.Path,
) -> None:
    """A link retargeted to identical bytes still invalidates the plan."""
    originals = _seed_configs(swapper)
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    first = tmp_path / "first.json"
    second = tmp_path / "second.json"
    for target in (first, second):
        target.write_bytes(originals[pi.cli])
        target.chmod(0o640)
    pi.path.unlink()
    pi.path.symlink_to(first)

    def retarget(_args: object) -> None:
        pi.path.unlink()
        pi.path.symlink_to(second)

    monkeypatch.setattr(swapper, "build", retarget)
    with pytest.raises(SystemExit, match="pi.*changed during preflight"):
        swapper.main(_use_args())

    assert pi.path.is_symlink() and pi.path.resolve() == second
    assert second.read_bytes() == originals[pi.cli]
    assert all(
        layer.path.read_bytes() == originals[layer.cli] for layer in swapper.LAYERS
    )
    assert all(not swapper.backup_of(layer).exists() for layer in swapper.LAYERS)


def test_mode_transition_after_planning_writes_nothing(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Mode is part of the preflight identity even when bytes do not change."""
    originals = _seed_configs(swapper)
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")

    def change_mode(_args: object) -> None:
        pi.path.chmod(0o600)

    monkeypatch.setattr(swapper, "build", change_mode)
    with pytest.raises(SystemExit, match="pi.*changed during preflight"):
        swapper.main(_use_args())

    assert pi.path.read_bytes() == originals[pi.cli]
    assert stat.S_IMODE(pi.path.stat().st_mode) == 0o600
    assert all(
        layer.path.read_bytes() == originals[layer.cli] for layer in swapper.LAYERS
    )
    assert all(not swapper.backup_of(layer).exists() for layer in swapper.LAYERS)


def test_backup_appearance_after_planning_writes_nothing(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A concurrent backup must be preserved and invalidate the whole plan."""
    originals = _seed_configs(swapper)
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    external = b"external backup\n"

    def create_backup(_args: object) -> None:
        swapper.backup_of(pi).write_bytes(external)

    monkeypatch.setattr(swapper, "build", create_backup)
    with pytest.raises(SystemExit, match="pi.*backup changed during preflight"):
        swapper.main(_use_args())

    _assert_original_state(swapper, originals, backups={pi.cli: external})


def test_existing_backup_mode_change_invalidates_the_plan(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A retained backup's identity and mode are frozen with the configs."""
    _seed_configs(swapper)
    assert swapper.main(_use_args()) == 0
    before = {layer.cli: _layer_state(swapper, layer) for layer in swapper.LAYERS}
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    pi_backup = swapper.backup_of(pi)

    def change_backup_mode(_args: object) -> None:
        pi_backup.chmod(0o600)

    monkeypatch.setattr(swapper, "build", change_backup_mode)
    with pytest.raises(SystemExit, match="pi.*backup changed during preflight"):
        swapper.main(_use_args("--bin", "/opt/another/libtmux-mcp"))

    for layer in swapper.LAYERS:
        current = _layer_state(swapper, layer)
        if layer.cli == pi.cli:
            expected = list(before[layer.cli])
            expected[6] = 0o600
            assert current == tuple(expected)
        else:
            assert current == before[layer.cli]
    _assert_no_stages(swapper)


def test_dry_run_plans_without_building_or_writing(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Dry-run parses every config and backup destination but changes no files."""
    originals = _seed_configs(swapper)

    def forbidden_build(_args: object) -> None:
        raise AssertionError("dry-run built the distribution")

    monkeypatch.setattr(swapper, "build", forbidden_build)
    assert swapper.main(_use_args("--dry-run")) == 0
    _assert_original_state(swapper, originals)
    _assert_no_stages(swapper)


def test_staging_failure_cleans_up_before_any_destination_write(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A late stage failure leaves configs, backups, and earlier stages absent."""
    originals = _seed_configs(swapper)
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    real_stage = swapper._stage

    def fail_pi_stage(
        directory: pathlib.Path,
        logical_name: str,
        role: str,
        data: bytes,
        mode: int,
    ) -> pathlib.Path:
        if logical_name == pi.path.name and role == "output":
            raise OSError("synthetic stage failure")
        return real_stage(directory, logical_name, role, data, mode)

    monkeypatch.setattr(swapper, "_stage", fail_pi_stage)
    with pytest.raises(SystemExit, match="synthetic stage failure"):
        swapper.main(_use_args())

    _assert_original_state(swapper, originals)
    _assert_no_stages(swapper)


def test_failed_rollback_preserves_backup_and_recovery_stage(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Unknown rollback state must retain both recoverable copies."""
    originals = _seed_configs(swapper)
    claude = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    cursor = next(layer for layer in swapper.LAYERS if layer.cli == "cursor")
    real_replace = os.replace
    claude_writes = 0

    def fail_commit_and_rollback(src: object, dst: object) -> None:
        nonlocal claude_writes
        destination = pathlib.Path(dst)
        if destination == cursor.path:
            raise OSError("synthetic replace failure")
        if destination == claude.path:
            claude_writes += 1
            if claude_writes == 2:
                raise OSError("synthetic rollback failure")
        real_replace(src, dst)

    monkeypatch.setattr(os, "replace", fail_commit_and_rollback)
    with pytest.raises(SystemExit, match="synthetic rollback failure"):
        swapper.main(_use_args())

    assert swapper.backup_of(claude).read_bytes() == originals[claude.cli]
    recovery = list(claude.path.parent.glob(f".{claude.path.name}.mcp-swap-recovery-*"))
    assert len(recovery) == 1
    assert recovery[0].read_bytes() == originals[claude.cli]
    assert stat.S_IMODE(recovery[0].stat().st_mode) == 0o640


def test_failed_backup_rollback_preserves_its_recovery_copy(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A removed backup is retained as a stage if recreation cannot be proven."""
    originals = _seed_configs(swapper)
    assert swapper.main(_use_args()) == 0
    swapped = {layer.cli: layer.path.read_bytes() for layer in swapper.LAYERS}
    claude = next(layer for layer in swapper.LAYERS if layer.cli == "claude")
    codex = next(layer for layer in swapper.LAYERS if layer.cli == "codex")
    claude_backup = swapper.backup_of(claude)
    codex_backup = swapper.backup_of(codex)
    real_replace = os.replace
    rollback_started = False

    def fail_backup_restore(src: object, dst: object) -> None:
        nonlocal rollback_started
        if pathlib.Path(src) == codex_backup:
            rollback_started = True
            raise OSError("synthetic backup removal failure")
        if rollback_started and pathlib.Path(dst) == claude_backup:
            raise OSError("synthetic backup rollback failure")
        real_replace(src, dst)

    monkeypatch.setattr(os, "replace", fail_backup_restore)
    with pytest.raises(SystemExit, match="synthetic backup rollback failure"):
        swapper.main(["revert"])

    for layer in swapper.LAYERS:
        assert layer.path.read_bytes() == swapped[layer.cli]
    assert not claude_backup.exists()
    recovery = list(
        claude_backup.parent.glob(f".{claude_backup.name}.mcp-swap-recovery-*")
    )
    assert len(recovery) == 1
    assert recovery[0].read_bytes() == originals[claude.cli]
    assert stat.S_IMODE(recovery[0].stat().st_mode) == 0o640
    for layer in swapper.LAYERS[1:]:
        assert swapper.backup_of(layer).read_bytes() == originals[layer.cli]


def test_opencode_missing_root_preserves_jsonc_bytes_and_mode(
    swapper: types.ModuleType,
) -> None:
    """Adding mcp leaves comments, trailing comma, and unrelated bytes alone."""
    _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "opencode")
    before = layer.path.read_text(encoding="utf-8")

    assert swapper.main(_use_args("--cli", "opencode")) == 0

    after = layer.path.read_text(encoding="utf-8")
    assert after.startswith(before[: before.index(",\n}")])
    assert after.endswith(",\n}\n")
    assert "// root comment stays" in after
    assert _document(layer)["unrelated"] == {"keep": True}
    assert stat.S_IMODE(layer.path.stat().st_mode) == 0o640
    _assert_swapped(layer)


def test_opencode_replaces_entry_without_dropping_its_comment(
    swapper: types.ModuleType,
) -> None:
    """A rationale inside the replaced server entry survives the swap."""
    _seed_configs(swapper)
    layer = next(layer for layer in swapper.LAYERS if layer.cli == "opencode")
    layer.path.write_text(
        "{\n"
        '  "mcp": {\n'
        '    "tmux": {\n'
        '      "type": "local",\n'
        "      // pinned locally; keep this rationale\n"
        '      "command": ["old", "server"],\n'
        "    },\n"
        '    "other": {"type": "local", "command": ["echo", "keep"]},\n'
        "  },\n"
        "}\n",
        encoding="utf-8",
    )

    assert swapper.main(_use_args("--cli", "opencode")) == 0

    after = layer.path.read_text(encoding="utf-8")
    assert "// pinned locally; keep this rationale" in after
    document = _document(layer)
    assert document["mcp"]["other"]["command"] == ["echo", "keep"]
    _assert_swapped(layer)


def test_pi_detect_explains_adapter_availability(
    swapper: types.ModuleType,
    tmp_path: pathlib.Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Pi is not reported usable when only its adapter config exists."""
    _seed_configs(swapper)
    swapper.PI_ADAPTER_DIR = tmp_path / "missing-adapter"

    assert swapper.main(["detect"]) == 0
    assert swapper.PI_ADAPTER_HINT in capsys.readouterr().out

    swapper.PI_ADAPTER_DIR.mkdir()
    assert swapper.main(["detect"]) == 0
    assert swapper.PI_ADAPTER_HINT not in capsys.readouterr().out


def test_antigravity_is_an_alias_for_canonical_agy(
    swapper: types.ModuleType,
) -> None:
    """The legacy name selects one agy layer and never appears as a ninth."""
    originals = _seed_configs(swapper)

    assert swapper.main(_use_args("--cli", "antigravity")) == 0

    assert tuple(layer.cli for layer in swapper.LAYERS) == CANONICAL_CLIS
    agy = next(layer for layer in swapper.LAYERS if layer.cli == "agy")
    _assert_swapped(agy)
    for layer in swapper.LAYERS:
        if layer.cli != "agy":
            assert layer.path.read_bytes() == originals[layer.cli]


def test_no_arguments_and_explicit_help_exit_zero(
    swapper: types.ModuleType, capsys: pytest.CaptureFixture[str]
) -> None:
    """Help is a successful query, including the convenient no-arg form."""
    assert swapper.main([]) == 0
    assert "usage: mcp_swap" in capsys.readouterr().out

    with pytest.raises(SystemExit) as stopped:
        swapper.main(["--help"])
    assert stopped.value.code == 0
    assert "usage: mcp_swap" in capsys.readouterr().out


def _assert_original_state(
    swapper: types.ModuleType,
    originals: dict[str, bytes],
    *,
    backups: dict[str, bytes] | None = None,
) -> None:
    expected_backups = backups or {}
    for layer in swapper.LAYERS:
        assert layer.path.read_bytes() == originals[layer.cli]
        assert stat.S_IMODE(layer.path.stat().st_mode) == 0o640
        backup = swapper.backup_of(layer)
        if layer.cli in expected_backups:
            assert backup.read_bytes() == expected_backups[layer.cli]
        else:
            assert not backup.exists()
            assert not _state_of(swapper, layer).exists()


def _layer_state(swapper: types.ModuleType, layer: object) -> tuple[object, ...]:
    link = os.readlink(layer.path) if layer.path.is_symlink() else None
    backup = swapper.backup_of(layer)
    state = _state_of(swapper, layer)
    return (
        layer.path.is_symlink(),
        link,
        layer.path.read_bytes(),
        stat.S_IMODE(layer.path.stat().st_mode),
        backup.is_symlink(),
        backup.read_bytes() if backup.is_file() else None,
        stat.S_IMODE(backup.stat().st_mode) if backup.is_file() else None,
        state.is_symlink(),
        state.read_bytes() if state.is_file() else None,
        stat.S_IMODE(state.stat().st_mode) if state.is_file() else None,
    )


def _state_of(swapper: types.ModuleType, layer: object) -> pathlib.Path:
    backup = swapper.backup_of(layer)
    return backup.with_name(backup.name + ".state")


def _owned_layer_state(swapper: types.ModuleType, layer: object) -> tuple[object, ...]:
    return _layer_state(swapper, layer)


def _assert_no_stages(swapper: types.ModuleType) -> None:
    home = next(layer for layer in swapper.LAYERS if layer.cli == "claude").path.parent
    roles = re.compile(r"\.mcp-swap-(?:new|output|recovery|restore|state)-")
    assert [path for path in home.rglob("*") if roles.search(path.name)] == []


def _fail_first_replace_to(
    monkeypatch: pytest.MonkeyPatch, destination: pathlib.Path
) -> list[pathlib.Path]:
    real_replace = os.replace
    real_rename = os.rename
    failed = False
    destinations: list[pathlib.Path] = []

    def replace(src: object, dst: object, *args: object, **kwargs: object) -> None:
        nonlocal failed
        target = pathlib.Path(dst)
        destinations.append(target)
        if target == destination and not failed:
            failed = True
            raise OSError("synthetic replace failure")
        real_replace(src, dst, *args, **kwargs)

    def rename(src: object, dst: object, *args: object, **kwargs: object) -> None:
        nonlocal failed
        target = pathlib.Path(dst)
        destinations.append(target)
        if target == destination and not failed:
            failed = True
            raise OSError("synthetic replace failure")
        real_rename(src, dst, *args, **kwargs)

    monkeypatch.setattr(os, "replace", replace)
    monkeypatch.setattr(os, "rename", rename)
    return destinations
