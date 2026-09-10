"""Bounded public-network diagnostics. Never reads chat files or credentials."""
import json
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import urllib.error
import urllib.request

report = {"purpose": "new media VPS only", "changes_made": False,
          "architecture": platform.machine(), "cpu_count": os.cpu_count()}
release = Path("/etc/os-release")
if release.exists():
    report["os"] = next((line.split("=", 1)[1].strip('"') for line in release.read_text().splitlines()
                         if line.startswith("PRETTY_NAME=")), "unknown")
disk = shutil.disk_usage("/")
report["disk_gib"] = {"total": round(disk.total / 2**30, 1), "free": round(disk.free / 2**30, 1)}
memory = Path("/proc/meminfo")
if memory.exists():
    report["memory"] = [line for line in memory.read_text().splitlines()
                        if line.startswith(("MemTotal:", "MemAvailable:"))]
if shutil.which("ss"):
    ports = subprocess.run(["ss", "-H", "-lntu"], capture_output=True, text=True, timeout=5)
    report["listening_ports"] = ports.stdout.splitlines()[:60]
report["youtube"] = {}
socket.setdefaulttimeout(8)
for host in ("www.youtube.com", "i.ytimg.com", "googlevideo.com"):
    status = {}
    try:
        addresses = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
        status["dns"] = "OK" if addresses else "EMPTY"
    except OSError:
        status["dns"] = "FAILED"
    try:
        # No cookies, tokens, video IDs, search terms, proxy env, or API generation.
        request = urllib.request.Request("https://" + host + "/", method="HEAD")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=8) as response:
            status["https_status"] = response.status
    except urllib.error.HTTPError as error:
        status["https_status"] = error.code
    except Exception as error:
        status["https_error"] = type(error).__name__
    report["youtube"][host] = status
report["limits"] = "HTTPS reachability is not proof of video playback or API provider eligibility."
print(json.dumps(report, ensure_ascii=False, indent=2))
