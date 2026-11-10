# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The permissions held for a turn per process: the agent's permission request waits here until the owner answers in
the app, or the deadline passes. Also the hook that reports a tool the auto mode denied. The agent reads its hooks'
bodies and writes their answers (bridge/agent.py); who answers, until when and what "always" keeps are decided here.

A Remote Control turn's permission is answered on the other device, never here (remote_runner.py).
"""
from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

from ..agent import Agent, PermissionAnswer, PermissionAsk
from ..wire import Answer, DeniedBy, Err, Kind, ProtocolError
from .bus import ChatBus
from .errors import ChatError
from .index import Conversation

log = logging.getLogger("zeffin.chat")

ANSWERS = (Answer.ONCE, Answer.ALWAYS, Answer.DENY)  # the app's
MISMATCH = "mismatch"  # a request that does not belong to the running turn


@dataclass
class Hold:
    id: str
    turn: Any  # the turn.Turn it belongs to
    event: dict
    rules: list[str]
    future: asyncio.Future
    granted: list[str] = field(default_factory=list)  # with always: the offered rules the client showed
    whole: bool = True  # False: the card could not show the whole request, so only deny is taken


class PermissionBroker:
    def __init__(self, agent: Agent, bus: ChatBus, *, turn_for: Callable[[str], Any], timeout_s: Callable[[], float],
                 changed: Callable[..., None], status_changed: Callable[[], None]) -> None:
        self.agent = agent
        self.bus = bus
        self.turn_for = turn_for  # the running -p turn with this hook token, else HookRejected
        self.timeout_s = timeout_s  # the owner's permission timeout, from the settings
        self.changed = changed  # ChatService.changed: saves the index and sends the conversation
        self.status_changed = status_changed
        self.holds: dict[str, Hold] = {}

    def waiting(self, turn: Any) -> bool:
        return any(h.turn is turn for h in self.holds.values())

    def pending(self) -> list[dict]:
        return [h.event for h in self.holds.values()]

    def answer(self, hold_id: object, answer: object, shown: object = None) -> None:
        """`shown`: the rules the client showed with always. Only those it also offered are saved; with none of
        them, always allows once. A client that shows nothing cannot grant what the owner never saw."""
        hold = self.holds.get(hold_id) if isinstance(hold_id, str) else None
        if hold is None or hold.future.done():
            raise ChatError(Err.PERMISSION_GONE)
        if answer not in ANSWERS or (answer == Answer.ALWAYS and not hold.rules) or (answer != Answer.DENY and not hold.whole):
            raise ProtocolError(Err.BAD_REQUEST)  # a request too long to show whole is never allowed, by any client
        if shown is not None and (not isinstance(shown, list) or not all(isinstance(r, str) for r in shown)):
            raise ProtocolError(Err.BAD_REQUEST)
        if answer == Answer.ALWAYS:
            hold.granted = [r for r in hold.rules if r in (shown or [])]
            if not hold.granted:
                answer = Answer.ONCE
        hold.future.set_result(answer)

    def resolve(self, turn: Any, answer: str) -> None:
        """Answer every permission still held for this turn (it ends, or is stopped)."""
        for hold in list(self.holds.values()):
            if hold.turn is turn and not hold.future.done():
                hold.future.set_result(answer)

    async def request(self, token: str, body: dict) -> dict:
        """A turn's permission hook (Claude Code): its body read by the agent, its answer written by the agent."""
        turn = self.turn_for(token)
        ask = self.agent.read_permission(body)
        if turn.session_id and ask.session != turn.session_id:
            return self.agent.permission_reply(MISMATCH, [])
        answer = await self.ask(turn, ask)
        return self.agent.permission_reply(answer.answer, answer.rules)

    async def ask(self, turn: Any, ask: PermissionAsk) -> PermissionAnswer:
        """A permission request of a running turn, from a hook or from inside the agent's protocol: held until the
        owner answers in the app or the deadline passes, refused once the turn is stopping or interrupted."""
        c: Conversation = turn.conversation
        if turn.stopping or turn.interrupted:
            return PermissionAnswer(Answer.INTERRUPTED)
        if ask.flipper:
            # Flipper tools never ask, in any mode (the maintainer's decision); the operator's pause still rules.
            # The turn already allows them; this covers a mode that would ask anyway.
            return PermissionAnswer(Answer.ONCE)
        rules = ask.rules if ask.whole else []
        timeout = self.timeout_s()
        hold = Hold(id=uuid.uuid4().hex[:12], turn=turn, rules=rules, future=asyncio.get_running_loop().create_future(),
                    event={}, whole=ask.whole)
        hold.event = self.bus.event(c, {"kind": Kind.PERMISSION, "id": hold.id, "tool": ask.tool, "input": ask.input,
                                        "deadline": round(time.time() + timeout, 1), "always": bool(rules),
                                        "rules": rules, **({} if ask.whole else {"whole": False})})
        self.holds[hold.id] = hold
        self.status_changed()
        try:
            answer = await asyncio.wait_for(asyncio.shield(hold.future), timeout)
        except asyncio.TimeoutError:
            answer = Answer.TIMEOUT
        finally:
            self.holds.pop(hold.id, None)
        self.bus.event(c, {"kind": Kind.PERMISSION_END, "id": hold.id, "answer": answer})
        if answer == Answer.ALWAYS:
            c.rules += [r for r in hold.granted if r not in c.rules]
            self.changed(c, status=False)
        self.status_changed()
        log.info("chat %s: permission %s", c.id[:8], answer)
        return PermissionAnswer(answer, hold.granted)

    async def denied(self, token: str, body: dict) -> dict:
        turn = self.turn_for(token)
        denied = self.agent.read_denied(body)
        if denied.tool_use is not None:
            turn.reported.add(denied.tool_use)
        self.bus.event(turn.conversation, {"kind": Kind.DENIED, "tool": denied.tool, "input": denied.input,
                                           "by": DeniedBy.AUTO, "reason": denied.reason})
        return {}
