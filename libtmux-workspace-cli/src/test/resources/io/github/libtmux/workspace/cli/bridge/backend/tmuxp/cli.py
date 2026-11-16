"""The `tmuxp` command a qualified backend runs; it records that it was entered."""

from pathlib import Path


def cli() -> None:
    Path(Path(__file__).parents[1].joinpath("marker.txt").read_text()).write_text("entered")
    print("qualified")
