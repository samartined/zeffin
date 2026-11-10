# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Properties that hold for any input (hypothesis; settings in tests/hypo.py): framing, the stream parser and the text
helpers. A failing input is kept as an @example next to the property."""
import json
from pathlib import Path

from hypothesis import example, given
from hypothesis import strategies as st

from bridge import framing
from bridge.chat.stream import StreamParser
from bridge.text import clean_text, one_line
from hypo import SETTINGS

FIXTURES = Path(__file__).parent / "fixtures" / "stream"


# ---- framing: any message, split into any chunks, comes back whole and in order
@st.composite
def chunked(draw):
    # 128 bytes and more need a two-byte length prefix: a chunk can end inside it
    sizes = st.binary(min_size=1, max_size=40) | st.binary(min_size=128, max_size=400)
    messages = draw(st.lists(sizes, min_size=1, max_size=8))
    stream = b"".join(framing.frame(m) for m in messages)
    cuts = sorted(set(draw(st.lists(st.integers(0, len(stream)), max_size=20))) | {0, len(stream)})
    return messages, [stream[a:b] for a, b in zip(cuts, cuts[1:])]


def cut_after(messages, *at):
    """The messages framed, in chunks that end after each of these byte counts."""
    stream = b"".join(framing.frame(m) for m in messages)
    cuts = [0, *at, len(stream)]
    return messages, [stream[a:b] for a, b in zip(cuts, cuts[1:])]


@SETTINGS
@given(chunked())
@example(cut_after([b"\x07" * 200], 1))  # a 2-byte prefix cut after its first byte
@example(cut_after([b"\x07" * 200, b"x"], 1, 2))  # ... and after the whole prefix
@example(cut_after([b"\x07" * 20000], 1))  # a 3-byte prefix, cut after one and after two bytes
@example(cut_after([b"\x07" * 20000], 2))
def test_framing_gives_back_every_message_whole_and_in_order(case):
    messages, chunks = case
    decoder = framing.Decoder()
    got = [m for chunk in chunks for m in decoder.feed(chunk)]
    assert got == messages and not decoder.buffer


def test_framing_a_largest_message_in_two_chunks():
    big = bytes(range(256)) * (framing.MAX_FRAME // 256)
    stream = framing.frame(big) + framing.frame(b"x")
    decoder = framing.Decoder()
    assert [m for chunk in (stream[:40000], stream[40000:]) for m in decoder.feed(chunk)] == [big, b"x"]


@SETTINGS
@given(st.lists(st.binary(min_size=1, max_size=200), min_size=1, max_size=5))
def test_framing_one_byte_at_a_time(messages):
    stream = b"".join(framing.frame(m) for m in messages)
    decoder = framing.Decoder()
    assert [m for i in range(len(stream)) for m in decoder.feed(stream[i:i + 1])] == messages


# ---- the stream parser never raises
JSON = st.recursive(
    st.none() | st.booleans() | st.integers() | st.floats(allow_nan=False, allow_infinity=False) | st.text(max_size=20),
    lambda inner: st.lists(inner, max_size=4) | st.dictionaries(st.text(max_size=12), inner, max_size=4),
    max_leaves=12)
KEYS = ["type", "subtype", "event", "message", "content", "delta", "index", "error", "id", "name", "input", "text",
        "tool_use_id", "is_error", "parent_tool_use_id", "rate_limit_info", "status", "session_id", "attempt"]
TYPES = ["system", "stream_event", "assistant", "user", "result", "rate_limit_event", "message_start",
         "content_block_start", "content_block_delta", "text", "text_delta", "tool_use", "tool_result", "image",
         "init", "permission_denied", "api_retry", "compact_boundary", "allowed"]
KNOWN = st.sampled_from(TYPES)
SHAPED = st.dictionaries(st.sampled_from(KEYS), st.one_of(KNOWN, JSON, st.dictionaries(st.sampled_from(KEYS), st.one_of(
    KNOWN, JSON, st.lists(st.dictionaries(st.sampled_from(KEYS), st.one_of(KNOWN, JSON), max_size=5), max_size=3)),
    max_size=5)), max_size=6)
REAL = [line for path in sorted(FIXTURES.glob("*.jsonl")) for line in path.read_text().splitlines()]


def mutate(value, data):
    """The value with one leaf (or a whole branch) replaced by something of another shape."""
    if isinstance(value, dict) and value and data.draw(st.booleans()):
        key = data.draw(st.sampled_from(sorted(value)))
        return {**value, key: mutate(value[key], data)}
    if isinstance(value, list) and value and data.draw(st.booleans()):
        i = data.draw(st.integers(0, len(value) - 1))
        return value[:i] + [mutate(value[i], data)] + value[i + 1:]
    return data.draw(JSON)


def read(line: str, parser: StreamParser) -> None:
    """What the agent does with each line (claude_code.py): bad JSON and non-objects are skipped."""
    try:
        obj = json.loads(line)
    except ValueError:
        return
    if isinstance(obj, dict):
        parser.feed(obj)


@SETTINGS
@given(st.lists(st.text(max_size=200) | JSON.map(json.dumps) | SHAPED.map(json.dumps), max_size=12))
def test_the_stream_parser_never_raises_on_any_line(lines):
    parser = StreamParser()
    for line in lines:
        read(line, parser)


@SETTINGS
@given(st.data())
def test_the_stream_parser_never_raises_on_cut_or_changed_real_lines(data):
    parser = StreamParser()
    for _ in range(data.draw(st.integers(1, 6))):
        line = data.draw(st.sampled_from(REAL))
        how = data.draw(st.sampled_from(["whole", "cut", "changed"]))
        if how == "cut":
            line = line[:data.draw(st.integers(0, len(line)))]
        elif how == "changed":
            line = json.dumps(mutate(json.loads(line), data))
        read(line, parser)


def stream_event(inner):
    return {"type": "stream_event", "event": inner}


# Every nested field that was read as an object or a list without checking, as a value of another shape.
@SETTINGS
@given(st.lists(SHAPED, max_size=10))
@example([stream_event("system")])
@example([stream_event({"type": "message_start", "message": "x"})])
@example([stream_event({"type": "message_start", "message": {"id": "m"}}),
          stream_event({"type": "content_block_start", "content_block": "x", "index": 0}),
          stream_event({"type": "content_block_start", "content_block": {"type": "text"}, "index": 0}),
          stream_event({"type": "content_block_delta", "delta": 7, "index": 0})])
@example([{"type": "assistant", "message": "x"}])
@example([{"type": "assistant", "message": {"content": 5}}])
@example([{"type": "user", "message": "x"}])
@example([{"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "t", "content": 5}]}}])
@example([{"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "t", "content": {"a": 1}}]}}])
def test_the_stream_parser_never_raises_on_shaped_events(events):
    parser = StreamParser()
    for event in events:
        parser.feed(event)


# ---- text helpers keep their promises
@SETTINGS
@given(st.text(), st.integers(1, 200))
@example("a\u2028b\x00c\u0085d\r\ne\u202ef", 50)
def test_one_line_never_leaves_line_breaks_or_control_characters(text, limit):
    out = one_line(text, limit)
    assert out.isprintable() and len(out) <= limit
    assert out == " ".join(out.split())


@SETTINGS
@given(st.text(), st.integers(1, 200))
@example("\x1b[2J\nhi\u202e", 64)
def test_clean_text_never_leaves_line_breaks_or_control_characters(text, limit):
    out = clean_text(text, limit)
    assert out is None or (out.isprintable() and 0 < len(out) <= limit)


@SETTINGS
@given(st.one_of(st.none(), st.integers(), st.lists(st.text()), st.binary()))
def test_clean_text_is_none_for_what_is_not_text(value):
    assert clean_text(value) is None
