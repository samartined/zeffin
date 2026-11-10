// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.zeffin.AppGraph
import dev.zeffin.link.Conversation
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.present.ConvSheet
import dev.zeffin.ui.present.ConversationFilters
import dev.zeffin.ui.present.OptionUi
import dev.zeffin.ui.present.Period
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.PresentConversations
import dev.zeffin.ui.present.SortBy
import dev.zeffin.ui.screens.DeleteManySheet
import dev.zeffin.ui.screens.SelectionActions
import dev.zeffin.ui.screens.SelectionWords
import dev.zeffin.ui.screens.Selecting
import dev.zeffin.ui.present.PresentChat
import androidx.compose.runtime.LaunchedEffect
import dev.zeffin.ui.screens.ConversationsScreen
import dev.zeffin.ui.screens.ConversationsSelecting
import dev.zeffin.ui.screens.DeleteConversationSheet
import dev.zeffin.ui.screens.FiltersSheet
import dev.zeffin.ui.screens.OptionSheet
import dev.zeffin.ui.screens.RenameSheet
import kotlinx.coroutines.launch

@Composable
fun ConversationsRoute(graph: AppGraph, nav: AppNavigator) {
    val scope = rememberCoroutineScope()
    val relay by graph.relay.runtime.state.collectAsState()
    val link by graph.link.state.collectAsState()
    val chatState by graph.chat.state.collectAsState()
    val now = rememberNow()
    val lang = LocalLang.current
    val present = remember(lang) { Present(lang) }
    val words = remember(lang, chatState.agentName) { PresentConversations(lang, chatState.agentName) }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var filtersAll by rememberSaveable(stateSaver = FiltersSaver) { mutableStateOf(ConversationFilters.defaults(false)) }
    var filtersFav by rememberSaveable(stateSaver = FiltersSaver) { mutableStateOf(ConversationFilters.defaults(true)) }
    val filters = if (favorites) filtersFav else filtersAll
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val list = rememberSaveable(saver = ListState.Saver) { ListState() }
    val ui = words.conversationsUi(chatState, favorites, filters, query, now)
    val shown = words.shownIds(ui)
    // What left the list (deleted, or out of Favorites) leaves the selection; nothing left, no selection
    LaunchedEffect(shown, chatState.deletions) { list.keepOnly(shown, chatState.deletions?.done != false, graph) }
    val selection = rememberSelecting(graph, list, shown, favorites, query)
    Box(Modifier.fillMaxSize()) {
        if (selection != null) ConversationsSelecting(words.selecting(ui, list.picked.orEmpty().toSet()), selection, searching) { query = it }
        else ConversationsScreen(ui, present.nav(relay, link), searching, nav::tab,
            onFavorites = { favorites = it },
            onNew = { graph.chat.newConversation { scope.launch { nav.openConversation() } } },
            onOpen = { id -> graph.chat.open(id); nav.openConversation() },
            onActions = { id -> graph.chat.dismissDeletions(); list.picked = listOf(id) },
            onSearch = { searching = !searching; if (!searching) query = "" },
            onQuery = { query = it },
            onFilters = { list.sheet = ConvSheet.FILTERS })
        when (list.sheet) {
            ConvSheet.FILTERS, ConvSheet.SORT, ConvSheet.PERIOD -> FilterSheets(list.sheet, filters,
                words.count(chatState, favorites, filters, query, now), { if (favorites) filtersFav = it else filtersAll = it }) { list.sheet = it }
            else -> SelectionSheets(graph, list)
        }
    }
}

/** The list's own state across a rotation: the conversations selected (in the order they were picked; null when not
 *  selecting: a long press starts it) and the sheet open over the list. */
private class ListState(picked: List<String>? = null, sheet: ConvSheet = ConvSheet.NONE) {
    var picked by mutableStateOf(picked)
    var sheet by mutableStateOf(sheet)

    fun leave(graph: AppGraph) {
        picked = null
        sheet = ConvSheet.NONE
        graph.chat.dismissDeletions()
    }

    /** Only the ones the list still shows stay selected; none left (and no deletion waiting), no selection. */
    fun keepOnly(shown: List<String>, settled: Boolean, graph: AppGraph) {
        val now = picked ?: return
        val kept = now.filter { it in shown }
        if (kept.isEmpty() && settled) leave(graph) else if (kept != now) picked = kept
    }

    companion object {
        val Saver = listSaver<ListState, String>(
            save = { listOf(it.sheet.name) + it.picked.orEmpty() },
            restore = { ListState(it.drop(1).ifEmpty { null }, ConvSheet.valueOf(it[0])) },
        )
    }
}

