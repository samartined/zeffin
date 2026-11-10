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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.zeffin.link.Effort
import dev.zeffin.link.Mode
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.FlipperScreen
import dev.zeffin.ui.components.Icon
import dev.zeffin.ui.components.IconButton
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.theme.color
import dev.zeffin.ui.present.ChatItem
import dev.zeffin.ui.present.ChatUi
import dev.zeffin.ui.present.ConversationSettingsUi
import dev.zeffin.ui.present.FeedItem
import dev.zeffin.ui.present.HomeUi
import dev.zeffin.ui.present.PresentRemote
import dev.zeffin.ui.components.Bar
import dev.zeffin.ui.components.Character
import dev.zeffin.ui.components.CharacterSize
import dev.zeffin.ui.present.CharacterUi
import dev.zeffin.ui.present.Face
import dev.zeffin.ui.present.Tone

/** A conversation's screen: what it shows, the open sheet, and how the app answers. */
class ConversationChat(
    val ui: ChatUi,
    val draft: String,
    val callbacks: ChatCallbacks,
    val sheet: ChatSheet = ChatSheet.NONE,
    val onSheet: (ChatSheet) -> Unit = {},
    val onEffort: (Effort) -> Unit = {},
    val onModel: (String) -> Unit = {},
    val onMode: (Mode) -> Unit = {},
    /** Remote Control on or off in this conversation. */
    val onRemote: (Boolean) -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    /** Above the composer: the files attached to the next message. */
    val tray: (@Composable () -> Unit)? = null,
    /** The Attach sheet's content. */
    val attachSheet: (@Composable BoxScope.() -> Unit)? = null,
    /** The sheet of a tapped link. */
    val linkSheet: (@Composable BoxScope.() -> Unit)? = null,
    /** Deleting this conversation asks first; without it (an older server) the settings have no Delete. */
    val deleteSheet: (@Composable BoxScope.() -> Unit)? = null,
    /** Over everything: the image viewer. */
    val overlay: (@Composable BoxScope.() -> Unit)? = null,
    /** For screenshots: draw the effort dial while dragging, and the keyboard as open. */
    val dialDragging: Boolean = false,
    val keyboardOpen: Boolean? = null,
    /** The agent's remote access as the server names it, for the turn-off sheet's words. */
    val remote: dev.zeffin.link.RemoteCapability? = null,
)

/**
 * A conversation on its own screen: the app bar holds the title and the
 * settings line and opens the settings; the Flipper screen is small in a strip, large after a tap, hidden while
 * typing; Pause lives on Home, so here there is only the turn's stop. In landscape the screen is large on the left.
 */
