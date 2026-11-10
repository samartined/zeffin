// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import dev.zeffin.ui.LocalLang
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.zeffin.link.PermissionAnswer
import dev.zeffin.ui.components.ActionRow
import dev.zeffin.ui.components.InputRow
import dev.zeffin.ui.components.BottomNav
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.FlipperScreen
import dev.zeffin.ui.components.Icon
import dev.zeffin.ui.components.GateActions
import dev.zeffin.ui.components.GateControls
import dev.zeffin.ui.components.Tab
import dev.zeffin.ui.components.ToolCard
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.present.ScreenMode
import dev.zeffin.ui.components.StateText
import dev.zeffin.ui.components.ScreenBadge
import dev.zeffin.ui.components.CharacterSize
import dev.zeffin.ui.components.CharacterLook
import dev.zeffin.ui.components.Character
import dev.zeffin.ui.components.Bar
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.theme.color
import dev.zeffin.ui.present.FeedItem
import dev.zeffin.ui.present.HomeUi
import dev.zeffin.ui.present.LastConversationUi
import dev.zeffin.ui.present.Offline

/**
 * Home is the Flipper's panel: the screen large, Pause and Resume, every agent's actions and, above them, the last
 * conversation. A conversation itself opens on its own screen (Conversation.kt).
 */
/** Home's Flipper screen as drawn, and the layout. */
class HomeView(val frame: ByteArray?, val orientation: Int, val landscape: Boolean)

/** What Home's taps do: the gate row (with the remote), the relay and server settings, the tabs and the card. */
class HomeActions(
    val gate: GateActions,
    val onOpenRelay: () -> Unit,
    val onOpenServer: () -> Unit,
    val onTab: (Tab) -> Unit,
    val card: CardActions = CardActions(),
)

/** The last conversation's card: open it, start a new one, answer a permission. */
class CardActions(
    val onOpenConversation: (String?) -> Unit = {},
    val onNewConversation: () -> Unit = {},
    val onAnswer: (String, PermissionAnswer) -> Unit = { _, _ -> },
)

@Composable
fun HomeScreen(ui: HomeUi, view: HomeView, actions: HomeActions, last: LastConversationUi? = null) {
    val landscape = view.landscape
    val l = LocalLang.current
    val screen = @Composable { HomeFlipperScreen(ui, view) }
    val controls = @Composable { modifier: Modifier ->
        if (ui.offline == null) GateControls(ui.paused, ui.gateEnabled, actions.gate, modifier)
        else Column(modifier.padding(14.dp)) {
            FbCard {
                when (ui.offline) {
                    Offline.NO_RELAY -> item { ActionRow(FbIcons.settings, l.t("Open the relay settings", "Abrir los ajustes del relé"), onClick = actions.onOpenRelay) }
                    Offline.NO_SERVER -> item { ActionRow(FbIcons.settings, l.t("Open the server settings", "Abrir los ajustes del servidor"), onClick = actions.onOpenServer) }
                    Offline.NOT_PAIRED -> item { ActionRow(FbIcons.qr, l.t("Pair with the server", "Emparejar con el servidor"), onClick = actions.onOpenServer) }
                    Offline.PAIR_AGAIN -> item { ActionRow(FbIcons.qr, l.t("Pair with QR code", "Emparejar con el QR"), onClick = actions.onOpenServer) }
                }
                // without a server the remote still drives the Flipper over the relay's own link
                ui.useRemote?.let { label -> actions.gate.remote?.let { (_, open) -> item { ActionRow(FbIcons.flipper, label, onClick = open) } } }
            }
        }
    }
    val cardAndFeed = @Composable { modifier: Modifier ->
        // A card taller than the room (a long request and its rules) scrolls, so every button can be reached.
        BoxWithConstraints(modifier) {
            val room = maxHeight
            Column(Modifier.fillMaxSize()) {
                if (last != null) Box(Modifier.heightIn(max = room).verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, top = if (landscape) 14.dp else 0.dp)) {
                    LastConversationCard(last, actions.card.onOpenConversation, actions.card.onNewConversation, actions.card.onAnswer)
                }
                Feed(ui.feed, Modifier.weight(1f))
            }
        }
    }
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        if (landscape) {
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(392.dp).fillMaxSize()) {
                    TopBar(Bar(ui.title, ui.subtitle, ui.face))
                    screen()
                    Box(Modifier.weight(1f))
                    controls(Modifier)
                }
                Box(Modifier.fillMaxSize().padding(start = 1.dp).background(FbColors.surface)) {
                    Box(Modifier.fillMaxHeight().width(1.dp).background(FbColors.line).align(Alignment.CenterStart))
                    cardAndFeed(Modifier.fillMaxSize())
                }
            }
        } else {
            TopBar(Bar(ui.title, ui.subtitle, ui.face))
            screen()
            controls(Modifier)
            cardAndFeed(Modifier.weight(1f))
            BottomNav(Tab.HOME, ui.nav, actions.onTab)
        }
    }
}

