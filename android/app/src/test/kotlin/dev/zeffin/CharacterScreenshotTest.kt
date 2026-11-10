// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.zeffin.core.Lang
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.Character
import dev.zeffin.ui.components.CharacterLook
import dev.zeffin.ui.components.CharacterSize
import dev.zeffin.ui.components.LocalStillFrames
import dev.zeffin.ui.present.CharacterUi
import dev.zeffin.ui.present.Face
import dev.zeffin.ui.present.Scene
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Zeffin's character, one still frame per expression and tool scene, at the captures' size (the screens that show it
 * are in ScreenshotTest), and the notification icon. The sheets have no words, so they are drawn once, into the
 * English folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-mdpi")
class CharacterScreenshotTest {
    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("build/outputs/roborazzi/en/$name.png") {
            CompositionLocalProvider(LocalLang provides Lang.EN, LocalStillFrames provides true) {
                FbTheme { Box(Modifier.fillMaxSize().background(FbColors.surface).padding(14.dp)) { content() } }
            }
        }

    @Composable
    private fun Tile(dark: Boolean = true, content: @Composable () -> Unit) =
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (dark) FbColors.surfaceRaised else FbColors.accent).padding(10.dp),
            contentAlignment = Alignment.Center) { content() }

    /** Every face on a dark surface, at the stage's size, and on the orange bar (the chat's tail size is in [scenes]). */
    @Test fun faces() = shot("character-01-faces") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in Face.entries.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (face in row) Tile {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        Character(face, height = CharacterSize.stage, look = CharacterLook.Dark(FbColors.surfaceRaised))
                        Tile(dark = false) { Character(face, height = CharacterSize.bar, look = CharacterLook.OnAccent) }
                    }
                }
            }
        }
    }

    /** The tool scenes and the end of a turn, at the card's size and the chat's tail's. */
    @Test fun scenes() = shot("character-02-scenes") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val all: List<Pair<Face, Scene?>> = (listOf<Scene?>(null) + Scene.entries).map { Face.WORKING to it } + (Face.FINISHED to null)
            for (row in all.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((face, scene) in row) Tile {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                        Character(CharacterUi(face, scene), height = CharacterSize.stage, look = CharacterLook.Dark(FbColors.surfaceRaised))
                        Character(CharacterUi(face, scene), height = CharacterSize.tail, look = CharacterLook.Dark(FbColors.surfaceRaised))
                    }
                }
            }
        }
    }

    /** The notification small icon (relay's ic_relay): white in the status bar, and in the shade's orange circle. */
    @Test fun notificationIcon() = shot("notify-07-icon") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            for (size in listOf(24.dp, 48.dp, 96.dp)) Icon(size)
            Box(Modifier.size(40.dp).clip(CircleShape).background(FbColors.accent), contentAlignment = Alignment.Center) {
                Image(painterResource(dev.zeffin.relay.R.drawable.ic_relay), null, Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(FbColors.onAccent))
            }
        }
    }

    @Composable
    private fun Icon(size: Dp) = Image(painterResource(dev.zeffin.relay.R.drawable.ic_relay), null, Modifier.size(size))
}
