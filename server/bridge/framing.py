# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Length-delimited protobuf framing for the Flipper RPC stream.

Each message is preceded by its length as a base-128 varint. The decoder accepts the stream in
chunks of any size, as the transport delivers them, and returns whole messages.
"""
MAX_FRAME = 65536
# A length up to MAX_FRAME needs at most three varint bytes; more than this is never valid.
_MAX_VARINT_BYTES = 5


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        low, n = n & 0x7F, n >> 7
        out.append(low | (0x80 if n else 0))
        if not n:
            return bytes(out)


def frame(payload: bytes) -> bytes:
    """One message with its length prefix."""
    if not 0 < len(payload) <= MAX_FRAME:
        raise ValueError("Invalid protobuf frame size")
    return varint(len(payload)) + payload


class Decoder:
    """Splits a byte stream into messages; raises ValueError on a length that cannot be valid."""

    def __init__(self) -> None:
        self.buffer = bytearray()

    def _length(self) -> tuple[int, int] | None:
        """(message length, prefix length), or None while the prefix is incomplete."""
        value = 0
        for index, byte in enumerate(self.buffer):
            if index == _MAX_VARINT_BYTES:
                break
            value |= (byte & 0x7F) << (7 * index)
            if not byte & 0x80:
                if not 0 < value <= MAX_FRAME:
                    raise ValueError("Invalid protobuf length")
                return value, index + 1
        if len(self.buffer) >= _MAX_VARINT_BYTES:
            raise ValueError("Invalid varint length")
        return None

    def feed(self, data: bytes) -> list[bytes]:
        if len(data) > MAX_FRAME:
            raise ValueError("Transport chunk too large")
        self.buffer += data
        messages = []
        while self.buffer:
            header = self._length()
            if header is None:
                break
            size, prefix = header
            end = prefix + size
            if len(self.buffer) < end:
                break
            messages.append(bytes(self.buffer[prefix:end]))
            del self.buffer[:end]
        return messages
