// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A permission mode, by its wire name. The agent offers its own (chat.status "mode_options"); the four the app
 * knows are named here and worded by the app. Any other is kept as it comes.
 */
@JvmInline
value class Mode(val wire: String) {
    companion object {
        val MANUAL = Mode("default")
        val ACCEPT_EDITS = Mode("acceptEdits")
        val PLAN = Mode("plan")
        val AUTO = Mode("auto")

        /** The modes the app has its own words and hints for, when the agent lists them. */
        val KNOWN = listOf(MANUAL, ACCEPT_EDITS, PLAN, AUTO)

        private val WIRE = Regex("[A-Za-z][A-Za-z0-9_.-]{0,39}")

        /** Null when [value] is not a mode's wire name (missing, blank, too long or with other characters). */
        fun of(value: String?): Mode? = value?.takeIf { WIRE.matches(it) }?.let(::Mode)
    }
}

/** A mode the agent offers: its wire name and its own English name. */
data class ModeOption(val mode: Mode, val label: String)

/** Who the agent is (hello "agent"): its name, and its version once the server has checked it. */
data class AgentInfo(val name: String, val version: String?)

/** Effort levels, lowest first. A conversation always has one (server 0.5.0), unless its model has none. */
enum class Effort(val wire: String) {
    LOW("low"), MEDIUM("medium"), HIGH("high"), XHIGH("xhigh"), MAX("max");

    companion object {
        fun of(value: String?): Effort? = entries.firstOrNull { it.wire == value }
    }
}

enum class ConvState { NEW, READY, EXPIRED }

enum class AttachmentKind { IMAGE, FILE }

enum class PermissionAnswer(val wire: String) {
    ONCE(AppProtocol.Answer.ONCE), ALWAYS(AppProtocol.Answer.ALWAYS), DENY(AppProtocol.Answer.DENY)
}

/**
 * A model an agent lists (its entry's "models"), in its order: [label] is the server's name for it. [available] when the
 * server takes it (with free only on: the owner's subscription covers it, or it is free on a provider the agent is
 * signed in to; off: any model on a login or provider the agent has); otherwise [reason] says why not
 * ([AppProtocol.ModelReason], or another the app words plainly), and it cannot be chosen. [images]: it takes images
 * (an image attachment, the Flipper's screen in a tool result). [paid]: it may bill per use (its row says so).
 */
data class ModelInfo(val value: String, val label: String, val efforts: List<Effort>, val auto: Boolean,
                     val available: Boolean = true, val reason: String? = null, val images: Boolean = true,
                     val paid: Boolean = false)

/** Where an agent's new conversations start (chat.status's settings.agents). [model] is null when the server names none. */
data class AgentDefaults(val mode: Mode = Mode.MANUAL, val model: String? = null, val effort: Effort = Effort.MEDIUM)

/**
 * The chat's settings. [defaultMode], [defaultModel] and [defaultEffort] are the default agent's ([defaultAgent]), what
 * today's screens show; [agents] has every agent's, by id.
 */
data class ChatSettings(
    val defaultMode: Mode = Mode.MANUAL,
    /** Null when the server names none. */
    val defaultModel: String? = null,
    val defaultEffort: Effort = Effort.MEDIUM,
    val permissionTimeoutS: Int = 120,
    /** The agent of a new conversation when the app names none; null from a server that names none. */
    val defaultAgent: String? = null,
    val agents: Map<String, AgentDefaults> = emptyMap(),
)

/**
 * An agent the server knows (hello's and chat.status's "agents"), with what it offers now: who it is, whether it can
 * take a turn ([problem] when not), its login, models and modes, its remote access ([remote], null when it has none)
 * and whether messages to it may carry attachments.
 */
data class AgentEntry(
    val id: String,
    val info: AgentInfo,
    val configured: Boolean,
    val ready: Boolean,
    val problem: String?,
    val login: Login?,
    val models: List<ModelInfo>,
    val modeOptions: List<ModeOption>,
    val remote: RemoteCapability?,
    val attachments: Boolean,
)

/** The turn running on the server, in any conversation (one at a time on the whole server). With Remote Control,
 *  [remote] is set; [via] is "remote" when another device started it, and [remoteWaiting] says a permission waits for
 *  its answer there ([waiting] stays false: it is never answered from here). */
data class RunningTurn(
    val conversation: String, val turn: Int, val mode: Mode?, val model: String?, val effort: Effort?,
    val startedAt: Double, val waiting: Boolean,
    val remote: Boolean = false, val via: String? = null, val remoteWaiting: Boolean = false,
    /** The conversation's agent; null from a server that names none. */
    val agent: String? = null,
)

/**
 * The agent's remote access, as the server names it (hello): names and codes only, the app words them.
 * [attachments]: messages sent with it on may carry attachments (false from an older server, which lacks it).
 */
data class RemoteCapability(val name: String, val keeper: String?, val answers: String?, val attachments: Boolean = false)

/** The conversation in Remote Control (one at a time): starting until its process is up. */
data class RemoteInfo(val conversation: String, val starting: Boolean, val connected: Boolean, val agent: String? = null)

/**
 * How the agent is signed in on the server (chat.status's login): [method] in its own words, shown as is, null when it is
 * not signed in or not checked yet; [subscription] when that login is a subscription.
 */
