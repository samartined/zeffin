# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Configuration file: bind address, ports and four independent credentials."""
from __future__ import annotations

import ipaddress
import json
import os
import secrets
import sys
import tempfile
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path

from . import privnet


def install_base() -> Path:
    """The folder that holds the repo clone. The bridge keeps its data and the agent's folder there, next to the
    clone and outside its git tree, never as new folders in home. A package installed outside a clone uses the
    folder that holds its Python environment."""
    root = Path(__file__).resolve().parents[2]
    return root.parent if (root / "server" / "bridge").is_dir() else Path(sys.prefix).parent


BASE_DIR = install_base()
DATA_DIR = BASE_DIR / "zeffin-data"   # config.json (tokens) and state/ (logs, metrics, conversations)
DEFAULT_CONFIG = DATA_DIR / "config.json"
DEFAULT_STATE = DATA_DIR / "state"
DEFAULT_AGENT = BASE_DIR / "zeffin-agent"    # the folder the agents run in for chats (zf agent setup)

# Models included in the subscriptions. Fable and the [1m] variants can bill usage credits, and in
# `claude -p` that happens without asking (code.claude.com/docs/en/model-config), so they are not offered.
CHAT_MODELS = ("opus", "sonnet", "haiku")

# The file's format: save() writes it, and load() refuses a newer one, so an older server never rewrites a file it cannot
# read in full. A file without it is format 1.
# - 2: opencode_bin, the second agent's program; notes, the switch of the notes per app.
CONFIG_VERSION = 2

# The folders' names before 1.0.0 (flipper-bridge), each with its new name: a config.json moved by hand from an older
# install still names them (state_dir and agent_dir are saved resolved), and load() moves it to the new ones.
OLD_NAMES = {"flipper-bridge-data": DATA_DIR.name, "flipper-agent": DEFAULT_AGENT.name}

SOURCE_NAME = re.compile(r"[a-z0-9][a-z0-9_-]{0,31}")  # a profile name in profile_data/index.json


class ConfigError(Exception):
    pass


def check_public_host(host: str) -> str:
    """The phone/view listener may bind only to a private-network or loopback address (privnet)."""
    try:
        ip = ipaddress.ip_address(host)
    except ValueError as e:
        raise ConfigError(f"Host must be a literal IP address, got {host!r}") from e
    if not (ip.is_loopback or privnet.is_private(ip)):
        raise ConfigError(privnet.BIND_HINT)
    return str(ip)


@dataclass
class Config:
    host: str
    phone_port: int
    mcp_port: int
    phone_token: str
    mcp_token: str
    view_token: str
    state_dir: str
    keepalive_s: float = 30.0
    app_token: str = ""
    agent_dir: str = ""      # folder the agents run in for chats (zf agent setup); empty: chat not set up
    claude_bin: str = ""     # absolute path of the claude CLI: the service does not have the login PATH
    opencode_bin: str = ""   # absolute path of the opencode CLI (zf agent setup --agent opencode); empty: not set up
    chat_model: str = "opus"
    # The "free only" switch: on, the agents run only on a subscription or free providers; off, on whatever login
    # or provider the user set up. Set with zf set-free-only; never asked by install.sh.
    free_only: bool = False
    # Local checkouts of the firmwares' app sources, by profile name ("momentum": "/abs/path"), for flipper_app's
    # READMEs; optional, set with zf set-firmware-sources, never in the repository.
    firmware_sources: dict[str, str] = field(default_factory=dict)
    # Notes per app: the agent's notes on driving the Flipper's apps, kept in state/notes and handed back as
    # untrusted data. Off, flipper_note refuses and no note is handed back; the notes on disk stay. Set with zf notes.
    notes: bool = True

    def validate(self) -> "Config":
        check_public_host(self.host)
        for port in (self.phone_port, self.mcp_port):
            if not isinstance(port, int) or not 1 <= port <= 65535:
                raise ConfigError(f"Invalid port {port!r}")
        if self.phone_port == self.mcp_port:
            raise ConfigError("phone_port and mcp_port must differ")
        tokens = [self.phone_token, self.mcp_token, self.view_token, self.app_token]
        if any(not isinstance(t, str) or len(t) < 32 for t in tokens):
            raise ConfigError("All tokens must be strings of at least 32 characters")
        if len(set(tokens)) != 4:
            raise ConfigError("phone_token, mcp_token, view_token and app_token must be distinct")
        if not isinstance(self.free_only, bool):
            raise ConfigError("free_only must be true or false")
        if not isinstance(self.notes, bool):
            raise ConfigError("notes must be true or false")
        if self.chat_model not in CHAT_MODELS:
            raise ConfigError(f"chat_model must be one of {', '.join(CHAT_MODELS)}")
        for name in ("agent_dir", "claude_bin", "opencode_bin"):
            value = getattr(self, name)
            if value and not Path(value).is_absolute():
                raise ConfigError(f"{name} must be an absolute path")
        if not isinstance(self.firmware_sources, dict) or any(
                not isinstance(k, str) or not SOURCE_NAME.fullmatch(k) or not isinstance(v, str)
                or not Path(v).is_absolute() for k, v in self.firmware_sources.items()):
            raise ConfigError("firmware_sources must map firmware profile names to absolute paths")
        return self

    @property
    def state(self) -> Path:
        return Path(self.state_dir)

    def phone_url(self) -> str:
        host = f"[{self.host}]" if ":" in self.host else self.host
        return f"wss://{host}:{self.phone_port}/phone"

    def base_url(self) -> str:
        host = f"[{self.host}]" if ":" in self.host else self.host
        return f"https://{host}:{self.phone_port}"

    def app_url(self) -> str:
        host = f"[{self.host}]" if ":" in self.host else self.host
        return f"wss://{host}:{self.phone_port}/app"

    def view_url(self) -> str:
        host = f"[{self.host}]" if ":" in self.host else self.host
        return f"https://{host}:{self.phone_port}/view"

    def mcp_url(self) -> str:
        return f"http://127.0.0.1:{self.mcp_port}/mcp"