@Composable
private fun LastConversationCard(ui: LastConversationUi, onOpen: (String?) -> Unit, onNew: () -> Unit,
                                 onAnswer: (String, PermissionAnswer) -> Unit) {
    val l = LocalLang.current
    FbCard {
        // The head stands for the card's title: no line between it and the first row, as before the character.
        item { CardHead(ui) }
        var first = true
        fun row(content: @Composable () -> Unit) {
            item(divider = !first, content = content)
            first = false
        }
        if (ui.line != null) row { Txt(ui.line, FbType.body, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp), color = FbColors.inkMuted, maxLines = 1) }
        val p = ui.permission
        if (p != null) {
            for ((label, value) in p.rows + p.rules) row { InputRow(label, value, l.t("Scroll to read it all", "Desplaza para leerlo entero"), maxHeight = 120.dp) }
            val tooLong = p.tooLong
            if (tooLong != null) row { Txt(tooLong, FbType.body, Modifier.padding(12.dp), color = FbColors.warning) }
            else row { ActionRow(FbIcons.check, l.t("Allow once", "Permitir una vez")) { onAnswer(p.id, PermissionAnswer.ONCE) } }
            if (p.always) row {
                ActionRow(FbIcons.check, l.t("Always allow in this conversation", "Permitir siempre en esta conversación")) { onAnswer(p.id, PermissionAnswer.ALWAYS) }
            }
            row { ActionRow(FbIcons.close, l.t("Deny", "Denegar"), danger = true) { onAnswer(p.id, PermissionAnswer.DENY) } }
        }
        if (!ui.isNew) row { ActionRow(FbIcons.open, l.t("Open the conversation", "Abrir la conversación")) { onOpen(ui.id) } }
        // While a permission waits, a new conversation would only wait too: one turn at a time on the server.
        if (p == null) row { ActionRow(FbIcons.plus, l.t("New conversation", "Conversación nueva"), onClick = onNew) }
    }
}

/** The Flipper's screen; without an image, the character says why in the dark LCD, above the badge. */
@Composable
private fun HomeFlipperScreen(ui: HomeUi, view: HomeView) {
    Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp), contentAlignment = Alignment.Center) {
        // Without an image, the character says why in the dark LCD, above the badge (offline or connecting).
        val face = ui.screenMode == ScreenMode.OFF
        FlipperScreen(view.frame, view.orientation, ui.screenMode, ui.badge.takeUnless { face }, scale = 2)
        if (face) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Character(ui.face, height = CharacterSize.stage, look = CharacterLook.Dark(FbColors.line))
            ui.badge?.let { ScreenBadge(it) }
        }
    }
}

/** The card's head: the character, then the title and what the agent does in words under it. */
@Composable
private fun CardHead(ui: LastConversationUi) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Character(ui.character, height = CharacterSize.card, look = CharacterLook.Dark(FbColors.surfaceRaised))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(ui.title, FbType.heading, Modifier.weight(1f, fill = false), maxLines = 2)
                ui.remote?.let { label -> Icon(FbIcons.remote, FbColors.info, size = 16.dp, description = label) }
            }
            ui.state?.let { (tone, text) -> StateText(tone, text) }
        }
    }
}

/** Every agent's actions on the Flipper, newest at the bottom, with the gate's lines. */
@Composable
private fun Feed(feed: List<FeedItem>, modifier: Modifier) {
    LazyColumn(
        modifier.fillMaxWidth(),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
    ) {
        items(feed.asReversed()) { f ->
            when {
                f.action != null -> ToolCard(f.action.title, f.action.text, f.action.meta, f.action.error)
                f.line != null -> Txt(f.line.second, FbType.label, Modifier.fillMaxWidth(), color = f.line.first.color(), align = TextAlign.Center)
            }
        }
    }
}
