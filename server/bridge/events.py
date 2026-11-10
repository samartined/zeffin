# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The names of the metrics log's events (state/metrics.jsonl, the "ev" field).

`zeffin report`, the app's Diagnostics (stats.py) and the phone histories of the app and the web view
read them; tests/test_protocol.py checks that every "ev" written in bridge/ is one of these.
"""
from __future__ import annotations

from enum import StrEnum


class Ev(StrEnum):
    START = "start"                # the server started
    STOP = "stop"
    WS_OPEN = "ws_open"            # the relay's WebSocket opened
    READY = "ready"                # the relay said ready
    FIRST_FRAME = "first_frame"
    CLOSE = "close"                # a relay session ended
    WS_REJECTED = "ws_rejected"    # a relay connection or check refused
    SETTLE = "settle"              # bytes dropped while the Flipper's stream settled
    STREAM = "stream"              # the screen stream started or failed
    DETECT = "detect"              # a firmware detection ended: done (with the apps learned) or failed (firmware.py)
    PING = "ping"
    WINDOW = "window"              # one minute's frame figures
    PAIRED = "paired"              # the app collected the tokens after the owner confirmed its pairing request
    PAIR_REFUSED = "pair_refused"  # a pairing request got nothing: "why" is refused, expired, lost or cancelled
    APP_OPEN = "app_open"
    APP_CLOSE = "app_close"
    APP_REJECTED = "app_rejected"
    PEER_REFUSED = "peer_refused"  # the public listener refused a peer outside the private network (app.PrivatePeers)
    CHAT_TURN = "chat_turn"


# The relay's connection attempts and their outcome: the phone histories of the app and the web view.
PHONE_EVENTS = frozenset({Ev.WS_OPEN, Ev.READY, Ev.CLOSE, Ev.WS_REJECTED, Ev.STREAM, Ev.FIRST_FRAME, Ev.PAIRED})
# The web view's phone connections also show the pairing requests that got nothing (/app's phone.history does not yet).
VIEW_PHONE_EVENTS = PHONE_EVENTS | {Ev.PAIR_REFUSED}
