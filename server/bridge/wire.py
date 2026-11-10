# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The /app protocol's names: one source of truth for the server.

Message types both ways, the chat event kinds, and the codes the app words in its language. What each
message carries is in the JSON Schemas of docs/protocol/app/; tests/test_protocol.py checks that they
and these names say the same, and that the app's link/AppProtocol.kt has the same names.

Also ProtocolError, the one exception for a malformed /app message, and the vocabulary the layers share
(docs/architecture.md): the gate's states and actions, the permission modes and effort levels, the
shape of an "always allow" rule, and the key of a frame's number. Nothing here imports from bridge.

The values are StrEnums: each member is a str, so JSON, comparisons and the kept history see plain text.
"""
from __future__ import annotations

import re
from enum import StrEnum

PROTOCOL = 1


class ProtocolError(Exception):
    """A malformed /app message from a client: not JSON, not an object, an unknown type, or a field that is missing, of
    the wrong type or out of range. `code` is the error's code (Err); `message` is the English text of a reply that
    has no chat request to name (the others say "<type> failed: <code>"); `data` goes with the code to the app.
    appws turns it into the error answer; a request that is well formed but cannot be served now (a conversation that
    is gone, a busy slot, a missing attachment) is the chat's ChatError instead."""

    def __init__(self, code: str, message: str | None = None, **data):
        super().__init__(code)
        self.code, self.message, self.data = code, message, data


class Out(StrEnum):
    """Server -> app message types."""
    HELLO = "hello"
    STATUS = "status"
    FRAME = "frame"
    AUDIT_HISTORY = "audit.history"
    AUDIT = "audit"
    PHONE_HISTORY = "phone.history"
    PHONE = "phone"
    DIAG = "diag"
    ERROR = "error"
    PONG = "pong"
    CHAT_STATUS = "chat.status"
    CHAT_CONVERSATIONS = "chat.conversations"
    CHAT_CONVERSATION = "chat.conversation"
    CHAT_DELETED = "chat.deleted"
    CHAT_HISTORY = "chat.history"
    CHAT_EVENT = "chat.event"
    CHAT_DELTA = "chat.delta"


class In(StrEnum):
    """App -> server message types."""
    GATE = "gate"
    SCREEN_FRESH = "screen.fresh"
    DIAG = "diag"
    PING = "ping"
    CHAT_LIST = "chat.list"
    CHAT_NEW = "chat.new"
    CHAT_OPEN = "chat.open"
    CHAT_SEND = "chat.send"
    CHAT_INTERRUPT = "chat.interrupt"
    CHAT_SET = "chat.set"
    CHAT_PERMISSION_ANSWER = "chat.permission.answer"
    CHAT_UPDATE = "chat.update"
    CHAT_DELETE = "chat.delete"
    CHAT_SETTINGS = "chat.settings"
    CHAT_REMOTE = "chat.remote"


CHAT_PREFIX = "chat."
OVERFLOW = "chat.overflow"  # the manager's queue marker for a client too far behind; never sent


class Feature(StrEnum):
    """hello's "features"."""
    CHAT = "chat"
    ATTACHMENTS = "attachments"
    DELETE = "delete"
    REMOTE = "remote"


class Kind(StrEnum):
    """chat.event kinds."""
    USER = "user"
    TURN_START = "turn.start"
    TURN_INFO = "turn.info"
    TEXT = "text"
    TOOL = "tool"
    TOOL_RESULT = "tool.result"
    PERMISSION = "permission"
    PERMISSION_END = "permission.end"
    DENIED = "denied"
    NOTICE = "notice"
    TURN_END = "turn.end"


