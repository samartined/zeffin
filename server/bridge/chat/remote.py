# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Remote Control on a conversation: an interactive `claude --resume <id> --remote-control "<title>"` that the
Claude app and claude.ai/code can also write to (code.claude.com/docs/en/remote-control, read 2026-10-24).

Checked on a real server with `zf agent probe --remote` (Claude Code 2.1.308) before it was built.

- The process runs under a pseudo-terminal, started by a small launcher (setsid, the terminal opened by path so it
  becomes the controlling one, then exec), never `preexec_fn`, which is unsafe in a process with threads. Its output
  is drained and thrown away; only a short tail is kept to notice bracketed paste and to answer the terminal's
  cursor and device queries.
- It is followed only through its documented http hooks (code.claude.com/docs/en/hooks), never its transcript,
  whose format is internal. There is no PermissionRequest hook: Claude Code forwards permission prompts to the
  connected device, and the app shows that one waits.
- Our messages are typed into it as one bracketed paste and Enter; their UserPromptSubmit is matched to the send.
  A message's attachments go first in the same paste, one `@<absolute path>` each, in the conversation's
  attachments folder, which the process gets with `--add-dir` on every launch (checked on a real server with
  Claude Code 2.1.311, `zf agent probe --remote` steps 30-33: the file or photo reaches the model with no tool and
  no prompt).
  Any other prompt came from another device: it opens a turn marked "via": "remote", or is blocked while another
  turn runs anywhere on the server (one turn at a time).
- A remote turn closes at Stop, StopFailure, the next prompt, Notification(idle_prompt), the end of the process or
  10 minutes without any hook. A permission waiting on the other device closes only by a hook, or after
  `permission_stale_s` with no hook at all: then one Esc declines it (a denial there fires no hook).
  Remote Control ends when it is turned off, after 8 hours without activity, when the process exits and on a server
  restart. While it is still starting, it is ended with Ctrl-C and signals only, never text or Enter.
- The Remote Control session id that hooks bring in a header is never kept: only that one came ("connected").

