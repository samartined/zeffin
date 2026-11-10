# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""A malformed /app message is a ProtocolError and nothing else (hypothesis; settings in tests/hypo.py).

"Malformed" is what wire.ProtocolError says: not JSON, not an object, an unknown type, or a field that is missing, of
the wrong type or out of range. The property drives AppClient.parse and dispatch, the two steps `handle` answers
errors for, so any other exception (a bug) fails it. Well-formed requests that cannot be served now (no such
conversation, a busy slot…) are ChatErrors and are not generated here.
"""
import asyncio
import json

import pytest
from hypothesis import example, given
from hypothesis import strategies as st

from bridge.appws import AppClient
from bridge.chat.attachments import MAX_PER_MESSAGE
from bridge.chat.claude_code import ClaudeCode
from bridge.chat.manager import ChatManager
from bridge.chat.service import MAX_TEXT
from bridge.config import Config
from bridge.hub import Hub
from bridge.wire import EFFORTS, GATE_ACTIONS, MODES, Err, In, ProtocolError
from hypo import SETTINGS
from protocol import problems

CFG = Config(host="127.0.0.1", phone_port=19434, mcp_port=19435, phone_token="p" * 40, mcp_token="m" * 40,
             view_token="v" * 40, app_token="a" * 40, state_dir="/tmp", keepalive_s=0, free_only=True)
CATALOG = [{"value": "opus", "label": "Opus", "efforts": ["low", "high"], "auto": True, "available": True, "reason": None},
           {"value": "haiku", "label": "Haiku", "efforts": [], "auto": True, "available": True, "reason": None}]
JSON = st.recursive(
    st.none() | st.booleans() | st.integers() | st.floats(allow_nan=False, allow_infinity=False) | st.text(max_size=20),
    lambda inner: st.lists(inner, max_size=3) | st.dictionaries(st.text(max_size=8), inner, max_size=3), max_leaves=6)
NOT_TEXT = JSON.filter(lambda v: not isinstance(v, str))
NOT_BOOL = JSON.filter(lambda v: not isinstance(v, bool))
NOT_NUMBER = JSON.filter(lambda v: isinstance(v, bool) or not isinstance(v, (int, float)))


def other_than(good, strategy=JSON):
    return strategy.filter(lambda v: not any(v == g and type(v) is type(g) for g in good))


class Socket:
    async def send_text(self, text):
        pass


class Env:
    """One client with a real chat manager and a conversation, on one event loop for all the examples."""

    def __init__(self, tmp_path):
        self.loop = asyncio.new_event_loop()
        self.hub = Hub(tmp_path, keepalive_s=0)
        self.chat = ChatManager(CFG, self.hub, tmp_path, agent=ClaudeCode(CFG))
        self.chat.models = CATALOG
        self.conversation = self.chat.index.new().id
        self.chat.set_settings({"default_model": "opus", "default_effort": "high"})
        self.client = AppClient(self.hub, Socket(), "100.98.12.7:5000", self.chat)

    def malformed(self, text: str) -> ProtocolError:
        async def process():
            await self.client.dispatch(self.client.parse(text))
        with pytest.raises(ProtocolError) as e:
            self.loop.run_until_complete(process())
        return e.value

    def request(self, kind, **fields) -> ProtocolError:
        return self.malformed(json.dumps({"type": kind, **fields}))

    def close(self):
        self.loop.close()


@pytest.fixture
def env(tmp_path):
    env = Env(tmp_path)
    yield env
    env.close()


@SETTINGS
@given(st.text(max_size=100).filter(lambda t: not _is_json(t)))
@example("")
@example("{")
def test_text_that_is_not_json(env, text):
    assert env.malformed(text).code == Err.BAD_JSON


def _is_json(text: str) -> bool:
    try:
        json.loads(text)
    except ValueError:
        return False
    return True


@SETTINGS
@given(JSON.filter(lambda v: not isinstance(v, dict)))
def test_json_that_is_not_an_object(env, value):
    assert env.malformed(json.dumps(value)).code == Err.BAD_MESSAGE


@SETTINGS
@given(other_than(set(In), JSON))
@example("chat.nothing")
@example("launch")
def test_an_unknown_type(env, kind):
    e = env.request(kind)
    assert e.code == Err.UNKNOWN_TYPE
    assert env.malformed(json.dumps({"no": "type"})).code == Err.UNKNOWN_TYPE


@SETTINGS
@given(other_than(GATE_ACTIONS))
def test_a_gate_action_that_is_not_one(env, action):
    assert env.request(In.GATE, action=action).code == Err.BAD_ACTION
    assert env.request(In.GATE).code == Err.BAD_ACTION


bad_mode = other_than(MODES + (None,))
bad_model = other_than([m["value"] for m in CATALOG] + [None])
bad_effort = other_than(["low", "high", None])  # opus, the default model, offers these two


def test_a_mode_the_schema_takes_but_the_agent_does_not_offer(env):
    """defs.json's mode is a plain string, so the schemas cannot refuse a mode: the server does, with bad_mode."""
    c = env.conversation
    for message in ({"type": In.CHAT_NEW, "mode": "bypassPermissions"},
                    {"type": In.CHAT_SET, "conversation": c, "mode": "bypassPermissions"},
                    {"type": In.CHAT_SETTINGS, "default_mode": "bypassPermissions"}):
        assert not problems("app", message), message
        assert env.malformed(json.dumps(message)).code == Err.BAD_MODE


