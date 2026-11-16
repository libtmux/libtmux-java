"""Tests for the tmux matrix builder.

A small source archive stands in for tmux. Its `configure` and `Makefile` install
a `bin/tmux` that prints a version, so the whole path from download to a usable
tree runs offline. The stand-ins are Python programs; `make` runs the install
recipe, which only copies a file.
"""

import hashlib
import io
import json
import os
import shutil
import socket
import sys
import tarfile
from pathlib import Path

import pytest

import build_tmux_matrix as builder

CONFIGURE = f"""#!{sys.executable}
import pathlib
import sys

prefix = sys.argv[1].removeprefix("--prefix=")
pathlib.Path("Makefile").write_text(
    f"all:\\n\\t@true\\ninstall:\\n\\tmkdir -p {{prefix}}/bin\\n\\tcp tmux {{prefix}}/bin/tmux\\n"
)
"""
BROKEN_CONFIGURE = f"#!{sys.executable}\nraise SystemExit(7)\n"


def stand_in_tmux(release: str) -> str:
    """Return a tmux that reports its release and does nothing else."""
    return f"#!{sys.executable}\nprint('tmux {release}')\n"


def publish(base: Path, release: str, *, configure: str = CONFIGURE) -> str:
    """Write `<base>/<release>/tmux-<release>.tar.gz` holding the stand-in source.

    Returns the archive's SHA256, which is what the shared action would pin.
    """
    directory = base / release
    directory.mkdir(parents=True)
    archive = directory / f"tmux-{release}.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        for name, text in (("configure", configure), ("tmux", stand_in_tmux(release))):
            info = tarfile.TarInfo(f"tmux-{release}/{name}")
            data = text.encode()
            info.size = len(data)
            info.mode = 0o755
            tar.addfile(info, io.BytesIO(data))
    return hashlib.sha256(archive.read_bytes()).hexdigest()


def pin(tmp_path: Path, digests: dict[str, str]) -> list[str]:
    """Write the digests a shared action would pin and return the option that names them."""
    path = tmp_path / "tmux-releases.json"
    path.write_text(json.dumps(digests))
    return ["--digests-url", path.as_uri()]


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
    digests = {release: publish(base, release) for release in ("1.1", "1.2a", "1.0")}
    destination = tmp_path / "tree"
    arguments = [
        str(destination),
        "--manifest",
        str(manifest),
        "--base-url",
        base.as_uri(),
        *pin(tmp_path, digests),
    ]

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


def run_one_release(
    tmp_path: Path, manifest: Path, base: Path, digests: dict[str, str]
) -> tuple[int, Path]:
    """Build the manifest's lanes from `base` against `digests`; return the status and the tree."""
    destination = tmp_path / "tree"
    status = builder.main(
        [
            str(destination),
            "--manifest",
            str(manifest),
            "--base-url",
            base.as_uri(),
            *pin(tmp_path, digests),
        ]
    )
    return status, destination


