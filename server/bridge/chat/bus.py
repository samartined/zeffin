# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Where the chat's messages go out: the listeners (one per /app client that asked for the chat) and their queues.

Only the bus emits. Events are kept in the history first, then sent; text being written goes out as deltas that may be
dropped when a client falls behind, and a client too far behind gets the overflow marker and asks again.
"""
from __future__ import annotations

import asyncio
import contextlib

from ..wire import OVERFLOW, Out
from .history import History
from .index import Conversation

QUEUE_SIZE = 1024


class Listener:
    def __init__(self) -> None:
        self.queue: asyncio.Queue = asyncio.Queue(maxsize=QUEUE_SIZE)
        self.overflow = False

    def put(self, message: dict, durable: bool) -> None:
        if self.overflow:
            return
        if not durable:
            if self.queue.qsize() < QUEUE_SIZE * 3 // 4:
                self.queue.put_nowait(message)
            return
        try:
            self.queue.put_nowait(message)
        except asyncio.QueueFull:
            self.overflow = True  # the app reconnects and asks for what it missed
            with contextlib.suppress(asyncio.QueueFull):
                self.queue.put_nowait({"type": OVERFLOW})


class ChatBus:
    def __init__(self, history: History) -> None:
        self.history = history
        self.listeners: set[Listener] = set()

    def listen(self) -> Listener:
        listener = Listener()
        self.listeners.add(listener)
        return listener

    def unlisten(self, listener: Listener) -> None:
        self.listeners.discard(listener)

    def emit(self, message: dict, durable: bool = True) -> None:
        for listener in list(self.listeners):
            listener.put(message, durable)

    def event(self, c: Conversation, event: dict) -> dict:
        """Keep the event in the conversation's history and send it; returns the kept record."""
        record = self.history.append(c, event)
        self.emit({"type": Out.CHAT_EVENT, "conversation": c.id, **record})
        return record

    def delta(self, c: Conversation, turn: int, block: str, offset: int, text: str) -> None:
        self.emit({"type": Out.CHAT_DELTA, "conversation": c.id, "turn": turn, "id": block, "offset": offset,
                   "text": text}, durable=False)

    def conversation(self, c: Conversation) -> None:
        self.emit({"type": Out.CHAT_CONVERSATION, "item": c.summary()})

    def deleted(self, conversation_id: str) -> None:
        self.emit({"type": Out.CHAT_DELETED, "conversation": conversation_id})

    def status(self, status: dict) -> None:
        self.emit({"type": Out.CHAT_STATUS, **status})
