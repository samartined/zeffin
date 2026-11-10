// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import dev.zeffin.core.Lang
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.LocalStillFrames
import dev.zeffin.ui.screens.ChatCallbacks
import dev.zeffin.ui.screens.ConversationChat
import dev.zeffin.ui.screens.ConversationScreen
import dev.zeffin.ui.theme.FbTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.zeffin.ChatFixtures as C
import dev.zeffin.Fixtures as F
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.PresentChat

/** Sending while reading further up brings the end of the chat into view, so the answer is not missed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w390dp-h500dp-mdpi")
class ChatScrollTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun sendingScrollsToTheEnd() {
        var sent = 0
        val lang = Lang.EN
        val state = C.markdown()
        rule.setContent {
            CompositionLocalProvider(LocalLang provides lang, LocalStillFrames provides true) {
                FbTheme {
                    val ui = Present(lang).homeUi(F.relayRunning, F.link, F.frame, F.settings, F.pairing, F.now)
                    ConversationScreen(ui, ConversationChat(PresentChat(lang).chatUi(state, emptyMap()), "Thanks", ChatCallbacks(onSend = { sent++ })),
                        F.frame.data, 0, false, false, {}, {})
                }
            }
        }
        val list = rule.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(PresentChat(lang).chatUi(state, emptyMap()).items.size - 1)
        rule.onNodeWithText("What is in demo-tv.ir? Make me a table of its buttons.").assertIsDisplayed()
        rule.onNode(SemanticsMatcher("send") { n ->
            n.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Send"
        }).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(1, sent)
        rule.onNodeWithText("What is in demo-tv.ir? Make me a table of its buttons.").assertDoesNotExist()
    }
}
