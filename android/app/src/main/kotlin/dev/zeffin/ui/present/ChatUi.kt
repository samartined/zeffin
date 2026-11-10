// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.link.AttachmentKind
import dev.zeffin.link.Effort
import dev.zeffin.link.Frame
import dev.zeffin.link.Mode

/** One attachment of a sent message, or of the tray before sending. */
data class AttachmentUi(
    val conversation: String,
    val id: String,
    val name: String,
    val kind: AttachmentKind,
    val meta: String,
    val width: Int?,
    val height: Int?,
) {
    /** A Flipper screen (128x64 or turned): drawn on the orange LCD paper, without smoothing. */
    val isScreen get() = kind == AttachmentKind.IMAGE && ((width == 128 && height == 64) || (width == 64 && height == 128) ||
        (width == 512 && height == 256) || (width == 256 && height == 512))
}

/** One file of the tray before sending; [hasPreview]: the screen draws its picture, decoded apart. */
data class TrayUi(val localId: Int, val chip: AttachmentUi, val hasPreview: Boolean, val progress: Float?, val error: String?)

/** A line of the chat. [key] is stable across updates, so the list never jumps. */
sealed class ChatItem {
    abstract val key: String

    /** [remote]: written on another device (Remote Control): its meta line carries the icon. */
    data class Mine(override val key: String, val text: String, val attachments: List<AttachmentUi>, val meta: String?,
                    val remote: Boolean = false) : ChatItem()
    data class Claude(override val key: String, val text: String, val streaming: Boolean) : ChatItem()
    data class Tool(override val key: String, val title: String, val text: String?, val meta: String?, val error: Boolean,
                    val screen: Frame?, val remote: Boolean = false) : ChatItem()
    data class Ask(override val key: String, val id: String, val title: String, val rows: List<Pair<String, String>>,
                   val always: Boolean, val state: Pair<Tone, String>, val rules: List<Pair<String, String>> = emptyList(),
                   /** Set when the request was too long to show whole: then only Deny is offered. */
                   val tooLong: String? = null) : ChatItem()
    data class Plan(override val key: String, val id: String, val plan: String, val state: Pair<Tone, String>) : ChatItem()
    data class Blocked(override val key: String, val rows: List<Pair<String, String>>, val canRetry: Boolean) : ChatItem()
    /** [face]: the last turn's end (a failure, a stop), drawn with the character beside the words. */
    data class Line(override val key: String, val tone: Tone, val text: String, val face: Face? = null) : ChatItem()
    /** A permission waiting for its answer on another device (Remote Control): nothing to answer here. */
    data class Waiting(override val key: String, val title: String, val line: String, val rows: List<Pair<String, String>>,
                       val state: Pair<Tone, String>) : ChatItem()
}

data class ComposerUi(
    val enabled: Boolean,
    /** The agent is working here: the round button interrupts instead of sending. */
    val busy: Boolean,
    val canAttach: Boolean,
    /** Files are attached and uploaded: the message may go without text. */
    val hasAttachments: Boolean = false,
    /** A file is still uploading: sending waits for it. */
    val uploading: Boolean = false,
    /** No clip at all (Remote Control on: messages carry no attachments). */
    val attachHidden: Boolean = false,
    /** The text is over the server's limit and cannot go: Send waits until it fits. */
    val tooLong: Boolean = false,
) {
    /** A message may go from here, once it has text or files. */
    val canSend get() = enabled && !tooLong
}

/** One choice of a list; one not [enabled] is shown dimmed and cannot be picked. [lead] starts its description with a
 *  word in a color, as a state ("Ready") or what a model takes ("Takes images"). */
data class OptionUi<T>(val value: T, val title: String, val desc: String?, val selected: Boolean, val enabled: Boolean = true,
                       val lead: Pair<Tone, String>? = null)

/**
 * A conversation's agent, when the server knows more than one: its name, and the list to change it
 * while nothing was written in the conversation ([canChange]); after that it is kept.
 */
data class ConvAgentUi(val name: String, val desc: String, val canChange: Boolean, val options: List<OptionUi<String>>,
                       val note: String, val sub: String)

data class ConversationSettingsUi(
    /** The Agent row and its list; null when the server knows one agent only. */
    val agent: ConvAgentUi? = null,
    val modelLabel: String,
    val models: List<OptionUi<String>>,
    val modelNote: String,
    val efforts: List<Effort>,
    val effort: Effort?,
    /** Each effort's name and hint, for the dial. */
    val effortWords: Map<Effort, Pair<String, String>>,
    val modeLabel: String,
    val modes: List<OptionUi<Mode>>,
    /** The agent's remote access, when the server offers it: the switch row. */
    val remote: RemoteUi? = null,
    /** Why model, effort and mode cannot change now (Remote Control on); null when they can. */
    val locked: String? = null,
    /** Under the panel's title and its lists' titles. */
    val sub: String,
    /** At the panel's foot. */
    val note: String,
    /** At the mode list's foot. */
    val modeNote: String,
)

/** The Remote Control switch, and the app bar's icon while it is on. */
data class RemoteUi(val name: String, val desc: String, val on: Boolean, val iconLabel: String,
                    /** Turning it off asks first: a turn runs in it. */
                    val confirmOff: Boolean)

/** A notice above the composer: the chat cannot be used, an error, or the agent is busy elsewhere. */
data class ChatBanner(val tone: Tone, val title: String, val text: String?, val actions: List<Pair<ChatAction, String>> = emptyList(),
                      /** A part of [text] in the ink color, such as a count. */
                      val strong: String? = null)

enum class ChatAction { DISMISS, OPEN_BUSY, STOP_BUSY, PAIR, CHOOSE_MODEL, CHOOSE_AGENT }

data class ChatUi(
    /** false: the server has no chat; the banner says to update it. */
    val available: Boolean,
    /** The conversation's app bar: its title and "Opus · Medium · Manual", which opens the settings. */
    val title: String,
    val settingsLine: String,
    val items: List<ChatItem>,
    val composer: ComposerUi,
    val banner: ChatBanner?,
    val settings: ConversationSettingsUi?,
    /** No conversation yet: a hint where the chat goes. */
    val empty: String?,
    /** Nothing written yet and the server knows several agents: a card with the agent, which a tap changes. */
    val agentCard: ConvAgentUi? = null,
    /** Remote Control is on here: the app bar shows its icon. */
    val remoteOn: Boolean = false,
    /** The character at the chat's tail while the agent works, and for a moment after a turn ends well. */
    val tail: CharacterUi? = null,
    /** The character in the app bar (PresentCharacter.bar). */
    val face: Face = Face.ACTIVE,
)
