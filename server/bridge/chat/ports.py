# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The chat's two ports to the device: the frames kept for the agent, and writing a metric.

The chat services never import the device (docs/architecture.md): the facade builds these ports once from what it is
given, and the pieces only see them.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass
from typing import Any, Callable, Protocol


class Frame(Protocol):
    seq: int
    orientation: int
    data: bytes


@dataclass(frozen=True)
class DevicePorts:
    kept: Callable[[int], Frame | None]  # a frame handed to the agent, by its seq, while the screen still keeps it
    metric: Callable[[dict], Any]  # one line of the metrics log

    @classmethod
    def none(cls) -> DevicePorts:
        return cls(kept=lambda seq: None, metric=lambda record: None)

    @classmethod
    def of(cls, device: Any) -> DevicePorts:
        """From the hub, or anything with screen.kept and metrics.write; none without one."""
        if device is None:
            return cls.none()
        return cls(kept=lambda seq: device.screen.kept.get(seq), metric=lambda record: device.metrics.write(record))

    def screen(self, seq: int) -> dict | None:
        """The kept frame as a tool result's "screen", or None when it is no longer kept."""
        frame = self.kept(seq)
        if frame is None:
            return None
        return {"seq": frame.seq, "orientation": frame.orientation, "data": base64.b64encode(frame.data).decode()}
