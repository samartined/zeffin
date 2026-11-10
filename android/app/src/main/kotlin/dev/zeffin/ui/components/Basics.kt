// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.theme.color
import dev.zeffin.ui.present.Tone

/**
 * Where each [Txt] reports, after every layout, whether it is cut ("…", or wider than its space), with the instance
 * and its text; null in the app. The screenshot tests set it and keep each instance's last report, so a label,
 * button or title cut in either language shows up (no text may show cut), not a passing measure.
 */
val LocalTextCut = androidx.compose.runtime.staticCompositionLocalOf<((Any, String, Boolean) -> Unit)?> { null }

@Composable
fun Txt(
    text: String,
    style: TextStyle = FbType.body,
    modifier: Modifier = Modifier,
    color: Color = style.color,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    val cut = LocalTextCut.current
    val instance = remember { Any() }
    BasicText(
        text, modifier,
        style = style.copy(color = color, textAlign = align ?: style.textAlign),
        onTextLayout = if (cut == null) null else { r: TextLayoutResult ->
            // cut with "…", or wider than its space; a text a sheet's edge only hides below is not cut
            cut(instance, text, r.didOverflowWidth || (r.lineCount > 0 && r.isLineEllipsized(r.lineCount - 1)))
        },
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@Composable
fun Icon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, description: String? = null) {
    Image(
        rememberVectorPainter(icon), contentDescription = description,
        modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint),
    )
}

/** Status is colored text, never a chip. */
@Composable
fun StateText(tone: Tone, text: String, modifier: Modifier = Modifier) {
    Txt(text, FbType.bodyStrong, modifier, color = tone.color())
}

enum class ButtonKind { PRIMARY, WARNING, SUCCESS, QUIET }

/** Filled pill like ComposableFlipperButton. No outlined buttons. */
/** True where three pills share one row (Home's Pause, Resume and Remote): 8 dp at their sides instead of 24. */
val LocalTightPills = androidx.compose.runtime.compositionLocalOf { false }

@Composable
fun FbButton(
    text: String,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    small: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val (bg, fg) = when {
        !enabled -> FbColors.line to FbColors.inkFaint
        kind == ButtonKind.PRIMARY -> FbColors.action to FbColors.onAction
        kind == ButtonKind.WARNING -> FbColors.warning to Color.Black
        kind == ButtonKind.SUCCESS -> FbColors.success to Color.Black
        else -> Color.Transparent to FbColors.action
    }
    Box(
        modifier
            .heightIn(min = if (small) 44.dp else 48.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (kind == ButtonKind.QUIET || LocalTightPills.current) 8.dp else if (small) 18.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, if (small) FbType.buttonSmall else FbType.button, color = fg, maxLines = 1)
    }
}

class CardScope {
    internal val items = mutableListOf<Pair<Boolean, @Composable () -> Unit>>()

    /** A row of the card; a divider goes above every row but the first. */
    fun item(divider: Boolean = true, content: @Composable () -> Unit) {
        items += divider to content
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(FbColors.line))
}

/** True in screenshot tests: animations draw one still frame (an endless one would keep the test from settling). */
val LocalStillFrames = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Like InfoElementCard: title inside, rows separated by lines. */
@Composable
fun FbCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    state: Pair<Tone, String>? = null,
    headEnd: (@Composable RowScope.() -> Unit)? = null,
    titleLines: Int = 1,
    /** Right after the title's text (an icon), before the state. */
    titleEnd: (@Composable () -> Unit)? = null,
    content: CardScope.() -> Unit,
) {
    val scope = CardScope().apply(content)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FbColors.surfaceRaised)) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (titleEnd == null) Txt(title, FbType.heading, Modifier.weight(1f), maxLines = titleLines)
                else Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(title, FbType.heading, Modifier.weight(1f, fill = false), maxLines = titleLines)
                    titleEnd()
                }
                if (state != null) StateText(state.first, state.second)
                headEnd?.invoke(this)
            }
        }
        scope.items.forEachIndexed { i, (divider, row) ->
            if (i > 0 && divider) Divider()
            row()
        }
    }
}

/** Gray label left, value right (ComposableDeviceInfoRow). */
@Composable
fun InfoRow(label: String, value: String, tone: Tone? = null, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(label, FbType.body, Modifier.weight(1f), color = FbColors.inkMuted, maxLines = 1)
        Txt(value, if (mono) FbType.monoSmall.copy(lineHeight = FbType.body.lineHeight) else FbType.body,
            color = tone?.color() ?: FbColors.ink, maxLines = 1, align = TextAlign.End)
    }
}

/**
 * A row of what the owner approves (a command, a path: untrusted input), never cut: like [InfoRow] while it fits on
 * one line; otherwise the label above and the whole value below, with its line breaks, selectable, in a box that
 * scrolls past [maxHeight], with [more] under it until the end is reached.
 */
