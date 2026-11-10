// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zeffin.relay.FlipperKey
import dev.zeffin.ui.components.ButtonKind
import dev.zeffin.ui.components.FbButton
import dev.zeffin.ui.components.FbSheet
import dev.zeffin.ui.components.FlipperScreen
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.present.FlipperRemoteUi
import dev.zeffin.ui.present.PresentFlipperRemote
import dev.zeffin.ui.present.RemoteLine
import dev.zeffin.ui.present.Tone
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.theme.color
import dev.zeffin.ui.components.Bar
import dev.zeffin.ui.components.Character
import dev.zeffin.ui.components.CharacterSize

/**
 * The remote for the Flipper: its screen in the space under the bar and, at the bottom, a round pad with the
 * arrows around OK and a round Back beside it. Between them a space of fixed height holds the agent's state, a refused
 * key and Retry, so the pad never moves under the finger. A tap sends a short press when the finger lifts; a hold sends
 * a long press at Android's long-press timeout, with a short vibration. In landscape the screen is on the left.
 */
/** The remote's Flipper screen as drawn, the layout, and whether the leaving sheet is open. */
class RemoteView(val frame: ByteArray?, val orientation: Int, val landscape: Boolean, val leaving: Boolean)

/** What the remote's taps do: a key (short or long), the note's button, and leaving with or without Resume. */
class RemoteActions(
    val onBack: () -> Unit,
    val onKey: (FlipperKey, Boolean) -> Unit,
    val onAction: () -> Unit,
    val onResume: () -> Unit,
    val onKeepPaused: () -> Unit,
    val onDismissLeave: () -> Unit,
)

@Composable
fun FlipperRemoteScreen(ui: FlipperRemoteUi, words: PresentFlipperRemote, view: RemoteView, actions: RemoteActions) {
    val frame = view.frame
    val orientation = view.orientation
    val onAction = actions.onAction
    val onKey = actions.onKey
    val screen = @Composable {
        FlipperScreen(frame, orientation, ui.screenMode, scale = 2)
    }
    val notes = @Composable { height: Dp, top: Dp -> Notes(ui, height, top, onAction) }
    val pad = @Composable { Pad(ui.keysOn, words, onKey) }
    Box(Modifier.fillMaxSize().background(FbColors.surface)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(Bar(ui.title, ui.subtitle, ui.face, actions.onBack))
            if (view.landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(460.dp).fillMaxHeight().padding(top = 12.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        screen()
                        notes(144.dp, 12.dp)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(FbColors.line))
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { pad() }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) { screen() }
                notes(180.dp, 0.dp)
                Box(Modifier.fillMaxWidth().padding(bottom = 32.dp), contentAlignment = Alignment.Center) { pad() }
            }
        }
        if (view.leaving) {
            FbSheet(words.leaveTitle, onDismiss = actions.onDismissLeave) {
                Txt(words.leaveText, FbType.body, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), color = FbColors.inkMuted)
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FbButton(words.leaveResume, Modifier.fillMaxWidth(), ButtonKind.SUCCESS, onClick = actions.onResume)
                    FbButton(words.leaveKeep, Modifier.fillMaxWidth(), ButtonKind.QUIET, onClick = actions.onKeepPaused)
                }
            }
        }
    }
}

/** The fixed space under the screen: the agent's state, the keys' note and the one button the state needs. */
@Composable
private fun Notes(ui: FlipperRemoteUi, height: Dp, top: Dp, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(height).padding(start = 24.dp, end = 24.dp, top = top),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Character(ui.face, Modifier.padding(bottom = 8.dp), CharacterSize.stage)
        ui.lines.forEach { Line(it) }
        if (ui.actionLabel != null) {
            FbButton(ui.actionLabel, Modifier.padding(top = 2.dp), ButtonKind.PRIMARY, small = true, onClick = onAction)
        }
    }
}

@Composable
private fun Line(line: RemoteLine) {
    val tint = if (line.tone == Tone.NEUTRAL) FbColors.inkMuted else line.tone.color()
    val text = buildAnnotatedString {
        append(line.text)
        if (line.code != null) {
            append(" ")
            // device data: a fixed status name in mono on the sunken surface
            withStyle(SpanStyle(fontFamily = FbType.mono.fontFamily, fontSize = 13.sp, color = FbColors.ink, background = FbColors.surfaceSunken)) {
                append("\u2009${line.code}\u2009")
            }
        }
    }
    BasicText(text, style = FbType.bodyStrong.copy(color = tint, textAlign = TextAlign.Center))
}

