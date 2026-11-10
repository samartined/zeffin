# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""ASGI apps and the two-listener server.

- Public listener (private-network IP, default port 9434), over TLS 1.3 only with the server's own key
  (bridge/tlskey.py): /phone for the relay, /app for the Zeffin app, /view for the operator in a browser.
- MCP listener (127.0.0.1 only, default port 9435): /mcp Streamable HTTP for Claude Code, GET /status for
  zf status, /hooks/* for the PermissionRequest and PermissionDenied hooks of chat turns (each turn
  has its own token), and /pair for zf pair (with the app token, never the MCP token: bridge/pairing.py). It stays
  cleartext: zf sends a token to it only after localpeer's check that the listener is its own user's.
"""
from __future__ import annotations

import asyncio
import contextlib
import hmac
import json
import logging
import signal
import ssl
import subprocess
import time
from collections import OrderedDict
from collections.abc import Callable
from pathlib import Path

import uvicorn
from starlette.applications import Starlette
from starlette.routing import Route, WebSocketRoute
from starlette.responses import FileResponse, JSONResponse, PlainTextResponse
from starlette.websockets import WebSocket

from . import __version__, paircodes, pairing, privnet, tlskey, view
from .appws import SERVER_NAME, app_endpoint
from .chat import attachments
from .chat.attachments import AttachmentError
from .chat.claude_code import ClaudeCode
from .chat.opencode import OpenCode
from .chat.manager import ChatError, ChatManager, HookRejected
from .config import Config
from .events import Ev
from .hub import Hub
from .peers import APP, PAIRING, RELAY, Peers
from .phonews import phone_endpoint, phone_token_ok
from .tools import build_mcp
from .web import deny

log = logging.getLogger("zeffin")

PUBLIC_BODY_LIMIT = 16 * 1024
MCP_BODY_LIMIT = 4 * 1024 * 1024
HOOK_BODY_LIMIT = 4 * 1024 * 1024
GRACEFUL_SHUTDOWN_S = 10


class BodyLimit:
    """Reject oversized HTTP bodies before the application reads them; `paths` raise it for single routes."""

    def __init__(self, app, limit: int, paths: dict[str, int] | None = None):
        self.app, self.default, self.paths = app, limit, paths or {}

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        limit = self.paths.get(scope.get("path", ""), self.default)
        for name, value in scope.get("headers", []):
            if name == b"content-length":
                try:
                    too_big = int(value) > limit
                except ValueError:
                    too_big = True
                if too_big:
                    return await _plain(send, 413, "Request body too large")
        seen = 0

        async def limited_receive():
            nonlocal seen
            message = await receive()
            if message["type"] == "http.request":
                seen += len(message.get("body", b""))
                if seen > limit:
                    raise ValueError("Request body too large")
            return message

        return await self.app(scope, limited_receive, send)


class RefusalLog:
    """The metrics lines of refused peers, rated so a flood cannot push the relay's history out of the log.

    At most one line per peer address every PER_PEER_S and PER_MINUTE lines a minute in all; the next line written
    says how many were left out ("suppressed"). The table of recent peers keeps the newest PEERS addresses.
    """

    PER_PEER_S = 60.0
    PER_MINUTE = 20
    PEERS = 256

    def __init__(self, write: Callable[[dict], object], clock: Callable[[], float] = time.monotonic):
        self.write, self.clock = write, clock
        self.last: OrderedDict[str, float] = OrderedDict()
        self.window, self.lines, self.suppressed = clock(), 0, 0

    def __call__(self, host: str, port: int | None, kind: str, path: str) -> None:
        now = self.clock()
        if now - self.window >= 60.0:
            self.window, self.lines = now, 0
        last = self.last.get(host)
        if (last is not None and now - last < self.PER_PEER_S) or self.lines >= self.PER_MINUTE:
            self.suppressed += 1
            return
        self.last[host] = now
        self.last.move_to_end(host)
        while len(self.last) > self.PEERS:
            self.last.popitem(last=False)
        self.lines += 1
        line: dict[str, object] = {"ev": Ev.PEER_REFUSED, "peer": f"{host}:{port}" if port is not None else host, "kind": kind,
                "path": path[:64]}
        if self.suppressed:
            line["suppressed"], self.suppressed = self.suppressed, 0
        self.write(line)


class PrivatePeers:
    """Serve only private-network or loopback peers (privnet.allowed_peer): the outermost layer of the public listener.

    The peer is the kernel's address in scope["client"]: the listeners run with proxy_headers=False (listener_config),
    so no X-Forwarded-For header can rewrite it. A refused peer gets 403 (a WebSocket, a denial response) before the
    body limit, routing or any token is read; a peer the server cannot tell is refused.
    """

    def __init__(self, app, on_refused: Callable[[str, int | None, str, str], None]):
        self.app, self.on_refused = app, on_refused

    async def __call__(self, scope, receive, send):
        if scope["type"] not in ("http", "websocket"):
            return await self.app(scope, receive, send)
        client = scope.get("client")
        host = client[0] if client else None
        if privnet.allowed_peer(host):
            return await self.app(scope, receive, send)
        self.on_refused(host or "?", client[1] if client else None, scope["type"], scope.get("path", ""))
        if scope["type"] == "websocket":
            return await deny(WebSocket(scope, receive, send), 403, "Forbidden")
        return await _plain(send, 403, "Forbidden")


class BearerAuth:
    """Require a static Bearer token on every HTTP request, checked before the body is read."""

    def __init__(self, app, token: str):
        self.app = app
        self.expected = f"Bearer {token}".encode()

    async def __call__(self, scope, receive, send):
        if scope["type"] not in ("http", "websocket"):
            return await self.app(scope, receive, send)
        header = b""
        for name, value in scope.get("headers", []):
            if name == b"authorization":
                header = value
                break
        if not hmac.compare_digest(header, self.expected):
            if scope["type"] == "websocket":
                return await send({"type": "websocket.close", "code": 1008})
            return await _plain(send, 401, "Unauthorized", {b"www-authenticate": b"Bearer"})
        return await self.app(scope, receive, send)


async def _plain(send, status: int, text: str, extra: dict[bytes, bytes] | None = None) -> None:
    body = json.dumps({"error": text}).encode()
    headers = [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())]
    headers += list((extra or {}).items())
    await send({"type": "http.response.start", "status": status, "headers": headers})
    await send({"type": "http.response.body", "body": body})


def public_app(hub: Hub, config: Config, chat: ChatManager | None = None, *, codes: paircodes.PairCodes):
    """The phone, the app and the web view, on the private-network address. codes is the one store mcp_app's /pair shares."""

    async def phone(websocket):
        await phone_endpoint(hub, websocket, config.phone_token)

    async def app_ws(websocket):
        await app_endpoint(hub, websocket, config.app_token, chat)

    async def root(request):
        return PlainTextResponse("zeffin\n")

    async def phone_check(request):
        """What a relay needs before connecting: token valid, gate, attached relay. Takes no slot."""
        client = request.client
        peer = f"{client.host}:{client.port}" if client else "?"
        headers = {"cache-control": "no-store"}
        if not phone_token_ok(hub, request.headers.get("authorization", ""), config.phone_token, peer, via="check"):
            return JSONResponse({"error": "Phone token rejected"}, status_code=401, headers=headers)
        hub.peers.seen(client.host if client else None, RELAY)
        return JSONResponse({"server": {"name": SERVER_NAME, "version": __version__}, "gate": hub.gate.state,
                             "relay": hub.relay_summary()}, headers=headers)

    routes = [WebSocketRoute("/phone", phone), WebSocketRoute("/app", app_ws), Route("/", root),
              Route("/phone/check", phone_check, methods=["GET"]), *view.routes(hub, config, codes),
              *pairing.app_routes(codes, config),
              *(attachment_routes(chat, config.app_token, hub.peers) if chat is not None else [])]
    app = Starlette(routes=routes)
    limited = BodyLimit(app, PUBLIC_BODY_LIMIT, {UPLOAD_PATH: attachments.MAX_BYTES + 1024})
    return PrivatePeers(limited, RefusalLog(hub.metrics.write))


UPLOAD_PATH = "/app/upload"


def attachment_routes(chat: ChatManager, app_token: str, peers: Peers | None = None) -> list[Route]:
    """Chat attachments over HTTP with the app token: /app's WebSocket shares the relay's 64 KiB frame limit.

    POST /app/upload?conversation=<id>&name=<file name>  body: the file, Content-Type: its type
         201 {id, name, kind, media_type, bytes, width, height}; 400, 401, 404, 413 or 415 {error, message}
    GET  /app/attachments/<conversation>/<id>             the file as stored
    """
    expected = f"Bearer {app_token}".encode()

    def authed(request) -> bool:
        ok = bool(app_token) and hmac.compare_digest(request.headers.get("authorization", "").encode(), expected)
        if ok and peers is not None:
            peers.seen(request.client.host if request.client else None, APP)
        return ok

    def fail(status: int, code: str, message: str) -> JSONResponse:
        return JSONResponse({"error": code, "message": message}, status_code=status, headers={"cache-control": "no-store"})

    async def upload(request):
        if not authed(request):
            return fail(401, "unauthorized", "App token rejected")
        try:
            data = await request.body()
        except ValueError:
            return fail(413, "too_large", "Request body too large")
        try:
            meta = await asyncio.to_thread(chat.upload, request.query_params.get("conversation"),
                                           request.query_params.get("name"),
                                           request.headers.get("content-type", ""), data)
        except ChatError as e:
            return fail(404 if e.code == "not_found" else 400, e.code, e.code)
        except AttachmentError as e:
            return fail(e.status, e.code, str(e))
        return JSONResponse(meta, status_code=201, headers={"cache-control": "no-store"})

    async def download(request):
        if not authed(request):
            return fail(401, "unauthorized", "App token rejected")
        found = chat.attachment(request.path_params["conversation"], request.path_params["ident"])
        if found is None or not found[1].is_file():
            return fail(404, "not_found", "No such attachment")
        meta, path = found
        return FileResponse(path, media_type=meta["media_type"], headers={
            "cache-control": "private, max-age=86400", "x-content-type-options": "nosniff",
            "content-disposition": "inline" if meta["kind"] == "image" else "attachment"})

    return [Route(UPLOAD_PATH, upload, methods=["POST"]),
            Route("/app/attachments/{conversation}/{ident}", download, methods=["GET"])]


def source_commit() -> str | None:
    """The git commit of the checkout this code was loaded from, or None outside a git checkout."""
    try:
        out = subprocess.run(["git", "-C", str(Path(__file__).resolve().parent), "rev-parse", "HEAD"],
                             capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    commit = out.stdout.strip()
    return commit if out.returncode == 0 and commit and all(c in "0123456789abcdef" for c in commit) else None


def mcp_app(hub: Hub, config: Config, chat: ChatManager | None = None, *, codes: paircodes.PairCodes,
            key: tlskey.ServerKey | None = None):
    mcp = build_mcp(hub, config.firmware_sources, config.notes)
    commit = source_commit()  # read once at start: zf update compares it with the checkout's HEAD

    @mcp.custom_route("/status", methods=["GET"], include_in_schema=False)
    async def status(request):  # behind the same BearerAuth as /mcp (loopback only)
        data = {"version": __version__, "commit": commit, **hub.status(), "relay_attached": hub.relay_summary(),
                "firmware": hub.firmware.summary(),  # neither is in hub.status(), which /app also sends
                "devices": hub.peers.summary()}  # the devices that used the public listener: loopback and MCP token only
        if key is not None:  # the key the public listener serves, read once at start (zf status compares the file's)
            data["key"] = {"pin": key.pin, "fingerprint": key.fingerprint}
        if chat is not None:
            data["chat"] = chat.summary()
        return JSONResponse(data, headers={"cache-control": "no-store"})

    app = mcp.streamable_http_app(host="127.0.0.1", max_request_body_size=MCP_BODY_LIMIT)
    stack = BearerAuth(BodyLimit(app, MCP_BODY_LIMIT), config.mcp_token)
    pair = BodyLimit(pairing.local_app(codes, config), pairing.BODY_LIMIT)
    return LoopbackRouter(stack, hooks_app(chat) if chat is not None else None, pair)


def hooks_app(chat: ChatManager):
    """Claude Code's http hooks for the running chat turn; the token is the turn's own."""
    bridge_header, turn_hooks, remote_events = chat.hook_routes()

    def handler(method):
        async def endpoint(request):
            header = request.headers.get("authorization", "")
            token = header[7:] if header.startswith("Bearer ") else ""
            try:
                body = await request.json()
            except ValueError:
                body = None
            if not isinstance(body, dict):
                return JSONResponse({"error": "Body must be a JSON object"}, status_code=400)
            try:
                return JSONResponse(await method(token, body))
            except HookRejected:
                return JSONResponse({"error": "Unauthorized"}, status_code=401)
        return endpoint

    def remote_handler(event: str):
        """Remote Control's hooks: its own token, and whether the Remote Control session id came (never kept)."""
        async def endpoint(request):
            header = request.headers.get("authorization", "")
            token = header[7:] if header.startswith("Bearer ") else ""
            connected = bool(request.headers.get(bridge_header, "").strip())
            try:
                body = await request.json()
            except ValueError:
                body = None
            if not isinstance(body, dict):
                return JSONResponse({"error": "Body must be a JSON object"}, status_code=400)
            try:
                return JSONResponse(await chat.remote_hook(event, token, connected, body))
            except HookRejected:
                return JSONResponse({"error": "Unauthorized"}, status_code=401)
        return endpoint

    routes = [Route(f"/hooks/{name}", handler(method), methods=["POST"]) for name, method in turn_hooks.items()]
    routes += [Route(f"/hooks/remote/{event}", remote_handler(event), methods=["POST"]) for event in remote_events]
    return BodyLimit(Starlette(routes=routes), HOOK_BODY_LIMIT)


class LoopbackRouter:
    """/hooks/* to the chat hooks, /pair* to zf pair's; everything else (MCP, /status, lifespan) to the MCP stack."""

    def __init__(self, mcp, hooks, pair):
        self.mcp, self.hooks, self.pair = mcp, hooks, pair

    async def __call__(self, scope, receive, send):
        path = scope.get("path", "") if scope["type"] == "http" else ""
        if self.hooks is not None and path.startswith("/hooks/"):
            return await self.hooks(scope, receive, send)
        if path == "/pair" or path.startswith("/pair/"):
            return await self.pair(scope, receive, send)
        return await self.mcp(scope, receive, send)


class _Server(uvicorn.Server):
    """uvicorn server that leaves signal handling to us (two servers share one process)."""

    @contextlib.contextmanager
    def capture_signals(self):
        yield


class _Tls13Config(uvicorn.Config):
    """uvicorn's settings with TLS 1.3 only: uvicorn has no option for the lowest version, so it is set on the context
    it builds. A TLS 1.2 client fails the handshake, and a cleartext one gets a closed connection."""

    def load(self) -> None:
        super().load()
        assert self.ssl is not None
        self.ssl.minimum_version = ssl.TLSVersion.TLSv1_3


def listener_config(app, host: str, port: int, *, tls: tlskey.ServerKey | None = None, **options) -> uvicorn.Config:
    """uvicorn's settings for both listeners. proxy_headers=False: the peer stays the kernel's address, never a header's
    (uvicorn's default installs ProxyHeadersMiddleware, which rewrites scope["client"] from X-Forwarded-For).
    With tls (the public listener only), TLS 1.3 with the server's key and certificate."""
    if tls is None:
        return uvicorn.Config(app, host=host, port=port, timeout_graceful_shutdown=GRACEFUL_SHUTDOWN_S,
                              **options, proxy_headers=False, server_header=False, access_log=False, log_level="info")
    return _Tls13Config(app, host=host, port=port, timeout_graceful_shutdown=GRACEFUL_SHUTDOWN_S, **options,
                        ssl_keyfile=str(tls.key_path), ssl_certfile=str(tls.cert_path),
                        proxy_headers=False, server_header=False, access_log=False, log_level="info")


def pairing_metrics(hub: Hub):
    """How a pairing request ended, as a metrics line the phone histories show: never its code, ticket or number."""

    def write(outcome: str, peer: str) -> None:
        if outcome == paircodes.PAIRED:
            hub.metrics.write({"ev": Ev.PAIRED, "peer": peer})
            hub.peers.seen(peer, PAIRING)  # the device that collected the tokens
        else:
            hub.metrics.write({"ev": Ev.PAIR_REFUSED, "peer": peer, "why": outcome})

    return write


async def serve(config: Config, data_dir: Path) -> None:
    """Both listeners. The server's key is loaded (or made, the first time) once, here: its pin goes into every pairing
    QR code and into /status, so both always name the key the public listener serves."""
    key, created = tlskey.ensure(data_dir, config.host)
    if created:
        log.info("created the server's TLS key (fingerprint %s)", key.fingerprint)
    hub = Hub(config.state, keepalive_s=config.keepalive_s, own_address=config.host)
    chat = ChatManager(config, hub, config.state, agents=[ClaudeCode(config), OpenCode(config)])
    await chat.start()
    codes = paircodes.PairCodes(config.base_url(), key.pin, on_end=pairing_metrics(hub))
    public = _Server(listener_config(public_app(hub, config, chat, codes=codes), config.host, config.phone_port,
                                     tls=key, lifespan="off", ws_max_size=65536, ws_ping_interval=20,
                                     ws_ping_timeout=20))
    mcp = _Server(listener_config(mcp_app(hub, config, chat, codes=codes, key=key), "127.0.0.1", config.mcp_port,
                                  lifespan="on"))
    servers = [public, mcp]
    loop = asyncio.get_running_loop()

    def exit_servers(_=None) -> None:
        for s in servers:
            s.should_exit = True

    def stop() -> None:
        # Answer held permission requests and end the chat turn first: a held hook request would
        # otherwise keep uvicorn's graceful shutdown waiting.
        task = loop.create_task(asyncio.wait_for(chat.stop(), GRACEFUL_SHUTDOWN_S))
        task.add_done_callback(exit_servers)

    for sig in (signal.SIGINT, signal.SIGTERM):
        with contextlib.suppress(NotImplementedError):
            loop.add_signal_handler(sig, stop)

    metrics = asyncio.create_task(hub.metrics_loop())
    hub.metrics.write({"ev": "start", "host": config.host, "phone_port": config.phone_port, "mcp_port": config.mcp_port,
                       **hub.restored_gate()})
    tasks = [asyncio.create_task(s.serve()) for s in servers]
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        exit_servers()  # if one listener dies (e.g. port in use), stop the other too
        await asyncio.gather(*tasks, return_exceptions=True)
        for task in done:
            if task.exception():
                raise task.exception()
        if not all(s.started for s in servers):
            raise SystemExit("A listener failed to start (port in use or address not available?)")
    finally:
        with contextlib.suppress(Exception):
            await asyncio.wait_for(chat.stop(), GRACEFUL_SHUTDOWN_S)
        metrics.cancel()
        if hub.session is not None:
            await hub.session.abort("Bridge stopping")
        hub.metrics.write({"ev": "stop"})
