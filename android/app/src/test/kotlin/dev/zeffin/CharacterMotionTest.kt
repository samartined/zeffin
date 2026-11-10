// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.zeffin.core.Lang
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.Character
import dev.zeffin.ui.components.CharacterSize
import dev.zeffin.ui.components.LocalStillFrames
import dev.zeffin.ui.components.animationsOn
import dev.zeffin.ui.present.Face
import dev.zeffin.ui.theme.FbTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The character follows Android's animator duration scale ("Remove animations"): at 0 it draws its still frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-mdpi")
class CharacterMotionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Android's "Remove animations" (animator duration scale 0): the face draws its still frame and nothing moves. */
    @Test fun removeAnimationsDrawsTheStillFrame() {
        val resolver = rule.activity.contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertFalse(animationsOn(rule.activity))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLang provides Lang.EN) {
                FbTheme {
                    Column {
                        for (face in Face.entries) Row {
                            // Whole-dp boxes, so both faces sit on the same pixel grid.
                            Box(Modifier.size(88.dp, 64.dp)) {
                                CompositionLocalProvider(LocalStillFrames provides true) {
                                    Character(face, Modifier.testTag("still-$face"), CharacterSize.stage)
                                }
                            }
                            Box(Modifier.size(88.dp, 64.dp)) { Character(face, Modifier.testTag("live-$face"), CharacterSize.stage) }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_250)
        val moved = mutableMapOf<Face, Int>()
        for (face in Face.entries) {
            val still = rule.onNodeWithTag("still-$face").captureToImage().toPixelMap()
            val live = rule.onNodeWithTag("live-$face").captureToImage().toPixelMap()
            assertEquals(still.width, live.width)
            // Anti-aliasing differs by a few levels between two places on screen; a face that moved swaps orange and
            // black, a change of 255. Only a change of more than 40 counts.
            val differ = (0 until still.width).sumOf { x ->
                (0 until still.height).count { y ->
                    val a = still[x, y]
                    val b = live[x, y]
                    maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue)) > 40f / 255f
                }
            }
            if (differ > 0) moved[face] = differ
        }
        assertEquals("pixels that moved with animations off", emptyMap<Face, Int>(), moved)
    }

    @Test fun animationsFollowTheSystemScale() {
        val resolver = rule.activity.contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertTrue(animationsOn(rule.activity))
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
        assertTrue(animationsOn(rule.activity))
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertFalse(animationsOn(rule.activity))
    }
}
