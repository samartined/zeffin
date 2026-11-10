# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The chat end to end: the real server in a subprocess, the fake claude, and /app over a real socket."""
import contextlib
import json
import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

import pytest
from websockets.exceptions import ConnectionClosed
from websockets.sync.client import connect

from bridge import config as cfg
from bridge import tlskey
from conftest import fake_runs, free_port


def tls_socket(c: cfg.Config, folder: Path) -> socket.socket:
    """A TLS connection to the public listener, its key checked against the one in the data folder, as zf chat does."""
    key = tlskey.read(folder)
    if key is None:
        raise ConnectionRefusedError("no key yet")
    return tlskey.client_wrap(key.pin)(socket.create_connection(("127.0.0.1", c.phone_port), timeout=10))


def app_ws(c: cfg.Config, folder: Path, **options):
    """/app over that connection (ws:// so websockets does not wrap the TLS socket again)."""
    return connect(f"ws://127.0.0.1:{c.phone_port}/app", sock=tls_socket(c, folder),
                   additional_headers={"Authorization": f"Bearer {c.app_token}"}, **options)


class Live:
    def __init__(self, tmp_path: Path, claude: Path, env: dict, patch: str = "", free_only: bool = True):
        self.agent = tmp_path / "agent"
        self.agent.mkdir(exist_ok=True)
        self.path = tmp_path / "config.json"
        self.folder = tmp_path
        if not self.path.exists():
            cfg.create(self.path, "127.0.0.1", free_port(), free_port(), tmp_path / "state")
            c = cfg.load(self.path)
            c.agent_dir, c.claude_bin = str(self.agent), str(claude)
            c.free_only = free_only  # the guards' tests run with the switch on
            cfg.save(self.path, c)
        self.c = cfg.load(self.path)
        # patch: Python run in the server before it starts, to shorten a limit for a test
        run = ["-c", f"import sys\n{patch}\nfrom bridge.cli import main\nsys.exit(main(sys.argv[1:]))"] if patch else \
            ["-m", "bridge.cli"]
        self.proc = subprocess.Popen([sys.executable, "-I", *run, "--config", str(self.path), "run"],
                                     env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        self.ws = None
        deadline = time.monotonic() + 15
        while True:
            try:
                self.ws = app_ws(self.c, self.folder, max_size=16 * 1024 * 1024).__enter__()
                break
            except OSError:
                if time.monotonic() > deadline or self.proc.poll() is not None:
                    raise RuntimeError("server did not start: " + (self.proc.stderr.read() if self.proc.poll() is not None else ""))
                time.sleep(0.1)
        self.send("chat.list")
        self.until(lambda m: m["type"] == "chat.status" and m["agents"][0]["problem"] != "checking", 15)

    def send(self, kind: str, **fields) -> None:
        self.ws.send(json.dumps({"type": kind, **fields}))

    def until(self, predicate, timeout: float = 10.0) -> dict:
        deadline = time.monotonic() + timeout
        while True:
            message = json.loads(self.ws.recv(timeout=max(0.01, deadline - time.monotonic())))
            if predicate(message):
                return message

    def collect(self, conversation: str, timeout: float = 15.0) -> list[dict]:
        """Every message until the conversation's turn ends."""
        seen = []
        deadline = time.monotonic() + timeout
        while True:
            m = json.loads(self.ws.recv(timeout=max(0.01, deadline - time.monotonic())))
            seen.append(m)
            if m["type"] == "chat.event" and m["conversation"] == conversation and m["kind"] == "turn.end":
                return seen

    def new(self, **fields) -> str:
        self.send("chat.new", **fields)
        return self.until(lambda m: m["type"] == "chat.conversation")["item"]["id"]

    def close(self) -> int:
        if self.ws is not None:
            # Read what is left first: a full receive queue stops the client reading, so it would never see
            # the server's answer to its close and would wait out the whole close timeout (10 s).
            with contextlib.suppress(TimeoutError, ConnectionClosed):
                for _ in range(1000):
                    self.ws.recv(timeout=0.05)
            self.ws.close()
        if self.proc.poll() is None:
            self.proc.send_signal(signal.SIGTERM)
        return self.proc.wait(timeout=20)


@pytest.fixture
def live(tmp_path, fake_claude):
    servers = []

    def start(patch: str = "", free_only: bool = True, **extra) -> Live:
        server = Live(tmp_path, fake_claude, {**os.environ, **extra}, patch, free_only)
        servers.append(server)
        return server

    yield start
    for server in servers:
        if server.proc.poll() is None:
            server.close()


def events(messages, kind=None):
    return [m for m in messages if m["type"] == "chat.event" and (kind is None or m["kind"] == kind)]


def test_hello_announces_the_chat_and_status_is_ready(live):
    server = live()
    server.ws.close()
    with app_ws(server.c, server.folder) as ws:
        hello = json.loads(ws.recv(timeout=5))
        assert hello["type"] == "hello" and hello["features"] == ["chat", "attachments", "delete", "remote"]
        claude = hello["agents"][0]
        assert claude["id"] == "claude-code" and [a["id"] for a in hello["agents"]] == ["claude-code", "opencode"]
        opencode = hello["agents"][1]  # known, not set up on this server
        assert (opencode["configured"], opencode["problem"], opencode["remote"]) == (False, "not_configured", None)
        assert claude["name"] == "Claude Code" and isinstance(claude["version"], str) and claude["ready"]
        assert claude["remote"]["attachments"] is True  # messages in Remote Control may carry files
        for _ in range(3):  # status, audit and phone history; without chat.list, no chat messages
            assert not json.loads(ws.recv(timeout=5))["type"].startswith("chat.")
    server.ws = None


def test_a_turn_streams_and_the_next_one_resumes(live, fake_claude):
    server = live()
    cid = server.new()
    server.send("chat.send", conversation=cid, text="Please remember the number 4817.")
    first = server.collect(cid)
    kinds = [m["kind"] for m in events(first)]
    assert kinds[:3] == ["user", "turn.start", "turn.info"] and kinds[-1] == "turn.end"
    assert events(first, "turn.end")[-1]["outcome"] == "done"
    deltas = [m for m in first if m["type"] == "chat.delta"]
    text = events(first, "text")[-1]
    assert deltas and all(d["id"] == text["id"] for d in deltas)
    assert "".join(d["text"] for d in sorted(deltas, key=lambda d: d["offset"])) == text["text"]
    numbers = [m["n"] for m in events(first)]
    assert numbers == list(range(1, len(numbers) + 1))
    server.send("chat.send", conversation=cid, text="What number did I ask you to remember?")
    second = server.collect(cid)
    assert events(second, "text")[-1]["text"] == "4817"
    runs = fake_runs(fake_claude)
    assert "--session-id" in runs[-2]["argv"] and runs[-1]["argv"][runs[-1]["argv"].index("--resume") + 1] == cid
    assert "ANTHROPIC_API_KEY" not in runs[-1]["env"] and server.c.mcp_token not in json.dumps(runs[-1]["argv"])
    server.send("chat.open", conversation=cid, limit=3)
    page = server.until(lambda m: m["type"] == "chat.history")
    assert len(page["events"]) == 3 and page["more"] is True and page["events"][-1]["kind"] == "turn.end"
    server.send("chat.list")
    conversations = server.until(lambda m: m["type"] == "chat.conversations")["items"]
    assert conversations[0]["id"] == cid and conversations[0]["turns"] == 2 and conversations[0]["title"].startswith("Please")


def test_permission_always_then_deny(live, fake_claude):
    server = live()
    cid = server.new()
    server.send("chat.send", conversation=cid, text="Use the Write tool to create a.txt containing alpha.")
    ask = server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
    assert ask["tool"] == "Write" and ask["always"] is False and ask["deadline"] > time.time()
    server.send("chat.permission.answer", id=ask["id"], answer="always")
    assert server.until(lambda m: m["type"] == "error")["error"] == "bad_request"  # no rule to keep
    server.send("chat.permission.answer", id=ask["id"], answer="once")
    server.collect(cid)
    assert (server.agent / "a.txt").read_text() == "alpha"
    server.send("chat.send", conversation=cid, text="Bash command: ls -la")
    ask = server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
    assert ask["tool"] == "Bash" and ask["always"] is True
    server.send("chat.permission.answer", id=ask["id"], answer="always", rules=ask["rules"])
    rest = server.collect(cid)
    assert events(rest, "permission.end")[0]["answer"] == "always"
    server.send("chat.send", conversation=cid, text="Bash command: ls -la")
    rest = server.collect(cid)
    assert not events(rest, "permission") and events(rest, "text")[-1]["text"] == "Ran it."
    argv = fake_runs(fake_claude)[-1]["argv"]
    # the kept rule goes in the inline settings' permissions.allow, never on the command line (F1)
    assert argv[-2:] == ["--allowedTools", "mcp__flipper__*"]
    assert json.loads(argv[argv.index("--settings") + 1])["permissions"]["allow"] == ["Bash(ls -la)"]
    # A redirect gets no suggestion from Claude Code: "always" then keeps an exact-match rule.
    server.send("chat.send", conversation=cid, text="Bash command: echo hola > z.txt")
    ask = server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
    assert ask["always"] is True and ask["rules"] == ["Bash(echo hola > z.txt)"]
    server.send("chat.permission.answer", id=ask["id"], answer="always", rules=ask["rules"])
    server.collect(cid)
    server.send("chat.send", conversation=cid, text="Bash command: echo hola > z.txt")
    rest = server.collect(cid)
    assert not events(rest, "permission") and (server.agent / "z.txt").read_text() == "hola"

    other = server.new()
    server.send("chat.send", conversation=other, text="Use the Write tool to create b.txt containing gamma.")
    ask = server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
    server.send("chat.permission.answer", id=ask["id"], answer="deny")
    rest = server.collect(other)
    assert events(rest, "permission.end")[0]["answer"] == "deny"
    assert not events(rest, "denied") and events(rest, "text")[-1]["text"] == "DENIED"  # shown by permission.end
    assert not (server.agent / "b.txt").exists()
    server.send("chat.permission.answer", id=ask["id"], answer="once")
    assert server.until(lambda m: m["type"] == "error")["error"] == "permission_gone"


def test_busy_and_interrupt(live):
    server = live()
    cid, other = server.new(), server.new()
    server.send("chat.send", conversation=cid, text="Count from 1 to 400, one number per line.")
    server.until(lambda m: m["type"] == "chat.delta")
    server.send("chat.send", conversation=other, text="hello")
    error = server.until(lambda m: m["type"] == "error")
    assert error["error"] == "busy" and error["data"] == {"conversation": cid}
    server.send("chat.interrupt", conversation=cid)
    end = events(server.collect(cid), "turn.end")[-1]
    assert end["outcome"] == "interrupted"
    server.send("chat.send", conversation=other, text="hello")
    assert events(server.collect(other), "turn.end")[-1]["outcome"] == "done"


def test_accept_edits_and_expired_session(live, fake_claude):
    server = live()
    cid = server.new(mode="acceptEdits")
    server.send("chat.send", conversation=cid, text="Use the Write tool to create c.txt containing delta.")
    messages = server.collect(cid)
    assert not events(messages, "permission") and (server.agent / "c.txt").read_text() == "delta"
    for path in (fake_claude.parent / "sessions").glob(f"{cid}.json"):
        path.unlink()  # what Claude Code's 30-day cleanup does
    server.send("chat.send", conversation=cid, text="hello")
    end = events(server.collect(cid), "turn.end")[-1]
    assert end["error"] == "expired"
    server.send("chat.send", conversation=cid, text="hello")
    assert server.until(lambda m: m["type"] == "error")["error"] == "expired"


def test_plan_is_approved_before_anything_runs(live):
    server = live()
    cid = server.new(mode="plan")
    server.send("chat.send", conversation=cid, text="Use the Write tool to create p.txt containing echo.")
    ask = server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
    assert ask["tool"] == "ExitPlanMode" and "Write" in ask["input"]["plan"] and not (server.agent / "p.txt").exists()
    server.send("chat.permission.answer", id=ask["id"], answer="once")
    ask = server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
    assert ask["tool"] == "Write"
    server.send("chat.permission.answer", id=ask["id"], answer="once")
    server.collect(cid)
    assert (server.agent / "p.txt").read_text() == "echo"
    other = server.new(mode="plan")
    server.send("chat.send", conversation=other, text="Use the Write tool to create q.txt containing echo.")
    ask = server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
    server.send("chat.permission.answer", id=ask["id"], answer="deny")
    server.collect(other)
    assert not (server.agent / "q.txt").exists()


def test_model_and_effort_per_conversation(live, fake_claude):
    server = live(FAKE_CLAUDE_FABLE="included")
    server.send("chat.list")
    status = server.until(lambda m: m["type"] == "chat.status")
    claude = status["agents"][0]
    assert [m["value"] for m in claude["models"]] == ["opus", "sonnet", "haiku", "fable"]
    assert not {"fable", "modes", "models", "login"} & set(status)
    assert claude["login"] == {"method": "claude.ai", "subscription": True}
    assert [(o["value"], o["label"]) for o in claude["mode_options"]] == [
        ("default", "Manual"), ("acceptEdits", "Accept edits"), ("plan", "Plan"), ("auto", "Auto")]
    cid = server.new(model="sonnet", effort="low")
    server.send("chat.send", conversation=cid, text="hello")
    messages = server.collect(cid)
    start, info = events(messages, "turn.start")[0], events(messages, "turn.info")[0]
    assert (start["model"], start["effort"], info["effort"]) == ("sonnet", "low", "low")
    argv = fake_runs(fake_claude)[-1]["argv"]
    assert argv[argv.index("--model") + 1] == "sonnet" and argv[argv.index("--effort") + 1] == "low"
    server.send("chat.set", conversation=cid, model="fable", effort="max", mode="auto")
    item = server.until(lambda m: m["type"] == "chat.conversation" and m["item"]["mode"] == "auto")["item"]
    assert (item["model"], item["effort"], item["mode"]) == ("fable", "max", "auto")
    server.send("chat.set", conversation=cid, effort="extreme")
    error = server.until(lambda m: m["type"] == "error")
    assert error["error"] == "bad_effort" and "max" in error["data"]["efforts"]


def test_a_model_that_bills_usage_credits_is_listed_and_refused(live, fake_claude):
    server = live(FAKE_CLAUDE_FABLE="credits")
    server.send("chat.list")
    status = server.until(lambda m: m["type"] == "chat.status")
    models = status["agents"][0]["models"]
    fable = next(m for m in models if m["value"] == "fable")
    assert (fable["available"], fable["reason"]) == (False, "usage_credits")
    assert "fable" not in status  # gone: models says it
    assert all(m["available"] for m in models if m["value"] != "fable")
    cid = server.new()
    runs = len(fake_runs(fake_claude))
    for request in ({"type": "chat.set", "conversation": cid, "model": "fable"},
                    {"type": "chat.new", "model": "fable"}, {"type": "chat.settings", "default_model": "fable"}):
        server.send(request.pop("type"), **request)
        error = server.until(lambda m: m["type"] == "error")
        assert error["error"] == "model_unavailable" and error["data"]["model"] == "fable", request
        assert "fable" not in error["data"]["models"]
    assert len(fake_runs(fake_claude)) == runs  # no turn


def test_crash_is_reported(live):
    server = live()
    cid = server.new()
    server.send("chat.send", conversation=cid, text="scenario:crash")
    end = events(server.collect(cid), "turn.end")[-1]
    assert end["outcome"] == "error" and end["error"] == "agent_failed" and end["data"] == {"exit": 1, "subtype": None}
    server.close()  # claude's stderr goes to the server log only
    log = server.proc.stderr.read()
    assert "turn 1 claude failed (exit 1" in log and "something broke" in log


def test_api_key_source_kills_the_turn(live):
    server = live(FAKE_CLAUDE_KEY_SOURCE="ANTHROPIC_API_KEY")
    cid = server.new()
    start = time.monotonic()
    server.send("chat.send", conversation=cid, text="scenario:api_key")
    end = events(server.collect(cid), "turn.end")[-1]
    assert end["error"] == "billing_guard" and end["data"] == {"why": "api_key_source"}
    assert time.monotonic() - start < 4  # killed at init, not after the fake's 5 s


def test_live_off_an_api_key_login_offers_paid_models_and_runs_them(live, fake_claude):
    server = live(free_only=False, FAKE_CLAUDE_AUTH="api_key", FAKE_CLAUDE_FABLE="credits", ANTHROPIC_API_KEY="k")
    server.send("chat.list")
    status = server.until(lambda m: m["type"] == "chat.status" and m["agents"][0]["problem"] != "checking")
    claude = status["agents"][0]
    assert status["free_only"] is False and claude["ready"] and claude["login"]["subscription"] is False
    assert all(m["available"] and m["paid"] for m in claude["models"])
    cid = server.new(model="fable")
    server.send("chat.send", conversation=cid, text="hello")
    end = events(server.collect(cid), "turn.end")[-1]
    assert end["outcome"] == "done", end
    run = fake_runs(fake_claude)[-1]
    assert "ANTHROPIC_API_KEY" in run["env"] and "--bare" not in run["argv"]
    assert "k" not in run["argv"]


def test_not_ready_without_a_subscription_login(live):
    server = live(FAKE_CLAUDE_AUTH="api_key")
    cid = server.new()
    server.send("chat.send", conversation=cid, text="hello")
    error = server.until(lambda m: m["type"] == "error")
    assert error["error"] == "not_ready" and error["data"] == {"problem": "not_subscription"}
    server.send("chat.list")
    status = server.until(lambda m: m["type"] == "chat.status")
    claude = status["agents"][0]
    assert claude["login"] == {"method": "api_key", "subscription": False} and "claude" not in status
    assert (claude["ready"], claude["problem"]) == (False, "not_subscription")


def test_stopping_with_a_pending_permission_and_restart(live):
    server = live()
    cid = server.new()
    server.send("chat.send", conversation=cid, text="Use the Write tool to create d.txt containing echo.")
    server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
    start = time.monotonic()
    assert server.close() == 0 and time.monotonic() - start < 12
    again = live()
    server_events = [json.loads(line) for line in (Path(again.c.state_dir) / "conversations" / f"{cid}.jsonl").read_text().splitlines()]
    assert server_events[-1]["kind"] == "turn.end" and server_events[-1]["error"] == "stopping"
    assert next(e for e in server_events if e["kind"] == "permission.end")["answer"] == "stopping"
    again.send("chat.send", conversation=cid, text="hello")
    assert events(again.collect(cid), "turn.end")[-1]["outcome"] == "done"


def test_zf_chat_in_a_terminal(live, monkeypatch, capsys):
    from bridge import cli

    server = live()
    answers = iter(["Please remember the number 77.", "/mode accept", "/model haiku", "/effort low", "/status",
                    "/fb restart", "/list", "Use the Write tool to create e.txt containing kilo.", "/new",
                    "Bash command: touch f.txt", "a", "/mode plan", "Use the Write tool to create g.txt containing lima.",
                    "o", "d", "/quit"])
    prompts = []

    def ask(prompt: str) -> str:
        prompts.append(prompt)
        return next(answers)

    monkeypatch.setattr("builtins.input", ask)
    assert cli.main(["--config", str(server.path), "chat", "--new"]) == 0
    out = capsys.readouterr().out
    assert "New conversation (opus · effort medium · Manual)" in out and "You said: Please remember the number 77." in out
    assert "Now: opus · effort medium · Accept edits" in out and "Now: haiku · effort low · Accept edits" in out
    # /new starts where the last conversation was set
    assert "New conversation (haiku · effort low · Accept edits)" in out
    assert "Agent: Claude Code 2." in out
    assert "Models: opus (" in out and "Fable" not in out and "Now: haiku · effort low · Plan" in out
    assert "Unknown command /fb" in out and "another terminal" in out
    assert (server.agent / "e.txt").read_text() == "kilo"  # Accept edits: no question
    assert any(p.startswith("  Allow? [o]nce, [a]lways") for p in prompts) and "Ran it." in out, (prompts, out)
    assert "Claude proposes this plan:" in out and any("[o] approve, [d] reject" in p for p in prompts)
    assert not (server.agent / "g.txt").exists()  # plan approved, then the write itself denied
    assert server.c.app_token not in out
    assert cli.main(["--config", str(server.path), "chat", "--list"]) == 0
    listing = capsys.readouterr().out
    assert "acceptEdits" in listing and "Please remember" in listing


def test_zf_chat_guided_test(live, fake_claude, monkeypatch, capsys):
    from bridge import cli
    from bridge.chat.client import guided_test

    assert "in Spanish" in guided_test("es_ES.UTF-8") and "in English" in guided_test("C.UTF-8")
    server = live()
    answers = iter(["/quit"])
    prompts = []
    monkeypatch.setattr("builtins.input", lambda prompt="": prompts.append(prompt) or next(answers, "/quit"))
    monkeypatch.setenv("LANG", "es_ES.UTF-8")
    assert cli.main(["--config", str(server.path), "chat", "--test"]) == 0
    run = fake_runs(fake_claude)[-1]
    assert run["stdin"].startswith("This is the guided manual test") and "in Spanish" in run["stdin"]
    assert run["argv"][run["argv"].index("--permission-mode") + 1] == "default"
    out = capsys.readouterr().out
    assert "Starting the guided test" in out and prompts == ["> "], (prompts, out[-800:])


def _http(server, method: str, path: str, data: bytes | None = None, token: str | None = None,
          content_type: str = "application/octet-stream") -> tuple[int, bytes, dict]:
    import http.client

    headers = {"Content-Type": content_type}
    if token is not False:
        headers["Authorization"] = f"Bearer {token or server.c.app_token}"
    conn = http.client.HTTPConnection("127.0.0.1", server.c.phone_port, timeout=30)
    conn.sock = tls_socket(server.c, server.folder)  # https, pinned like the app
    try:
        conn.request(method, path, body=data, headers=headers)
        response = conn.getresponse()
        return response.status, response.read(), dict(response.headers)
    finally:
        conn.close()


def test_attachments_upload_send_and_download(live, fake_claude):
    from bridge.chat.attachments import solid_png

    server = live()
    cid = server.new()
    png = solid_png(40, 30, (200, 0, 0))
    up = f"/app/upload?conversation={cid}&name=screen.png"
    assert _http(server, "POST", up, png, token=False)[0] == 401
    assert _http(server, "POST", up, png, token="x" * 43)[0] == 401
    assert _http(server, "POST", f"/app/upload?conversation={'0' * 8}-0000-0000-0000-{'0' * 12}&name=a", png)[0] == 404
    status, body, _ = _http(server, "POST", up, b"MZ", content_type="application/x-msdownload")
    assert status == 415 and json.loads(body)["error"] == "bad_type"
    with tls_socket(server.c, server.folder) as raw:  # refused before the body
        raw.sendall(f"POST {up} HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {server.c.app_token}\r\n"
                    f"Content-Length: {11 * 1024 * 1024}\r\n\r\n".encode())
        assert raw.recv(100).startswith(b"HTTP/1.1 413")
    status, body, _ = _http(server, "POST", up, png, content_type="image/png")
    image = json.loads(body)
    assert status == 201 and image["kind"] == "image" and (image["width"], image["height"]) == (40, 30)
    status, body, _ = _http(server, "POST", f"/app/upload?conversation={cid}&name=tv.ir", b"Filetype: IR signals file\n")
    ir = json.loads(body)
    assert status == 201 and ir["kind"] == "file"

    # An image goes to Claude as one stream-json user message with an image block.
    server.send("chat.send", conversation=cid, text="What is on this screen?", attachments=[image["id"]])
    seen = server.collect(cid)
    user = events(seen, "user")[-1]
    assert user["attachments"] == [image] and user["text"] == "What is on this screen?"
    assert "I see 1 image(s): image/png." in json.dumps(events(seen, "text"))
    run = fake_runs(fake_claude)[-1]
    assert run["argv"][run["argv"].index("--input-format") + 1] == "stream-json" and run["images"] == ["image/png"]
    folder = Path(server.c.state_dir) / "attachments" / cid
    assert run["argv"][run["argv"].index("--add-dir") + 1] == str(folder)

    # Another file is named by path in a folder added with --add-dir: Claude reads it without asking.
    server.send("chat.send", conversation=cid, text="", attachments=[ir["id"]])
    seen = server.collect(cid)
    assert "Read: Filetype: IR signals file" in json.dumps(events(seen, "text")) and not events(seen, "permission")
    run = fake_runs(fake_claude)[-1]
    assert "--input-format" not in run["argv"] and '(sent as "tv.ir", application/octet-stream' in run["stdin"]
    assert str(folder) in run["stdin"] and run["images"] == []
    server.send("chat.list")
    item = next(c for c in server.until(lambda m: m["type"] == "chat.conversations")["items"] if c["id"] == cid)
    assert item["attachments"] == 2  # what the app's confirmation counts before deleting

    # Unknown ids are refused; the history keeps what was sent; the files come back.
    server.send("chat.send", conversation=cid, text="again", attachments=["0123456789abcdef"])
    error = server.until(lambda m: m["type"] == "error")
    assert error["error"] == "bad_attachment" and error["request"] == "chat.send"
    status, body, headers = _http(server, "GET", f"/app/attachments/{cid}/{image['id']}")
    assert status == 200 and body == png and headers["content-type"] == "image/png"
    assert _http(server, "GET", f"/app/attachments/{cid}/{image['id']}", token=False)[0] == 401
    assert _http(server, "GET", f"/app/attachments/{cid}/../../config.json")[0] == 404
    server.send("chat.delete", conversation=cid)
    server.until(lambda m: m["type"] == "chat.deleted" and m["conversation"] == cid)
    assert not folder.exists()


def test_zf_chat_attach(live, fake_claude, monkeypatch, capsys, tmp_path):
    from bridge import cli
    from bridge.chat.attachments import solid_png

    server = live()
    photo = tmp_path / "photo.png"
    photo.write_bytes(solid_png(20, 10, (0, 0, 255)))
    answers = iter([f"/attach {photo}", "/attach", f"/attach {tmp_path / 'missing.png'}", "Look at this", "/quit"])
    monkeypatch.setattr("builtins.input", lambda prompt: next(answers))
    assert cli.main(["--config", str(server.path), "chat", "--new"]) == 0
    out = capsys.readouterr().out
    assert "Attached [image photo.png 1 KB 20×10]: it goes with your next message." in out
    assert "Attached for your next message: [image photo.png" in out and "No file at" in out
    assert "I see 1 image(s): image/png." in out and server.c.app_token not in out


def _claude_files(root: Path, sid: str) -> list[Path]:
    """Claude Code's files for a session, where code.claude.com/docs/en/claude-directory puts them."""
    project = root / "projects" / "-home-demo-projects-zeffin-agent"
    paths = [project / f"{sid}.jsonl", project / f"{sid}.orphaned-1-a.jsonl", project / f"{sid}.jsonl.superseded-2",
             project / sid / "subagents" / "agent-1.jsonl", root / "file-history" / sid / "snap-1"]
    for p in paths:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("{}\n")
    return paths


def test_delete_removes_the_conversation_for_real(live, tmp_path):
    claude_home = tmp_path / "claude-home"
    server = live(CLAUDE_CONFIG_DIR=str(claude_home))
    cid = server.new()
    server.send("chat.send", conversation=cid, text="Please remember the number 31.")
    server.collect(cid)
    status, body, _ = _http(server, "POST", f"/app/upload?conversation={cid}&name=tv.ir", b"Filetype: IR signals file\n")
    assert status == 201
    mine = _claude_files(claude_home, cid)
    other = _claude_files(claude_home, "11111111-2222-4333-8444-555555555555")
    state = Path(server.c.state_dir)
    assert (state / "conversations" / f"{cid}.jsonl").exists() and (state / "attachments" / cid).exists()

    server.send("chat.delete", conversation=cid)
    assert server.until(lambda m: m["type"] == "chat.deleted")["conversation"] == cid
    assert not any(p.exists() for p in mine) and not (claude_home / "projects" / "-home-demo-projects-zeffin-agent" / cid).exists()
    assert all(p.exists() for p in other)
    assert not (state / "conversations" / f"{cid}.jsonl").exists() and not (state / "attachments" / cid).exists()
    assert cid not in (state / "conversations.json").read_text()
    server.send("chat.list")
    assert cid not in [c["id"] for c in server.until(lambda m: m["type"] == "chat.conversations")["items"]]
    server.send("chat.delete", conversation=cid)
    assert server.until(lambda m: m["type"] == "error")["error"] == "not_found"


def test_delete_stops_a_running_turn_first(live, tmp_path):
    server = live(CLAUDE_CONFIG_DIR=str(tmp_path / "claude-home"))
    cid = server.new()
    server.send("chat.send", conversation=cid, text="Count from 1 to 400, one number per line.")
    server.until(lambda m: m["type"] == "chat.delta")
    server.send("chat.delete", conversation=cid)
    assert events(server.collect(cid), "turn.end")[-1]["outcome"] == "interrupted"
    assert server.until(lambda m: m["type"] == "chat.deleted")["conversation"] == cid
    server.send("chat.list")
    assert server.until(lambda m: m["type"] == "chat.status")["turn"] is None


def test_zf_chat_delete(live, monkeypatch, capsys, tmp_path):
    from bridge import cli

    server = live(CLAUDE_CONFIG_DIR=str(tmp_path / "claude-home"))

    def chat() -> int:
        answers = iter(["Please remember the number 5.", "/delete", "n", "/delete", "y", "/quit"])
        monkeypatch.setattr("builtins.input", lambda prompt: next(answers))
        return cli.main(["--config", str(server.path), "chat", "--new"])

    code = chat()
    if code != 0:
        # A cold first run on a busy machine can miss chat/client.py's 5 s limits (connect, open, hello) and return 1
        # before anything is sent: one retry, so the test checks the chat, not the machine's speed.
        capsys.readouterr()
        code = chat()
    assert code == 0
    out = capsys.readouterr().out
    assert "Kept." in out and "Deleted." in out and out.count("New conversation (") == 2
    assert "Please remember" not in (Path(server.c.state_dir) / "conversations.json").read_text()


def test_pairing_on_the_real_server(live):
    """zf pair's loopback routes open a pairing with the app token; over TLS, /app/pair makes a request that gives the
    tokens only after the owner confirms it, once. The QR code and /status name the key the listener serves."""
    import urllib.error
    import urllib.request

    server = live()
    key = tlskey.read(server.folder)

    def local(method: str, path: str, token: str, body: dict | None = None) -> tuple[int, dict]:
        data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
        request = urllib.request.Request(f"http://127.0.0.1:{server.c.mcp_port}{path}", method=method, data=data,
                                         headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as e:
            return e.code, {}

    def phone(path: str, body: dict) -> tuple[int, dict]:
        status, data, _ = _http(server, "POST", path, json.dumps(body).encode(), token=False,
                                content_type="application/json")
        return status, json.loads(data)

    assert local("POST", "/pair", server.c.mcp_token)[0] == 401  # what a chat turn holds cannot open a pairing
    assert local("GET", "/status", server.c.mcp_token)[1]["key"] == {"pin": key.pin, "fingerprint": key.fingerprint}
    status, opened = local("POST", "/pair", server.c.app_token)
    payload = json.loads(opened["payload"])
    assert status == 200 and payload == {"fb": 3, "url": server.c.base_url(), "code": payload["code"], "key": key.pin}
    assert payload["url"].startswith("https://") and server.c.app_token not in opened["payload"]
    status, asked = phone("/app/pair", {"code": payload["code"]})
    assert status == 202 and set(asked) == {"pairing", "number", "expires_in"}
    assert phone("/app/pair", {"code": payload["code"]}) == (409, {"error": "code_used"})
    request = local("GET", "/pair", server.c.app_token)[1]["request"]
    assert request["number"] == asked["number"]
    assert local("POST", "/pair/confirm", server.c.app_token, {"ref": request["ref"]})[0] == 200
    assert phone("/app/pair/wait", {"pairing": asked["pairing"]}) == (
        200, {"url": server.c.base_url(), "phone": server.c.phone_token, "app": server.c.app_token})
    assert phone("/app/pair/wait", {"pairing": asked["pairing"]}) == (404, {"error": "unknown"})
    assert local("GET", "/pair", server.c.app_token)[1]["state"] == "paired"


def test_remote_control_end_to_end(live, fake_claude, tmp_path):
    """Remote Control with the fake claude under a pseudo-terminal: our message typed into it, a prompt from
    another device (the fake's inbox), then off; the conversation goes on with -p turns."""
    inbox = tmp_path / "inbox.txt"
    inbox.write_text("")
    agent = tmp_path / "agent"
    agent.mkdir()
    (tmp_path / ".claude.json").write_text(json.dumps({"projects": {}}))
    server = live(FAKE_CLAUDE_INBOX=str(inbox), HOME=str(tmp_path))
    server.send("chat.remote", conversation=server.new(), on=True)  # not trusted yet: the question would block it
    assert server.until(lambda m: m["type"] == "error")["error"] == "not_ready"
    (tmp_path / ".claude.json").write_text(json.dumps({"projects": {str(agent): {"hasTrustDialogAccepted": True}}}))
    cid = server.new()
    server.send("chat.send", conversation=cid, text="Please remember the number 5023.")
    server.collect(cid)
    server.send("chat.remote", conversation=cid, on=True)
    server.until(lambda m: m["type"] == "chat.status" and (m.get("remote") or {}).get("state") == "on", 20)
    server.send("chat.send", conversation=cid, text="What number did I ask you to remember?\nOnly the number.")
    ours = server.collect(cid, 20)
    assert [m["kind"] for m in events(ours)][:2] == ["user", "turn.start"]
    assert events(ours, "text")[-1]["text"] == "5023" and not any(m.get("via") for m in events(ours))
    assert events(ours, "turn.end")[-1]["outcome"] == "done"
    with inbox.open("a") as f:
        f.write("Reply with only the word pong.\n")
    theirs = server.collect(cid, 20)
    assert all(m.get("via") == "remote" for m in events(theirs))
    assert events(theirs, "user")[-1]["text"] == "Reply with only the word pong." and events(theirs, "text")[-1]["text"] == "pong"
    server.send("chat.send", conversation=cid, text="/model sonnet")
    error = server.until(lambda m: m["type"] == "error")
    assert error["error"] == "remote_command"
    # control characters never reach the terminal: no early end of the paste, no shell command
    server.send("chat.send", conversation=cid, text="Reply with only the word ok.\x1b[201~\r!touch pwned.txt")
    server.collect(cid, 20)
    assert not (fake_claude.parent / "shell.log").exists() and not (server.agent / "pwned.txt").exists()
    # a permission waiting on the other device: our sends wait, and turning off presses Esc first, never Enter
    with inbox.open("a") as f:
        f.write("Use the Write tool to create rc.txt containing the word secret\n")
    server.until(lambda m: m["type"] == "chat.event" and m.get("code") == "remote_permission", 20)
    server.send("chat.send", conversation=cid, text="hello")
    assert server.until(lambda m: m["type"] == "error")["error"] == "busy"
    server.send("chat.remote", conversation=cid, on=False)
    off = server.until(lambda m: m["type"] == "chat.event" and m.get("code") == "remote_off", 20)
    assert off["data"] == {"reason": "off"}
    assert not (fake_claude.parent / "allowed.log").exists() and not (server.agent / "rc.txt").exists()
    server.until(lambda m: m["type"] == "chat.status" and m.get("remote") is None)
    runs = [r for r in fake_runs(fake_claude) if r.get("interactive")]
    assert runs[0]["argv"][:3] == ["--remote-control", "Please remember the number 5023.", "--resume"]
    assert server.c.mcp_token not in json.dumps(runs[0]["argv"]) and "FB_HOOK_TOKEN" in runs[0]["env"]
    server.send("chat.send", conversation=cid, text="Reply with only the word back.")
    assert events(server.collect(cid), "text")[-1]["text"] == "back"
    server.send("chat.open", conversation=cid)
    page = server.until(lambda m: m["type"] == "chat.history")
    assert "cse_fake" not in json.dumps(page)


def test_remote_control_takes_attachments(live, fake_claude, tmp_path):
    """With Remote Control on, a message's files are typed as @ mentions in the same paste: the agent reads them from
    the conversation's folder (--add-dir on the launch) with no tool and no prompt, and the history shows their chips."""
    from bridge.chat.attachments import solid_png

    _trusted_agent(tmp_path)
    server = live(HOME=str(tmp_path))
    cid = server.new()
    server.send("chat.remote", conversation=cid, on=True)
    server.until(lambda m: m["type"] == "chat.status" and (m.get("remote") or {}).get("state") == "on", 20)
    status, body, _ = _http(server, "POST", f"/app/upload?conversation={cid}&name=photo.png",
                            solid_png(8, 8, (0, 0, 200)), content_type="image/png")
    photo = json.loads(body)
    status, body, _ = _http(server, "POST", f"/app/upload?conversation={cid}&name=notes.txt", b"The word is tulip.\n",
                            content_type="text/plain")
    notes = json.loads(body)
    server.send("chat.send", conversation=cid, text="What colour is it?", attachments=[photo["id"]])
    seen = server.collect(cid, 20)
    assert [(m["kind"], m.get("via")) for m in events(seen)][:2] == [("user", None), ("turn.start", None)]
    assert events(seen, "user") == [{**events(seen, "user")[0], "text": "What colour is it?", "attachments": [photo]}]
    assert events(seen, "text")[-1]["text"] == "Blue." and events(seen, "turn.end")[-1]["outcome"] == "done"
    assert not events(seen, "tool") and not any(m.get("code") == "remote_permission" for m in events(seen, "notice"))
    server.send("chat.send", conversation=cid, text="", attachments=[notes["id"]])
    seen = server.collect(cid, 20)
    assert len(events(seen, "user")) == 1 and events(seen, "user")[0]["attachments"] == [notes]
    assert events(seen, "text")[-1]["text"] == "The word is tulip." and not events(seen, "tool")
    folder = Path(server.c.state_dir) / "attachments" / cid
    run = next(r for r in fake_runs(fake_claude) if r.get("interactive"))
    assert run["argv"][run["argv"].index("--add-dir") + 1] == str(folder)
    server.send("chat.open", conversation=cid)
    page = server.until(lambda m: m["type"] == "chat.history")
    assert str(folder) not in json.dumps(page) and [e.get("attachments") for e in page["events"] if e["kind"] == "user"] \
        == [[photo], [notes]]
    server.send("chat.list")
    item = next(c for c in server.until(lambda m: m["type"] == "chat.conversations")["items"] if c["id"] == cid)
    assert item["attachments"] == 2


def test_zf_chat_remote(live, monkeypatch, capsys, tmp_path):
    from bridge import cli

    agent = tmp_path / "agent"
    agent.mkdir()
    (tmp_path / ".claude.json").write_text(json.dumps({"projects": {str(agent): {"hasTrustDialogAccepted": True}}}))
    server = live(HOME=str(tmp_path))
    answers = iter(["Please remember the number 77.", "/remote on", "/status", "!ls", "What number did I ask you to remember?",
                    "/remote off", "/quit"])
    monkeypatch.setattr("builtins.input", lambda prompt: next(answers))
    assert cli.main(["--config", str(server.path), "chat", "--new"]) == 0
    out = capsys.readouterr().out
    assert "Remote Control is on" in out and "Remote Control: on here" in out
    assert "can't start with / ! or #" in out and "\n77\n" in out and "Remote Control is off" in out


def _keys_once_written(path: Path, timeout: float = 5.0, settle: float = 0.3) -> str:
    """keys.log once the fake has written it, read again after a short settle: the fake writes it from its own reader
    thread, which can lag behind the turn's end on a busy machine (a CI runner saw it still empty)."""
    deadline = time.monotonic() + timeout
    while not (path.exists() and path.read_text()) and time.monotonic() < deadline:
        time.sleep(0.05)
    time.sleep(settle)
    return path.read_text() if path.exists() else ""


def _trusted_agent(tmp_path):
    agent = tmp_path / "agent"
    agent.mkdir()
    (tmp_path / ".claude.json").write_text(json.dumps({"projects": {str(agent): {"hasTrustDialogAccepted": True}}}))


def test_a_permission_denied_elsewhere_goes_stale_and_is_declined_with_esc(live, fake_claude, tmp_path):
    """A denial in the Claude app fires no hook: past the (shortened) limit the server presses Esc, which declines and
    never grants, and closes the turn; the server takes sends again."""
    inbox = tmp_path / "inbox.txt"
    inbox.write_text("")
    _trusted_agent(tmp_path)
    server = live("from bridge.chat import remote\nremote.WATCH_EVERY_S = 0.2\nremote.permission_stale_s = lambda env: 2.0",
                  FAKE_CLAUDE_INBOX=str(inbox), HOME=str(tmp_path))
    cid = server.new()
    server.send("chat.remote", conversation=cid, on=True)
    server.until(lambda m: m["type"] == "chat.status" and (m.get("remote") or {}).get("state") == "on", 20)
    with inbox.open("a") as f:
        f.write("Use the Write tool to create rc.txt containing the word secret\n")
    seen = server.collect(cid, 20)
    end = events(seen, "turn.end")[-1]
    assert (end["outcome"], end["error"]) == ("interrupted", "permission_stale")
    assert any(m.get("code") == "remote_permission" for m in events(seen, "notice"))
    assert not (fake_claude.parent / "allowed.log").exists() and not (server.agent / "rc.txt").exists()
    assert _keys_once_written(fake_claude.parent / "keys.log") == "\x1b"  # one Esc, nothing else
    server.send("chat.send", conversation=cid, text="Reply with only the word pong.")
    assert events(server.collect(cid, 20), "text")[-1]["text"] == "pong"


def test_a_start_that_never_comes_is_ended_without_typing(live, fake_claude, tmp_path):
    """A question at start nobody answers (here Remote Control's consent): past the (shortened) start limit the
    session ends as could_not_start, and the process never got text or Enter, only Ctrl-C and signals."""
    _trusted_agent(tmp_path)
    server = live("from bridge.chat import remote\nremote.WATCH_EVERY_S = 0.2\nremote.START_LIMIT_S = 1.0",
                  FAKE_CLAUDE_RC_CONSENT="1", HOME=str(tmp_path))
    cid = server.new()
    server.send("chat.remote", conversation=cid, on=True)
    off = server.until(lambda m: m["type"] == "chat.event" and m.get("code") == "remote_off", 20)
    assert off["data"]["reason"] == "could_not_start"
    keys = (fake_claude.parent / "keys.log").read_text()
    assert "/exit" not in keys and "\r" not in keys and "\n" not in keys and "y" not in keys
