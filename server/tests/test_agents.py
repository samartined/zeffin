# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The agents on /app: hello and chat.status list every agent the server knows, each conversation has
its agent, and what a conversation offers (models, modes, efforts, Remote Control) is its agent's."""
import asyncio
import json
from pathlib import Path

import pytest

from bridge.agent import HookRoutes, Output, Started, TurnEnd
from bridge.chat.claude_code import ClaudeCode
from bridge.chat.index import INDEX_VERSION, Index, IndexVersionError
from bridge.chat.manager import ChatError, ChatManager
from bridge.config import Config
from bridge.hub import Hub
from bridge.wire import AgentId, Err, Outcome, ProtocolError, TurnError

from protocol import problems

CFG = Config(host="127.0.0.1", phone_port=19434, mcp_port=19435, phone_token="p" * 40, mcp_token="m" * 40,
             view_token="v" * 40, app_token="a" * 40, state_dir="/tmp", keepalive_s=0, free_only=True)
PROTOCOL = Path(__file__).resolve().parents[2] / "docs" / "protocol" / "app"


class OtherAgent:
    """A second agent behind the port, ready, without Remote Control: two free models, two modes, no "auto"."""
    id = "other"
    remote = None
    configured = True

    def problem(self):
        return None

    def remote_problem(self):
        return None

    async def check(self):
        pass

    def identity(self):
        return {"name": "Other", "version": "1.0.0"}

    def mode_options(self):
        return [{"value": "default", "label": "Manual"}, {"value": "plan", "label": "Plan"}]

    def login(self):
        return {"method": "free", "subscription": False}

    def models(self):
        return [{"value": "free/vision", "label": "Vision", "efforts": ["low", "high"], "auto": False,
                 "images": True, "available": True, "reason": None, "paid": False},
                {"value": "free/text", "label": "Text", "efforts": [], "auto": False, "images": False,
                 "available": False, "reason": "not_signed_in", "paid": False}]

    def capabilities(self):
        return {"remote": None, "attachments": False}

    def summary_fields(self):
        return {}

    def hook_routes(self):
        return HookRoutes(header="x", request="r", denied="d", remote=())

    def forget(self, session):
        return 0


@pytest.fixture
def two(tmp_path):
    manager = ChatManager(CFG, Hub(tmp_path, keepalive_s=0), tmp_path, agents=[ClaudeCode(CFG), OtherAgent()])
    manager.problem = lambda: None
    return manager


def test_hello_and_chat_status_list_every_agent(two):
    hello = {"type": "hello", "protocol": 1, "server": {"version": "1.0.0", "name": "my-server"},
             "features": ["chat"], **two.capabilities()}
    assert not problems("server", hello), problems("server", hello)
    assert [a["id"] for a in hello["agents"]] == [AgentId.CLAUDE_CODE, "other"]
    claude, other = hello["agents"]
    assert claude["name"] == "Claude Code" and claude["remote"]["name"] == "Remote Control" and claude["attachments"]
    assert all(m["images"] for m in claude["models"]) and claude["login"] == {"method": None, "subscription": False}
    assert other["remote"] is None and other["mode_options"][1]["value"] == "plan"
    assert other["ready"] and other["problem"] is None and other["configured"]
    status = {"type": "chat.status", **two.status()}
    assert not problems("server", status), problems("server", status)
    assert not {"ready", "problem", "login", "models", "mode_options"} & set(status)
    assert status["agents"] == hello["agents"]
    settings = status["settings"]
    assert settings["default_agent"] == AgentId.CLAUDE_CODE and set(settings["agents"]) == {"claude-code", "other"}
    assert settings["agents"]["other"]["default_model"] == "free/vision"


