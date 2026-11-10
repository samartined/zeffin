# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The pieces behind the ChatManager facade, each on its own: the slot, the bus, the broker, the device ports and the
service's saving."""
import asyncio
import json
from dataclasses import dataclass, field
from types import SimpleNamespace

import pytest

from bridge.chat.broker import PermissionBroker
from bridge.chat.bus import QUEUE_SIZE, ChatBus
from bridge.chat.claude_code import ClaudeCode
from bridge.chat.errors import ChatError, HookRejected
from bridge.chat.history import History
from bridge.chat.index import Index
from bridge.chat.ports import DevicePorts
from bridge.chat.slot import TurnSlot
from bridge.hub import Hub
from bridge.screen import Frame
from bridge.wire import ProtocolError


class Owner:
    """A runner as the slot sees it: the conversation of its running turn, if any."""

    def __init__(self, slot, name):
        self.name, self.conversation = name, None
        slot.register(self)

    def turn_conversation(self):
        return self.conversation

    def turn_info(self):
        return {"conversation": self.conversation, "by": self.name} if self.conversation else None


def test_the_slot_allows_one_turn_on_the_whole_server():
    slot = TurnSlot()
    p, r = Owner(slot, "p"), Owner(slot, "remote")
    assert slot.current() is None and slot.info() is None
    slot.claim(p)
    p.conversation = "a"
    for owner in (p, r):  # busy for every owner, the one running included
        with pytest.raises(ChatError) as e:
            slot.claim(owner)
        assert e.value.code == "busy" and e.value.data == {"conversation": "a"}
    assert slot.other(r) == "a" and slot.other(p) is None
    assert slot.info() == {"conversation": "a", "by": "p"}


def test_the_slot_asks_the_runners_in_order_and_never_outlives_a_turn():
    slot = TurnSlot()
    p, r = Owner(slot, "p"), Owner(slot, "remote")
    r.conversation, p.conversation = "b", "a"  # an edge case today's code allows: chat.status shows the -p turn
    assert slot.info()["by"] == "p"
    p.conversation = r.conversation = None  # a runner that drops its turn frees the slot at once
    slot.claim(p)
    assert slot.last_turn_at is None
    slot.release(p)
    assert slot.last_turn_at is not None


def test_a_stopping_server_starts_no_turn():
    slot = TurnSlot()
    owner = Owner(slot, "p")
    slot.stopping = True
    with pytest.raises(ChatError) as e:
        slot.claim(owner)
    assert e.value.code == "not_ready" and e.value.data == {"problem": "stopping"}


def test_the_bus_keeps_events_and_drops_only_deltas(tmp_path):
    bus = ChatBus(History(tmp_path))
    c = Index(None).new()
    listener = bus.listen()
    record = bus.event(c, {"kind": "text", "id": "x", "text": "hi"})
    assert bus.history.all(c.id) == [record]
    assert listener.queue.get_nowait() == {"type": "chat.event", "conversation": c.id, **record}
    for i in range(QUEUE_SIZE):
        bus.delta(c, 1, "b", i, ".")
    assert listener.queue.qsize() == QUEUE_SIZE * 3 // 4 and not listener.overflow  # deltas stop first
    for _ in range(QUEUE_SIZE):
        bus.status({"ready": True})
    assert listener.overflow  # durable messages never drop: the client is marked and asks again
    bus.unlisten(listener)
    bus.status({"ready": True})
    assert listener not in bus.listeners


@dataclass
class _Turn:
    conversation: object
    token: str = "t" * 40
    session_id: str | None = None
    stopping: bool = False
    interrupted: bool = False
    reported: set = field(default_factory=set)


def _broker(turn, timeout=5.0):
    saved, statuses = [], []

    def turn_for(token):
        if token != turn.token:
            raise HookRejected()
        return turn

    broker = PermissionBroker(ClaudeCode(SimpleNamespace(agent_dir="/agent")), ChatBus(History(None)), turn_for=turn_for, timeout_s=lambda: timeout,
                              changed=lambda c, status=True: saved.append((c, status)),
                              status_changed=lambda: statuses.append(1))
    return broker, saved, statuses


async def test_the_broker_holds_until_answered_and_always_adds_rules_through_the_service():
    turn = _Turn(Index(None).new())
    broker, saved, statuses = _broker(turn)
    task = asyncio.create_task(broker.request(turn.token, {"tool_name": "Bash", "tool_input": {"command": "touch x"}}))
    await asyncio.sleep(0.01)
    (hold_id,) = list(broker.holds)
    assert broker.waiting(turn) and broker.pending()[0]["id"] == hold_id and statuses == [1]
    with pytest.raises(ProtocolError):
        broker.answer(hold_id, "maybe")
    broker.answer(hold_id, "always", ["Bash(touch x)"])
    out = await task
    assert out["hookSpecificOutput"]["decision"]["behavior"] == "allow" and not broker.holds
    assert turn.conversation.rules == ["Bash(touch x)"] and saved == [(turn.conversation, False)]
    with pytest.raises(ChatError) as e:
        broker.answer(hold_id, "once")
    assert e.value.code == "permission_gone"
    with pytest.raises(HookRejected):
        await broker.request("x" * 40, {})


