# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""One turn per process: start it through the agent, turn its events into the conversation's, and end it.

The runner changes the Conversation it was given and reports through the callbacks the facade wires: it never saves
the index nor emits except through the bus. One turn at a time on the server is the slot's (slot.py). The agent is
reached only through its port (bridge/agent.py).

A model whose provider caps the images per request (Agent.image_limit) loses its conversation once the agent's
session holds more: the agent sends the whole session each time. The runner counts the images the session holds (the
message's and the tools' results) and has the agent compact it, which drops them: before a turn that starts with half
the limit or more, and after a turn that failed past the limit, which then ends with image_limit.
"""
from __future__ import annotations

import asyncio
import contextlib
import hmac
import logging
import secrets
import time
from dataclasses import dataclass, field, replace
from typing import Callable

from ..agent import (Agent, AgentTurn, Delta, Denied, Limit, Output, PermissionAnswer, PermissionAsk, Started,
                     TurnRequest)
from ..events import Ev
from ..text import one_line
from ..wire import Answer, DeniedBy, Guard, Kind, Notice, Outcome, TurnError, notice, turn_end
from . import attachments
from .broker import PermissionBroker
from .bus import ChatBus
from .errors import HookRejected
from .index import LAST_CHARS, TITLE_CHARS, Conversation
from .ports import DevicePorts
from .slot import TurnSlot
from .tasks import Tasks

log = logging.getLogger("zeffin.chat")

DELTA_EVERY_S = 0.1
COMPACT_S = 180.0  # a summary took 9 to 65 s on NIM's free tier
INTERRUPT_WAITS = (10.0, 5.0)
STOP_WAITS = (3.0, 2.0)


@dataclass
class Turn:
    conversation: Conversation
    number: int
    mode: str
    model: str
    effort: str | None
    token: str
    started: float
    resumed: bool
    run: AgentTurn | None = None  # the agent's process, once started
    session_id: str | None = None
    interrupted: bool = False
    stopping: bool = False
    guard: str | None = None
    reported: set = field(default_factory=set)  # tool_use ids already shown as denied
    task: asyncio.Task | None = None
    attachments: list[dict] = field(default_factory=list)  # their stored metadata, in the message's order
    delete_after: bool = False  # the owner deleted the conversation while this turn ran: it goes when the turn ends
    image_limit: dict | None = None  # it failed past its model's image limit: image_limit's data


def compact_from(limit: int) -> int:
    """How many images a session may hold before a turn starts without compacting it first: under half the limit, so
    every turn has at least half of it (a measurement saw 5 to 14 images in a task's turn)."""
    return (limit + 1) // 2


class TurnRunner:
    def __init__(self, agent_for: Callable[[Conversation], Agent], store: attachments.Store, bus: ChatBus,
                 broker: PermissionBroker, slot: TurnSlot, ports: DevicePorts, tasks: Tasks, *,
                 changed: Callable[..., None], purge: Callable[[Conversation], None],
                 settings: Callable[[], dict]) -> None:
        self.agent_for = agent_for  # the conversation's agent
        self.store = store
        self.bus = bus
        self.broker = broker
        self.slot = slot
        self.ports = ports
        self.tasks = tasks
        self.changed = changed  # ChatService.changed: saves the index, sends the conversation and the status
        self.purge = purge  # ChatService.purge: a conversation deleted while its turn ran goes once it ends
        self.settings = settings  # the index's settings (the permission timeout)
        self.turn: Turn | None = None
        slot.register(self)

    # ---- the slot's owner
    def turn_conversation(self) -> str | None:
        return self.turn.conversation.id if self.turn is not None else None

    def turn_info(self) -> dict | None:
        t = self.turn
        if t is None:
            return None
        return {"conversation": t.conversation.id, "agent": t.conversation.agent, "turn": t.number, "mode": t.mode, "model": t.model,
                "effort": t.effort, "started_at": t.started, "waiting": self.broker.waiting(t)}

    def turn_for(self, token: str) -> Turn:
        turn = self.turn
        if turn is None or not token or not hmac.compare_digest(token.encode(), turn.token.encode()):
            raise HookRejected()
        return turn

    # ---- requests
    def start(self, c: Conversation, text: str, metas: list[dict], effort: str | None) -> None:
        """Start a turn the service has checked (and the slot allowed)."""
        text = text.replace("\x00", "")
        shown = text.strip() or ", ".join(m["name"] for m in metas)
        if not c.title:
            c.title = one_line(shown, TITLE_CHARS)
        c.turns += 1
        c.open_turn = True
        c.updated = time.time()
        c.last = one_line(shown, LAST_CHARS)
        turn = Turn(conversation=c, number=c.turns, mode=c.mode, model=c.model, effort=effort,
                    token=secrets.token_urlsafe(32), started=time.time(), resumed=c.state == "ready", attachments=metas)
        self.turn = turn
        c.attachments += sum(1 for m in metas if not m["sent"])
        self.store.mark_sent(c.id, metas)
        self.bus.event(c, {"kind": Kind.USER, "text": text, **({"attachments": [attachments.public(m) for m in metas]}
                                                              if metas else {})})
        self.bus.event(c, {"kind": Kind.TURN_START, "turn": turn.number, "mode": turn.mode, "model": turn.model,
                           "effort": turn.effort})
        self.changed(c)
        turn.task = self.tasks.spawn(self._run(turn, text))

    def interrupt(self, c: Conversation) -> None:
        turn = self.turn
        if turn is None or turn.conversation is not c:
            return
        turn.interrupted = True
        self.tasks.spawn(self._end_process(turn, Answer.INTERRUPTED, INTERRUPT_WAITS))

    async def stop(self) -> None:
        turn = self.turn
        if turn is not None:
            turn.stopping = True
            await self._end_process(turn, Answer.STOPPING, STOP_WAITS)
            if turn.task is not None:
                with contextlib.suppress(asyncio.TimeoutError, asyncio.CancelledError, Exception):
                    await asyncio.wait_for(asyncio.shield(turn.task), 3)

    # ---- the process
    async def _end_process(self, turn: Turn, answer: str, waits: tuple[float, float]) -> None:
        self.broker.resolve(turn, answer)
        if turn.run is not None:
            await turn.run.end(waits)

    def _kill_now(self, turn: Turn, reason: str) -> None:
        turn.guard = reason
        log.warning("chat %s: subscription guard (%s): turn killed", turn.conversation.id[:8], reason)
        self.broker.resolve(turn, Answer.STOPPING)
        if turn.run is not None:
            turn.run.kill()

    def _request(self, turn: Turn, text: str) -> TurnRequest:
        """What the agent gets: the text with the other files named by path, the images, the attachments' folder."""
        c = turn.conversation
        files = [(m, self.store.path(c.id, m)) for m in turn.attachments if m["kind"] != "image"]
        if files:
            text = (text + "\n\n" if text.strip() else "") + attachments.file_lines(files)
        async def ask(request: PermissionAsk) -> PermissionAnswer:
            # A request from inside the agent's protocol: only while this turn is the running one.
            if self.turn is not turn:
                return PermissionAnswer(Answer.INTERRUPTED)
            return await self.broker.ask(turn, request)

        return TurnRequest(
            session=c.agent_session or c.id, resume=turn.resumed, ask=ask, model=turn.model, effort=turn.effort, mode=turn.mode, rules=list(c.rules),
            permission_timeout_s=self.settings()["permission_timeout_s"], text=text,
            images=[(m["media_type"], self.store.path(c.id, m)) for m in turn.attachments if m["kind"] == "image"],
            folders=[str(self.store.folder(c.id))] if turn.attachments else [], token=turn.token)

    async def _run(self, turn: Turn, text: str) -> None:
        c = turn.conversation
        saw_init = False
        last_text = None
        log.info("chat %s: turn %d started (%s)", c.id[:8], turn.number, turn.mode)
        try:
            agent = self.agent_for(c)
            request = self._request(turn, text)
            limit = agent.image_limit(turn.model)
            if not turn.resumed:
                c.images = 0  # a new session holds none of the old one's images
            if limit and turn.resumed and c.images >= compact_from(limit):
                await self._compact(turn, agent, request)
                if turn.interrupted or turn.stopping:
                    return
            c.images += len(request.images)
            turn.run = run = await agent.start_turn(request)
            pending: dict[str, list] = {}
            flushed = time.monotonic()

            def flush():
                nonlocal flushed
                for block, (offset, chunk) in pending.items():
                    self.bus.delta(c, turn.number, block, offset, chunk)
                pending.clear()
                flushed = time.monotonic()

            async for events in run.lines():
                for out in events:
                    if isinstance(out, Delta):
                        if out.block in pending:
                            pending[out.block][1] += out.text
                        else:
                            pending[out.block] = [out.offset, out.text]
                        continue
                    flush()
                    if isinstance(out, Started):
                        saw_init = True
                        self._on_init(turn, out)
                    elif isinstance(out, Output):
                        event = out.event
                        if out.flipper:
                            c.flipper = True
                        if out.seq is not None:
                            event["screen"] = self.ports.screen(out.seq)
                        if event["kind"] == Kind.TEXT:
                            last_text = event["text"]
                        elif event["kind"] == Kind.TOOL_RESULT:
                            c.images += event.get("images", 0)
                        self.bus.event(c, event)
                    elif isinstance(out, Denied):
                        self._denied(turn, out)
                    elif isinstance(out, Limit):
                        self._kill_now(turn, Guard.USAGE_EXHAUSTED)
                if pending and time.monotonic() - flushed >= DELTA_EVERY_S:
                    flush()
                if turn.guard:
                    break
            flush()
            if not await run.wait(10):
                await self._end_process(turn, Answer.STOPPING, (1.0, 1.0))
            for denial in await run.drain():
                self._denied(turn, denial)
            end = run.outcome()
            if limit and end.error == TurnError.API_ERROR and c.images > limit and not (
                    turn.interrupted or turn.stopping or turn.guard):
                # Past the limit every request fails, this turn's and the next ones': the summary frees the session
                log.warning("chat %s: turn %d failed with %d images, past its model's %d: %s", c.id[:8], turn.number,
                            c.images, limit, one_line(str(end.data.get("reason")), 120))
                images = c.images
                turn.image_limit = {"images": images, "limit": limit,
                                    "compacted": await self._compact(turn, agent, request)}
                turn.run = run  # the turn's outcome stays its own
        except Exception:  # never leave the turn open
            log.exception("chat %s: turn failed", c.id[:8])
        finally:
            self.broker.resolve(turn, Answer.INTERRUPTED)
            self._finish(turn, saw_init, last_text)

    async def _compact(self, turn: Turn, agent: Agent, request: TurnRequest) -> bool:
        """Have the agent replace the session's history with a summary (Agent.compact): its own turn, whose events are
        not shown; the conversation gets the compacted notice. False when it could not, was stopped or took too long."""
        c = turn.conversation

        async def deny(_: PermissionAsk) -> PermissionAnswer:
            return PermissionAnswer(Answer.DENY)  # a summary asks for nothing: anything else is refused

        run = await agent.compact(replace(request, resume=True, ask=deny))
        if run is None:
            return False
        turn.run = run  # an interrupt or a stop ends it as it would the turn
        try:
            async with asyncio.timeout(COMPACT_S):
                async for events in run.lines():
                    if any(isinstance(e, Started) and e.guard for e in events):
                        run.kill()  # the guards hold for it as for any turn
                        return False
                await run.wait(10)
        except TimeoutError:
            await run.end(STOP_WAITS)
            log.warning("chat %s: compacting took over %d s: stopped", c.id[:8], COMPACT_S)
            return False
        end = run.outcome()
        if end.outcome != Outcome.DONE or turn.interrupted or turn.stopping:
            log.warning("chat %s: compacting failed: %s %s", c.id[:8], end.outcome, end.error or "")
            return False
        log.info("chat %s: compacted: %d images dropped", c.id[:8], c.images)
        c.images = 0
        self.bus.event(c, notice(Notice.COMPACTED))
        return True

    def _on_init(self, turn: Turn, init: Started) -> None:
        c = turn.conversation
        turn.session_id = init.session
        if init.session and init.session != (c.agent_session or c.id):
            # another session than the one asked for holds only this message's images
            c.images = sum(1 for m in turn.attachments if m["kind"] == "image")
        if init.session and init.session != c.id:
            c.agent_session = init.session  # the agent names its own sessions: resumed by that name
        if init.guard:
            return self._kill_now(turn, init.guard)
        c.state = "ready"
        # The agent may not report the effort (zf agent probe): show the one asked for.
        self.bus.event(c, {"kind": Kind.TURN_INFO, "mode": init.mode, "model": init.model,
                           "effort": init.effort or turn.effort, "version": init.version, "flipper_mcp": init.mcp})
        if turn.mode == "auto" and init.mode != "auto":
            self.bus.event(c, notice(Notice.AUTO_UNAVAILABLE, {"mode": init.mode}))
        if not init.mcp_ok:
            self.bus.event(c, notice(Notice.FLIPPER_MCP_FAILED, {"status": init.mcp}))

    def _denied(self, turn: Turn, denied: Denied) -> None:
        if denied.tool_use is not None:
            if denied.tool_use in turn.reported:
                return
            turn.reported.add(denied.tool_use)
        if denied.by_hook:
            return  # the owner's own answer (or its timeout): permission.end already shows it
        self.bus.event(turn.conversation, {"kind": Kind.DENIED, "tool": denied.tool, "input": denied.input,
                                           "by": DeniedBy.AUTO if turn.mode == "auto" else DeniedBy.MODE,
                                           "reason": denied.reason})

    def _finish(self, turn: Turn, saw_init: bool, last_text: str | None) -> None:
        c = turn.conversation
        error, data = None, {}
        if turn.guard:
            outcome, error, data = Outcome.ERROR, TurnError.BILLING_GUARD, {"why": turn.guard}
        elif turn.stopping:
            outcome, error = Outcome.INTERRUPTED, TurnError.STOPPING
        elif turn.interrupted:
            outcome = Outcome.INTERRUPTED
        elif turn.run is None:
            outcome = Outcome.ERROR  # it failed before the agent was asked
            error, data = TurnError.AGENT_FAILED, {"exit": None, "subtype": None}
        else:
            end = turn.run.outcome()
            outcome, error, data = end.outcome, end.error, end.data
            if error == TurnError.EXPIRED:
                c.state = "expired"
                c.images = 0  # the agent no longer has the session, nor its images
            if end.note:
                log.warning("chat %s: turn %d %s", c.id[:8], turn.number, end.note)
            if turn.image_limit is not None:
                error, data = TurnError.IMAGE_LIMIT, turn.image_limit
        duration_ms = round((time.time() - turn.started) * 1000)
        self.bus.event(c, turn_end(turn.number, outcome, error, data, duration_ms))
        c.open_turn = False
        c.updated = time.time()
        if last_text:
            c.last = one_line(last_text, LAST_CHARS)
        self.turn = None
        self.slot.release(self)
        self.ports.metric({"ev": Ev.CHAT_TURN, "outcome": outcome, "error": error, "mode": turn.mode,
                           "duration_s": round(duration_ms / 1000, 1), "init": saw_init})
        log.info("chat %s: turn %d %s%s (%.1f s)", c.id[:8], turn.number, outcome, f" {error}" if error else "",
                 duration_ms / 1000)
        self.changed(c)
        if turn.delete_after:
            self.purge(c)
