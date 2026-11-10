// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.zeffin.R
import dev.zeffin.ui.present.Tone
import dev.zeffin.link.FlipperFrame

/** Colors from docs/design/system/tokens.json (a test keeps them in sync). Dark only. */
@Immutable
object FbColors {
    val surface = Color(0xFF000000)
    val surfaceRaised = Color(0xFF1C1C1C)
    val surfaceSunken = Color(0xFF101010)
    val ink = Color(0xFFFFFFFF)
    val inkMuted = Color(0xFF999999)
    val inkFaint = Color(0xFF8A8A8A)
    val line = Color(0xFF333333)
    val lineStrong = Color(0xFF777777)
    val accent = Color(0xFFFF8200)
    val onAccent = Color(0xFF000000)
    val accentStrong = Color(0xFFFF8200)
    val accentSoft = Color(0xFF492502)
    val action = Color(0xFF589DFF)
    val onAction = Color(0xFF000000)
    val success = Color(0xFF34C7A4)
    val successSoft = Color(0xFF0F2A24)
    val warning = Color(0xFFFECF5D)
    val warningSoft = Color(0xFF242000)
    val danger = Color(0xFFF63F3F)
    val dangerSoft = Color(0xFF340006)
    val onDanger = Color(0xFF000000)
    val info = Color(0xFF589DFF)
    val infoSoft = Color(0xFF1B2A41)
    val screenBezel = Color(0xFF804100)
    val screenPaper = Color(FlipperFrame.PAPER)
    val screenPixel = Color(FlipperFrame.INK)
    val focus = Color(0xFF589DFF)

    /** By token name, for the test against tokens.json. */
    val byToken: Map<String, Color> = mapOf(
        "surface" to surface, "surface-raised" to surfaceRaised, "surface-sunken" to surfaceSunken,
        "ink" to ink, "ink-muted" to inkMuted, "ink-faint" to inkFaint, "line" to line, "line-strong" to lineStrong,
        "accent" to accent, "on-accent" to onAccent, "accent-strong" to accentStrong, "accent-soft" to accentSoft,
        "action" to action, "on-action" to onAction, "success" to success, "success-soft" to successSoft,
        "warning" to warning, "warning-soft" to warningSoft, "danger" to danger, "danger-soft" to dangerSoft,
        "on-danger" to onDanger, "info" to info, "info-soft" to infoSoft, "screen-bezel" to screenBezel,
        "screen-paper" to screenPaper, "screen-pixel" to screenPixel, "focus" to focus,
    )
}

/** Roboto and Roboto Mono ship with the app (OFL), so it looks the same on every phone. */
val Roboto = FontFamily(
    Font(R.font.roboto_regular, FontWeight.Normal),
    Font(R.font.roboto_medium, FontWeight.Medium),
    Font(R.font.roboto_bold, FontWeight.Bold),
)
val RobotoMono = FontFamily(Font(R.font.roboto_mono_medium, FontWeight.Medium))

/** Type styles of tokens.json, plus the few the components define. */
object FbType {
    /** Like CSS: the line height is kept whole, not trimmed above the first line and below the last. */
    private val cssLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

    private fun sans(size: Int, line: Int, weight: FontWeight) =
        TextStyle(fontFamily = Roboto, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, color = FbColors.ink,
            lineHeightStyle = cssLines)

    val display = sans(24, 30, FontWeight.Bold)
    val title = sans(20, 26, FontWeight.Bold)
    val heading = sans(16, 22, FontWeight.Bold)
    val body = sans(14, 20, FontWeight.Normal)
    val bodyStrong = sans(14, 20, FontWeight.Medium)
    val label = sans(12, 16, FontWeight.Medium)
    val caption = sans(12, 16, FontWeight.Normal)
    val mono = TextStyle(fontFamily = RobotoMono, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = FbColors.ink,
        lineHeightStyle = cssLines)
    val monoSmall = mono.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val button = sans(16, 22, FontWeight.Bold)
    val buttonSmall = sans(14, 20, FontWeight.Bold)
    val navLabel = sans(10, 14, FontWeight.Bold)
    val deviceName = sans(16, 22, FontWeight.Medium)
    val dialValue = sans(22, 28, FontWeight.Medium)
    val chipName = sans(13, 18, FontWeight.Medium)
}

fun Tone.color(): Color = when (this) {
    Tone.NEUTRAL -> FbColors.inkMuted
    Tone.SUCCESS -> FbColors.success
    Tone.WARNING -> FbColors.warning
    Tone.DANGER -> FbColors.danger
    Tone.ACTION -> FbColors.action
}

val LocalFbColors = staticCompositionLocalOf { FbColors }

@Composable
fun FbTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalFbColors provides FbColors,
        LocalTextSelectionColors provides TextSelectionColors(FbColors.action, FbColors.action.copy(alpha = 0.4f)),
        content = content,
    )
}
