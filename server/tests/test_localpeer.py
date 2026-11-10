# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""zf sends a token only to a listener of its own user: localpeer reads the server's end in /proc/net/tcp{,6}."""
import http.server
import ipaddress
import os
import socket
import sys
import threading

import pytest

from bridge import localpeer

HEADER = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"


def hexaddr(ip: str, port: int) -> str:
    """Encode an endpoint as /proc/net/tcp does: 32-bit words in the host's byte order."""
    raw = ipaddress.ip_address(ip).packed
    if sys.byteorder == "little":
        raw = b"".join(raw[i:i + 4][::-1] for i in range(0, len(raw), 4))
    return f"{raw.hex().upper()}:{port:04X}"


def row(local, remote, state, uid, inode=12345) -> str:
    return (f"   0: {hexaddr(*local)} {hexaddr(*remote)} {state} 00000000:00000000 00:00000000 00000000"
            f" {uid:>5}        0 {inode} 1 0000000000000000 20 4 30 10 -1\n")


def proc(tmp_path, tcp="", tcp6=None):
    root = tmp_path / "net"
    root.mkdir(exist_ok=True)
    (root / "tcp").write_text(HEADER + tcp)
    if tcp6 is not None:
        (root / "tcp6").write_text(HEADER + tcp6)
    return root


SERVER, CLIENT = ("127.0.0.1", 9435), ("127.0.0.1", 50000)


def test_finds_the_server_end_not_ours(tmp_path):
    root = proc(tmp_path, row(CLIENT, SERVER, "01", 1000) + row(SERVER, CLIENT, "01", 2000))
    assert localpeer.owner(SERVER, CLIENT, root) == 2000  # our own end (uid 1000) is not the listener's


def test_syn_recv_counts_and_other_states_do_not(tmp_path):
    assert localpeer.owner(SERVER, CLIENT, proc(tmp_path, row(SERVER, CLIENT, "03", 7))) == 7
    assert localpeer.owner(SERVER, CLIENT, proc(tmp_path, row(SERVER, CLIENT, "0A", 7))) is None  # LISTEN


def test_ipv6_and_ipv4_mapped(tmp_path):
    root = proc(tmp_path, "", row(("::ffff:127.0.0.1", 9435), ("::ffff:127.0.0.1", 50000), "01", 42)
                + row(("fd7a:115c:a1e0::1", 9434), ("fd7a:115c:a1e0::1", 50001), "01", 43))
    assert localpeer.owner(SERVER, CLIENT, root) == 42
    assert localpeer.owner(("fd7a:115c:a1e0::1", 9434, 0, 0), ("fd7a:115c:a1e0::1", 50001, 0, 0), root) == 43


def test_an_entry_not_accepted_yet_says_nothing(tmp_path):
    """Before accept(), Linux shows the server's ESTABLISHED end with uid 0 and inode 0; SYN_RECV, whose inode
    is 0 too, keeps the listener's uid."""
    assert localpeer.owner(SERVER, CLIENT, proc(tmp_path, row(SERVER, CLIENT, "01", 0, inode=0))) is None
    assert localpeer.owner(SERVER, CLIENT, proc(tmp_path, row(SERVER, CLIENT, "03", 1000, inode=0))) == 1000
    assert localpeer.owner(SERVER, CLIENT, proc(tmp_path, row(SERVER, CLIENT, "01", 0))) == 0  # root's, accepted


def test_unreadable_proc_refuses(tmp_path):
    with pytest.raises(localpeer.NotOurs, match="cannot read"):
        localpeer.owner(SERVER, CLIENT, tmp_path / "missing")


class FakeSocket:
    def getpeername(self):
        return SERVER

    def getsockname(self):
        return CLIENT


def test_check_refuses_another_user_and_says_who(tmp_path):
    root = proc(tmp_path, row(SERVER, CLIENT, "01", 2000))
    localpeer.check(FakeSocket(), root, uid=2000)
    with pytest.raises(localpeer.NotOurs, match=r"127\.0\.0\.1:9435 belongs to another user \(uid 2000\)"):
        localpeer.check(FakeSocket(), root, uid=1000)


def test_check_reads_again_until_the_entry_appears(tmp_path, monkeypatch):
    root = proc(tmp_path)
    reads = []
    owner = localpeer.owner

    def later(peer, ours, where):
        reads.append(1)
        if len(reads) == 2:
            (root / "tcp").write_text(HEADER + row(SERVER, CLIENT, "01", 1000))
        return owner(peer, ours, where)

    monkeypatch.setattr(localpeer, "owner", later)
    localpeer.check(FakeSocket(), root, uid=1000)
    assert len(reads) == 2  # missing on the first read, found on the second


