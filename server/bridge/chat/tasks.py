# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The chat's background tasks, kept so stop() can cancel them all."""
from __future__ import annotations

import asyncio


class Tasks:
    def __init__(self) -> None:
        self._tasks: set[asyncio.Task] = set()

    def spawn(self, coro) -> asyncio.Task:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    def cancel_all(self) -> None:
        for task in list(self._tasks):
            task.cancel()
