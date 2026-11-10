// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.core.Lang
import dev.zeffin.link.ChatState
import dev.zeffin.link.Conversation
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The Conversations screen from the chat's state: tabs, groups by day, filters, search and time labels.
 * Pure, for tests: [nowMs] and [Fmt.zone] decide what "today" is.
 */
class PresentConversations(private val l: Lang, private val name: String? = null) {
    /** What belongs to the agent: "Claude Code's" or "the agent's"; "de Claude Code" or "del agente". */
    private val agents get() = name?.let { l.t("$it's", "de $it") } ?: l.t("the agent's", "del agente")

    private fun t(en: String, es: String) = l.t(en, es)
    private val locale = if (l == Lang.ES) Locale.forLanguageTag("es") else Locale.ENGLISH
    private val day = DateTimeFormatter.ofPattern(if (l == Lang.ES) "d MMM" else "MMM d", locale)
    private val weekday = DateTimeFormatter.ofPattern("EEE", locale)
    private val month = DateTimeFormatter.ofPattern("LLLL", locale)
    private val monthYear = DateTimeFormatter.ofPattern("LLLL yyyy", locale)

    private fun date(epochS: Double) = Instant.ofEpochMilli((epochS * 1000).toLong()).atZone(Fmt.zone).toLocalDate()
    private fun clean(s: String) = s.trimEnd('.').replaceFirstChar { it.uppercase(locale) }

    /** How many days before today; 0 is today. */
    private fun daysAgo(epochS: Double, nowMs: Long) =
        ChronoUnit.DAYS.between(date(epochS), Instant.ofEpochMilli(nowMs).atZone(Fmt.zone).toLocalDate()).toInt()

    /** "14:03" today; "Mon" for the last days; "Oct 18" before. Longer when the list is not grouped by day. */
    fun timeLabel(epochS: Double, nowMs: Long, grouped: Boolean): String {
        val days = daysAgo(epochS, nowMs)
        val d = date(epochS)
        val time = Fmt.hmSeconds(epochS)
        return when {
            days <= 0 -> time
            days == 1 -> if (grouped) time else t("Yesterday $time", "Ayer $time")
            days <= 3 && grouped -> clean(weekday.format(d))
            days <= 6 && !grouped -> clean(weekday.format(d)) + " " + clean(day.format(d))
            else -> clean(day.format(d))
        }
    }

    private fun groupOf(epochS: Double, nowMs: Long): String {
        val days = daysAgo(epochS, nowMs)
        val d: LocalDate = date(epochS)
        val today = Instant.ofEpochMilli(nowMs).atZone(Fmt.zone).toLocalDate()
        return when {
            days <= 0 -> t("Today", "Hoy")
            days == 1 -> t("Yesterday", "Ayer")
            days <= 6 -> t("This week", "Esta semana")
            d.year == today.year -> clean(month.format(d))
            else -> clean(monthYear.format(d))
        }
    }

    fun matches(c: Conversation, filters: ConversationFilters, favoritesOnly: Boolean, query: String, nowMs: Long): Boolean {
        if (favoritesOnly && !c.favorite) return false
        if (filters.onlyFlipper && !c.flipper) return false
        val days = daysAgo(c.updated, nowMs)
        if (filters.period == Period.WEEK && days > 6) return false
        if (filters.period == Period.MONTH && days > 30) return false
        if (query.isNotBlank() && !(c.title.contains(query, true) || c.last.contains(query, true))) return false
        return true
    }

    fun sorted(list: List<Conversation>, sort: SortBy): List<Conversation> = when (sort) {
        SortBy.ACTIVITY -> list.sortedByDescending { it.updated }
        SortBy.CREATED -> list.sortedByDescending { it.created }
        SortBy.TITLE -> list.sortedBy { it.title.lowercase(locale) }
    }

    fun sortName(s: SortBy) = when (s) {
        SortBy.ACTIVITY -> t("Last activity", "Última actividad")
        SortBy.CREATED -> t("Newest first", "Más nuevas primero")
        SortBy.TITLE -> t("Title", "Título")
    }

    fun periodName(p: Period) = when (p) {
        Period.ALL -> t("All", "Todo")
        Period.WEEK -> t("Last 7 days", "Últimos 7 días")
        Period.MONTH -> t("Last 30 days", "Últimos 30 días")
    }

    private fun title(c: Conversation) = c.title.ifBlank { t("New conversation", "Conversación nueva") }