/** The round pad (162 dp: a 3 dp ring, a 3 dp gap, the orange face) and Back beside it, bottom-aligned. */
@Composable
private fun Pad(on: Boolean, words: PresentFlipperRemote, onKey: (FlipperKey, Boolean) -> Unit) {
    val face = if (on) FbColors.accent else FbColors.line
    val ring = if (on) FbColors.screenBezel else FbColors.line
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
        Box(Modifier.size(162.dp).border(3.dp, ring, CircleShape).padding(6.dp).clip(CircleShape).background(face)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f))
                    Key(FlipperKey.UP, on, words.keyName(FlipperKey.UP), onKey, Modifier.weight(1f))
                    Box(Modifier.weight(1f))
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Key(FlipperKey.LEFT, on, words.keyName(FlipperKey.LEFT), onKey, Modifier.weight(1f))
                    Key(FlipperKey.OK, on, words.keyName(FlipperKey.OK), onKey, Modifier.weight(1f))
                    Key(FlipperKey.RIGHT, on, words.keyName(FlipperKey.RIGHT), onKey, Modifier.weight(1f))
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f))
                    Key(FlipperKey.DOWN, on, words.keyName(FlipperKey.DOWN), onKey, Modifier.weight(1f))
                    Box(Modifier.weight(1f))
                }
            }
        }
        Box(Modifier.size(54.dp).border(3.dp, ring, CircleShape).padding(6.dp).clip(CircleShape).background(face)) {
            Key(FlipperKey.BACK, on, words.keyName(FlipperKey.BACK), onKey, Modifier.fillMaxSize())
        }
    }
}

/**
 * One key: a tap sends the short press when the finger lifts; a hold sends the long press at the long-press timeout,
 * with a short vibration, and nothing more when it lifts. No repeat.
 */
@Composable
private fun Key(key: FlipperKey, on: Boolean, name: String, onKey: (FlipperKey, Boolean) -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier.fillMaxHeight()
            .semantics {
                contentDescription = name
                role = Role.Button
                if (on) {
                    onClick { onKey(key, false); true }
                    onLongClick { onKey(key, true); true }
                } else {
                    disabled()
                }
            }
            .then(if (on) Modifier.pointerInput(key) {
                detectTapGestures(
                    onTap = { onKey(key, false) },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onKey(key, true)
                    },
                )
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            FlipperKey.UP -> Triangle(0f, on)
            FlipperKey.RIGHT -> Triangle(90f, on)
            FlipperKey.DOWN -> Triangle(180f, on)
            FlipperKey.LEFT -> Triangle(270f, on)
            FlipperKey.OK -> OkCircle(on)
            FlipperKey.BACK -> BackArrow(on)
        }
    }
}

private fun outline(on: Boolean) = if (on) FbColors.screenPixel else FbColors.inkFaint
private fun fill(on: Boolean) = if (on) FbColors.accent else FbColors.line

/** An arrow: a triangle with rounded corners, outlined; [degrees] turns it from pointing up. */
@Composable
private fun Triangle(degrees: Float, on: Boolean) {
    Canvas(Modifier.size(34.dp, 30.dp).rotate(degrees)) {
        val u = size.width / 34f
        // rounded corners, as the approved mockup draws them
        val path = Path().apply {
            moveTo(17f * u, 3.5f * u)
            quadraticTo(18.6f * u, 3.5f * u, 19.4f * u, 4.9f * u)
            lineTo(31f * u, 25f * u)
            quadraticTo(31.8f * u, 26.5f * u, 30.9f * u, 27.7f * u)
            quadraticTo(30.2f * u, 28.5f * u, 29f * u, 28.5f * u)
            lineTo(5f * u, 28.5f * u)
            quadraticTo(3.8f * u, 28.5f * u, 3.1f * u, 27.7f * u)
            quadraticTo(2.2f * u, 26.5f * u, 3f * u, 25f * u)
            lineTo(14.6f * u, 4.9f * u)
            quadraticTo(15.4f * u, 3.5f * u, 17f * u, 3.5f * u)
            close()
        }
        drawPath(path, fill(on))
        drawPath(path, outline(on), style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
    }
}

@Composable
private fun OkCircle(on: Boolean) {
    Canvas(Modifier.size(44.dp)) {
        val r = 19.5f * size.width / 44f
        drawCircle(fill(on), radius = r)
        drawCircle(outline(on), radius = r, style = Stroke(width = 3.dp.toPx()))
    }
}

/** The Flipper's Back: an arrow that turns back, outlined like the others. */
@Composable
private fun BackArrow(on: Boolean) {
    Canvas(Modifier.size(26.dp)) {
        val u = size.width / 26f
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val head = Path().apply {
            moveTo(9f * u, 4f * u)
            lineTo(3.5f * u, 9f * u)
            lineTo(9f * u, 14f * u)
        }
        val tail = Path().apply {
            moveTo(3.5f * u, 9f * u)
            lineTo(16f * u, 9f * u)
            arcTo(androidx.compose.ui.geometry.Rect(Offset(9.5f * u, 9f * u), androidx.compose.ui.geometry.Size(13f * u, 13f * u)),
                -90f, 180f, false)
            lineTo(8f * u, 22f * u)
        }
        val c: Color = outline(on)
        drawPath(head, c, style = stroke)
        drawPath(tail, c, style = stroke)
    }
}
