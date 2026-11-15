"""Tests for the tmux matrix builder.

A small source archive stands in for tmux. Its `configure` and `Makefile` install
a `bin/tmux` that prints a version, so the whole path from download to a usable
tree runs offline.
"""

from __future__ import annotations

import io
import json
import shutil
import tarfile
import textwrap
from pathlib import Path

import pytest

import build_tmux_matrix as builder

CONFIGURE = textwrap.dedent("""\
    #!/bin/sh
    prefix=${1#--prefix=}
    printf 'PREFIX = %s\\n' "$prefix" > Makefile
    cat >> Makefile <<'MAKE'
    all:
    \t@true
    install:
    \tmkdir -p $(PREFIX)/bin
    \tprintf '#!/bin/sh\\necho tmux %s\\n' "$(VERSION)" > $(PREFIX)/bin/tmux
    \tchmod +x $(PREFIX)/bin/tmux
    MAKE
    printf 'VERSION = %s\\n' "$(basename "$PWD" | sed 's/^tmux-//')" >> Makefile
    """)


def publish(base: Path, release: str) -> None:
    """Write `<base>/<release>/tmux-<release>.tar.gz` holding the stand-in source."""
    directory = base / release
    directory.mkdir(parents=True)
    with tarfile.open(directory / f"tmux-{release}.tar.gz", "w:gz") as tar:
        info = tarfile.TarInfo(f"tmux-{release}/configure")
        data = CONFIGURE.encode()
        info.size = len(data)
        info.mode = 0o755
        tar.addfile(info, io.BytesIO(data))


@pytest.fixture
def manifest(tmp_path: Path) -> Path:
    """Write a manifest with two lanes and one refusal lane."""
    path = tmp_path / "lanes.json"
    path.write_text(
        json.dumps(
            {
                "lanes": ["1.1", "1.2a"],
                "previewLanes": ["main"],
                "rejectionLanes": ["1.0"],
                "clientLane": "1.1",
            }
        )
    )
    return path


def test_the_lanes_and_the_refusal_lanes_are_built_and_previews_are_not(manifest: Path) -> None:
    """The matrix needs both lists, and a preview has no release archive."""
    assert builder.read_releases(manifest) == ["1.1", "1.2a", "1.0"]


def test_the_shipped_manifest_names_releases() -> None:
    """The real manifest is one the builder can read."""
    releases = builder.read_releases(builder.MANIFEST)
    assert "3.3a" in releases
    assert "main" not in releases


def test_a_manifest_without_lanes_is_refused(tmp_path: Path) -> None:
    """The builder has nothing to build from, and says so."""
    path = tmp_path / "lanes.json"
    path.write_text('{"previewLanes": ["main"]}')
    with pytest.raises(builder.ManifestError):
        builder.read_releases(path)


@pytest.mark.skipif(shutil.which("make") is None, reason="needs make")
def test_a_missing_release_is_built_and_a_built_one_is_kept(
    tmp_path: Path, manifest: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """Run twice: the first run builds all three, the second builds none."""
    base = tmp_path / "published"
    for release in ("1.1", "1.2a", "1.0"):
        publish(base, release)
    destination = tmp_path / "tree"
    arguments = [str(destination), "--manifest", str(manifest), "--base-url", base.as_uri()]

    assert builder.main(arguments) == 0
    first = capsys.readouterr().out
    assert first.count("== building tmux") == 3
    assert "1.2a   tmux 1.2a" in first
    assert (destination / "1.0" / "bin" / "tmux").is_file()

    assert builder.main(arguments) == 0
    second = capsys.readouterr().out
    assert second.count("already built") == 3
    assert "== building" not in second
    assert second.rstrip().endswith(f"./gradlew testTmuxMatrix -PlibtmuxMatrix={destination}")


@pytest.mark.skipif(shutil.which("make") is None, reason="needs make")
def test_a_failed_build_stops_the_run(
    tmp_path: Path, manifest: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """A release that does not build is an error, not a missing line in the table."""
    base = tmp_path / "published"
    publish(base, "1.1")
    (base / "1.1" / "tmux-1.1.tar.gz").write_bytes(b"not an archive")
    with pytest.raises(tarfile.ReadError):
        builder.main(
            [str(tmp_path / "tree"), "--manifest", str(manifest), "--base-url", base.as_uri()]
        )
    assert "== building tmux 1.1" in capsys.readouterr().out