@pytest.mark.skipif(shutil.which("make") is None, reason="needs make")
def test_a_failed_build_stops_the_run(
    tmp_path: Path, manifest: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """A release that does not build is an error, not a missing line in the table."""
    base = tmp_path / "published"
    digests = {"1.1": publish(base, "1.1", configure=BROKEN_CONFIGURE)}

    status, destination = run_one_release(tmp_path, manifest, base, digests)

    captured = capsys.readouterr()
    assert status == 1
    assert "== building tmux 1.1" in captured.out
    assert "configure" in captured.err
    assert "failed with status 7" in captured.err
    assert not (destination / "1.1" / "bin" / "tmux").exists()


def test_an_archive_that_is_not_the_pinned_one_is_refused(
    tmp_path: Path, manifest: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """The bytes must be the ones CI starts from; nothing is built from any others."""
    base = tmp_path / "published"
    digests = {"1.1": publish(base, "1.1")}
    (base / "1.1" / "tmux-1.1.tar.gz").write_bytes(b"not the pinned archive")

    status, destination = run_one_release(tmp_path, manifest, base, digests)

    assert status == 1
    assert "not the pinned" in capsys.readouterr().err
    assert not (destination / "1.1").exists()


def test_a_release_the_shared_action_does_not_pin_is_refused(
    tmp_path: Path, manifest: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """A release with no pinned digest cannot be verified, so it is not built."""
    base = tmp_path / "published"
    publish(base, "1.1")

    status, destination = run_one_release(tmp_path, manifest, base, {})

    assert status == 1
    assert "pins no SHA256 for tmux 1.1" in capsys.readouterr().err
    assert not (destination / "1.1").exists()


def test_the_pin_is_read_from_the_workflow_the_matrix_runs() -> None:
    """The digests come from the commit CI builds with, so they cannot drift from it."""
    commit = builder.pinned_commit(builder.WORKFLOW)

    assert len(commit) == 40
    assert commit in builder.WORKFLOW.read_text()


def test_a_download_that_stops_answering_is_given_up_on(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A server that accepts the connection and sends nothing must not hold the run."""
    monkeypatch.setattr(builder, "DOWNLOAD_TIMEOUT_SECONDS", 0.5)
    with socket.socket() as silent:
        silent.bind(("127.0.0.1", 0))
        silent.listen(1)
        url = f"http://127.0.0.1:{silent.getsockname()[1]}/tmux.tar.gz"

        with pytest.raises(builder.BuildError, match="cannot download"):
            builder.download(url, tmp_path / "tmux.tar.gz")


def test_a_build_step_that_never_finishes_is_given_up_on(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A hung ./configure or make ends the run with the step named."""
    monkeypatch.setattr(builder, "STEP_TIMEOUT_SECONDS", 0.5)

    with pytest.raises(builder.BuildError, match=r"did not finish within 0\.5 seconds"):
        builder.run([sys.executable, "-c", "import time; time.sleep(30)"], tmp_path)


def test_one_release_can_be_built_alone(
    tmp_path: Path, manifest: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """A macOS lane builds the one release it is named for."""
    base = tmp_path / "published"
    digests = {"1.2a": publish(base, "1.2a")}
    destination = tmp_path / "tree"

    status = builder.main(
        [
            str(destination),
            "--manifest",
            str(manifest),
            "--base-url",
            base.as_uri(),
            "--only",
            "1.2a",
            *pin(tmp_path, digests),
        ]
    )

    assert status == 0
    assert (destination / "1.2a" / "bin" / "tmux").is_file()
    assert not (destination / "1.1").exists()
    assert "lanes: 1.2a\n" in capsys.readouterr().out


def test_a_release_outside_the_manifest_cannot_be_built_alone(
    tmp_path: Path, manifest: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert builder.main([str(tmp_path / "tree"), "--manifest", str(manifest), "--only", "9.9"]) == 1

    assert "9.9 is not a release of lanes.json" in capsys.readouterr().err


def test_macos_finds_its_libraries_through_homebrew(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The build names each Homebrew prefix, and drops utf8proc, as the macOS lane did."""
    brew = tmp_path / "brew"
    brew.write_text(
        f"#!{sys.executable}\nimport sys\nprint('/opt/' + sys.argv[-1])\n", encoding="utf-8"
    )
    brew.chmod(0o755)
    monkeypatch.setenv("PATH", f"{tmp_path}{os.pathsep}{os.environ['PATH']}")
    monkeypatch.setattr(sys, "platform", "darwin")

    environment = builder.build_environment()

    assert environment["PKG_CONFIG_PATH"] == (
        "/opt/libevent/lib/pkgconfig:/opt/ncurses/lib/pkgconfig:/opt/jemalloc/lib/pkgconfig"
    )
    assert environment["CPPFLAGS"] == "-I/opt/ncurses/include"
    assert environment["LDFLAGS"] == "-L/opt/ncurses/lib"
    assert builder.configure_arguments(Path("/p")) == ["--prefix=/p", "--disable-utf8proc"]


def test_linux_builds_in_the_ambient_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(sys, "platform", "linux")

    assert builder.build_environment() == dict(os.environ)
    assert builder.configure_arguments(Path("/p")) == ["--prefix=/p"]


def test_a_built_tmux_that_hangs_on_its_version_is_a_build_error(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    binary = tmp_path / "tmux"
    binary.write_text(f"#!{sys.executable}\nimport time\ntime.sleep(30)\n", encoding="utf-8")
    binary.chmod(0o755)
    monkeypatch.setattr(builder.bounded, "PROBE", 0.3)
    with pytest.raises(builder.BuildError, match="cannot read the version"):
        builder.version_of(binary)
