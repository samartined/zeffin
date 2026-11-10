// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.zeffin.core.Lang
import dev.zeffin.link.AttachmentKind
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.LocalStillFrames
import dev.zeffin.ui.screens.AttachSheet
import dev.zeffin.ui.screens.ChatCallbacks
import dev.zeffin.ui.screens.ChatSheet
import dev.zeffin.ui.screens.ConversationChat
import dev.zeffin.ui.screens.ConversationScreen
import dev.zeffin.ui.screens.ImageViewer
import dev.zeffin.ui.theme.FbTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.zeffin.ChatFixtures as C
import dev.zeffin.Fixtures as F
import dev.zeffin.ui.present.AttachmentUi
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.PresentChat

/**
 * Back closes what is open over the conversation, one level at a time, and leaves the conversation only when nothing is.
 * The outer handler stands for the NavHost's, which is registered before the conversation's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-mdpi")
class ConversationBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var sheet by mutableStateOf(ChatSheet.NONE)
    private var viewing by mutableStateOf<AttachmentUi?>(null)
    private var left = 0

    private fun show() = rule.setContent {
        val lang = Lang.EN
        BackHandler { left++ }
        CompositionLocalProvider(LocalLang provides lang, LocalStillFrames provides true) {
            FbTheme {
                val ui = Present(lang).homeUi(F.relayRunning, F.link, F.frame, F.settings, F.pairing, F.now)
                val chat = PresentChat(lang).chatUi(C.writing(), C.streaming)
                ConversationScreen(ui, ConversationChat(chat, "", ChatCallbacks(), sheet, onSheet = { sheet = it },
                    attachSheet = { AttachSheet(flipperScreen = true) { sheet = ChatSheet.NONE } },
                    overlay = { viewing?.let { ImageViewer(it) { viewing = null } } }),
                    F.frame.data, 0, false, false, {}, { left++ })
            }
        }
    }

    private fun back() {
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test fun backClosesTheSettingsInsteadOfLeaving() {
        show()
        rule.onNodeWithContentDescription("Conversation settings").performClick()
        assertEquals(ChatSheet.SETTINGS, sheet)
        back()
        assertEquals(ChatSheet.NONE, sheet)
        assertEquals(0, left)
    }

    @Test fun backFromAListReturnsToTheSettings() {
        sheet = ChatSheet.MODEL
        show()
        back()
        assertEquals(ChatSheet.SETTINGS, sheet)
        back()
        assertEquals(ChatSheet.NONE, sheet)
        assertEquals(0, left)
    }

    @Test fun theSettingsButtonClosesThePanel() {
        sheet = ChatSheet.SETTINGS
        show()
        rule.onNodeWithText("Conversation settings").assertExists()
        rule.onNodeWithContentDescription("Conversation settings").performClick()
        assertEquals(ChatSheet.NONE, sheet)
    }

    @Test fun backClosesTheAttachSheet() {
        sheet = ChatSheet.ATTACH
        show()
        back()
        assertEquals(ChatSheet.NONE, sheet)
        assertEquals(0, left)
    }

    @Test fun backClosesTheImageViewer() {
        viewing = AttachmentUi(C.ID, "a", "IMG_2041.jpg", AttachmentKind.IMAGE, "1.4 MB", 2000, 1500)
        show()
        back()
        assertEquals(null, viewing)
        assertEquals(0, left)
    }

    @Test fun backLeavesWhenNothingIsOpen() {
        show()
        back()
        assertEquals(1, left)
    }
}
