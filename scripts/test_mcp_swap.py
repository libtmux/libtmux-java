from __future__ import annotations

import importlib.util
import json
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
