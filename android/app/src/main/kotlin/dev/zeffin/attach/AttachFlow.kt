// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.attach

import dev.zeffin.link.ChatOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What a conversation does with its attachments, apart from Android: files go to the open conversation (one is
 * created first when there is none), a file that cannot be read or does not fit shows its error in the tray, the
 * tray keeps only the open conversation's files, and a sent message empties it. The screen hands it what Android
 * picked; reading a file stays with the screen.
 */
class AttachFlow(private val tray: AttachTray, private val scope: CoroutineScope, private val chat: Chat) {
    /** The chat, as the attachments see it. */
    interface Chat {
        /** The open conversation, or null when there is none yet. */
        fun openId(): String?
        /** Creates a conversation and calls [then] with its id once the server has it. */
        fun newConversation(then: (String) -> Unit)
        /** Sends the message with the uploaded files; false when it could not go. */
        fun send(text: String, attachments: List<String>): Boolean
    }

    /** Runs [then] with the open conversation, creating one first when there is none. */
    fun withConversation(then: (String) -> Unit) {
        val open = chat.openId()
        if (open != null) then(open) else chat.newConversation(then)
    }

    /** Files the owner picked: each one is read with [prepare]; one that fails is named with [nameOf] in the tray. */
    fun <T> attach(picked: List<T>, prepare: suspend (T) -> Result<Prepared>, nameOf: suspend (T) -> String) {
        if (picked.isEmpty()) return
        withConversation { id ->
            scope.launch {
                for (p in picked) {
                    prepare(p).fold(
                        onSuccess = { if (!tray.add(id, it)) tray.addError(it.name, AttachError.TooBig) },
                        onFailure = { e -> tray.addError(nameOf(p), if (e is ImageScaler.TooBig) AttachError.TooBig else AttachError.Unreadable) })
                }
            }
        }
    }

    /** The Flipper screen shown above, already made a file; null when there is no screen. */
    fun attachScreen(item: Prepared?) {
        item ?: return
        withConversation { id -> tray.add(id, item) }
    }

    /**
     * What the field holds after the owner changed [old] into [new]. A paste (more than one character added at once)
     * that would take the field over [ChatOut.MAX_TEXT] becomes a text file in the tray, as `pasted-text.txt`, and
     * the field keeps what it held. Typing, or a paste when no file can go ([canAttach] false: Remote Control on,
     * with a server that takes no attachments there), stays in the field, and the screen says the message is too long.
     */
    fun draftChanged(old: String, new: String, canAttach: Boolean): String {
        val pasted = if (new.length > ChatOut.MAX_TEXT && canAttach) added(old, new) else ""
        if (pasted.length <= 1) return new
        attachText(pasted)
        return old
    }

    /** What [new] adds to [old] in one place: a paste, or a typed character (over a selection, it replaces it). */
    private fun added(old: String, new: String): String {
        val start = old.commonPrefixWith(new).length
        val end = old.substring(start).commonSuffixWith(new.substring(start)).length
        return new.substring(start, new.length - end)
    }

    /** A long text as a file of its own: `pasted-text.txt`, then `-2`, `-3` in the same message. */
    private fun attachText(text: String) {
        val taken = tray.items.value.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) PASTED else "pasted-text-$it.txt" }.first { it !in taken }
        val item = Prepared(name, "text/plain", text.toByteArray(Charsets.UTF_8), null, null, chars = text.length)
        withConversation { id -> if (!tray.add(id, item)) tray.addError(name, AttachError.TooBig) }
    }

    /** Another conversation is open: files uploaded for the earlier one cannot go with its messages. */
    fun opened(conversation: String?) = tray.keepOnly(conversation)

    /** Sends [text] with the uploaded files; the tray empties when the message went. */
    fun send(text: String): Boolean {
        if (!chat.send(text, tray.ids)) return false
        tray.clear()
        return true
    }

    companion object {
        /** The name of a long paste made a file; it does not change with the app's language, like flipper-screen.png. */
        const val PASTED = "pasted-text.txt"
    }
}
