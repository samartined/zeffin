# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The public listener over TLS 1.3 with the server's key, on a real socket (bridge/app.py, listener_config): the key the
peer sees is the pinned one, TLS 1.2 and cleartext get nothing, and the loopback listener stays cleartext."""
import asyncio
import json
import socket
import ssl
import threading
import time

import pytest
from cryptography import x509

from bridge import app, tlskey
from bridge.app import listener_config
from conftest import free_port


async def hello(scope, receive, send):
    if scope["type"] != "http":
        return
    body = json.dumps({"scheme": scope["scheme"], "client": scope["client"][0]}).encode()
    await send({"type": "http.response.start", "status": 200, "headers": [(b"content-type", b"application/json")]})
    await send({"type": "http.response.body", "body": body})


@pytest.fixture
def listener(tmp_path):
    """A uvicorn listener on 127.0.0.1 built by listener_config, with or without the server's key."""
    servers = []

    def start(tls: bool):
        key = tlskey.ensure(tmp_path, "127.0.0.1")[0] if tls else None
        port = free_port()
        server = app._Server(listener_config(hello, "127.0.0.1", port, tls=key, lifespan="off"))
        thread = threading.Thread(target=lambda: asyncio.run(server.serve()), daemon=True)
        thread.start()
        servers.append((server, thread))
        deadline = time.monotonic() + 10
        while not server.started:
            assert time.monotonic() < deadline, "the listener did not start"
            time.sleep(0.05)
        return port, key

    yield start
    for server, thread in servers:
        server.should_exit = True
        thread.join(10)


def client_context(maximum: ssl.TLSVersion | None = None) -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    if maximum is not None:
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.maximum_version = maximum
    return context


def get(sock) -> bytes:
    sock.sendall(b"GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
    data = b""
    while chunk := sock.recv(4096):
        data += chunk
    return data


def test_tls_13_serves_the_pinned_key(listener):
    port, key = listener(tls=True)
    with socket.create_connection(("127.0.0.1", port), timeout=5) as raw, client_context().wrap_socket(raw) as tls:
        assert tls.version() == "TLSv1.3"
        der = tls.getpeercert(binary_form=True)
        assert tlskey.cert_pin(der) == key.pin
        assert x509.load_der_x509_certificate(der).subject.rfc4514_string() == "CN=zeffin"
        reply = get(tls)
    assert reply.startswith(b"HTTP/1.1 200") and b'"scheme": "https"' in reply


def test_zfs_own_client_checks_the_pin_before_it_sends(listener, tmp_path):
    port, key = listener(tls=True)
    with tlskey.client_wrap(key.pin)(socket.create_connection(("127.0.0.1", port), timeout=5)) as tls:
        assert get(tls).startswith(b"HTTP/1.1 200")
    other = tlskey.pin_of(b"another key")
    with pytest.raises(tlskey.Mismatch):
        tlskey.client_wrap(other)(socket.create_connection(("127.0.0.1", port), timeout=5))


def test_tls_12_is_refused(listener):
    port, _ = listener(tls=True)
    with socket.create_connection(("127.0.0.1", port), timeout=5) as raw:
        with pytest.raises((ssl.SSLError, ConnectionResetError)):
            client_context(maximum=ssl.TLSVersion.TLSv1_2).wrap_socket(raw)


def test_a_cleartext_client_gets_nothing(listener, caplog):
    port, _ = listener(tls=True)
    with socket.create_connection(("127.0.0.1", port), timeout=5) as raw:
        raw.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        try:
            data = raw.recv(4096)
        except ConnectionResetError:
            data = b""
    assert b"HTTP" not in data and b"flipper" not in data


def test_the_loopback_listener_stays_cleartext(listener):
    port, _ = listener(tls=False)
    with socket.create_connection(("127.0.0.1", port), timeout=5) as raw:
        assert b'"scheme": "http"' in get(raw)


def test_both_listeners_keep_the_kernels_peer(tmp_path):
    key, _ = tlskey.ensure(tmp_path, "127.0.0.1")
    public = listener_config(hello, "127.0.0.1", 1, tls=key)
    loopback = listener_config(hello, "127.0.0.1", 2)
    assert public.proxy_headers is False and loopback.proxy_headers is False
    assert public.ssl_keyfile == str(key.key_path) and loopback.ssl_keyfile is None
    public.load()
    assert public.ssl.minimum_version == ssl.TLSVersion.TLSv1_3


def test_the_public_listener_sends_no_session_tickets(tmp_path):
    """The server does not resume sessions: every connection presents the key, so the pin is always checked."""
    key, _ = tlskey.ensure(tmp_path, "127.0.0.1")
    config = listener_config(hello, "127.0.0.1", free_port(), tls=key)
    config.load()
    assert config.ssl.num_tickets == 0
