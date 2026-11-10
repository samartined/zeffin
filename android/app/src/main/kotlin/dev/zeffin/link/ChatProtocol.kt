// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

import dev.zeffin.link.AppProtocol.In
import dev.zeffin.link.AppProtocol.Kind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** The chat messages the app sends on /app, built as JSON (never by hand). Absent values are left out. */
object ChatOut {
    /** The most characters a message may have: the server's MAX_TEXT (server/bridge/chat/service.py), so one fits one /app frame. */
    const val MAX_TEXT = 16_000

    fun list() = msg(In.CHAT_LIST) {}

    /** With [agent] null, the server's default agent; with [mode], [model] or [effort] null, the agent's defaults. */
    fun new(mode: Mode? = null, model: String? = null, effort: Effort? = null, agent: String? = null) = msg(In.CHAT_NEW) {
        agent?.let { put("agent", it) }
        mode?.let { put("mode", it.wire) }
        model?.let { put("model", it) }
        effort?.let { put("effort", it.wire) }
    }

    fun open(conversation: String, before: Int? = null, after: Int? = null, limit: Int = PAGE) = msg(In.CHAT_OPEN) {
        put("conversation", conversation)
        before?.let { put("before", it) }
        after?.let { put("after", it) }
        put("limit", limit)
    }

    fun send(conversation: String, text: String, attachments: List<String> = emptyList()) = msg(In.CHAT_SEND) {
        put("conversation", conversation)
        put("text", text)
        if (attachments.isNotEmpty()) put("attachments", buildJsonArray { attachments.forEach { add(JsonPrimitive(it)) } })
    }

    fun interrupt(conversation: String) = msg(In.CHAT_INTERRUPT) { put("conversation", conversation) }

    fun set(conversation: String, mode: Mode? = null, model: String? = null, effort: Effort? = null) = msg(In.CHAT_SET) {
        put("conversation", conversation)
        mode?.let { put("mode", it.wire) }
        model?.let { put("model", it) }
        effort?.let { put("effort", it.wire) }
    }

    /** With always, [rules] are the exact rules the owner was shown: the server saves only those (and allows once without them). */
    fun answer(id: String, answer: PermissionAnswer, rules: List<String> = emptyList()) = msg(In.CHAT_PERMISSION_ANSWER) {
        put("id", id)
        put("answer", answer.wire)
        if (rules.isNotEmpty()) put("rules", buildJsonArray { rules.forEach { add(JsonPrimitive(it)) } })
    }

    fun update(conversation: String, title: String? = null, pinned: Boolean? = null, favorite: Boolean? = null) =
        msg(In.CHAT_UPDATE) {
            put("conversation", conversation)
            title?.let { put("title", it) }
            pinned?.let { put("pinned", it) }
            favorite?.let { put("favorite", it) }
        }

    /** Remote Control on or off for the conversation (feature "remote"). */
    fun remote(conversation: String, on: Boolean) = msg(In.CHAT_REMOTE) {
        put("conversation", conversation)
        put("on", on)
    }

    /** For good: its history, attachments and the agent's own files on the server (server 0.6.0, feature "delete"). */
    fun delete(conversation: String) = msg(In.CHAT_DELETE) { put("conversation", conversation) }

    /** [mode], [model] and [effort] are [agent]'s defaults (with [agent] null, the default agent's). */
    fun settings(mode: Mode? = null, model: String? = null, effort: Effort? = null, permissionTimeoutS: Int? = null,
                 agent: String? = null) =
        msg(In.CHAT_SETTINGS) {
            agent?.let { put("agent", it) }
            mode?.let { put("default_mode", it.wire) }
            model?.let { put("default_model", it) }
            effort?.let { put("default_effort", it.wire) }
            permissionTimeoutS?.let { put("permission_timeout_s", it) }
        }

    /** The agent of a new conversation when the app names none (chat.settings's default_agent). */
    fun defaultAgent(agent: String) = msg(In.CHAT_SETTINGS) { put("default_agent", agent) }

    const val PAGE = 200

