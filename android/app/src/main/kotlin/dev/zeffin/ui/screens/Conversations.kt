// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.ActionRow
import dev.zeffin.ui.components.BottomNav
import dev.zeffin.ui.components.ButtonKind
import dev.zeffin.ui.components.FbButton
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.FbSheet
import dev.zeffin.ui.components.Icon
import dev.zeffin.ui.components.IconButton
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.SettingRow
import dev.zeffin.ui.components.StateText
import dev.zeffin.ui.components.Tab
import dev.zeffin.ui.components.TabBand
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.components.UnderlineTextField
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.present.ConvRowUi
import dev.zeffin.ui.present.ConversationFilters
import dev.zeffin.ui.present.ConversationsUi
import dev.zeffin.ui.present.NavUi
import dev.zeffin.ui.present.Period
import dev.zeffin.ui.present.Tone
import dev.zeffin.ui.present.ConvSheet
import dev.zeffin.ui.components.Character
import dev.zeffin.ui.components.CharacterSize
import dev.zeffin.ui.present.Face

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationsScreen(
    ui: ConversationsUi,
    nav: NavUi,
    searching: Boolean,
    onTab: (Tab) -> Unit,
    onFavorites: (Boolean) -> Unit = {},
    onNew: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onActions: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onQuery: (String) -> Unit = {},
    onFilters: () -> Unit = {},
) {
    val l = LocalLang.current
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("Conversations", "Conversaciones")) {
            IconButton(FbIcons.search, l.t("Search conversations", "Buscar conversaciones"), onClick = onSearch)
            IconButton(FbIcons.tune, l.t("Filter and sort", "Filtrar y ordenar"), onClick = onFilters)
        }
        TabBand(listOf(l.t("All", "Todas"), l.t("Favorites", "Favoritas")), if (ui.favorites) 1 else 0) { onFavorites(it == 1) }
        PageBody(Modifier.weight(1f)) {
            if (!ui.available) {
                dev.zeffin.ui.components.Banner(Tone.WARNING, l.t("The chat needs a server update", "El chat necesita actualizar el servidor"),
                    l.t("Run zf update on the server.", "Ejecuta zf update en el servidor."))
                return@PageBody
            }
            if (searching) UnderlineTextField(l.t("Search", "Buscar"), ui.query, onQuery, mono = false, keyboard = KeyboardType.Text)
            if (!searching && !ui.favorites) FbCard { item { ActionRow(FbIcons.plus, l.t("New conversation", "Conversación nueva"), onClick = onNew) } }
            if (ui.summary != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(ui.summary, FbType.caption, Modifier.weight(1f), color = FbColors.inkMuted)
                    Box(Modifier.clickable(role = Role.Button, onClick = onFilters).padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                        Txt(l.t("Change", "Cambiar"), FbType.label, color = FbColors.action)
                    }
                }
            }
            ConversationGroups(ui, RowClicks(l.t("Open", "Abrir"), onOpen, onActions))
        }
        BottomNav(Tab.CONVERSATIONS, nav, onTab)
    }
}

/** What a tap and a long press on a row do, and the tap's label for TalkBack ([tapLabel] null: [selectedLabel] says it). */
internal class RowClicks(val tapLabel: String?, val onTap: (String) -> Unit, val onLong: (String) -> Unit,
                        val selectedLabel: (Boolean) -> String = { "" })

/** The list's groups (Pinned, Today…), each a card of rows; while selecting each row has its check. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationGroups(ui: ConversationsUi, clicks: RowClicks) {
    val l = LocalLang.current
    for (group in ui.groups) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (group.pinned) Icon(FbIcons.pin, FbColors.inkMuted, size = 16.dp)
                Txt(group.title, FbType.heading)
            }
            FbCard {
                for (row in group.rows) item {
                    Row(
                        Modifier.fillMaxWidth().background(if (row.selected == true) FbColors.infoSoft else androidx.compose.ui.graphics.Color.Transparent)
                            .combinedClickable(onClickLabel = clicks.tapLabel ?: clicks.selectedLabel(row.selected == true),
                                onLongClickLabel = l.t("Select", "Seleccionar"), onLongClick = { clicks.onLong(row.id) }) { clicks.onTap(row.id) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (row.selected != null) Tick(row.selected)
                        ConversationRow(row, Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (ui.empty != null) Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Character(Face.ACTIVE, height = CharacterSize.stage)
        Txt(ui.empty, FbType.label, Modifier.fillMaxWidth(), color = FbColors.inkFaint, align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** One conversation: title, Remote Control's icon, the star and the time; its last line; what the agent does in it. */
