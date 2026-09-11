"""Read-only SSH preflight for the owner's separate media VPS.

No deployment, package install, migration, paid API call or old-server access.
Secrets are provided by GitHub Actions, never by workflow inputs or source code.
"""
import base64
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

HOST = "2.56.174.123"


def main():
    key = os.environ.get("AEZA_SSH_PRIVATE_KEY", "").strip()
    known = os.environ.get("AEZA_SSH_KNOWN_HOSTS", "").strip()
    user = os.environ.get("AEZA_SSH_USER", "").strip() or "root"
    if not key or not known:
        print("SETUP_REQUIRED: add AEZA_SSH_PRIVATE_KEY and AEZA_SSH_KNOWN_HOSTS in repository Actions Secrets.")
        return 2
    if not re.fullmatch(r"[a-z_][a-z0-9_-]{0,31}", user):
        print("SETUP_REQUIRED: invalid SSH user name.")
        return 2
    parts = known.split()
    if len(parts) != 3 or parts[:2] != [HOST, "ssh-ed25519"]:
        print("SETUP_REQUIRED: use the exact public host-key line from the Aeza console.")
        return 2
    try:
        base64.b64decode(parts[2], validate=True)
    except ValueError:
        print("SETUP_REQUIRED: invalid public host-key encoding.")
        return 2
    if "PRIVATE KEY-----" not in key:
        print("SETUP_REQUIRED: the private-key secret must contain the SSH private key, not the .pub file.")
        return 2
    agent_env = {k: v for k, v in os.environ.items() if not k.startswith("AEZA_")}
    with tempfile.TemporaryDirectory(prefix="oldi-aeza-") as directory:
        root = Path(directory)
        keyfile = root / "identity"
        keyfile.write_text(key + "\n")
        keyfile.chmod(0o600)
        hosts = root / "known_hosts"
        hosts.write_text(" ".join(parts) + "\n")
        hosts.chmod(0o600)
        askpass = root / "askpass"
        askpass.write_text('#!/bin/sh\nprintf \'%s\' "$AEZA_SSH_KEY_PASSPHRASE"\n')
        askpass.chmod(0o700)
        started = subprocess.run(["ssh-agent", "-s"], capture_output=True, text=True, env=agent_env, timeout=10)
        values = dict(re.findall(r"(SSH_AUTH_SOCK|SSH_AGENT_PID)=([^;\n]+);", started.stdout))
        if started.returncode or len(values) != 2:
            print("SSH_AGENT_FAILED")
            return 3
        agent_env.update(values)
        try:
            add_env = dict(agent_env, DISPLAY="oldi:0", SSH_ASKPASS=str(askpass), SSH_ASKPASS_REQUIRE="force",
                           AEZA_SSH_KEY_PASSPHRASE=os.environ.get("AEZA_SSH_KEY_PASSPHRASE", ""))
            loaded = subprocess.run(["ssh-add", str(keyfile)], stdin=subprocess.DEVNULL,
                                    capture_output=True, env=add_env, timeout=15)
            if loaded.returncode:
                print("SSH_KEY_FAILED: check key format and optional AEZA_SSH_KEY_PASSPHRASE secret.")
                return 3
            remote = Path(__file__).with_name("inspect_media_vps.py").read_bytes()
            connected = subprocess.run([
                "ssh", "-F", "/dev/null", "-p", "22", "-T", "-i", str(keyfile),
                "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                "-o", "UserKnownHostsFile=" + str(hosts), "-o", "GlobalKnownHostsFile=/dev/null",
                "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=2",
                "-o", "ForwardAgent=no", "-o", "ClearAllForwardings=yes", "-o", "RequestTTY=no",
                user + "@" + HOST, "python3 -"], input=remote, env=agent_env, timeout=90)
            if connected.returncode:
                print("SSH_PREFLIGHT_FAILED: no deployment was attempted.")
            return connected.returncode
        finally:
            subprocess.run(["ssh-agent", "-k"], env=agent_env, capture_output=True, timeout=10)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.TimeoutExpired:
        print("SSH_PREFLIGHT_TIMEOUT: no deployment was attempted.")
        sys.exit(3)
