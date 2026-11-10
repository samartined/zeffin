# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The conversation index, state_dir/conversations.json: titles, flags, each conversation's agent, mode and "always"
rules, and the defaults of new conversations per agent.

The transcripts themselves belong to the agent (internal format, not read here); what the app shows comes from the
server's own history (history.py).
"""
from __future__ import annotations

import json
import os
import re
import tempfile
import time
import uuid
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path

from ..wire import EFFORTS, MODES, AgentId, normal_mode, valid_agent_id, valid_rule

# The file's format: save() writes it, and loading refuses a newer one, so an older server never rewrites conversations
# it cannot read in full. A file without it is format 1.
# - 2: each conversation's agent (and the agent's own session id, when it is not the conversation's id);
#   the settings keep the default agent and each agent's defaults. Format 1 is read as Claude Code's.
INDEX_VERSION = 2

_UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
TITLE_CHARS = 80
EDITED_TITLE_CHARS = 120
LAST_CHARS = 140
TIMEOUT_RANGE = (30, 570)
EMPTY_TTL_S = 24 * 3600
STARTING_EFFORT = "medium"  # where Claude Code starts the current models (code.claude.com/docs/en/model-config)
DEFAULT_AGENT = AgentId.CLAUDE_CODE
AGENT_DEFAULTS = ("default_mode", "default_model", "default_effort")


def valid_id(value: object) -> bool:
    return isinstance(value, str) and _UUID.fullmatch(value) is not None


@dataclass
class Conversation:
    id: str
    created: float
    updated: float
    title: str = ""
    last: str = ""
    pinned: bool = False
    favorite: bool = False
    flipper: bool = False
    agent: str = DEFAULT_AGENT  # a wire.AgentId
    mode: str = "default"      # one of wire.MODES that its agent offers: default (Manual), acceptEdits, plan, auto
    model: str = ""            # a value from its agent's catalog (for Claude Code an alias: opus, sonnet, haiku, fable)
    effort: str | None = STARTING_EFFORT  # one of EFFORTS; None only for a model with no effort levels
    rules: list[str] = field(default_factory=list)
    state: str = "new"          # new: no transcript yet; ready: the agent can resume it; expired: the agent deleted it
    turns: int = 0
    events: int = 0             # number of the last history event
    attachments: int = 0        # files sent in its messages
    open_turn: bool = False     # a turn started and has not ended (a crash leaves it set)
    remote: bool = False        # Remote Control is on (a server restart turns it off)
    agent_session: str | None = None  # the agent's own session id, when the agent names it (None: the conversation's)
    images: int = 0             # images the agent's session holds since its last compaction: its own, not /app's

    def summary(self) -> dict:
        data = asdict(self)
        data.pop("open_turn")
        data.pop("agent_session")
        data.pop("images")
        return data


def agent_defaults(mode: str = "default", model: str = "", effort: str = STARTING_EFFORT) -> dict:
    """Where an agent's new conversations start. model "": its first available model, chosen when used."""
    return {"default_mode": mode, "default_model": model, "default_effort": effort}


class IndexVersionError(Exception):
    """conversations.json was written by a newer server."""