async def test_always_keeps_only_the_offered_rules_the_client_showed():
    suggestions = [{"type": "addRules", "behavior": "allow", "rules": [
        {"toolName": "Bash", "ruleContent": "make test"}, {"toolName": "Bash", "ruleContent": "make:*"}]}]

    async def ask(shown):
        turn = _Turn(Index(None).new())
        broker, _, _ = _broker(turn)
        task = asyncio.create_task(broker.request(turn.token, {"tool_name": "Bash", "tool_input": {"command": "make test"},
                                                               "permission_suggestions": suggestions}))
        await asyncio.sleep(0.01)
        (hold_id,) = list(broker.holds)
        if shown == "bad":
            with pytest.raises(ProtocolError):
                broker.answer(hold_id, "always", "Bash(make:*)")
            shown = None
        broker.answer(hold_id, "always", *([] if shown is None else [shown]))
        decision = (await task)["hookSpecificOutput"]["decision"]
        ends = [e for e in broker.bus.history.memory[turn.conversation.id] if e["kind"] == "permission.end"]
        return turn.conversation.rules, decision, ends[-1]["answer"]

    rules, decision, answer = await ask(["Bash(make test)"])
    assert rules == ["Bash(make test)"] and answer == "always"
    assert decision["updatedPermissions"][0]["rules"] == [{"toolName": "Bash", "ruleContent": "make test"}]
    for shown in (None, [], ["Bash(rm -rf /)"], "bad"):  # nothing shown, or nothing it offered: allow once
        rules, decision, answer = await ask(shown)
        assert rules == [] and decision == {"behavior": "allow"} and answer == "once"


async def test_the_broker_times_out_and_resolves_a_turn_s_holds():
    turn = _Turn(Index(None).new())
    broker, saved, _ = _broker(turn, timeout=0.05)
    out = await broker.request(turn.token, {"tool_name": "Bash", "tool_input": {"command": "touch x"}})
    assert out["hookSpecificOutput"]["decision"]["behavior"] == "deny" and saved == []
    broker.timeout_s = lambda: 5.0
    task = asyncio.create_task(broker.request(turn.token, {"tool_name": "Bash", "tool_input": {"command": "touch y"}}))
    await asyncio.sleep(0.01)
    broker.resolve(turn, "interrupted")
    assert (await task)["hookSpecificOutput"]["decision"]["interrupt"] is True


def test_the_device_ports(tmp_path):
    none = DevicePorts.none()
    assert none.screen(1) is None and none.metric({"ev": "x"}) is None
    hub = Hub(tmp_path, keepalive_s=0)
    ports = DevicePorts.of(hub)
    hub.screen.keep(Frame(seq=3, mono=0.0, wall=0.0, data=b"\x01" * 1024, orientation=1))
    assert ports.screen(3)["seq"] == 3 and ports.screen(3)["orientation"] == 1 and ports.screen(4) is None
    ports.metric({"ev": "chat_turn"})
    assert hub.metrics.recent[-1]["ev"] == "chat_turn"


def test_only_the_service_saves_the_index(manager_parts):
    service, bus, sent = manager_parts
    c = service.index.new()
    service.changed(c)
    assert [m["type"] for m in sent()] == ["chat.conversation", "chat.status"]
    service.changed(status=False)
    assert sent() == []
    assert Index(service.index.path).get(c.id) is not None


@pytest.fixture
def manager_parts(tmp_path):
    from bridge.chat.attachments import Store
    from bridge.chat.service import ChatService
    from bridge.chat.tasks import Tasks
    from bridge.config import Config

    config = Config(host="127.0.0.1", phone_port=1, mcp_port=2, phone_token="p" * 40, mcp_token="m" * 40,
                    view_token="v" * 40, app_token="a" * 40, state_dir=str(tmp_path), free_only=True)
    bus = ChatBus(History(tmp_path / "conversations"))
    service = ChatService(config, [ClaudeCode(config)], tmp_path, bus, Store(tmp_path), Tasks(),
                          status_changed=lambda: bus.status({"ready": False}))
    listener = bus.listen()

    def sent():
        out = []
        while not listener.queue.empty():
            out.append(listener.queue.get_nowait())
        return out

    return service, bus, sent


def test_removed_is_gone_and_an_old_update_keeps_the_files(manager_parts):
    """The old "removed" flag only hid a conversation until the next start; chat.delete deletes for real now."""
    service, _, sent = manager_parts
    c = service.index.new()
    meta = service.store.save(c.id, "a.txt", "text/plain", b"a")
    service.update(c.id, {"removed": True, "pinned": True})
    assert c.pinned and "removed" not in c.summary() and service.store.meta(c.id, meta["id"]) is not None
    assert [m["item"].get("removed") for m in sent() if m["type"] == "chat.conversation"] == [None]


