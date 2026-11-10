// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.notify

import dev.zeffin.core.Lang
import dev.zeffin.link.ChatEvent
import dev.zeffin.link.PermissionAnswer
import dev.zeffin.link.AppLink
import dev.zeffin.link.chatText
import dev.zeffin.link.inputText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import kotlin.math.roundToInt
import dev.zeffin.ui.present.HomePath
import dev.zeffin.ui.present.PresentChat

/** The Notifications card of Settings, Agent. */
data class NotifySettingsUi(val turns: Boolean, val permissions: Boolean, val allowed: Boolean)

/**
 * One notification's words: [sub] goes next to the app's name, [publicTitle] and [publicText] on the lock screen.
 * [allowOnce]: a permission's notification offers Allow once (only when [text] shows the whole request).
 */
data class NoteText(val sub: String, val title: String, val text: String, val publicTitle: String, val publicText: String,
                    val allowOnce: Boolean = false)

/** The notifications' texts in both languages (docs/design/captures/notify-*). Nothing here touches Android. */
class PresentNotify(private val lang: Lang, agent: String? = null, private val home: String? = null) {
    private fun t(en: String, es: String) = lang.t(en, es)
    private val chat = PresentChat(lang, agent)

    fun settingsUi(prefs: NotifyPrefs, allowed: Boolean) = NotifySettingsUi(prefs.turns, prefs.permissions, allowed)

    // Channels: what Android lists in the app's notification settings.
    val workingChannel get() = t("Agent working", "Agente trabajando")
    val workingChannelDesc get() = t("While a turn runs with the app off screen", "Mientras un turno sigue con la app fuera de pantalla")
    val turnsChannel get() = t("Turn finished", "Turno terminado")
    val turnsChannelDesc get() = t("When a turn ends: finished, stopped or failed", "Cuando acaba un turno: terminado, parado o fallido")
    val permissionsChannel get() = t("Permission requests", "Peticiones de permiso")
    val permissionsChannelDesc get() = t("When the agent asks for permission", "Cuando el agente pide permiso")

    val working get() = t("The agent is working", "El agente está trabajando")
    val allowOnce get() = t("Allow once", "Permitir una vez")
    val deny get() = t("Deny", "Denegar")

    fun title(title: String?) = title?.ifBlank { null } ?: t("New conversation", "Conversación nueva")

    fun turnEnded(note: TurnNote, title: String?): NoteText {
        val unlock = t("Unlock to read it.", "Desbloquea para leerlo.")
        return when (note.outcome) {
            "done" -> NoteText(t("Turn finished", "Turno terminado"), title(title),
                note.text?.let(::plain)?.ifBlank { null } ?: t("The agent finished.", "El agente terminó."),
                t("The agent finished a turn", "El agente terminó un turno"), unlock)
            "interrupted" -> NoteText(t("Stopped", "Parado"), title(title),
                if (note.error == "stopping") chat.turnError("stopping") else t("The turn was stopped.", "El turno se paró."),
                t("The agent stopped", "El agente se paró"), unlock)
            else -> NoteText(t("Turn failed", "Turno fallido"), title(title), chat.turnError(note.error),
                t("The agent's turn failed", "El turno del agente falló"), unlock)
        }
    }

    fun isPlan(p: ChatEvent.Permission) = p.tool == "ExitPlanMode"

    fun ask(p: ChatEvent.Permission, title: String?): NoteText {
        val seconds = (p.deadline - p.at).coerceAtLeast(0.0).roundToInt()
        return if (isPlan(p)) {
            NoteText(t("Plan", "Plan"), t("The agent has a plan", "El agente tiene un plan"),
                t("Open the conversation to read it and approve it.", "Abre la conversación para leerlo y aprobarlo.") + "\n" + title(title),
                t("The agent has a plan", "El agente tiene un plan"), t("Unlock to see it.", "Desbloquea para verlo."))
        } else {
            // Allow once approves the whole request, so it is offered only when the notification shows all of it on
            // one line; otherwise the line breaks stay and the owner answers in the conversation, where it is whole.
            val input = shown(p.input)
            val whole = p.whole && input != null && isWhole(p.input) && '\n' !in input && input.length <= ALLOW_MAX
            val what = input?.let { "${p.tool}: $it" } ?: p.tool
            NoteText(t("Permission", "Permiso"), t("The agent asks for permission", "El agente pide permiso"),
                what + "\n" + (if (whole) "" else if (!p.whole) t("Too long to show whole, so it can only be denied.",
                    "Demasiado largo para mostrarlo entero, así que solo se puede denegar.") + "\n" else t("Open the conversation to read it all and answer.",
                    "Abre la conversación para leerlo entero y responder.") + "\n") +
                    title(title) + " · " + t("denied if no one answers in ${chat.timeoutName(seconds)}",
                    "se deniega si nadie responde en ${chat.timeoutName(seconds)}"),
                t("The agent asks for permission", "El agente pide permiso"),
                t("Unlock to see what it wants to do.", "Desbloquea para ver qué quiere hacer."), allowOnce = whole)
        }
    }