def _accepted_on_read(root, n, uid, monkeypatch):
    """The server's end shows not accepted (uid 0, inode 0) until the n-th read, then accepted with `uid`."""
    (root / "tcp").write_text(HEADER + row(SERVER, CLIENT, "01", 0, inode=0))
    reads = []
    owner = localpeer.owner

    def later(peer, ours, where):
        reads.append(1)
        if len(reads) == n:
            (root / "tcp").write_text(HEADER + row(SERVER, CLIENT, "01", uid))
        return owner(peer, ours, where)

    monkeypatch.setattr(localpeer, "owner", later)
    return reads


def test_check_waits_for_the_server_to_accept(tmp_path, monkeypatch):
    root = proc(tmp_path)
    reads = _accepted_on_read(root, 3, 1000, monkeypatch)
    localpeer.check(FakeSocket(), root, uid=1000)
    assert len(reads) == 3  # uid 0 twice, not taken for the listener's


def test_check_refuses_a_connection_never_accepted(tmp_path):
    root = proc(tmp_path, row(SERVER, CLIENT, "01", 0, inode=0))
    with pytest.raises(localpeer.NotOurs, match="cannot tell who owns"):
        localpeer.check(FakeSocket(), root, uid=0, wait_s=0.05)  # not even when uid 0 is the one expected


def test_check_refuses_another_user_who_accepts(tmp_path, monkeypatch):
    root = proc(tmp_path)
    _accepted_on_read(root, 2, 0, monkeypatch)
    with pytest.raises(localpeer.NotOurs, match=r"belongs to another user \(uid 0\)"):
        localpeer.check(FakeSocket(), root, uid=1000)


def test_check_refuses_when_the_entry_never_appears(tmp_path):
    with pytest.raises(localpeer.NotOurs, match="cannot tell who owns"):
        localpeer.check(FakeSocket(), proc(tmp_path), uid=1000, wait_s=0.02)


class Recorder(http.server.BaseHTTPRequestHandler):
    seen: list = []

    def do_GET(self):
        Recorder.seen.append(self.headers.get("Authorization"))
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{"ok": true}')

    def log_message(self, *args):
        pass


@pytest.fixture
def listener():
    server = http.server.HTTPServer(("127.0.0.1", 0), Recorder)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    Recorder.seen = []
    yield server.server_address[1]
    server.shutdown()
    server.server_close()


@pytest.mark.skipif(not os.path.exists("/proc/net/tcp"), reason="needs Linux /proc/net")
def test_real_listener_of_ours_gets_the_request_through_no_proxy(listener, monkeypatch):
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:9")  # a proxy variable changes nothing
    monkeypatch.setenv("http_proxy", "http://127.0.0.1:9")
    assert localpeer.request("127.0.0.1", listener, "GET", "/status", "fake-token") == (200, b'{"ok": true}')
    assert Recorder.seen == ["Bearer fake-token"]


@pytest.mark.skipif(not os.path.exists("/proc/net/tcp"), reason="needs Linux /proc/net")
def test_another_users_listener_never_sees_the_token(listener, monkeypatch):
    check = localpeer.check
    monkeypatch.setattr(localpeer, "check", lambda sock, proc: check(sock, proc, uid=os.getuid() + 1))
    with pytest.raises(localpeer.NotOurs, match="belongs to another user"):
        localpeer.request("127.0.0.1", listener, "GET", "/status", "fake-token")
    assert Recorder.seen == []


@pytest.mark.skipif(not os.path.exists("/proc/net/tcp"), reason="needs Linux /proc/net")
def test_a_real_listener_that_accepts_late_is_ours():
    """`zf pair` over SSH reached the service before it accepted, and read uid 0. A listener that accepts only
    after a while passes; one that never accepts is refused, without a byte sent."""
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(4)
    accepted: list[socket.socket] = []
    timer = threading.Timer(0.3, lambda: accepted.append(server.accept()[0]))
    timer.start()
    try:
        localpeer.connect("127.0.0.1", server.getsockname()[1], 5.0).close()
        timer.join()
        sock = socket.create_connection(server.getsockname(), timeout=5.0)
        with pytest.raises(localpeer.NotOurs, match="cannot tell who owns"):
            localpeer.check(sock, wait_s=0.3)
        sock.close()
    finally:
        timer.cancel()
        for s in accepted:
            s.close()
        server.close()


def test_connect_closes_the_socket_it_refuses(tmp_path, monkeypatch):
    closed = []

    class Sock(FakeSocket):
        def close(self):
            closed.append(True)

    monkeypatch.setattr(socket, "create_connection", lambda *a, **k: Sock())
    with pytest.raises(localpeer.NotOurs):
        localpeer.connect("127.0.0.1", 9435, 1.0, tmp_path / "missing")
    assert closed == [True]
