// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.components

import dev.zeffin.ui.LocalLang
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.theme.color
import dev.zeffin.ui.present.Face
import dev.zeffin.ui.present.NavUi
import dev.zeffin.ui.present.Tone

@Composable
fun IconButton(icon: ImageVector, description: String, tint: Color = FbColors.onAccent, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(44.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, tint, description = description)
    }
}

/** Orange app bar with black title, like OrangeAppBar. */
@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /** A tap anywhere on the bar, with its label for TalkBack: the conversation's bar opens its settings. */
    onClick: Pair<String, () -> Unit>? = null,
    actions: @Composable RowScope.() -> Unit = {},
) = TopBar(Bar(title, subtitle, onBack = onBack, onClick = onClick), actions)

/**
 * What an app bar holds: its [title] and [subtitle], the character's [face] before the title (the icon's own look,
 * still, decoration: the subtitle says the state), Back, and a tap on the whole bar.
 */
data class Bar(
    val title: String,
    val subtitle: String? = null,
    val face: Face? = null,
    val onBack: (() -> Unit)? = null,
    val onClick: Pair<String, () -> Unit>? = null,
)

@Composable
fun TopBar(bar: Bar, actions: @Composable RowScope.() -> Unit = {}) {
    val onClick = bar.onClick
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(FbColors.accent)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClick.first, role = Role.Button, onClick = onClick.second) else Modifier)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val shift = if (bar.onBack != null) (-10).dp else 0.dp
        if (bar.onBack != null) IconButton(FbIcons.back, LocalLang.current.t("Back", "Volver"), modifier = Modifier.offset(x = shift), onClick = bar.onBack)
        if (bar.face != null) Character(bar.face, Modifier.offset(x = shift).padding(end = 4.dp), CharacterSize.bar, CharacterLook.OnAccent)
        Column(Modifier.weight(1f).offset(x = shift)) {
            Txt(bar.title, FbType.title, color = FbColors.onAccent, maxLines = 1)
            if (bar.subtitle != null) Txt(bar.subtitle, FbType.caption, color = FbColors.onAccent, maxLines = 1)
        }
        actions()
    }
}

/** Segmented control on the orange bar (ComposableTabswitch). */
@Composable
fun TabBand(tabs: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().background(FbColors.accent).padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.5f))
            .padding(2.dp)) {
            tabs.forEachIndexed { i, label ->
                val active = i == selected
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (active) FbColors.accent else Color.Transparent)
                        .clickable(enabled = enabled, role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(label, FbType.buttonSmall, color = if (active) FbColors.onAccent else Color.Black.copy(alpha = 0.72f))
                }
            }
        }
    }
}

enum class Tab { HOME, CONVERSATIONS, SETTINGS }

/** Bottom bar like ComposeBottomBar; the first tab's label is the connection state, and its face says it too. */
@Composable
fun BottomNav(active: Tab, nav: NavUi, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(FbColors.surfaceRaised)) {
        NavItem(nav.statusLabel, FbIcons.homeFace(nav.face, active == Tab.HOME), active == Tab.HOME, status = !nav.off) { onSelect(Tab.HOME) }
        NavItem(LocalLang.current.t("Conversations", "Conversaciones"), if (active == Tab.CONVERSATIONS) FbIcons.chatOn else FbIcons.chatOff,
            active == Tab.CONVERSATIONS) { onSelect(Tab.CONVERSATIONS) }
        NavItem(LocalLang.current.t("Settings", "Ajustes"), if (active == Tab.SETTINGS) FbIcons.settingsOn else FbIcons.settingsOff,
            active == Tab.SETTINGS) { onSelect(Tab.SETTINGS) }
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: ImageVector, active: Boolean, status: Boolean = false, onClick: () -> Unit) {
    val text = when {
        !active -> FbColors.inkMuted
        status -> FbColors.action
        else -> FbColors.ink
    }
    val pill = when {
        !active -> Color.Transparent
        status -> FbColors.action
        else -> FbColors.line
    }
    val iconColor = if (active && status) FbColors.onAction else text
    Column(
        Modifier.weight(1f).fillMaxSize().clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(width = 48.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)).background(pill), contentAlignment = Alignment.Center) {
            Icon(icon, iconColor)
        }
        Txt(label, FbType.navLabel, color = text, maxLines = 1)
    }
}

/** Bottom sheet over a scrim; Back closes it, not the screen under it. */
@Composable
fun BoxScope.FbSheet(title: String, sub: String? = null, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClickLabel = LocalLang.current.t("Close", "Cerrar"), onClick = onDismiss))
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(FbColors.surfaceRaised)
            .clickable(enabled = false) {}.padding(bottom = 14.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 4.dp).size(width = 32.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp)).background(FbColors.lineStrong))
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, FbType.heading)
            if (sub != null) Txt(sub, FbType.caption, color = FbColors.inkMuted)
        }
        Divider()
        content()
    }
}

/**
 * Panel that unfolds from the app bar, over a scrim that leaves the bar uncovered: [top] is the bar's height, [width] the
 * bar's when it does not span the screen. Its content scrolls when it does not fit. With [onBack] the header shows an arrow
 * and Back goes there; otherwise Back closes the panel.
 */