def test_a_conversation_has_its_agent_and_offers_only_its_agents_choices(two):
    c = two.new(agent="other")
    assert c["agent"] == "other" and c["model"] == "free/vision" and c["effort"] in ("low", "high")
    assert two.new()["agent"] == AgentId.CLAUDE_CODE
    with pytest.raises(ProtocolError) as e:
        two.new(agent="nobody")
    assert e.value.code == Err.BAD_AGENT and e.value.data == {"agents": ["claude-code", "other"]}
    for changes, code in (({"model": "opus"}, Err.BAD_MODEL), ({"model": "free/text"}, Err.MODEL_UNAVAILABLE),
                          ({"mode": "auto"}, Err.BAD_MODE), ({"effort": "max"}, Err.BAD_EFFORT)):
        with pytest.raises(ProtocolError) as e:
            two.configure(c["id"], changes)
        assert e.value.code == code, changes
    with pytest.raises(ProtocolError) as e:
        two.new(agent="other", model="opus")
    assert e.value.code == Err.BAD_MODEL and e.value.data == {"models": ["free/vision"]}
    two.configure(c["id"], {"mode": "plan", "effort": "high"})
    assert two.status()["settings"]["agents"]["other"] == {"default_mode": "plan", "default_model": "free/vision",
                                                          "default_effort": "high"}
    assert two.status()["settings"]["agents"]["claude-code"]["default_mode"] == "default"
    with pytest.raises(ChatError) as e:
        two.remote_on(c["id"])
    assert e.value.code == Err.REMOTE_UNSUPPORTED


def test_chat_settings_are_per_agent(two):
    two.set_settings({"agent": "other", "default_mode": "plan", "default_effort": "low"})
    two.set_settings({"default_agent": "other"})
    c = two.new()
    assert (c["agent"], c["mode"], c["effort"]) == ("other", "plan", "low")
    two.set_settings({"default_model": "free/vision"})  # without agent: the default one's
    with pytest.raises(ProtocolError) as e:
        two.set_settings({"agent": "other", "default_model": "opus"})
    assert e.value.code == Err.BAD_MODEL
    for bad in ({"default_agent": "nobody"}, {"agent": "nobody", "default_mode": "plan"}):
        with pytest.raises(ProtocolError) as e:
            two.set_settings(bad)
        assert e.value.code == Err.BAD_AGENT, bad
    message = {"type": "chat.settings", "agent": "other", "default_agent": "other", "default_mode": "plan"}
    assert not problems("app", message)


def test_the_index_records_each_conversations_agent(tmp_path):
    path = tmp_path / "conversations.json"
    old = {"version": 1, "settings": {"default_mode": "plan", "default_model": "sonnet", "default_effort": "high",
                                      "permission_timeout_s": 60},
           "conversations": [{"id": "3f2b8c1e-5d4a-4e6f-9b7c-2a1d0e9f8c7b", "created": 1.0, "updated": 2.0,
                              "model": "opus", "turns": 1, "state": "ready"}]}
    path.write_text(json.dumps(old))
    index = Index(path)
    c = index.get("3f2b8c1e-5d4a-4e6f-9b7c-2a1d0e9f8c7b")
    assert c.agent == AgentId.CLAUDE_CODE and c.agent_session is None and "agent_session" not in c.summary()
    assert index.settings == {"default_agent": "claude-code", "permission_timeout_s": 60, "agents": {
        "claude-code": {"default_mode": "plan", "default_model": "sonnet", "default_effort": "high"}}}
    c.agent_session = "ses_abc"
    other = index.new(agent="other", mode="plan", model="free/vision", effort="low")
    index.save()
    saved = json.loads(path.read_text())
    assert saved["version"] == INDEX_VERSION == 2
    again = Index(path)
    assert again.get(c.id).agent_session == "ses_abc" and again.get(other.id).agent == "other"
    assert again.settings == index.settings
    # the images its agent's session holds: the server's own count, kept, never sent to the app
    assert c.images == 0 and "images" not in c.summary()
    again.get(c.id).images = 9
    again.save()
    assert Index(path).get(c.id).images == 9
    for wrong in (-1, True, "9", None):
        saved = json.loads(path.read_text())
        saved["conversations"][0]["images"] = wrong
        path.write_text(json.dumps(saved))
        assert Index(path).get(saved["conversations"][0]["id"]).images == 0, wrong
    saved = json.loads(path.read_text())
    path.write_text(json.dumps({**saved, "version": 3}))
    with pytest.raises(IndexVersionError):
        Index(path)


