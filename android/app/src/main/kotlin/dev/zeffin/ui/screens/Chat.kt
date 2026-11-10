// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zeffin.link.Effort
import dev.zeffin.link.Frame
import dev.zeffin.link.PermissionAnswer
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.LocalMarkdownClean
import dev.zeffin.ui.Markdown
import dev.zeffin.ui.MarkdownText
import dev.zeffin.ui.components.ActionRow
import dev.zeffin.ui.components.Banner
import dev.zeffin.ui.components.emphasized
import dev.zeffin.ui.components.EffortDial
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.FbPanel
import dev.zeffin.ui.components.FbSheet
import dev.zeffin.link.FlipperFrame
import dev.zeffin.ui.components.flipperBitmap
import dev.zeffin.ui.components.Icon
import dev.zeffin.ui.components.InfoRow
import dev.zeffin.ui.components.InputRow
import dev.zeffin.ui.components.Note
import dev.zeffin.ui.components.SettingRow
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.theme.color
import dev.zeffin.ui.present.AttachmentUi
import dev.zeffin.ui.present.ChatAction
import dev.zeffin.ui.present.ChatBanner
import dev.zeffin.ui.present.ChatItem
import dev.zeffin.ui.present.ComposerUi
import dev.zeffin.ui.present.ConversationSettingsUi
import dev.zeffin.ui.present.OptionUi
import dev.zeffin.ui.components.Character
import dev.zeffin.ui.components.CharacterSize
import dev.zeffin.ui.present.CharacterUi
import dev.zeffin.ui.present.Tone

/** What a conversation asks of the app. */
data class ChatCallbacks(
    val onDraft: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onInterrupt: () -> Unit = {},
    val onAttach: () -> Unit = {},
    val onAnswer: (String, PermissionAnswer) -> Unit = { _, _ -> },
    val onRetryManual: () -> Unit = {},
    val onBanner: (ChatAction) -> Unit = {},
    val onOpenAttachment: (AttachmentUi) -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    /** A link tapped in the agent's text: a sheet shows its address before anything opens. */
    val onLink: (String) -> Unit = {},
    /** Copy on a code block. */
    val onCopy: (String) -> Unit = {},
    /** Another agent for a conversation nothing was written in yet. */
    val onAgent: (String) -> Unit = {},
    /** TalkBack's words for the icon on what came from another device (Remote Control). */
    val remoteLabel: String = "",
    val attachmentContent: @Composable (AttachmentUi, Modifier) -> Unit = { a, m -> MessageAttachment(a, m) },
)

private val bubbleMine = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 10.dp, bottomEnd = 4.dp)
private val bubbleClaude = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 4.dp, bottomEnd = 10.dp)

/** One line of the chat; its text can be selected and copied with a long press. */
@Composable
fun ChatItemView(item: ChatItem, callbacks: ChatCallbacks) {
    SelectionContainer { ChatItemContent(item, callbacks) }
}

