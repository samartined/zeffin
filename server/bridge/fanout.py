# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""One update, many listeners: each listener has its own bounded queue, and a full one loses its oldest item."""
from __future__ import annotations

import asyncio
from typing import Any


class FanOut:
    """Hands every published item to each listener's queue of `size` items.

    A listener that does not keep up never blocks the publisher: when its queue is full, its oldest unread item is
    dropped to make room for the new one, so it always ends up holding the latest ones.
    """

    def __init__(self, size: int) -> None:
        self.size = size
        self._queues: set[asyncio.Queue] = set()

    def __len__(self) -> int:
        return len(self._queues)

    def listen(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=self.size)
        self._queues.add(q)
        return q

    def unlisten(self, q: asyncio.Queue) -> None:
        self._queues.discard(q)

    def publish(self, item: Any) -> None:
        for q in list(self._queues):
            if q.full():
                q.get_nowait()
            q.put_nowait(item)
