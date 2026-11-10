# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""docs/protocol/rpc/vectors.json: the exact bytes of what the relay sends and reads on the Flipper's RPC link.

The app's RpcCodecTest reads the same file, so a change on either side fails the other (README.md there)."""
import json
from pathlib import Path

import pytest

from bridge import pb
from bridge.framing import Decoder, frame
from bridge.hub import Hub
from bridge.phone import (RELAY_ID_MAX, RELAY_ID_MIN, SERVER_ID_MAX, SETTLE_ID_MAX, SETTLE_ID_MIN, PhoneSession,
                          build)

VECTORS = json.loads((Path(__file__).resolve().parents[2] / "docs/protocol/rpc/vectors.json").read_text())
REQUESTS = {v["name"]: v for v in VECTORS["requests"]}
REPLIES = {v["name"]: v for v in VECTORS["replies"]}
RANGES = {name: tuple(bounds) for name, bounds in VECTORS["ranges"].items()}


def decode_one(hex_: str):
    raw = bytes.fromhex(hex_)
    messages = list(Decoder().feed(raw))
    assert len(messages) == 1, "one varint-delimited message per vector"
    return pb.Main.FromString(messages[0]), raw


def test_the_ranges_are_phone_pys():
    assert RANGES == {"unsolicited": (0, 0), "server": (1, SERVER_ID_MAX), "settle": (SETTLE_ID_MIN, SETTLE_ID_MAX),
                      "relay": (RELAY_ID_MIN, RELAY_ID_MAX), "reserved": (1 << 30, 0xFFFFFFFF)}


def test_the_relay_sends_exactly_the_keys_and_the_stream():
    keys = [k for k in pb.gui.InputKey.keys()]
    kinds = ["PRESS", "SHORT", "LONG", "RELEASE"]  # never REPEAT
    expected = {f"input.{k.lower()}.{t.lower()}" for k in keys for t in kinds} | {"screen_stream.start",
                                                                                 "screen_stream.stop"}
    assert {v["name"] for v in VECTORS["requests"] if v["sender"] == "relay"} == expected
    assert len(keys) == 6
    assert {v["name"] for v in VECTORS["requests"] if v["sender"] != "relay"} == {"settle.stop"}


@pytest.mark.parametrize("name", sorted(REQUESTS))
def test_each_request_is_built_byte_for_byte(name):
    v = REQUESTS[name]
    low, high = RANGES["relay"] if v["sender"] == "relay" else RANGES["settle"]
    assert low <= v["command_id"] <= high
    payload = {}
    if v["message"] == "gui_send_input_event_request":
        f = v["fields"]
        assert pb.gui.InputKey.Value(f["key"]) == f["key_value"]
        assert pb.gui.InputType.Value(f["type"]) == f["type_value"]
        payload = {"key": f["key"], "type": f["type"]}
    message = build(v["message"], payload)
    message.command_id = v["command_id"]
    assert frame(message.SerializeToString()).hex() == v["hex"]
    decoded, _ = decode_one(v["hex"])
    assert decoded.WhichOneof("content") == v["message"] and decoded.command_id == v["command_id"]
    assert not decoded.has_next


def test_the_relay_s_ids_reach_the_top_of_its_range():
    assert max(v["command_id"] for v in VECTORS["requests"]) == RELAY_ID_MAX


@pytest.mark.parametrize("name", sorted(REPLIES))
def test_each_reply_decodes_to_its_fields(name):
    v = REPLIES[name]
    message, raw = decode_one(v["hex"])
    assert message.command_id == v["command_id"]
    assert pb.CommandStatus.Value(v["command_status"]) == v["command_status_value"] == message.command_status
    assert message.has_next == v["has_next"]
    assert message.WhichOneof("content") == v["content"]
    if v["content"] == "gui_screen_frame":
        f = v["fields"]
        assert message.command_id == 0
        assert message.gui_screen_frame.data == bytes.fromhex(f["data_hex"]) and len(f["data_hex"]) == 2048
        assert message.gui_screen_frame.orientation == f["orientation_value"]
        assert pb.gui.ScreenOrientation.Value(f["orientation"]) == f["orientation_value"]
    else:
        assert any(low <= v["command_id"] <= high for low, high in (RANGES["relay"], RANGES["settle"]))
    assert frame(message.SerializeToString()).hex() == v["hex"]  # unknown fields are kept, in order, at the end
    if name == "unknown_fields":
        known = pb.Main(command_id=message.command_id)
        known.empty.SetInParent()
        assert len(raw) > len(frame(known.SerializeToString())) + 20


def test_the_frame_data_follows_its_pattern():
    data = bytes.fromhex(REPLIES["screen_frame"]["fields"]["data_hex"])
    assert data == bytes((i * 7 + 3) % 256 for i in range(1024))
    tricky = bytearray(bytes.fromhex(REPLIES["screen_frame.settle_lookalike"]["fields"]["data_hex"]))
    differs = [i for i in range(1024) if tricky[i] != data[i]]
    assert differs and min(differs) >= 100 and max(differs) < 310


def settling(tmp_path, settle_id: int) -> PhoneSession:
    session = PhoneSession(Hub(tmp_path, keepalive_s=0), None, None, "test")
    session._settle_id = settle_id
    return session


def test_the_settle_stop_s_reply_is_found_and_its_lookalike_is_not(tmp_path):
    stop = REQUESTS["settle.stop"]
    reply = REPLIES["settle.stop.ok"]
    lookalike = REPLIES["screen_frame.settle_lookalike"]
    assert reply["command_id"] == stop["command_id"] == lookalike["fields"]["lookalike_for"]
    session = settling(tmp_path, stop["command_id"])
    assert session._after_settle_reply(bytes.fromhex(lookalike["hex"])) is None
    after = bytes.fromhex(REPLIES["screen_frame"]["hex"])
    assert session._after_settle_reply(bytes.fromhex(reply["hex"]) + after) == after


def test_the_lookalike_holds_the_marker(tmp_path):
    from bridge.framing import varint
    v = REPLIES["screen_frame.settle_lookalike"]
    data = bytes.fromhex(v["fields"]["data_hex"])
    assert data.count(b"\x08" + varint(v["fields"]["lookalike_for"])) == 2