@Composable
private fun ChatItemContent(item: ChatItem, callbacks: ChatCallbacks) {
    val l = LocalLang.current
    when (item) {
        is ChatItem.Mine -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(Modifier.fillMaxWidth(0.85f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.attachments.isNotEmpty()) MessageAttachments(item.attachments, callbacks)
                if (item.text.isNotBlank()) {
                    Box(Modifier.clip(bubbleMine).background(FbColors.infoSoft).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Txt(item.text, FbType.body)
                    }
                }
                if (item.meta != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt(item.meta, FbType.caption, color = FbColors.inkFaint)
                    if (item.remote) RemoteMark(callbacks.remoteLabel)
                }
            }
        }
        is ChatItem.Claude -> Row(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth(0.85f)) {
                Box(Modifier.fillMaxWidth().clip(bubbleClaude).background(FbColors.surfaceRaised).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    MarkdownText(item.text, streaming = item.streaming, onLink = callbacks.onLink, onCopy = callbacks.onCopy)
                }
            }
        }
        is ChatItem.Tool -> ToolCardWithScreen(item.title, item.text, item.meta, item.error, item.screen,
            remote = if (item.remote) callbacks.remoteLabel else null)
        is ChatItem.Waiting -> FbCard(title = item.title, state = item.state) {
            item { Txt(item.line, FbType.body, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp), color = FbColors.inkMuted) }
            for ((label, value) in item.rows) item { InfoRow(label, value, mono = true) }
        }
        is ChatItem.Ask -> FbCard(title = item.title, state = item.state) {
            for ((label, value) in item.rows + item.rules) item { InputRow(label, value, l.t("Scroll to read it all", "Desplaza para leerlo entero")) }
            val tooLong = item.tooLong
            if (tooLong != null) item { Txt(tooLong, FbType.body, Modifier.padding(12.dp), color = FbColors.warning) }
            else item { ActionRow(FbIcons.check, l.t("Allow once", "Permitir una vez")) { callbacks.onAnswer(item.id, PermissionAnswer.ONCE) } }
            if (item.always) item {
                ActionRow(FbIcons.check, l.t("Always allow in this conversation", "Permitir siempre en esta conversación")) {
                    callbacks.onAnswer(item.id, PermissionAnswer.ALWAYS)
                }
            }
            item { ActionRow(FbIcons.close, l.t("Deny", "Denegar"), danger = true) { callbacks.onAnswer(item.id, PermissionAnswer.DENY) } }
        }
        is ChatItem.Plan -> FbCard(title = l.t("The agent proposes a plan", "El agente propone un plan"), state = item.state) {
            item { Box(Modifier.padding(12.dp)) { PlanText(item.plan, callbacks) } }
            if (item.state.second == l.t("Waiting", "Esperando")) {
                item { ActionRow(FbIcons.check, l.t("Approve and continue", "Aprobar y continuar")) { callbacks.onAnswer(item.id, PermissionAnswer.ONCE) } }
                item { ActionRow(FbIcons.close, l.t("Keep planning", "Seguir planificando"), danger = true) { callbacks.onAnswer(item.id, PermissionAnswer.DENY) } }
            }
        }
        is ChatItem.Blocked -> FbCard(title = l.t("Blocked by automatic mode", "Bloqueado por Automático"),
            state = dev.zeffin.ui.present.Tone.DANGER to l.t("Not run", "No ejecutado")) {
            for ((label, value) in item.rows) item { InfoRow(label, value, mono = label != l.t("Reason", "Motivo")) }
            if (item.canRetry) item { ActionRow(FbIcons.sync, l.t("Switch to Manual and retry", "Pasar a Manual y reintentar"), onClick = callbacks.onRetryManual) }
        }
        is ChatItem.Line -> if (item.face != null) TurnEndLine(item, item.face)
            else Txt(item.text, FbType.label, Modifier.fillMaxWidth(), color = item.tone.color(), align = TextAlign.Center)
    }
}

/** A plan is input the owner approves: what Markdown decodes in it is cleaned like that input (PresentChat). */
@Composable
private fun PlanText(plan: String, callbacks: ChatCallbacks) {
    CompositionLocalProvider(LocalMarkdownClean provides Markdown.input) {
        MarkdownText(plan, onLink = callbacks.onLink, onCopy = callbacks.onCopy)
    }
}

/** The tool card with the returned Flipper screen as a thumbnail, as in the chat mockups. */
@Composable
fun ToolCardWithScreen(title: String, text: String?, meta: String?, error: Boolean, screen: Frame?, remote: String? = null) {
    val image = remember(screen) { screen?.let { FlipperFrame.decode(it.data, it.orientation) }?.let(::flipperBitmap) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FbColors.surfaceRaised).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (image != null) {
            val w = if (image.width > image.height) 128.dp else 64.dp
            val h = if (image.width > image.height) 64.dp else 128.dp
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(FbColors.screenPaper).padding(3.dp)) {
                Canvas(Modifier.size(w, h)) {
                    drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero,
                        filterQuality = FilterQuality.None)
                }
            }
        }
        Column {
            Txt(title, FbType.monoSmall, color = if (error) FbColors.danger else FbColors.accentStrong, maxLines = 1)
            if (text != null) Txt(text, FbType.body, maxLines = 3)
            if (meta != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(meta, FbType.caption, color = FbColors.inkMuted, maxLines = 1)
                if (remote != null) RemoteMark(remote)
            }
        }
    }
}

