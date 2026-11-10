# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The Agent port: how the chat services reach the agent, without knowing which one it is.

The chat services (chat/manager.py and the pieces behind it) hold the bridge's rules: one turn at a time, who answers a
permission and until when, the conversations. Everything specific to the agent (its command line and environment, its
output format, its hooks, its models, where it keeps its sessions) lives behind this port, in the agent integration
layer: chat/claude_code.py for Claude Code. The composition root (app.py) builds every agent the server knows and hands
them to ChatManager; each conversation has one, by its `id`, and what it offers (models, modes, efforts, Remote
Control, attachments) is its agent's.

Two ways of working:
- **a turn per process:** `start_turn` starts one, and its output comes back as the neutral events below. Its
  permission requests reach the bridge either through hooks (Claude Code: `read_permission`, `permission_reply`) or
  inside the agent's own protocol (OpenCode over ACP): then the integration hands each one to `TurnRequest.ask`, which
  the bridge's broker answers, and waits for the answer;
- **an interactive session followed by hooks:** `remote` starts and stops it, types into it, and reads its hooks as
  `RemoteHook`.

The events the agent shows are already in the bridge's own vocabulary (wire.py's chat events): the wire is the
bridge's, not the agent's. Values such as an outcome, a turn error or a guard are wire.py's names, carried as strings:
this module is a contract and imports nothing from the bridge.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, AsyncIterator, Awaitable, Callable, Protocol


# ------------------------------------------------------------------ permissions of a turn
@dataclass
class PermissionAsk:
    session: object                # the session the request says it belongs to
    tool: str
    input: object
    rules: list[str]               # what "always" would grant
    flipper: bool = False          # a tool of the bridge's own MCP server
    whole: bool = True             # input is the request's whole input; False: too long to show, it can only be denied


@dataclass
class PermissionAnswer:
    """The bridge's answer to a permission request: a wire.Answer (once, always, deny, or the bridge's own endings:
    timeout, interrupted, stopping) and, with always, the rules the owner granted."""
    answer: str
    rules: list[str] = field(default_factory=list)


# A permission request handed to the bridge, from inside the agent's protocol: the broker holds it until the owner
# answers in the app, the deadline passes or the turn ends, with the same rules as a hook's.
Ask = Callable[[PermissionAsk], Awaitable[PermissionAnswer]]


# ------------------------------------------------------------------ a turn per process
@dataclass(frozen=True)
class TurnRequest:
    session: str                   # the agent's session: the conversation's id, or the one the agent gave it
    resume: bool                   # the session has a transcript: go on with it
    model: str
    effort: str | None
    mode: str                      # one of wire.MODES
    rules: list[str]               # permissions granted with "always" on earlier turns
    permission_timeout_s: float
    text: str                      # the message, with the lines naming the attached files
    images: list[tuple[str, Path]] = field(default_factory=list)  # (media type, file), in the message's order
    folders: list[str] = field(default_factory=list)  # folders the agent may read without asking (attachments)
    token: str = ""                # the turn's hook token: its hooks bring it back
    ask: Ask | None = None         # where an agent without hooks hands its permission requests; None: refuse them


@dataclass
class Delta:
    """Text being written: may be dropped."""
    block: str
    offset: int
    text: str


@dataclass
class Output:
    """A chat event to show (text, tool, tool.result, a notice), already in the wire's shape. `flipper`: a tool of the
    bridge's own MCP server; `seq`: the frame its result reports, for the bridge to attach."""
    event: dict
    flipper: bool = False
    seq: int | None = None


@dataclass
class Started:
    """The agent says how the turn runs. `guard` (a wire.Guard) when it must be killed at once."""
    session: str | None
    model: str
    mode: object
    effort: object
    version: object
    mcp: object                    # the bridge's MCP server's status as the agent reports it
    mcp_ok: bool
    guard: str | None = None


@dataclass
class Denied:
    """A tool the agent's own rules denied. `by_hook`: the owner's answer (or its timeout), already shown."""
    tool_use: str | None
    tool: str
    input: object
    reason: str | None
    by_hook: bool = False


@dataclass
class Limit:
    """Going on would need usage credits: the turn is killed (wire.Guard.USAGE_EXHAUSTED)."""


TurnEvent = Delta | Output | Started | Denied | Limit


@dataclass
class TurnEnd:
    """How the turn ended, when the bridge did not end it: a wire.Outcome, a wire.TurnError and its data. `note`: a
    line for the server log when the agent failed (never sent to the phone)."""
    outcome: str
    error: str | None = None
    data: dict = field(default_factory=dict)
    note: str | None = None


class AgentTurn(Protocol):
    def lines(self) -> AsyncIterator[list[TurnEvent]]:
        """The events of each line of output, until the output ends."""

    async def wait(self, timeout: float) -> bool:
        """Whether the process ended within the timeout."""

    async def end(self, waits: tuple[float, float]) -> None:
        """Ask it to stop, then terminate, then kill, waiting this long between them."""

    def kill(self) -> None:
        """At once (a guard)."""

    async def drain(self) -> list[Denied]:
        """After the end: the denials the agent reports only in its result."""

    def outcome(self) -> TurnEnd:
        ...


# ------------------------------------------------------------------ hooks
@dataclass(frozen=True)
class HookRoutes:
    """The paths of the agent's hooks under /hooks: the turn's permission request and denial, the interactive
    session's events (under /hooks/remote/), and the header that says another device is connected."""
    header: str
    request: str
    denied: str
    remote: tuple[str, ...]