    fun conversationsUi(state: ChatState, favorites: Boolean, filters: ConversationFilters, query: String, nowMs: Long): ConversationsUi {
        val turn = state.status?.turn
        val remoteLabel = state.remote?.let { PresentRemote(l, it).iconLabel }
        val visible = sorted(state.conversations.filter { matches(it, filters, favorites, query, nowMs) }, filters.sort)
        fun row(c: Conversation) = ConvRowUi(
            id = c.id, title = title(c), time = timeLabel(c.updated, nowMs, filters.groupByDay), last = c.last,
            favorite = c.favorite, pinned = c.pinned,
            active = if (turn?.conversation == c.id) (if (turn.waiting) t("Waiting for your permission", "Esperando tu permiso")
                else t("Driving the Flipper · the agent is writing", "Manejando el Flipper · el agente escribe")) else null,
            remote = remoteLabel?.takeIf { state.status?.remote?.conversation == c.id },
        )
        val groups = mutableListOf<ConvGroupUi>()
        val pinned = visible.filter { it.pinned }
        if (pinned.isNotEmpty()) groups += ConvGroupUi(t("Pinned", "Fijadas"), pinned = true, rows = pinned.map(::row))
        val rest = visible.filterNot { it.pinned }
        if (filters.groupByDay && filters.sort == SortBy.ACTIVITY) {
            for ((name, items) in rest.groupBy { groupOf(it.updated, nowMs) }) groups += ConvGroupUi(name, false, items.map(::row))
        } else if (rest.isNotEmpty()) {
            groups += ConvGroupUi(if (favorites) t("Favorites", "Favoritas") else t("Conversations", "Conversaciones"), false, rest.map(::row))
        }
        val defaults = ConversationFilters.defaults(favorites)
        val summary = if (filters != defaults || favorites) {
            val count = visible.size
            listOfNotNull(
                if (favorites) t("$count favorites", "$count favoritas") else t("$count conversations", "$count conversaciones"),
                if (filters.groupByDay) t("grouped by day", "agrupadas por día") else t("not grouped", "sin agrupar"),
                when (filters.sort) {
                    SortBy.ACTIVITY -> t("newest first", "las más recientes primero")
                    SortBy.CREATED -> t("by creation", "por creación")
                    SortBy.TITLE -> t("by title", "por título")
                },
                periodName(filters.period).takeIf { filters.period != Period.ALL }?.lowercase(locale),
                t("only with Flipper actions", "solo con acciones en el Flipper").takeIf { filters.onlyFlipper },
            ).joinToString(" · ")
        } else null
        return ConversationsUi(favorites, groups, summary, query,
            empty = if (groups.isEmpty()) (if (query.isNotBlank()) t("Nothing matches the search.", "Nada coincide con la búsqueda.")
                else if (favorites) t("Mark a conversation as favorite with a long press.", "Marca una conversación como favorita con una pulsación larga.")
                else t("No conversations yet. Start one from Home or here.", "Aún no hay conversaciones. Empieza una desde Inicio o aquí.")) else null,
            available = state.supported != false)
    }

    /** The deletion's subtitle: "23 messages · 2 attachments". */
    fun deleteSub(c: Conversation): String = listOfNotNull(
        if (c.turns == 1) t("1 message", "1 mensaje") else t("${c.turns} messages", "${c.turns} mensajes"),
        when {
            c.attachments == 1 -> t("1 attachment", "1 adjunto")
            c.attachments > 1 -> t("${c.attachments} attachments", "${c.attachments} adjuntos")
            else -> null
        },
    ).joinToString(" · ")

    /** What goes with it, and that it cannot be undone. From the list it names the conversation ([named]); a running turn
     *  in it stops first. */
    fun deleteText(c: Conversation, named: Boolean, busy: Boolean): String {
        val what = if (named) t("“${title(c)}” is deleted from the server", "«${title(c)}» se borra del servidor")
            else t("It is deleted from the server", "Se borra del servidor")
        return what + t(": its messages, its attachments and the agent's own copy of the session ($agents transcript).",
            ": sus mensajes, sus adjuntos y la copia de la sesión que guarda el agente (la transcripción $agents).") +
            (if (busy) t(" The agent is writing in it: it stops first.", " El agente está escribiendo en ella: se detiene antes.") else "") +
            t(" It cannot be undone.", " No se puede deshacer.")
    }

    /** The same list while selecting: each row says whether it is one of [selected]. */
    fun selecting(ui: ConversationsUi, selected: Set<String>): ConversationsUi =
        ui.copy(groups = ui.groups.map { g -> g.copy(rows = g.rows.map { it.copy(selected = it.id in selected) }) })

    /** The ids the list shows, in its order: what Select all takes. */
    fun shownIds(ui: ConversationsUi): List<String> = ui.groups.flatMap { g -> g.rows.map { it.id } }

