"""Run local HTTP smoke tests in scripts/smoke/.

The default suite runs all scripts against the local development backend on
port 8085. Integration tests use the separate test database.
--ci runs only credential-free tests. The backend and Mailpit must be running.
"""

import argparse
import os
import socket
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

CI_SCRIPTS = {"auth-smoke.py"}


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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ci", action="store_true",
                        help="run only smoke tests that need no external API credentials")
    args = parser.parse_args()
    scripts = sorted((Path(__file__).resolve().parent / "smoke").glob("*-smoke.py"))
    if not scripts:
        raise SystemExit("No smoke tests found in scripts/smoke/")
    if args.ci:
        scripts = [script for script in scripts if script.name in CI_SCRIPTS]
        if len(scripts) != len(CI_SCRIPTS):
            raise SystemExit("One or more CI smoke scripts are missing")
    else:
        # The journal smoke verifies that HTTP and SQL use the same local DB.
        # Run it before scripts that would otherwise write to the wrong DB.
        scripts.sort(key=lambda script: (script.name != "journal-search-smoke.py", script.name))

    wait_for_backend()
    for script in scripts:
        print(f"RUN: {script.name}", flush=True)
        result = subprocess.run([sys.executable, str(script)], check=False)
        if result.returncode != 0:
            raise SystemExit(result.returncode)
    print(f"PASS: {len(scripts)} smoke test(s)")


if __name__ == "__main__":
    main()
