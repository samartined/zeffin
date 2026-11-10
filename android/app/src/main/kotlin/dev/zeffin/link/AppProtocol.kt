// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

/**
 * The /app protocol's names, as the server's bridge/wire.py and bridge/events.py have them. What each message
 * carries is in docs/protocol/app/ (a JSON Schema per message and examples); the server's tests check that these
 * names are the same as wire.py's, and the app's tests read every example.
 */
object AppProtocol {
    /** hello's "protocol": the version this app speaks. */
    const val PROTOCOL = 1

    const val CHAT_PREFIX = "chat."

    /** "via" on events and the turn: it came from the other device, in Remote Control. */
    const val VIA_REMOTE = "remote"

    /** Server -> app message types. */
    object Out {
        const val HELLO = "hello"
        const val STATUS = "status"
        const val FRAME = "frame"
        const val AUDIT_HISTORY = "audit.history"
        const val AUDIT = "audit"
        const val PHONE_HISTORY = "phone.history"
        const val PHONE = "phone"
        const val DIAG = "diag"
        const val ERROR = "error"
        const val PONG = "pong"
        const val CHAT_STATUS = "chat.status"
        const val CHAT_CONVERSATIONS = "chat.conversations"
        const val CHAT_CONVERSATION = "chat.conversation"
        const val CHAT_DELETED = "chat.deleted"
        const val CHAT_HISTORY = "chat.history"
        const val CHAT_EVENT = "chat.event"
        const val CHAT_DELTA = "chat.delta"
    }

    /**
     * The gate message's optional "via" (wire.GateVia): the phone's remote for the Flipper, recorded as "remote"
     * in the gate's "by", status "gate_by" and the audit. Apart from VIA_REMOTE (Remote Control): same word only.
     */
    object GateVia {
        const val REMOTE = "remote"
    }

    /** App -> server message types. */
    object In {
        const val GATE = "gate"
        const val SCREEN_FRESH = "screen.fresh"
        const val DIAG = "diag"
        const val PING = "ping"
        const val CHAT_LIST = "chat.list"
        const val CHAT_NEW = "chat.new"
        const val CHAT_OPEN = "chat.open"
        const val CHAT_SEND = "chat.send"
        const val CHAT_INTERRUPT = "chat.interrupt"
        const val CHAT_SET = "chat.set"
        const val CHAT_PERMISSION_ANSWER = "chat.permission.answer"
        const val CHAT_UPDATE = "chat.update"
        const val CHAT_DELETE = "chat.delete"
        const val CHAT_SETTINGS = "chat.settings"
        const val CHAT_REMOTE = "chat.remote"
    }

    /** hello's "features". */
    object Feature {
        const val CHAT = "chat"
        const val ATTACHMENTS = "attachments"
        const val DELETE = "delete"
        const val REMOTE = "remote"
    }

    /** chat.event kinds. */
    object Kind {
        const val USER = "user"
        const val TURN_START = "turn.start"
        const val TURN_INFO = "turn.info"
        const val TEXT = "text"
        const val TOOL = "tool"
        const val TOOL_RESULT = "tool.result"
        const val PERMISSION = "permission"
        const val PERMISSION_END = "permission.end"
        const val DENIED = "denied"
        const val NOTICE = "notice"
        const val TURN_END = "turn.end"
    }

    /** error codes: the first six for any request, the rest for chat.* requests (with data). */
    object Err {
        const val BAD_JSON = "bad_json"
        const val BAD_MESSAGE = "bad_message"
        const val BAD_ACTION = "bad_action"
        const val NOT_CONNECTED = "not_connected"
        const val FLIPPER = "flipper"
        const val UNKNOWN_TYPE = "unknown_type"
        const val BUSY = "busy"
        const val NOT_FOUND = "not_found"
        const val EXPIRED = "expired"
        const val TOO_LONG = "too_long"
        const val NOT_READY = "not_ready"
        const val BAD_MODE = "bad_mode"
        const val BAD_MODEL = "bad_model"
        const val BAD_EFFORT = "bad_effort"
        const val MODEL_UNAVAILABLE = "model_unavailable"
        const val BAD_AGENT = "bad_agent"
        const val BAD_REQUEST = "bad_request"
        const val PERMISSION_GONE = "permission_gone"
        const val BAD_ATTACHMENT = "bad_attachment"
        const val TOO_LARGE = "too_large"
        const val REMOTE_SETTINGS = "remote_settings"
        const val REMOTE_BUSY = "remote_busy"
        const val REMOTE_ATTACHMENTS = "remote_attachments"
        const val REMOTE_COMMAND = "remote_command"
        const val REMOTE_UNSUPPORTED = "remote_unsupported"
    }