/** Written on another device (Remote Control): the signal icon in blue, small, in a meta line. */
@Composable
fun RemoteMark(label: String) {
    Icon(FbIcons.remote, FbColors.info, size = 13.dp, description = label)
}

/** The attachments of a sent message, above its bubble. */
@Composable
fun MessageAttachments(items: List<AttachmentUi>, callbacks: ChatCallbacks) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in row) callbacks.attachmentContent(a, Modifier.clickable { callbacks.onOpenAttachment(a) })
            }
        }
    }
}

/** A file attached to a message: an icon tile, the name and one gray line. */
@Composable
fun AttachmentChip(a: AttachmentUi, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null, progress: Float? = null,
                   thumb: (@Composable () -> Unit)? = null, error: Boolean = false) {
    Box(modifier.widthIn(max = 200.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, FbColors.line, RoundedCornerShape(10.dp))
        .background(FbColors.surface)) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = if (onRemove != null) 30.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(FbColors.surfaceSunken), contentAlignment = Alignment.Center) {
                if (thumb != null) thumb() else Icon(if (a.kind == dev.zeffin.link.AttachmentKind.IMAGE) FbIcons.image else FbIcons.file,
                    FbColors.inkMuted, size = 22.dp)
            }
            Column(Modifier.heightIn(max = 44.dp)) {
                Txt(a.name, FbType.chipName, Modifier.widthIn(max = 136.dp), maxLines = 1)
                Txt(a.meta, FbType.caption, color = if (error) FbColors.danger else FbColors.inkFaint, maxLines = 1)
            }
        }
        if (onRemove != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(RoundedCornerShape(50)).background(FbColors.surfaceRaised)
                .clickable(role = Role.Button, onClick = onRemove).semantics { contentDescription = "×" }, contentAlignment = Alignment.Center) {
                Icon(FbIcons.close, FbColors.inkMuted, size = 14.dp)
            }
        }
        if (progress != null) {
            Box(Modifier.align(Alignment.BottomStart).matchParentSize().padding(horizontal = 4.dp, vertical = 2.dp), contentAlignment = Alignment.BottomStart) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(FbColors.line)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(FbColors.action))
                }
            }
        }
    }
}

/** The bar under the chat: settings, text field with the clip, send or interrupt. */
@Composable
fun Composer(ui: ComposerUi, draft: String, callbacks: ChatCallbacks, modifier: Modifier = Modifier) {
    val l = LocalLang.current
    Row(modifier.fillMaxWidth().background(FbColors.surfaceRaised).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            BasicTextField(
                draft, callbacks.onDraft,
                enabled = ui.enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .semantics { contentDescription = l.t("Message for the agent", "Mensaje para el agente") },
                textStyle = FbType.body,
                maxLines = 5,
                cursorBrush = SolidColor(FbColors.action),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    // A fixed radius, half the one-line height: a pill on one line, and corners that stay clear of the text on more.
                    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(fieldShape).border(1.dp, FbColors.lineStrong, fieldShape)
                        .background(FbColors.surface).padding(start = 16.dp, end = if (ui.attachHidden) 16.dp else 44.dp, top = 12.dp, bottom = 12.dp),
                        contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) Txt(l.t("Message the agent…", "Escribe al agente…"), FbType.body, color = FbColors.inkMuted, maxLines = 1)
                        inner()
                    }
                },
            )
            if (!ui.attachHidden) Box(Modifier.align(Alignment.BottomEnd).padding(end = 2.dp).size(40.dp, 44.dp)
                .clickable(enabled = ui.canAttach, role = Role.Button, onClickLabel = l.t("Attach", "Adjuntar"), onClick = callbacks.onAttach),
                contentAlignment = Alignment.Center) {
                Icon(FbIcons.clip, if (ui.canAttach) FbColors.inkMuted else FbColors.line, size = 20.dp)
            }
        }
        val sendEnabled = ui.busy || (ui.canSend && (draft.isNotBlank() || ui.hasAttachments) && !ui.uploading)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(if (ui.busy || ui.canSend) FbColors.action else FbColors.line)
            .clickable(enabled = sendEnabled, role = Role.Button,
                onClickLabel = if (ui.busy) l.t("Interrupt the agent", "Interrumpir al agente") else l.t("Send", "Enviar"),
                onClick = if (ui.busy) callbacks.onInterrupt else callbacks.onSend),
            contentAlignment = Alignment.Center) {
            Icon(if (ui.busy) FbIcons.stopFill else FbIcons.send, FbColors.onAction, size = 22.dp)
        }
    }
}