    private inline fun msg(type: String, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject { put("type", type); fill() }
}

/** The most modes the sheet lists. */
private const val MAX_MODES = 20


/** An agent's "mode_options": the modes it offers, in its order; none when it sends none usable. */
internal fun modeOptions(m: JsonObject): List<ModeOption> = m.objects("mode_options").mapNotNull { o ->
    val mode = Mode.of(o.s("value")) ?: return@mapNotNull null
    ModeOption(mode, o.s("label", 40)?.takeIf { it.isNotBlank() } ?: mode.wire)
}.distinctBy { it.mode }.take(MAX_MODES)

/**
 * The rules "always" would save, shown and sent back as they are (chat.permission.answer): cleaned like a command,
 * line breaks kept, as many as an answer can carry. A rule the cleaning changed matches none on the server.
 */
private fun rules(o: JsonObject): List<String> = (o["rules"] as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let { r -> inputText(r, multiline = true) } }.take(ChatIn.MAX_RULES)

/** Reading the chat messages of /app into Kotlin types. Anything malformed is dropped, never thrown. */
object ChatIn {
    /** chat.status, with the default agent's state, models and modes in the fields today's screens read. */
    fun status(m: JsonObject): ChatStatus {
        val settings = AgentsIn.settings(m.obj("settings"))
        return ChatStatus(
            agents = AgentsIn.agents(m),
            settings = settings,
            freeOnly = m.b("free_only") ?: false,
            turn = m.obj("turn")?.let { t ->
                val conversation = t.s("conversation") ?: return@let null
                RunningTurn(conversation, t.i("turn") ?: 0, Mode.of(t.s("mode")), t.s("model"), Effort.of(t.s("effort")),
                    t.d("started_at") ?: 0.0, t.b("waiting") ?: false, t.b("remote") ?: false, t.s("via"),
                    t.b("remote_waiting") ?: false, AgentsIn.id(t.s("agent")))
            },
            pending = m.objects("pending").mapNotNull { event(it) as? ChatEvent.Permission },
            remote = m.obj("remote")?.let { r ->
                val conversation = r.s("conversation") ?: return@let null
                RemoteInfo(conversation, r.s("state") != "on", r.b("connected") ?: false, AgentsIn.id(r.s("agent")))
            },
        ).forAgent(settings.defaultAgent)
    }

    /** An agent's name and version (from its entry), or null when it has no usable name (the texts then say "the agent"). */
    fun agent(o: JsonObject?): AgentInfo? {
        val name = o?.s("name", 40)?.takeIf { it.isNotBlank() } ?: return null
        return AgentInfo(name, o.s("version", 40)?.takeIf { it.isNotBlank() })
    }

    /** An agent's "remote": its remote access, or null when it has none. */
    fun capability(o: JsonObject?): RemoteCapability? {
        val name = o?.text("name")?.take(40)?.takeIf { it.isNotBlank() } ?: return null
        return RemoteCapability(name, o.text("keeper")?.take(40)?.takeIf { it.isNotBlank() }, o.s("answers"),
            o.b("attachments") ?: false)
    }

    fun conversation(o: JsonObject): Conversation? {
        val id = o.s("id")
        // "model" is required: a conversation without it, or with a non-string, is dropped; the server's index keeps "" for none
        val model = (o["model"] as? JsonPrimitive)?.takeIf { it.isString }?.let { o.s("model") ?: "" }
        if (id == null || model == null) return null
        return Conversation(
            id = id,
            created = o.d("created") ?: 0.0,
            updated = o.d("updated") ?: 0.0,
            title = o.text("title")?.take(120) ?: "",
            last = o.text("last")?.take(140) ?: "",
            pinned = o.b("pinned") ?: false,
            favorite = o.b("favorite") ?: false,
            flipper = o.b("flipper") ?: false,
            mode = Mode.of(o.s("mode")) ?: Mode.MANUAL,
            model = model,
            effort = Effort.of(o.s("effort")),
            state = when (o.s("state")) { "ready" -> ConvState.READY; "expired" -> ConvState.EXPIRED; else -> ConvState.NEW },
            turns = o.i("turns") ?: 0,
            events = o.i("events") ?: 0,
            attachments = o.i("attachments") ?: 0,
            remote = o.b("remote") ?: false,
            agent = AgentsIn.id(o.s("agent")),
        )
    }

