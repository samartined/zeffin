// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.notify

import dev.zeffin.link.ChatEvent
import dev.zeffin.link.ChatIn
import dev.zeffin.link.PermissionAnswer
import dev.zeffin.link.d
import dev.zeffin.link.objects
import dev.zeffin.link.obj
import dev.zeffin.link.s
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/** The owner's switches in Settings, Agent. */
data class NotifyPrefs(val turns: Boolean = true, val permissions: Boolean = true) {
    val any get() = turns || permissions
}

/** A turn runs (or a permission waits) while the app is off screen: the ongoing notification and the link stay. */
data class Work(val conversation: String, val startedAt: Double)

/** A turn that ended while the app was off screen. [text] is the agent's last text (raw Markdown), only when done. */
data class TurnNote(val conversation: String, val outcome: String, val error: String?, val text: String?)

/** A permission waiting for an answer, in [conversation]. */
data class AskNote(val conversation: String, val permission: ChatEvent.Permission)

/** Why an answer from a notification did not go through. */
enum class NotSent { GONE, OFFLINE }

/** What the notifications do; [AndroidNotifications] on the phone, a recorder in the tests. */
interface NotifySink {
    fun turnEnded(note: TurnNote)
    fun ask(note: AskNote)
    fun sending(id: String, answer: PermissionAnswer)
    fun notSent(id: String, why: NotSent)
    /** The server stayed unreachable while the agent worked in [conversation]: say so once. */
    fun lost(conversation: String)
    fun cancelAsk(id: String)
    /** The app came on screen: it shows everything itself. */
    fun clearAll()
}

/**
 * Decides what to notify from the /app chat messages (docs/protocol/app/): a turn's end and a pending
 * permission, only while the app is off screen and the owner's switch is on. Events the server mirrors from
 * Remote Control ("via": "remote") are ignored: the Claude app sends its own push for those turns.
 * A dropped link changes nothing here: what runs is kept until the reconnection's chat.status corrects it.
 * Messages come on the socket thread, the rest from the main thread, so every entry point holds [lock].
 */
class Notifier(
    private val sink: NotifySink,
    /** Sends an answer to the server; false when the link is down. */
    private val answer: (String, PermissionAnswer) -> Boolean,
    private val connected: () -> Boolean,
) {
    private val lock = Any()
    private var visible = true
    private var prefs = NotifyPrefs()
    private var running: Work? = null
    private val pending = LinkedHashMap<String, AskNote>()
    private val asked = HashSet<String>()
    private val answering = ArrayDeque<String>()
    private val lastText = HashMap<String, String>()

    private val _work = MutableStateFlow<Work?>(null)
    /** Non-null while the app is off screen and something runs that the owner wants to hear about. */
    val work: StateFlow<Work?> = _work.asStateFlow()

    fun setVisible(on: Boolean) = synchronized(lock) {
        visible = on
        if (on) {
            asked.clear()
            answering.clear()
            sink.clearAll()
        } else {
            pending.values.forEach(::maybeAsk)  // already waiting when the app left the screen
        }
        update()
    }

    fun setPrefs(p: NotifyPrefs) = synchronized(lock) {
        prefs = p
        if (!p.permissions) asked.toList().forEach { cancel(it) }
        update()
    }

    fun onMessage(m: JsonObject) = synchronized(lock) {
        when (m.s("type")) {
            "chat.status" -> status(m)
            "chat.event" -> if (!remote(m)) event(m.s("conversation") ?: return@synchronized, ChatIn.event(m) ?: return@synchronized)
            "error" -> if (m.s("request") == "chat.permission.answer") answering.removeFirstOrNull()?.let { sink.notSent(it, NotSent.GONE) }
        }
    }

    /**
     * The link has been down for [LOST_AFTER_MS] while off screen: stop waiting (no ongoing notification, no service),
     * say so once, and forget what ran. A reconnection's chat.status restores everything.
     */
    fun linkLost() = synchronized(lock) {
        val conversation = _work.value?.conversation ?: return@synchronized
        running = null
        pending.clear()
        asked.toList().forEach { cancel(it) }
        sink.lost(conversation)
        update()
    }

    /** Allow once or Deny from a notification's button. */
    fun answerFromNotification(id: String, a: PermissionAnswer) = synchronized(lock) {
        when {
            !connected() -> sink.notSent(id, NotSent.OFFLINE)
            id !in pending -> sink.notSent(id, NotSent.GONE)
            !answer(id, a) -> sink.notSent(id, NotSent.OFFLINE)
            else -> {
                answering.addLast(id)
                sink.sending(id, a)
            }
        }
    }

    private fun status(m: JsonObject) {
        val turn = m.obj("turn")?.takeIf { !remote(it) }
        running = turn?.s("conversation")?.let { Work(it, turn.d("started_at") ?: 0.0) }
        val now = m.objects("pending").filter { !remote(it) }.mapNotNull { ChatIn.event(it) as? ChatEvent.Permission }
        val ids = now.map { it.id }.toSet()
        pending.keys.filter { it !in ids }.forEach { pending.remove(it); cancel(it) }  // ended, even if permission.end was missed
        for (p in now) {
            if (p.id in pending) continue
            val note = AskNote(running?.conversation ?: continue, p)
            pending[p.id] = note
            maybeAsk(note)
        }
        update()
    }

    private fun event(conversation: String, e: ChatEvent) {
        when (e) {
            is ChatEvent.TurnStart -> lastText.remove(conversation)
            is ChatEvent.Text -> lastText[conversation] = e.text
            is ChatEvent.Permission -> if (e.id !in pending) AskNote(conversation, e).also { pending[e.id] = it; maybeAsk(it) }
            is ChatEvent.PermissionEnd -> {
                pending.remove(e.id)
                answering.remove(e.id)
                cancel(e.id)
            }
            is ChatEvent.TurnEnd -> {
                val text = lastText.remove(conversation)
                if (running?.conversation == conversation) running = null
                if (!visible && prefs.turns) sink.turnEnded(TurnNote(conversation, e.outcome, e.error, text.takeIf { e.outcome == "done" }))
            }
            else -> {}
        }
        update()
    }

    private fun maybeAsk(note: AskNote) {
        if (visible || !prefs.permissions || !asked.add(note.permission.id)) return
        sink.ask(note)
    }

    private fun cancel(id: String) {
        if (asked.remove(id)) sink.cancelAsk(id)
    }

    private fun update() {
        val w = running ?: pending.values.firstOrNull()?.let { Work(it.conversation, it.permission.at) }
        _work.value = w?.takeIf { !visible && prefs.any }
    }

    private fun remote(o: JsonObject) = o.s("via") == "remote"

    companion object {
        const val LOST_AFTER_MS = 15 * 60 * 1000L
    }
}
