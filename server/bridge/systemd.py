# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The systemd user unit that runs the server (zf install-service), and the systemctl calls on it."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

UNIT_NAME = "zeffin.service"

UNIT_TEMPLATE = """[Unit]
Description=zeffin (Flipper Zero relay for Claude Code)
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
WorkingDirectory={workdir}
ExecStart={exec_start}
Restart=on-failure
RestartSec=5
KillMode=mixed
TimeoutStopSec=30
UMask=0077
NoNewPrivileges=true
{hardening}
[Install]
WantedBy=default.target
"""

# Only directives that work in a systemd *user* service: no capability-bounding-set changes
# (PrivateDevices=, ProtectKernelModules= and similar fail there with 218/CAPABILITIES).
HARDENING = """ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths={state_dir}
PrivateTmp=true
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
RestrictNamespaces=true
LockPersonality=true
MemoryDenyWriteExecute=true
SystemCallArchitectures=native
SystemCallFilter=@system-service
"""

# With the agent set up the service starts `claude`, which writes in your home, uses a JIT and runs
# the commands you allow: only directives that do not get in its way.
AGENT_HARDENING = """ProtectSystem=full
ProtectKernelTunables=true
ProtectControlGroups=true
LockPersonality=true
"""


def _q(value) -> str:
    """Quote one ExecStart argument (systemd also expands % specifiers inside quotes)."""
    return json.dumps(str(value).replace("%", "%%"))


def _path(value) -> str:
    """WorkingDirectory= takes a bare absolute path: no quotes, only % escaped."""
    return str(value).replace("%", "%%")


def render_unit(config_path: Path, state_dir: Path, minimal: bool, agent: bool = False) -> str:
    exec_start = " ".join(_q(v) for v in (sys.executable, "-m", "bridge.cli", "--config", config_path.resolve(), "run"))
    hardening = "" if minimal else AGENT_HARDENING if agent else HARDENING.format(state_dir=_q(state_dir))
    return UNIT_TEMPLATE.format(exec_start=exec_start, hardening=hardening,
                                workdir=_path(Path(__file__).resolve().parent.parent))


def _systemctl(*args: str, check: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(["systemctl", "--user", *args], check=check, capture_output=True, text=True)


def _start_and_check(dest: Path, unit: str, settle_s: float = 4.0) -> tuple[bool, str]:
    """Write the unit, (re)start it and report whether it stays up without restarting."""
    dest.write_text(unit)
    _systemctl("daemon-reload", check=True)
    _systemctl("enable", UNIT_NAME, check=True)
    _systemctl("restart", UNIT_NAME)
    time.sleep(settle_s)
    state = _systemctl("is-active", UNIT_NAME).stdout.strip()
    restarts = _systemctl("show", "-p", "NRestarts", "--value", UNIT_NAME).stdout.strip()
    return state == "active" and restarts in ("0", ""), f"{state}, restarts={restarts or '?'}"


def unit_path() -> Path:
    return Path.home() / ".config/systemd/user" / UNIT_NAME


def runs_config(config_path: Path, unit: Path | None = None) -> bool:
    """Whether the installed unit runs this very config: its ExecStart names the file, as render_unit writes it."""
    try:
        text = (unit or unit_path()).read_text()
    except OSError:
        return False
    return f"{_q('--config')} {_q(Path(config_path).resolve())}" in text


def _service() -> dict[str, str] | None:
    """The systemd unit's state, or None without systemctl or without the unit."""
    if not shutil.which("systemctl"):
        return None
    out = _systemctl("show", UNIT_NAME, "-p", "LoadState", "-p", "ActiveState", "-p", "SubState",
                     "-p", "NRestarts", "-p", "ActiveEnterTimestamp").stdout
    info = dict(line.split("=", 1) for line in out.splitlines() if "=" in line)
    return None if info.get("LoadState") in (None, "not-found") else info
