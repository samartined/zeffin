# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Before zf sends a token to a listener on this machine, it checks that the listener belongs to its own user.

Any local user can bind the bridge's ports while the bridge is down. So after connect() and before the first byte,
`check` finds the server's end of the connection we made in /proc/net/tcp and /proc/net/tcp6: the entry whose local
end is our peer and whose remote end is our own address. Once the server has accepted it, that entry has the
socket's inode and the listener's uid, so it says who owns the listener we actually reached; a request still in
SYN_RECV has the listener's uid too. Before accept(), though, the ESTABLISHED entry waiting in the listener's queue
shows uid 0 and inode 0 (seen on Linux): its uid tells nothing, so `check` reads again until the server has
accepted, for up to WAIT_S. Checking the connection made, not the port beforehand, leaves no gap between check and
use. Where /proc/net cannot be read, or no accepted entry appears in time, nothing is sent: refusing is the safe
default (the server runs on Linux only). A root-owned listener that accepts shows uid 0 with an inode, and is refused
as any other user's.

`Connection` and `connect` never go through a proxy: no HTTP(S)_PROXY variable can take the token elsewhere. For the
public listener, which speaks TLS, `tls` wraps the checked socket (tlskey.client_wrap: the server's key against its pin)
before the first byte of the request.
"""
from __future__ import annotations

import http.client
import ipaddress
import os
import socket
import sys
import time
from collections.abc import Callable
from pathlib import Path

PROC_NET = Path("/proc/net")
ESTABLISHED, SYN_RECV = "01", "03"  # the server's end, after and before the final ACK (include/net/tcp_states.h)
NOT_ACCEPTED = "0"  # the inode of an ESTABLISHED entry the server has not accepted yet: its uid is not the listener's
WAIT_S = 2.0  # how long the server may take to accept after connect() returns (the clients' own limits are 5 s)


class NotOurs(Exception):
    """The listener is not ours, or who owns it cannot be told: nothing was sent."""


def _address(text: str) -> tuple[ipaddress.IPv4Address | ipaddress.IPv6Address, int]:
    """One `HEXADDR:HEXPORT` of /proc/net/tcp{,6}: the address is 32-bit words in the host's byte order."""
    addr, port = text.split(":")
    raw = bytes.fromhex(addr)
    if sys.byteorder == "little":
        raw = b"".join(raw[i:i + 4][::-1] for i in range(0, len(raw), 4))
    ip = ipaddress.ip_address(raw)
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip, int(port, 16)


def _endpoint(address: tuple) -> tuple[ipaddress.IPv4Address | ipaddress.IPv6Address, int]:
    ip = ipaddress.ip_address(address[0].split("%")[0])
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip, address[1]


def owner(peer: tuple, ours: tuple, proc: Path = PROC_NET) -> int | None:
    """The uid of the server's end of the connection ours -> peer, or None when no table lists it yet, or lists it
    ESTABLISHED but not accepted (inode 0: its uid is not the listener's). Raises NotOurs when neither table can be
    read."""
    server, client = _endpoint(peer), _endpoint(ours)
    read = False
    for name in ("tcp", "tcp6"):
        try:
            lines = (proc / name).read_text().splitlines()[1:]
        except OSError:
            continue
        read = True
        for line in lines:
            fields = line.split()
            if len(fields) < 10 or fields[3] not in (ESTABLISHED, SYN_RECV):
                continue
            try:
                if _address(fields[1]) == server and _address(fields[2]) == client:
                    if fields[3] == ESTABLISHED and fields[9] == NOT_ACCEPTED:
                        return None
                    return int(fields[7])
            except ValueError:
                continue
    if not read:
        raise NotOurs(f"cannot read {proc}/tcp to check who owns the listener")
    return None


def check(sock: socket.socket, proc: Path = PROC_NET, uid: int | None = None, wait_s: float = WAIT_S) -> None:
    """Raise NotOurs unless the listener this connected socket reached belongs to `uid` (default: ours). Reads the
    tables again until the server has accepted the connection, for up to `wait_s`."""
    peer, ours = sock.getpeername(), sock.getsockname()
    where = f"{peer[0]}:{peer[1]}" if ":" not in peer[0] else f"[{peer[0]}]:{peer[1]}"
    expected = os.getuid() if uid is None else uid
    deadline = time.monotonic() + wait_s
    while True:
        found = owner(peer, ours, proc)
        if found is not None or time.monotonic() >= deadline:
            break
        time.sleep(0.01)
    if found is None:
        raise NotOurs(f"cannot tell who owns the listener on {where}: not sending the token")
    if found != expected:
        raise NotOurs(f"the listener on {where} belongs to another user (uid {found}): not sending the token")


def connect(host: str, port: int, timeout: float, proc: Path = PROC_NET) -> socket.socket:
    """A TCP connection to a listener of ours on this machine, checked before anything is sent."""
    sock = socket.create_connection((host, port), timeout=timeout)
    try:
        check(sock, proc)
    except BaseException:
        sock.close()
        raise
    return sock


class Connection(http.client.HTTPConnection):
    """HTTP to a listener of ours on this machine: the socket is checked before the request is written."""

    proc = PROC_NET
    tls: Callable[[socket.socket], socket.socket] | None = None

    def connect(self) -> None:
        sock = connect(self.host, self.port, self.timeout, self.proc)
        if self.tls is not None:
            try:
                sock = self.tls(sock)
            except BaseException:
                sock.close()
                raise
        self.sock = sock


def request(host: str, port: int, method: str, path: str, token: str, *, body: bytes | None = None,
            content_type: str | None = None, timeout: float = 5.0,
            tls: Callable[[socket.socket], socket.socket] | None = None) -> tuple[int, bytes]:
    """One request with the token, to a checked listener: (status, body). Raises NotOurs or OSError (and whatever
    `tls` raises when the server is not the one it expects)."""
    headers = {"Authorization": f"Bearer {token}"}
    if content_type:
        headers["Content-Type"] = content_type
    conn = Connection(host, port, timeout=timeout)
    conn.tls = tls
    try:
        conn.request(method, path, body=body if body is not None else (b"" if method == "POST" else None),
                     headers=headers)
        response = conn.getresponse()
        return response.status, response.read()
    finally:
        conn.close()