    val lostSub get() = t("No connection", "Sin conexión")
    val lostTitle get() = t("Lost contact with the server", "Se perdió el contacto con el servidor")
    val lostText get() = t("Open the app to see what happened.", "Abre la app para ver qué pasó.")

    fun sending(a: PermissionAnswer) = if (a == PermissionAnswer.DENY) t("Denying…", "Denegando…") else t("Allowing…", "Permitiendo…")

    fun notSent(why: NotSent) = when (why) {
        NotSent.GONE -> t("Already answered, or no longer waiting.", "Ya se respondió, o ya no espera.")
        NotSent.OFFLINE -> t("Could not reach the server. Open the app to answer.", "No se pudo llegar al servidor. Abre la app para responder.")
    }

    /**
     * What the notification shows of a request: its command, path, address or pattern, cut at 300 characters (past
     * ALLOW_MAX, so a cut text never gets Allow once), else its arguments. Never the agent's own description of it.
     */
    private fun shown(input: JsonElement?): String? {
        val o = input as? JsonObject
        val key = SHOWN.firstOrNull { (o?.get(it) as? JsonPrimitive)?.isString == true }
        return if (o != null && key != null) inputText((o[key] as JsonPrimitive).content, key == "command", MAX_TEXT)
            .let { if (key == "command" || key == "url" || key == "pattern") it else HomePath.path(it, home) }
            else AppLink.summarize(input)
    }

    companion object {
        const val MAX_TEXT = 300
        /** The fields the notification shows, in order (as the card's rows). */
        private val SHOWN = listOf("command", "file_path", "path", "url", "pattern")
        /** A request is shown whole when one of these is its only field that counts… */
        private val SOLE = setOf("command", "file_path", "path", "url")
        /** …besides these, which change nothing it does: the agent's words about it and its time limit. */
        private val BESIDES = setOf("description", "timeout")

        /** Whether [input] is one field the notification shows whole: Write's content, Edit's strings, WebFetch's prompt or any other argument stays hidden there. */
        fun isWhole(input: JsonElement?): Boolean {
            val keys = (input as? JsonObject)?.filterValues { it !is JsonNull }?.keys?.minus(BESIDES) ?: return false
            return keys.size == 1 && keys.single() in SOLE
        }
        /**
         * The longest one-line request the notification can answer with Allow once. Under the 300 characters where
         * PresentChat.toolText cuts, and the 160 where AppLink.summarize cuts, so a cut text never gets Allow once.
         */
        const val ALLOW_MAX = 150
        private val parser = Parser.builder().build()

        /** The agent's Markdown as plain text on one paragraph: no marks, no links (only their words), cut to [max]. */
        fun plain(markdown: String, max: Int = MAX_TEXT): String {
            val out = StringBuilder()
            parser.parse(markdown).accept(object : AbstractVisitor() {
                override fun visit(text: Text) { out.append(text.literal) }
                override fun visit(code: Code) { out.append(code.literal) }
                override fun visit(code: FencedCodeBlock) { out.append(' ').append(code.literal).append(' ') }
                override fun visit(code: IndentedCodeBlock) { out.append(' ').append(code.literal).append(' ') }
                override fun visit(b: SoftLineBreak) { out.append(' ') }
                override fun visit(b: HardLineBreak) { out.append(' ') }
                override fun visitChildren(parent: org.commonmark.node.Node) {
                    super.visitChildren(parent)
                    if (parent is org.commonmark.node.Block) out.append(' ')  // paragraphs, headings, list items
                }
            })
            return oneLine(chatText(out.toString()), max)  // again: commonmark decodes character references
        }

        fun oneLine(text: String, max: Int): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
        }
    }
}
