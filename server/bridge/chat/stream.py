# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Turn `claude -p --output-format stream-json` lines into the chat's events.

Shapes from code.claude.com/docs/en/agent-sdk/typescript (the stream-json messages): `system/init`,
`stream_event` (with --include-partial-messages), `assistant`, `user` (tool results), `result`,
`system/permission_denied`, `system/api_retry`, `system/compact_boundary` and `rate_limit_event`.
Anything else is counted and ignored. Messages of subagents (`parent_tool_use_id` set) are not
shown: their parent's tool call and result are.
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass

from ..text import cut, truncate
from ..wire import SEQ, Kind, Notice, notice

TEXT_LIMIT = 64 * 1024
INPUT_STRING_LIMIT = 2000
INPUT_JSON_LIMIT = 8 * 1024
# A permission request's input goes to the card whole up to this much JSON (UTF-8), the owner approving what they see
# whole (security scan before 1.0.0, S1b). The bound is the agent's own text event's (TEXT_LIMIT): the server already
# sends and keeps events that large (a history page holds up to 512 KiB, the last 2,000 events are kept), the app reads
# /app messages of any size (the server's 64 KiB limit is on what the app sends), and the agents allow more (Claude
# Code's hook body up to 4 MiB, an ACP line up to 16 MiB), so this is what decides. Past it, the request goes cut, as a
# tool event does, marked not whole: it can only be denied.
PERMISSION_INPUT_LIMIT = 64 * 1024
RESULT_TEXT_LIMIT = 4 * 1024
FLIPPER_PREFIX = "mcp__flipper__"


def _obj(value: object) -> dict:
    """A JSON object, or an empty one: what the agent writes is not trusted to have the shape the docs give."""
    return value if isinstance(value, dict) else {}


def _items(value: object) -> list:
    return value if isinstance(value, list) else []


def permission_input(value: object) -> tuple[object, bool]:
    """A permission request's input for its card: whole up to PERMISSION_INPUT_LIMIT, and True; past it, cut as a tool
    event's (tool_input), and False."""
    try:
        size = len(json.dumps(value, ensure_ascii=False).encode())
    except (TypeError, ValueError):
        return tool_input(value), False
    return (value, True) if size <= PERMISSION_INPUT_LIMIT else (tool_input(value), False)


def tool_input(value: object) -> object:
    small = truncate(value, INPUT_STRING_LIMIT)
    if len(json.dumps(small, ensure_ascii=False, default=str)) > INPUT_JSON_LIMIT:
        return {"truncated": cut(json.dumps(small, ensure_ascii=False, default=str), INPUT_JSON_LIMIT)}
    return small


@dataclass
class Out:
    """One parser output: kind is event, delta, init, result, denied or limit."""
    kind: str
    data: dict