@Composable
fun InputRow(label: String, value: String, more: String, maxHeight: Dp = 200.dp) {
    var whole by remember(value) { mutableStateOf('\n' in value) }
    if (!whole) {
        // InfoRow with mono = true, told when the label or the value does not fit
        val cut = { r: TextLayoutResult -> if (r.hasVisualOverflow) whole = true }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(label, Modifier.weight(1f), FbType.body.copy(color = FbColors.inkMuted), cut, TextOverflow.Ellipsis, maxLines = 1)
            BasicText(value, Modifier, FbType.monoSmall.copy(lineHeight = FbType.body.lineHeight, color = FbColors.ink, textAlign = TextAlign.End),
                cut, TextOverflow.Ellipsis, maxLines = 1)
        }
        return
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, FbType.body, color = FbColors.inkMuted, maxLines = 1)
        SelectionContainer {
            Box(Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(4.dp)).background(FbColors.surfaceSunken)
                .verticalScroll(scroll).padding(8.dp)) {
                Txt(value, FbType.monoSmall.copy(lineHeight = FbType.body.lineHeight), color = FbColors.ink)
            }
        }
        // Only once the box is measured: until then maxValue is Int.MAX_VALUE and canScrollForward says true, so a
        // frame (a screenshot, a slow phone) showed the hint under a value the box shows whole.
        if (scroll.maxValue in 1 until Int.MAX_VALUE && scroll.canScrollForward) Txt(more, FbType.caption, color = FbColors.inkMuted)
    }
}

/** Icon and text in the action color inside a card (Synchronize, Forget Flipper). */
@Composable
fun ActionRow(icon: ImageVector, text: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (!enabled) FbColors.inkFaint else if (danger) FbColors.danger else FbColors.action
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, color, size = 22.dp)
        Txt(text, FbType.bodyStrong, color = color)
    }
}

/** Bold title, gray description, a control or a value at the end (CategoryElement). */
@Composable
fun SettingRow(
    title: String,
    desc: String? = null,
    value: String? = null,
    checked: Boolean? = null,
    descContent: (@Composable () -> Unit)? = null,
    descMaxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val click = when {
        checked != null && onCheckedChange != null -> Modifier.clickable(role = Role.Switch) { onCheckedChange(!checked) }
        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(click).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, FbType.heading)
            if (descContent != null) descContent()
            else if (desc != null) Txt(desc, FbType.caption, color = FbColors.inkMuted, maxLines = descMaxLines)
        }
        if (value != null) Txt(value, FbType.body, color = FbColors.inkMuted, maxLines = 1)
        if (checked != null) FbSwitch(checked, title)
        else if (onClick != null) Icon(dev.zeffin.ui.theme.FbIcons.chevron, FbColors.inkFaint, size = 20.dp)
    }
}

/** Material-like switch, orange when on. The row handles the click. */
@Composable
fun FbSwitch(checked: Boolean, label: String) {
    Box(Modifier.size(width = 48.dp, height = 44.dp).semantics { contentDescription = label }) {
        Box(Modifier.padding(start = 7.dp, top = 15.dp).size(width = 34.dp, height = 14.dp).clip(RoundedCornerShape(7.dp))
            .background(if (checked) FbColors.accent.copy(alpha = 0.54f) else FbColors.lineStrong))
        Box(Modifier.padding(start = if (checked) 24.dp else 4.dp, top = 12.dp).size(20.dp).clip(RoundedCornerShape(50))
            .background(if (checked) FbColors.accent else FbColors.inkMuted))
    }
}

/** Card whose title carries the color: no tinted backgrounds. */
@Composable
fun Banner(tone: Tone, title: String, text: String? = null, detail: String? = null, actions: (@Composable ColumnScope.() -> Unit)? = null) =
    BannerCard(tone, title, actions) {
        if (text != null) Txt(text, FbType.body, color = FbColors.inkMuted)
        if (detail != null) Txt(detail, FbType.monoSmall, color = FbColors.inkMuted)
    }

/** The same with a text that carries its own styles, such as a count in the ink color ([emphasized]). */
@Composable
fun Banner(tone: Tone, title: String, text: AnnotatedString, actions: (@Composable ColumnScope.() -> Unit)? = null) =
    BannerCard(tone, title, actions) { BasicText(text, style = FbType.body.copy(color = FbColors.inkMuted)) }

@Composable
private fun BannerCard(tone: Tone, title: String, actions: (@Composable ColumnScope.() -> Unit)?, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FbColors.surfaceRaised)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, FbType.heading, color = if (tone == Tone.NEUTRAL) FbColors.ink else tone.color())
            body()
        }
        if (actions != null) {
            Divider()
            actions()
        }
    }
}

/** [text] with its first [strong] in the ink color and medium weight, as a count in a banner. */
fun emphasized(text: String, strong: String): AnnotatedString = buildAnnotatedString {
    val at = text.indexOf(strong)
    append(text.substring(0, at))
    withStyle(SpanStyle(color = FbColors.ink, fontWeight = FontWeight.Medium)) { append(strong) }
    append(text.substring(at + strong.length))
}

/** Small gray note under a card. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Txt(text, FbType.caption, modifier.padding(horizontal = 2.dp), color = FbColors.inkFaint)
}

@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Txt(text, FbType.heading, modifier.padding(horizontal = 2.dp))
}

/** Three figures under a card title, like the relay card. */
@Composable
fun Metrics(values: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in values) {
            Column(Modifier.weight(1f)) {
                Txt(value, FbType.title, maxLines = 1)
                Txt(label, FbType.caption, color = FbColors.inkMuted, maxLines = 1)
            }
        }
    }
}

/** The standard page body: 14 dp padding and 14 dp between blocks, scrolling when needed. */
@Composable
fun PageBody(modifier: Modifier = Modifier, scroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
             content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(scroll).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HGap(width: Dp) = Spacer(Modifier.width(width))

val CardPadding = PaddingValues(12.dp)
