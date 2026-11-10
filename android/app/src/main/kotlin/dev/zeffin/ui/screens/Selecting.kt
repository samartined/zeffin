// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import dev.zeffin.ui.components.FbSheet
import dev.zeffin.ui.components.Icon
import dev.zeffin.ui.components.IconButton
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.TabBand
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.components.UnderlineTextField
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.present.ConversationsUi
import dev.zeffin.ui.present.Tone

/**
 * Selecting several conversations: the same list with a check on each row, the bar with the count,
 * Select all and an X to leave, the tabs dimmed, and a bottom bar whose actions apply to every selected one.
 */
@Composable
fun ConversationsSelecting(ui: ConversationsUi, selection: Selecting, searching: Boolean = false, onQuery: (String) -> Unit = {}) {
    val l = LocalLang.current
    val a = selection.actions
    androidx.activity.compose.BackHandler(onBack = a.onLeave)
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(FbColors.accent).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(FbIcons.close, selection.words.leave, onClick = a.onLeave)
            Column(Modifier.weight(1f)) {
                Txt(selection.ui.title, FbType.title, color = FbColors.onAccent, maxLines = 1)
                Txt(selection.ui.sub, FbType.caption, color = FbColors.onAccent, maxLines = 1)
            }
            IconButton(FbIcons.selectAll, selection.words.selectAll, onClick = a.onSelectAll)
        }
        TabBand(listOf(l.t("All", "Todas"), l.t("Favorites", "Favoritas")), if (ui.favorites) 1 else 0, enabled = false) {}
        PageBody(Modifier.weight(1f)) {
            if (searching) UnderlineTextField(l.t("Search", "Buscar"), ui.query, onQuery, mono = false, keyboard = KeyboardType.Text)
            selection.deletions?.let { d ->
                dev.zeffin.ui.components.Banner(Tone.WARNING, d.title, d.text) {
                    ActionRow(FbIcons.refresh, l.t("Try again", "Reintentar"), onClick = a.onRetry)
                }
            }
            if (ui.summary != null && ui.favorites) Txt(ui.summary, FbType.caption, Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                color = FbColors.inkMuted)
            ConversationGroups(ui, RowClicks(null, a.onToggle, a.onToggle) { selection.words.toggle(it) })
        }
        SelectionBar(selection)
    }
}

/** The words of the selection's controls, in the app's language. */
data class SelectionWords(val leave: String, val selectAll: String, val pin: (Boolean) -> String, val favorite: (Boolean) -> String,
                     val rename: String, val copy: String, val delete: String, val toggle: (Boolean) -> String)

/** What the selection's controls do; each action applies to every selected conversation. */
data class SelectionActions(
    val onToggle: (String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onLeave: () -> Unit = {},
    val onPin: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** Selecting several conversations: the bar's words and counts, the actions, and what a deletion left behind. */
class Selecting(val ui: dev.zeffin.ui.present.SelectionUi, val words: SelectionWords, val actions: SelectionActions,
                val deletions: dev.zeffin.ui.present.DeletionsUi? = null)

/** A row's check while selecting: a ring, or the blue disc with the check. */
@Composable
internal fun Tick(on: Boolean) {
    Box(Modifier.size(24.dp).clip(CircleShape)
        .background(if (on) FbColors.action else androidx.compose.ui.graphics.Color.Transparent)
        .then(if (on) Modifier else Modifier.border(2.dp, FbColors.lineStrong, CircleShape)), contentAlignment = Alignment.Center) {
        if (on) Icon(FbIcons.check, FbColors.onAction, size = 16.dp)
    }
}

/** In place of the bottom navigation while selecting: Pin, Favorite, Rename and Copy ID (one only) and Delete. */
@Composable
private fun SelectionBar(s: Selecting) {
    Column {
        dev.zeffin.ui.components.Divider()
        Row(Modifier.fillMaxWidth().height(64.dp).background(FbColors.surfaceRaised)) {
            BarButton(if (s.ui.pin) FbIcons.pin else FbIcons.unpin, s.words.pin(s.ui.pin), onClick = s.actions.onPin)
            BarButton(if (s.ui.favorite) FbIcons.star else FbIcons.unstar, s.words.favorite(s.ui.favorite), onClick = s.actions.onFavorite)
            if (s.ui.single) {
                BarButton(FbIcons.pencil, s.words.rename, onClick = s.actions.onRename)
                BarButton(FbIcons.copy, s.words.copy, onClick = s.actions.onCopy)
            }
            if (s.ui.canDelete) BarButton(FbIcons.trash, s.words.delete, danger = true, onClick = s.actions.onDelete)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
                                                                  danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) FbColors.danger else FbColors.action
    Column(Modifier.weight(1f).fillMaxSize().clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Icon(icon, color)
        Txt(label, FbType.navLabel, color = color, maxLines = 1)
    }
}

/** Deleting several asks first: each title with its messages, the totals, and that a running turn stops first. */
@Composable
fun BoxScope.DeleteManySheet(ui: dev.zeffin.ui.present.DeleteManyUi, onDelete: () -> Unit, onDismiss: () -> Unit) {
    FbSheet(ui.title, ui.sub, onDismiss = onDismiss) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            for ((title, count) in ui.rows) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(title, FbType.body, Modifier.weight(1f), maxLines = 1)
                Txt(count, FbType.body, color = FbColors.inkMuted, maxLines = 1)
            }
        }
        Txt(ui.text, FbType.body, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), color = FbColors.inkMuted)
        dev.zeffin.ui.components.Divider()
        ActionRow(FbIcons.trash, ui.delete, danger = true, onClick = onDelete)
        dev.zeffin.ui.components.Divider()
        ActionRow(FbIcons.close, ui.keep, onClick = onDismiss)
    }
}
