// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.zeffin.ui.MarkdownText
import dev.zeffin.ui.theme.FbTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent's text nested thousands of levels deep is drawn, not a crash (security scan before 1.0.0): the app closed
 * each time such a conversation opened, overflowing the stack while drawing. The levels past the cap are drawn flat.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-mdpi")
class DeepMarkdownTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun thousandsOfNestedQuotesAndListsAreDrawn() {
        val quotes = "> ".repeat(5000) + "deep quote"
        val lists = (0 until 3000).joinToString("\n") { "  ".repeat(it) + "- item $it" }
        val mixed = (0 until 4000).joinToString("") { if (it % 2 == 0) "> " else "- " } + "mixed end"
        rule.setContent {
            FbTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MarkdownText(quotes)
                    MarkdownText(lists)
                    MarkdownText(mixed)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("deep quote", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("item 2999", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("mixed end", substring = true).performScrollTo().assertExists()
    }
}
