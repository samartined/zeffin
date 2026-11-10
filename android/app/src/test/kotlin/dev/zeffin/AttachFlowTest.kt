// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.ChatFixtures as C
import dev.zeffin.attach.AttachError
import dev.zeffin.attach.AttachFlow
import dev.zeffin.attach.AttachTray
import dev.zeffin.attach.ImageScaler
import dev.zeffin.attach.Prepared
import dev.zeffin.core.Lang
import dev.zeffin.link.Attachment
import dev.zeffin.link.AttachmentKind
import dev.zeffin.ui.present.PresentChat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a conversation does with its attachments, without Android: the flow over a real tray and a fake chat. */
class AttachFlowTest {
    private val photo = Prepared("IMG_2041.jpg", "image/jpeg", ByteArray(1000), 2000, 1500)
    private val file = Prepared("notes.pdf", "application/pdf", ByteArray(500), null, null)
    private val scope = TestScope(StandardTestDispatcher())
    private val tray = AttachTray(scope) { _, p, _ ->
        Result.success(Attachment(p.name + "-id", p.name, if (p.isImage) AttachmentKind.IMAGE else AttachmentKind.FILE, p.mediaType,
            p.bytes.size.toLong(), p.width, p.height))
    }

    /** The chat's side: the open conversation, those created, and what was sent. */
    private class FakeChat(var open: String? = null) : AttachFlow.Chat {
        val created = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, List<String>>>()
        var accepts = true
        override fun openId() = open
        override fun newConversation(then: (String) -> Unit) {
            val id = "new-${created.size + 1}"
            created += id
            open = id
            then(id)
        }
        override fun send(text: String, attachments: List<String>): Boolean {
            if (accepts) sent += text to attachments
            return accepts
        }
    }

    private fun flow(chat: FakeChat) = AttachFlow(tray, scope, chat)

    @Test fun filesGoToTheOpenConversation() {
        val chat = FakeChat(open = "c1")
        var to: String? = null
        val uploads = AttachTray(scope) { conversation, p, _ ->
            to = conversation
            Result.success(Attachment("x", p.name, AttachmentKind.FILE, p.mediaType, 1, null, null))
        }
        AttachFlow(uploads, scope, chat).attach(listOf(file), prepare = { Result.success(it) }, nameOf = { it.name })
        scope.advanceUntilIdle()
        assertEquals("c1", to)
        assertTrue(chat.created.isEmpty())
    }

    @Test fun withNoConversationOneIsCreatedFirst() {
        val chat = FakeChat()
        flow(chat).attach(listOf(photo, file), prepare = { Result.success(it) }, nameOf = { it.name })
        scope.advanceUntilIdle()
        assertEquals(listOf("new-1"), chat.created)
        assertEquals(listOf("IMG_2041.jpg-id", "notes.pdf-id"), tray.ids)
    }

    @Test fun nothingPickedCreatesNothing() {
        val chat = FakeChat()
        flow(chat).attach(emptyList<Prepared>(), prepare = { Result.success(it) }, nameOf = { it.name })
        assertTrue(chat.created.isEmpty())
    }

    @Test fun aFileThatCannotBeReadShowsItsErrorByName() {
        val chat = FakeChat(open = "c1")
        flow(chat).attach(listOf("big.heic", "broken.jpg"),
            prepare = { if (it == "big.heic") Result.failure(ImageScaler.TooBig()) else Result.failure(IllegalStateException()) },
            nameOf = { it })
        scope.advanceUntilIdle()
        assertEquals(listOf("big.heic" to AttachError.TooBig, "broken.jpg" to AttachError.Unreadable),
            tray.items.value.map { it.name to it.error })
    }

    @Test fun aFileOverTheLimitsShowsTooBig() {
        val chat = FakeChat(open = "c1")
        val many = List(AttachTray.MAX_PER_MESSAGE + 1) { Prepared("f$it.txt", "text/plain", ByteArray(10), null, null) }
        flow(chat).attach(many, prepare = { Result.success(it) }, nameOf = { it.name })
        scope.advanceUntilIdle()
        assertEquals(AttachError.TooBig, tray.items.value.last().error)
        assertEquals("f${AttachTray.MAX_PER_MESSAGE}.txt", tray.items.value.last().name)
    }

    @Test fun theFlipperScreenGoesLikeAFile() {
        val chat = FakeChat()
        val f = flow(chat)
        f.attachScreen(null)
        assertTrue(chat.created.isEmpty())
        f.attachScreen(Prepared("flipper-screen.png", "image/png", ByteArray(100), 512, 256))
        scope.advanceUntilIdle()
        assertEquals(listOf("flipper-screen.png-id"), tray.ids)
    }

    @Test fun openingAnotherConversationKeepsOnlyItsFiles() {
        val chat = FakeChat(open = "c1")
        val f = flow(chat)
        f.attach(listOf(file), prepare = { Result.success(it) }, nameOf = { it.name })
        scope.advanceUntilIdle()
        f.opened("c2")
        assertTrue(tray.items.value.isEmpty())
    }