data class Login(val method: String?, val subscription: Boolean)

/**
 * The chat's state (chat.status). [agents] lists every agent the server knows; [ready], [problem], [login], [models] and
 * [modeOptions] are one agent's, the one today's screens show: the open conversation's, or the default agent
 * ([forAgent]).
 */
data class ChatStatus(
    val ready: Boolean = false,
    val problem: String? = null,
    /** Null when the agent's entry lacks it. */
    val login: Login? = null,
    val models: List<ModelInfo> = emptyList(),
    /** The modes the agent offers, in its order. */
    val modeOptions: List<ModeOption> = emptyList(),
    val settings: ChatSettings = ChatSettings(),
    val turn: RunningTurn? = null,
    val pending: List<ChatEvent.Permission> = emptyList(),
    val remote: RemoteInfo? = null,
    val agents: List<AgentEntry> = emptyList(),
    /** The owner's "free only" switch (chat.status's free_only): the texts promise "free" only while it is on. */
    val freeOnly: Boolean = false,
) {
    fun model(value: String?): ModelInfo? = models.firstOrNull { it.value == value }

    fun modeOption(mode: Mode?): ModeOption? = modeOptions.firstOrNull { it.mode == mode }

    /** An agent by its id, or null. */
    fun agent(id: String?): AgentEntry? = agents.firstOrNull { it.id == id }

    /** The agent [id] (or the default one, or the first, when the server lists no [id]): this status with its state,
     *  models and modes. Unchanged when the server lists no agent. */
    fun forAgent(id: String?): ChatStatus {
        val entry = agent(id) ?: agent(settings.defaultAgent) ?: agents.firstOrNull() ?: return this
        return copy(ready = entry.ready, problem = entry.problem, login = entry.login, models = entry.models,
            modeOptions = entry.modeOptions)
    }
}

data class Conversation(
    val id: String,
    val created: Double,
    val updated: Double,
    val title: String,
    val last: String,
    val pinned: Boolean,
    val favorite: Boolean,
    val flipper: Boolean,
    val mode: Mode,
    /** Empty when the server's index has none. */
    val model: String,
    val effort: Effort?,
    val state: ConvState,
    val turns: Int,
    val events: Int,
    /** Files sent in its messages (server 0.6.0). */
    val attachments: Int = 0,
    /** Remote Control is on in it. */
    val remote: Boolean = false,
    /** Its agent ([AppProtocol.AgentId], or another a newer server knows); null from a server that names none. */
    val agent: String? = null,
)

data class Attachment(
    val id: String, val name: String, val kind: AttachmentKind, val mediaType: String, val bytes: Long,
    val width: Int?, val height: Int?,
)

/** One numbered event of a conversation's history (docs/protocol/app/). Unknown kinds are kept as
 *  [Unknown] so a newer server never breaks the chat. */
sealed class ChatEvent {
    abstract val n: Int
    abstract val at: Double

    /** [via] "remote": written on another device, with Remote Control on. */
    data class User(override val n: Int, override val at: Double, val text: String, val attachments: List<Attachment>,
                    val via: String? = null) : ChatEvent()
    data class TurnStart(override val n: Int, override val at: Double, val turn: Int, val mode: Mode?, val model: String?, val effort: Effort?) : ChatEvent()
    data class TurnInfo(override val n: Int, override val at: Double, val mode: String?, val model: String?, val effort: Effort?,
                        val version: String?, val flipperMcp: String?) : ChatEvent()
    data class Text(override val n: Int, override val at: Double, val id: String, val text: String) : ChatEvent()
    data class Tool(override val n: Int, override val at: Double, val id: String, val name: String, val input: JsonElement?,
                    val via: String? = null) : ChatEvent()
    data class ToolResult(override val n: Int, override val at: Double, val id: String, val error: Boolean, val text: String?,
                          val ms: Long?, val images: Int, val screen: Frame?) : ChatEvent()
    data class Permission(override val n: Int, override val at: Double, val id: String, val tool: String, val input: JsonElement?,
                          val deadline: Double, val always: Boolean, val rules: List<String>,
                          /** False: the input was too long to show whole (cut by the server); it can only be denied. */
                          val whole: Boolean = true) : ChatEvent()
    data class PermissionEnd(override val n: Int, override val at: Double, val id: String, val answer: String) : ChatEvent()
    data class Denied(override val n: Int, override val at: Double, val tool: String, val input: JsonElement?, val by: String?,
                      val reason: String?) : ChatEvent()
    data class Notice(override val n: Int, override val at: Double, val code: String, val data: JsonObject?) : ChatEvent()
    data class TurnEnd(override val n: Int, override val at: Double, val turn: Int, val outcome: String, val error: String?,
                       val data: JsonObject?, val durationMs: Long?) : ChatEvent()
    data class Unknown(override val n: Int, override val at: Double, val kind: String) : ChatEvent()
}

/** Text being written: droppable, replaced by the [ChatEvent.Text] with the same [id]. */
data class Delta(val conversation: String, val turn: Int, val id: String, val offset: Int, val text: String)

/** An error the server sent: a code and its data; the app words it. */
data class ChatError(val code: String, val data: JsonObject?, val request: String?)