    /**
     * The selection's bar and actions for [selected] among [shown] (the rows the list shows: Select all takes those,
     * so with a search only the matches).
     */
    fun selectionUi(state: ChatState, selected: Set<String>, shown: List<String>, favorites: Boolean, query: String): SelectionUi {
        val chosen = state.conversations.filter { it.id in selected }
        val n = chosen.size
        val list = when {
            query.isNotBlank() -> t("Matching “$query”", "Con «$query»")
            favorites -> t("Favorites", "Favoritas")
            else -> t("All conversations", "Todas las conversaciones")
        }
        val count = if (shown.size > 1 && shown.all { it in selected }) t("all ${shown.size} selected", "las ${shown.size} seleccionadas")
            else t("${shown.size} in the list", "${shown.size} en la lista")
        return SelectionUi(
            title = if (n == 1) t("1 selected", "1 seleccionada") else t("$n selected", "$n seleccionadas"),
            sub = "$list · $count",
            pin = chosen.any { !it.pinned }, favorite = chosen.any { !it.favorite }, single = n == 1,
            canDelete = state.deletion,
        )
    }

    /** The bottom bar's labels while selecting, short to fit five. */
    fun pinLabel(pin: Boolean) = if (pin) t("Pin", "Fijar") else t("Unpin", "Desfijar")
    fun favoriteLabel(favorite: Boolean) = if (favorite) t("Favorite", "Favorita") else t("Unfavorite", "No favorita")
    val renameLabel get() = t("Rename", "Renombrar")
    val copyLabel get() = t("Copy ID", "Copiar ID")
    val deleteLabel get() = t("Delete", "Borrar")
    val selectAllLabel get() = t("Select all", "Seleccionar todas")
    val leaveLabel get() = t("Leave selection", "Salir de la selección")

    private fun messages(n: Int) = if (n == 1) t("1 message", "1 mensaje") else t("$n messages", "$n mensajes")

    /** Deleting several: each title with its messages, the totals, and that a running turn stops first. */
    fun deleteMany(state: ChatState, ids: Collection<String>): DeleteManyUi {
        val chosen = state.conversations.filter { it.id in ids }
        val n = chosen.size
        val attachments = chosen.sumOf { it.attachments }
        val running = chosen.firstOrNull { it.id == state.status?.turn?.conversation }
        return DeleteManyUi(
            title = t("Delete $n conversations?", "¿Borrar $n conversaciones?"),
            sub = listOfNotNull(messages(chosen.sumOf { it.turns }), when {
                attachments == 1 -> t("1 attachment", "1 adjunto")
                attachments > 1 -> t("$attachments attachments", "$attachments adjuntos")
                else -> null
            }).joinToString(" · "),
            rows = chosen.map { title(it) to messages(it.turns) },
            text = t("They are deleted from the server: their messages, their attachments and each agent's own copy of the session.",
                "Se borran del servidor: sus mensajes, sus adjuntos y la copia de la sesión que guarda cada agente.") +
                (running?.let { t(" “${title(it)}” is running: its turn stops first.", " «${title(it)}» está en marcha: su turno se detiene antes.") } ?: "") +
                t(" It cannot be undone.", " No se puede deshacer."),
            delete = t("Delete $n conversations", "Borrar $n conversaciones"),
            keep = t("Keep them", "Conservarlas"),
        )
    }

    /** After deleting several, when some could not be deleted: how many went, and why each other one stayed. */
    fun deletionsUi(state: ChatState, chat: PresentChat): DeletionsUi? {
        val d = state.deletions?.takeIf { it.done && it.failed.isNotEmpty() } ?: return null
        val failed = d.failed.size
        val why = d.failed.entries.joinToString(" ") { (id, e) ->
            val name = state.conversations.firstOrNull { it.id == id }?.let(::title) ?: id.take(8)
            val reason = if (e.code == dev.zeffin.link.AppProtocol.Err.NOT_CONNECTED)
                t("the app was not connected to the server", "la app no estaba conectada al servidor")
                else chat.errorText(e).trimEnd('.').replaceFirstChar { it.lowercase(locale) }
            t("“$name”: $reason, so it was kept.", "«$name»: $reason, así que se conservó.")
        }
        return DeletionsUi(
            title = t("${d.deleted} deleted, $failed not deleted", "${d.deleted} borradas, $failed sin borrar"),
            text = why + (if (failed == 1) t(" It stays selected.", " Sigue seleccionada.") else t(" They stay selected.", " Siguen seleccionadas.")),
        )
    }

    /** How many the filters would show, for the button "Show 12 conversations". */
    fun count(state: ChatState, favorites: Boolean, filters: ConversationFilters, query: String, nowMs: Long) =
        state.conversations.count { matches(it, filters, favorites, query, nowMs) }
}
