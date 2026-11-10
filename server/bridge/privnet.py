# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The private network the public listener lives on: today Tailscale, and the only module that names it.

It says which addresses are private, how the server finds its own address on the network, a device's name on it, and
the texts that name the network. config.check_public_host (the bind check), `zf init` / `zf set-host` and app.PrivatePeers (the peer
check) ask it; tests/test_privnet.py forbids the network's ranges, command and name anywhere else in bridge/.
Another private network is another implementation of these functions and texts.
"""
from __future__ import annotations

import ipaddress
import json
import os
import selectors
import subprocess
import time

IPAddress = ipaddress.IPv4Address | ipaddress.IPv6Address

NAME = "Tailscale"
RANGES = (ipaddress.ip_network("100.64.0.0/10"), ipaddress.ip_network("fd7a:115c:a1e0::/48"))

HOST_HELP = "Tailscale IP to bind (default: tailscale ip -4)"
SET_HOST_HELP = "change the bound Tailscale IP, keeping tokens"
VIEW_FROM = "from your devices on the tailnet"
BIND_HINT = "Bind the public listener to this server's Tailscale IP (or loopback for tests)"
NO_NAME = "no name from Tailscale"

LOOKUP_TIMEOUT_S = 2.0
LOOKUP_MAX_BYTES = 256 * 1024  # what `tailscale whois --json` may print before the answer is dropped


class Unavailable(Exception):
    """The server's own address on the private network cannot be found."""


def peer_ip(host: str | None) -> IPAddress | None:
    """A peer's address from its text: an IPv4-mapped IPv6 address becomes IPv4, a zone is dropped. None if unparseable."""
    if not host:
        return None
    try:
        ip = ipaddress.ip_address(host.split("%", 1)[0])
    except ValueError:
        return None
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        return ip.ipv4_mapped
    return ip


def is_private(ip: IPAddress) -> bool:
    """Inside the private network's ranges (an IPv4-mapped address counts as its IPv4 one)."""
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return any(ip in net for net in RANGES)


def allowed_peer(host: str | None) -> bool:
    """Whether the public listener serves this peer: private or loopback. A peer it cannot tell is refused."""
    ip = peer_ip(host)
    return ip is not None and (ip.is_loopback or is_private(ip))


def detect_own_ip() -> str:
    """This server's IPv4 address on the private network."""
    try:
        out = subprocess.check_output(["tailscale", "ip", "-4"], text=True, timeout=10)
    except (OSError, subprocess.SubprocessError) as e:
        raise Unavailable("Tailscale is not available; pass --host with this server's Tailscale IP") from e
    lines = [line.strip() for line in out.splitlines() if line.strip()]
    if not lines:
        raise Unavailable("Tailscale returned no IPv4 address")
    return lines[0]


def lookup(ip: IPAddress, timeout: float = LOOKUP_TIMEOUT_S) -> dict[str, str | None] | None:
    """A device's name and OS on the private network, from `tailscale whois --json <ip>`: {"name", "os"}, or None.

    The name is Node.ComputedName, the short name Tailscale's machine list shows; the OS is Node.Hostinfo.OS. Nothing
    else is read: not Node.Name (it names the tailnet), the host name, or the user's login. Any failure (no tailscale,
    a peer it does not know, a timeout, an answer too long or not JSON) is None. Run it in a worker thread: it waits.
    The device chooses its own name: the caller cleans it."""
    try:
        address = str(ipaddress.ip_address(ip))
    except ValueError:
        return None
    out = _read_capped(["tailscale", "whois", "--json", address], timeout, LOOKUP_MAX_BYTES)
    if out is None:
        return None
    try:
        data = json.loads(out)
    except ValueError:
        return None
    node = data.get("Node") if isinstance(data, dict) else None
    if not isinstance(node, dict):
        return None
    hostinfo = node.get("Hostinfo") if isinstance(node.get("Hostinfo"), dict) else {}
    name, os_name = node.get("ComputedName"), hostinfo.get("OS")
    found = {"name": name if isinstance(name, str) and name else None,
             "os": os_name if isinstance(os_name, str) and os_name else None}
    return found if found["name"] or found["os"] else None


def _read_capped(argv: list[str], timeout: float, cap: int) -> bytes | None:
    """A command's standard output, or None when it fails, takes longer than timeout or prints more than cap bytes:
    then it is killed. No shell; its other streams go nowhere."""
    try:
        proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except (OSError, ValueError, subprocess.SubprocessError):
        return None
    assert proc.stdout is not None
    deadline, chunks, size = time.monotonic() + timeout, [], 0
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(proc.stdout, selectors.EVENT_READ)
            while True:
                left = deadline - time.monotonic()
                if left <= 0:
                    return None
                if not selector.select(left):
                    continue
                chunk = os.read(proc.stdout.fileno(), 65536)
                if not chunk:
                    break
                size += len(chunk)
                if size > cap:
                    return None
                chunks.append(chunk)
        code = proc.wait(timeout=max(deadline - time.monotonic(), 0.01))
    except (OSError, subprocess.SubprocessError):
        return None
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait()
        proc.stdout.close()
    return b"".join(chunks) if code == 0 else None
