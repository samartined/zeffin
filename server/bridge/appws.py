# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""WebSocket channel for the Zeffin app: /app on the public listener.

Authentication: "Authorization: Bearer <app token>", checked before the upgrade.
Every message is a JSON text frame with a "type". The names are in wire.py; what each message carries is in the
JSON Schemas of docs/protocol/app/ (with examples), which the tests check both sides against.

Server -> app:
- hello          {"protocol": 1, "server": {"version", "name"}, "features": ["chat", "attachments", "delete", "remote"],
                 "agents": [{"id", "name", "version", "configured", "ready", "problem", "login", "models",
                 "mode_options", "remote", "attachments"}]}   once, first; name = host name; agents only with the chat:
                 every agent the server knows and what it offers (names and codes; the app words them)
- status         Hub.status(): phone link, relay info, gate, screen freshness; sent when its stable part
                 changes (not for the figures alone) and every STATUS_EVERY_S
- frame          {"seq", "orientation", "stale", "data"}               data = base64 of the 1024-byte framebuffer
- audit.history / audit      the agent's actions on the Flipper
- phone.history / phone      relay connection attempts and their outcome
- diag           figures for the app's Diagnostics screen (reply to "diag"), last 24 h; never tokens
- error          {"error", "message"}                                   a request could not be served
- pong           reply to "ping"
- chat.*         the chat with the agents, only after the client's first chat.list (see CHAT below)

App -> server:
- gate           {"action": "pause" | "resume" | "stop", "via"?: "remote"}: "via" names the phone's remote in
                 the gate's "by" and the audit (else "app")
- screen.fresh   ask the Flipper to redraw (restarts the screen stream)
- diag           ask for the diagnostics figures
- ping

