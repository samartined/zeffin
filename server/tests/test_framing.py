# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
import random

import pytest

from bridge.framing import Decoder, frame


def test_arbitrary_packet_boundaries():
    payloads = [bytes([n % 256]) * n for n in (1, 127, 128, 1024, 65536)]
    stream = b"".join(map(frame, payloads))
    decoder, actual = Decoder(), []
    rng = random.Random(42)
    while stream:
        size = rng.randint(1, 700)
        actual.extend(decoder.feed(stream[:size]))
        stream = stream[size:]
    assert actual == payloads
    assert decoder.buffer == b""


@pytest.mark.parametrize("bad", [b"\x00", b"\xff" * 5, b"\x81\x80\x04"])
def test_bad_frame_rejected(bad):
    with pytest.raises(ValueError):
        Decoder().feed(bad)