class Err(StrEnum):
    """error codes: the first six for any request, the rest for chat.* requests (with data)."""
    BAD_JSON = "bad_json"
    BAD_MESSAGE = "bad_message"
    BAD_ACTION = "bad_action"
    NOT_CONNECTED = "not_connected"
    FLIPPER = "flipper"
    UNKNOWN_TYPE = "unknown_type"
    BUSY = "busy"
    NOT_FOUND = "not_found"
    EXPIRED = "expired"
    TOO_LONG = "too_long"
    NOT_READY = "not_ready"
    BAD_MODE = "bad_mode"
    BAD_MODEL = "bad_model"
    BAD_EFFORT = "bad_effort"
    MODEL_UNAVAILABLE = "model_unavailable"
    BAD_AGENT = "bad_agent"
    BAD_REQUEST = "bad_request"
    PERMISSION_GONE = "permission_gone"
    BAD_ATTACHMENT = "bad_attachment"
    TOO_LARGE = "too_large"
    REMOTE_SETTINGS = "remote_settings"
    REMOTE_BUSY = "remote_busy"
    REMOTE_ATTACHMENTS = "remote_attachments"
    REMOTE_COMMAND = "remote_command"
    REMOTE_UNSUPPORTED = "remote_unsupported"


class Problem(StrEnum):
    """An agent's "problem" (hello's and chat.status's agents), and not_ready's "problem"."""
    NOT_CONFIGURED = "not_configured"
    AGENT_MISSING = "agent_missing"
    UNSAFE_AGENT_SETTINGS = "unsafe_agent_settings"
    CHECKING = "checking"
    NOT_LOGGED_IN = "not_logged_in"
    NOT_SUBSCRIPTION = "not_subscription"
    STOPPING = "stopping"
    AGENT_FOLDER_UNTRUSTED = "agent_folder_untrusted"
    REMOTE_STARTING = "remote_starting"
    REMOTE_ENDING = "remote_ending"
    AGENT_VERSION = "agent_version"


class Notice(StrEnum):
    """The notice event's "code"."""
    AUTO_UNAVAILABLE = "auto_unavailable"
    FLIPPER_MCP_FAILED = "flipper_mcp_failed"
    API_RETRY = "api_retry"
    COMPACTED = "compacted"
    RATE_LIMIT = "rate_limit"
    REMOTE_ON = "remote_on"
    REMOTE_OFF = "remote_off"
    REMOTE_PERMISSION = "remote_permission"


class RemoteOff(StrEnum):
    """The remote_off notice's "reason"."""
    OFF = "off"
    DELETED = "deleted"
    SERVER_RESTARTED = "server_restarted"
    SERVER_STOPPED = "server_stopped"
    IDLE = "idle"
    COULD_NOT_START = "could_not_start"
    EXITED = "exited"


class Outcome(StrEnum):
    """turn.end's "outcome"."""
    DONE = "done"
    INTERRUPTED = "interrupted"
    ERROR = "error"


class TurnError(StrEnum):
    """turn.end's "error"; the last four only with Remote Control."""
    AGENT_FAILED = "agent_failed"
    BILLING_GUARD = "billing_guard"
    EXPIRED = "expired"
    API_ERROR = "api_error"
    MAX_TURNS = "max_turns"
    SERVER_RESTARTED = "server_restarted"
    STOPPING = "stopping"
    IMAGE_LIMIT = "image_limit"  # the model's images per conversation: data images, limit, compacted
    PERMISSION_STALE = "permission_stale"
    NO_NEWS = "no_news"
    REMOTE_ENDED = "remote_ended"
    IDLE = "idle"


class Guard(StrEnum):
    """Why the subscription guard killed a turn: billing_guard's data "why"."""
    USAGE_EXHAUSTED = "usage_exhausted"
    API_KEY_SOURCE = "api_key_source"
    MODEL = "model"


class Answer(StrEnum):
    """permission.end's "answer": the app's three, and the server's own endings."""
    ONCE = "once"
    ALWAYS = "always"
    DENY = "deny"
    TIMEOUT = "timeout"
    INTERRUPTED = "interrupted"
    STOPPING = "stopping"


class DeniedBy(StrEnum):
    """The denied event's "by"."""
    AUTO = "auto"
    MODE = "mode"


VIA_REMOTE = "remote"  # "via" on events and the turn: it came from the other device, in Remote Control

