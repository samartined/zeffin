# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The /phone endpoint: the relay's WebSocket, with its token checked, handed to a PhoneSession (phone.py, whose
docstring holds the /phone contract). Also the token check that GET /phone/check shares."""
from __future__ import annotations

import asyncio

from google.protobuf.message import DecodeError

from .hub import Hub
from .peers import RELAY
from .phone import PhoneSession
from .text import clean_text
from .web import deny

READY_TIMEOUT = 150.0  # the phone may spend up to 60 s pairing plus 20 s connecting


def phone_token_ok(hub: Hub, auth: str, phone_token: str, peer: str, via: str | None = None) -> bool:
    """Check the phone's Authorization header; a rejection is logged without revealing either token."""
    import hmac

    if hmac.compare_digest(auth.encode(), f"Bearer {phone_token}".encode()):
        return True
    given = auth[7:] if auth.startswith("Bearer ") else ""
    hub.metrics.write({"ev": "ws_rejected", "reason": "bad_token", "peer": peer,
                       **({"via": via} if via else {}),
                       "has_header": bool(auth), "bearer_prefix": auth.startswith("Bearer "),
                       "token_length": len(given), "expected_length": len(phone_token)})
    return False


async def phone_endpoint(hub: Hub, websocket, phone_token: str) -> None:
    """Starlette WebSocket handler for /phone."""
    client = websocket.client
    peer = f"{client.host}:{client.port}" if client else "?"
    auth = websocket.headers.get("authorization", "")
    if not phone_token_ok(hub, auth, phone_token, peer):
        await deny(websocket, 401, "Phone token rejected")
        return
    host = client.host if client else None
    hub.peers.seen(host, RELAY)  # a device with the phone token, even when the slot is taken

    async def send_bytes(data: bytes) -> None:
        await websocket.send_bytes(data)

    async def close(code: int, reason: str) -> None:
        await websocket.close(code=code, reason=reason)

    session = PhoneSession(hub, send_bytes, close, peer)
    refused = hub.reserve(session)
    if refused is not None:
        hub.metrics.write({"ev": "ws_rejected", "reason": refused, "peer": peer})
        if refused == "busy":
            await deny(websocket, 409, "Another phone is already connected")
        else:
            await deny(websocket, 423, "Control stopped by the operator")
        return
    reason = "Phone disconnected"
    hub.peers.open(host, RELAY)
    try:
        await websocket.accept()
        while not session.closed:
            if session.ready:
                message = await websocket.receive()
            else:
                message = await asyncio.wait_for(websocket.receive(), READY_TIMEOUT)
            if message["type"] == "websocket.disconnect":
                session.phone_close = {"code": message.get("code"), "reason": clean_text(message.get("reason"), 123)}
                break
            if message.get("bytes") is not None:
                session.on_bytes(message["bytes"])
            elif message.get("text") is not None:
                await session.on_text(message["text"])
    except TimeoutError:
        reason = "Phone did not report ready in time"
    except (ValueError, DecodeError) as e:
        reason = f"Protocol error: {e}"
    except Exception as e:  # WebSocketDisconnect, RuntimeError on closed socket, etc.
        reason = f"Phone disconnected ({type(e).__name__})"
    finally:
        try:
            await session.abort(session.close_reason or reason)
        finally:
            hub.peers.close(host, RELAY)
