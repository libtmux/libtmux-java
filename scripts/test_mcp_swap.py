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
    for layer in swapper.LAYERS:
        if layer.cli != cli:
            assert layer.path.read_bytes() == originals[layer.cli]
            assert not swapper.backup_of(layer).exists()

    assert swapper.main(["revert", "--cli", cli]) == 0
    assert selected.path.read_bytes() == originals[cli]
    assert stat.S_IMODE(selected.path.stat().st_mode) == 0o640
    assert not swapper.backup_of(selected).exists()


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

    assert swapper.main(["revert"]) == 0
    for layer in swapper.LAYERS:
        assert layer.path.read_bytes() == originals[layer.cli]
        assert stat.S_IMODE(layer.path.stat().st_mode) == 0o640
        assert not swapper.backup_of(layer).exists()


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
        *reversed(configs[:-1]),
    ]
    _assert_no_stages(swapper)


def test_failed_repeat_use_preserves_existing_backups(
    swapper: types.ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Rollback may not remove backups owned by an earlier successful swap."""
    _seed_configs(swapper)
    assert swapper.main(_use_args()) == 0
    before = {layer.cli: _layer_state(swapper, layer) for layer in swapper.LAYERS}
    pi = next(layer for layer in swapper.LAYERS if layer.cli == "pi")
    _fail_first_replace_to(monkeypatch, pi.path)

    with pytest.raises((OSError, SystemExit), match="synthetic replace failure"):
        swapper.main(_use_args("--bin", "/opt/another/libtmux-mcp"))

    assert {
        layer.cli: _layer_state(swapper, layer) for layer in swapper.LAYERS
    } == before
    _assert_no_stages(swapper)


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
        *reversed(configs[:-1]),
    ]
    _assert_no_stages(swapper)


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
            assert current[:-1] == before[layer.cli][:-1]
            assert current[-1] == 0o600
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
    real_unlink = os.unlink
    rollback_started = False

    def fail_backup_restore(src: object, dst: object) -> None:
        if rollback_started and pathlib.Path(dst) == claude_backup:
            raise OSError("synthetic backup rollback failure")
        real_replace(src, dst)

    def fail_later_removal(path: object, *args: object, **kwargs: object) -> None:
        nonlocal rollback_started
        if pathlib.Path(path) == codex_backup:
            rollback_started = True
            raise OSError("synthetic backup removal failure")
        real_unlink(path, *args, **kwargs)

    monkeypatch.setattr(os, "replace", fail_backup_restore)
    monkeypatch.setattr(os, "unlink", fail_later_removal)
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


def _layer_state(swapper: types.ModuleType, layer: object) -> tuple[object, ...]:
    link = os.readlink(layer.path) if layer.path.is_symlink() else None
    backup = swapper.backup_of(layer)
    return (
        layer.path.is_symlink(),
        link,
        layer.path.read_bytes(),
        stat.S_IMODE(layer.path.stat().st_mode),
        backup.is_symlink(),
        backup.read_bytes() if backup.is_file() else None,
        stat.S_IMODE(backup.stat().st_mode) if backup.is_file() else None,
    )


def _assert_no_stages(swapper: types.ModuleType) -> None:
    home = next(layer for layer in swapper.LAYERS if layer.cli == "claude").path.parent
    roles = re.compile(r"\.mcp-swap-(?:new|output|recovery|restore)-")
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