# The frame number: a Flipper tool's result names it (tools.frame_meta), the stream parser and remote.flipper_seq read
# it back, and the manager swaps it for the kept frame (manager._screen).
SEQ = "seq"

# The operator gate: status "gate" is one of the states; the gate message's "action" sets one.
ACTIVE, PAUSED, STOPPED = "active", "paused", "stopped"
GATE_ACTIONS = {"pause": PAUSED, "resume": ACTIVE, "stop": STOPPED}


class GateVia(StrEnum):
    """The gate message's optional "via": who on the phone sent it. It becomes the first word of the gate's "by", so
    status "gate_by" and the audit read it. Apart from VIA_REMOTE (Remote Control), which only shares the word."""
    REMOTE = "remote"  # the phone's remote for the Flipper, which pauses the agent while the owner drives


GATE_VIAS = tuple(GateVia)


class Mode(StrEnum):
    """A conversation's permission mode: Claude Code's own (code.claude.com/docs/en/permission-modes), with its names:
    Manual, Accept edits, Plan and Auto. dontAsk and bypassPermissions are not offered."""
    DEFAULT = "default"
    ACCEPT_EDITS = "acceptEdits"
    PLAN = "plan"
    AUTO = "auto"


class Effort(StrEnum):
    """A conversation's effort level."""
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    XHIGH = "xhigh"
    MAX = "max"


class AgentId(StrEnum):
    """The agents the server knows, by the id /app names them with (hello's and chat.status's agents, a conversation's
    agent). A conversation saved before agents had ids is Claude Code's."""
    CLAUDE_CODE = "claude-code"
    OPENCODE = "opencode"


class ModelReason(StrEnum):
    """Why a model is not available (an agent's models' "reason")."""
    USAGE_CREDITS = "usage_credits"  # the agent says it bills usage credits
    UNCHECKED = "unchecked"          # its list cannot prove the owner's subscription covers it
    NOT_SIGNED_IN = "not_signed_in"  # free, on a provider the agent is not signed in to


_AGENT_ID = re.compile(r"[a-z][a-z0-9-]{0,39}")


def valid_agent_id(value: object) -> bool:
    """An agent's id as /app carries it (defs.json's agentId)."""
    return isinstance(value, str) and _AGENT_ID.fullmatch(value) is not None


MODES = tuple(Mode)
EFFORTS = tuple(Effort)
LEGACY_MODES = {"manual": "default", "flipper": "default"}  # names used before 2026-10-23


def normal_mode(mode: object) -> str | None:
    mode = LEGACY_MODES.get(mode, mode) if isinstance(mode, str) else None
    return mode if mode in MODES else None


_RULE = re.compile(r"[A-Za-z][A-Za-z0-9_-]*(\([^\x00-\x1f\x7f]{1,500}\))?")


_QUOTES = frozenset("\"'`")


def valid_rule(rule: object) -> bool:
    """An "always allow" rule: Tool or Tool(content). It can never look like an option, nor be read as several rules:
    its content's parentheses balance and never close before its end, and it has no quote (a rule list's parser
    splits on those: Read(x") Bash ") must never become a bare Bash rule; security scan before 1.0.0, F1)."""
    if not isinstance(rule, str) or len(rule) > 600 or _RULE.fullmatch(rule) is None:
        return False
    content = rule[rule.index("(") + 1:-1] if "(" in rule else ""
    depth = 0
    for c in content:
        if c in _QUOTES:
            return False
        depth += (c == "(") - (c == ")")
        if depth < 0:
            return False
    return depth == 0


def notice(code: Notice, data: dict | None = None) -> dict:
    """A notice event."""
    return {"kind": Kind.NOTICE, "code": code, "data": data if data is not None else {}}


def turn_end(turn: int, outcome: Outcome, error: TurnError | None = None, data: dict | None = None,
             duration_ms: int | None = None) -> dict:
    """A turn.end event."""
    return {"kind": Kind.TURN_END, "turn": turn, "outcome": outcome, "error": error,
            "data": data if data is not None else {}, "duration_ms": duration_ms}
