# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Pairing the app: the routes. The session, its rotating code and what the QR code holds are in
paircodes.py; the contract is docs/protocol/pairing.

The QR code of the web view's "Pair the app" and of `zf pair` holds {"fb": 3, "url", "code", "key"}, never the tokens.
The app checks the server's key against `key` in the TLS handshake, then trades the code for a request the owner
confirms on the server, and only then collects the address and the relay and app tokens. Showing a code needs the
view token typed again (web view) or the app token (`zf pair`, on the loopback listener); the MCP token, which chat
turns hold, cannot. Confirming or refusing needs the same credential and names the request's `ref`.

On the public listener:
    POST /app/pair        {"code"}     202 {"pairing", "number", "expires_in"}; 401 bad_code; 409 code_used; 410 code_expired
    POST /app/pair/wait   {"pairing"}  200 {"url", "phone", "app"} (once); 202 {"state": "waiting", "expires_in"};
                                       403 refused; 410 expired; 404 unknown
    POST /app/pair/cancel {"pairing"}  200 {"state": "cancelled"}; 404 unknown
Every body over BODY_LIMIT gets 413 too_large. Nothing here logs a code, a ticket, a ref or a number.
"""
from __future__ import annotations

import asyncio
import hmac
import json
import logging

from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from .config import Config
from .paircodes import PairCodes

log = logging.getLogger("zeffin.pairing")

BODY_LIMIT = 1024
WAIT_HOLD_S = 20.0   # the longest /app/pair/wait holds; the app's read timeout is 30 s
REFUSAL_DELAY_S = 0.5
_NO_STORE = {"cache-control": "no-store"}


def _reply(body: dict, status: int = 200) -> JSONResponse:
    return JSONResponse(body, status_code=status, headers=_NO_STORE)


async def _field(request: Request, name: str) -> tuple[object, JSONResponse | None]:
    """The one field of a small JSON body, or a 413 reply."""
    body = await request.body()
    if len(body) > BODY_LIMIT:
        return None, _reply({"error": "too_large"}, 413)
    try:
        return json.loads(body).get(name), None
    except (ValueError, AttributeError):
        return None, None


async def _refused(error: str, status: int, delay_s: float) -> JSONResponse:
    await asyncio.sleep(delay_s)  # slow down guessing
    return _reply({"error": error}, status)


def app_routes(codes: PairCodes, config: Config, *, hold_s: float = WAIT_HOLD_S,
               delay_s: float = REFUSAL_DELAY_S) -> list[Route]:
    """The phone's routes on the public listener: the code, then the ticket, are its only credentials."""

    async def ask(request: Request):
        code, too_large = await _field(request, "code")
        if too_large is not None:
            return too_large
        peer = request.client.host if request.client else "?"
        result, r = codes.ask(code, peer)
        if r is not None:
            log.info("pairing request from %s: waiting for the owner", peer)
            return _reply({"pairing": r.ticket, "number": r.number, "expires_in": codes.expires_in(r)}, 202)
        log.warning("pairing refused from %s: %s", peer, result)
        status = {"bad_code": 401, "code_used": 409}.get(result, 410)
        return await _refused(result, status, delay_s)

    async def wait(request: Request):
        ticket, too_large = await _field(request, "pairing")
        if too_large is not None:
            return too_large
        changed = asyncio.Event()
        codes.listeners.add(changed.set)
        try:
            deadline = asyncio.get_running_loop().time() + hold_s
            while True:
                result, r = codes.poll(ticket)
                left = deadline - asyncio.get_running_loop().time()
                if result != "waiting" or r is None or left <= 0:
                    break
                changed.clear()
                # Wake up at least each second: the request's own expiry is read from the clock, not signalled.
                try:
                    await asyncio.wait_for(changed.wait(), min(left, 1.0))
                except TimeoutError:
                    pass
        finally:
            codes.listeners.discard(changed.set)
        if result == "paired":
            log.info("app paired from %s", r.peer if r else "?")
            return _reply({"url": config.base_url(), "phone": config.phone_token, "app": config.app_token})
        if result == "waiting" and r is not None:
            return _reply({"state": "waiting", "expires_in": codes.expires_in(r)}, 202)
        status = {"refused": 403, "expired": 410}.get(result, 404)
        return await _refused(result, status, delay_s)

    async def cancel(request: Request):
        ticket, too_large = await _field(request, "pairing")
        if too_large is not None:
            return too_large
        if codes.withdraw(ticket):
            return _reply({"state": "cancelled"})
        return await _refused("unknown", 404, delay_s)

    return [Route("/app/pair", ask, methods=["POST"]), Route("/app/pair/wait", wait, methods=["POST"]),
            Route("/app/pair/cancel", cancel, methods=["POST"])]


def local_app(codes: PairCodes, config: Config):
    """zf pair's routes on the loopback listener, with the app token:
    POST /pair (start), GET /pair (state), POST /pair/cancel, POST /pair/confirm {"ref"}, POST /pair/refuse {"ref"}."""
    from starlette.applications import Starlette

    expected = f"Bearer {config.app_token}".encode()

    def authed(request: Request) -> bool:
        return bool(config.app_token) and hmac.compare_digest(request.headers.get("authorization", "").encode(), expected)

    def guarded(handler):
        async def endpoint(request: Request):
            if not authed(request):
                return _reply({"error": "unauthorized"}, 401)
            return await handler(request)
        return endpoint

    async def start(request: Request):
        return _reply(codes.start())

    async def state(request: Request):
        return _reply(codes.view())

    async def cancel(request: Request):
        return _reply(codes.cancel())

    def answer(method):
        async def endpoint(request: Request):
            ref, too_large = await _field(request, "ref")
            if too_large is not None:
                return too_large
            done = method(ref)
            return _reply({"done": done, **codes.view()}, 200 if done else 409)
        return endpoint

    return Starlette(routes=[Route("/pair", guarded(start), methods=["POST"]),
                             Route("/pair", guarded(state), methods=["GET"]),
                             Route("/pair/cancel", guarded(cancel), methods=["POST"]),
                             Route("/pair/confirm", guarded(answer(codes.confirm)), methods=["POST"]),
                             Route("/pair/refuse", guarded(answer(codes.refuse)), methods=["POST"])])
