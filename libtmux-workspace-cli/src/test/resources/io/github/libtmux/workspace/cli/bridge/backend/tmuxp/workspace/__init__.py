"""Record that the bridge imported tmuxp code: write `entered` to the path in `marker.txt`.

ExecutionTest asserts the marker is absent when the bridge must refuse a backend before
running any of its code, and present when it may enter it.
"""

from pathlib import Path

Path(Path(__file__).parents[2].joinpath("marker.txt").read_text()).write_text("entered")
