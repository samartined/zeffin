# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""What the probe saves of Claude Code's stream-json, with nothing personal in it (`zf agent probe --fixtures`).

Two layers. First, an allow-list: a fixture keeps the structure the parser needs (event types, keys,
numbers, booleans, enum-like words, tool names, ids as placeholders, timestamps) and the probe's own prompts
and words; every other string (the model's text and its streamed pieces, tool results, descriptions, paths)
becomes `<text-N>`, the same N for the same text in the whole run. A string that holds JSON (a Flipper tool's
result, a tool's streamed input) is redacted inside and written back as JSON.

Second, before the allow-list looks at a string, a deny-list, applied to every key and string of each event:
- the bridge's tokens, the probe's hook token, `Bearer <x>` and `sk-ant-…` become `<token>`;
- known absolute paths (the probe's folders, the state folder, the folder the clone sits in, home, the
  temporary folder) become placeholders, the longest first, also in their `~/` form and in the form Claude
  Code gives a project's folder under ~/.claude/projects (every character but letters and digits as `-`);
- the Claude account (email, display and organization names in ~/.claude.json) becomes `<account>`, and any
  other email `<email>`;
- every UUID becomes `<uuid-N>`, the same N for the same UUID in the whole run, so a resumed turn still
  points at its session;
- the machine's IP addresses become `<ip>`; the user and host names, as whole words, `<user>` and `<host>`;
  time zone names (`Europe/…`) `<tz>`;
- the owner's own setup, wherever it is listed (`system/init`, the `initialize` answer): slash commands,
  commands, skills, agents, plugins, output styles, MCP servers and their tools become `<name-N>`, stable in
  the run, except Claude Code's built-in commands and the bridge's `flipper` server and its tools;
- what a local command such as `/usage` prints (the owner's usage, folders and plugins) becomes
  `<local command output>`;
- streamed text arrives cut in pieces (`content_block_delta`), and a path can be cut anywhere: each block's
  pieces are joined, redacted, and cut again into as many pieces.

A name from the owner's setup that is also written elsewhere (`plugin:skill`, `my-agent`) is replaced there too.

Before anything is written, `leaks` searches the redacted text and each block's joined pieces, in process,
for every value above that is known: the user and host names, home and the paths under it in every form,
the tokens, the IP addresses, the account and time zones. The values are never printed or logged: a leak is
named by its kind only.
"""
from __future__ import annotations

import getpass
import ipaddress
import json
import re
import socket
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

TOKEN = "<token>"
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
BEARER = re.compile(r"(?i)\bbearer\s+(?!<token>)[A-Za-z0-9._~+/=-]{8,}")
API_KEY = re.compile(r"\bsk-ant-[A-Za-z0-9_-]+")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
TIME_ZONE = re.compile(r"\b(Africa|America|Antarctica|Asia|Atlantic|Australia|Europe|Indian|Pacific)/[A-Z][A-Za-z_+-]+"
                       r"(/[A-Z][A-Za-z_+-]+)?")
OWN = ("flipper",)  # the bridge's own MCP server: its name and tools stay
SETUP_LISTS = ("slash_commands", "terminal_slash_commands", "commands", "skills", "agents", "plugins",
               "available_output_styles")
LOCAL_OUTPUT = "<local command output>"
WORD = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:+-]{0,63}")  # an enum, a model, a tool name, an id, a time
ONLY_PLACEHOLDERS = re.compile(r"(<[a-z]+(-\d+)?>[ ,.:;]*)+")
SAFE_PATH = re.compile(r"<(workdir|state|tmp|project)>(/[A-Za-z0-9._-]+)*/?")  # folders the probe made
KEY = re.compile(r"[A-Za-z0-9_$][A-Za-z0-9_.:/$-]{0,79}")
PROJECT = re.compile(r"(\.claude/projects/)[^/\s\"'`]+")
DELTA_FIELDS = ("text", "partial_json", "thinking")
PLACEHOLDER = re.compile(r"<[a-z]+(-\d+)?>")


@dataclass
class Secrets:
    """What must never reach a fixture. Read in process; never printed."""
    paths: dict[str, str] = field(default_factory=dict)   # absolute path -> placeholder
    user: str = ""
    host: str = ""
    home: str = ""
    tokens: list[str] = field(default_factory=list)
    ips: list[str] = field(default_factory=list)
    account: list[str] = field(default_factory=list)


def encoded(path: str) -> str:
    """A folder as Claude Code names its project under ~/.claude/projects (needs testing on the server for
    other characters than `/`, `.`, `-` and `_`)."""
    return re.sub(r"[^A-Za-z0-9]", "-", path)


def path_forms(paths: dict[str, str], home: str) -> dict[str, str]:
    """Each path as given, resolved, under `~/` when it is in home, and encoded as a project folder."""
    known = dict(paths)
    for path, name in list(known.items()):
        known.setdefault(str(Path(path).resolve()), name)
    for path, name in list(known.items()):
        if home and len(home) > 1 and path.startswith(home + "/"):
            known.setdefault("~" + path[len(home):], name)
        if path.startswith("/") and path.count("/") > 1:  # not "/tmp": "-tmp" is too common a word
            known.setdefault(encoded(path), name)
    return known


def local_secrets(*, tokens: list[str], paths: dict[str, Path], ips: list[str], claude_json: Path) -> Secrets:
    """This machine's: user, host, home, temporary folder, IP addresses and the Claude account."""
    home = str(Path.home())
    known = {str(p): name for p, name in paths.items()}
    for path, name in ((home, "<home>"), (tempfile.gettempdir(), "<tmp>")):
        known.setdefault(path, name)
    known = path_forms(known, home)
    try:
        user = getpass.getuser()
    except (KeyError, OSError):
        user = Path(home).name
    return Secrets(paths=known, user=user, host=socket.gethostname(), home=home,
                   tokens=[t for t in tokens if t], ips=sorted(set(ips) | set(machine_ips())),
                   account=claude_account(claude_json))


def claude_account(path: Path) -> list[str]:
    """The login's email, display and organization names from Claude Code's ~/.claude.json (`oauthAccount`)."""
    try:
        account = json.loads(path.read_text()).get("oauthAccount")
    except (OSError, ValueError, AttributeError):
        return []
    if not isinstance(account, dict):
        return []
    keys = ("emailAddress", "displayName", "organizationName", "accountUuid", "organizationUuid")
    return [str(account[k]) for k in keys if isinstance(account.get(k), str) and len(account[k]) >= 3]


def machine_ips() -> list[str]:
    """The addresses of this machine's interfaces (/proc/net/fib_trie and if_inet6) and of its host name."""
    found: set[str] = set()
    try:
        lines = Path("/proc/net/fib_trie").read_text().splitlines()
        for before, line in zip(lines, lines[1:]):
            if "/32 host LOCAL" in line and "|--" in before:
                found.add(before.split("|--")[1].strip())
    except OSError:
        pass
    try:
        for line in Path("/proc/net/if_inet6").read_text().splitlines():
            raw = line.split()[0]
            found.add(str(ipaddress.ip_address(bytes.fromhex(raw))))
    except (OSError, ValueError, IndexError):
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None):
            found.add(str(info[4][0]).split("%")[0])
    except OSError:
        pass
    keep = []
    for text in found:
        try:
            ip = ipaddress.ip_address(text)
        except ValueError:
            continue
        if not (ip.is_loopback or ip.is_unspecified or ip.is_link_local):
            keep.append(ip.compressed)
    return keep


def _word(value: str) -> re.Pattern:
    return re.compile(r"(?<![A-Za-z0-9<])" + re.escape(value) + r"(?![A-Za-z0-9>])", re.IGNORECASE)


def _ip(value: str) -> re.Pattern:
    """An address alone: not part of a longer one (an IPv4 address may be followed by its port)."""
    after = r"(?![0-9A-Fa-f:])" if ":" in value else r"(?!\d|\.\d)"
    return re.compile(r"(?<![0-9A-Fa-f:.])" + re.escape(value) + after, re.IGNORECASE)


class Redactor:
    """One per probe run: the UUID and name placeholders stay the same across its turns."""

    def __init__(self, secrets: Secrets, known: list[str] | tuple[str, ...] = ()):
        self.s = secrets
        self.uuids: dict[str, str] = {}
        self.names: dict[str, str] = {}
        self.texts: dict[str, str] = {}
        self.learned: list[tuple[re.Pattern, str]] = []  # setup names written elsewhere too
        self.paths = sorted(((p, n) for p, n in secrets.paths.items() if len(p) > 1), key=lambda pn: -len(pn[0]))
        self.words = [(_word(v), "<user>") for v in [secrets.user] if v] + \
                     [(_word(v), "<host>") for v in {secrets.host, secrets.host.split(".")[0]} if v]
        self.ips = [_ip(ip) for ip in secrets.ips]
        home = secrets.home.rstrip("/")
        # A known folder's part below home ("projects/x"), plain and encoded: what is left when only the
        # user name in it was replaced. Only for the self-check.
        # One folder name alone ("projects") is too common a word to search for.
        tails = {p[len(home) + 1:] for p in secrets.paths if home and p.startswith(home + "/")}
        tails = {t for t in tails if "/" in t and not t.startswith((".", "~"))}
        self.tails = sorted(tails | {encoded(t) for t in tails})
        self.known = {self.text(k) for k in known if k} | {LOCAL_OUTPUT}  # the probe's own prompts and words

    # ---------------------------------------------------------------- strings
    def text(self, value: str) -> str:
        for token in self.s.tokens:
            value = value.replace(token, TOKEN)
        value = BEARER.sub("Bearer " + TOKEN, value)
        value = API_KEY.sub(TOKEN, value)
        for path, name in self.paths:
            value = value.replace(path, name)
        value = PROJECT.sub(r"\1<project>", value)
        for item in self.s.account:
            value = value.replace(item, "<account>")
        value = EMAIL.sub("<email>", value)
        value = TIME_ZONE.sub("<tz>", value)
        value = UUID.sub(lambda m: self.uuid(m.group(0)), value)
        for pattern in self.ips:
            value = pattern.sub("<ip>", value)
        for pattern, name in self.words:
            value = pattern.sub(name, value)
        for pattern, name in self.learned:
            value = pattern.sub(name, value)
        return value

    def value(self, value: str) -> str:
        """One string: the deny-list, then the allow-list."""
        value = self.text(value)
        if self.allowed(value):
            return value
        inside = self._json(value)
        return inside if inside is not None else self.free(value)

    def allowed(self, value: str) -> bool:
        return (value == "" or value in self.known or bool(WORD.fullmatch(value))
                or bool(ONLY_PLACEHOLDERS.fullmatch(value)) or bool(SAFE_PATH.fullmatch(value)))

    def free(self, value: str) -> str:
        if value not in self.texts:
            self.texts[value] = f"<text-{len(self.texts) + 1}>"
        return self.texts[value]

    def _json(self, value: str) -> str | None:
        if value.strip()[:1] not in ("{", "["):
            return None
        try:
            data = json.loads(value)
        except ValueError:
            return None
        return json.dumps(self.walk(data), ensure_ascii=False) if isinstance(data, (dict, list)) else None

    def uuid(self, value: str) -> str:
        key = value.lower()
        if key not in self.uuids:
            self.uuids[key] = f"<uuid-{len(self.uuids) + 1}>"
        return self.uuids[key]

    def name(self, value: str) -> str:
        if value not in self.names:
            self.names[value] = name = f"<name-{len(self.names) + 1}>"
            if len(value) >= 5 and re.fullmatch(r"[^\s<>]*[-:][^\s<>]*", value):
                edge = r"[A-Za-z0-9_:-]"
                self.learned.append((re.compile(f"(?<!{edge})" + re.escape(value) + f"(?!{edge})"), name))
        return self.names[value]

    # ---------------------------------------------------------------- events
    def events(self, events: list[dict]) -> list[dict]:
        """One turn's events: the streamed pieces of each block are redacted joined (`event` alone cannot)."""
        events = [self._local_output(e) for e in json.loads(json.dumps(events))]
        cuts = [_cut(self.value("".join(d[f] for d, f in pieces)), [len(d[f]) for d, f in pieces])
                for pieces in blocks(events).values()]
        out = [self.event(e) for e in events]
        # The pieces are written back after the walk, which would take each one for a string of its own.
        # Message ids and indexes survive the walk, so the blocks come out the same.
        for pieces, cut in zip(blocks(out).values(), cuts, strict=True):
            for (delta, name), text in zip(pieces, cut, strict=True):
                delta[name] = text
        return out

    def event(self, event: dict) -> dict:
        return self.walk(self._local_output(event))

    @staticmethod
    def _local_output(event: dict) -> dict:
        """What a local command printed (`/usage`: the owner's usage, folders and plugins) is not kept."""
        if not isinstance(event, dict):
            return event
        if "local_command_source" in event or "local_command_run" in event:
            event = json.loads(json.dumps(event))
            if "local_command_source" in event:
                event["local_command_source"] = LOCAL_OUTPUT
            for block in (event.get("message") or {}).get("content") or []:
                if isinstance(block, dict) and isinstance(block.get("text"), str):
                    block["text"] = LOCAL_OUTPUT
        if event.get("type") == "result" and event.get("local_command") and isinstance(event.get("result"), str):
            event = {**event, "result": LOCAL_OUTPUT}
        return event

    def walk(self, value):
        if isinstance(value, dict):
            if any(k in value for k in (*SETUP_LISTS, "mcp_servers", "tools", "output_style")):
                value = self._setup(value)
            return {self._key(str(k)): self.walk(v) for k, v in value.items()}
        if isinstance(value, list):
            return [self.walk(v) for v in value]
        if isinstance(value, str):
            return self.value(value)
        return value

    def _key(self, key: str) -> str:
        key = self.text(key)
        return key if KEY.fullmatch(key) or ONLY_PLACEHOLDERS.fullmatch(key) else self.free(key)

    def _setup(self, event: dict) -> dict:
        """The owner's own setup, by rule: names nobody can list in advance."""
        event = dict(event)
        for key in SETUP_LISTS:
            if isinstance(event.get(key), list):
                event[key] = [v if isinstance(v, dict) and v.get("builtin") is True or v == "default"
                              else self._entry(v) for v in event[key]]
        if isinstance(event.get("output_style"), str) and event["output_style"] != "default":
            event["output_style"] = self.name(event["output_style"])
        if isinstance(event.get("mcp_servers"), list):
            event["mcp_servers"] = [
                {**s, "name": s["name"] if s.get("name") in OWN else self.name(str(s.get("name")))}
                if isinstance(s, dict) else self._entry(s) for s in event["mcp_servers"]]
        if isinstance(event.get("tools"), list):
            event["tools"] = [self._tool(t) for t in event["tools"]]
        return event

    def _entry(self, value):
        if isinstance(value, dict):
            return {k: self.name(v) if isinstance(v, str) else self._entry(v) for k, v in value.items()}
        if isinstance(value, list):
            return [self._entry(v) for v in value]
        return self.name(value) if isinstance(value, str) else value

    def _tool(self, value):
        if not isinstance(value, str) or not value.startswith("mcp__"):
            return value
        if any(value.startswith(f"mcp__{own}__") for own in OWN):
            return value
        return self.name(value)

    # ---------------------------------------------------------------- the self-check
    def leaks(self, text: str) -> list[str]:
        """The kinds of known personal values still in `text` (never the values)."""
        text = PLACEHOLDER.sub(" ", text)
        found = []
        if any(t in text for t in self.s.tokens) or BEARER.search(text) or API_KEY.search(text):
            found.append("token")
        home = self.s.home if len(self.s.home) > 1 else ""
        if home and (home in text or encoded(home) in text):
            found.append("home")
        if any(name not in ("<tmp>", "<home>") and path in text for path, name in self.paths) or \
                any(tail in text for tail in self.tails):
            found.append("path")
        if self.s.user and _word(self.s.user).search(text):
            found.append("user")
        if any(h and _word(h).search(text) for h in {self.s.host, self.s.host.split(".")[0]}):
            found.append("host")
        if any(p.search(text) for p in self.ips):
            found.append("ip")
        if any(a in text for a in self.s.account) or EMAIL.search(text):
            found.append("account")
        if TIME_ZONE.search(text):
            found.append("time zone")
        if any(pattern.search(text) for pattern, _ in self.learned):
            found.append("setup")
        return found


def blocks(events: list[dict]) -> dict[tuple[str, int], list[tuple[dict, str]]]:
    """The streamed pieces of each content block of a turn, in order: (message id, index) -> [(delta, field)]."""
    found: dict[tuple[str, int], list[tuple[dict, str]]] = {}
    message = ""
    for e in events:
        inner = e.get("event") if isinstance(e, dict) and e.get("type") == "stream_event" else None
        if not isinstance(inner, dict):
            continue
        if inner.get("type") == "message_start":
            message = str((inner.get("message") or {}).get("id") or len(found))
        delta = inner.get("delta") if inner.get("type") == "content_block_delta" else None
        if isinstance(delta, dict):
            for name in DELTA_FIELDS:
                if isinstance(delta.get(name), str):
                    found.setdefault((message, int(inner.get("index") or 0)), []).append((delta, name))
    return found


def joined_blocks(events: list[dict]) -> list[str]:
    return ["".join(d[f] for d, f in pieces) for pieces in blocks(events).values()]


def _cut(text: str, sizes: list[int]) -> list[str]:
    """`text` in len(sizes) pieces, in proportion to the original ones; the last takes the rest."""
    total = sum(sizes) or 1
    pieces, start, seen = [], 0, 0
    for size in sizes[:-1]:
        seen += size
        end = max(start, min(len(text), round(len(text) * seen / total)))
        pieces.append(text[start:end])
        start = end
    pieces.append(text[start:])
    return pieces
