import errno, os, select, subprocess, time
def drain_pty(fd):
    chunks = []
    while select.select([fd], [], [], 0)[0]:
        try: chunk = os.read(fd, 65536)
        except OSError as error:
            if error.errno == errno.EIO: break
            raise
        if not chunk: break
        chunks.append(chunk)
    return b''.join(chunks)
def wait_pty(child, master, timeout):
    until = time.monotonic() + timeout
    while child.poll() is None:
        drain_pty(master)
        remaining = until - time.monotonic()
        if remaining <= 0:
            raise subprocess.TimeoutExpired(child.args, timeout)
        select.select([master], [], [], min(remaining, .025))
    drain_pty(master)
    return child.returncode