@SETTINGS
@given(bad_mode, bad_model, bad_effort)
def test_chat_new_with_a_value_out_of_range(env, mode, model, effort):
    assert env.request(In.CHAT_NEW, mode=mode).code == Err.BAD_MODE
    assert env.request(In.CHAT_NEW, model=model).code == Err.BAD_MODEL
    assert env.request(In.CHAT_NEW, effort=effort).code == Err.BAD_EFFORT


@SETTINGS
@given(bad_mode, bad_model, bad_effort)
def test_chat_set_with_a_value_out_of_range(env, mode, model, effort):
    c = env.conversation
    assert env.request(In.CHAT_SET, conversation=c, mode=mode).code == Err.BAD_MODE
    assert env.request(In.CHAT_SET, conversation=c, model=model).code == Err.BAD_MODEL
    assert env.request(In.CHAT_SET, conversation=c, effort=effort).code == Err.BAD_EFFORT
    assert env.request(In.CHAT_SET, conversation=c, effort=None).code == Err.BAD_EFFORT  # opus has levels


@SETTINGS
@given(bad_mode, bad_model, other_than(EFFORTS), NOT_NUMBER)
def test_chat_settings_with_a_value_out_of_range(env, mode, model, effort, timeout):
    assert env.request(In.CHAT_SETTINGS, default_mode=mode).code == Err.BAD_MODE
    assert env.request(In.CHAT_SETTINGS, default_model=model).code == Err.BAD_MODEL
    assert env.request(In.CHAT_SETTINGS, default_effort=effort).code == Err.BAD_EFFORT
    assert env.request(In.CHAT_SETTINGS, permission_timeout_s=timeout).code == Err.BAD_REQUEST


@SETTINGS
@given(st.one_of(NOT_TEXT, st.sampled_from(["", " ", "\n\t", "\x00\x1b", " "])), NOT_BOOL.filter(bool))
def test_chat_update_with_the_wrong_type_or_an_empty_title(env, title, flag):
    c = env.conversation
    assert env.request(In.CHAT_UPDATE, conversation=c, title=title).code == Err.BAD_REQUEST
    for name in ("pinned", "favorite"):
        assert env.request(In.CHAT_UPDATE, conversation=c, **{name: flag}).code == Err.BAD_REQUEST


@SETTINGS
@given(other_than([None], JSON).filter(lambda v: not isinstance(v, int) or isinstance(v, bool)),
       JSON.filter(lambda v: not isinstance(v, int)))
def test_chat_open_with_a_page_that_is_not_one(env, number, limit):
    c = env.conversation
    assert env.request(In.CHAT_OPEN, conversation=c, before=number).code == Err.BAD_REQUEST
    assert env.request(In.CHAT_OPEN, conversation=c, after=number).code == Err.BAD_REQUEST
    assert env.request(In.CHAT_OPEN, conversation=c, limit=limit).code == Err.BAD_REQUEST


@SETTINGS
@given(NOT_TEXT, st.sampled_from(["", " ", "\n"]), NOT_TEXT.filter(lambda v: bool(v) and not isinstance(v, list)),
       st.lists(st.text(max_size=5), min_size=MAX_PER_MESSAGE + 1, max_size=MAX_PER_MESSAGE + 3))
def test_chat_send_with_text_or_attachments_that_are_not_valid(env, text, blank, attached, many):
    c = env.conversation
    assert env.request(In.CHAT_SEND, conversation=c, text=text).code == Err.BAD_REQUEST
    assert env.request(In.CHAT_SEND, conversation=c).code == Err.BAD_REQUEST
    assert env.request(In.CHAT_SEND, conversation=c, text=blank).code == Err.BAD_REQUEST
    assert env.request(In.CHAT_SEND, conversation=c, text="hi", attachments=attached).code == Err.BAD_ATTACHMENT
    assert env.request(In.CHAT_SEND, conversation=c, text="hi", attachments=many).code == Err.BAD_ATTACHMENT
    e = env.request(In.CHAT_SEND, conversation=c, text="x" * (MAX_TEXT + 1))
    assert e.code == Err.TOO_LONG and e.data == {"max": MAX_TEXT}