/** While selecting: the bar's words and counts, and what each action does to every selected conversation. */
@Composable
private fun rememberSelecting(graph: AppGraph, list: ListState, shown: List<String>, favorites: Boolean, query: String): Selecting? {
    val ids = list.picked ?: return null
    val chatState by graph.chat.state.collectAsState()
    val context = LocalContext.current
    val lang = LocalLang.current
    val words = PresentConversations(lang, chatState.agentName)
    val sel = words.selectionUi(chatState, ids.toSet(), shown, favorites, query)
    val leave = { list.leave(graph) }
    fun forEach(action: (String) -> Unit) { ids.forEach(action); leave() }
    return Selecting(sel, SelectionWords(words.leaveLabel, words.selectAllLabel, words::pinLabel, words::favoriteLabel, words.renameLabel,
        words.copyLabel, words.deleteLabel) { on -> if (on) lang.t("Unselect", "Quitar de la selección") else lang.t("Select", "Seleccionar") },
        SelectionActions(
            onToggle = { id -> (if (id in ids) ids - id else ids + id).let { if (it.isEmpty()) leave() else list.picked = it } },
            onSelectAll = { list.picked = shown },
            onLeave = leave,
            onPin = { forEach { graph.chat.update(it, pinned = sel.pin) } },
            onFavorite = { forEach { graph.chat.update(it, favorite = sel.favorite) } },
            onRename = { list.sheet = ConvSheet.RENAME },
            onCopy = { context.copy(ids.first(), "${chatState.agentName ?: "Agent"} session"); leave() },
            onDelete = { list.sheet = if (ids.size == 1) ConvSheet.DELETE else ConvSheet.DELETE_MANY },
            onRetry = { chatState.deletions?.failed?.keys?.toList()?.let(graph.chat::deleteAll) },
        ), words.deletionsUi(chatState, PresentChat(lang, chatState.agentName)))
}

/** The selection's sheets: renaming the one selected, deleting it, or deleting several (each asks first). */
@Composable
private fun BoxScope.SelectionSheets(graph: AppGraph, list: ListState) {
    val chatState by graph.chat.state.collectAsState()
    val lang = LocalLang.current
    val ids = list.picked ?: return
    val first = chatState.conversations.firstOrNull { it.id == ids.first() }
    when {
        list.sheet == ConvSheet.DELETE_MANY -> DeleteManySheet(PresentConversations(lang, chatState.agentName).deleteMany(chatState, ids),
            onDelete = { graph.chat.deleteAll(ids); list.sheet = ConvSheet.NONE }, onDismiss = { list.sheet = ConvSheet.NONE })
        (list.sheet == ConvSheet.RENAME || list.sheet == ConvSheet.DELETE) && first != null ->
            ConversationSheets(graph, list.sheet, first, onDone = { list.leave(graph) }) { list.sheet = it }
    }
}

/** Filter and sort, and the two lists they open. */
@Composable
private fun BoxScope.FilterSheets(sheet: ConvSheet, filters: ConversationFilters, count: Int, onFilters: (ConversationFilters) -> Unit,
                                  onSheet: (ConvSheet) -> Unit) {
    val lang = LocalLang.current
    val words = remember(lang) { PresentConversations(lang) }
    when (sheet) {
        ConvSheet.SORT -> OptionSheet(lang.t("Sort by", "Ordenar por"),
            SortBy.entries.map { OptionUi(it, words.sortName(it), null, it == filters.sort) }, null, null) { pick ->
            pick?.let { onFilters(filters.copy(sort = it)) }; onSheet(ConvSheet.FILTERS)
        }
        ConvSheet.PERIOD -> OptionSheet(lang.t("Period", "Periodo"),
            Period.entries.map { OptionUi(it, words.periodName(it), null, it == filters.period) }, null, null) { pick ->
            pick?.let { onFilters(filters.copy(period = it)) }; onSheet(ConvSheet.FILTERS)
        }
        else -> FiltersSheet(filters, words.sortName(filters.sort), words.periodName(filters.period), count, onFilters, onSheet)
    }
}

/** One selected conversation: renaming it, and deleting it (it asks first). */
@Composable
private fun BoxScope.ConversationSheets(graph: AppGraph, sheet: ConvSheet, conversation: Conversation, onDone: () -> Unit,
                                        onSheet: (ConvSheet) -> Unit) {
    val chatState by graph.chat.state.collectAsState()
    val lang = LocalLang.current
    val words = remember(lang, chatState.agentName) { PresentConversations(lang, chatState.agentName) }
    var newTitle by rememberSaveable(conversation.id) { mutableStateOf(conversation.title) }
    when (sheet) {
        ConvSheet.RENAME -> RenameSheet(newTitle, { newTitle = it }, onSave = {
            graph.chat.update(conversation.id, title = newTitle.trim()); onDone()
        }, onDismiss = { onSheet(ConvSheet.NONE) })
        else -> DeleteConversationSheet(words.deleteSub(conversation),
            words.deleteText(conversation, named = true, busy = chatState.status?.turn?.conversation == conversation.id),
            onDelete = { if (graph.chat.deleteAll(listOf(conversation.id))) onSheet(ConvSheet.NONE) },
            onDismiss = { onSheet(ConvSheet.NONE) })
    }
}

/** The filters kept across a rotation, by name. */
private val FiltersSaver = listSaver<ConversationFilters, Any>(
    save = { listOf(it.groupByDay, it.sort.name, it.period.name, it.onlyFlipper) },
    restore = { ConversationFilters(it[0] as Boolean, SortBy.valueOf(it[1] as String), Period.valueOf(it[2] as String), it[3] as Boolean) },
)
