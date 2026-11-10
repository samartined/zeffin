// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import dev.zeffin.ui.theme.FbColors

// Drawing the character: the launcher icon's shapes in its 108 dp viewport (Character.kt chooses what to draw).

/** What one frame draws: the [pose], the face it leaves ([before], at 1 - [fade]), the body's [dim] and the [antenna]. */
internal data class Frame(val pose: Pose, val before: Pose?, val fade: Float, val dim: Float, val antenna: Float)

/** The colors of a look: the body, the screen, the face, the button's hole, the z's and the antenna's light, the bubbles. */
internal data class Look(val body: Color, val screen: Color, val face: Color, val hole: Color, val extra: Color, val bubble: Color)

private val BODY = PathParser().parsePathString("M40,38H68A16,16 0 0 1 68,70H40A16,16 0 0 1 40,38z").toPath()
private val SCREEN = PathParser().parsePathString(
    "M34.5,44H58.5A3.5,3.5 0 0 1 62,47.5V60.5A3.5,3.5 0 0 1 58.5,64H34.5A3.5,3.5 0 0 1 31,60.5V47.5A3.5,3.5 0 0 1 34.5,44z").toPath()
private val BUTTON = Offset(71.5f, 54f)
private const val BUTTON_R = 4f
/** The two eyes on the screen, around its middle (46.5, 54). */
private val EYES = listOf(Offset(40.9f, 54f), Offset(52.1f, 54f))
private const val EYE_R = 3.2f

internal fun DrawScope.drawCharacter(f: Frame, c: Look) {
    val p = f.pose
    val dim = f.dim
    val antenna = f.antenna
    val fade = f.fade
    val before = f.before
    if (antenna > 0f) {
        // The antenna grows from the body's top: its light blinks while connecting.
        val tip = Offset(44.5f + (40.6f - 44.5f) * antenna, 38.4f + (31.4f - 38.4f) * antenna)
        drawLine(c.body, Offset(44.5f, 38.4f), tip, strokeWidth = 2.6f, cap = StrokeCap.Round)
        val lit = p.antennaLit != false
        drawCircle(if (lit) c.extra else c.body, 3.1f * antenna, Offset(39.8f, 29.4f) + (tip - Offset(40.6f, 31.4f)))
        if (!lit) drawCircle(c.hole, 1.6f * antenna, Offset(39.8f, 29.4f) + (tip - Offset(40.6f, 31.4f)))
    }
    withTransform({ translate(p.bodyDx, p.bodyDy) }) {
        drawPath(BODY, c.body, alpha = dim)
        drawPath(SCREEN, c.screen, alpha = dim)
        drawCircle(c.hole, BUTTON_R * p.buttonScale, BUTTON)
        if (before != null && fade < 1f) drawFace(before, c, dim * (1f - fade))
        drawFace(p, c, dim * fade)
    }
    p.zs.forEachIndexed { i, z ->
        val size = if (i == 0) SMALL_Z else BIG_Z
        drawPath(zPath(z.x, z.y, size, size), c.extra, alpha = z.alpha)
    }
    p.folderBob?.let { drawFolder(65.5f, 25.5f + it, c, p.folderOpen) }
    listOf(Triple(61f, 35.5f, 1.6f), Triple(66f, 31.5f, 2.2f), Triple(72.6f, 28.2f, 2.9f)).take(p.bubbles).forEach { (x, y, r) ->
        drawCircle(c.bubble, r, Offset(x, y))
    }
}

private const val SMALL_Z = 4.2f
private const val BIG_Z = 6f

/** The two eyes and what is drawn on the screen, at [alpha]. */
private fun DrawScope.drawFace(p: Pose, c: Look, alpha: Float) {
    for (eye in EYES) drawEye(p, c, eye + Offset(p.dx, p.dy), alpha)
    p.dots?.forEachIndexed { i, lift -> drawCircle(c.face, 1.3f, Offset(41.5f + 5f * i, 60.6f - lift), alpha = alpha) }
    p.bar?.let { progress ->
        drawRoundRect(c.face, Offset(35f, 59f), Size(23f, 3f), androidx.compose.ui.geometry.CornerRadius(0.6f), alpha = alpha,
            style = Stroke(width = 0.9f))
        drawRect(c.face, Offset(35.9f, 59.9f), Size(21.2f * progress, 1.2f), alpha = alpha)
    }
}

