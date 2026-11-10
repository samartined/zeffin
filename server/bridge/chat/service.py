# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The conversations and whether their agent can take a turn: the index and its settings, each agent's model catalog
and state (through its port, bridge/agent.py), the checks on each request, and the recovery when the server starts.

Each conversation has its agent; the models, modes and efforts a request may choose are that agent's.

Only the service saves the index (changed): the runners and the broker report to it.
"""
from __future__ import annotations

import asyncio
import contextlib
import logging
from pathlib import Path
from typing import Callable, Sequence

from ..agent import Agent
from ..config import Config
from ..text import one_line
from ..wire import (EFFORTS, Err, Notice, Outcome, ProtocolError, Problem, RemoteOff, TurnError, notice, turn_end,
                    valid_agent_id)
from . import attachments
from .attachments import AttachmentError
from .bus import ChatBus
from .errors import ChatError
from .index import EDITED_TITLE_CHARS, STARTING_EFFORT, Conversation, Index, clamp_timeout
from .slot import TurnOwner, TurnSlot
from .tasks import Tasks

log = logging.getLogger("zeffin.chat")

MAX_TEXT = 16000
CHECK_EVERY_S = 600
# Problems the owner fixes on the server (a setting, the agent's version): a message to an agent that has one makes the
# server look again first, so a fixed file works at once instead of after the next check.
FIXABLE = (Problem.UNSAFE_AGENT_SETTINGS, Problem.AGENT_VERSION)


class ChatService:
    def __init__(self, config: Config, agents: Sequence[Agent], state_dir: Path | None, bus: ChatBus,
                 store: attachments.Store, tasks: Tasks, *, status_changed: Callable[[], None]) -> None:
        """`agents`: every agent the server knows, in the order /app lists them; the first is a new index's default."""
        self.config = config
        self.agents: dict[str, Agent] = {a.id: a for a in agents}
        self.agent = agents[0]
        self.index = Index(state_dir / "conversations.json" if state_dir else None, default_model=config.chat_model)
        self.bus = bus
        self.store = store
        self.tasks = tasks
        self.status_changed = status_changed
        self.slot = TurnSlot()  # handed to both runners
        self._recheck = asyncio.Lock()  # recheck(): one agent check at a time
        self.catalogs: dict[str, list[dict]] = {a.id: a.models() for a in agents}

    @property
    def models(self) -> list[dict]:
        """The first agent's catalog (kept for the existing tests)."""
        return self.catalogs[self.agent.id]

    @models.setter
    def models(self, models: list[dict]) -> None:
        self.catalogs[self.agent.id] = models

    # ---- saving
    def changed(self, c: Conversation | None = None, *, status: bool = True) -> None:
        """Save the index, then send the conversation (when given) and the status (unless status=False)."""
        self.index.save()
        if c is not None:
            self.bus.conversation(c)
        if status:
            self.status_changed()

    def save(self) -> None:
        self.index.save()

    # ---- lifecycle
    def start(self) -> None:
        """Recovery when the server starts, then the check loop."""
        for c in self.index.items.values():
            c.attachments = self.store.count_sent(c.id)  # the store is the truth (and older indexes lack it)
            if c.open_turn:  # the server stopped in the middle of a turn
                c.open_turn = False
                self.bus.history.append(c, turn_end(c.turns, Outcome.ERROR, TurnError.SERVER_RESTARTED))
            if c.remote:  # Remote Control does not survive a restart: its process is gone
                c.remote = False
                self.bus.history.append(c, notice(Notice.REMOTE_OFF, {"reason": RemoteOff.SERVER_RESTARTED}))
        self.index.prune_empty()
        self.index.save()
        if any(a.configured for a in self.agents.values()):
            self.tasks.spawn(self._check_loop())

    async def _check_loop(self) -> None:
        while True:
            await self.check_agent()
            with contextlib.suppress(OSError):
                await asyncio.to_thread(self.store.sweep)
            await asyncio.sleep(CHECK_EVERY_S)

    async def check_agent(self) -> None:
        """Look at every configured agent again: its version, its login and its models."""
        for agent in self.agents.values():
            if agent.configured:
                await agent.check()
                self.catalogs[agent.id] = agent.models()
        self.status_changed()

    async def recheck(self, conversation_id: object) -> None:
        """Before a message: an agent whose settings or version were refused is checked again (one check at a time),
        so the message goes through once the owner fixed it; any other problem waits for the next check."""
        c = self.index.get(conversation_id)
        agent = self.agents.get(c.agent) if c is not None else None
        if agent is None or not agent.configured or agent.problem() not in FIXABLE:
            return
        async with self._recheck:
            if agent.problem() in FIXABLE:
                await agent.check()
                self.catalogs[agent.id] = agent.models()
                self.status_changed()

    def problem(self, agent: str | None = None) -> str | None:
        """Why this agent (the first when None) cannot take a turn now, or None."""
        found = self.agents.get(agent or self.agent.id)
        return found.problem() if found is not None else Problem.NOT_CONFIGURED

    # ---- the agents
    def agent_of(self, value: object) -> Agent:
        """The agent a request named, or ProtocolError: bad_agent, with the ids the server knows."""
        found = self.agents.get(value) if valid_agent_id(value) else None  # type: ignore[arg-type]
        if found is None:
            raise ProtocolError(Err.BAD_AGENT, agents=list(self.agents))
        return found

    def default_agent(self) -> Agent:
        return self.agents.get(self.index.settings["default_agent"], self.agent)

    def modes(self, agent: str) -> list[str]:
        found = self.agents.get(agent)
        return [o["value"] for o in found.mode_options()] if found is not None else []

    def defaults(self, agent: str) -> dict:
        """Where this agent's new conversations start, as chat.status shows them: the kept defaults, with its first
        available model when none is kept, and its first mode when it does not offer the kept one."""
        kept = dict(self.index.defaults(agent))
        if not kept["default_model"]:
            offered = self.offered(agent)
            kept["default_model"] = offered[0] if offered else ""
        modes = self.modes(agent)
        if modes and kept["default_mode"] not in modes:
            kept["default_mode"] = modes[0]
        return kept

    def settings(self) -> dict:
        """chat.status's settings."""
        return {"default_agent": self.default_agent().id,
                "permission_timeout_s": self.index.settings["permission_timeout_s"],
                "agents": {agent: self.defaults(agent) for agent in self.agents}}

    # ---- the catalog
    def model(self, agent: str, value: object) -> dict | None:
        return next((m for m in self.catalogs.get(agent, []) if m["value"] == value), None)

    def offered(self, agent: str) -> list[str]:
        """The models a request may choose for this agent: its catalog's available ones."""
        return [m["value"] for m in self.catalogs.get(agent, []) if m["available"]]

    def offered_model(self, agent: str, value: object) -> dict:
        """The agent's catalog entry for a model a request chose, or ProtocolError: bad_model when the agent does not
        list it, model_unavailable when it does but may not take it (not covered by the subscription, or not free on
        a provider it is signed in to)."""
        entry = self.model(agent, value)
        if entry is None:
            raise ProtocolError(Err.BAD_MODEL, models=self.offered(agent))
        if not entry["available"]:
            raise ProtocolError(Err.MODEL_UNAVAILABLE, model=entry["value"], models=self.offered(agent))
        return entry

    def choice(self, agent: str, model: object, effort: object) -> tuple[str, str | None]:
        """A valid (model, effort) pair from the agent's catalog, or ProtocolError. The effort is always a level the
        model accepts; only a model with no levels at all has none."""
        entry = self.offered_model(agent, model)
        if (effort not in entry["efforts"]) if entry["efforts"] else (effort is not None):
            raise ProtocolError(Err.BAD_EFFORT, efforts=entry["efforts"])
        return entry["value"], effort

    @staticmethod
    def fit(entry: dict | None, effort: object) -> str | None:
        """The level to use with this model: the wanted one if it accepts it, else Medium, else its first."""
        levels = (entry or {}).get("efforts") or []
        if not levels:
            return None
        if effort in levels:
            return effort
        return STARTING_EFFORT if STARTING_EFFORT in levels else levels[0]

    def _turn_model(self, c: Conversation) -> str | None:
        """The effort for a turn of this conversation, or ChatError when its model is not offered now (its agent no
        longer lists it, or may not take it): no turn starts."""
        entry = self.model(c.agent, c.model)
        if entry is None or not entry["available"]:
            raise ChatError(Err.MODEL_UNAVAILABLE, model=c.model, models=self.offered(c.agent))
        return self.fit(entry, c.effort)

    # ---- checks before a turn
    def check_send(self, c: Conversation, text: str, attached: object, owner: TurnOwner) -> tuple[list[dict], str | None]:
        """A -p turn's message: its attachments' metadata and the effort, or ProtocolError (a malformed message) or ChatError."""
        try:
            metas = self.store.check(c.id, attached) if attached else []
        except AttachmentError as e:
            raise ChatError(e.code, ids=attached if isinstance(attached, list) else None) from None
        if len(text) > MAX_TEXT:
            raise ProtocolError(Err.TOO_LONG, max=MAX_TEXT)
        if c.state == "expired":
            raise ChatError(Err.EXPIRED)
        self.slot.claim(owner)
        problem = self.problem(c.agent)
        if problem:
            raise ChatError(Err.NOT_READY, problem=problem)
        return metas, self._turn_model(c)

    def check_remote_on(self, c: Conversation, owner: TurnOwner) -> str | None:
        """Remote Control for a conversation: the effort, or ChatError."""
        agent = self.agents.get(c.agent)
        if agent is None or agent.remote is None:
            raise ChatError(Err.REMOTE_UNSUPPORTED)
        if c.state == "expired":
            raise ChatError(Err.EXPIRED)
        if self.slot.other(owner) == c.id:
            raise ChatError(Err.BUSY, conversation=c.id)
        if self.slot.stopping:
            raise ChatError(Err.NOT_READY, problem=Problem.STOPPING)
        problem = self.problem(c.agent)
        if problem:
            raise ChatError(Err.NOT_READY, problem=problem)
        problem = agent.remote_problem()
        if problem:
            raise ChatError(Err.NOT_READY, problem=problem)
        return self._turn_model(c)

    # ---- conversations
    def get(self, conversation_id: object) -> Conversation:
        c = self.index.get(conversation_id)
        if c is None:
            raise ChatError(Err.NOT_FOUND)
        return c

    def conversations(self) -> list[dict]:
        return [c.summary() for c in self.index.ordered()]

    def new(self, mode: object = None, model: object = None, effort: object = None, agent: object = None) -> dict:
        """A conversation with an agent (the default one when None), from its defaults where the request names none."""
        found = self.agent_of(agent) if agent is not None else self.default_agent()
        defaults = self.defaults(found.id)
        if mode is not None and mode not in self.modes(found.id):
            raise ProtocolError(Err.BAD_MODE)
        model = model if model is not None else defaults["default_model"]
        if effort is None:  # not given: the default level, fitted to the model
            effort = self.fit(self.model(found.id, model), defaults["default_effort"])
        model, effort = self.choice(found.id, model, effort)
        c = self.index.new(mode if mode is not None else defaults["default_mode"], model, effort, agent=found.id)
        self.bus.conversation(c)
        return c.summary()

    def page(self, conversation_id: object, before: object = None, after: object = None, limit: object = 200) -> dict:
        c = self.get(conversation_id)
        ints = [v for v in (before, after) if v is not None]
        if any(not isinstance(v, int) or isinstance(v, bool) for v in ints) or not isinstance(limit, int):
            raise ProtocolError(Err.BAD_REQUEST)
        events, more = self.bus.history.page(c.id, before=before, after=after, limit=limit)
        return {"conversation": c.id, "events": events, "more": more}

    def upload(self, conversation_id: object, name: object, media_type: str, data: bytes) -> dict:
        c = self.get(conversation_id)
        return self.store.save(c.id, name, media_type, data)

    def attachment(self, conversation_id: object, ident: object) -> tuple[dict, Path] | None:
        try:
            c = self.get(conversation_id)
        except ChatError:
            return None
        meta = self.store.meta(c.id, ident)
        return (meta, self.store.path(c.id, meta)) if meta else None

    def purge(self, c: Conversation) -> None:
        self.index.items.pop(c.id, None)
        self.index.save()
        self.bus.history.drop(c.id)
        self.store.drop_conversation(c.id)
        agent = self.agents.get(c.agent)
        gone = agent.forget(c.agent_session or c.id) if agent is not None else 0
        log.info("chat %s: deleted (%d of the agent's paths)", c.id[:8], gone)
        self.bus.deleted(c.id)
        self.status_changed()

    def configure(self, c: Conversation, changes: dict, *, in_remote: bool) -> None:
        if in_remote and changes:
            raise ChatError(Err.REMOTE_SETTINGS)  # the running process keeps its own until Remote Control is off
        if "mode" in changes and changes["mode"] not in self.modes(c.agent):
            raise ProtocolError(Err.BAD_MODE)
        model = changes.get("model", c.model)
        effort = changes["effort"] if "effort" in changes else c.effort
        if "model" in changes and "effort" not in changes:
            effort = self.fit(self.model(c.agent, model), effort)  # a level the new model lacks becomes Medium
        if "model" in changes or "effort" in changes:
            c.model, c.effort = self.choice(c.agent, model, effort)
        if "mode" in changes:
            c.mode = changes["mode"]
        # What was last chosen in a conversation is where the agent's next new one starts (Settings, Agent shows it).
        defaults = self.index.defaults(c.agent)
        if "model" in changes:
            defaults["default_model"] = c.model
        if ("model" in changes or "effort" in changes) and c.effort is not None:
            defaults["default_effort"] = c.effort
        if "mode" in changes:
            defaults["default_mode"] = c.mode
        self.changed(c)

    def update(self, conversation_id: object, changes: dict) -> None:
        c = self.get(conversation_id)
        if "title" in changes:
            if not isinstance(changes["title"], str) or not one_line(changes["title"], EDITED_TITLE_CHARS):
                raise ProtocolError(Err.BAD_REQUEST)
            c.title = one_line(changes["title"], EDITED_TITLE_CHARS)
        for flag in ("pinned", "favorite"):
            if flag in changes:
                if not isinstance(changes[flag], bool):
                    raise ProtocolError(Err.BAD_REQUEST)
                setattr(c, flag, changes[flag])
        self.changed(c, status=False)

    def set_settings(self, changes: dict) -> None:
        """chat.settings: default_mode, default_model and default_effort are the defaults of "agent" (without it, of
        the default agent, after "default_agent" if given)."""
        if "default_agent" in changes:
            self.index.settings["default_agent"] = self.agent_of(changes["default_agent"]).id
        agent = self.agent_of(changes["agent"]).id if "agent" in changes else self.default_agent().id
        defaults = self.index.defaults(agent)
        if "default_model" in changes:
            defaults["default_model"] = self.offered_model(agent, changes["default_model"])["value"]
        if "default_effort" in changes:  # a level, never "the model's default"; fitted to each model when used
            if changes["default_effort"] not in EFFORTS:
                raise ProtocolError(Err.BAD_EFFORT, efforts=list(EFFORTS))
            defaults["default_effort"] = changes["default_effort"]
        if "default_mode" in changes:
            if changes["default_mode"] not in self.modes(agent):
                raise ProtocolError(Err.BAD_MODE)
            defaults["default_mode"] = changes["default_mode"]
        if "permission_timeout_s" in changes:
            value = changes["permission_timeout_s"]
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise ProtocolError(Err.BAD_REQUEST)
            self.index.settings["permission_timeout_s"] = clamp_timeout(value)
        self.changed()