    /** An agent's "problem" (hello's and chat.status's agents), and not_ready's "problem". */
    object Problem {
        const val NOT_CONFIGURED = "not_configured"
        const val CLAUDE_MISSING = "claude_missing"
        const val AGENT_MISSING = "agent_missing"
        const val UNSAFE_AGENT_SETTINGS = "unsafe_agent_settings"
        const val CHECKING = "checking"
        const val NOT_LOGGED_IN = "not_logged_in"
        const val NOT_SUBSCRIPTION = "not_subscription"
        const val STOPPING = "stopping"
        const val AGENT_FOLDER_UNTRUSTED = "agent_folder_untrusted"
        const val REMOTE_STARTING = "remote_starting"
        const val REMOTE_ENDING = "remote_ending"
        const val AGENT_VERSION = "agent_version"
    }

    /** The agents a server may know, by their id (hello's and chat.status's agents, a conversation's agent). Any
     *  other id is kept as it comes: a newer server may know more. */
    object AgentId {
        const val CLAUDE_CODE = "claude-code"
        const val OPENCODE = "opencode"
    }

    /** Why a model is not available (an agent's models' "reason"). */
    object ModelReason {
        const val USAGE_CREDITS = "usage_credits"
        const val UNCHECKED = "unchecked"
        const val NOT_SIGNED_IN = "not_signed_in"
    }

    /** The notice event's "code". */
    object Notice {
        const val AUTO_UNAVAILABLE = "auto_unavailable"
        const val FLIPPER_MCP_FAILED = "flipper_mcp_failed"
        const val API_RETRY = "api_retry"
        const val COMPACTED = "compacted"
        const val RATE_LIMIT = "rate_limit"
        const val REMOTE_ON = "remote_on"
        const val REMOTE_OFF = "remote_off"
        const val REMOTE_PERMISSION = "remote_permission"
    }

    /** The remote_off notice's "reason". */
    object RemoteOff {
        const val OFF = "off"
        const val DELETED = "deleted"
        const val SERVER_RESTARTED = "server_restarted"
        const val SERVER_STOPPED = "server_stopped"
        const val IDLE = "idle"
        const val COULD_NOT_START = "could_not_start"
        const val EXITED = "exited"
    }

    /** turn.end's "outcome". */
    object Outcome {
        const val DONE = "done"
        const val INTERRUPTED = "interrupted"
        const val ERROR = "error"
    }

    /** turn.end's "error"; the last four only with Remote Control. */
    object TurnError {
        const val CLAUDE_FAILED = "claude_failed"
        const val AGENT_FAILED = "agent_failed"
        const val BILLING_GUARD = "billing_guard"
        const val EXPIRED = "expired"
        const val API_ERROR = "api_error"
        const val MAX_TURNS = "max_turns"
        const val SERVER_RESTARTED = "server_restarted"
        const val STOPPING = "stopping"
        const val IMAGE_LIMIT = "image_limit"  // 1.0.0: past the images the model takes; data images, limit, compacted
        const val PERMISSION_STALE = "permission_stale"
        const val NO_NEWS = "no_news"
        const val REMOTE_ENDED = "remote_ended"
        const val IDLE = "idle"
    }

    /** permission.end's "answer": the app's three, and the server's own endings. */
    object Answer {
        const val ONCE = "once"
        const val ALWAYS = "always"
        const val DENY = "deny"
        const val TIMEOUT = "timeout"
        const val INTERRUPTED = "interrupted"
        const val STOPPING = "stopping"
    }

    /** The relay's connection events in phone.history and phone (the metrics log's "ev"). */
    object PhoneEv {
        const val WS_OPEN = "ws_open"
        const val READY = "ready"
        const val FIRST_FRAME = "first_frame"
        const val CLOSE = "close"
        const val WS_REJECTED = "ws_rejected"
        const val STREAM = "stream"
        const val PAIRED = "paired"
    }
}
