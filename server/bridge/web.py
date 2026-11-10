# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Starlette helpers the adapters share: the one exception to "adapters import adapters only from app.py and
cli.py" (docs/architecture.md)."""
from __future__ import annotations

from starlette.responses import PlainTextResponse


async def deny(websocket, status: int, text: str) -> None:
    """Refuse the WebSocket upgrade with a real HTTP status, so the phone or the app can tell causes apart."""
    try:
        await websocket.send_denial_response(PlainTextResponse(text + "\n", status_code=status))
    except RuntimeError:  # server without the WebSocket Denial Response extension: plain 403
        await websocket.close(code=1008, reason=text[:100])
