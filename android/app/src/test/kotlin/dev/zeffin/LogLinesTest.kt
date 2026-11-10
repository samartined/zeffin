// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.zeffin.core.Lang
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.LocalStillFrames
import dev.zeffin.ui.screens.LogScreen
import dev.zeffin.ui.theme.FbTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.zeffin.ui.present.LogLineUi
import dev.zeffin.ui.present.LogUi
import dev.zeffin.ui.present.Tone

/** Log lines are whole: a long one scrolls sideways instead of ending in "…", and each event is one line of text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)  // real text measurement
@Config(sdk = [36], qualifiers = "w390dp-h844dp-mdpi")
class LogLinesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val long = "Server: the server does not accept the phone token; pair the app again from Settings, Server"

    @Test fun aLongLineIsWholeAndScrollsSideways() {
        rule.setContent {
            CompositionLocalProvider(LocalLang provides Lang.EN, LocalStillFrames provides true) {
                FbTheme {
                    LogScreen(LogUi(0, listOf(LogLineUi("13:53:31", "error", Tone.DANGER, long), LogLineUi("13:53:35", "warning", Tone.WARNING, "Retry 2 in 4 s"))),
                        {}, {}, {}, {})
                }
            }
        }
        rule.onNodeWithText("13:53:31  error    $long").assertExists()
        rule.onNodeWithText("13:53:35  warning  Retry 2 in 4 s").assertExists()
        val range = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() }
        assertTrue("sideways ranges: $range", range.any { it > 0f })
    }
}