class StreamParser:
    def __init__(self) -> None:
        self.message_id = ""
        self.streamed: dict[str, list[str]] = {}   # message id -> streamed text block ids, in order
        self.offsets: dict[str, int] = {}
        self.tools: dict[str, tuple[str, float]] = {}  # tool_use id -> (name, started)
        self.api_error: str | None = None
        self.ignored = 0

    def feed(self, event: dict) -> list[Out]:
        kind, subtype = event.get("type"), event.get("subtype")
        if event.get("parent_tool_use_id"):
            return []
        if kind == "system" and subtype == "init":
            return [Out("init", event)]
        if kind == "stream_event":
            return self._stream(_obj(event.get("event")))
        if kind == "assistant":
            return self._assistant(event)
        if kind == "user":
            return self._user(event)
        if kind == "result":
            return [Out("result", event)]
        if kind == "system" and subtype == "permission_denied":
            return [Out("denied", event)]
        if kind == "system" and subtype == "api_retry":
            data = {k: event[k] for k in ("attempt", "max_retries", "retry_delay_ms", "error_status") if k in event}
            return [Out("event", notice(Notice.API_RETRY, data))]
        if kind == "system" and subtype == "compact_boundary":
            return [Out("event", notice(Notice.COMPACTED))]
        if kind == "rate_limit_event":
            if "credits_required" in json.dumps(event):
                return [Out("limit", event)]
            info = event.get("rate_limit_info") if isinstance(event.get("rate_limit_info"), dict) else {}
            if info.get("status") == "allowed":
                return []  # sent on every turn while usage is fine: nothing to show
            data = {k: info[k] for k in ("status", "resetsAt", "rateLimitType") if k in info}
            return [Out("event", notice(Notice.RATE_LIMIT, data))]
        self.ignored += 1
        return []

    def _stream(self, inner: dict) -> list[Out]:
        kind = inner.get("type")
        if kind == "message_start":
            self.message_id = str(_obj(inner.get("message")).get("id") or time.monotonic())
        elif kind == "content_block_start" and _obj(inner.get("content_block")).get("type") == "text":
            block = f"{self.message_id}:{inner.get('index', 0)}"
            self.streamed.setdefault(self.message_id, []).append(block)
            self.offsets[block] = 0
        elif kind == "content_block_delta":
            delta = _obj(inner.get("delta"))
            block = f"{self.message_id}:{inner.get('index', 0)}"
            if delta.get("type") == "text_delta" and block in self.offsets and isinstance(delta.get("text"), str):
                offset = self.offsets[block]
                self.offsets[block] = offset + len(delta["text"])
                return [Out("delta", {"id": block, "offset": offset, "text": delta["text"]})]
        return []

    def _assistant(self, event: dict) -> list[Out]:
        message = _obj(event.get("message"))
        if isinstance(event.get("error"), str):
            self.api_error = event["error"][:500]
        mid = str(message.get("id") or "")
        outs: list[Out] = []
        for i, block in enumerate(_items(message.get("content"))):
            if not isinstance(block, dict):
                continue
            if block.get("type") == "text" and isinstance(block.get("text"), str) and block["text"].strip():
                pending = self.streamed.get(mid) or []
                block_id = pending.pop(0) if pending else f"{mid}:t{i}"
                self.offsets.pop(block_id, None)
                outs.append(Out("event", {"kind": Kind.TEXT, "id": block_id, "text": cut(block["text"], TEXT_LIMIT)}))
            elif block.get("type") == "tool_use":
                tid, name = str(block.get("id") or ""), str(block.get("name") or "?")
                self.tools[tid] = (name, time.monotonic())
                outs.append(Out("event", {"kind": Kind.TOOL, "id": tid, "name": name, "input": tool_input(block.get("input"))}))
        return outs

    def _user(self, event: dict) -> list[Out]:
        content = _obj(event.get("message")).get("content")
        if not isinstance(content, list):
            return []
        outs: list[Out] = []
        for block in content:
            if not isinstance(block, dict) or block.get("type") != "tool_result":
                continue
            tid = str(block.get("tool_use_id") or "")
            name, started = self.tools.pop(tid, ("?", None))
            parts = block.get("content")
            texts = [parts] if isinstance(parts, str) else [
                p.get("text", "") for p in _items(parts) if isinstance(p, dict) and p.get("type") == "text"]
            images = 0 if isinstance(parts, str) else sum(
                1 for p in _items(parts) if isinstance(p, dict) and p.get("type") == "image")
            data = {"kind": Kind.TOOL_RESULT, "id": tid, "error": bool(block.get("is_error")),
                    "text": cut("\n".join(t for t in texts if isinstance(t, str)), RESULT_TEXT_LIMIT),
                    "ms": round((time.monotonic() - started) * 1000) if started is not None else None}
            if images:
                data["images"] = images
            if name.startswith(FLIPPER_PREFIX):
                for text in texts:
                    try:
                        meta = json.loads(text)
                    except (TypeError, ValueError):
                        continue
                    if isinstance(meta, dict) and isinstance(meta.get(SEQ), int):
                        data[SEQ] = meta[SEQ]  # the manager attaches the kept frame
                        break
            outs.append(Out("event", data))
        return outs
