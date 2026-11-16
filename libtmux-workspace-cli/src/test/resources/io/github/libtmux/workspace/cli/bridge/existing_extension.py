"""A workspace builder that adds nothing to a session that already exists.

ExecutionTest loads a workspace that names this builder and an `existing` session, and
checks the reported effects keep the session's ownership and expansion.
"""

from pathlib import Path
from typing import Protocol


class Sessions(Protocol):
    def get(self, *, session_name: str) -> object: ...


class Server(Protocol):
    sessions: Sessions


class Builder:
    def __init__(self, session_config: dict[str, str], server: Server, plugins: object) -> None:
        assert session_config["start_directory"] == str(Path(__file__).parent)
        self.session = server.sessions.get(session_name=session_config["custom_session"])
        self.plugins = plugins

    def build(self, session: object = None, append: bool = False) -> None:
        pass