@Composable
fun ConversationScreen(
    home: HomeUi,
    chat: ConversationChat,
    frame: ByteArray?,
    orientation: Int,
    landscape: Boolean,
    large: Boolean,
    onToggleScreen: () -> Unit,
    onBack: () -> Unit,
) {
    val l = LocalLang.current
    val density = LocalDensity.current
    val typing = chat.keyboardOpen ?: (WindowInsets.ime.getBottom(density) > 0)
    val canSet = chat.ui.available && chat.ui.settings != null
    val focus = LocalFocusManager.current
    // The settings unfold from the bar, so the same button closes them; opening them puts the keyboard away.
    val openSettings = {
        if (chat.sheet in settingsSheets) chat.onSheet(ChatSheet.NONE)
        else if (canSet) { focus.clearFocus(); chat.onSheet(ChatSheet.SETTINGS) }
    }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    // Sending jumps to the end of the chat, even when reading further up, so the answer is not missed.
    val feed = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val callbacks = remember(chat.callbacks) {
        chat.callbacks.copy(onSend = { chat.callbacks.onSend(); scope.launch { feed.scrollToItem(0) } })
    }
    val bar = @Composable {
        val label = l.t("Conversation settings", "Ajustes de la conversación")
        TopBar(Bar(chat.ui.title, chat.ui.settingsLine.takeIf { canSet }, chat.ui.face, onBack, if (canSet) label to openSettings else null)) {
            val remote = chat.ui.settings?.remote
            if (chat.ui.remoteOn && remote != null) RemotePill(remote.iconLabel, onClick = openSettings)
            if (canSet) IconButton(FbIcons.tune, label, onClick = openSettings)
        }
    }
    val measuredBar = @Composable { Box(Modifier.onSizeChanged { barSize = it }) { bar() } }
    val talk = @Composable { modifier: Modifier ->
        Column(modifier) {
            ChatFeed(chat, feed, home.feed.filter { it.line != null }, Modifier.weight(1f))
            chat.ui.banner?.let { b -> ChatBannerView(b, chat.callbacks.onBanner) }
            if (chat.ui.available) {
                chat.tray?.invoke()
                Composer(chat.ui.composer, chat.draft, callbacks)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (landscape) {
            Row(Modifier.fillMaxSize().background(FbColors.surface)) {
                Column(Modifier.width(392.dp).fillMaxHeight()) {
                    measuredBar()
                    Box(Modifier.fillMaxWidth().weight(1f).padding(14.dp), contentAlignment = Alignment.Center) {
                        FlipperScreen(frame, orientation, home.screenMode, home.badge, scale = 2)
                    }
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(FbColors.line))
                talk(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize().background(FbColors.surface)) {
                measuredBar()
                if (!typing) {
                    if (large) LargeScreen(home, frame, orientation, onToggleScreen)
                    else ScreenStrip(home, frame, orientation, onToggleScreen)
                }
                talk(Modifier.weight(1f))
            }
        }
        val panel = with(density) { BarArea(barSize.height.toDp(), if (landscape) barSize.width.toDp() else null) }
        ConversationSheets(chat, panel)
        chat.overlay?.invoke(this)
    }
}

/**
 * Remote Control is on: its signal in a blue pill, like the bottom bar's active item; a tap opens the settings,
 * where its switch is.
 */
@Composable
private fun RemotePill(label: String, onClick: () -> Unit) {
    Box(Modifier.width(52.dp).height(44.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.width(48.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(FbColors.action),
            contentAlignment = Alignment.Center) {
            Icon(FbIcons.remote, FbColors.onAction, size = 22.dp, description = label)
        }
    }
}

/** The Flipper screen at 1x with two lines; a tap makes it large. */
@Composable
private fun ScreenStrip(home: HomeUi, frame: ByteArray?, orientation: Int, onToggle: () -> Unit) {
    val l = LocalLang.current
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = l.t("Show the Flipper screen larger", "Ver la pantalla del Flipper en grande"),
            role = Role.Button, onClick = onToggle).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlipperScreen(frame, orientation, home.screenMode, home.badge, scale = 1)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(home.stripTitle, FbType.bodyStrong, maxLines = 1)
                Txt(home.stripSub + l.t(" · tap to enlarge", " · toca para ampliar"), FbType.caption, color = FbColors.inkFaint, maxLines = 2)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FbColors.line))
    }
}

/** The Flipper screen at 2x; a tap, or the arrow, makes it small again. */
@Composable
private fun LargeScreen(home: HomeUi, frame: ByteArray?, orientation: Int, onToggle: () -> Unit) {
    val l = LocalLang.current
    val label = l.t("Show the Flipper screen smaller", "Ver la pantalla del Flipper en pequeño")
    Column {
        Box(Modifier.fillMaxWidth().padding(14.dp)) {
            Box(Modifier.align(Alignment.Center).clickable(onClickLabel = label, role = Role.Button, onClick = onToggle)) {
                FlipperScreen(frame, orientation, home.screenMode, home.badge, scale = 2)
            }
            IconButton(FbIcons.chevronDown, label, modifier = Modifier.align(Alignment.TopEnd).rotate(180f), onClick = onToggle)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FbColors.line))
    }
}

/** The sheets that belong to the settings, unfolding from the app bar. */
private val settingsSheets = setOf(ChatSheet.SETTINGS, ChatSheet.MODEL, ChatSheet.MODE, ChatSheet.AGENT)

/** Where a settings list opens: under the app bar, with the settings' subtitle; Back returns to the settings. */
internal class PanelPlace(val sub: String, val bar: BarArea, val onBack: () -> Unit, val onDismiss: () -> Unit)

private fun place(chat: ConversationChat, settings: ConversationSettingsUi, bar: BarArea) =
    PanelPlace(settings.sub, bar, onBack = { chat.onSheet(ChatSheet.SETTINGS) }, onDismiss = { chat.onSheet(ChatSheet.NONE) })

/** Where the app bar is, for the settings panel: its height, and its width when it does not span the screen. */
internal data class BarArea(val height: Dp, val width: Dp?)

@Composable
private fun BoxScope.ConversationSheets(chat: ConversationChat, bar: BarArea) {
    val l = LocalLang.current
    if (chat.sheet == ChatSheet.LINK) return chat.linkSheet?.invoke(this) ?: Unit
    if (chat.sheet == ChatSheet.DELETE) return chat.deleteSheet?.invoke(this) ?: Unit
    if (chat.sheet == ChatSheet.REMOTE_OFF) {
        val r = PresentRemote(l, chat.remote)
        return RemoteOffSheet(r.offTitle, r.offSub, r.offText, r.offStop, r.offKeep,
            onStop = { chat.onRemote(false); chat.onSheet(ChatSheet.NONE) }, onKeep = { chat.onSheet(ChatSheet.SETTINGS) })
    }
    val settings = chat.ui.settings ?: return
    when (chat.sheet) {
        ChatSheet.NONE -> {}
        ChatSheet.SETTINGS -> ConversationSettingsPanel(settings, bar.height, bar.width, chat.onSheet, chat.onEffort,
            { settings.effortWords.getValue(it) }, chat.dialDragging, canDelete = chat.deleteSheet != null,
            onRemote = chat.onRemote)
        ChatSheet.MODEL, ChatSheet.MODE, ChatSheet.AGENT -> SettingsList(chat, settings, bar)
        ChatSheet.ATTACH -> chat.attachSheet?.invoke(this)
        ChatSheet.LINK, ChatSheet.DELETE, ChatSheet.REMOTE_OFF -> {}  // drawn above, without the settings
    }
}

