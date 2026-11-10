// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.zeffin.ui.present.CharacterUi
import dev.zeffin.ui.present.Face
import dev.zeffin.ui.present.Scene
import dev.zeffin.ui.theme.FbColors

/**
 * True when the phone lets things move: Android's "Remove animations" sets the animator duration scale to 0, and
 * then every face draws its still frame.
 */
fun animationsOn(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** The character in [face], with no scene: the bars, the empty and offline states. */
@Composable
fun Character(face: Face, modifier: Modifier = Modifier, height: Dp = CharacterSize.card, look: CharacterLook = CharacterLook.Dark()) =
    Character(CharacterUi(face), modifier, height, look)

/**
 * Zeffin's character: the launcher icon's body and screen with two round eyes, in [ui]'s face, drawn from
 * vectors at [height]. While it works, the scene acts out the tool in use. [CharacterLook.OnAccent]: the icon's own
 * look for the orange app bar (a black body, the screen in the bar's orange), always still.
 *
 * It moves only while its state lasts and the screen is started; with no motion (screenshot tests, Remove
 * animations) it draws its still frame. A face that plays once (finished, error) plays again for each new event.
 * Decoration by default: [description] gives it words for TalkBack where no text beside it says the state.
 */
@Composable
fun Character(ui: CharacterUi, modifier: Modifier = Modifier, height: Dp = CharacterSize.card,
              look: CharacterLook = CharacterLook.Dark(), description: String? = null) {
    val moving = rememberMoving(look)
    val scene = if (ui.face == Face.WORKING) ui.scene else null
    val pose = animatedPose(ui.face, scene, ui.event, moving)
    val frame = transitions(ui.face, scene, pose, moving)
    val colors = colorsOf(look)
    val semantics = if (description != null) Modifier.semantics { contentDescription = description; role = Role.Image } else Modifier
    Canvas(modifier.size(CharacterSize.width(height), height).then(semantics)) {
        scale(size.height / BOX_H, size.height / BOX_H, pivot = Offset.Zero) {
            translate(-BOX_X, -BOX_Y) { drawCharacter(frame, colors) }
        }
    }
}

/** True when the face may move: not a screenshot, not on the orange bar, animations on, and the screen started. */
@Composable
private fun rememberMoving(look: CharacterLook): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val still = LocalStillFrames.current || look is CharacterLook.OnAccent || !remember(context) { animationsOn(context) }
    return !still && lifecycle.value.isAtLeast(Lifecycle.State.STARTED)
}

/** The pose at this frame of the face's motion, or its still frame when it does not move. */
@Composable
private fun animatedPose(face: Face, scene: Scene?, event: Int?, moving: Boolean): Pose {
    val motion = if (moving) CharacterPose.motion(face, scene) else null
    val time = when {
        motion == null -> null
        motion.once -> {
            val t = remember(face, scene, event) { Animatable(0f) }
            LaunchedEffect(t) { t.animateTo(motion.periodMs.toFloat(), tween(motion.periodMs, easing = LinearEasing)) }
            t.value
        }
        else -> rememberInfiniteTransition(label = "character").animateFloat(0f, motion.periodMs.toFloat(),
            infiniteRepeatable(tween(motion.periodMs, easing = LinearEasing), RepeatMode.Restart), label = "time").value
    }
    return if (time == null) CharacterPose.still(face, scene) else CharacterPose.at(face, scene, time)
}

/** Changes of state: what moves slides, what changes shape cross-fades (Character.md, "Transitions"). */
@Composable
private fun transitions(face: Face, scene: Scene?, pose: Pose, moving: Boolean): Frame {
    val key = face to scene
    val change = updateTransition(key, label = "face")
    val fade by change.animateFloat({ if (moving) tween(FADE_MS, easing = FastOutSlowInEasing) else snap() }, label = "fade") {
        if (it == key) 1f else 0f
    }
    val before = change.currentState.takeIf { it != change.targetState }?.let { (f, s) -> CharacterPose.still(f, s) }
    val base = CharacterPose.still(face, scene)
    val slide = if (moving) tween<Float>(SLIDE_MS, easing = FastOutSlowInEasing) else snap()
    val dx by animateFloatAsState(base.dx, slide, label = "dx")
    val dy by animateFloatAsState(base.dy, slide, label = "dy")
    val dim by animateFloatAsState(if (pose.dim) DIM else 1f, if (moving) tween(DIM_MS, easing = LinearEasing) else snap(), label = "dim")
    val antenna by animateFloatAsState(if (pose.antennaLit != null) 1f else 0f,
        if (moving) tween(SLIDE_MS, easing = FastOutLinearInEasing) else snap(), label = "antenna")
    return Frame(pose.copy(dx = dx + pose.dx - base.dx, dy = dy + pose.dy - base.dy), before, if (before != null) fade else 1f, dim, antenna)
}

private fun colorsOf(look: CharacterLook) = when (look) {
    CharacterLook.OnAccent -> Look(FbColors.onAccent, FbColors.accent, FbColors.onAccent, FbColors.accent, FbColors.onAccent, FbColors.onAccent)
    is CharacterLook.Dark -> Look(FbColors.accent, FbColors.surface, FbColors.accent, look.background, FbColors.warning, FbColors.ink)
}

private const val FADE_MS = 150
private const val SLIDE_MS = 200
private const val DIM_MS = 300
private const val DIM = 0.45f
