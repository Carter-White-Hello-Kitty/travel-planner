"""Run every local HTTP smoke test in scripts/smoke/.

Smoke test files must be named <module>-smoke.py and return a nonzero exit
status on failure. The backend and Mailpit must already be running.
"""

import os
import socket
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse


def wait_for_backend() -> None:
    url = urlparse(os.environ.get("SMOKE_URL", "http://localhost:8085"))
    if url.scheme != "http" or url.hostname != "localhost" or not url.port:
        raise SystemExit("SMOKE_URL must be http://localhost:<port>")

    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            with socket.create_connection(("localhost", url.port), timeout=1):
                return
        except OSError:
            time.sleep(0.5)
    raise SystemExit(f"Backend did not become ready at {url.geturl()}")


def main() -> None:
    scripts = sorted((Path(__file__).resolve().parent / "smoke").glob("*-smoke.py"))
    if not scripts:
        raise SystemExit("No smoke tests found in scripts/smoke/")

    wait_for_backend()
    for script in scripts:
        print(f"RUN: {script.name}", flush=True)
        result = subprocess.run([sys.executable, str(script)], check=False)
        if result.returncode != 0:
            raise SystemExit(result.returncode)
    print(f"PASS: {len(scripts)} smoke test(s)")


if __name__ == "__main__":
    main()
