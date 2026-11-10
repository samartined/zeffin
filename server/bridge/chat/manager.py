# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The conversation manager: one agent process per turn, one turn at a time on the server.

- A conversation is an agent session whose id the server chooses (the first turn starts it, later ones resume it).
- Each turn's output becomes numbered events, kept in the history and sent to the app; text being
  written goes out as deltas that may be dropped.
- In Manual mode, the agent's permission request waits on the loopback listener until the
  owner answers or the deadline passes.
- Each conversation has its agent, reached only through its port (bridge/agent.py). The composition root builds every
  agent the server knows (app.py: Claude Code, chat/claude_code.py, where its subscription guard lives); hello and
  chat.status list them, each with what it offers.

ChatManager is a facade over the pieces (docs/architecture.md, "The chat, split"): ChatBus emits (bus.py), TurnSlot
keeps one turn at a time (slot.py), PermissionBroker holds permissions (broker.py), TurnRunner runs a `-p` turn
(turn.py), RemoteRunner the conversation in Remote Control (remote_runner.py), and ChatService keeps the conversations
and saves the index (service.py). The facade wires them and routes each request.

Logs carry short ids, outcomes and times: never message texts or tokens.
"""
from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any, Sequence

from ..agent import Agent
from ..config import Config
from ..wire import Err, ProtocolError, RemoteOff
from . import attachments
from .broker import PermissionBroker
from .bus import ChatBus, Listener
from .errors import ChatError as ChatError
from .errors import HookRejected as HookRejected
from .history import History
from .index import Conversation, Index
from .ports import DevicePorts
from .remote_runner import RemoteRunner, RemoteSession
from .service import MAX_TEXT, ChatService
from .tasks import Tasks
from .turn import Turn as Turn
from .turn import TurnRunner


class ChatManager:
    def __init__(self, config: Config, hub: Any = None, state_dir: Path | None = None, *, agent: Agent | None = None,
                 agents: Sequence[Agent] = ()):
        """`agents`: every agent the server knows, behind its port, built by the composition root (app.py), in the
        order /app lists them; `agent` alone stands for a list of one. The first has the hooks of a -p turn's
        permissions and the interactive session (Claude Code)."""
        listed = [agent] if agent is not None else list(agents)
        if not listed:
            raise ValueError("ChatManager needs an agent")
        self.config = config
        self.agent = listed[0]
        self.agents = listed
        self.hub = hub  # kept for the existing tests; the pieces only see the two ports
        self.ports = DevicePorts.of(hub)
        self.tasks = Tasks()
        self.bus = ChatBus(History(state_dir / "conversations" if state_dir else None))
        self.store = attachments.Store(state_dir)
        self.service = ChatService(config, listed, state_dir, self.bus, self.store, self.tasks,
                                   status_changed=self._emit_status)
        hooks = next((a for a in listed if a.hook_routes() is not None), self.agent)
        interactive = next((a for a in listed if a.remote is not None), self.agent)
        self.slot = self.service.slot
        self.broker = PermissionBroker(hooks, self.bus, turn_for=lambda token: self.turns.turn_for(token),
                                       timeout_s=lambda: self.service.index.settings["permission_timeout_s"],
                                       changed=self.service.changed, status_changed=self._emit_status)
        self.turns = TurnRunner(self.agent_for, self.store, self.bus, self.broker, self.slot, self.ports, self.tasks,
                                changed=self.service.changed, purge=self.service.purge,
                                settings=lambda: self.service.index.settings)
        self.remotes = RemoteRunner(interactive, self.store, self.bus, self.slot, self.ports, self.tasks, max_text=MAX_TEXT,
                                    changed=self.service.changed, purge=self.service.purge,
                                    status_changed=self._emit_status)

    # ------------------------------------------------------------------ lifecycle
    async def start(self) -> None:
        self.service.start()

    async def stop(self) -> None:
        """Answer every held permission and end the turn, before the listeners shut down."""
        self.slot.stopping = True
        # Both at once, inside the server's shutdown budget (app.GRACEFUL_SHUTDOWN_S).
        await asyncio.gather(self.remotes.stop(), self.turns.stop())
        self.tasks.cancel_all()
        self.service.save()

    async def check_claude(self) -> None:
        await self.service.check_agent()

    # ------------------------------------------------------------------ state for the app and zf status
    @property
    def problem(self):
        """Why no turn can start now (a Problem), or None: the service's."""
        return self.service.problem

    @problem.setter
    def problem(self, check) -> None:  # kept for the existing tests: they stand in for every agent's readiness check
        self.service.problem = lambda agent=None: check()  # type: ignore[method-assign]

    def agent_for(self, c: Conversation) -> Agent:
        """The conversation's agent (the service checked it is one the server knows before its turn)."""
        return self.service.agents[c.agent]

    def agent_entries(self) -> list[dict]:
        """hello's and chat.status's agents: each agent with its state now and what it offers. Attachments with the
        remote session are offered only when this server's attachment paths can be typed (RemoteRunner.can_attach)."""
        entries = []
        for agent in self.agents:
            problem = self.service.problem(agent.id)
            caps = agent.capabilities()
            remote = caps.get("remote")
            if isinstance(remote, dict) and remote.get("attachments") and agent is self.remotes.agent:
                remote = {**remote, "attachments": self.remotes.can_attach()}
            identity = agent.identity()
            entries.append({"id": agent.id, "name": identity["name"], "version": identity["version"],
                            "configured": agent.configured, "ready": problem is None, "problem": problem,
                            "login": agent.login(), "models": self.service.catalogs[agent.id],
                            "mode_options": agent.mode_options(), "remote": remote,
                            "attachments": bool(caps.get("attachments"))})
        return entries

    def status(self) -> dict:
        return {"agents": self.agent_entries(), "settings": self.service.settings(), "turn": self.slot.info(),
                "pending": self.broker.pending(), "remote": self.remotes.info(), "free_only": self.free_only}

    @property
    def free_only(self) -> bool:
        """The owner's "free only" switch (config.free_only): the app words what it promises by it."""
        return self.config.free_only is True

    def summary(self) -> dict:
        """The chat block of GET /status, for zf status."""
        return {"configured": bool(self.config.agent_dir), "problem": self.problem(),
                "model": self.service.defaults(self.agent.id)["default_model"], "agent_dir": self.config.agent_dir or None,
                "turn": self.slot.info(), "last_turn_at": self.slot.last_turn_at, "remote": self.remotes.info(),
                "conversations": len(self.service.index.items), "free_only": self.free_only,
                **self.agent.summary_fields(),
                "agents": [self._agent_summary(agent) for agent in self.agents]}

    def _agent_summary(self, agent: Agent) -> dict:
        """Each agent in GET /status's chat: who it is, whether it is set up and ready, its login and its default
        model (zf status prints every agent but the first, which keeps its own line)."""
        identity = agent.identity()
        return {"id": agent.id, "name": identity["name"], "version": identity["version"],
                "configured": agent.configured, "problem": self.service.problem(agent.id),
                "login": agent.login()["method"], "model": self.service.defaults(agent.id)["default_model"]}

    def capabilities(self) -> dict:
        """hello's blocks of the chat, for appws: every agent the server knows."""
        return {"agents": self.agent_entries(), "free_only": self.free_only}

    def hook_routes(self) -> tuple[str, dict, tuple[str, ...]]:
        """For app.hooks_app: the header that says another device is connected, the -p hooks and their handlers,
        and the Remote Control hook events (the first agent that has hooks: Claude Code)."""
        routes = self.broker.agent.hook_routes()
        if routes is None:  # no agent has hooks: no route
            return "", {}, ()
        return (routes.header, {routes.request: self.permission_request, routes.denied: self.permission_denied},
                routes.remote)

    def _emit_status(self) -> None:
        self.bus.status(self.status())

    # ------------------------------------------------------------------ listeners
    def listen(self) -> Listener:
        return self.bus.listen()

    def unlisten(self, listener: Listener) -> None:
        self.bus.unlisten(listener)

    # ------------------------------------------------------------------ requests from the app
    def conversations(self) -> list[dict]:
        return self.service.conversations()

    def new(self, mode: object = None, model: object = None, effort: object = None, agent: object = None) -> dict:
        return self.service.new(mode, model, effort, agent)

    def page(self, conversation_id: object, before: object = None, after: object = None, limit: object = 200) -> dict:
        return self.service.page(conversation_id, before, after, limit)

    def upload(self, conversation_id: object, name: object, media_type: str, data: bytes) -> dict:
        """POST /app/upload: store one attachment for the conversation's next message."""
        return self.service.upload(conversation_id, name, media_type, data)

    def attachment(self, conversation_id: object, ident: object) -> tuple[dict, Path] | None:
        """GET /app/attachments/<conversation>/<id>: the file and its metadata, or None."""
        return self.service.attachment(conversation_id, ident)

    async def recheck(self, conversation_id: object) -> None:
        """Before chat.send: an agent refused for its settings or version is looked at again (ChatService.recheck)."""
        await self.service.recheck(conversation_id)

    def send(self, conversation_id: object, text: object, attached: object = None) -> None:
        c = self.service.get(conversation_id)
        if attached is None:
            attached = []
        if not isinstance(text, str) or not (text.strip() or attached):
            raise ProtocolError(Err.BAD_REQUEST)
        if self.remotes.holds(c):
            return self.remotes.send(c, text, attached)
        metas, effort = self.service.check_send(c, text, attached, self.turns)
        self.turns.start(c, text, metas, effort)

    def interrupt(self, conversation_id: object) -> None:
        c = self.service.get(conversation_id)
        if self.remotes.holds(c):
            self.remotes.interrupt()
            return
        self.turns.interrupt(c)

    def delete(self, conversation_id: object) -> None:
        """chat.delete: the conversation goes for good, with its history, its attachments and Claude Code's own
        files for the session. A running turn in it is stopped first and the deletion follows its end."""
        c = self.service.get(conversation_id)
        if self.remotes.holds(c):
            self.remotes.session.delete_after = True
            self.remotes.off(c, reason=RemoteOff.DELETED)
            return
        turn = self.turns.turn
        if turn is not None and turn.conversation is c:
            turn.delete_after = True
            if not turn.interrupted:
                self.interrupt(c.id)
            return
        self.service.purge(c)

    def configure(self, conversation_id: object, changes: dict) -> None:
        """chat.set: the conversation's mode, model and effort, from its next turn; new conversations start with them."""
        c = self.service.get(conversation_id)
        self.service.configure(c, changes, in_remote=self.remotes.holds(c))

    def update(self, conversation_id: object, changes: dict) -> None:
        self.service.update(conversation_id, changes)

    def set_settings(self, changes: dict) -> None:
        self.service.set_settings(changes)

    def answer(self, hold_id: object, answer: object, rules: object = None) -> None:
        self.broker.answer(hold_id, answer, rules)

    # ------------------------------------------------------------------ Remote Control (remote_runner.py)
    def remote_on(self, conversation_id: object) -> None:
        """chat.remote on: the conversation goes on in an interactive process that other devices can write to."""
        c = self.service.get(conversation_id)
        if self.service.agents.get(c.agent) is not self.remotes.agent or self.remotes.remote is None:
            raise ChatError(Err.REMOTE_UNSUPPORTED)
        session = self.remotes.session
        if session is not None:
            if session.conversation is c:
                return
            raise ChatError(Err.REMOTE_BUSY, conversation=session.conversation.id)
        effort = self.service.check_remote_on(c, self.remotes)
        self.remotes.on(c, effort)

    def remote_off(self, conversation_id: object, reason: str = RemoteOff.OFF) -> None:
        """chat.remote off: Esc for a running turn, /exit, then signals; the end is handled when the process exits."""
        self.remotes.off(self.service.get(conversation_id), reason)

    async def remote_hook(self, event: str, token: str, connected: bool, body: dict) -> dict:
        """The Remote Control process's hooks (/hooks/remote/<event>), authenticated by its own token."""
        return await self.remotes.hook(event, token, connected, body)

    # ------------------------------------------------------------------ the -p turn's hooks (broker.py)
    async def permission_request(self, token: str, body: dict) -> dict:
        return await self.broker.request(token, body)

    async def permission_denied(self, token: str, body: dict) -> dict:
        return await self.broker.denied(token, body)

    # ------------------------------------------------------------------ kept for the existing tests
    # These reach into the pieces for tests written before the split. Nothing new builds on them; they go in the
    # next phase, with the tests that use them.
    @property
    def index(self) -> Index:
        return self.service.index

    @property
    def history(self) -> History:
        return self.bus.history

    @property
    def turn(self) -> Turn | None:
        return self.turns.turn

    @turn.setter
    def turn(self, turn: Turn | None) -> None:
        self.turns.turn = turn

    @property
    def remote(self) -> RemoteSession | None:
        return self.remotes.session

    @remote.setter
    def remote(self, session: RemoteSession | None) -> None:
        self.remotes.session = session

    @property
    def holds(self) -> dict:
        return self.broker.holds

    @property
    def models(self) -> list[dict]:
        return self.service.models

    @models.setter
    def models(self, models: list[dict]) -> None:
        self.service.models = models

    def _screen(self, seq: int) -> dict | None:
        return self.ports.screen(seq)

    async def _remote_watch(self, session: RemoteSession) -> None:
        await self.remotes.watch(session)
