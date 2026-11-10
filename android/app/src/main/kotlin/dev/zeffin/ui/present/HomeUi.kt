// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

/** The bottom bar's first tab: the connection in words, and the character's [face] on its icon. */
data class NavUi(val statusLabel: String, val off: Boolean, val face: Face = if (off) Face.OFFLINE else Face.ACTIVE)

data class ActionUi(val title: String, val text: String?, val meta: String?, val error: Boolean)

/** A line between the actions: who paused the agent, why the screen is old. */
data class FeedItem(val action: ActionUi? = null, val line: Pair<Tone, String>? = null)

/** Why Home has no live Flipper; [PAIR_AGAIN]: the server's key changed, or the pairing has none. */
enum class Offline { NO_RELAY, NO_SERVER, NOT_PAIRED, PAIR_AGAIN }

data class HomeUi(
    val title: String,
    val subtitle: String,
    val screenMode: ScreenMode,
    val badge: String?,
    val paused: Boolean,
    val gateEnabled: Boolean,
    val offline: Offline?,
    val feed: List<FeedItem>,
    val nav: NavUi,
    /** The small screen in a conversation: "Demo · agent active" and "#418 · now" (or what is wrong). */
    val stripTitle: String = title,
    val stripSub: String = subtitle,
    /** Offline with a Flipper chosen: the remote still works without a server, so Home offers this row. */
    val useRemote: String? = null,
    /** The character's face for the shared device (the app bar and the screen without an image). */
    val face: Face = Face.ACTIVE,
) {
    val offersRemote: Boolean get() = useRemote != null
}

/** A permission pending in a conversation, answered from Home: the same rows and buttons as in the conversation. */
data class PermissionUi(val id: String, val rows: List<Pair<String, String>>, val always: Boolean, val rules: List<Pair<String, String>> = emptyList(),
                        /** Set when the request was too long to show whole: then only Deny is offered. */
                        val tooLong: String? = null)

/**
 * Home's card: the conversation where the agent works, or the last one; what the agent does in it; its last line or
 * a pending [permission]. [id] is null when there is no conversation yet.
 */
data class LastConversationUi(
    val id: String?, val title: String, val state: Pair<Tone, String>?, val line: String?, val isNew: Boolean,
    val permission: PermissionUi? = null,
    /** Remote Control is on in it: the icon after the title, with this label for TalkBack. */
    val remote: String? = null,
    /** The character beside the title: what the agent does in it, or how its last turn ended. */
    val character: CharacterUi = CharacterUi(Face.ACTIVE),
)

enum class ScreenMode { LIVE, STALE, OFF }
