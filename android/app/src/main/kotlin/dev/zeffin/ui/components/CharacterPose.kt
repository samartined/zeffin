// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zeffin.ui.present.Face
import dev.zeffin.ui.present.Scene
import dev.zeffin.ui.theme.FbColors
import kotlin.math.PI
import kotlin.math.sin

/** The eyes' expressions: both eyes carry the same one (docs/design/system/components/Character.md). */
enum class Eye { ROUND, WIDE, CLOSED, HAPPY, FLAT, CROSS, RING }

/** A small z of Pause: its top left, in the icon's 108 dp viewport, and its alpha. */
data class Zz(val x: Float, val y: Float, val alpha: Float)

/**
 * Everything that changes in the character between frames, in the launcher icon's 108 dp viewport. [dx] and [dy] move
 * both eyes together (where the face looks); the body moves by [bodyDx] and [bodyDy].
 */
data class Pose(
    val eye: Eye = Eye.ROUND,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val squint: Float = 1f,
    val eyeScale: Float = 1f,
    val bodyDx: Float = 0f,
    val bodyDy: Float = 0f,
    val buttonScale: Float = 1f,
    val dots: List<Float>? = null,
    val bar: Float? = null,
    val bubbles: Int = 0,
    val folderBob: Float? = null,
    val folderOpen: Boolean = false,
    val zs: List<Zz> = emptyList(),
    val antennaLit: Boolean? = null,
    val dim: Boolean = false,
)

/** How a face moves: a loop of [periodMs], or once ([once]) and then still. */
data class Motion(val periodMs: Int, val once: Boolean = false)

/** The poses of every face and scene: the still frame (no motion, screenshot tests) and the frame at a time. */
object CharacterPose {
    /** The loop of each tool scene, in ms. */
    private val SCENE_MS = mapOf(Scene.SCREEN to 1600, Scene.PRESS to 1000, Scene.FILES to 1200, Scene.APP to 2000, Scene.OTHER to 2400)

    /** The face's motion; null for a face that never moves (stopped, offline). */
    fun motion(face: Face, scene: Scene?): Motion? = when (face) {
        Face.ACTIVE -> Motion(3500)
        Face.WORKING -> Motion(scene?.let { SCENE_MS.getValue(it) } ?: 4000)
        Face.WRITING -> Motion(1200)
        Face.PERMISSION -> Motion(1400)
        Face.PAUSED -> Motion(2400)
        Face.FINISHED -> Motion(1120, once = true)
        Face.ERROR -> Motion(600, once = true)
        Face.CONNECTING -> Motion(1800)
        Face.STOPPED, Face.OFFLINE -> null
    }

    /** The still frame: the mockups' frame 0 of each face and scene. */
    fun still(face: Face, scene: Scene? = null): Pose = when (face) {
        Face.ACTIVE -> Pose()
        Face.WORKING -> stillWorking(scene)
        Face.WRITING -> Pose(dy = -2.6f, dots = listOf(0f, 0f, 0f))
        Face.PERMISSION -> Pose(eye = Eye.WIDE)
        Face.PAUSED -> Pose(eye = Eye.CLOSED, zs = listOf(Zz(65f, 33.5f, 1f), Zz(71.5f, 26.5f, 1f)))
        Face.STOPPED -> Pose(eye = Eye.FLAT)
        Face.FINISHED -> Pose(eye = Eye.HAPPY)
        Face.ERROR -> Pose(eye = Eye.CROSS)
        Face.CONNECTING -> Pose(dy = -2.6f, antennaLit = true)
        Face.OFFLINE -> Pose(eye = Eye.RING, dim = true)
    }

    private fun stillWorking(scene: Scene?) = when (scene) {
        null -> Pose(dx = 2f)
        Scene.SCREEN -> Pose(dy = 1.8f)
        Scene.PRESS -> Pose(dx = 2f)
        Scene.FILES -> Pose(dx = 2f, dy = -2.4f, folderBob = 0f)
        Scene.APP -> Pose(dy = -2.4f, bar = 0.6f)
        Scene.OTHER -> Pose(dx = -1.6f, dy = -2.4f, bubbles = 3)
    }

    /** The frame at [ms] into the face's motion (looped by the caller; a face that plays once holds its end). */
    fun at(face: Face, scene: Scene?, ms: Float): Pose {
        val s = still(face, scene)
        return when (face) {
            Face.ACTIVE -> s.copy(squint = keys(ms, 1f, k(3290f, 1f), k(3360f, 0.08f, FastOutLinearInEasing), k(3430f, 0.08f),
                k(3500f, 1f, LinearOutSlowInEasing)))
            Face.WORKING -> working(s, scene, ms)
            Face.WRITING -> s.copy(dots = List(3) { i -> dot(ms - 240f * i) })
            Face.PERMISSION -> s.copy(
                bodyDy = keys(ms, 0f, k(980f, 0f), k(1120f, 0.8f, FastOutSlowInEasing), k(1260f, -3.2f, LinearOutSlowInEasing),
                    k(1400f, 0f, FastOutLinearInEasing)),
                eyeScale = keys(ms, 1f, k(1120f, 1f), k(1260f, 1.1f, LinearOutSlowInEasing), k(1400f, 1f, FastOutLinearInEasing)))
            Face.PAUSED -> s.copy(zs = listOf(rising(ms, 63f, 37f, 67f, 30f), rising((ms + 1200f) % 2400f, 69f, 31f, 73f, 25f)))
            Face.FINISHED -> s.copy(
                bodyDy = keys(ms, 0f, k(320f, -4.4f, FastOutSlowInEasing), k(640f, 0f, FastOutLinearInEasing),
                    k(880f, -1.8f, FastOutSlowInEasing), k(1120f, 0f, FastOutLinearInEasing)))
            Face.ERROR -> s.copy(bodyDx = keys(ms, 0f, k(75f, -2.4f, EaseInOutSine), k(225f, 2.4f, EaseInOutSine),
                k(375f, -2.4f, EaseInOutSine), k(525f, 2.4f, EaseInOutSine), k(600f, 0f, EaseInOutSine)))
            Face.CONNECTING -> s.copy(dx = -2f * wave(ms, 1800f), antennaLit = ms < 900f)
            Face.STOPPED, Face.OFFLINE -> s
        }
    }

