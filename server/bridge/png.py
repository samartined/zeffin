# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""A one-colour PNG, built with the standard library: the probe sends one, and the tests make images with it."""
from __future__ import annotations

import struct
import zlib


def solid_png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    row = b"\x00" + bytes(rgb) * width
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * height)) + chunk(b"IEND", b""))
