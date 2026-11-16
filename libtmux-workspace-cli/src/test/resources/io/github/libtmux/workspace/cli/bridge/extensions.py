"""A workspace builder and plugin that report what they did, for the bridge's effect checks.

ExecutionTest loads a workspace that names `extensions:Builder` and `extensions.Plugin`
with `custom_append` and `custom_fail` set from the test's parameters. The builder prints
a line to standard output and one to standard error, and raises when `custom_fail` is set.
"""

import sys
from typing import Protocol

# tmuxp is the bridge's runtime. The jobs that run the bridge install it; the script checks do not.
from tmuxp.workspace.builder.classic import ClassicWorkspaceBuilder  # ty: ignore[import-not-found]


class Window(Protocol):
    def set_option(self, name: str, value: str) -> None: ...


class Plugin:
    def before_workspace_builder(self, session: object) -> None:
        pass

    def on_window_create(self, window: Window) -> None:
        window.set_option("@plugin", "called")

    def on_pane_create(self, pane: object) -> None:
        pass

    def after_window_finished(self, window: object) -> None:
        pass

    def before_script(self, session: object) -> None:
        pass


class Builder(ClassicWorkspaceBuilder):
    def build(self, session: object = None, append: bool = False) -> None:
        assert append == self.session_config["custom_append"]
        super().build(session=session, append=append)
        print("extension-out\x1b", flush=True)
        print("extension-err", file=sys.stderr, flush=True)
        if self.session_config["custom_fail"]:
            raise RuntimeError("extension failed")