    @Test fun aSentMessageTakesTheFilesAndEmptiesTheTray() {
        val chat = FakeChat(open = "c1")
        val f = flow(chat)
        f.attach(listOf(file), prepare = { Result.success(it) }, nameOf = { it.name })
        scope.advanceUntilIdle()
        assertTrue(f.send("Read this"))
        assertEquals(listOf("Read this" to listOf("notes.pdf-id")), chat.sent)
        assertTrue(tray.items.value.isEmpty())
    }

    private val long = "09:14:02.640 ble    disconnected: status 8\n".repeat(500).take(17_230)

    @Test fun aLongPasteBecomesATextFile() {
        val chat = FakeChat(open = "c1")
        val f = flow(chat)
        assertEquals("", f.draftChanged("", long, canAttach = true))
        scope.advanceUntilIdle()
        val u = tray.items.value.single()
        assertEquals(AttachFlow.PASTED to 17_230, u.name to u.chars)
        assertEquals(17_230L, u.bytes)
        assertEquals(listOf("pasted-text.txt-id"), tray.ids)
    }

    @Test fun theFieldKeepsWhatItHeld() {
        val chat = FakeChat(open = "c1")
        val typed = "Here it is. Why does it drop at 09:14?"
        // Pasted at the end, at the start, and over a selection: the field keeps what it held, the paste goes whole.
        assertEquals(typed, flow(chat).draftChanged(typed, typed + long, canAttach = true))
        assertEquals(typed, flow(chat).draftChanged(typed, long + typed, canAttach = true))
        assertEquals(typed, flow(chat).draftChanged(typed, "Here it is. " + long + " at 09:14?", canAttach = true))
        scope.advanceUntilIdle()
        assertEquals(listOf(17_230, 17_230, 17_230), tray.items.value.map { it.chars })
    }

    @Test fun aPasteThatFitsStaysInTheField() {
        val chat = FakeChat(open = "c1")
        val text = "a".repeat(15_000)
        assertEquals(text + "b".repeat(1_000), flow(chat).draftChanged(text, text + "b".repeat(1_000), canAttach = true))
        assertTrue(tray.items.value.isEmpty())
    }

    @Test fun aPasteOverTheLimitWithTheFieldCountsWhatItHeld() {
        val chat = FakeChat(open = "c1")
        val typed = "a".repeat(500)
        val paste = "b".repeat(15_600)  // fits alone, not with what the field held
        assertEquals(typed, flow(chat).draftChanged(typed, typed + paste, canAttach = true))
        scope.advanceUntilIdle()
        assertEquals(15_600, tray.items.value.single().chars)
    }

    @Test fun typingPastTheLimitStaysInTheField() {
        val chat = FakeChat(open = "c1")
        val full = "a".repeat(16_000)
        assertEquals(full + "b", flow(chat).draftChanged(full, full + "b", canAttach = true))
        assertTrue(tray.items.value.isEmpty())
    }

    @Test fun withNoFilesAllowedTheLongPasteStaysInTheField() {
        val chat = FakeChat(open = "c1")
        assertEquals(long, flow(chat).draftChanged("", long, canAttach = false))
        assertTrue(tray.items.value.isEmpty())
        assertTrue(chat.created.isEmpty())
    }

    @Test fun withRemoteControlOnTheLongPasteFollowsTheServer() {
        val present = PresentChat(Lang.EN)
        val files = present.chatUi(C.relayLogRemoteFiles(), emptyMap()).composer.canAttach
        val older = present.chatUi(C.relayLogRemote(), emptyMap()).composer.canAttach
        val chat = FakeChat(open = "c1")
        assertEquals(long, flow(chat).draftChanged("", long, canAttach = older))  // an older server: it stays
        assertTrue(tray.items.value.isEmpty())
        assertEquals("", flow(chat).draftChanged("", long, canAttach = files))  // a server that takes it: a file
        scope.advanceUntilIdle()
        assertEquals(listOf("pasted-text.txt"), tray.items.value.map { it.name })
    }

    @Test fun morePastesAreNumbered() {
        val chat = FakeChat()
        val f = flow(chat)
        f.draftChanged("", long, canAttach = true)
        f.draftChanged("", long, canAttach = true)
        f.draftChanged("", long, canAttach = true)
        scope.advanceUntilIdle()
        assertEquals(listOf("pasted-text.txt", "pasted-text-2.txt", "pasted-text-3.txt"), tray.items.value.map { it.name })
        assertEquals(listOf("new-1"), chat.created)  // the first paste created the conversation, the others went to it
    }

    @Test fun aRefusedMessageKeepsTheFiles() {
        val chat = FakeChat(open = "c1").apply { accepts = false }
        val f = flow(chat)
        f.attach(listOf(file), prepare = { Result.success(it) }, nameOf = { it.name })
        scope.advanceUntilIdle()
        assertFalse(f.send("Read this"))
        assertEquals(listOf("notes.pdf-id"), tray.ids)
        assertNull(tray.items.value.single().error)
    }
}
