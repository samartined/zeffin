# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""What the server starts for the agent, pinned whole: the command line, folder, environment and input of a `-p` turn
with attachments, and of the Remote Control process. Written before the Agent port and kept unchanged
through it: moving the agent behind the port must not change one byte of what is launched.

The process is never started: asyncio.create_subprocess_exec is replaced, wherever it is called from."""
import asyncio
import json
import sys
from pathlib import Path

import pytest

from bridge.chat.attachments import solid_png
from bridge.config import Config
from bridge.hub import Hub

GOLDEN = Path(__file__).parent / "fixtures" / "launch.json"
SESSION = "7f1d3c3e-1111-4222-8333-944455556666"
REMOTE_SESSION = "7f1d3c3e-1111-4222-8333-944455556667"


class _Pipe:
    def __init__(self):
        self.data = bytearray()

    def write(self, data):
        self.data += data

    async def drain(self):
        pass

    def close(self):
        pass

    async def readline(self):
        return b""

    async def read(self, n=-1):
        return b""


class _Proc:
    pid = 999999

    def __init__(self):
        self.returncode = None
        self.stdin, self.stdout, self.stderr = _Pipe(), _Pipe(), _Pipe()

    async def wait(self):
        self.returncode = 0
        return 0

    def send_signal(self, sig):
        pass


@pytest.fixture
def launched(tmp_path, monkeypatch):
    """Every process the chat would start: (argv, cwd, env, the process)."""
    runs = []

    async def spawn(*argv, cwd=None, env=None, **kw):
        proc = _Proc()
        runs.append((list(argv), cwd, dict(env or {}), proc))
        return proc

    monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)
    home = tmp_path / "home"
    home.mkdir()
    agent = tmp_path / "agent"
    agent.mkdir()
    (home / ".claude").mkdir()
    (home / ".claude" / "settings.json").write_text('{"cleanupPeriodDays": 45}')
    (home / ".claude.json").write_text(json.dumps({"projects": {str(agent): {"hasTrustDialogAccepted": True}}}))
    monkeypatch.setattr("os.environ", {"PATH": "/usr/bin:/bin", "HOME": str(home), "LANG": "en_US.UTF-8",
                                       "ANTHROPIC_API_KEY": "k", "CLAUDE_CODE_USE_BEDROCK": "1",
                                       "BASH_MAX_TIMEOUT_MS": "1200000"})
    return runs


def _manager(tmp_path):
    from bridge.chat.claude_code import ClaudeCode
    from bridge.chat.manager import ChatManager

    config = Config(host="127.0.0.1", phone_port=19434, mcp_port=19435, phone_token="p" * 40, mcp_token="m" * 40,
                    view_token="v" * 40, app_token="a" * 40, state_dir=str(tmp_path), keepalive_s=0,
                    agent_dir=str(tmp_path / "agent"), claude_bin="/opt/claude/bin/claude", free_only=True)
    manager = ChatManager(config, Hub(tmp_path, keepalive_s=0), tmp_path / "state", agent=ClaudeCode(config))
    manager.problem = lambda: None
    return manager


def _normal(value, tmp_path, tokens):
    """The run with its temporary folder, its tokens and this Python replaced by fixed names."""
    text = json.dumps(value)
    for token, name in tokens.items():
        text = text.replace(token, name)
    return json.loads(text.replace(str(tmp_path), "<tmp>").replace(sys.executable, "<python>"))


async def _until(check):
    for _ in range(200):
        if check():
            return
        await asyncio.sleep(0.01)
    raise AssertionError("timed out")


async def test_what_a_turn_and_remote_control_launch(tmp_path, launched):
    manager = _manager(tmp_path)
    c = manager.index.new(mode="plan", model="opus", effort="high")
    c.id, c.state, c.rules = SESSION, "ready", ["Bash(ls)", "Bash(touch a.txt)"]
    manager.index.items = {SESSION: c}
    image = manager.upload(SESSION, "screen.png", "image/png", solid_png(4, 4, (255, 136, 0)))
    notes = manager.upload(SESSION, "notes.txt", "text/plain", b"one\ntwo\n")
    manager.send(SESSION, "Look at these", [image["id"], notes["id"]])
    token = manager.turn.token
    await _until(lambda: manager.turn is None)

    r = manager.index.new(mode="acceptEdits", model="sonnet", effort="low")
    r.id, r.title, r.rules = REMOTE_SESSION, "- Fix the remote", ["Bash(make test)"]
    manager.index.items[REMOTE_SESSION] = r
    manager.remote_on(REMOTE_SESSION)
    remote_token, stale_s = manager.remote.token, manager.remote.stale_s
    await _until(lambda: manager.remote is None)

    (turn_argv, turn_cwd, turn_env, turn_proc), (remote_argv, remote_cwd, remote_env, _) = launched
    runs = {"turn": {"argv": turn_argv, "cwd": turn_cwd, "env": turn_env,
                     "stdin": json.loads(turn_proc.stdin.data.decode())},
            "remote": {"argv": remote_argv[5:], "launcher": remote_argv[:4], "cwd": remote_cwd, "env": remote_env,
                       "stale_s": stale_s}}
    for run in runs.values():
        run["env"].pop("PYTEST_CURRENT_TEST", None)  # pytest's own, set in the replaced environment
    got = _normal(runs, tmp_path, {token: "<hook-token>", remote_token: "<remote-token>",
                                    image["id"]: "<image-id>", notes["id"]: "<notes-id>"})
    assert remote_argv[4].startswith("/dev/")
    assert (tmp_path / "state" / "attachments" / REMOTE_SESSION).is_dir()  # --add-dir takes only a folder that exists
    assert got == json.loads(GOLDEN.read_text())
