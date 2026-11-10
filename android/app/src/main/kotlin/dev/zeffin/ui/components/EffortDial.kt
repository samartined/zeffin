// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.zeffin.link.Effort
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The effort as a gauge (docs/design/system/components/EffortDial.md): one segment per level the model
 * accepts on a half circle, Low at the left ("Faster") to Max at the right ("Deeper"). Tap a segment or drag
 * the handle. Always a concrete level: there is no "default" state.
 */
@Composable
fun EffortDial(
    levels: List<Effort>,
    level: Effort,
    name: (Effort) -> String,
    description: (Effort) -> String,
    onLevel: (Effort) -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
) {
    val l = LocalLang.current
    if (levels.isEmpty()) return
    val selected = levels.indexOf(level).coerceAtLeast(0)
    var held by remember { mutableStateOf(false) }
    val showDrag = dragging || held
    val currentLevels by rememberUpdatedState(levels)
    val currentOnLevel by rememberUpdatedState(onLevel)
    val span = 180f / levels.size

    fun levelAt(p: Offset, width: Float, height: Float): Effort {
        val cx = width / 2f
        val cy = height * (120f / 132f)
        val angle = Math.toDegrees(atan2((cy - p.y).toDouble(), (p.x - cx).toDouble())).toFloat()
        val a = if (p.y > cy) (if (p.x < cx) 180f else 0f) else angle.coerceIn(0f, 180f)
        val index = ((180f - a) / span).toInt().coerceIn(0, currentLevels.lastIndex)
        return currentLevels[index]
    }

    Column(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(l.t("Effort", "Esfuerzo"), FbType.deviceName, Modifier.weight(1f))
            Txt(l.t("Tap or drag", "Toca o arrastra"), FbType.caption, color = FbColors.inkFaint)
        }
        Box(Modifier.padding(top = 8.dp).size(248.dp, 132.dp)) {
            Canvas(
                Modifier.size(248.dp, 132.dp)
                    .semantics {
                        contentDescription = l.t("Effort", "Esfuerzo")
                        stateDescription = name(level)
                        progressBarRangeInfo = ProgressBarRangeInfo((selected + 1).toFloat(), 1f..levels.size.toFloat(), levels.size - 2)
                        setProgress { target ->
                            val i = (target.roundToInt() - 1).coerceIn(0, levels.lastIndex)
                            onLevel(levels[i]); true
                        }
                    }
                    .pointerInput(levels) {
                        detectTapGestures { p -> currentOnLevel(levelAt(p, size.width.toFloat(), size.height.toFloat())) }
                    }
                    .pointerInput(levels) {
                        detectDragGestures(
                            onDragStart = { p -> held = true; currentOnLevel(levelAt(p, size.width.toFloat(), size.height.toFloat())) },
                            onDragEnd = { held = false },
                            onDragCancel = { held = false },
                        ) { change, _ -> currentOnLevel(levelAt(change.position, size.width.toFloat(), size.height.toFloat())) }
                    },
            ) {
                val unit = size.width / 248f
                val cx = 124f * unit
                val cy = 120f * unit
                val r = 100f * unit
                val stroke = 18f * unit
                val gap = 4f
                val box = Size(2 * r, 2 * r)
                val topLeft = Offset(cx - r, cy - r)
                for (i in levels.indices) {
                    val start = 180f + span * i + if (i == 0) 0f else gap / 2
                    val end = 180f + span * (i + 1) - if (i == levels.lastIndex) 0f else gap / 2
                    val color = if (i <= selected) FbColors.accent else FbColors.line
                    if (showDrag && i == selected) {
                        drawArc(FbColors.accent.copy(alpha = 0.35f), start, end - start, false, topLeft, box, style = Stroke(stroke + 12f * unit))
                    }
                    drawArc(color, start, end - start, false, topLeft, box, style = Stroke(stroke))
                }
                val a = 180f - span * selected - span / 2
                val rad = Math.toRadians(a.toDouble())
                val hx = cx + r * cos(rad).toFloat()
                val hy = cy - r * sin(rad).toFloat()
                val side = (if (showDrag) 26f else 22f) * unit
                rotate(90f - a, Offset(hx, hy)) {
                    val outline = 3f * unit
                    drawRoundRect(FbColors.surfaceRaised, Offset(hx - side / 2 - outline / 2, hy - side / 2 - outline / 2),
                        Size(side + outline, side + outline), CornerRadius(6f * unit + outline / 2))
                    drawRoundRect(FbColors.ink, Offset(hx - side / 2 + outline / 2, hy - side / 2 + outline / 2),
                        Size(side - outline, side - outline), CornerRadius(6f * unit - outline / 2))
                }
            }
            Txt(name(level), FbType.dialValue, Modifier.align(Alignment.BottomCenter), align = TextAlign.Center)
        }
        Row(Modifier.width(268.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(l.t("Faster", "Más rápido"), FbType.caption, color = FbColors.inkFaint)
            Txt(l.t("Deeper", "Más a fondo"), FbType.caption, color = FbColors.inkFaint)
        }
        Txt(description(level), FbType.body, Modifier.padding(top = 8.dp), color = FbColors.inkMuted, align = TextAlign.Center)
    }
}