    private fun working(s: Pose, scene: Scene?, ms: Float): Pose = when (scene) {
        null -> s.copy(dx = keys(ms, 2f, k(1300f, 2f), k(2000f, -2f, FastOutSlowInEasing), k(3300f, -2f),
            k(4000f, 2f, FastOutSlowInEasing)))
        Scene.SCREEN -> s.copy(dx = -2.4f * wave(ms, 1600f))
        Scene.PRESS -> s.copy(buttonScale = keys(ms, 1f, k(400f, 1f), k(500f, 0.55f, FastOutLinearInEasing), k(620f, 0.55f),
            k(770f, 1f, LinearOutSlowInEasing)))
        Scene.FILES -> s.copy(folderBob = -(1f - kotlin.math.cos(2 * PI.toFloat() * ms / 1200f)), folderOpen = ms in 300f..900f)
        Scene.APP -> s.copy(bar = keys(ms, 0f, k(1600f, 1f, LinearOutSlowInEasing)))
        Scene.OTHER -> s.copy(bubbles = when { ms < 300f -> 1; ms < 600f -> 2; ms < 2100f -> 3; else -> 0 })
    }

    /** One dot of the typing: up 180 ms and down 180 ms, eased in and out; [ms] from its own start. */
    private fun dot(ms: Float) = keys(ms, 0f, k(180f, 1.6f, EaseInOutSine), k(360f, 0f, EaseInOutSine))

    /** A z that fades in for 300 ms, rises over the loop and fades out in its last 600 ms. */
    private fun rising(ms: Float, x0: Float, y0: Float, x1: Float, y1: Float): Zz {
        val p = LinearOutSlowInEasing.transform((ms / 2400f).coerceIn(0f, 1f))
        val alpha = when { ms < 300f -> ms / 300f; ms > 1800f -> ((2400f - ms) / 600f).coerceIn(0f, 1f); else -> 1f }
        return Zz(x0 + (x1 - x0) * p, y0 + (y1 - y0) * p, alpha)
    }

    /** A sine from 0 to 1, back to 0, to -1 and back to 0 over [period]. */
    private fun wave(ms: Float, period: Float) = sin(2 * PI.toFloat() * ms / period)

    private class Key(val at: Float, val value: Float, val easing: Easing?)

    /** A key frame: at [at] ms the value is [value]; with an [easing] it eases there from the key before, without
     *  one the value before holds until [at]. */
    private fun k(at: Float, value: Float, easing: Easing? = null) = Key(at, value, easing)

    /** A value through key frames: [start] at 0 ms, then each key. After the last key, its value. */
    private fun keys(ms: Float, start: Float, vararg ks: Key): Float {
        val i = ks.indexOfFirst { ms <= it.at }
        val key = ks.getOrNull(i)
        val t0 = if (i > 0) ks[i - 1].at else 0f
        val v0 = if (i > 0) ks[i - 1].value else start
        val span = (key?.at ?: 0f) - t0
        return when {
            key == null -> ks.lastOrNull()?.value ?: start
            key.easing == null -> v0
            span <= 0f -> key.value
            else -> v0 + (key.value - v0) * key.easing.transform(((ms - t0) / span).coerceIn(0f, 1f))
        }
    }
}

/** The character's heights (the design system's spacing): its width is 17/13 of the height. */
object CharacterSize {
    /** App bars and the bottom bar (space-6). */
    val bar = 24.dp
    /** The chat's tail (space-8). */
    val tail = 32.dp
    /** Home's card (space-12). */
    val card = 48.dp
    /** The remote, the empty states and Home's screen without an image (2 x space-8). */
    val stage = 64.dp

    fun width(height: Dp): Dp = height * BOX_W / BOX_H
}

/** The character's box in the launcher icon's 108 dp viewport: the body, with room above it for the z's and the antenna. */
internal const val BOX_X = 20f
internal const val BOX_Y = 24f
internal const val BOX_W = 68f
internal const val BOX_H = 52f

/** Where the character is drawn: on a dark surface ([background] shows through its button), or on the orange app bar. */
sealed class CharacterLook {
    data class Dark(val background: Color = FbColors.surface) : CharacterLook()
    data object OnAccent : CharacterLook()
}