def test_conversations_json_carries_its_format_and_a_newer_one_is_refused(tmp_path):
    import json

    from bridge.chat.index import INDEX_VERSION, IndexVersionError

    path = tmp_path / "conversations.json"
    c = Index(path).new()
    assert json.loads(path.read_text())["version"] == INDEX_VERSION == 2
    assert Index(path).get(c.id) is not None
    path.write_text(json.dumps({"settings": {}, "conversations": []}))  # older files: format 1
    assert Index(path).items == {}
    path.write_text(json.dumps({"version": 3, "settings": {}, "conversations": []}))
    with pytest.raises(IndexVersionError, match="newer zeffin"):
        Index(path)


def test_an_old_turn_end_never_sends_claude_s_stderr(tmp_path):
    """Events kept before stderr stayed in the server log still hold it on disk: chat.history drops it."""
    history = History(tmp_path)
    c = Index(None).new()
    history.append(c, {"kind": "turn.end", "turn": 1, "outcome": "error", "error": "claude_failed",
                       "data": {"exit": 1, "subtype": None, "stderr": "Error: /home/someone/secret"},
                       "duration_ms": 5})
    history.append(c, {"kind": "text", "id": "t", "text": "stderr: keep me"})
    events, _ = history.page(c.id)
    assert events[0]["data"] == {"exit": 1, "subtype": None} and events[1]["text"] == "stderr: keep me"
    assert "stderr" in history.all(c.id)[0]["data"]  # the file itself is left as it was


def test_an_old_turn_end_is_sent_with_the_neutral_error(tmp_path):
    """A turn.end kept before 0.14.0 says claude_failed on disk: chat.history sends agent_failed."""
    history = History(tmp_path)
    c = Index(None).new()
    history.append(c, {"kind": "turn.end", "turn": 1, "outcome": "error", "error": "claude_failed",
                       "data": {"exit": 1, "subtype": None}, "duration_ms": 5})
    history.append(c, {"kind": "turn.end", "turn": 2, "outcome": "error", "error": "expired", "duration_ms": 5})
    history.append(c, {"kind": "text", "id": "t", "text": "claude_failed"})
    events, _ = history.page(c.id)
    assert [e.get("error") for e in events] == ["agent_failed", "expired", None]
    assert events[0]["data"] == {"exit": 1, "subtype": None} and events[2]["text"] == "claude_failed"
    assert history.all(c.id)[0]["error"] == "claude_failed"  # the file itself is left as it was


async def test_a_permission_goes_whole_up_to_its_bound_and_past_it_can_only_be_denied():
    """S1b (security scan before 1.0.0): a permission request's input reaches the card whole up to
    PERMISSION_INPUT_LIMIT (a Write's content far past the 2,000 characters a tool event keeps); past it, the request
    is cut as a tool event, marked not whole, offers no always, and the broker takes only deny, from any client."""
    from bridge.chat.stream import PERMISSION_INPUT_LIMIT, permission_input

    long = "x" * 30_000
    assert permission_input({"content": long}) == ({"content": long}, True)
    edge = {"content": "é" * ((PERMISSION_INPUT_LIMIT - len('{"content": ""}')) // 2)}  # bytes of UTF-8, not characters
    assert len(json.dumps(edge, ensure_ascii=False).encode()) <= PERMISSION_INPUT_LIMIT and permission_input(edge)[1]
    over, whole = permission_input({"content": "x" * PERMISSION_INPUT_LIMIT})
    assert not whole and len(over["content"]) < 2100 and over["content"].endswith(")")

    for content, whole in ((long, True), ("y" * (PERMISSION_INPUT_LIMIT + 1), False)):
        turn = _Turn(Index(None).new())
        broker, _, _ = _broker(turn)
        task = asyncio.create_task(broker.request(turn.token, {
            "tool_name": "Write", "tool_input": {"file_path": "/agent/notes.md", "content": content},
            "permission_suggestions": [{"type": "addRules", "behavior": "allow",
                                        "rules": [{"toolName": "Write", "ruleContent": "notes.md"}]}]}))
        await asyncio.sleep(0.01)
        (hold_id,) = list(broker.holds)
        event = broker.pending()[0]
        if whole:
            assert event["input"]["content"] == content and "whole" not in event and event["always"]
            broker.answer(hold_id, "once")
            assert (await task)["hookSpecificOutput"]["decision"]["behavior"] == "allow"
            continue
        assert event["whole"] is False and event["always"] is False and event["rules"] == []
        assert len(event["input"]["content"]) < 2100
        for answer in ("once", "always"):
            with pytest.raises(ProtocolError):
                broker.answer(hold_id, answer, ["Write(notes.md)"])
        broker.answer(hold_id, "deny")
        assert (await task)["hookSpecificOutput"]["decision"]["behavior"] == "deny"
