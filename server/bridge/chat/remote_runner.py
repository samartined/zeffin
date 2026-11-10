# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The conversation in Remote Control: the agent's interactive session, which other devices can write to, followed
through its hooks, with our own messages typed into it and a watchdog for what no hook reports. The agent starts it,
types into it and reads its hooks (bridge/agent.py, Interactive); the turns, the one-turn rule and the watchdog are here.

The runner changes the Conversation it was given and reports through the callbacks the facade wires: it never saves
the index nor emits except through the bus. One turn at a time on the server is the slot's (slot.py).
"""
from __future__ import annotations

import asyncio
import contextlib
import hmac
import logging
import secrets
import time
from dataclasses import dataclass, field
from typing import Any, Callable

from ..agent import Agent, HookKind, Interactive, Launch, RemoteRequest
from ..events import Ev
from ..text import cut, one_line
from ..wire import VIA_REMOTE, Err, Kind, Notice, Outcome, Problem, ProtocolError, RemoteOff, TurnError, notice, turn_end
from . import attachments
from .bus import ChatBus
from .errors import ChatError, HookRejected
from .index import LAST_CHARS, TITLE_CHARS, Conversation
from .ports import DevicePorts
from .slot import TurnSlot
from .tasks import Tasks

log = logging.getLogger("zeffin.chat")

STALE_S = 15 * 60.0  # until the agent says how long a permission may wait (Launch.stale_s)


@dataclass
class RemoteTurn:
    number: int
    via: str | None            # "remote" when another device started it
    started: float
    pending: str | None = None  # the text we typed, until its prompt hook comes
    pending_at: float = 0.0
    waiting: bool = False       # a permission waits on the other device


@dataclass
class RemoteSession:
    conversation: Any           # index.Conversation
    token: str
    terminal: Any               # the agent's handle (Launch.terminal), or a stand-in in tests
    stale_s: float = STALE_S
    state: str = "starting"     # starting, on
    connected: bool = False     # a hook brought the Remote Control session id (never kept)
    started_at: float = field(default_factory=time.time)
    last_hook: float = field(default_factory=time.time)
    turn: RemoteTurn | None = None
    ending: str | None = None   # why it is being turned off
    delete_after: bool = False  # the conversation was deleted: it goes once the process has ended
    task: asyncio.Task | None = None

    def info(self) -> dict:
        return {"conversation": self.conversation.id, "agent": self.conversation.agent, "state": self.state, "connected": self.connected,
                "since": self.started_at}


class RemoteRunner:
    def __init__(self, agent: Agent, store: attachments.Store, bus: ChatBus, slot: TurnSlot, ports: DevicePorts,
                 tasks: Tasks, *, max_text: int, changed: Callable[..., None], purge: Callable[[Conversation], None],
                 status_changed: Callable[[], None]) -> None:
        self.agent = agent
        # The agent's interactive session; ChatManager.remote_on refuses a conversation whose agent has none.
        self.remote: Interactive = agent.remote  # type: ignore[assignment]
        self.store = store
        self.bus = bus
        self.slot = slot
        self.ports = ports
        self.tasks = tasks
        self.max_text = max_text
        self.changed = changed  # ChatService.changed: saves the index, sends the conversation and the status
        self.purge = purge  # ChatService.purge: a conversation deleted while in Remote Control goes once it is off
        self.status_changed = status_changed
        self.session: RemoteSession | None = None  # the conversation in Remote Control (one at a time)
        slot.register(self)

    # ---- the slot's owner
    def turn_conversation(self) -> str | None:
        session = self.session
        return session.conversation.id if session is not None and session.turn is not None else None

    def turn_info(self) -> dict | None:
        """A turn in the Remote Control process. "waiting" stays false: its permission is answered on the other
        device, never from here ("remote_waiting" says one waits)."""
        session = self.session
        t = session.turn if session is not None else None
        if t is None:
            return None
        c = session.conversation
        info = {"conversation": c.id, "agent": c.agent, "turn": t.number, "mode": c.mode, "model": c.model, "effort": c.effort,
                "started_at": t.started, "waiting": False, "remote": True, "remote_waiting": t.waiting}
        if t.via:
            info["via"] = t.via
        return info

    def holds(self, c: Conversation) -> bool:
        """This conversation is the one in Remote Control."""
        return self.session is not None and self.session.conversation is c

    def info(self) -> dict | None:
        return self.session.info() if self.session is not None else None

    def can_attach(self) -> bool:
        """Whether a message's files can be typed into the process: send refuses them when an attachment's path could
        be misread. The server names the conversations and files itself, so only the store's own folder decides."""
        if self.store.root is None:
            return False
        sample = self.store.root / "00000000-0000-0000-0000-000000000000" / "0000000000000000.bin"
        return self.remote.message("", [str(sample)]) is not None

    # ---- on and off
    def on(self, c: Conversation, effort: str | None) -> None:
        """Start the interactive process for a conversation the service has checked. Its attachments folder is
        added on every launch (--resume does not restore it), so messages sent later may carry files."""
        token = secrets.token_urlsafe(32)
        try:
            folders = [str(self.store.make(c.id))]
        except (attachments.AttachmentError, OSError):
            folders = []  # no state folder (tests), or it cannot be written: messages with files are refused
        launch = self.remote.launch(RemoteRequest(session=c.id, resume=c.state == "ready", model=c.model, effort=effort,
                                                  mode=c.mode, rules=list(c.rules), title=c.title or "", token=token,
                                                  folders=folders))
        session = RemoteSession(conversation=c, token=token, terminal=launch.terminal, stale_s=launch.stale_s)
        self.session = session
        c.remote = True
        self.bus.event(c, notice(Notice.REMOTE_ON))
        self.changed(c)
        log.info("chat %s: remote control starting", c.id[:8])
        session.task = self.tasks.spawn(self._run(session, launch))

    def off(self, c: Conversation, reason: str = RemoteOff.OFF) -> None:
        """Esc for a running turn, /exit, then signals (while starting, Ctrl-C and signals only); the end is handled
        when the process exits."""
        session = self.session
        if session is None or session.conversation is not c or session.ending:
            return
        session.ending = reason
        self.tasks.spawn(self.remote.stop(session.terminal, session.turn is not None,
                                          starting=session.state == "starting"))

    async def stop(self) -> None:
        session = self.session
        if session is None:
            return
        session.ending = session.ending or RemoteOff.SERVER_STOPPED
        await self.remote.stop(session.terminal, session.turn is not None, starting=session.state == "starting",
                               waits=(1.5, 1.5))
        if session.task is not None:
            with contextlib.suppress(asyncio.TimeoutError, asyncio.CancelledError, Exception):
                await asyncio.wait_for(asyncio.shield(session.task), 2)

    async def _run(self, session: RemoteSession, launch: Launch) -> None:
        exit_code = None
        watch = None
        try:
            await self.remote.start(launch)
            watch = asyncio.create_task(self.watch(session))
            exit_code = await self.remote.wait(launch)
        except OSError as e:
            log.warning("chat %s: remote control could not start (%s)", session.conversation.id[:8], type(e).__name__)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("chat %s: remote control failed", session.conversation.id[:8])
        finally:
            if watch is not None:
                watch.cancel()
            self._finish(session, exit_code)

    async def watch(self, session: RemoteSession) -> None:
        while True:
            await asyncio.sleep(self.remote.watch_every_s)
            if self.session is not session or session.ending:
                return
            now = time.time()
            quiet = now - session.last_hook
            if session.state == "starting":
                if now - session.started_at > self.remote.start_limit_s:  # a question nobody answers, a login, an update
                    self.off(session.conversation, reason=RemoteOff.COULD_NOT_START)
            elif session.turn is not None and session.turn.waiting:
                # Answered there, a hook follows; denied there, none does. Long past any allowed tool's run, Esc
                # declines a dialog still open (never Enter or text, which could allow it) and the turn ends.
                if quiet > session.stale_s:
                    self.remote.interrupt(session.terminal)
                    self._close(Outcome.INTERRUPTED, TurnError.PERMISSION_STALE)
            elif session.turn is not None and quiet > self.remote.no_news_s:
                self._close(Outcome.INTERRUPTED, TurnError.NO_NEWS)
            elif session.turn is None and quiet > self.remote.idle_end_s:
                self.off(session.conversation, reason=RemoteOff.IDLE)

    def _finish(self, session: RemoteSession, exit_code: int | None) -> None:
        c = session.conversation
        if session.turn is not None:
            self._close(Outcome.INTERRUPTED, TurnError.REMOTE_ENDED)
        reason = session.ending or (RemoteOff.COULD_NOT_START if session.state == "starting" else RemoteOff.EXITED)
        data = {"reason": reason}
        if reason in (RemoteOff.COULD_NOT_START, RemoteOff.EXITED):
            data["exit"] = exit_code
        self.remote.close(session.terminal)
        if self.session is session:
            self.session = None
        c.remote = False
        self.bus.event(c, notice(Notice.REMOTE_OFF, data))
        log.info("chat %s: remote control off (%s)", c.id[:8], reason)
        self.changed(c)
        if session.delete_after:
            self.purge(c)

    # ---- turns
    def send(self, c: Conversation, text: str, attached: object) -> None:
        """Our message to the conversation in Remote Control: typed into its process; the reply comes with Stop.
        Its attachments are checked as a -p turn's and typed with it, so the agent reads them from their folder."""
        session = self.session
        try:
            metas = self.store.check(c.id, attached) if attached else []
            files = [str(self.store.path(c.id, m)) for m in metas]
        except attachments.AttachmentError as e:
            raise ChatError(e.code, ids=attached if isinstance(attached, list) else None) from None
        if self.remote.refused(self.remote.clean(text)):
            raise ChatError(Err.REMOTE_COMMAND)
        if len(text) > self.max_text:
            raise ProtocolError(Err.TOO_LONG, max=self.max_text)
        self.slot.claim(self)
        if session.state != "on" or session.ending:
            raise ChatError(Err.NOT_READY,
                            problem=Problem.REMOTE_STARTING if not session.ending else Problem.REMOTE_ENDING)
        text = self.remote.clean(text)
        if not text.strip() and not metas:
            raise ProtocolError(Err.BAD_REQUEST)
        typed = self.remote.message(text, files)
        if typed is None:  # a path the terminal could misread: only a state folder with spaces or quotes in it,
            raise ChatError(Err.REMOTE_ATTACHMENTS)  # and then hello.remote.attachments is false (can_attach)
        c.attachments += sum(1 for m in metas if not m["sent"])
        self.store.mark_sent(c.id, metas)
        turn = self._open(None, text, metas)
        turn.pending, turn.pending_at = typed, time.time()
        self.tasks.spawn(self.remote.type(session.terminal, typed))

    def interrupt(self) -> None:
        """Esc fires no hook: the turn closes here."""
        session = self.session
        if session is not None and session.turn is not None:
            self.remote.interrupt(session.terminal)
            self._close(Outcome.INTERRUPTED)

    def _event(self, event: dict, via: str | None) -> dict:
        if via:
            event["via"] = via
        return self.bus.event(self.session.conversation, event)

    def _open(self, via: str | None, prompt: str | None, metas: list[dict] | None = None) -> RemoteTurn:
        session = self.session
        c = session.conversation
        c.turns += 1
        c.open_turn = True
        c.updated = time.time()
        turn = RemoteTurn(number=c.turns, via=via, started=time.time())
        session.turn = turn
        if prompt is not None:
            shown = prompt.strip() or ", ".join(m["name"] for m in metas or [])
            if not c.title:
                c.title = one_line(shown, TITLE_CHARS)
            c.last = one_line(shown, LAST_CHARS)
            self._event({"kind": Kind.USER, "text": cut(prompt, self.max_text),
                         **({"attachments": [attachments.public(m) for m in metas]} if metas else {})}, via)
        self._event({"kind": Kind.TURN_START, "turn": turn.number, "mode": c.mode, "model": c.model,
                     "effort": c.effort}, via)
        self.changed(c)
        return turn

    def _close(self, outcome: str, error: str | None = None, data: dict | None = None) -> None:
        session = self.session
        turn = session.turn if session is not None else None
        if turn is None:
            return
        c = session.conversation
        duration_ms = round((time.time() - turn.started) * 1000)
        self._event(turn_end(turn.number, outcome, error, data, duration_ms), turn.via)
        session.turn = None
        c.open_turn = False
        c.updated = time.time()
        self.slot.release(self)
        self.ports.metric({"ev": Ev.CHAT_TURN, "outcome": outcome, "error": error, "mode": c.mode,
                           "duration_s": round(duration_ms / 1000, 1), "remote": True, "via": turn.via})
        log.info("chat %s: remote turn %d %s%s", c.id[:8], turn.number, outcome, f" {error}" if error else "")
        self.changed(c)

    def _ready(self, session: RemoteSession) -> None:
        """The session has a transcript now: later -p turns resume it."""
        if session.conversation.state != "ready":
            session.conversation.state = "ready"
            self.changed(status=False)

    # ---- the hooks
    async def hook(self, event: str, token: str, connected: bool, body: dict) -> dict:
        """The Remote Control process's hooks (/hooks/remote/<event>), authenticated by its own token."""
        session = self.session
        if session is None or not token or not hmac.compare_digest(token.encode(), session.token.encode()):
            raise HookRejected()
        session.last_hook = time.time()
        changed = False
        if connected and not session.connected:
            session.connected = changed = True
        if session.state == "starting":
            session.state, changed = "on", True
        turn = session.turn
        answer: dict = {}
        hook = self.remote.read_hook(event, body)
        if hook.kind == HookKind.PROMPT:
            answer = self._prompt(session, hook.prompt)
            changed = False  # _open emits
        elif hook.kind in (HookKind.TOOL_START, HookKind.TOOL_DONE):
            if turn is None:
                turn = self._open(VIA_REMOTE, None)  # a turn the server had closed goes on
            if turn.waiting:
                turn.waiting, changed = False, True
            if hook.kind == HookKind.TOOL_DONE:
                self._ready(session)
                tool, result = hook.tool, hook.result
                if hook.flipper:
                    session.conversation.flipper = True
                    if hook.seq is not None:
                        result["screen"] = self.ports.screen(hook.seq)
                self._event(tool, turn.via)
                self._event(result, turn.via)
        elif hook.kind in (HookKind.STOP, HookKind.FAILED):
            self._ready(session)
            message = hook.message
            via = turn.via if turn is not None else VIA_REMOTE
            if hook.kind == HookKind.STOP and message is not None and message.strip():
                self._event({"kind": Kind.TEXT, "id": f"remote-{session.conversation.turns}-{len(message)}",
                             "text": hook.text}, via)
                session.conversation.last = one_line(message, LAST_CHARS)
            if hook.kind == HookKind.STOP:
                self._close(Outcome.DONE)
            else:
                self._close(Outcome.ERROR, TurnError.API_ERROR, {"reason": hook.error})
            changed = False
        elif hook.kind == HookKind.PERMISSION:
            if turn is None:
                turn = self._open(VIA_REMOTE, None)
            turn.waiting, changed = True, True
            self._event(notice(Notice.REMOTE_PERMISSION, {"tool": hook.permission_tool}), turn.via)
        elif hook.kind == HookKind.IDLE:
            if turn is not None and not turn.waiting:
                self._close(Outcome.INTERRUPTED, TurnError.IDLE)
                changed = False
        elif hook.kind == HookKind.SESSION_END:
            if turn is not None:
                turn.waiting = False
            log.info("chat %s: remote session end (%s)", session.conversation.id[:8], hook.reason or "?")
        if changed:
            self.status_changed()
        return answer

    def _prompt(self, session: RemoteSession, prompt: str) -> dict:
        turn = session.turn
        if turn is not None and turn.pending is not None:
            ours = self.remote.matches(turn.pending, turn.pending_at, prompt)
            turn.pending = None
            if ours:
                return {}
            # Not ours after all (or changed on the way): both stay in the history, nothing is dropped.
            log.info("chat %s: remote prompt did not match the send", session.conversation.id[:8])
            self._event({"kind": Kind.USER, "text": cut(prompt, self.max_text)}, VIA_REMOTE)
            return {}
        if self.slot.other(self) is not None:  # a -p turn runs elsewhere: one turn at a time
            log.info("chat %s: remote prompt blocked (another turn runs)", session.conversation.id[:8])
            return self.remote.block()
        if turn is not None:
            self._close(Outcome.INTERRUPTED)  # the previous one ended without a word (Esc, a rejection)
        self._open(VIA_REMOTE, prompt)
        return {}
