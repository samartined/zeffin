# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""One turn at a time on the whole server, a Remote Control turn included: decided here, in one place.

The slot keeps no copy of the turn. Its owners, the two runners, register when they are built and each keeps its own
turn; the slot asks them. So it can never drift from them, nor outlive a turn. A claim is the check before an owner
starts its turn: nothing is awaited in between, so on the event loop it is atomic.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Protocol

from ..wire import Err, Problem
from .errors import ChatError


class TurnOwner(Protocol):
    def turn_conversation(self) -> str | None:
        """The conversation of its running turn, or None."""

    def turn_info(self) -> dict | None:
        """Its running turn as chat.status's "turn", or None."""


@dataclass(frozen=True)
class Held:
    owner: TurnOwner
    conversation: str


class TurnSlot:
    def __init__(self) -> None:
        self.owners: list[TurnOwner] = []  # in the order chat.status looks: the -p turn first
        self.stopping = False  # the server is stopping: no turn starts
        self.last_turn_at: float | None = None

    def register(self, owner: TurnOwner) -> None:
        self.owners.append(owner)

    def current(self) -> Held | None:
        for owner in self.owners:
            conversation = owner.turn_conversation()
            if conversation is not None:
                return Held(owner, conversation)
        return None

    def other(self, owner: TurnOwner) -> str | None:
        """The conversation of a turn another owner runs, or None."""
        held = self.current()
        return held.conversation if held is not None and held.owner is not owner else None

    def claim(self, owner: TurnOwner) -> None:
        """Before a turn starts: refused while any turn runs (the owner's own too), and once the server stops."""
        held = self.current()
        if held is not None:
            raise ChatError(Err.BUSY, conversation=held.conversation)
        if self.stopping:
            raise ChatError(Err.NOT_READY, problem=Problem.STOPPING)

    def release(self, owner: TurnOwner) -> None:
        """A turn ended."""
        self.last_turn_at = time.time()

    def info(self) -> dict | None:
        held = self.current()
        return held.owner.turn_info() if held is not None else None
