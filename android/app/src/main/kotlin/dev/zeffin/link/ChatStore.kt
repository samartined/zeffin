// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

import dev.zeffin.link.AppProtocol.Feature
import dev.zeffin.link.AppProtocol.In
import dev.zeffin.link.AppProtocol.Out
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/** The conversation shown on Home: its events in order, and whether older ones exist on the server. */
data class OpenConversation(
    val id: String,
    val events: List<ChatEvent> = emptyList(),
    val more: Boolean = false,
    val loading: Boolean = true,
) {
    val lastN get() = events.lastOrNull()?.n ?: 0
}

data class ChatState(
    /** null until the server's hello; false: the server has no chat (it needs zf update). */
    val supported: Boolean? = null,
    val attachments: Boolean = false,
    /** The server deletes conversations for good (server 0.6.0). */
    val deletion: Boolean = false,
    /** The shown agent's remote access (Remote Control), when the server offers it and the agent has it. */
    val remote: RemoteCapability? = null,
    /** Who the shown agent is: the open conversation's, or the default one; null when the server names none. */
    val agent: AgentInfo? = null,
    /** Every agent the server knows, as hello listed them (chat.status's [ChatStatus.agents] are newer). */
    val agents: List<AgentEntry> = emptyList(),
    /** hello's free_only (chat.status's [ChatStatus.freeOnly] is newer). */
    val helloFreeOnly: Boolean = false,
    /** The server user's home folder (hello's server.home): paths and rules show it as "~"; null from older servers. */
    val home: String? = null,
    val connected: Boolean = false,
    val status: ChatStatus? = null,
    val conversations: List<Conversation> = emptyList(),
    val open: OpenConversation? = null,
    val error: ChatError? = null,
    /** The last deletion of several conversations from the list, while it runs and after; null when there was none. */
    val deletions: Deletions? = null,
) {
    /** Every agent the server knows: chat.status's, newer than hello's. */
    val agentList: List<AgentEntry> get() = status?.agents?.takeIf { it.isNotEmpty() } ?: agents
    /** The owner's "free only" switch: chat.status's, newer than hello's; off from a server that sends neither. */
    val freeOnly: Boolean get() = status?.freeOnly ?: helloFreeOnly
    /** The agent's name, as the texts that name it say it; null when the server does not send it (they say "the agent"). */
    val agentName: String? get() = agent?.name

    /** The agent's version (hello); null from an older server or while the server checks it. */
    val agentVersion: String? get() = agent?.version

    val current: Conversation? get() = open?.let { o -> conversations.firstOrNull { it.id == o.id } }

    /** Claude is working in the open conversation. */
    val busyHere: Boolean get() = open != null && status?.turn?.conversation == open.id

    /** Claude is working in another conversation: a message here would get "busy". */
    val busyElsewhere: Boolean get() = status?.turn != null && status.turn.conversation != open?.id

    /** Remote Control is on (or starting) in the open conversation. */
    val remoteHere: Boolean get() = remote != null && open != null && status?.remote?.conversation == open.id

    /** Remote Control is on here and the server takes no attachments with it (an older server): no file can go. */
    val remoteNoFiles: Boolean get() = remoteHere && remote?.attachments != true
}

/**
 * Several conversations deleted at once from the list: one chat.delete each, answered in order with chat.deleted or an
 * error. [waiting] have no answer yet (a running turn stops first), [failed] got an error, by its code.
 */
data class Deletions(val asked: List<String>, val waiting: Set<String>, val failed: Map<String, ChatError> = emptyMap()) {
    val deleted get() = asked.count { it !in waiting && it !in failed }
    val done get() = waiting.isEmpty()
}

/**
 * The chat over /app (docs/protocol/app/): subscribes after hello, keeps the conversation list and
 * the open conversation, merges text being written, and catches up after a reconnection. Everything runs on one
 * thread, the main one: [AppLink]'s events are collected there, and the UI's commands come from it, so nothing here
 * locks. Nothing here touches Android, so it is unit tested.
 */