private val fieldShape = RoundedCornerShape(22.dp)

/** A notice above the composer. */
@Composable
fun ChatBannerView(b: ChatBanner, onAction: (ChatAction) -> Unit) {
    Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        val actions: (@Composable ColumnScope.() -> Unit)? = if (b.actions.isEmpty()) null else ({
            for ((action, label) in b.actions) ActionRow(when (action) {
                ChatAction.STOP_BUSY -> FbIcons.stop
                ChatAction.OPEN_BUSY -> FbIcons.open
                ChatAction.PAIR -> FbIcons.qr
                ChatAction.CHOOSE_MODEL, ChatAction.CHOOSE_AGENT -> FbIcons.tune
                ChatAction.DISMISS -> FbIcons.check
            }, label, danger = action == ChatAction.STOP_BUSY) { onAction(action) }
        })
        if (b.text != null && b.strong != null && b.strong in b.text) Banner(b.tone, b.title, emphasized(b.text, b.strong), actions)
        else Banner(b.tone, b.title, b.text, actions = actions)
    }
}

enum class ChatSheet { NONE, SETTINGS, MODEL, MODE, ATTACH, LINK, DELETE, REMOTE_OFF, AGENT }

/**
 * Conversation settings, unfolding from the app bar ([top] is its height, [width] its width in landscape): model and
 * permission mode open their lists in the same panel; the effort is a dial in it.
 */
@Composable
fun BoxScope.ConversationSettingsPanel(ui: ConversationSettingsUi, top: Dp, width: Dp?, onSheet: (ChatSheet) -> Unit, onEffort: (Effort) -> Unit,
                                       labels: (Effort) -> Pair<String, String>, dragging: Boolean = false, canDelete: Boolean = false,
                                       onRemote: (Boolean) -> Unit = {}) {
    val l = LocalLang.current
    FbPanel(l.t("Conversation settings", "Ajustes de la conversación"), ui.sub, top, width, onDismiss = { onSheet(ChatSheet.NONE) }) {
        val locked = ui.locked != null  // Remote Control on: the running process keeps its own (no chevrons, no dial)
        ui.agent?.let { a ->
            SettingRow(l.t("Agent", "Agente"), desc = a.desc, value = a.name, onClick = if (a.canChange) ({ onSheet(ChatSheet.AGENT) }) else null)
            dev.zeffin.ui.components.Divider()
        }
        SettingRow(l.t("Model", "Modelo"), value = ui.modelLabel, onClick = if (locked) null else ({ onSheet(ChatSheet.MODEL) }))
        if (ui.effort != null && ui.efforts.isNotEmpty()) {
            if (locked) SettingRow(l.t("Effort", "Esfuerzo"), value = labels(ui.effort).first)
            else EffortDial(ui.efforts, ui.effort, { labels(it).first }, { labels(it).second }, onEffort, dragging = dragging)
        }
        SettingRow(l.t("Permission mode", "Modo de permisos"), value = ui.modeLabel, onClick = if (locked) null else ({ onSheet(ChatSheet.MODE) }))
        ui.remote?.let { r ->
            SettingRow(r.name, desc = r.desc, checked = r.on, onCheckedChange = { on ->
                if (!on && r.confirmOff) onSheet(ChatSheet.REMOTE_OFF) else onRemote(on)
            })
        }
        if (ui.locked != null) Note(ui.locked, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp))
        if (canDelete) {
            dev.zeffin.ui.components.Divider()
            ActionRow(FbIcons.trash, l.t("Delete this conversation", "Borrar esta conversación"), danger = true) { onSheet(ChatSheet.DELETE) }
        }
        Note(ui.note, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
    }
}