/** One eye in the pose's expression, centered on [e]. */
private fun DrawScope.drawEye(p: Pose, c: Look, e: Offset, alpha: Float) {
    val stroke = Stroke(width = 2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (p.eye) {
        Eye.ROUND -> if (p.squint < 1f) {
            val ry = (EYE_R * p.squint).coerceAtLeast(0.35f)
            drawOval(c.face, Offset(e.x - EYE_R, e.y - ry), Size(EYE_R * 2, ry * 2), alpha = alpha)
        } else drawCircle(c.face, EYE_R * p.eyeScale, e, alpha = alpha)
        Eye.WIDE -> {
            drawCircle(c.face, 4f * p.eyeScale, e + Offset(0f, -0.3f), alpha = alpha)
            drawCircle(c.screen, 1.4f * p.eyeScale, e + Offset(0.9f, -1.3f), alpha = alpha)
        }
        Eye.CLOSED -> drawPath(Path().apply { moveTo(e.x - 3.2f, e.y - 0.8f); quadraticTo(e.x, e.y + 3.2f, e.x + 3.2f, e.y - 0.8f) },
            c.face, alpha = alpha, style = stroke)
        Eye.HAPPY -> drawPath(Path().apply { moveTo(e.x - 3.2f, e.y + 1.6f); quadraticTo(e.x, e.y - 3.4f, e.x + 3.2f, e.y + 1.6f) },
            c.face, alpha = alpha, style = stroke)
        Eye.FLAT -> drawLine(c.face, e - Offset(3.2f, 0f), e + Offset(3.2f, 0f), 2.3f, StrokeCap.Round, alpha = alpha)
        Eye.CROSS -> {
            drawLine(c.face, e + Offset(-2.4f, -2.4f), e + Offset(2.4f, 2.4f), 2.2f, StrokeCap.Round, alpha = alpha)
            drawLine(c.face, e + Offset(2.4f, -2.4f), e + Offset(-2.4f, 2.4f), 2.2f, StrokeCap.Round, alpha = alpha)
        }
        Eye.RING -> drawCircle(c.face, 2.6f, e, alpha = alpha, style = Stroke(width = 1.5f))
    }
}

/** A small z of Pause, [w] x [h] from its top left (bars 2.4 of 9). */
private fun zPath(x: Float, y: Float, w: Float, h: Float) = Path().apply {
    val t = h * 2.4f / 9f
    moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + t); lineTo(x + w * 0.42f, y + h - t); lineTo(x + w, y + h - t)
    lineTo(x + w, y + h); lineTo(x, y + h); lineTo(x, y + h - t); lineTo(x + w * 0.58f, y + t); lineTo(x, y + t); close()
}

/** A folder 18 x 13 from its top left; open: a sheet shows and the front flap tilts down. */
private fun DrawScope.drawFolder(x: Float, y: Float, c: Look, open: Boolean) {
    val back = PathParser().parsePathString(
        "M$x,${y + 2}a2,2 0 0 1 2,-2h4.5l2.3,2.3h7.2a2,2 0 0 1 2,2v8.7a2,2 0 0 1 -2,2h-14a2,2 0 0 1 -2,-2z").toPath()
    drawPath(back, c.extra)
    if (open) {
        drawRect(c.bubble, Offset(x + 3f, y + 3f), Size(11f, 6f))
        val front = Path().apply { moveTo(x + 1.2f, y + 6.4f); lineTo(x + 18.8f, y + 6.4f); lineTo(x + 16.8f, y + 13f); lineTo(x + 1.2f, y + 13f); close() }
        drawPath(front, c.extra)
        drawPath(front, FbColors.surface, style = Stroke(width = 0.8f))
    }
}