@SETTINGS
@given(NOT_BOOL)
def test_chat_remote_needs_a_boolean(env, on):
    assert env.request(In.CHAT_REMOTE, conversation=env.conversation, on=on).code == Err.BAD_REQUEST
    assert env.request(In.CHAT_REMOTE, conversation=env.conversation).code == Err.BAD_REQUEST


@SETTINGS
@given(other_than(["once", "always", "deny"]), NOT_TEXT)
def test_chat_permission_answer_that_is_not_one(env, answer, shown):
    from bridge.chat.broker import Hold

    hold = Hold("h1", None, {}, ["Bash(ls)"], env.loop.create_future())
    env.chat.holds["h1"] = hold
    assert env.request(In.CHAT_PERMISSION_ANSWER, id="h1", answer=answer).code == Err.BAD_REQUEST
    ok = {"id": "h1", "answer": "always"}
    for bad in (shown if isinstance(shown, list) and not all(isinstance(r, str) for r in shown) else 7, "Bash(ls)"):
        assert env.request(In.CHAT_PERMISSION_ANSWER, **ok, rules=bad).code == Err.BAD_REQUEST
    assert not hold.future.done()


class Recording(Socket):
    def __init__(self):
        self.sent: list[dict] = []

    async def send_text(self, text):
        self.sent.append(json.loads(text))


async def test_each_answer_is_the_one_it_was_before_the_error_type(env):
    """Same codes, same fields: a top-level error has no data and no request; a chat one has both."""
    ws = Recording()
    client = AppClient(env.hub, ws, "100.98.12.7:5000", env.chat)
    c = env.conversation
    many = [f"id{i}" for i in range(MAX_PER_MESSAGE + 1)]
    for text in ("not json", "[1]", json.dumps({"type": "launch"}), json.dumps({"type": "gate", "action": ["x"]}),
                 json.dumps({"type": "chat.nothing"}), json.dumps({"type": "chat.new", "model": "nope"}),
                 json.dumps({"type": "chat.send", "conversation": c, "text": 5}),
                 json.dumps({"type": "chat.send", "conversation": c, "text": "x" * (MAX_TEXT + 1)}),
                 json.dumps({"type": "chat.send", "conversation": c, "text": "hi", "attachments": 5}),
                 json.dumps({"type": "chat.send", "conversation": c, "text": "hi", "attachments": many}),
                 json.dumps({"type": "chat.open", "conversation": "no-such"})):
        await client.handle(text)
    assert ws.sent == [
        {"type": "error", "error": "bad_json", "message": "Message is not JSON"},
        {"type": "error", "error": "bad_message", "message": "Message must be a JSON object"},
        {"type": "error", "error": "unknown_type", "message": "Unknown message type 'launch'"},
        {"type": "error", "error": "bad_action", "message": "action must be pause, resume or stop"},
        {"type": "error", "error": "unknown_type", "data": {}, "request": "chat.nothing",
         "message": "chat.nothing failed: unknown_type"},
        {"type": "error", "error": "bad_model", "data": {"models": ["opus", "haiku"]}, "request": "chat.new",
         "message": "chat.new failed: bad_model"},
        {"type": "error", "error": "bad_request", "data": {}, "request": "chat.send",
         "message": "chat.send failed: bad_request"},
        {"type": "error", "error": "too_long", "data": {"max": MAX_TEXT}, "request": "chat.send",
         "message": "chat.send failed: too_long"},
        {"type": "error", "error": "bad_attachment", "data": {"ids": None}, "request": "chat.send",
         "message": "chat.send failed: bad_attachment"},
        {"type": "error", "error": "bad_attachment", "data": {"ids": many}, "request": "chat.send",
         "message": "chat.send failed: bad_attachment"},
        {"type": "error", "error": "not_found", "data": {}, "request": "chat.open",
         "message": "chat.open failed: not_found"},
    ]


async def test_a_bug_in_a_chat_request_is_answered_bad_request_without_data(env):
    """Not a ProtocolError: the answer has no data (the error's text is never sent), and /app stays open."""
    class Broken:
        def page(self, *args):
            raise AttributeError("'int' object has no attribute 'get'")

    ws = Recording()
    client = AppClient(env.hub, ws, "100.98.12.7:5000", Broken())
    await client.handle(json.dumps({"type": "chat.open", "conversation": env.conversation}))
    assert ws.sent == [{"type": "error", "error": "bad_request", "request": "chat.open",
                        "message": "chat.open failed: bad_request"}]