class Index:
    def __init__(self, path: Path | None, default_model: str = "opus"):
        """`default_model`: Claude Code's, from the server's config."""
        self.path = path
        self.settings: dict = {"default_agent": DEFAULT_AGENT, "permission_timeout_s": 120,
                               "agents": {DEFAULT_AGENT: agent_defaults(model=default_model)}}
        self.items: dict[str, Conversation] = {}
        if path is not None and path.exists():
            self._load()

    def defaults(self, agent: str) -> dict:
        """The defaults of an agent's new conversations (kept, and saved with the index once changed)."""
        return self.settings["agents"].setdefault(agent, agent_defaults())

    def _load(self) -> None:
        data = json.loads(self.path.read_text())
        version = data.get("version", 1)
        if not isinstance(version, int) or isinstance(version, bool) or version > INDEX_VERSION:
            raise IndexVersionError(f"{self.path} was written by a newer zeffin (format {version!r}): "
                                    "update with zf update")
        settings = data.get("settings") or {}
        if isinstance(settings.get("permission_timeout_s"), (int, float)):
            self.settings["permission_timeout_s"] = clamp_timeout(settings["permission_timeout_s"])
        if version == 1:  # the defaults were Claude Code's
            self._load_defaults(DEFAULT_AGENT, settings)
        else:
            if valid_agent_id(settings.get("default_agent")):
                self.settings["default_agent"] = settings["default_agent"]
            agents = settings.get("agents")
            for agent, values in (agents.items() if isinstance(agents, dict) else ()):
                if valid_agent_id(agent) and isinstance(values, dict):
                    self._load_defaults(agent, values)
        names = {f.name for f in fields(Conversation)}
        for item in data.get("conversations") or []:
            if isinstance(item, dict) and valid_id(item.get("id")):
                c = Conversation(**{k: v for k, v in item.items() if k in names})
                if not valid_agent_id(c.agent):
                    continue
                c.rules = [r for r in c.rules if valid_rule(r)]
                c.mode = normal_mode(c.mode) or "default"  # the old "Flipper only" becomes Manual
                c.model = c.model if isinstance(c.model, str) and c.model else self.defaults(c.agent)["default_model"]
                c.effort = c.effort if c.effort in EFFORTS else STARTING_EFFORT  # no more "the model's default"
                c.agent_session = c.agent_session if isinstance(c.agent_session, str) and c.agent_session else None
                c.images = c.images if isinstance(c.images, int) and not isinstance(c.images, bool) and c.images > 0 \
                    else 0
                self.items[c.id] = c

    def _load_defaults(self, agent: str, values: dict) -> None:
        defaults = self.defaults(agent)
        if normal_mode(values.get("default_mode")):
            defaults["default_mode"] = normal_mode(values["default_mode"])
        if isinstance(values.get("default_model"), str) and values["default_model"]:
            defaults["default_model"] = values["default_model"]
        if values.get("default_effort") in EFFORTS:
            defaults["default_effort"] = values["default_effort"]

    def save(self) -> None:
        if self.path is None:
            return
        data = {"version": INDEX_VERSION, "settings": self.settings,
                "conversations": [asdict(c) for c in self.items.values()]}
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        fd, tmp = tempfile.mkstemp(prefix=".conversations-", dir=self.path.parent)
        try:
            with os.fdopen(fd, "w") as f:
                json.dump(data, f, ensure_ascii=False, indent=1)
            os.chmod(tmp, 0o600)
            os.replace(tmp, self.path)
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)

    def get(self, conversation_id: object) -> Conversation | None:
        return self.items.get(conversation_id) if valid_id(conversation_id) else None

    def new(self, mode: str | None = None, model: str | None = None, effort: str | None = None, *,
            agent: str = DEFAULT_AGENT) -> Conversation:
        """With a model, the effort is taken as given (the manager fits it to the model; None only for a model
        with no effort levels). Without one, the agent's defaults from the settings."""
        now = time.time()
        defaults = self.defaults(agent)
        c = Conversation(id=str(uuid.uuid4()), created=now, updated=now, agent=agent,
                         mode=mode if mode in MODES else defaults["default_mode"],
                         model=model or defaults["default_model"],
                         effort=(effort if effort in EFFORTS else None) if model else defaults["default_effort"])
        self.items[c.id] = c
        self.save()
        return c

    def ordered(self) -> list[Conversation]:
        return sorted(self.items.values(), key=lambda c: c.updated, reverse=True)

    def prune_empty(self, now: float | None = None) -> list[str]:
        """Drop conversations that never had a turn, a day after they were created."""
        now = time.time() if now is None else now
        gone = [c.id for c in self.items.values() if c.turns == 0 and not c.open_turn and now - c.created > EMPTY_TTL_S]
        for cid in gone:
            del self.items[cid]
        if gone:
            self.save()
        return gone


def clamp_timeout(value: float) -> int:
    return int(min(TIMEOUT_RANGE[1], max(TIMEOUT_RANGE[0], value)))