/** A list the settings open in the same panel (model, mode, agent): picking one goes back to the settings. */
@Composable
private fun BoxScope.SettingsList(chat: ConversationChat, settings: ConversationSettingsUi, bar: BarArea) {
    val l = LocalLang.current
    val back = { chat.onSheet(ChatSheet.SETTINGS) }
    when (chat.sheet) {
        ChatSheet.MODEL -> OptionPanel(l.t("Model", "Modelo"), settings.models, settings.modelNote, place(chat, settings, bar)) {
            chat.onModel(it); back()
        }
        ChatSheet.MODE -> OptionPanel(l.t("Permission mode", "Modo de permisos"), settings.modes, settings.modeNote, place(chat, settings, bar)) {
            chat.onMode(it); back()
        }
        else -> settings.agent?.let { a ->
            OptionPanel(l.t("Agent", "Agente"), a.options, a.note, PanelPlace(a.sub, bar, onBack = back, onDismiss = { chat.onSheet(ChatSheet.NONE) })) {
                chat.callbacks.onAgent(it); back()
            }
        }
    }
}

/** The conversation, newest at the bottom; the gate's lines stay at the end. */
@Composable
private fun ChatFeed(chat: ConversationChat, state: LazyListState, lines: List<FeedItem>, modifier: Modifier) {
    val l = LocalLang.current
    val items = chat.ui.items
    val last = items.lastOrNull()
    val lastSize = (last as? ChatItem.Claude)?.text?.length ?: 0
    // A message of mine (sent from here or elsewhere) brings the end into view; then the answer is followed as it streams.
    val lastMine = items.lastOrNull { it is ChatItem.Mine }?.key
    LaunchedEffect(lastMine) { state.scrollToItem(0) }
    LaunchedEffect(last?.key, lastSize, items.size) {
        if (state.firstVisibleItemIndex <= 1) state.scrollToItem(0)
    }
    val nearTop by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.layoutInfo.totalItemsCount - 3 } ?: false } }
    LaunchedEffect(nearTop) { if (nearTop && items.isNotEmpty()) chat.onLoadOlder() }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = state,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
    ) {
        chat.ui.tail?.let { tail -> item(key = "tail") { CharacterTail(tail) } }
        items(lines.asReversed()) { f -> f.line?.let { Txt(it.second, FbType.label, Modifier.fillMaxWidth(), color = it.first.color(), align = TextAlign.Center) } }
        items(items.asReversed(), key = { it.key }) { item -> ChatItemView(item, chat.callbacks) }
        chat.ui.empty?.let { hint ->
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Character(Face.ACTIVE, height = CharacterSize.stage)
                    Txt(hint, FbType.label, Modifier.fillMaxWidth(), color = FbColors.inkFaint, align = TextAlign.Center)
                }
            }
        }
        chat.ui.agentCard?.let { a ->
            item {
                dev.zeffin.ui.components.FbCard(Modifier.padding(bottom = 4.dp)) {
                    item { dev.zeffin.ui.components.SettingRow(l.t("Agent", "Agente"), desc = a.desc, value = a.name,
                        onClick = { chat.onSheet(ChatSheet.AGENT) }) }
                }
            }
        }
    }
}

/** The chat's tail: the character acting out what the agent does, and the same in words (Flipper data in mono, quoted). */
@Composable
private fun CharacterTail(ui: CharacterUi) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Character(ui, height = CharacterSize.tail)
        val line = ui.line.orEmpty()
        val text = androidx.compose.ui.text.buildAnnotatedString {
            append(line)
            ui.data?.let { d ->
                if (line.isNotEmpty()) append(' ')
                pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = FbType.mono.fontFamily, fontWeight = FbType.mono.fontWeight,
                    color = FbColors.ink))
                append(if (ui.quote) "\"$d\"" else d)
                pop()
            }
        }
        androidx.compose.foundation.text.BasicText(text, Modifier.weight(1f),
            style = FbType.body.copy(color = if (ui.tone == Tone.NEUTRAL) FbColors.inkMuted else ui.tone.color()))
    }
}
