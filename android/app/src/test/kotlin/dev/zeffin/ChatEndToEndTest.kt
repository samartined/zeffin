// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.attach.Prepared
import dev.zeffin.attach.Uploader
import dev.zeffin.link.AppLink
import dev.zeffin.link.AttachmentKind
import dev.zeffin.link.ChatEvent
import dev.zeffin.link.ChatStore
import dev.zeffin.link.LinkEvent
import dev.zeffin.link.PermissionAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * The chat end to end: the real AppLink and ChatStore against the real server, whose `claude` is the fake one
 * from server/tests/fake_claude.py. android/scripts/e2e.sh starts them and sets FB_E2E_CHAT=1; without it this
 * test is skipped.
 */
class ChatEndToEndTest {
    private val url = System.getenv("FB_E2E_URL")
    private val token = System.getenv("FB_E2E_APP_TOKEN")
    /** The pin the pairing QR code would carry: the test pins the real server's key, as the app does. */
    private val key = System.getenv("FB_E2E_KEY")?.let(dev.zeffin.core.ServerAuth.PinnedKey::parse)
    private val link = AppLink(testHttp(key = key))
    private val chat = ChatStore(link::sendJson)

    /** One thread for the chat, as the app's main thread: the link's events are handled there, and so are the commands. */
    private val thread = Executors.newSingleThreadExecutor()
    private val confined = thread.asCoroutineDispatcher()
    private val collector = CoroutineScope(confined).launch(start = CoroutineStart.UNDISPATCHED) {
        link.events.collect { e ->
            when (e) {
                is LinkEvent.Hello -> chat.onHello(e.features, e.hello)
                is LinkEvent.Chat -> chat.onMessage(e.message)
                LinkEvent.Disconnected -> chat.onDisconnected()
                else -> {}
            }
        }
    }

    private fun <T> onChat(block: () -> T): T = runBlocking(confined) { block() }

    @After fun stop() {
        link.stop()
        collector.cancel()
        thread.shutdown()
    }

    private fun waitFor(what: String, seconds: Int = 20, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what: ${chat.state.value}")
            Thread.sleep(50)
        }
    }

    private fun events() = chat.state.value.open?.events.orEmpty()
    private fun turnsEnded() = events().count { it is ChatEvent.TurnEnd }

    @Test fun chatsWithTheFakeClaude() {
        assumeTrue(url != null && token != null && key != null && System.getenv("FB_E2E_CHAT") == "1")
        link.start(url!!.replace("https://", "wss://") + "/app", token)
        waitFor("the chat status") { chat.state.value.status?.problem == null && chat.state.value.status?.ready == true }
        assertTrue(chat.state.value.attachments)

        assertTrue(onChat { chat.send("Please remember the number 4817.") })
        waitFor("the first turn") { turnsEnded() == 1 }
        val text = events().filterIsInstance<ChatEvent.Text>().joinToString { it.text }
        assertTrue(text, text.contains("4817"))
        assertEquals("done", events().filterIsInstance<ChatEvent.TurnEnd>().last().outcome)
        assertTrue(chat.streaming.value.isEmpty())

        assertTrue(onChat { chat.send("Use the Write tool to create fb-e2e.txt containing hello") })
        waitFor("a permission request") { events().any { it is ChatEvent.Permission } }
        val permission = events().filterIsInstance<ChatEvent.Permission>().last()
        assertEquals("Write", permission.tool)
        onChat { chat.answer(permission.id, PermissionAnswer.ONCE) }
        waitFor("the second turn") { turnsEnded() == 2 }
        assertTrue(events().any { it is ChatEvent.PermissionEnd && it.answer == "once" })
        assertEquals("done", events().filterIsInstance<ChatEvent.TurnEnd>().last().outcome)

        // An image, uploaded as the app does it and sent with a message: the fake claude "sees" its colour.
        val conversation = chat.state.value.open!!.id
        val red = Prepared("red.png", "image/png", redPng(), 8, 8)
        val uploaded = runBlocking { Uploader(testHttp(key = key)).upload(url.toHttpUrl(), token, conversation, red) {} }.getOrThrow()
        assertEquals(AttachmentKind.IMAGE, uploaded.kind)
        assertTrue(onChat { chat.send("what colour is this?", listOf(uploaded.id)) })
        waitFor("the third turn") { turnsEnded() == 3 }
        assertEquals(listOf("red.png"), events().filterIsInstance<ChatEvent.User>().last().attachments.map { it.name })
        assertEquals("Red.", events().filterIsInstance<ChatEvent.Text>().last().text)
    }

    /** An 8x8 red PNG, made without Android. */
    private fun redPng(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 8) for (y in 0 until 8) image.setRGB(x, y, 0xD03030)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