/** Turning Remote Control off while the agent works there: its turn stops, so it asks first. */
@Composable
fun BoxScope.RemoteOffSheet(title: String, sub: String, text: String, stop: String, keep: String, onStop: () -> Unit, onKeep: () -> Unit) {
    FbSheet(title, sub, onDismiss = onKeep) {
        Txt(text, FbType.body, Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), color = FbColors.inkMuted)
        ActionRow(FbIcons.back, stop, danger = true, onClick = onStop)
        dev.zeffin.ui.components.Divider()
        ActionRow(FbIcons.close, keep, onClick = onKeep)
    }
}

/** A list of choices with a check on the chosen one (model, permission mode); one not enabled is dimmed and cannot be picked. */
@Composable
fun <T> BoxScope.OptionSheet(title: String, options: List<OptionUi<T>>, note: String?, sub: String?, onPick: (T?) -> Unit) {
    FbSheet(title, sub, onDismiss = { onPick(null) }) { OptionList(options, note, onPick) }
}

/** The same list inside the conversation's settings panel: the arrow and Back return to the settings. */
@Composable
internal fun <T> BoxScope.OptionPanel(title: String, options: List<OptionUi<T>>, note: String?, place: PanelPlace, onPick: (T) -> Unit) {
    FbPanel(title, place.sub, place.bar.height, place.bar.width, onBack = place.onBack, onDismiss = place.onDismiss) {
        OptionList(options, note) { if (it != null) onPick(it) }
    }
}

@Composable
private fun <T> OptionList(options: List<OptionUi<T>>, note: String?, onPick: (T?) -> Unit) {
    for ((i, o) in options.withIndex()) {
        if (i > 0) dev.zeffin.ui.components.Divider()
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = o.enabled, role = Role.RadioButton) { onPick(o.value) }
            .padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(o.title, FbType.heading, color = if (o.enabled) FbType.heading.color else FbColors.inkFaint)
                if (o.lead != null) LeadText(o.lead, o.desc.orEmpty(), if (o.enabled) FbColors.inkMuted else FbColors.inkFaint)
                else if (o.desc != null) Txt(o.desc, FbType.caption, color = if (o.enabled) FbColors.inkMuted else FbColors.inkFaint)
            }
            if (o.selected) Icon(FbIcons.check, FbColors.action, size = 24.dp)
        }
    }
    if (note != null) Note(note, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
}

/** A description that starts with a word in its color ("Ready", "Takes images"), then the rest in [rest]'s color. */
@Composable
internal fun LeadText(lead: Pair<dev.zeffin.ui.present.Tone, String>, rest: String, color: androidx.compose.ui.graphics.Color) {
    val text = androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(color = lead.first.color(), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
        append(lead.second)
        pop()
        pushStyle(androidx.compose.ui.text.SpanStyle(color = color))
        append(rest)
        pop()
    }
    androidx.compose.foundation.text.BasicText(text, style = FbType.caption)
}

/** A link from the agent's answer: its full address first, then Open in the browser or Copy the link. */
@Composable
fun BoxScope.LinkSheet(url: String, onOpen: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val l = LocalLang.current
    val web = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
    FbSheet(l.t("Open this link?", "¿Abrir este enlace?"), onDismiss = onDismiss) {
        Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(FbColors.surfaceSunken).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Txt(url, FbType.mono.copy(fontSize = 13.sp, lineHeight = 18.sp), maxLines = 6)
        }
        if (web) ActionRow(FbIcons.open, l.t("Open in the browser", "Abrir en el navegador"), onClick = onOpen)
        if (web) dev.zeffin.ui.components.Divider()
        ActionRow(FbIcons.copy, l.t("Copy the link", "Copiar el enlace"), onClick = onCopy)
        Note(if (web) l.t("Links come from the agent's answer. Check the address before you open it.",
            "Los enlaces vienen de la respuesta del agente. Revisa la dirección antes de abrirla.")
            else l.t("Only web addresses (http or https) can be opened.", "Solo se pueden abrir direcciones web (http o https)."),
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
    }
}

/** The last turn's end (a failure, a stop): the character beside its words, as at the chat's tail. */
@Composable
private fun TurnEndLine(item: ChatItem.Line, face: dev.zeffin.ui.present.Face) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Character(CharacterUi(face), height = CharacterSize.tail)
        Txt(item.text, FbType.body, Modifier.weight(1f), color = if (item.tone == Tone.NEUTRAL) FbColors.inkMuted else item.tone.color())
    }
}