class ChatStore(
    private val send: (JsonObject) -> Boolean,
    /** Saves the open conversation's id, so the app reopens it next time. */
    private val remember: (String?) -> Unit = {},
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** Text being written in the open conversation, by block id. Kept apart: it changes up to 10 times a second. */
    private val _streaming = MutableStateFlow<Map<String, String>>(emptyMap())
    val streaming: StateFlow<Map<String, String>> = _streaming.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private enum class Page { LATEST, BEFORE, AFTER }

    private val pages = ArrayDeque<Pair<String, Page>>()  // chat.open requests, answered in order
    private var subscribed = false
    private var wanted: String? = null                     // the conversation to open once the list is known
    private var newRequested = false                       // a chat.new is on its way
    private var waitingText: Pair<String, List<String>>? = null  // a message waiting for its new conversation
    private var afterNew: ((String) -> Unit)? = null

    /** The saved conversation, read at start. */
    fun restore(id: String?) {
        if (_state.value.open == null) wanted = id
    }

    private var remoteFeature = false  // hello's features name "remote"

    /** Every change goes through here: the agent the screens show follows the open conversation. */
    private fun set(change: (ChatState) -> ChatState) = _state.update { focus(change(it)) }

    /**
     * The shown agent, in the fields today's screens read ([ChatState.agent], [ChatState.remote], and the status's
     * state, models and modes): the open conversation's agent, or the default one when none is open or it names none.
     * Unchanged when the server lists no agent.
     */
    private fun focus(s: ChatState): ChatState {
        val agents = s.status?.agents?.takeIf { it.isNotEmpty() } ?: s.agents
        val default = s.status?.settings?.defaultAgent
        val entry = agents.firstOrNull { it.id == s.current?.agent } ?: agents.firstOrNull { it.id == default }
            ?: agents.firstOrNull() ?: return s
        return s.copy(agent = entry.info, remote = entry.remote.takeIf { remoteFeature }, status = s.status?.forAgent(entry.id))
    }

    // ------------------------------------------------------------------ from AppLink
    /** After each connection: what the server offers, and hello's [hello] (its agents; older servers send none). */
    fun onHello(features: Set<String>, hello: JsonObject? = null) {
        val supported = Feature.CHAT in features
        remoteFeature = Feature.REMOTE in features
        set { it.copy(supported = supported, attachments = Feature.ATTACHMENTS in features, deletion = Feature.DELETE in features,
            agents = hello?.let(AgentsIn::agents).orEmpty(), helloFreeOnly = hello?.b("free_only") ?: false,
            home = (hello?.get("server") as? JsonObject)?.s("home", HOME_MAX),
            agent = null, remote = null, connected = true) }
        _streaming.value = emptyMap()  // what was lost comes back inside the complete text events
        pages.clear()
        subscribed = false
        if (!supported) return
        subscribed = send(ChatOut.list())
        _state.value.open?.let { o -> request(o.id, if (o.events.isEmpty()) Page.LATEST else Page.AFTER, o.lastN) }
    }

    fun onDisconnected() {
        subscribed = false
        pages.clear()
        set { it.copy(connected = false) }
    }

    fun onMessage(m: JsonObject) {
        when (m.s("type")) {
            Out.CHAT_STATUS -> {
                val status = ChatIn.status(m)
                set { it.copy(status = status) }
                if (status.turn == null) _streaming.value = emptyMap()
            }
            Out.CHAT_CONVERSATIONS -> {
                val list = m.objects("items").mapNotNull(ChatIn::conversation).sortedByDescending { it.updated }
                set { it.copy(conversations = list) }
                if (_state.value.open == null) chooseFirst(list)
            }
            Out.CHAT_CONVERSATION -> m.obj("item")?.let(ChatIn::conversation)?.let(::upsert)
            Out.CHAT_DELETED -> m.s("conversation")?.let(::deleted)
            Out.CHAT_HISTORY -> ChatIn.history(m)?.let { (id, events, more) -> history(id, events, more) }
            Out.CHAT_EVENT -> {
                val id = m.s("conversation") ?: return
                val event = ChatIn.event(m) ?: return
                live(id, event)
            }
            Out.CHAT_DELTA -> ChatIn.delta(m)?.let(::delta)
            Out.ERROR -> {
                val error = ChatIn.error(m)
                if (error.request?.startsWith(AppProtocol.CHAT_PREFIX) != true) return  // not the chat's: no banner
                if (error.request == In.CHAT_OPEN) pages.removeFirstOrNull()
                if (error.request == In.CHAT_NEW) newRequested = false
                if (error.request == In.CHAT_DELETE && deleteFailed(error)) return  // the list says it, not a chat banner
                set { it.copy(error = error) }
            }
        }
    }

    // ------------------------------------------------------------------ commands from the UI
    fun open(id: String) {
        if (_state.value.open?.id == id) return
        _streaming.value = emptyMap()
        set { it.copy(open = OpenConversation(id)) }
        remember(id)
        if (subscribed) request(id, Page.LATEST, 0)
    }

    /** Starts a new conversation with the default agent (the last one chosen) and opens it; [then] runs with its id. */
    fun newConversation(then: (String) -> Unit = {}) {
        afterNew = then
        if (!newRequested) newRequested = send(ChatOut.new())
    }

    /**
     * Another agent for the conversation being started: [agent] becomes the default one (the last chosen), and a new
     * conversation with it replaces the open one while nothing was written in it, which is deleted once the new one is
     * open. A conversation already written in keeps its agent: nothing changes.
     */
    fun chooseAgent(agent: String): Boolean {
        val s = _state.value
        val current = s.current
        val open = s.open
        return when {
            current != null && current.turns > 0 -> false
            !send(ChatOut.defaultAgent(agent)) -> false
            open == null || current?.agent == agent || newRequested -> true
            else -> {
                afterNew = { if (_state.value.deletion) send(ChatOut.delete(open.id)) }
                newRequested = send(ChatOut.new(agent = agent))
                newRequested
            }
        }
    }

    /** The agent of new conversations (Settings, Agent): it becomes the default one. */
    fun defaultAgent(agent: String) = send(ChatOut.defaultAgent(agent))

    /** Deletes every one of [ids] for good, one request each; [ChatState.deletions] follows the answers. */
    fun deleteAll(ids: List<String>): Boolean {
        val asked = ids.distinct().filter { id -> _state.value.conversations.any { it.id == id } }
        val sent = asked.filter { send(ChatOut.delete(it)) }
        set { it.copy(deletions = Deletions(asked, sent.toSet(), asked.filter { id -> id !in sent }
            .associateWith { ChatError(AppProtocol.Err.NOT_CONNECTED, null, In.CHAT_DELETE) })) }
        return sent.isNotEmpty()
    }

    /** The list shows the deletion's outcome no longer. */
    fun dismissDeletions() = set { it.copy(deletions = null) }

    /** An error for a deletion of several: the first one still waiting failed (the server answers in order). */
    private fun deleteFailed(error: ChatError): Boolean {
        val d = _state.value.deletions
        val named = (error.data?.get("conversation") as? kotlinx.serialization.json.JsonPrimitive)?.content
        val id = d?.let { named?.takeIf { it in d.waiting } ?: d.asked.firstOrNull { it in d.waiting } }
        if (d != null && id != null) set { it.copy(deletions = d.copy(waiting = d.waiting - id, failed = d.failed + (id to error))) }
        return id != null
    }

    /** Sends a message in the open conversation, creating one first if there is none. */
    fun send(text: String, attachments: List<String> = emptyList()): Boolean {
        if (text.isBlank() && attachments.isEmpty()) return false
        val open = _state.value.open
        return if (open != null) send(ChatOut.send(open.id, text, attachments)).also { if (it) _draft.value = "" } else {
            waitingText = text to attachments
            if (!newRequested) newRequested = send(ChatOut.new())
            newRequested
        }
    }

    /** Stops the turn in [id], or in the open conversation. */
    fun interrupt(id: String? = null) = (id ?: _state.value.open?.id)?.let { send(ChatOut.interrupt(it)) } ?: false

    fun set(mode: Mode? = null, model: String? = null, effort: Effort? = null) =
        _state.value.open?.let { send(ChatOut.set(it.id, mode, model, effort)) } ?: false

    /** Always sends the rules of [id] as the cards show them, every one (PresentChat): the server saves no others. */
    fun answer(id: String, answer: PermissionAnswer) = send(ChatOut.answer(id, answer,
        if (answer == PermissionAnswer.ALWAYS) permission(id)?.rules.orEmpty() else emptyList()))

    private fun permission(id: String): ChatEvent.Permission? = _state.value.let { s ->
        s.status?.pending?.firstOrNull { it.id == id } ?: s.open?.events?.filterIsInstance<ChatEvent.Permission>()?.lastOrNull { it.id == id }
    }

    fun update(id: String, title: String? = null, pinned: Boolean? = null, favorite: Boolean? = null) =
        send(ChatOut.update(id, title, pinned, favorite))

    /** Remote Control on or off in [id]; the server answers with chat.status (its "remote"). */
    fun remote(id: String, on: Boolean) = send(ChatOut.remote(id, on))

    /** Deletes [id] for good; the server answers chat.deleted to every app (a running turn in it stops first). */
    fun delete(id: String) = send(ChatOut.delete(id))

    /** New conversations' defaults: [mode], [model] and [effort] are [agent]'s (with it null, the default agent's). */
    fun settings(mode: Mode? = null, model: String? = null, effort: Effort? = null, permissionTimeoutS: Int? = null,
                 agent: String? = null) = send(ChatOut.settings(mode, model, effort, permissionTimeoutS, agent))

    /** Automatic mode blocked something: switch to Manual and send the turn's message again. */
    fun retryInManual(): Boolean {
        val open = _state.value.open
        val last = open?.events?.lastOrNull { it is ChatEvent.User } as? ChatEvent.User
        return open != null && last != null &&
            send(ChatOut.set(open.id, mode = Mode.MANUAL)) && send(ChatOut.send(open.id, last.text, last.attachments.map { it.id }))
    }

    /** The UI reached the oldest loaded event. */
    fun loadOlder() {
        val o = _state.value.open ?: return
        if (!o.more || o.loading || pages.any { it.first == o.id && it.second == Page.BEFORE }) return
        set { it.copy(open = o.copy(loading = true)) }
        request(o.id, Page.BEFORE, o.events.firstOrNull()?.n ?: 0)
    }

    fun setDraft(text: String) {
        _draft.value = text
    }

    fun dismissError() = set { it.copy(error = null) }

    // ------------------------------------------------------------------ inside
    private fun request(id: String, page: Page, n: Int) {
        val message = when (page) {
            Page.LATEST -> ChatOut.open(id)
            Page.BEFORE -> ChatOut.open(id, before = n)
            Page.AFTER -> ChatOut.open(id, after = n)
        }
        if (send(message)) pages.addLast(id to page)
    }

    private fun chooseFirst(list: List<Conversation>) {
        val pick = wanted?.takeIf { w -> list.any { it.id == w } }
            ?: _state.value.status?.turn?.conversation?.takeIf { t -> list.any { it.id == t } }
            ?: list.firstOrNull { it.turns > 0 }?.id
        wanted = null
        if (pick != null) {
            set { it.copy(open = OpenConversation(pick)) }
            remember(pick)
            request(pick, Page.LATEST, 0)
        }
    }

    private fun upsert(c: Conversation) {
        val known = _state.value.conversations.any { it.id == c.id }
        set { s -> s.copy(conversations = (s.conversations.filter { it.id != c.id } + c).sortedByDescending { it.updated }) }
        if (!known && newRequested && c.turns == 0) {
            newRequested = false
            _streaming.value = emptyMap()
            set { it.copy(open = OpenConversation(c.id, loading = false)) }
            remember(c.id)
            waitingText?.let { (text, attachments) ->
                if (send(ChatOut.send(c.id, text, attachments))) _draft.value = ""
            }
            waitingText = null
            afterNew?.invoke(c.id)
            afterNew = null
        }
    }

    /** Gone for good, from this app or another one: out of the list, and closed if it was open. */
    private fun deleted(id: String) {
        val wasOpen = _state.value.open?.id == id
        set { s -> s.copy(conversations = s.conversations.filter { it.id != id }, open = s.open?.takeIf { !wasOpen },
            deletions = s.deletions?.let { d -> d.copy(waiting = d.waiting - id) }) }
        if (wanted == id) wanted = null
        if (wasOpen) {
            _streaming.value = emptyMap()
            remember(null)
        }
    }

    private fun history(id: String, events: List<ChatEvent>, more: Boolean) {
        val page = pages.removeFirstOrNull()?.takeIf { it.first == id }?.second ?: Page.LATEST
        val o = _state.value.open?.takeIf { it.id == id } ?: return
        val merged = (o.events + events).associateBy { it.n }.values.sortedBy { it.n }
        val olderExist = when (page) {
            Page.AFTER -> o.more
            else -> more
        }
        val capped = if (merged.size > MAX_EVENTS) merged.takeLast(MAX_EVENTS) else merged
        set { it.copy(open = o.copy(events = capped, more = olderExist || capped.size < merged.size, loading = false)) }
        if (page == Page.AFTER && more) request(id, Page.AFTER, capped.lastOrNull()?.n ?: 0)
    }

    private fun live(id: String, event: ChatEvent) {
        val o = _state.value.open?.takeIf { it.id == id } ?: return
        if (o.events.any { it.n == event.n }) return
        val gap = o.events.isNotEmpty() && event.n > o.lastN + 1 && !o.loading
        val events = (o.events + event).sortedBy { it.n }.let { if (it.size > MAX_EVENTS) it.takeLast(MAX_EVENTS) else it }
        set { it.copy(open = o.copy(events = events, more = o.more || events.size < o.events.size + 1)) }
        when (event) {
            is ChatEvent.Text -> _streaming.update { it - event.id }
            is ChatEvent.TurnEnd -> _streaming.value = emptyMap()
            else -> {}
        }
        if (gap) request(id, Page.AFTER, o.lastN)
    }

    private fun delta(d: Delta) {
        val o = _state.value.open?.takeIf { it.id == d.conversation } ?: return
        if (o.events.any { it is ChatEvent.Text && it.id == d.id }) return  // the complete block is already here
        _streaming.update { map ->
            val now = map[d.id] ?: ""
            val next = when {
                d.offset >= now.length -> now + d.text  // in order (or after a dropped delta: keep going)
                d.offset + d.text.length > now.length -> now + d.text.substring(now.length - d.offset)
                else -> now
            }
            map + (d.id to next)
        }
    }

    companion object {
        const val MAX_EVENTS = 2000
        /** The longest home folder kept (PATH_MAX on Linux). */
        const val HOME_MAX = 4096
    }
}
