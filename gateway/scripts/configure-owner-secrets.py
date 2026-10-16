#!/usr/bin/env python3
"""Install this owner's already-generated gateway secrets with Wrangler. Run locally by the owner.

The owner key stays on disk. Only its SHA-256 hash and the separate signing secret are
sent to the already-authenticated Cloudflare account. No values are printed or committed.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys


def main() -> int:
    gateway = Path(__file__).resolve().parent.parent
    private = Path.home() / ".local/share/newpipe-ai-control/owner-credentials.json"
    if not private.is_file():
        print(f"Owner credential file is missing: {private}", file=sys.stderr)
        return 1
    if stat.S_IMODE(private.stat().st_mode) & 0o077:
        print("Credential file must be owner-readable only (chmod 600).", file=sys.stderr)
        return 1
    values = json.loads(private.read_text())
    owner_key, signing_secret = values["owner_key"], values["session_secret"]
    if not isinstance(owner_key, str) or len(owner_key) < 43 or not isinstance(signing_secret, str) or len(signing_secret) < 43:
        print("Owner credentials failed validation.", file=sys.stderr)
        return 1
    config = json.loads((gateway / "wrangler.jsonc").read_text())
    if config.get("name") != "ritovision-newpipe-ai-control" or values.get("origin") != config.get("vars", {}).get("PUBLIC_ORIGIN"):
        print("Worker identity or origin does not match the saved owner credentials.", file=sys.stderr)
        return 1
    binary = gateway / "node_modules/.bin/wrangler"
    if not binary.is_file():
        print("Run npm ci in the gateway directory first.", file=sys.stderr)
        return 1
    payload = {"OWNER_KEY_HASH": hashlib.sha256(owner_key.encode()).hexdigest(), "SESSION_SECRET": signing_secret}
    env = {**os.environ, "CI": "1", "NO_COLOR": "1"}
    result = subprocess.run([str(binary), "secret", "bulk", "--name", config["name"]],
                            input=json.dumps(payload), text=True, capture_output=True,
                            cwd=gateway, env=env, timeout=120, check=False)
    output = result.stdout + result.stderr
    for value in (owner_key, signing_secret, payload["OWNER_KEY_HASH"]):
        output = output.replace(value, "<redacted>")
    print(output)
    if result.returncode == 0:
        print("Authentication secrets installed. The configured Worker can now accept owner setup and OAuth authorization.")
    return result.returncode


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print(f"Setup did not complete ({type(error).__name__}); no credential values were displayed.", file=sys.stderr)
        raise SystemExit(1)