Logs carry short ids and outcomes: never prompts, replies or tokens.
"""
from __future__ import annotations

import asyncio
import contextlib
import fcntl
import logging
import os
import re
import shlex
import signal
import struct
import sys
import termios
import time

from ..text import cut
from ..wire import SEQ, Kind
from .stream import RESULT_TEXT_LIMIT, tool_input

log = logging.getLogger("zeffin.chat")

# What the server says about the agent's remote access: names and codes only, the app owns the sentences.
CAPABILITY = {"name": "Remote Control", "keeper": "Anthropic", "answers": "elsewhere", "attachments": True}
BLOCK_REASON = "The bridge is running another turn. Send it again when it ends."
FORBIDDEN_START = ("/", "!", "#")  # slash commands, shell mode, memory: never typed for the app (until checked)
MATCH_WINDOW_S = 15.0
NO_NEWS_S = 600.0
IDLE_END_S = 8 * 3600.0
WATCH_EVERY_S = 30.0
START_LIMIT_S = 60.0
STALE_FLOOR_S = 15 * 60.0
STALE_MARGIN_S = 5 * 60.0
BASH_DEFAULT_TIMEOUT_MS = 120_000  # code.claude.com/docs/en/tools-reference, "Timeout and output limits"
BASH_MAX_TIMEOUT_MS = 600_000
EXIT_WAIT_S = 5.0
TERM_WAIT_S = 3.0
RESULT_CHARS = 1000
TAIL = 16 * 1024

ESC = b"\x1b"
CTRL_C = b"\x03"
PASTE_ON = b"\x1b[?2004h"
_QUERY = re.compile(rb"\x1b\[(?:6n|0?c)")  # the cursor position (ESC[6n) and the device (ESC[c, ESC[0c)
_ANSWERS = {b"n": b"\x1b[1;1R", b"c": b"\x1b[?62;22c"}
_PERMISSION_TOOL = re.compile(r"\buse ([A-Za-z0-9_\-]+)\s*$")

# Sets the pseudo-terminal up as the controlling terminal, then becomes claude: argv[1] is the terminal's path.
LAUNCHER = ("import os, sys\n"
            "os.setsid()\n"
            "fd = os.open(sys.argv[1], os.O_RDWR)\n"
            "for n in (0, 1, 2):\n"
            "    os.dup2(fd, n)\n"
            "if fd > 2:\n"
            "    os.close(fd)\n"
            "os.execv(sys.argv[2], sys.argv[2:])\n")

# SessionStart takes only command hooks (hooks page): this one posts its input to the server. The token stays in
# the environment (FB_HOOK_TOKEN), never on a command line.
_POSTER = ("import os, sys, urllib.request as u\n"
           "u.build_opener(u.ProxyHandler({})).open(u.Request(sys.argv[1], sys.stdin.buffer.read(), "
           "{'content-type': 'application/json', 'authorization': 'Bearer ' + os.environ.get('FB_HOOK_TOKEN', '')}), "
           "timeout=5).read()\n")


def session_start_command(url: str) -> str:
    return " ".join(shlex.quote(p) for p in (sys.executable, "-I", "-c", _POSTER, url))


def normal(text: str) -> str:
    """For matching a send to its UserPromptSubmit: line ends unified, trailing spaces dropped."""
    lines = text.replace("\r\n", "\n").replace("\r", "\n").strip().split("\n")
    return "\n".join(line.rstrip() for line in lines)


_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f-\x9f]")  # C0, DEL and C1 (U+009B is the 8-bit CSI)


def clean(text: str) -> str:
    """What may be typed: line ends as \\n, and no other control character. An ESC would end the bracketed paste
    early and type the rest as keys (ESC[201~ then Enter and "!": a shell command; ESC[Z: Shift+Tab, which allows a
    permission for the session), and a bare \\r would submit early (code.claude.com/docs/en/interactive-mode)."""
    return _CONTROL.sub("", text.replace("\r\n", "\n").replace("\r", "\n"))


# Characters that would end an @ mention early or that a reader could take as quoting: an @ path is typed only
# without them. Quoting one is not documented (code.claude.com/docs/en/common-workflows, "Reference files and
# directories", read 2026-10-27), and the server picks the file names (<id>.<ext>), so only the folder could hold them.
_UNMENTIONABLE = re.compile(r"[\s\"'`\\]")


def mention(path: str) -> str | None:
    """`@<path>`: an absolute path that reaches the model as the file; None when it cannot be typed safely."""
    if not path.startswith("/") or _UNMENTIONABLE.search(path) or not path.isprintable():
        return None
    return "@" + path


def message(text: str, files: list[str]) -> str | None:
    """A message with its files: their mentions first, as the probe typed them (step 32: `@path` then the text)."""
    mentions = [mention(path) for path in files]
    if any(m is None for m in mentions):
        return None
    return " ".join([*(m for m in mentions if m), *([text] if text.strip() else [])])


def permission_stale_s(env: dict) -> float:
    """How long a permission may wait with no hook before the server declines it with Esc. Once allowed on the other
    device, the next hook is the tool's PostToolUse, so this is longer than any allowed tool may run: a foreground
    Bash command ends at the larger of BASH_DEFAULT_TIMEOUT_MS and BASH_MAX_TIMEOUT_MS (10 min by default) and then
    moves to the background (tools-reference, read 2026-10-25). Only the process environment is read, not a
    settings.json env block. The limit runs from the prompt, not from the approval: an approval that comes late for a
    long command can be cut by the Esc, which never grants anything."""
    def ms(name: str, default: int) -> int:
        try:
            return max(0, int(env.get(name, default)))
        except (TypeError, ValueError):
            return default

    ceiling = max(ms("BASH_DEFAULT_TIMEOUT_MS", BASH_DEFAULT_TIMEOUT_MS),
                  ms("BASH_MAX_TIMEOUT_MS", BASH_MAX_TIMEOUT_MS))
    return max(STALE_FLOOR_S, ceiling / 1000 + STALE_MARGIN_S)


def refused_start(text: str) -> bool:
    return text.lstrip()[:1] in FORBIDDEN_START


def result_text(response: object) -> str:
    """A short line of a tool's result, from PostToolUse's tool_response (untrusted data)."""
    if isinstance(response, str):
        text = response
    elif isinstance(response, list):
        text = "\n".join(p.get("text", "") for p in response if isinstance(p, dict) and isinstance(p.get("text"), str))
    elif isinstance(response, dict) and isinstance(response.get("content"), (list, str)):
        return result_text(response["content"])
    elif isinstance(response, dict):
        text = " ".join(f"{k}={v}" for k, v in response.items() if isinstance(v, (str, int, float, bool)))
    else:
        text = ""
    return cut(text, min(RESULT_CHARS, RESULT_TEXT_LIMIT))


def flipper_seq(response: object) -> int | None:
    """The frame number a Flipper tool reports in its result, as the stream parser reads it."""
    import json

    parts = response.get("content") if isinstance(response, dict) else response
    texts = [parts] if isinstance(parts, str) else [p.get("text") for p in parts or [] if isinstance(p, dict)]
    for text in texts:
        try:
            meta = json.loads(text) if isinstance(text, str) else None
        except ValueError:
            continue
        if isinstance(meta, dict) and isinstance(meta.get(SEQ), int):
            return meta[SEQ]
    return None


class Terminal:
    """The interactive process under a pseudo-terminal, on the event loop (no blocking calls)."""

    def __init__(self) -> None:
        self.proc: asyncio.subprocess.Process | None = None
        self.master: int | None = None
        self.slave: int | None = None
        self.paste = False
        self.tail = bytearray()
        self.sending = False
        self.answers = bytearray()  # answers to the terminal's queries that came while a send was being written

    async def start(self, argv: list[str], cwd: str, env: dict) -> None:
        master, slave = os.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 50, 200, 0, 0))
        os.set_blocking(master, False)
        self.master, self.slave = master, slave
        try:
            self.proc = await asyncio.create_subprocess_exec(
                sys.executable, "-I", "-c", LAUNCHER, os.ttyname(slave), *argv, cwd=cwd, env=env,
                stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
        except OSError:
            self.close()
            raise
        # The slave stays open here for the whole session: closed early, the master reads EIO before the child
        # opens it. The end is known from the process, never from the terminal.
        asyncio.get_running_loop().add_reader(master, self._read)

    def _read(self) -> None:
        try:
            data = os.read(self.master, 65536)
        except (BlockingIOError, InterruptedError):
            return
        except OSError:
            data = b""
        if not data:
            with contextlib.suppress(Exception):
                asyncio.get_running_loop().remove_reader(self.master)
            return
        # A sequence can be split across reads: look at it with the end of the tail before it. A query is answered
        # once, so only one that ends in the new data counts (one already answered may still be in that end).
        before = bytes(self.tail[-(len(PASTE_ON) - 1):])
        seen = before + data
        if PASTE_ON in seen:
            self.paste = True
        for query in _QUERY.finditer(seen):
            if query.end() > len(before):
                self._answer(_ANSWERS[query.group()[-1:]])
        self.tail += data
        del self.tail[:-TAIL]

    def _answer(self, data: bytes) -> None:
        """Inside a paste an answer would become part of the message: it waits for the send to end."""
        if self.sending:
            self.answers += data
        else:
            self.write(data)

    def write(self, data: bytes) -> None:
        """A few bytes at once (answers to the terminal's queries, single keys): best effort."""
        if self.master is None:
            return
        with contextlib.suppress(OSError):
            os.write(self.master, data)

    async def send(self, data: bytes, limit_s: float = 10.0) -> None:
        """Everything, waiting while claude reads (a long message fills the terminal's buffer); then the answers
        to the queries claude made meanwhile, after the paste's end and before Enter."""
        self.sending = True
        try:
            await self._write_all(data, limit_s)
        finally:
            self.sending = False
        if self.answers:
            answers, self.answers = bytes(self.answers), bytearray()
            await self._write_all(answers, limit_s)

    async def _write_all(self, data: bytes, limit_s: float) -> None:
        view, end = memoryview(data), time.monotonic() + limit_s
        while view and self.master is not None and time.monotonic() < end:
            try:
                view = view[os.write(self.master, view):]
            except BlockingIOError:
                await asyncio.sleep(0.01)
            except OSError:
                return

    async def type_text(self, text: str) -> None:
        """One message: a bracketed paste when the terminal turned it on (newlines stay in it), else the text on
        one line; then Enter as a key of its own."""
        if self.paste:
            await self.send(b"\x1b[200~" + text.encode() + b"\x1b[201~")
        else:
            await self.send(" ".join(text.split("\n")).encode())
        await asyncio.sleep(0.3)
        self.write(b"\r")

    async def command(self, text: str) -> None:
        await self.send(text.encode())
        await asyncio.sleep(0.3)
        self.write(b"\r")

    def key(self, data: bytes) -> None:
        self.write(data)

    def signal(self, sig: int) -> None:
        if self.proc is not None and self.proc.returncode is None:
            with contextlib.suppress(ProcessLookupError, PermissionError):
                os.killpg(self.proc.pid, sig)

    def close(self) -> None:
        for fd in (self.master, self.slave):
            if fd is not None:
                with contextlib.suppress(Exception):
                    asyncio.get_running_loop().remove_reader(fd)
                with contextlib.suppress(OSError):
                    os.close(fd)
        self.master = self.slave = None


def permission_tool(message: object) -> str | None:
    """The tool a permission_prompt notification names ("Claude needs your permission to use Bash")."""
    m = _PERMISSION_TOOL.search(message) if isinstance(message, str) else None
    return m.group(1)[:80] if m else None


def tool_events(body: dict) -> tuple[dict, dict]:
    """PostToolUse as the chat's tool and tool.result events."""
    tid = str(body.get("tool_use_id") or "")[:100]
    name = str(body.get("tool_name") or "?")[:200]
    response = body.get("tool_response")
    ms = body.get("duration_ms")
    tool = {"kind": Kind.TOOL, "id": tid, "name": name, "input": tool_input(body.get("tool_input"))}
    result = {"kind": Kind.TOOL_RESULT, "id": tid, "error": False, "text": result_text(response),
              "ms": ms if isinstance(ms, int) and not isinstance(ms, bool) else None}
    return tool, result


async def stop_process(terminal, has_turn: bool, starting: bool = False, waits=(EXIT_WAIT_S, TERM_WAIT_S)) -> None:
    """Turn it off: Esc for a running turn, /exit, then SIGTERM, then SIGKILL. While starting, a question nobody
    answered may be open (consent, an update, a trust check), and text or Enter would give it an answer: then one
    Ctrl-C at most, and the signals."""
    proc = terminal.proc
    if proc is None or proc.returncode is not None:
        return
    if starting:
        terminal.key(CTRL_C)
    else:
        if has_turn:
            terminal.key(ESC)
            await asyncio.sleep(1.0)
        await terminal.command("/exit")
    for sig, wait in ((signal.SIGTERM, waits[0]), (signal.SIGKILL, waits[1])):
        try:
            await asyncio.wait_for(proc.wait(), wait)
            return
        except asyncio.TimeoutError:
            terminal.signal(sig)
    with contextlib.suppress(asyncio.TimeoutError):
        await asyncio.wait_for(proc.wait(), 2)


def remote_env(env: dict) -> dict:
    """The service has no terminal: give the process one it can draw on, and a UTF-8 locale if it has none."""
    env = dict(env)
    env.setdefault("TERM", "xterm-256color")
    locale = env.get("LC_ALL") or env.get("LC_CTYPE") or env.get("LANG") or ""
    if "utf-8" not in locale.lower() and "utf8" not in locale.lower():
        env["LANG"] = "C.UTF-8"
        env.pop("LC_ALL", None)
        env.pop("LC_CTYPE", None)
    return env