# ------------------------------------------------------------------ the interactive session
@dataclass(frozen=True)
class RemoteRequest:
    session: str
    resume: bool
    model: str
    effort: str | None
    mode: str
    rules: list[str]
    title: str
    token: str
    folders: list[str] = field(default_factory=list)  # folders the agent may read without asking (attachments)


@dataclass
class Launch:
    terminal: Any                  # the agent's handle on the running process
    argv: list[str]
    env: dict
    stale_s: float                 # how long a permission may wait with no hook before it is declined


class HookKind:
    PROMPT = "prompt"              # a prompt was submitted (ours, or from another device)
    TOOL_START = "tool_start"
    TOOL_DONE = "tool_done"
    STOP = "stop"                  # the turn ended with a reply
    FAILED = "failed"              # the turn ended with an error
    PERMISSION = "permission"      # a permission waits on the other device
    IDLE = "idle"                  # waiting for a prompt
    SESSION_END = "session_end"
    OTHER = "other"


@dataclass
class RemoteHook:
    kind: str                      # a HookKind
    prompt: str = ""
    tool: dict | None = None       # TOOL_DONE: the tool and tool.result events
    result: dict | None = None
    flipper: bool = False
    seq: int | None = None
    message: str | None = None     # STOP: the last reply, whole
    text: str | None = None        # STOP: the reply as shown
    error: str | None = None       # FAILED: why
    permission_tool: str | None = None
    reason: str | None = None      # SESSION_END: why


class Interactive(Protocol):
    watch_every_s: float
    start_limit_s: float
    no_news_s: float
    idle_end_s: float

    def launch(self, request: RemoteRequest) -> Launch:
        ...

    async def start(self, launch: Launch) -> None:
        """Raises OSError when it cannot start."""

    async def wait(self, launch: Launch) -> int | None:
        ...

    def type(self, terminal: Any, text: str) -> Awaitable[None]:
        ...

    def interrupt(self, terminal: Any) -> None:
        """Esc: never anything that could answer a question."""

    async def stop(self, terminal: Any, has_turn: bool, starting: bool,
                   waits: tuple[float, float] | None = None) -> None:
        ...

    def close(self, terminal: Any) -> None:
        ...

    def clean(self, text: str) -> str:
        """What may be typed."""

    def refused(self, text: str) -> bool:
        """Text that would be a command, never typed for the app."""

    def message(self, text: str, files: list[str]) -> str | None:
        """What is typed for a message that carries these files (absolute paths in RemoteRequest.folders), so the
        agent reads them with it; None when a path cannot be typed safely."""

    def matches(self, sent: str, sent_at: float, prompt: str) -> bool:
        """Whether a submitted prompt is the one we typed."""

    def read_hook(self, event: str, body: dict) -> RemoteHook:
        ...

    def block(self) -> dict:
        """The answer to a prompt hook that refuses the prompt."""


# ------------------------------------------------------------------ the agent
class Agent(Protocol):
    id: str                        # a wire.AgentId: how /app and the index name it
    remote: Interactive | None     # its interactive session, or None when it has none (no Remote Control)

    @property
    def configured(self) -> bool:
        """The agent is set up on this server (it can be checked)."""

    def problem(self) -> str | None:
        """Why no turn can start now (a wire.Problem), or None."""

    def remote_problem(self) -> str | None:
        """Why the interactive session cannot start now, beyond problem()."""

    async def check(self) -> None:
        """Look again: its version, its login and its models."""

    def identity(self) -> dict:
        """hello's agent: {"name", "version"}; the version is None until checked (or when it is missing)."""

    def mode_options(self) -> list[dict]:
        """Its entry's mode_options: {"value", "label"} for each mode it offers, in its order. value is one of
        wire.MODES, what chat.set and chat.new take; label is the agent's own English name for it."""

    def login(self) -> dict:
        """Its entry's login: {"method", "subscription"}. method is how it is signed in, in its own words (None when
        it is not, or not checked yet); subscription says it is the owner's subscription, decided here."""

    def models(self) -> list[dict]:
        """The catalog: every model it lists, with its efforts, whether it takes images, and its availability."""

    def summary_fields(self) -> dict:
        """The agent's own blocks of GET /status's chat."""

    def capabilities(self) -> dict:
        """Its entry's capabilities: {"remote": its remote access or None, "attachments": bool}."""

    def hook_routes(self) -> HookRoutes | None:
        """The paths of its hooks, or None when it has none."""

    def forget(self, session: str) -> int:
        """Delete what the agent keeps for a session; how many paths went."""

    async def start_turn(self, request: TurnRequest) -> AgentTurn:
        """Never raises: a turn that could not start ends at once, with the reason in its outcome."""

    def image_limit(self, model: str) -> int | None:
        """The images the model takes in one request, where its provider caps them below what a conversation may hold;
        None: no cap the bridge must keep under."""

    async def compact(self, request: TurnRequest) -> AgentTurn | None:
        """A turn of its own that replaces the session's history with a summary, images included; None when the agent
        cannot. Never raises, as start_turn."""

    def read_permission(self, body: dict) -> PermissionAsk:
        ...

    def permission_reply(self, answer: str, rules: list[str]) -> dict:
        ...

    def read_denied(self, body: dict) -> Denied:
        ...
