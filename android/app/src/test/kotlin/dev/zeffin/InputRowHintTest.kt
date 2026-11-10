// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import dev.zeffin.core.Lang
import dev.zeffin.Fixtures as F
import dev.zeffin.ChatFixtures as C
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.GateActions
import dev.zeffin.ui.components.InputRow
import dev.zeffin.ui.components.LocalStillFrames
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.present.PresentFlipperRemote
import dev.zeffin.ui.screens.HomeActions
import dev.zeffin.ui.screens.HomeScreen
import dev.zeffin.ui.screens.HomeView
import dev.zeffin.ui.theme.FbTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "Scroll to read it all" shows only under a box that really hides part of its value (the permission cards). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-mdpi")
class InputRowHintTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val more = "Scroll to read it all"
    private fun hints() = rule.onAllNodesWithText(more).fetchSemanticsNodes().size

    private fun rows(vararg values: String) = rule.setContent {
        CompositionLocalProvider(LocalLang provides Lang.EN, LocalStillFrames provides true) {
            FbTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (v in values) InputRow("File", v, more, maxHeight = 120.dp)
                }
            }
        }
    }

    @Test fun aValueShownWholeHasNoHint() {
        rows("/home/demo/projects/zeffin-agent/ir/living-room-tv.ir is a long path", "one\ntwo", "a")
        rule.waitForIdle()
        assertEquals(0, hints())
    }

    /** Frame by frame, as a screenshot or a slow phone sees it: never a hint under a value the box shows whole. */
    @Test fun noFrameHintsAValueShownWhole() {
        rule.mainClock.autoAdvance = false
        rows("/home/demo/projects/zeffin-agent/ir/living-room-tv.ir is a long path", "one\ntwo")
        repeat(10) { frame ->
            rule.mainClock.advanceTimeByFrame()
            assertEquals("frame $frame", 0, hints())
        }
    }

    @Test fun aValueTallerThanItsBoxHasTheHint() {
        rows((1..30).joinToString("\n") { "line $it" })
        rule.waitForIdle()
        assertEquals(1, hints())
    }

    @Test fun homesEditCardHintsOnlyWhereSomethingIsHidden() {
        val present = Present(Lang.EN)
        rule.setContent {
            CompositionLocalProvider(LocalLang provides Lang.EN, LocalStillFrames provides true) {
                FbTheme {
                    HomeScreen(present.homeUi(F.relayRunning, F.link, F.frame, F.settings, F.pairing, F.now), HomeView(F.frame.data, 0, false),
                        HomeActions(GateActions({}, {}, PresentFlipperRemote(Lang.EN).homeButton to {}), {}, {}, {}),
                        last = PresentChat(Lang.EN).lastConversation(C.askingEdit()))
                }
            }
        }
        rule.waitForIdle()
        assertEquals(0, hints())
    }
}
