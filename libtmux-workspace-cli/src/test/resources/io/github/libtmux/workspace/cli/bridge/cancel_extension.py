"""A workspace builder that records its pid and then sleeps, so a test can cancel the load.

ExecutionTest loads a workspace that names `cancel_extension:Builder` with `marker` set
to a path. After the topology is built the builder prints `bridge-ready`, writes its pid
to the marker and sleeps; the test interrupts the load and checks the process is gone.
"""

import os
import time
from pathlib import Path

# tmuxp is the bridge's runtime. The jobs that run the bridge install it; the script checks do not.
from tmuxp.workspace.builder.classic import ClassicWorkspaceBuilder  # ty: ignore[import-not-found]


class Builder(ClassicWorkspaceBuilder):
    def build(self, session: object = None, append: bool = False) -> None:
        super().build(session=session, append=append)
        print("bridge-ready\x1b", flush=True)
        Path(self.session_config["marker"]).write_text(str(os.getpid()))
        time.sleep(30)