def detect_own_ip() -> str:
    try:
        return privnet.detect_own_ip()
    except privnet.Unavailable as e:
        raise ConfigError(str(e)) from e


def create(path: Path, host: str | None, phone_port: int, mcp_port: int, state_dir: Path | None) -> Config:
    if path.exists():
        raise ConfigError(f"{path} already exists; refusing to overwrite credentials")
    config = Config(
        host=check_public_host(host or detect_own_ip()),
        phone_port=phone_port,
        mcp_port=mcp_port,
        phone_token=secrets.token_urlsafe(32),
        mcp_token=secrets.token_urlsafe(32),
        view_token=secrets.token_urlsafe(32),
        app_token=secrets.token_urlsafe(32),
        state_dir=str((state_dir or DEFAULT_STATE).expanduser().resolve()),
    ).validate()
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(_on_disk(config), f, indent=2)
    return config


def load(path: Path) -> Config:
    if not path.exists():
        raise ConfigError(f"{path} not found; run 'zeffin init' first")
    mode = path.stat().st_mode & 0o777
    if mode & 0o077:
        raise ConfigError(f"{path} is readable by other users (mode {mode:o}); run chmod 600")
    with path.open() as f:
        data = json.load(f)
    version = data.get("version", 1)
    if not isinstance(version, int) or isinstance(version, bool) or version > CONFIG_VERSION:
        raise ConfigError(f"{path} was written by a newer zeffin (format {version!r}): update with zf update")
    known = {k: v for k, v in data.items() if k in Config.__dataclass_fields__}
    config = Config(**known)
    changed = False
    if not config.app_token:
        # Configs written before the app channel existed: add its token once, keep the others.
        config.app_token = secrets.token_urlsafe(32)
        changed = True
    for name in ("state_dir", "agent_dir"):
        moved = renamed(getattr(config, name))
        if moved is not None:
            setattr(config, name, moved)
            changed = True
    if changed:
        save(path, config)
    return config.validate()


def renamed(value: object) -> str | None:
    """A path that names a folder under its old name (flipper-bridge-data, flipper-agent), with the new name, when that
    path is a folder: the install was moved to the new names by hand (docs/guide/updating.md). None: keep it."""
    if not isinstance(value, str) or not value:
        return None
    parts = list(Path(value).parts)
    found = next((i for i, part in enumerate(parts) if part in OLD_NAMES), None)
    if found is None:
        return None
    parts[found] = OLD_NAMES[parts[found]]
    new = Path(*parts)
    return str(new) if new.is_dir() else None


def save(path: Path, config: Config) -> None:
    config.validate()
    fd, tmp = tempfile.mkstemp(prefix=".config-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(_on_disk(config), f, indent=2)
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def _on_disk(config: Config) -> dict:
    return {"version": CONFIG_VERSION, **asdict(config)}