@Composable
fun BoxScope.FbPanel(title: String, sub: String? = null, top: Dp, width: Dp? = null, onBack: (() -> Unit)? = null,
                     onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack ?: onDismiss)
    val l = LocalLang.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = top)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClickLabel = l.t("Close", "Cerrar"), onClick = onDismiss))
        val still = LocalStillFrames.current
        val shown = remember { MutableTransitionState(still).apply { targetState = true } }
        AnimatedVisibility(shown, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn()) {
            Column(
                (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).heightIn(max = maxHeight - 48.dp)
                    .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)).background(FbColors.surfaceRaised)
                    .clickable(enabled = false) {},
            ) {
                Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 2.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) IconButton(FbIcons.back, l.t("Back", "Volver"), tint = FbColors.ink, onClick = onBack)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Txt(title, FbType.heading)
                        if (sub != null) Txt(sub, FbType.caption, color = FbColors.inkMuted)
                    }
                }
                Divider()
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 14.dp), content = content)
            }
        }
    }
}

/** Underlined text field. */
@Composable
fun UnderlineTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String? = null,
    error: String? = null,
    secret: Boolean = false,
    mono: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Uri,
) {
    var focused by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val lineColor = when {
        error != null -> FbColors.danger
        focused -> FbColors.action
        else -> FbColors.lineStrong
    }
    val lineWidth = if (error != null || focused) 2.dp else 1.dp
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(label, FbType.caption, color = FbColors.inkMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value, onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).onFocusChanged { focused = it.isFocused }
                    .drawBehind {
                        val w = lineWidth.toPx()
                        drawRect(lineColor, topLeft = Offset(0f, size.height - w), size = androidx.compose.ui.geometry.Size(size.width, w))
                    },
                textStyle = if (mono) FbType.mono else FbType.body.copy(fontSize = FbType.heading.fontSize, lineHeight = FbType.heading.lineHeight),
                singleLine = true,
                cursorBrush = SolidColor(FbColors.action),
                visualTransformation = if (secret && !shown) PasswordVisualTransformation('•') else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
                decorationBox = { inner -> Box(Modifier.padding(top = 12.dp, bottom = 10.dp)) { inner() } },
            )
            if (secret) {
                IconButton(if (shown) FbIcons.eyeOff else FbIcons.eye, if (shown) LocalLang.current.t("Hide the token", "Ocultar el token") else LocalLang.current.t("Show the token", "Mostrar el token"),
                    tint = FbColors.inkMuted, modifier = Modifier.offset(x = 8.dp)) { shown = !shown }
            }
        }
        val h = error ?: hint
        if (h != null) Txt(h, FbType.caption, color = if (error != null) FbColors.danger else FbColors.inkFaint)
    }
}

/** A bonded device: a card with a Use button. */
@Composable
fun DeviceCard(name: String, address: String, looksLikeFlipper: Boolean, current: Boolean, onUse: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(10.dp)).background(FbColors.surfaceRaised),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(76.dp).height(68.dp).drawBehind {
            drawRect(FbColors.line, topLeft = Offset(size.width - 1.dp.toPx(), 0f),
                size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height))
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
            Icon(if (looksLikeFlipper) FbIcons.flipper else FbIcons.bluetooth, FbColors.inkMuted, size = 24.dp)
            Txt(if (looksLikeFlipper) "Flipper" else LocalLang.current.t("Other", "Otro"), FbType.label, color = FbColors.inkMuted)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Txt(name, FbType.deviceName, maxLines = 1)
            Txt(address, FbType.monoSmall, color = FbColors.inkMuted, maxLines = 1)
        }
        Box(Modifier.padding(end = 12.dp)) {
            if (current) StateText(Tone.ACTION, LocalLang.current.t("In use", "En uso")) else FbButton(LocalLang.current.t("Use", "Usar"), small = true, onClick = onUse)
        }
    }
}

/** One agent action on the Flipper (the chat's tool card). */
@Composable
fun ToolCard(title: String, text: String?, meta: String?, error: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FbColors.surfaceRaised).padding(12.dp)) {
        Txt(title, FbType.monoSmall, color = if (error) FbColors.danger else FbColors.accentStrong, maxLines = 1)
        if (text != null) Txt(text, FbType.body, maxLines = 3)
        if (meta != null) Txt(meta, FbType.caption, color = FbColors.inkMuted, maxLines = 1)
    }
}

/** Pause and resume the agent (the server's gate). */
/** Home's gate row: Pause and Resume, and the remote for the Flipper with its label when [remote] is set. */
class GateActions(val onPause: () -> Unit, val onResume: () -> Unit, val remote: Pair<String, () -> Unit>? = null)

@Composable
fun GateControls(paused: Boolean, enabled: Boolean = true, actions: GateActions, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(FbColors.surface).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // three pills share the row: less side padding, so "Pause agent" fits whole ("Pausar agente" did not: "Pausar")
        CompositionLocalProvider(LocalTightPills provides (actions.remote != null)) {
            FbButton(LocalLang.current.t("Pause agent", "Pausar"), Modifier.weight(1f), ButtonKind.WARNING, enabled = enabled && !paused,
                onClick = actions.onPause)
            FbButton(LocalLang.current.t("Resume", "Reanudar"), Modifier.weight(1f), ButtonKind.SUCCESS, enabled = enabled && paused,
                onClick = actions.onResume)
            actions.remote?.let { (label, open) -> FbButton(label, Modifier.weight(1f), ButtonKind.PRIMARY, onClick = open) }
        }
    }
}