def test_the_examples_carry_the_agents():
    hello = json.loads((PROTOCOL / "examples/server/hello.json").read_text())
    assert "agent" not in hello and "remote" not in hello
    assert [a["id"] for a in hello["agents"]] == ["claude-code", "opencode"]
    claude, opencode = hello["agents"]
    assert claude["remote"] is not None and opencode["remote"] is None
    assert {m["reason"] for m in opencode["models"]} >= {None, "not_signed_in"}
    conversation = json.loads((PROTOCOL / "examples/server/chat.conversation.json").read_text())["item"]
    assert conversation["agent"] == "claude-code"
    assert json.loads((PROTOCOL / "examples/app/chat.new.with-settings.json").read_text())["agent"] == "claude-code"


class _ScriptedTurn:
    """A turn whose events and outcome are given."""

    def __init__(self, events: list, end: TurnEnd) -> None:
        self.events, self.end_ = events, end

    async def lines(self):
        for event in self.events:
            yield [event]

    async def wait(self, timeout):
        return True

    async def end(self, waits):
        pass

    def kill(self):
        pass

    async def drain(self):
        return []

    def outcome(self):
        return self.end_


class ImageAgent(OtherAgent):
    """OtherAgent with a model that takes 13 images: each turn reports the session it is told to, returns its
    screens and ends as it is told to; compacting always works and is counted."""

    def __init__(self) -> None:
        self.session, self.screens, self.compacts, self.end = "ses_one", 0, 0, TurnEnd(Outcome.DONE)

    def image_limit(self, model):
        return 13

    async def compact(self, request):
        self.compacts += 1
        return _ScriptedTurn([], TurnEnd(Outcome.DONE))

    async def start_turn(self, request):
        events = [Started(self.session, request.model, request.mode, request.effort, None, "connected", True)]
        events += [Output({"kind": "tool.result", "id": f"t{i}", "error": False, "images": 1})
                   for i in range(self.screens)]
        return _ScriptedTurn(events, self.end)


async def _turn(manager, cid: str) -> None:
    manager.send(cid, "go")
    for _ in range(500):
        if manager.turns.turn is None:
            return
        await asyncio.sleep(0.01)
    raise AssertionError("the turn never ended")


async def test_the_image_count_belongs_to_the_agents_session(tmp_path):
    """the images a conversation counts are its agent session's; a new or another session starts from its own."""
    agent = ImageAgent()
    manager = ChatManager(CFG, Hub(tmp_path, keepalive_s=0), tmp_path, agents=[ClaudeCode(CFG), agent])
    manager.problem = lambda: None
    c = manager.service.get(manager.new(agent="other")["id"])

    # a turn that does not resume (state new: no session yet) starts from 0, whatever an earlier attempt counted
    c.images, agent.screens = 9, 2
    await _turn(manager, c.id)
    assert (agent.compacts, c.images, c.agent_session, c.state) == (0, 2, "ses_one", "ready")

    # the same session goes on counting
    agent.screens = 3
    await _turn(manager, c.id)
    assert (agent.compacts, c.images) == (0, 5)

    # resumed, but the agent named another session: only this turn's images count, and no compaction ran before
    agent.session, agent.screens = "ses_two", 1
    await _turn(manager, c.id)
    assert (agent.compacts, c.images, c.agent_session) == (0, 1, "ses_two")

    # the agent lost the session: the count goes with it
    c.images, agent.screens, agent.end = 5, 0, TurnEnd(Outcome.ERROR, TurnError.EXPIRED)
    await _turn(manager, c.id)
    assert (c.state, c.images, agent.compacts) == ("expired", 0, 0)


async def test_a_session_at_half_the_limit_is_compacted_first(tmp_path):
    agent = ImageAgent()
    manager = ChatManager(CFG, Hub(tmp_path, keepalive_s=0), tmp_path, agents=[ClaudeCode(CFG), agent])
    manager.problem = lambda: None
    c = manager.service.get(manager.new(agent="other")["id"])
    agent.screens = 7
    await _turn(manager, c.id)
    assert (agent.compacts, c.images) == (0, 7)
    agent.screens = 2
    await _turn(manager, c.id)
    assert (agent.compacts, c.images) == (1, 2)
    kinds = [e.get("code", e["kind"]) for e in manager.page(c.id)["events"]]
    assert kinds.count("compacted") == 1 and kinds.index("compacted") > kinds.index("turn.start", kinds.index("turn.end"))