    fun attachment(o: JsonObject): Attachment? {
        val id = o.s("id") ?: return null
        return Attachment(id, o.text("name")?.take(120) ?: "file",
            if (o.s("kind") == "image") AttachmentKind.IMAGE else AttachmentKind.FILE,
            o.s("media_type") ?: "application/octet-stream", o.l("bytes") ?: 0, o.i("width"), o.i("height"))
    }

    /** The most rules a chat.permission.answer carries (docs/protocol/app/app/chat.permission.answer.json). */
    const val MAX_RULES = 50

    /** One history event; null only when it has no number. */
    fun event(o: JsonObject): ChatEvent? {
        val n = o.i("n") ?: return null
        val at = o.d("at") ?: 0.0
        return when (val kind = o.s("kind") ?: "") {
            Kind.USER -> ChatEvent.User(n, at, o.text("text") ?: "", o.objects("attachments").mapNotNull(::attachment), o.s("via"))
            Kind.TURN_START -> ChatEvent.TurnStart(n, at, o.i("turn") ?: 0, Mode.of(o.s("mode")), o.s("model"), Effort.of(o.s("effort")))
            Kind.TURN_INFO -> ChatEvent.TurnInfo(n, at, o.s("mode"), o.s("model"), Effort.of(o.s("effort")), o.s("version"), o.s("flipper_mcp"))
            Kind.TEXT -> ChatEvent.Text(n, at, o.s("id") ?: "t$n", o.text("text") ?: "")
            Kind.TOOL -> ChatEvent.Tool(n, at, o.s("id") ?: "", o.s("name", 120) ?: "?", o["input"], o.s("via"))
            Kind.TOOL_RESULT -> ChatEvent.ToolResult(n, at, o.s("id") ?: "", o.b("error") ?: false, o.text("text"), o.l("ms"),
                o.i("images") ?: 0, o.obj("screen")?.let(::frame))
            Kind.PERMISSION -> ChatEvent.Permission(n, at, o.s("id") ?: "", o.s("tool", 120) ?: "?", o["input"], o.d("deadline") ?: 0.0,
                o.b("always") ?: false, rules(o), whole = o.b("whole") ?: true)
            Kind.PERMISSION_END -> ChatEvent.PermissionEnd(n, at, o.s("id") ?: "", o.s("answer") ?: "")
            Kind.DENIED -> ChatEvent.Denied(n, at, o.s("tool", 120) ?: "?", o["input"], o.s("by"), o.text("reason")?.take(600))
            Kind.NOTICE -> ChatEvent.Notice(n, at, o.s("code") ?: "", o.obj("data"))
            Kind.TURN_END -> ChatEvent.TurnEnd(n, at, o.i("turn") ?: 0, o.s("outcome") ?: "done", o.s("error"), o.obj("data"), o.l("duration_ms"))
            else -> ChatEvent.Unknown(n, at, kind)
        }
    }

    fun history(m: JsonObject): Triple<String, List<ChatEvent>, Boolean>? {
        val conversation = m.s("conversation") ?: return null
        return Triple(conversation, m.objects("events").mapNotNull(::event), m.b("more") ?: false)
    }

    fun delta(m: JsonObject): Delta? {
        val conversation = m.s("conversation") ?: return null
        val id = m.s("id") ?: return null
        return Delta(conversation, m.i("turn") ?: 0, id, m.i("offset") ?: 0, m.text("text") ?: "")
    }

    fun error(m: JsonObject) = ChatError(m.s("error") ?: "unknown", m.obj("data"), m.s("request"))

    /** A Flipper screen returned by a tool: the same 1024-byte frame as the live one. */
    private fun frame(o: JsonObject): Frame? {
        val text = (o["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val data = runCatching { Base64.getDecoder().decode(text) }.getOrNull()?.takeIf { it.size == AppLink.FRAME_BYTES } ?: return null
        return Frame(o.l("seq") ?: 0, o.i("orientation") ?: 0, false, data)
    }
}
