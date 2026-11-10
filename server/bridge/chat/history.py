# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Each conversation's numbered events, state_dir/conversations/<id>.jsonl (mode 600).

This is what the app shows when it opens a conversation. It holds what Claude wrote and what the
tools returned, so it stays in the state folder and never goes anywhere else.
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path

from ..wire import Kind, TurnError
from .index import Conversation, valid_id

MAX_EVENTS = 2000
PAGE_EVENTS = 200
PAGE_BYTES = 512 * 1024


class History:
    def __init__(self, folder: Path | None):
        self.folder = folder
        self.memory: dict[str, list[dict]] = {}  # without a state folder (tests)
        self._lines: dict[str, int] = {}

    def _path(self, conversation_id: str) -> Path:
        if not valid_id(conversation_id):
            raise ValueError("bad conversation id")
        return self.folder / f"{conversation_id}.jsonl"

    def append(self, conversation: Conversation, event: dict) -> dict:
        conversation.events += 1
        record = {"n": conversation.events, "at": round(time.time(), 3), **event}
        if self.folder is None:
            self.memory.setdefault(conversation.id, []).append(record)
            del self.memory[conversation.id][:-MAX_EVENTS]
            return record
        self.folder.mkdir(parents=True, exist_ok=True, mode=0o700)
        path = self._path(conversation.id)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        try:
            os.write(fd, (json.dumps(record, ensure_ascii=False) + "\n").encode())
        finally:
            os.close(fd)
        lines = self._lines.get(conversation.id)
        if lines is None:
            lines = sum(1 for _ in path.open("rb"))
        else:
            lines += 1
        if lines > MAX_EVENTS + MAX_EVENTS // 4:
            self._trim(path)
            lines = MAX_EVENTS
        self._lines[conversation.id] = lines
        return record

    def _trim(self, path: Path) -> None:
        keep = [line for line in path.read_text().split("\n") if line][-MAX_EVENTS:]
        tmp = path.with_name(f".{path.name}.tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write("\n".join(keep) + "\n")
        os.replace(tmp, path)

    def drop(self, conversation_id: str) -> None:
        """A deleted conversation's history goes with it."""
        self.memory.pop(conversation_id, None)
        self._lines.pop(conversation_id, None)
        if self.folder is not None:
            try:
                self._path(conversation_id).unlink()
            except (OSError, ValueError):
                pass

    def all(self, conversation_id: str) -> list[dict]:
        if self.folder is None:
            return list(self.memory.get(conversation_id, []))
        path = self._path(conversation_id)
        if not path.exists():
            return []
        events = []
        # Only "\n" ends a record: splitlines() would also split at U+2028, U+2029 or U+0085 in the text,
        # which json.dumps(ensure_ascii=False) leaves as they are.
        for line in path.read_text().split("\n"):
            try:
                event = json.loads(line)
            except ValueError:
                continue
            if isinstance(event, dict) and type(event.get("n")) is int:
                events.append(event)
        return events

    def page(self, conversation_id: str, before: int | None = None, after: int | None = None,
             limit: int = PAGE_EVENTS) -> tuple[list[dict], bool]:
        """Up to `limit` events (and PAGE_BYTES of JSON): the newest before `before`, or the oldest
        after `after`. Returns (events in order, whether more exist in that direction)."""
        events = [_as_sent(e) for e in self.all(conversation_id)]
        limit = max(1, min(PAGE_EVENTS, limit))
        if after is not None:
            chosen = [e for e in events if e["n"] > after]
            page, size = [], 0
            for e in chosen:
                size += len(json.dumps(e, ensure_ascii=False))
                if len(page) >= limit or (page and size > PAGE_BYTES):
                    break
                page.append(e)
            return page, len(page) < len(chosen)
        chosen = [e for e in events if before is None or e["n"] < before]
        page, size = [], 0
        for e in reversed(chosen):
            size += len(json.dumps(e, ensure_ascii=False))
            if len(page) >= limit or (page and size > PAGE_BYTES):
                break
            page.append(e)
        page.reverse()
        return page, len(page) < len(chosen)


# turn.end's error as servers before 0.14.0 kept it on disk: /app now carries only the neutral name.
OLD_TURN_ERRORS = {"claude_failed": TurnError.AGENT_FAILED}


def _as_sent(event: dict) -> dict:
    """A turn.end as /app carries it today. One kept before claude's stderr stayed in the server log still holds
    it, and it never goes to the app; one kept before 0.14.0 may name its error after Claude Code. The file itself
    is left as it was."""
    if event.get("kind") != Kind.TURN_END:
        return event
    data = event.get("data")
    if isinstance(data, dict) and "stderr" in data:
        event = {**event, "data": {k: v for k, v in data.items() if k != "stderr"}}
    if event.get("error") in OLD_TURN_ERRORS:
        event = {**event, "error": OLD_TURN_ERRORS[event["error"]]}
    return event
