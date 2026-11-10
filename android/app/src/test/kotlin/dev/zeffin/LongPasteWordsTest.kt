// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.ChatFixtures as C
import dev.zeffin.attach.Upload
import dev.zeffin.core.Lang
import dev.zeffin.link.Attachment
import dev.zeffin.link.AttachmentKind
import dev.zeffin.ui.present.Fmt
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.present.PresentRemote
import dev.zeffin.ui.present.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the screen says about a long paste and a message over the limit, in both languages. */
class LongPasteWordsTest {
    private val en = PresentChat(Lang.EN)
    private val es = PresentChat(Lang.ES)
    private val pasted = Upload(1, "pasted-text.txt", AttachmentKind.FILE, 17_230, null, progress = 0.5f, chars = 17_230)

    @Test fun countsAreGroupedAsEachLanguageWritesThem() {
        assertEquals("17,230", Fmt.count(Lang.EN, 17_230))
        assertEquals("17.230", Fmt.count(Lang.ES, 17_230))
        assertEquals("980", Fmt.count(Lang.EN, 980))
        assertEquals("1,000,000", Fmt.count(Lang.EN, 1_000_000))
    }

    @Test fun aMessageThatFitsSaysNothing() {
        assertNull(en.tooLong(16_000, null))
    }

    @Test fun typedPastTheLimitStatesTheLimitAndTheCount() {
        val b = en.tooLong(16_012, null)!!
        assertEquals(Tone.DANGER, b.tone)
        assertEquals("The message is too long", b.title)
        assertEquals("Messages can have at most 16,000 characters; this one has 16,012.", b.text)
        assertEquals("16,012", b.strong)
        assertTrue(b.actions.isEmpty())
        assertEquals("Los mensajes pueden tener como máximo 16.000 caracteres; este tiene 16.012.", es.tooLong(16_012, null)!!.text)
    }

    @Test fun withRemoteControlOnItSaysWhatToDo() {
        assertEquals("Messages can have at most 16,000 characters; this one has 17,230. With Remote Control on, messages carry no " +
            "attachments: shorten it, or turn Remote Control off to send it as a text file.",
            en.tooLong(17_230, PresentRemote(Lang.EN, C.remote))!!.text)
        assertTrue(es.tooLong(17_230, PresentRemote(Lang.ES, C.remote))!!.text!!.endsWith(
            "Con Remote Control activo, los mensajes no llevan adjuntos: acórtalo o apaga Remote Control para enviarlo como fichero de texto."))
    }

    @Test fun withRemoteControlOnTheErrorNamesItOnlyForAnOlderServer() {
        val older = C.relayLogRemote()
        assertTrue(en.composed(en.chatUi(older, emptyMap()), older, emptyList(), C.relayLogText).banner!!.text!!
            .endsWith("turn Remote Control off to send it as a text file."))
        val files = C.relayLogRemoteFiles()
        assertEquals("Messages can have at most 16,000 characters; this one has 17,230.",
            en.composed(en.chatUi(files, emptyMap()), files, emptyList(), C.relayLogText).banner!!.text)
    }

    @Test fun theComposerWaitsWhileTheTextIsTooLong() {
        val state = C.writing()
        val chat = en.chatUi(state, emptyMap())
        val long = en.composed(chat, state, emptyList(), "a".repeat(16_001))
        assertTrue(long.composer.tooLong)
        assertEquals("The message is too long", long.banner!!.title)
        val fits = en.composed(chat, state, emptyList(), "a".repeat(16_000))
        assertFalse(fits.composer.tooLong)
        assertEquals(chat.banner, fits.banner)
    }

    @Test fun thePasteChipCountsCharactersAndTheTraySaysWhy() {
        val chip = en.trayUi(listOf(pasted)).single()
        assertEquals("pasted-text.txt" to "17,230 characters", chip.chip.name to chip.chip.meta)
        assertEquals(0.5f, chip.progress)
        assertEquals("17.230 caracteres", es.trayUi(listOf(pasted)).single().chip.meta)
        assertEquals("A paste over 16,000 characters goes as a text file the agent reads.", en.trayNote(listOf(pasted)))
        assertEquals("Un pegado de más de 16.000 caracteres va como fichero de texto que el agente lee.", es.trayNote(listOf(pasted)))
        assertNull(en.trayNote(listOf(pasted.copy(chars = null))))
    }

    @Test fun aSentTextFileIsTxt() {
        val a = Attachment("3d4e5f60718293a4b5c6d7e8f9011223", "pasted-text.txt", AttachmentKind.FILE, "text/plain", 17_230, null, null)
        assertEquals("TXT · 16 KB", en.attachment(C.ID, a).meta)
    }
}
