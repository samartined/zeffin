# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The Agent Client Protocol, from the client's side: JSON-RPC 2.0 over an agent process's stdio, one message per line
(agentclientprotocol.com: "Overview", "Initialization", "Session Setup", "Prompt Turn", "Tool Calls", "Session Config
Options"). Generic: what any ACP agent speaks; what one agent adds (its tool names, its options' values) is its
integration's (opencode.py).

- `Connection` sends requests and notifications, answers the agent's own requests (session/request_permission) through
  a callback, and hands every session/update to another.
- `Updates` turns session/update notifications into the Agent port's events, in the bridge's vocabulary (wire.py's
  chat events): text being written as deltas and then whole, a tool and its result. What the agent writes and what its
  tools return are untrusted data: cut here, where they enter, as the stream parser cuts Claude Code's.
"""
from __future__ import annotations

import asyncio
import base64
import contextlib
import json
import time
from typing import Any, Awaitable, Callable

from ..agent import Delta, Output, TurnEvent
from ..text import cut, one_line
from ..wire import SEQ, Kind
from .stream import RESULT_TEXT_LIMIT, TEXT_LIMIT, tool_input

PROTOCOL_VERSION = 1
LINE_LIMIT = 16 * 1024 * 1024
METHOD_NOT_FOUND = -32601
INTERNAL_ERROR = -32603


class AcpError(Exception):
    """An error response: its code, its message and its data, all from the agent (untrusted)."""

    def __init__(self, code: object, message: object, data: object = None) -> None:
        super().__init__(str(message))
        self.code, self.message, self.data = code, message, data

    def text(self, limit: int = 300) -> str:
        """The message and its data's message, on one line and cut: for the log, or a turn's error data."""
        detail = self.data.get("message") if isinstance(self.data, dict) else self.data
        parts = [str(p) for p in (self.message, detail) if isinstance(p, (str, int, float)) and str(p)]
        return one_line(": ".join(parts), limit)


def text_block(text: str) -> dict:
    return {"type": "text", "text": text}


def image_block(media_type: str, data: bytes) -> dict:
    return {"type": "image", "mimeType": media_type, "data": base64.b64encode(data).decode()}


def mcp_http(name: str, url: str, headers: dict[str, str]) -> dict:
    """An MCP server over HTTP for session/new and session/load (the agent declares mcpCapabilities.http)."""
    return {"type": "http", "name": name, "url": url, "headers": [{"name": k, "value": v} for k, v in headers.items()]}


def option(options: object, category: str) -> dict | None:
    """The session config option of a category (model, mode, thought_level), or None."""
    for item in options if isinstance(options, list) else []:
        if isinstance(item, dict) and (item.get("category") == category or item.get("id") == category):
            return item
    return None


def option_values(item: dict | None) -> list[str]:
    return [o["value"] for o in (item or {}).get("options") or [] if isinstance(o, dict) and isinstance(o.get("value"), str)]


Updated = Callable[[dict], None]
Answered = Callable[[str, dict], Awaitable[dict]]


class Connection:
    """One agent process's stdio. `on_update` gets each session/update's params; `on_request` answers the agent's
    requests (raise AcpError to refuse one)."""

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, *, on_update: Updated,
                 on_request: Answered) -> None:
        self.reader = reader
        self.writer = writer
        self.on_update = on_update
        self.on_request = on_request
        self.next_id = 0
        self.pending: dict[int, asyncio.Future] = {}
        self.tasks: set[asyncio.Task] = set()
        self.closed = False

    async def run(self) -> None:
        """Read until the agent's output ends; then every request still waiting fails."""
        try:
            while True:
                try:
                    line = await self.reader.readline()
                except ValueError:  # a line over the limit: skip it
                    continue
                if not line:
                    break
                try:
                    message = json.loads(line)
                except ValueError:
                    continue
                if isinstance(message, dict):
                    self._dispatch(message)
        finally:
            self.closed = True
            for future in self.pending.values():
                if not future.done():
                    future.set_exception(ConnectionError("the agent's output ended"))
            self.pending.clear()

    def _dispatch(self, message: dict) -> None:
        method, mid = message.get("method"), message.get("id")
        params = message.get("params") if isinstance(message.get("params"), dict) else {}
        if isinstance(method, str):
            if mid is None:
                if method == "session/update":
                    self.on_update(params)
                return
            task = asyncio.create_task(self._answer(mid, method, params))
            self.tasks.add(task)
            task.add_done_callback(self.tasks.discard)
            return
        future = self.pending.pop(mid, None) if isinstance(mid, int) else None
        if future is None or future.done():
            return
        error = message.get("error")
        if isinstance(error, dict):
            future.set_exception(AcpError(error.get("code"), error.get("message"), error.get("data")))
        else:
            result = message.get("result")
            future.set_result(result if isinstance(result, dict) else {})

    async def _answer(self, mid: object, method: str, params: dict) -> None:
        try:
            result = await self.on_request(method, params)
            self._write({"id": mid, "result": result})
        except AcpError as e:
            self._write({"id": mid, "error": {"code": e.code, "message": str(e.message)}})
        except asyncio.CancelledError:
            raise
        except Exception:
            self._write({"id": mid, "error": {"code": INTERNAL_ERROR, "message": "Internal error"}})

    def _write(self, message: dict) -> None:
        if self.closed or self.writer.is_closing():
            return
        with contextlib.suppress(ConnectionError, RuntimeError):
            self.writer.write((json.dumps({"jsonrpc": "2.0", **message}) + "\n").encode())

    async def request(self, method: str, params: dict, timeout: float | None = None) -> dict:
        """Its result, or AcpError (an error response), ConnectionError (the output ended) or TimeoutError."""
        if self.closed:
            raise ConnectionError("the agent's output ended")
        self.next_id += 1
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        self.pending[self.next_id] = future
        self._write({"id": self.next_id, "method": method, "params": params})
        with contextlib.suppress(ConnectionError, RuntimeError):
            await self.writer.drain()
        return await asyncio.wait_for(future, timeout) if timeout is not None else await future

    def notify(self, method: str, params: dict) -> None:
        self._write({"method": method, "params": params})

    def close(self) -> None:
        """No more input: an ACP agent ends when its stdin closes."""
        with contextlib.suppress(ConnectionError, RuntimeError, OSError):
            self.writer.close()
        for task in self.tasks:
            task.cancel()


NameOf = Callable[[dict], tuple[str, bool]]


class Updates:
    """session/update → the port's events. `name_of` gives a tool call's name on the wire and whether it is one of the
    bridge's own MCP tools (the integration's: ACP carries a title and a kind, not the tool's name)."""

    def __init__(self, name_of: NameOf) -> None:
        self.name_of = name_of
        self.current: str | None = None  # the message being written
        self.text = ""
        self.tools: dict[str, dict[str, Any]] = {}

    def feed(self, update: dict) -> list[TurnEvent]:
        kind = update.get("sessionUpdate")
        if kind == "agent_message_chunk":
            return self._chunk(update)
        if kind in ("tool_call", "tool_call_update"):
            return self._close() + self._tool(update)
        return []  # thoughts, plans, commands, usage, config changes, the user's own chunks: not shown

    def flush(self) -> list[TurnEvent]:
        """At the end of the turn: the message still being written."""
        return self._close()

    def _chunk(self, update: dict) -> list[TurnEvent]:
        content = update.get("content")
        text = content.get("text") if isinstance(content, dict) and content.get("type") == "text" else None
        if not isinstance(text, str) or not text:
            return []
        mid = update.get("messageId")
        block = mid if isinstance(mid, str) and mid else "message"
        events = self._close() if self.current is not None and block != self.current else []
        if self.current is None:
            self.current, self.text = block, ""
        offset = len(self.text)
        if offset < TEXT_LIMIT:
            self.text += text
            events.append(Delta(block=block, offset=offset, text=text[:TEXT_LIMIT - offset]))
        return events

    def _close(self) -> list[TurnEvent]:
        block, text = self.current, self.text
        self.current, self.text = None, ""
        if block is None or not text:
            return []
        return [Output(event={"kind": Kind.TEXT, "id": block, "text": cut(text, TEXT_LIMIT)})]

    def _tool(self, update: dict) -> list[TurnEvent]:
        tid = update.get("toolCallId")
        if not isinstance(tid, str) or not tid:
            return []
        tool = self.tools.get(tid)
        if tool is None:
            name, flipper = self.name_of(update)
            tool = self.tools[tid] = {"name": name, "flipper": flipper, "input": None, "shown": False,
                                      "started": time.monotonic()}
        raw = update.get("rawInput")
        if isinstance(raw, dict) and raw:
            tool["input"] = raw
        status = update.get("status")
        done = status in ("completed", "failed")
        events: list[TurnEvent] = []
        # A tool shows once its input is known (OpenCode sends it after the first pending update) or when it ends.
        if not tool["shown"] and (tool["input"] is not None or done):
            tool["shown"] = True
            events.append(Output(event={"kind": Kind.TOOL, "id": tid, "name": tool["name"],
                                        "input": tool_input(tool["input"] or {})}, flipper=tool["flipper"]))
        if done:
            del self.tools[tid]
            events.append(self._result(tid, tool, update, failed=status == "failed"))
        return events

    @staticmethod
    def _result(tid: str, tool: dict, update: dict, *, failed: bool) -> Output:
        texts: list[str] = []
        images = 0
        for item in update.get("content") if isinstance(update.get("content"), list) else []:
            inner = item.get("content") if isinstance(item, dict) and item.get("type") == "content" else None
            if not isinstance(inner, dict):
                continue
            if inner.get("type") == "text" and isinstance(inner.get("text"), str):
                texts.append(inner["text"])
            elif inner.get("type") == "image":
                images += 1
        raw = update.get("rawOutput")
        if failed and isinstance(raw, dict) and isinstance(raw.get("error"), str):
            texts.append(raw["error"])
        data: dict = {"kind": Kind.TOOL_RESULT, "id": tid, "error": failed,
                      "text": cut("\n".join(t for t in texts if t), RESULT_TEXT_LIMIT),
                      "ms": round((time.monotonic() - tool["started"]) * 1000)}
        if images:
            data["images"] = images
        seq = None
        if tool["flipper"]:
            for text in texts:
                try:
                    meta = json.loads(text)
                except ValueError:
                    continue
                if isinstance(meta, dict) and isinstance(meta.get(SEQ), int) and not isinstance(meta.get(SEQ), bool):
                    seq = meta[SEQ]  # the bridge attaches the kept frame
                    break
        return Output(event=data, flipper=tool["flipper"], seq=seq)
