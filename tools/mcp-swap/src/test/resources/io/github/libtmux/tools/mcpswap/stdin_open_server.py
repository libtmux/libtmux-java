"""An MCP server that answers `initialize` only if its standard input is still open.

McpPreflightTest runs this as the server. It reads the request, waits 0.2 s, and
exits with status 9 when the client has closed its end of standard input; otherwise it
answers and stays alive, so the test also shows the preflight stops a server that
outlives its answer.
"""

import select
import sys
import time


def main() -> None:
    sys.stdin.readline()
    closed = select.poll()
    closed.register(sys.stdin, select.POLLHUP)
    time.sleep(0.2)
    if closed.poll(0):
        raise SystemExit(9)
    print('{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}', flush=True)
    time.sleep(300)


if __name__ == "__main__":
    main()