CHAT (bridge/chat/manager.py). Server -> app: chat.status, chat.conversations, chat.conversation,
chat.history, chat.event (numbered per conversation, kept in the history), chat.delta (text being
written; may be dropped), chat.deleted. App -> server: chat.list (subscribes), chat.new (with its agent), chat.open,
chat.send, chat.interrupt, chat.set (mode, model, effort: its agent's), chat.permission.answer, chat.update,
chat.delete (for good, with the agent's own files; a running turn stops first), chat.settings (per agent),
chat.remote {conversation, on} (Remote Control, for an agent that has it: chat/remote.py). Errors are codes with
data; the app words them. The agent's text and tool data are passed through as untrusted content.

The app renders the framebuffer itself, so frames are sent raw, at most MAX_FPS per second
(the newest one wins). Several apps may be connected at once, up to MAX_CLIENTS.
"""
from __future__ import annotations

import asyncio
import base64
import contextlib
import hmac
import json
import logging
import os
import socket
import time

from starlette.websockets import WebSocketDisconnect

from . import __version__
from .chat.manager import ChatError, ChatManager
from .events import PHONE_EVENTS, Ev
from .hub import Hub
from .peers import APP
from .phone import LinkError, RpcError, RpcTimeout
from .stats import read_events, summarize
from .text import clean_text, one_line
from .web import deny
from .wire import CHAT_PREFIX, GATE_ACTIONS, GATE_VIAS, OVERFLOW, PROTOCOL, Err, Feature, In, Out, ProtocolError

MAX_FPS = 10
MAX_CLIENTS = 4
MAX_MESSAGE = 64 * 1024  # bytes of UTF-8; the same as the WebSocket's own limit
STATUS_EVERY_S = 10.0
# Figures in status that move with every frame or ping: a change in them alone is not news. They go out with the
# status every STATUS_EVERY_S, and with any status the rest causes.
FIGURES = ("session_uptime_s", "last_ping_ms", "session_frames", "session_bytes_in", "session_bytes_out")
SCREEN_FIGURES = ("seq", "age_s")
DIAG_HOURS = 24
SERVER_NAME = clean_text(socket.gethostname()) or "zeffin"
# The server user's home folder: the app shows it as "~" in the permission cards and notifications (display only).
HOME = os.path.expanduser("~")
log = logging.getLogger("zeffin.app")


def diag_figures(hub: Hub, session: dict | None, hours: float = DIAG_HOURS) -> dict:
    """The Diagnostics figures. Reads the end of the logs, so call it in a worker thread."""
    since = time.time() - hours * 3600

    def events(log):
        if log.path is not None:
            return read_events(log.path, since)
        return [e for e in log.recent if e.get("ts", 0) >= since]

    s = summarize(events(hub.metrics))
    actions = [e for e in events(hub.audit) if e.get("tool") != "operator"]
    return {
        "hours": hours, "session": session,
        "connections": len(s["sessions"]), "reached_ready": s["reached_ready"],
        "reconnect_median_s": s["reconnect_median_s"],
        "rejected": s["rejected"], "last_rejected": s["last_rejected"],
        "pings": s["pings"], "pings_ok": s["pings_ok"], "rtt_median_ms": s["rtt_median_ms"],
        "stream_failures": s["stream_failures"], "agent_actions": len(actions),
    }


def stable_status(status: dict) -> dict:
    """The part of a status whose change is worth a message: the figures aside."""
    stable = {k: v for k, v in status.items() if k not in FIGURES}
    stable["screen"] = {k: v for k, v in (status.get("screen") or {}).items() if k not in SCREEN_FIGURES}
    return stable


def frame_message(hub: Hub) -> dict | None:
    frame = hub.screen.frame
    if frame is None:
        return None
    return {"type": Out.FRAME, "seq": frame.seq, "orientation": frame.orientation, "stale": hub.screen.stale,
            "data": base64.b64encode(frame.data).decode()}


class AppClient:
    def __init__(self, hub: Hub, websocket, peer: str, chat: ChatManager | None = None):
        self.hub = hub
        self.ws = websocket
        self.peer = peer
        self.chat = chat
        self.chat_listener = None  # set by the client's first chat.list
        self.last_frame_at = 0.0
        self.sent_status: dict | None = None  # the stable part of the last status sent

    async def send(self, kind: str, **fields) -> None:
        await self.ws.send_text(json.dumps({"type": kind, **fields}, ensure_ascii=False, default=str))

    async def send_status(self, only_if_changed: bool = False) -> None:
        """The status; with only_if_changed, only when its stable part differs from the last one sent."""
        status = self.hub.status()
        stable = stable_status(status)
        if only_if_changed and stable == self.sent_status:
            return
        self.sent_status = stable
        await self.send(Out.STATUS, **status)

    async def send_frame(self) -> None:
        wait = 1 / MAX_FPS - (time.monotonic() - self.last_frame_at)
        if wait > 0:
            await asyncio.sleep(wait)
        message = frame_message(self.hub)
        if message is not None:
            self.last_frame_at = time.monotonic()
            await self.ws.send_text(json.dumps(message))

    async def handle(self, text: str) -> None:
        """One message from the app: served, or answered with the error it earned. A malformed one is a ProtocolError
        from dispatch; a chat request the chat cannot serve now is a ChatError; a bug in a chat request is answered
        as bad_request and never closes /app."""
        if len(text.encode()) > MAX_MESSAGE:
            raise ValueError("Message too large")
        message = None
        try:
            message = self.parse(text)
            await self.dispatch(message)
        except ProtocolError as e:
            if self.is_chat(message):
                await self.send_chat_error(message["type"], e.code, e.data)
            else:
                await self.send(Out.ERROR, error=e.code, message=e.message or e.code)
        except ChatError as e:
            await self.send_chat_error(message["type"], e.code, e.data)
        except WebSocketDisconnect:
            raise
        except Exception as e:
            if not self.is_chat(message):
                raise
            # a bug must not close /app; a send to a client that left still raises here
            # never the error's text, and the client's kind only cleaned: both are untrusted
            log.warning("app %s: %s failed with %s", self.peer, one_line(message["type"], 40), type(e).__name__)
            await self.send_chat_error(message["type"], Err.BAD_REQUEST, {}, with_data=False)

    def is_chat(self, message: dict | None) -> bool:
        kind = message.get("type") if message else None
        return isinstance(kind, str) and kind.startswith(CHAT_PREFIX) and self.chat is not None

    async def send_chat_error(self, kind: str, code: str, data: dict, *, with_data: bool = True) -> None:
        fields = {"data": data} if with_data else {}
        await self.send(Out.ERROR, error=code, **fields, request=kind, message=f"{kind} failed: {code}")

    @staticmethod
    def parse(text: str) -> dict:
        try:
            message = json.loads(text)
        except json.JSONDecodeError:
            raise ProtocolError(Err.BAD_JSON, "Message is not JSON") from None
        if not isinstance(message, dict):
            raise ProtocolError(Err.BAD_MESSAGE, "Message must be a JSON object")
        return message

    async def dispatch(self, message: dict) -> None:
        """Serve a message that is a JSON object. Raises ProtocolError when it is malformed."""
        kind = message.get("type")
        if kind == In.PING:
            return await self.send(Out.PONG)
        if kind == In.GATE:
            action = message.get("action")
            if not isinstance(action, str) or action not in GATE_ACTIONS:  # a list or object is not hashable
                raise ProtocolError(Err.BAD_ACTION, "action must be pause, resume or stop")
            via = message.get("via")
            if "via" in message and (not isinstance(via, str) or via not in GATE_VIAS):
                raise ProtocolError(Err.BAD_ACTION, "via must be remote")
            await self.hub.operator(action, by=f"{via or 'app'} {self.peer}")
            return await self.send_status()
        if kind == In.DIAG:
            current = self.hub.session
            session = None
            if current is not None and not current.closed:
                session = {"frames": current.frames, "bytes_in": current.bytes_in, "bytes_out": current.bytes_out,
                           "uptime_s": round(time.monotonic() - current.ready_at, 1) if current.ready_at else None}
            figures = await asyncio.to_thread(diag_figures, self.hub, session)
            return await self.send(Out.DIAG, **figures)
        if kind == In.SCREEN_FRESH:
            try:
                await self.hub.restart_stream()
            except LinkError:
                return await self.send(Out.ERROR, error=Err.NOT_CONNECTED, message="The Flipper is not connected")
            except (RpcError, RpcTimeout) as e:
                return await self.send(Out.ERROR, error=Err.FLIPPER, message=str(e))
            return
        if self.is_chat(message):
            return await self.handle_chat(kind, message)
        raise ProtocolError(Err.UNKNOWN_TYPE, f"Unknown message type {kind!r}")

    async def handle_chat(self, kind: str, m: dict) -> None:
        chat = self.chat
        if kind == In.CHAT_LIST:
            if self.chat_listener is None:
                self.chat_listener = chat.listen()
            await self.send(Out.CHAT_STATUS, **chat.status())
            return await self.send(Out.CHAT_CONVERSATIONS, items=chat.conversations())
        if kind == In.CHAT_NEW:
            return await self.send(Out.CHAT_CONVERSATION, item=chat.new(m.get("mode"), m.get("model"), m.get("effort"),
                                                                         m.get("agent")))
        if kind == In.CHAT_OPEN:
            page = await asyncio.to_thread(chat.page, m.get("conversation"), m.get("before"), m.get("after"),
                                           m.get("limit", 200))
            return await self.send(Out.CHAT_HISTORY, **page)
        if kind == In.CHAT_SEND:
            await chat.recheck(m.get("conversation"))  # a setting the owner just fixed works at once
            return chat.send(m.get("conversation"), m.get("text"), m.get("attachments"))
        if kind == In.CHAT_INTERRUPT:
            return chat.interrupt(m.get("conversation"))
        if kind == In.CHAT_SET:
            return chat.configure(m.get("conversation"), {k: m[k] for k in ("mode", "model", "effort") if k in m})
        if kind == In.CHAT_PERMISSION_ANSWER:
            return chat.answer(m.get("id"), m.get("answer"), m.get("rules"))
        if kind == In.CHAT_DELETE:
            return chat.delete(m.get("conversation"))
        if kind == In.CHAT_REMOTE:
            if not isinstance(m.get("on"), bool):
                raise ProtocolError(Err.BAD_REQUEST)
            return chat.remote_on(m.get("conversation")) if m["on"] else chat.remote_off(m.get("conversation"))
        if kind == In.CHAT_UPDATE:
            return chat.update(m.get("conversation"), {k: m[k] for k in ("title", "pinned", "favorite") if k in m})
        if kind == In.CHAT_SETTINGS:
            return chat.set_settings({k: m[k] for k in ("agent", "default_agent", "default_mode", "default_model",
                                                        "default_effort", "permission_timeout_s") if k in m})
        raise ProtocolError(Err.UNKNOWN_TYPE)

    async def run(self) -> None:
        hub = self.hub
        sub = hub.screen.subscribe()
        gate_q = hub.gate.listen()
        busy_q = hub.busy_changes.listen()
        audit_q = hub.audit.listen()
        metrics_q = hub.metrics.listen()
        waits: dict[str, asyncio.Task] = {}
        try:
            await self.send(Out.HELLO, protocol=PROTOCOL, server={"version": __version__, "name": SERVER_NAME, "home": HOME},
                            features=list(Feature) if self.chat is not None else [],
                            **(self.chat.capabilities() if self.chat is not None else {}))
            await self.send_status()
            await self.send(Out.AUDIT_HISTORY, items=list(hub.audit.recent)[-50:])
            await self.send(Out.PHONE_HISTORY, items=[e for e in hub.metrics.recent if e.get("ev") in PHONE_EVENTS][-15:])
            if hub.screen.frame is not None:
                await self.send_frame()
            while True:
                sources = [("receive", self.ws.receive), ("frame", sub.get), ("gate", gate_q.get),
                           ("busy", busy_q.get), ("audit", audit_q.get), ("metrics", metrics_q.get)]
                if self.chat_listener is not None:
                    sources.append(("chat", self.chat_listener.queue.get))
                for name, getter in sources:
                    if name not in waits:
                        waits[name] = asyncio.create_task(getter())
                done, _ = await asyncio.wait(waits.values(), timeout=STATUS_EVERY_S,
                                             return_when=asyncio.FIRST_COMPLETED)
                if not done:
                    await self.send_status()
                    continue
                for name in [n for n, t in waits.items() if t in done]:
                    item = waits.pop(name).result()
                    if name == "receive":
                        if item["type"] == "websocket.disconnect":
                            return
                        if item.get("text") is None:
                            raise ValueError("Binary frames are not accepted on /app")
                        await self.handle(item["text"])
                    elif name == "frame":
                        await self.send_frame()
                        await self.send_status(only_if_changed=True)
                    elif name in ("gate", "busy"):
                        await self.send_status(only_if_changed=True)
                    elif name == "audit":
                        await self.send(Out.AUDIT, item=item)
                    elif name == "chat":
                        if item.get("type") == OVERFLOW:
                            raise _Overflow()
                        await self.ws.send_text(json.dumps(item, ensure_ascii=False, default=str))
                    elif item.get("ev") in PHONE_EVENTS:
                        await self.send(Out.PHONE, item=item)
                        await self.send_status(only_if_changed=True)
        finally:
            for task in waits.values():
                task.cancel()
            hub.screen.unsubscribe(sub)
            hub.gate.unlisten(gate_q)
            hub.busy_changes.unlisten(busy_q)
            hub.audit.unlisten(audit_q)
            hub.metrics.unlisten(metrics_q)
            if self.chat_listener is not None:
                self.chat.unlisten(self.chat_listener)


class _Overflow(Exception):
    """The client fell too far behind on chat messages: close it so it reconnects and catches up."""


async def app_endpoint(hub: Hub, websocket, app_token: str, chat: ChatManager | None = None) -> None:
    """Starlette WebSocket handler for /app."""
    client = websocket.client
    peer = f"{client.host}:{client.port}" if client else "?"
    auth = websocket.headers.get("authorization", "")
    if not app_token or not hmac.compare_digest(auth.encode(), f"Bearer {app_token}".encode()):
        hub.metrics.write({"ev": Ev.APP_REJECTED, "reason": "bad_token", "peer": peer})
        await deny(websocket, 401, "App token rejected")
        return
    host = client.host if client else None
    hub.peers.seen(host, APP)  # a device with the app token, even when there are too many
    if hub.app_clients >= MAX_CLIENTS:
        hub.metrics.write({"ev": Ev.APP_REJECTED, "reason": "too_many", "peer": peer})
        await deny(websocket, 429, "Too many app connections")
        return
    hub.app_clients += 1
    hub.peers.open(host, APP)
    hub.metrics.write({"ev": Ev.APP_OPEN, "peer": peer})
    reason = "closed"
    try:
        await websocket.accept()
        await AppClient(hub, websocket, peer, chat).run()
    except _Overflow:
        reason = "chat overflow"
        with contextlib.suppress(Exception):
            await websocket.close(code=1013, reason="Too far behind; reconnect")
    except ValueError as e:
        reason = f"protocol error: {e}"
        with contextlib.suppress(Exception):
            await websocket.close(code=1003, reason=str(e)[:100])
    except Exception as e:  # WebSocketDisconnect, RuntimeError on a closed socket, etc.
        reason = f"disconnected ({type(e).__name__})"
    finally:
        hub.app_clients -= 1
        hub.peers.close(host, APP)
        hub.metrics.write({"ev": Ev.APP_CLOSE, "peer": peer, "reason": reason})