@Composable
private fun ConversationRow(row: ConvRowUi, modifier: Modifier) {
    val l = LocalLang.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(row.title, FbType.heading, Modifier.weight(1f), maxLines = 1)
            if (row.remote != null) Icon(FbIcons.remote, FbColors.info, size = 16.dp, description = row.remote)
            if (row.favorite) Icon(FbIcons.starFill, FbColors.warning, size = 16.dp, description = l.t("Favorite", "Favorita"))
            Txt(row.time, FbType.caption, color = FbColors.inkFaint, maxLines = 1)
        }
        if (row.last.isNotBlank()) Txt(row.last, FbType.body, color = FbColors.inkMuted, maxLines = 1)
        if (row.active != null) StateText(Tone.ACTION, row.active)
    }
}

/** Filter and sort: the switches and the two lists, and a button that says how many will show. */
@Composable
fun BoxScope.FiltersSheet(
    filters: ConversationFilters, sortName: String, periodName: String, count: Int,
    onChange: (ConversationFilters) -> Unit, onSheet: (ConvSheet) -> Unit,
) {
    val l = LocalLang.current
    FbSheet(l.t("Filter and sort", "Filtrar y ordenar"), onDismiss = { onSheet(ConvSheet.NONE) }) {
        SettingRow(l.t("Group by day", "Agrupar por día"), l.t("Today, Yesterday, This week and earlier months",
            "Hoy, Ayer, Esta semana y los meses anteriores"), checked = filters.groupByDay,
            onCheckedChange = { onChange(filters.copy(groupByDay = it)) })
        dev.zeffin.ui.components.Divider()
        SettingRow(l.t("Sort by", "Ordenar por"), value = sortName, onClick = { onSheet(ConvSheet.SORT) })
        dev.zeffin.ui.components.Divider()
        SettingRow(l.t("Period", "Periodo"), value = periodName, onClick = { onSheet(ConvSheet.PERIOD) })
        dev.zeffin.ui.components.Divider()
        SettingRow(l.t("Only with Flipper actions", "Solo con acciones en el Flipper"),
            l.t("Hides the ones that used no flipper_* tools", "Oculta las que no usaron herramientas flipper_*"),
            checked = filters.onlyFlipper, onCheckedChange = { onChange(filters.copy(onlyFlipper = it)) })
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            FbButton(l.t("Show $count conversations", "Mostrar $count conversaciones"), Modifier.fillMaxWidth(), ButtonKind.PRIMARY) {
                onSheet(ConvSheet.NONE)
            }
        }
    }
}

/** Deleting a conversation asks first and says what goes; from the list and from inside the conversation. */
@Composable
fun BoxScope.DeleteConversationSheet(sub: String, text: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val l = LocalLang.current
    FbSheet(l.t("Delete this conversation?", "¿Borrar esta conversación?"), sub, onDismiss = onDismiss) {
        Txt(text, FbType.body, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), color = FbColors.inkMuted)
        dev.zeffin.ui.components.Divider()
        ActionRow(FbIcons.trash, l.t("Delete the conversation", "Borrar la conversación"), danger = true, onClick = onDelete)
        dev.zeffin.ui.components.Divider()
        ActionRow(FbIcons.close, l.t("Keep it", "Conservarla"), onClick = onDismiss)
    }
}

@Composable
fun BoxScope.RenameSheet(title: String, onTitle: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val l = LocalLang.current
    FbSheet(l.t("Rename", "Renombrar"), onDismiss = onDismiss) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UnderlineTextField(l.t("Title", "Título"), title, onTitle, mono = false, keyboard = KeyboardType.Text)
            FbButton(l.t("Save", "Guardar"), Modifier.fillMaxWidth(), ButtonKind.PRIMARY, enabled = title.isNotBlank(), onClick = onSave)
        }
    }
}
